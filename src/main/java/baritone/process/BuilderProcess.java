package baritone.process;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalRunAway;
import baritone.api.process.IBuilderProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.schematic.ISchematic;
import baritone.api.schematic.IStaticSchematic;
import baritone.api.schematic.format.ISchematicFormat;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.Rotation;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.builder.BlockStateResolver;
import baritone.utils.builder.BlockStateResolver.PlacementPlan;
import baritone.utils.builder.PathingFailureTracker;
import baritone.utils.builder.PlacementScheduler;
import baritone.utils.builder.PlacementScheduler.Target;
import baritone.utils.builder.SmoothLookHelper;
import baritone.utils.schematic.SchematicSystem;
import baritone.utils.schematic.litematica.LitematicaHelper;
import baritone.utils.schematic.schematica.SchematicaHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.util.Tuple;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.File;
import java.io.FileInputStream;
import java.util.*;

/**
 * BuilderProcess for MC 1.21.4 (Fabric, Mojang mappings).
 *
 * BUG 1: CALC_FAILED -> skip ladder (threshold + retry later), never stalls.
 * BUG 3: server-verified placements, configurable delay, ghost re-queue.
 * BUG 4: exact directional placement via vanilla getStateForPlacement() simulation.
 * BUG 5: Y-first build order (foundation -> walls -> roof).
 */
public final class BuilderProcess extends BaritoneProcessHelper implements IBuilderProcess {

    private final Minecraft mc = Minecraft.getInstance();
    private final PlacementScheduler scheduler = new PlacementScheduler();

    // ---- build state ----
    private String name = "build";
    private ISchematic schematic;
    private Vec3i origin;
    private BetterBlockPos clearMin, clearMax; // #cleararea mode
    private boolean active;
    private boolean paused;

    // ---- layer state (IMPROVEMENT 5) ----
    private boolean layerMode;
    private int layerBase;          // offset in schematic Y
    private int layerHeight = 1;
    private boolean topDown;

    // ---- work state ----
    private List<Target> pending = new ArrayList<>();
    private final Set<BlockPos> resigned = new HashSet<>(); // permanently given up
    private int rescanCountdown;
    private PlacementPlan pendingPlan; // plan we are currently rotating towards
    private BlockPos breaking;
    private int breakTicks;
    private Goal currentGoal;
    private BetterBlockPos currentGoalTarget;
    private int calcFailStreak;
    private int starvedTicks;

    // ---- missing materials ----
    private final Map<Block, Integer> missing = new HashMap<>();
    private boolean missingReported;

    // ---- sneak (GrimAC-safe: sneak must reach the server BEFORE the click) ----
    private boolean sneakHeld;
    private int sneakTicks;
    private static final int MIN_SNEAK_TICKS = 2;
    // ---- rotation settle (GrimAC-safe: rotation packet must arrive before interact) ----
    private int rotationSettleTicks;

    private static final int RESCAN_INTERVAL = 10;
    private static final float ROTATION_TOLERANCE = 2.0f;
    private static final int SKIP_RADIUS = 16;
    private static final int MAX_BREAK_TICKS = 200;
    private static final int STARVED_TICK_LIMIT = 200; // ~10 seconds before giving up on unreachable
    /** Max ticks of total inactivity (no place/break/path) before force-advancing. */
    private static final int ANTI_STALL_TICKS = 300; // 15 seconds
    private int inactivityTicks;
    /** Total targets at start of this layer/build (for progress %). */
    private int totalTargetsInitial;

    public BuilderProcess(Baritone baritone) {
        super(baritone);
    }

    // =====================================================================
    // Public API (BuilderCommand / BuildLayerCommand call these)
    // =====================================================================

    @Override
    public void build(String name, ISchematic schematic, Vec3i origin) {
        this.name = name;
        this.schematic = schematic;
        this.origin = origin;
        this.clearMin = this.clearMax = null;
        this.layerMode = Baritone.settings().buildInLayers.value;
        this.layerHeight = Math.max(1, Baritone.settings().layerHeight.value);
        this.topDown = Baritone.settings().layerOrder.value;
        this.layerBase = this.topDown
                ? Math.max(0, schematic.height() - this.layerHeight)
                : 0;
        start();
    }

    @Override
    public boolean build(String name, File schematic, Vec3i origin) {
        Optional<ISchematicFormat> format = SchematicSystem.INSTANCE.getByFile(schematic);
        if (!format.isPresent()) {
            return false;
        }
        IStaticSchematic parsed;
        try {
            parsed = format.get().parse(new FileInputStream(schematic));
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        build(name, parsed, origin);
        return true;
    }

    @Override
    public void buildOpenSchematic() {
        if (!SchematicaHelper.isSchematicaPresent()) {
            logDirect("Schematica is not present");
            return;
        }
        Optional<Tuple<IStaticSchematic, BlockPos>> opt = SchematicaHelper.getOpenSchematic();
        if (!opt.isPresent()) {
            logDirect("No schematic open");
            return;
        }
        Tuple<IStaticSchematic, BlockPos> tuple = opt.get();
        build(tuple.getA().toString(), tuple.getA(), tuple.getB());
    }

    @Override
    public void buildOpenLitematic(int i) {
        if (!LitematicaHelper.isLitematicaPresent()) {
            logDirect("Litematica mod is not installed. You can build .litematic files directly with: #litematica <filename> or #build <filename.litematic>");
            return;
        }
        if (!LitematicaHelper.hasLoadedSchematic(i)) {
            if (i >= 0) {
                logDirect("No placement loaded at index " + (i + 1) + ". Use '#litematica list' to see loaded placements.");
            } else {
                logDirect("No active or selected placement found in Litematica. Make sure a schematic is loaded in Litematica GUI (press M -> Load Schematics -> Create Placement).");
            }
            return;
        }
        Tuple<IStaticSchematic, Vec3i> t = null;
        try {
            t = LitematicaHelper.getSchematic(i);
        } catch (Throwable ex) {
            logDirect("Error loading schematic from Litematica: " + ex.getMessage());
            logDebug("LitematicaHelper getSchematic error: " + ex.getMessage());
        }
        if (t == null) {
            logDirect("Could not load schematic from Litematica. Make sure the placement is enabled and visible in the world.");
            return;
        }
        build(t.getA().toString(), t.getA(), t.getB());
        // Auto-apply layer mode if configured (buildInLayers setting)
        if (Baritone.settings().buildInLayers.value) {
            applyLayerMode();
            logDirect("Layer mode: " + (topDown ? "top→bottom" : "bottom→top")
                    + ", height=" + layerHeight
                    + ". Use #stop to cancel, layers complete automatically.");
        }
    }

    /** IMPROVEMENT 5: explicit layer build. */
    public void buildLayer(String name, ISchematic schematic, Vec3i origin,
                           int layerBase, int layerHeight, boolean topDown) {
        this.name = name;
        this.schematic = schematic;
        this.origin = origin;
        this.clearMin = this.clearMax = null;
        this.layerMode = true;
        this.layerHeight = Math.max(1, layerHeight);
        this.topDown = topDown;
        this.layerBase = Mth.clamp(layerBase, 0, Math.max(0, schematic.height() - 1));
        start();
    }

    /** Switch the RUNNING build into layer mode (#buildlayer). */
    public boolean applyLayerMode() {
        if (schematic == null) {
            return false;
        }
        this.layerMode = true;
        this.layerHeight = Math.max(1, Baritone.settings().layerHeight.value);
        this.topDown = Baritone.settings().layerOrder.value;
        this.layerBase = this.topDown
                ? Math.max(0, schematic.height() - this.layerHeight)
                : 0;
        this.rescanCountdown = 0;
        return true;
    }

    @Override
    public void clearArea(BlockPos a, BlockPos b) {
        this.name = "clear";
        this.schematic = null;
        this.origin = null;
        this.layerMode = false;
        this.clearMin = new BetterBlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        this.clearMax = new BetterBlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        start();
    }

    private void start() {
        this.active = true;
        this.paused = false;
        this.calcFailStreak = 0;
        this.starvedTicks = 0;
        this.inactivityTicks = 0;
        this.rescanCountdown = 0;
        this.scheduler.reset();
        this.resigned.clear();
        this.missing.clear();
        this.missingReported = false;
        this.pendingPlan = null;
        this.rotationSettleTicks = 0;
        this.breaking = null;
        this.currentGoal = null;
        this.currentGoalTarget = null;
        logDirect("Building " + name + (layerMode
                ? " in layers of " + layerHeight + " (" + (topDown ? "top->bottom" : "bottom->top") + ")"
                : ""));
    }

    @Override
    public void pause()   { paused = true;  setSneakHeld(false); }

    @Override
    public void resume()  { paused = false; }

    @Override
    public boolean isPaused() { return paused; }

    public int getPendingCount() { return pending.size(); }

    public BlockState placeAt(int x, int y, int z, BlockState current) {
        if (schematic == null || origin == null) {
            return null;
        }
        int relX = x - origin.getX();
        int relY = y - origin.getY();
        int relZ = z - origin.getZ();
        if (schematic.inSchematic(relX, relY, relZ, current)) {
            return schematic.desiredState(relX, relY, relZ, current, Collections.emptyList());
        }
        return null;
    }

    public boolean placementPlausible(BlockPos pos, BlockState state) {
        net.minecraft.world.phys.shapes.VoxelShape voxelshape = state.getCollisionShape(ctx.world(), pos);
        return voxelshape.isEmpty() || ctx.world().isUnobstructed(null, voxelshape.move(pos.getX(), pos.getY(), pos.getZ()));
    }

    public static class GoalBreak extends baritone.api.pathing.goals.GoalGetToBlock {
        public GoalBreak(BlockPos pos) {
            super(pos);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            if (y > this.y) {
                return false;
            }
            return super.isInGoal(x, y, z);
        }
    }

    @Override
    public List<BlockState> getApproxPlaceable() {
        return Collections.emptyList();
    }

    @Override
    public Optional<Integer> getMinLayer() {
        return layerMode ? Optional.of(layerBase) : Optional.empty();
    }

    @Override
    public Optional<Integer> getMaxLayer() {
        return layerMode ? Optional.of(layerBase + layerHeight) : Optional.empty();
    }

    // =====================================================================
    // IBaritoneProcess
    // =====================================================================

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public String displayName0() {
        if (!active) {
            return "Builder (inactive)";
        }
        String base = "Builder: " + name;
        if (layerMode && origin != null) {
            base += " (layer y=" + (origin.getY() + layerBase) + ")";
        }
        return base + " — " + pending.size() + " left";
    }

    @Override
    public double priority() {
        return DEFAULT_PRIORITY;
    }

    @Override
    public void onLostControl() {
        // CRITICAL FIX: must set active=false or process manager throws
        // "stayed active after being cancelled" IllegalStateException
        active = false;
        paused = false;
        pendingPlan = null;
        breaking = null;
        pending = new ArrayList<>();
        totalTargetsInitial = 0;
        stopBreaking();
        setSneakHeld(false);
        scheduler.reset();
        if (baritone.getLookBehavior() != null) {
            baritone.getLookBehavior().updateTarget(null, false);
        }
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        return onTick(calcFailed);
    }

    public PathingCommand onTick(boolean calcFailed) {
        LocalPlayer player = ctx.player();
        if (player == null || !active || paused) {
            return null;
        }
        Level world = ctx.world();
        if (world == null) {
            return null;
        }
        if (schematic == null && clearMin == null) {
            return null;
        }

        scheduler.tick(world, (int) world.getGameTime());

        // ---- FIX 3: self-unstuck — if player is inside a pending target, move away ----
        PathingCommand unstuckCmd = checkSelfUnstuck(player, world);
        if (unstuckCmd != null) {
            return unstuckCmd;
        }

        // ---- periodic schematic vs world re-scan (BUG 5 ordering) ----
        if (--rescanCountdown <= 0) {
            searchForPlacables(world);
        }
        if (pending.isEmpty()) {
            advanceLayerOrFinish();
            return null;
        }

        // ---- Anti-stall: only count when player is truly idle (not moving or pathing) ----
        if ((baritone.getPathingBehavior() != null && baritone.getPathingBehavior().isPathing())
                || player.getDeltaMovement().lengthSqr() > 0.001) {
            inactivityTicks = 0;
        } else {
            inactivityTicks++;
        }
        if (inactivityTicks >= ANTI_STALL_TICKS) {
            scheduler.forceResetVerification();
            inactivityTicks = 0;
            if (currentGoalTarget != null) {
                // Only skip the specific stalled target, do NOT mark the whole layer unreachable!
                scheduler.markUnreachable(currentGoalTarget, scheduler.currentTick() + 100);
                currentGoalTarget = null;
                currentGoal = null;
            }
        }

        // ---- BUG 1: CALC_FAILED ladder ----
        boolean calcFail = calcFailed
                || (currentGoalTarget != null
                    && PathingFailureTracker.get(baritone).getConsecutiveFailures()
                       >= Baritone.settings().calcFailSkipThreshold.value);
        if (calcFail) {
            if (++calcFailStreak >= 2) { // reduced from 3 to 2 for faster recovery
                skipAroundFailedGoal();
            }
        } else {
            calcFailStreak = 0;
        }

        // ---- phase 1: break blocks standing where blocks must go ----
        Target breakTarget = findBreakTargetInReach(world, player);
        if (breakTarget != null) {
            inactivityTicks = 0;
            return controlBreak(world, player, breakTarget);
        }

        // ---- phase 2: place a block that is already in reach ----
        if (!scheduler.canPlaceNow()) {
            if (hasPlacableInReach(world, player)) {
                inactivityTicks = 0;
                return standStill(); // Stay in position while cooling down between placements
            }
        }
        PlacementPlan plan = possibleToPlace(world, player);
        if (plan != null) {
            inactivityTicks = 0;
            return controlPlacement(world, player, plan);
        }

        // ---- phase 3: path to the next work position ----
        return pathToNextWork(player, world);
    }

    // =====================================================================
    // Scanning / ordering
    // =====================================================================

    /** Rebuilds `pending` (schematic cells not yet matching the world) and orders it. */
    private void searchForPlacables(Level world) {
        rescanCountdown = RESCAN_INTERVAL;
        missing.clear();
        missingReported = false;
        List<Target> fresh = new ArrayList<>();

        if (clearMin != null) { // #cleararea mode
            for (int y = clearMin.getY(); y <= clearMax.getY(); y++) {
                for (int z = clearMin.getZ(); z <= clearMax.getZ(); z++) {
                    for (int x = clearMin.getX(); x <= clearMax.getX(); x++) {
                        BetterBlockPos pos = new BetterBlockPos(x, y, z);
                        if (!world.hasChunkAt(pos)) {
                            continue;
                        }
                        BlockState cur = world.getBlockState(pos);
                        if (cur.getBlock() instanceof AirBlock || BlockStateResolver.isReplaceable(cur)) {
                            continue;
                        }
                        fresh.add(new Target(pos, null));
                    }
                }
            }
        } else {
            int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
            int yStart = 0, yEnd = schematic.height();
            if (layerMode) {
                yStart = layerBase;
                yEnd = Math.min(schematic.height(), layerBase + layerHeight);
                if (yStart >= yEnd) {
                    advanceLayerOrFinish();
                    return;
                }
            }
            for (int y = yStart; y < yEnd; y++) {
                for (int z = 0; z < schematic.length(); z++) {
                    for (int x = 0; x < schematic.width(); x++) {
                        BlockState want = schematic.desiredState(x, y, z);
                        if (isUnplaceable(want)) {
                            continue; // air, fluids, bubble column, portals, etc.
                        }
                        if (isAutoPlacedSecondaryPart(want)) {
                            continue; // door upper half / bed head appear automatically
                        }
                        BetterBlockPos pos = new BetterBlockPos(ox + x, oy + y, oz + z);
                        if (resigned.contains(pos) || !world.hasChunkAt(pos)) {
                            continue;
                        }
                        if (scheduler.isRecentlyPlaced(pos)) {
                            continue;
                        }
                        if (BlockStateResolver.statesMatch(want, world.getBlockState(pos))) {
                            continue;
                        }
                        fresh.add(new Target(pos, want));
                    }
                }
            }
        }
        pending = scheduler.orderCandidates(fresh, world, ctx.playerFeet());
        // Record initial count for progress percentage
        if (totalTargetsInitial == 0 && !pending.isEmpty()) {
            totalTargetsInitial = pending.size();
        }
    }

    private static boolean isAutoPlacedSecondaryPart(BlockState want) {
        if (want.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && want.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            return true; // doors
        }
        return want.hasProperty(BlockStateProperties.BED_PART)
                && want.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD; // beds
    }

    // =====================================================================
    // Phase 2: placement (BUG 3, BUG 4)
    // =====================================================================

    private boolean hasPlacableInReach(Level world, LocalPlayer player) {
        Vec3 eye = player.getEyePosition();
        double reach = Baritone.settings().antiCheatCompat.value
                ? Math.min(4.0D, Baritone.settings().builderPlacementReach.value)
                : Math.max(3.0, Math.min(player.blockInteractionRange() - 0.25, 4.5));
        double reachSq = reach * reach;
        for (Target t : pending) {
            if (t.want == null || scheduler.isUnreachable(t.pos) || resigned.contains(t.pos)) {
                continue;
            }
            if (BlockStateResolver.statesMatch(t.want, world.getBlockState(t.pos))) {
                continue;
            }
            if (slotFor(t.want) < 0) {
                continue;
            }
            double dx = t.pos.getX() + 0.5 - eye.x;
            double dy = t.pos.getY() + 0.5 - eye.y;
            double dz = t.pos.getZ() + 0.5 - eye.z;
            if (dx * dx + dy * dy + dz * dz <= reachSq) {
                if (hasSupport(world, t.pos)) {
                    return true;
                }
            }
        }
        return false;
    }

    private PlacementPlan possibleToPlace(Level world, LocalPlayer player) {
        if (!scheduler.canPlaceNow()) {
            if (pendingPlan != null) { // keep aiming while cooling down / verifying
                baritone.getLookBehavior().updateTarget(pendingPlan.rotation, true);
                if (Baritone.settings().antiCheatCompat.value) {
                    player.setSprinting(false);
                }
            }
            return null;
        }
        if (pendingPlan != null) {
            if (pendingPlan.stillValid(world)) {
                int slot = slotFor(pendingPlan.wanted);
                if (slot >= 0) {
                    pendingPlan.slot = slot;
                    return pendingPlan;
                }
            }
            pendingPlan = null;
            setSneakHeld(false);
        }

        Vec3 eye = player.getEyePosition();
        double reach = Baritone.settings().antiCheatCompat.value
                ? Math.min(4.0D, Baritone.settings().builderPlacementReach.value)
                : Math.max(3.0, Math.min(player.blockInteractionRange() - 0.25, 4.5));

        for (Iterator<Target> it = pending.iterator(); it.hasNext(); ) {
            Target t = it.next();
            if (t.want == null) {
                continue; // break-only target
            }
            BlockState cur = world.getBlockState(t.pos);
            if (BlockStateResolver.statesMatch(t.want, cur)) {
                it.remove(); // Target is already built! Prune immediately
                continue;
            }
            if (!(cur.getBlock() instanceof AirBlock) && !BlockStateResolver.isReplaceable(cur) && cur.getFluidState().isEmpty()) {
                continue; // Solid block is in the way; must be cleared during break phase
            }
            if (scheduler.isUnreachable(t.pos) || resigned.contains(t.pos)) {
                continue;
            }
            if (scheduler.ghostFailures(t.pos) >= 3) { // keeps getting rejected -> back off
                scheduler.markUnreachableWithBackoff(t.pos); // exponential backoff
                continue;
            }
            if (new AABB(t.pos).intersects(player.getBoundingBox())) {
                continue; // would place inside ourselves -> guaranteed server reject
            }
            int slot = slotFor(t.want);
            if (slot < 0) {
                trackMissing(t.want);
                continue;
            }
            double dx = t.pos.getX() + 0.5 - eye.x;
            double dy = t.pos.getY() + 0.5 - eye.y;
            double dz = t.pos.getZ() + 0.5 - eye.z;
            if (dx * dx + dy * dy + dz * dz > reach * reach) {
                continue;
            }
            PlacementPlan plan = BlockStateResolver.resolve(world, player, t.pos, t.want);
            if (plan == null) {
                continue; // orientation/support/LOS not satisfiable right now
            }
            plan.slot = slot;
            return plan;
        }
        return null;
    }

    private PathingCommand controlPlacement(Level world, LocalPlayer player, PlacementPlan plan) {
        if (Baritone.settings().antiCheatCompat.value) {
            player.setSprinting(false); // GrimAC: no sprint while fine-aiming/placing
        }
        this.pendingPlan = plan;
        setSneakHeld(plan.sneak);

        float dYaw = Math.abs(Mth.wrapDegrees(plan.rotation.getYaw() - player.getYRot()));
        float dPitch = Math.abs(plan.rotation.getPitch() - player.getXRot());
        boolean aligned = dYaw <= ROTATION_TOLERANCE && dPitch <= ROTATION_TOLERANCE;

        if (plan.sneak && sneakTicks < MIN_SNEAK_TICKS) {
            aligned = false;
        }

        if (!aligned) {
            rotationSettleTicks = 0;
            baritone.getLookBehavior().updateTarget(plan.rotation, true);
            return standStill();
        }

        // GrimAC guard 1: Settle ticks — ensure the server receives the rotation packet before the placement packet
        rotationSettleTicks++;
        int requiredSettle = Baritone.settings().antiCheatCompat.value
                ? Math.max(1, Baritone.settings().builderRotationSettleTicks.value)
                : 0;
        if (rotationSettleTicks < requiredSettle) {
            baritone.getLookBehavior().updateTarget(plan.rotation, true);
            return standStill();
        }

        // Snap client rotation to match planned rotation exactly for raycast consistency
        player.setYRot(plan.rotation.getYaw());
        player.setXRot(plan.rotation.getPitch());

        // GrimAC guard 2: Active raycast verification with current player eye position and view angles
        double reach = Baritone.settings().antiCheatCompat.value
                ? Math.min(4.0D, Baritone.settings().builderPlacementReach.value)
                : Baritone.settings().builderPlacementReach.value;
        HitResult activeHit = RayTraceUtils.rayTraceTowards(player, plan.rotation, reach, player.isCrouching());
        BlockHitResult bhr = null;
        if (activeHit != null && activeHit.getType() == HitResult.Type.BLOCK) {
            BlockHitResult candidate = (BlockHitResult) activeHit;
            if (candidate.getBlockPos().equals(plan.support) && candidate.getDirection() == plan.face) {
                bhr = candidate;
            }
        }
        if (bhr == null) {
            // If active hit slightly misses face, allow a short settle grace window
            if (rotationSettleTicks <= requiredSettle + 2) {
                baritone.getLookBehavior().updateTarget(plan.rotation, true);
                return standStill();
            }
            // Grace window expired: use pre-verified plan hit result to avoid infinite freeze
            bhr = plan.toHitResult();
        }

        // ---- aligned, settled and raytrace-verified: execute the placement ----
        BlockState cur = world.getBlockState(plan.target);
        if (!(cur.getBlock() instanceof AirBlock) && !BlockStateResolver.isReplaceable(cur) && cur.getFluidState().isEmpty()) {
            pendingPlan = null; // world changed while turning
            pending.removeIf(t -> t.pos.equals(plan.target));
            setSneakHeld(false);
            rotationSettleTicks = 0;
            return standStill();
        }
        int slot = slotFor(plan.wanted);
        if (slot < 0) {
            pendingPlan = null;
            setSneakHeld(false);
            rotationSettleTicks = 0;
            return standStill();
        }
        plan.slot = slot;

        if (player.getInventory().selected != slot) {
            player.getInventory().selected = slot;
            if (player.connection != null) {
                player.connection.send(new ServerboundSetCarriedItemPacket(slot));
            }
        }

        // GrimAC atomic synchronization: ensure exact rotation and sneak state packets are sent right before useItemOn
        if (player.connection != null) {
            player.connection.send(new ServerboundMovePlayerPacket.Rot(
                    plan.rotation.getYaw(),
                    plan.rotation.getPitch(),
                    player.onGround(),
                    player.horizontalCollision
            ));
            if (plan.sneak) {
                player.connection.send(new ServerboundPlayerCommandPacket(
                        player,
                        ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY
                ));
            }
        }

        // Click with the verified BlockHitResult
        InteractionResult result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, bhr);
        if (result != null && result.consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND);
            scheduler.notifyPlaced(plan.target); // cooldown + verification
            pending.removeIf(t -> t.pos.equals(plan.target));
        } else {
            scheduler.notifyRejected(plan.target); // client already knows it failed
        }

        if (plan.sneak && player.connection != null) {
            player.connection.send(new ServerboundPlayerCommandPacket(
                    player,
                    ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY
            ));
        }

        pendingPlan = null;
        setSneakHeld(false);
        rotationSettleTicks = 0;
        baritone.getLookBehavior().updateTarget(null, false); // release look control
        return standStill();
    }

    // =====================================================================
    // Phase 1: breaking wrong blocks
    // =====================================================================

    private Target findBreakTargetInReach(Level world, LocalPlayer player) {
        if (breaking != null) {
            for (Target t : pending) {
                if (t.pos.equals(breaking)) {
                    return t;
                }
            }
            stopBreaking();
        }
        Vec3 eye = player.getEyePosition();
        double reach = Math.max(3.0, Math.min(player.blockInteractionRange() - 0.25, 4.5));
        for (Target t : pending) {
            if (scheduler.isUnreachable(t.pos) || resigned.contains(t.pos)) {
                continue;
            }
            BlockState cur = world.getBlockState(t.pos);
            if (cur.getBlock() instanceof AirBlock || BlockStateResolver.isReplaceable(cur) || !cur.getFluidState().isEmpty()) {
                continue; // fluids get placed into, not broken
            }
            double dx = t.pos.getX() + 0.5 - eye.x;
            double dy = t.pos.getY() + 0.5 - eye.y;
            double dz = t.pos.getZ() + 0.5 - eye.z;
            if (dx * dx + dy * dy + dz * dz > reach * reach) {
                continue;
            }
            return t;
        }
        return null;
    }

    private PathingCommand controlBreak(Level world, LocalPlayer player, Target t) {
        if (Baritone.settings().antiCheatCompat.value) {
            player.setSprinting(false);
        }
        Rotation look = SmoothLookHelper.lookAt(player.getEyePosition(), Vec3.atCenterOf(t.pos));
        float dYaw = Math.abs(Mth.wrapDegrees(look.getYaw() - player.getYRot()));
        float dPitch = Math.abs(look.getPitch() - player.getXRot());
        if (dYaw > ROTATION_TOLERANCE || dPitch > ROTATION_TOLERANCE) {
            baritone.getLookBehavior().updateTarget(look, true);
            return standStill();
        }
        Direction face = breakFace(player, t.pos);
        if (breaking == null || !breaking.equals(t.pos)) {
            stopBreaking();
            breaking = t.pos.immutable();
            breakTicks = 0;
            mc.gameMode.startDestroyBlock(t.pos, face);
        } else {
            mc.gameMode.continueDestroyBlock(t.pos, face);
        }
        player.swing(InteractionHand.MAIN_HAND);

        if (++breakTicks > MAX_BREAK_TICKS) {
            scheduler.markUnreachable(t.pos, scheduler.currentTick() + 1200);
            stopBreaking();
        } else if (world.getBlockState(t.pos).getBlock() instanceof AirBlock) {
            stopBreaking();
        }
        return standStill();
    }

    private void stopBreaking() {
        if (breaking != null && mc.gameMode != null) {
            mc.gameMode.stopDestroyBlock();
        }
        breaking = null;
        breakTicks = 0;
    }

    private static Direction breakFace(LocalPlayer player, BlockPos pos) {
        Vec3 c = Vec3.atCenterOf(pos);
        Vec3 eye = player.getEyePosition();
        double dx = c.x - eye.x;
        double dy = c.y - eye.y;
        double dz = c.z - eye.z;
        Direction best = Direction.UP;
        double maxDot = -Double.MAX_VALUE;
        for (Direction d : Direction.values()) {
            double dot = d.getStepX() * dx + d.getStepY() * dy + d.getStepZ() * dz;
            if (dot > maxDot) {
                maxDot = dot;
                best = d;
            }
        }
        return best.getOpposite();
    }

    // =====================================================================
    // Phase 3: pathing (BUG 1 skip ladder)
    // =====================================================================

    private PathingCommand pathToNextWork(LocalPlayer player, Level world) {
        Target best = null;
        int missingCount = 0;
        int totalWithMaterial = 0;
        for (Target t : pending) {
            if (scheduler.isUnreachable(t.pos) || resigned.contains(t.pos)) {
                continue;
            }
            if (t.want != null && BlockStateResolver.statesMatch(t.want, world.getBlockState(t.pos))) {
                continue; // Already placed, do not path to it
            }
            if (t.want != null && slotFor(t.want) < 0) {
                missingCount++;
                continue;
            }
            totalWithMaterial++;
            // FIX 2: prioritize targets that have support (scaffolding logic)
            if (best == null) {
                best = t;
            } else if (t.want != null && hasSupport(world, t.pos) && !hasSupport(world, best.pos)) {
                best = t; // prefer placeable (has support) over unsupported
            }
        }
        if (best == null) {
            // FIX 4: don't stop if there are still blocks we CAN build
            if (missingCount > 0 && totalWithMaterial == 0) {
                reportMissingMaterials();
                if (layerMode) {
                    // All remaining blocks in this layer need missing materials.
                    // DO NOT FREEZE! Advance to the next layer!
                    logDirect("Layer missing materials (" + missingSummary() + ") — skipping to next layer");
                    advanceLayerOrFinish();
                    return standStill();
                } else {
                    for (Target t : pending) {
                        if (t.want != null && slotFor(t.want) < 0 && !scheduler.isUnreachable(t.pos)) {
                            scheduler.markUnreachable(t.pos, scheduler.currentTick() + 100);
                        }
                    }
                }
            } else if (missingCount == 0) {
                if (++starvedTicks > STARVED_TICK_LIMIT) {
                    resignAllUnreachable();
                }
            }
            return standStill();
        }
        starvedTicks = 0;

        if (currentGoalTarget == null || currentGoalTarget.distSqr(best.pos) > 8 * 8) {
            currentGoalTarget = new BetterBlockPos(best.pos);
            currentGoal = new GoalNear(currentGoalTarget, 3);
        }
        return new PathingCommand(currentGoal, PathingCommandType.SET_GOAL_AND_PATH);
    }

    /** FIX 2: Check if a target position has at least one solid neighbor to place against. */
    private boolean hasSupport(Level world, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockPos neighbor = pos.relative(d);
            BlockState ns = world.getBlockState(neighbor);
            if (!(ns.getBlock() instanceof AirBlock) && !(ns.getBlock() instanceof LiquidBlock)
                    && !BlockStateResolver.isReplaceable(ns)) {
                return true;
            }
        }
        return false;
    }

    /** BUG 1: after N consecutive CALC_FAILEDs, skip nearby blocks and retry them later. */
    private void skipAroundFailedGoal() {
        long retryAt = scheduler.currentTick() + Baritone.settings().unreachableRetryTicks.value;
        int marked = 0;
        if (currentGoalTarget == null) {
            if (!pending.isEmpty()) {
                scheduler.markUnreachable(pending.get(0).pos, retryAt);
                marked = 1;
            }
        } else {
            for (Target t : pending) {
                if (!scheduler.isUnreachable(t.pos)
                        && t.pos.distSqr(currentGoalTarget) <= SKIP_RADIUS * SKIP_RADIUS) {
                    scheduler.markUnreachable(t.pos, retryAt);
                    marked++;
                }
            }
        }
        logDirect(String.format("Pathing failed %dx around %s — skipping %d blocks for %dt, moving on",
                calcFailStreak, String.valueOf(currentGoalTarget), marked,
                Baritone.settings().unreachableRetryTicks.value));
        calcFailStreak = 0;
        currentGoalTarget = null; // force re-selection of a new work area
        currentGoal = null;
    }

    private void resignAllUnreachable() {
        int resignedNow = 0;
        for (Target t : pending) {
            if (scheduler.isUnreachable(t.pos) && !resigned.contains(t.pos)) {
                resigned.add(t.pos.immutable());
                resignedNow++;
            }
        }
        if (resignedNow > 0) {
            logDirect("Giving up on " + resignedNow + " unreachable blocks (skipped permanently this build)");
        }
        starvedTicks = 0;
    }

    // =====================================================================
    // Layers (IMPROVEMENT 5) / completion
    // =====================================================================

    private void advanceLayerOrFinish() {
        if (!layerMode || schematic == null) {
            finish("complete");
            return;
        }
        if (topDown) {
            layerBase -= layerHeight;
            if (layerBase < 0) {
                finish("complete (top->bottom)");
                return;
            }
        } else {
            layerBase += layerHeight;
            if (layerBase >= schematic.height()) {
                finish("complete (bottom->top)");
                return;
            }
        }
        logDirect("Layer done — building layer at y=" + (origin != null ? (origin.getY() + layerBase) : layerBase));
        this.inactivityTicks = 0;
        this.starvedTicks = 0;
        this.currentGoalTarget = null;
        this.currentGoal = null;
        rescanCountdown = 0; // force rescan next tick
    }

    private void finish(String why) {
        active = false;
        schematic = null;
        clearMin = clearMax = null;
        pendingPlan = null;
        pending = new ArrayList<>();
        setSneakHeld(false);
        stopBreaking();
        if (baritone.getLookBehavior() != null) {
            baritone.getLookBehavior().updateTarget(null, false);
        }
        logDirect("Builder finished: " + name + " (" + why + ")");
    }

    // =====================================================================
    // Inventory / reporting / sneak helper
    // =====================================================================

    private int slotFor(BlockState want) {
        if (want == null) {
            return -1;
        }
        net.minecraft.world.item.Item item = BlockStateResolver.getItemForState(want);
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            return -1;
        }
        LocalPlayer player = ctx.player();
        if (player == null) {
            return -1;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (!s.isEmpty() && s.getItem() == item) {
                return i;
            }
        }
        // Auto-swap from main inventory (9-35) into hotbar
        for (int i = 9; i < 36; i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (!s.isEmpty() && s.getItem() == item) {
                int hotbarSlot = findBestHotbarSlot(player);
                if (hotbarSlot >= 0) {
                    swapSlots(player, i, hotbarSlot);
                    return hotbarSlot;
                }
            }
        }
        return -1;
    }

    private int findBestHotbarSlot(LocalPlayer player) {
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getItem(i).isEmpty()) {
                return i;
            }
        }
        int sel = player.getInventory().selected;
        return (sel >= 0 && sel < 9) ? sel : 8;
    }

    private void swapSlots(LocalPlayer player, int invSlot, int hotbarSlot) {
        if (mc.gameMode != null && player != null) {
            mc.gameMode.handleInventoryMouseClick(
                    player.inventoryMenu.containerId,
                    invSlot,
                    hotbarSlot,
                    ClickType.SWAP,
                    player);
        }
    }

    private String missingSummary() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Block, Integer> e : missing.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(BuiltInRegistries.BLOCK.getKey(e.getKey()).getPath()).append(" x").append(e.getValue());
        }
        return sb.length() > 0 ? sb.toString() : "various";
    }

    private static boolean isUnplaceable(BlockState state) {
        if (state == null) return true;
        Block b = state.getBlock();
        if (b instanceof AirBlock) return true;
        if (b instanceof LiquidBlock) return true;
        if (b instanceof BubbleColumnBlock) return true;
        if (b instanceof NetherPortalBlock || b instanceof EndPortalBlock || b instanceof EndGatewayBlock) return true;
        if (b instanceof net.minecraft.world.level.block.piston.PistonHeadBlock || b instanceof net.minecraft.world.level.block.piston.MovingPistonBlock) return true;
        if (b instanceof FireBlock || b instanceof SoulFireBlock) return true;
        return false;
    }

    private void trackMissing(BlockState want) {
        missing.merge(want.getBlock(), 1, Integer::sum);
    }

    /**
     * FIX 4: Log missing blocks but DON'T set active=false.
     * Only stops if ALL remaining blocks need missing materials.
     */
    private void reportMissingMaterials() {
        if (!missingReported) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<Block, Integer> e : missing.entrySet()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(BuiltInRegistries.BLOCK.getKey(e.getKey()).getPath()).append(" x").append(e.getValue());
            }
            logDirect("Build paused — missing blocks: " + sb + " (will resume when materials available)");
            missingReported = true;
        }
        // Mark missing-material targets as temporarily unreachable instead of stopping
        for (Target t : pending) {
            if (t.want != null && slotFor(t.want) < 0 && !scheduler.isUnreachable(t.pos)) {
                scheduler.markUnreachable(t.pos, scheduler.currentTick() + 100); // re-check every 5 seconds
            }
        }
    }

    private void setSneakHeld(boolean held) {
        if (held == sneakHeld) {
            if (held) {
                sneakTicks++;
            }
            return;
        }
        sneakHeld = held;
        sneakTicks = 0;
        mc.options.keyShift.setDown(held); // feeds the vanilla input packet pipeline
        LocalPlayer p = ctx.player();
        if (p != null) {
            p.setShiftKeyDown(held); // client-side prediction consistency
        }
    }

    private PathingCommand standStill() {
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    // =====================================================================
    // FIX 3: Self-unstuck
    // =====================================================================

    /**
     * If the player's bounding box overlaps any pending build target,
     * path away first to avoid placing blocks inside ourselves.
     */
    private PathingCommand checkSelfUnstuck(LocalPlayer player, Level world) {
        AABB playerBox = player.getBoundingBox();
        for (Target t : pending) {
            if (t.want == null) continue;
            BlockState cur = world.getBlockState(t.pos);
            if (!(cur.getBlock() instanceof AirBlock) && !BlockStateResolver.isReplaceable(cur)) {
                continue; // already has a block, not a placement target
            }
            if (new AABB(t.pos).intersects(playerBox)) {
                // Player is standing in a spot where a block needs to go — move away
                return new PathingCommand(
                        new GoalRunAway(2.5, t.pos),
                        PathingCommandType.SET_GOAL_AND_PATH
                );
            }
        }
        return null;
    }

    // =====================================================================
    // Visual state accessors (read by PathRenderer for builder overlay)
    // =====================================================================

    /** The scheduler, for visual state queries (last placed pos, tick). */
    public PlacementScheduler getScheduler() { return scheduler; }

    /** Current pending targets list (unmodifiable view). */
    public List<Target> getPendingTargets() { return Collections.unmodifiableList(pending); }

    /**
     * The current target being aimed at (used by PathRenderer for pulsing highlight).
     * Returns the first pending target that has a valid pendingPlan, or null.
     */
    public Target getCurrentTarget() {
        if (pendingPlan == null) return null;
        for (Target t : pending) {
            if (t.pos.equals(pendingPlan.target)) return t;
        }
        return null;
    }

    /**
     * True if this target is missing required materials (used by overlay coloring).
     */
    public boolean isMissingMaterial(Target t) {
        if (t.want == null) return false;
        return missing.containsKey(t.want.getBlock());
    }

    /**
     * True if this target has been temporarily skipped / resigned (used by overlay coloring).
     */
    public boolean isSkipped(Target t) {
        return resigned.contains(t.pos) || scheduler.isKnownGhost(t.pos);
    }

    /** The currently aimed-at placement plan, if any. */
    public PlacementPlan getPendingPlan() { return pendingPlan; }

    /** Map of missing blocks -> count. */
    public Map<Block, Integer> getMissingBlocks() { return Collections.unmodifiableMap(missing); }

    /** Total initial targets for this build/layer (for progress %). */
    public int getTotalTargetsInitial() { return totalTargetsInitial; }

    /** Completed block count (confirmed placements). */
    public int getCompletedCount() { return scheduler.verifiedCount(); }

    /** Total schematic block count. */
    public int getTotalCount() { return totalTargetsInitial; }

    /** Build name. */
    public String getBuildName() { return name; }

    /** The schematic origin, or null. */
    public Vec3i getOrigin() { return origin; }

    /** Current layer base Y (offset from schematic, only valid if layerMode). */
    public int getLayerBase() { return layerBase; }

    /** Whether building in layer mode. */
    public boolean isLayerMode() { return layerMode; }
}
