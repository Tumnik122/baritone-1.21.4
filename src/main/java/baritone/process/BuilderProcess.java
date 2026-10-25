package baritone.process;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalGetToBlock;
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
import baritone.api.utils.input.Input;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.builder.BlockStateResolver;
import baritone.utils.builder.BlockStateResolver.PlacementPlan;

import baritone.utils.builder.PathingFailureTracker;
import baritone.utils.builder.PlacementScheduler;
import baritone.utils.builder.PlacementScheduler.Target;
import baritone.utils.builder.SmoothLookHelper;
import baritone.utils.builder.VantagePointSolver;
import baritone.utils.schematic.SchematicSystem;
import baritone.utils.schematic.litematica.LitematicaHelper;
import baritone.utils.schematic.schematica.SchematicaHelper;
import baritone.utils.player.BaritonePlayerController;
import baritone.utils.ToolSet;
import baritone.utils.FastBreakHelper;
import baritone.bypass.AutoToolManager;
import baritone.bypass.GrimSnapRotationUtil;
import net.minecraft.tags.ItemTags;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
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
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.ClipContext;
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
    private volatile List<Target> renderTargetsSnapshot = Collections.emptyList();
    private final Set<BlockPos> resigned = new HashSet<>(); // permanently given up
    private int rescanCountdown;
    private PlacementPlan pendingPlan; // plan we are currently rotating towards
    private BlockPos breaking;
    private int breakTicks;
    private int interBlockCooldown;
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
    private int aimWaitTicks;

    private static final int RESCAN_INTERVAL = 10;
    private static final float ROTATION_TOLERANCE = 12.0f; // Snappy look-and-mine
    private static final int SKIP_RADIUS = 16;
    private static final int MAX_BREAK_TICKS = 120;
    private static final int STARVED_TICK_LIMIT = 100;
    /** Max ticks of total inactivity (no place/break/path) before force-advancing (6 seconds). */
    private static final int ANTI_STALL_TICKS = 120;
    private int inactivityTicks;
    /** Total targets at start of this layer/build (for progress %). */
    private int totalTargetsInitial;

    // Temporary settings saved during clearArea excavation
    private boolean savedMineAvoidWater;
    private boolean savedAvoidFluidProximity;
    private boolean savedAllowDownward;
    private boolean savedBreakFromAbove;
    private boolean savedGoalBreakFromAbove;
    private boolean savedOkIfWater;
    private boolean customSettingsActive;

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
        adaptSettingsForBuilding();
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
        adaptSettingsForBuilding();
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

    public String previewStrategy(PlacementScheduler.ClearMode mode, int maxHeight) {
        scheduler.setClearMode(mode);
        return scheduler.describeStrategy(maxHeight);
    }

    public void clearArea(BlockPos a, BlockPos b, PlacementScheduler.ClearMode mode) {
        scheduler.setClearMode(mode);
        clearArea(a, b);
    }

    @Override
    public void clearArea(BlockPos a, BlockPos b) {
        this.name = "clear";
        this.schematic = null;
        this.origin = null;
        this.layerMode = false;
        this.clearMin = new BetterBlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        this.clearMax = new BetterBlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));

        adaptSettingsForBuilding();
        start();
    }

    private void adaptSettingsForBuilding() {
        if (!customSettingsActive) {
            this.savedMineAvoidWater = Baritone.settings().mineAvoidWater.value;
            this.savedAvoidFluidProximity = Baritone.settings().avoidFluidProximity.value;
            this.savedAllowDownward = Baritone.settings().allowDownward.value;
            this.savedBreakFromAbove = Baritone.settings().breakFromAbove.value;
            this.savedGoalBreakFromAbove = Baritone.settings().goalBreakFromAbove.value;
            this.savedOkIfWater = Baritone.settings().okIfWater.value;
            this.customSettingsActive = true;
        }

        Baritone.settings().mineAvoidWater.value = false;
        Baritone.settings().avoidFluidProximity.value = false;
        Baritone.settings().allowDownward.value = true;
        Baritone.settings().breakFromAbove.value = true;
        Baritone.settings().goalBreakFromAbove.value = true;
        Baritone.settings().okIfWater.value = true;
    }

    private void start() {
        this.active = true;
        this.paused = false;
        this.calcFailStreak = 0;
        this.starvedTicks = 0;
        this.inactivityTicks = 0;
        this.rescanCountdown = 0;
        this.interBlockCooldown = 0;
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

    public static class GoalMiningReach implements Goal, baritone.api.utils.interfaces.IGoalRenderPos {
        private final int x;
        private final int y;
        private final int z;
        private final double maxReach;

        public GoalMiningReach(BlockPos pos, double reach) {
            this.x = pos.getX();
            this.y = pos.getY();
            this.z = pos.getZ();
            this.maxReach = reach;
        }

        @Override
        public BlockPos getGoalPos() {
            return new BlockPos(x, y, z);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            double dx = (this.x + 0.5) - (x + 0.5);
            double dy = (this.y + 0.5) - (y + 1.62);
            double dz = (this.z + 0.5) - (z + 0.5);
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > maxReach * maxReach) {
                return false;
            }
            if (x == this.x && (y == this.y || y == this.y - 1) && z == this.z) {
                return false;
            }
            return true;
        }

        @Override
        public double heuristic(int x, int y, int z) {
            double dx = (this.x + 0.5) - (x + 0.5);
            double dy = (this.y + 0.5) - (y + 1.62);
            double dz = (this.z + 0.5) - (z + 0.5);
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            return Math.max(0.0, dist - (maxReach - 1.0));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof GoalMiningReach that)) return false;
            return x == that.x && y == that.y && z == that.z && Double.compare(that.maxReach, maxReach) == 0;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(x, y, z, maxReach);
        }
    }

    public class GoalBreak extends baritone.api.pathing.goals.GoalGetToBlock {
        public GoalBreak(BlockPos pos) {
            super(pos);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            if (!Baritone.settings().goalBreakFromAbove.value && clearMin == null && y > this.y) {
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

    public boolean isActivelyBreaking() {
        return active && breaking != null;
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
        if (customSettingsActive) {
            Baritone.settings().mineAvoidWater.value = savedMineAvoidWater;
            Baritone.settings().avoidFluidProximity.value = savedAvoidFluidProximity;
            Baritone.settings().allowDownward.value = savedAllowDownward;
            Baritone.settings().breakFromAbove.value = savedBreakFromAbove;
            Baritone.settings().goalBreakFromAbove.value = savedGoalBreakFromAbove;
            Baritone.settings().okIfWater.value = savedOkIfWater;
            customSettingsActive = false;
        }
        // CRITICAL FIX: must set active=false or process manager throws
        // "stayed active after being cancelled" IllegalStateException
        active = false;
        paused = false;
        pendingPlan = null;
        breaking = null;
        pending = new ArrayList<>();
        updateRenderSnapshot();
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

        // ---- periodic schematic vs world re-scan (BUG 5 ordering) ----
        if (--rescanCountdown <= 0) {
            searchForPlacables(world);
        }
        if (pending.isEmpty()) {
            advanceLayerOrFinish();
            return !active ? null : standStill();
        }

        // ---- Anti-stall: only count when player is truly idle (not breaking, not pathing, not turning, not moving) ----
        boolean isWorking = (breaking != null)
                || (baritone.getPathingBehavior() != null && (baritone.getPathingBehavior().isPathing() || baritone.getPathingBehavior().getInProgress().isPresent()))
                || (rotationSettleTicks > 0)
                || (player.getDeltaMovement().lengthSqr() > 0.001);

        if (isWorking) {
            inactivityTicks = 0;
        } else {
            inactivityTicks++;
        }
        if (inactivityTicks >= ANTI_STALL_TICKS) {
            scheduler.forceResetVerification();
            inactivityTicks = 0;
            if (currentGoalTarget != null) {
                // Temporarily back off from this stalled target (200 ticks) instead of permanently resigning!
                scheduler.markUnreachable(currentGoalTarget, scheduler.currentTick() + 200);
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
                if (pendingPlan != null || rotationSettleTicks > 0) {
                    return standStill(); // Stay in position while actively aiming/cooling down between placements
                }
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

    /**
     * Unified predicate determining whether a block is considered cleared.
     * True for air, pure fluids, bubble columns, and replaceable non-solid blocks.
     * False for waterlogged physical blocks (stairs, slabs, chests) which still need breaking.
     */
    public static boolean isCleared(BlockState state) {
        if (state == null || state.isAir()) return true;
        if (state.getBlock() instanceof LiquidBlock) return true;
        if (state.getBlock() instanceof BubbleColumnBlock) return true;
        return false;
    }

    /** Bedrock, bariery, portale końca itd. mają destroySpeed < 0. */
    public static boolean isBreakable(Level world, BlockPos pos, BlockState state) {
        return state != null && state.getDestroySpeed(world, pos) >= 0.0F;
    }

    /** Cel nadal wymaga kopania i da się go rozbić. */
    private boolean needsBreaking(Level world, BlockPos pos) {
        BlockState s = world.getBlockState(pos);
        return !isCleared(s) && isBreakable(world, pos, s);
    }

    /** Rebuilds `pending` (schematic cells not yet matching the world) and orders it. */
    private void searchForPlacables(Level world) {
        rescanCountdown = RESCAN_INTERVAL;
        missing.clear();
        missingReported = false;
        List<Target> fresh = new ArrayList<>();

        if (clearMin != null) { // #cleararea mode
            int minX = clearMin.getX(), maxX = clearMax.getX();
            int minY = clearMin.getY(), maxY = clearMax.getY();
            int minZ = clearMin.getZ(), maxZ = clearMax.getZ();

            int height = maxY - minY + 1;
            PlacementScheduler.Strategy strategy = scheduler.resolveStrategy(height);

            // Determine player position clamped into the selection bounds
            BetterBlockPos playerFeet = ctx.playerFeet();
            int px = (playerFeet != null)
                    ? Mth.clamp(playerFeet.getX(), minX, maxX)
                    : (minX + maxX) / 2;
            int py = (playerFeet != null)
                    ? Mth.clamp(playerFeet.getY(), minY, maxY)
                    : (minY + maxY) / 2;
            int pz = (playerFeet != null)
                    ? Mth.clamp(playerFeet.getZ(), minZ, maxZ)
                    : (minZ + maxZ) / 2;

            final int MAX_CLEAR_BATCH = 2500;
            BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();

            // Expand horizontally in concentric rings/boxes centered on the player.
            // This guarantees:
            // 1) Blocks immediately around the player (in reach) are prioritized and always collected.
            // 2) Short-distance pathing targets close to the player, eliminating long-distance CALC_FAILED timeouts.
            // 3) Massive performance boost on large arenas (e.g. 100x100x4 = 40,000 blocks).
            int maxRadius = Math.max(Math.max(px - minX, maxX - px), Math.max(pz - minZ, maxZ - pz));
            int[] radiusSteps = { 12, 24, 48, 96, Math.max(128, maxRadius) };

            boolean topDown = (strategy == PlacementScheduler.Strategy.TOP_DOWN);
            int yStart = topDown ? maxY : minY;
            int yEnd = topDown ? minY : maxY;
            int yStep = topDown ? -1 : 1;

            int prevR = 0;
            for (int r : radiusSteps) {
                int rMinX = Math.max(minX, px - r);
                int rMaxX = Math.min(maxX, px + r);
                int rMinZ = Math.max(minZ, pz - r);
                int rMaxZ = Math.min(maxZ, pz + r);

                for (int y = yStart; topDown ? (y >= yEnd) : (y <= yEnd); y += yStep) {
                    for (int z = rMinZ; z <= rMaxZ; z++) {
                        for (int x = rMinX; x <= rMaxX; x++) {
                            // Skip inner box already checked in previous radius step
                            if (prevR > 0 && x >= px - prevR && x <= px + prevR && z >= pz - prevR && z <= pz + prevR) {
                                continue;
                            }
                            mpos.set(x, y, z);
                            if (resigned.contains(mpos)) {
                                continue;
                            }
                            if (!world.hasChunkAt(mpos)) {
                                continue;
                            }
                            BlockState cur = world.getBlockState(mpos);
                            if (isCleared(cur) || !isBreakable(world, mpos, cur)) {
                                continue;
                            }
                            fresh.add(new Target(mpos.immutable(), null));
                            if (fresh.size() >= MAX_CLEAR_BATCH) {
                                break;
                            }
                        }
                        if (fresh.size() >= MAX_CLEAR_BATCH) break;
                    }
                    if (fresh.size() >= MAX_CLEAR_BATCH) break;
                }

                if (fresh.size() >= 300 || r >= maxRadius) {
                    break;
                }
                prevR = r;
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
            int sw = schematic.width();
            int sl = schematic.length();
            if (sw * sl > 500) {
                BetterBlockPos playerFeet = ctx.playerFeet();
                int px = (playerFeet != null) ? Mth.clamp(playerFeet.getX(), ox, ox + sw - 1) : ox + sw / 2;
                int pz = (playerFeet != null) ? Mth.clamp(playerFeet.getZ(), oz, oz + sl - 1) : oz + sl / 2;
                int relPx = px - ox;
                int relPz = pz - oz;

                final int MAX_BATCH = 1500;
                int maxR = Math.max(Math.max(relPx, sw - 1 - relPx), Math.max(relPz, sl - 1 - relPz));
                int[] radiusSteps = { 12, 24, 48, 96, Math.max(128, maxR) };
                int prevR = 0;

                for (int r : radiusSteps) {
                    int rMinX = Math.max(0, relPx - r);
                    int rMaxX = Math.min(sw - 1, relPx + r);
                    int rMinZ = Math.max(0, relPz - r);
                    int rMaxZ = Math.min(sl - 1, relPz + r);

                    for (int y = yStart; y < yEnd; y++) {
                        for (int z = rMinZ; z <= rMaxZ; z++) {
                            for (int x = rMinX; x <= rMaxX; x++) {
                                if (prevR > 0 && x >= relPx - prevR && x <= relPx + prevR && z >= relPz - prevR && z <= relPz + prevR) {
                                    continue;
                                }
                                BetterBlockPos pos = new BetterBlockPos(ox + x, oy + y, oz + z);
                                if (resigned.contains(pos) || !world.hasChunkAt(pos)) {
                                    continue;
                                }
                                if (scheduler.isRecentlyPlaced(pos)) {
                                    continue;
                                }
                                BlockState cur = world.getBlockState(pos);
                                if (!schematic.inSchematic(x, y, z, cur)) {
                                    continue;
                                }
                                BlockState want = schematic.desiredState(x, y, z, cur, Collections.emptyList());
                                if (want == null || isUnplaceable(want)) {
                                    if (want != null && want.isAir() && !isCleared(cur) && isBreakable(world, pos, cur)) {
                                        fresh.add(new Target(pos, null)); // obstacle clearing target
                                    }
                                    continue;
                                }
                                if (isAutoPlacedSecondaryPart(want)) {
                                    continue;
                                }
                                if (BlockStateResolver.statesMatch(want, cur)) {
                                    continue;
                                }
                                fresh.add(new Target(pos, want));
                                if (fresh.size() >= MAX_BATCH) {
                                    break;
                                }
                            }
                            if (fresh.size() >= MAX_BATCH) break;
                        }
                        if (fresh.size() >= MAX_BATCH) break;
                    }
                    if (fresh.size() >= 300 || r >= maxR) {
                        break;
                    }
                    prevR = r;
                }
            } else {
                for (int y = yStart; y < yEnd; y++) {
                    for (int z = 0; z < sl; z++) {
                        for (int x = 0; x < sw; x++) {
                            BetterBlockPos pos = new BetterBlockPos(ox + x, oy + y, oz + z);
                            if (resigned.contains(pos) || !world.hasChunkAt(pos)) {
                                continue;
                            }
                            if (scheduler.isRecentlyPlaced(pos)) {
                                continue;
                            }
                            BlockState cur = world.getBlockState(pos);
                            if (!schematic.inSchematic(x, y, z, cur)) {
                                continue;
                            }
                            BlockState want = schematic.desiredState(x, y, z, cur, Collections.emptyList());
                            if (want == null || isUnplaceable(want)) {
                                if (want != null && want.isAir() && !isCleared(cur) && isBreakable(world, pos, cur)) {
                                    fresh.add(new Target(pos, null)); // obstacle clearing target
                                }
                                continue; // air, fluids, bubble column, portals, etc.
                            }
                            if (isAutoPlacedSecondaryPart(want)) {
                                continue; // door upper half / bed head appear automatically
                            }
                            if (BlockStateResolver.statesMatch(want, cur)) {
                                continue;
                            }
                            fresh.add(new Target(pos, want));
                        }
                    }
                }
            }
        }
        pending = scheduler.orderCandidates(fresh, world, ctx.playerFeet());
        updateRenderSnapshot();
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
                ? Math.min(3.85D, Baritone.settings().builderPlacementReach.value)
                : Math.max(3.0, Math.min(player.blockInteractionRange() - 0.25, 4.5));
        double reachSq = reach * reach;
        for (Target t : pending) {
            if (t.want == null || scheduler.isUnreachable(t.pos) || resigned.contains(t.pos)) {
                continue;
            }
            if (BlockStateResolver.statesMatch(t.want, world.getBlockState(t.pos))) {
                continue;
            }
            if (!hasMaterial(t.want)) {
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

    private PlacementPlan findNextPlan(Level world, LocalPlayer player) {
        Vec3 eye = player.getEyePosition();
        double reach = Baritone.settings().antiCheatCompat.value
                ? Math.min(3.85D, Baritone.settings().builderPlacementReach.value)
                : Math.max(3.0, Math.min(player.blockInteractionRange() - 0.25, 4.5));
        double reachSq = reach * reach;

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
            if (!(cur.getBlock() instanceof AirBlock) && cur.getFluidState().isEmpty()) {
                continue; // Block/grass is in the way; must be cleared during break phase
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
            if (!hasMaterial(t.want)) {
                trackMissing(t.want);
                continue;
            }
            double dx = t.pos.getX() + 0.5 - eye.x;
            double dy = t.pos.getY() + 0.5 - eye.y;
            double dz = t.pos.getZ() + 0.5 - eye.z;
            if (dx * dx + dy * dy + dz * dz > reachSq) {
                continue;
            }
            PlacementPlan plan = BlockStateResolver.resolve(world, player, t.pos, t.want);
            if (plan == null) {
                continue; // orientation/support/LOS not satisfiable right now
            }
            return plan;
        }
        return null;
    }

    private PlacementPlan possibleToPlace(Level world, LocalPlayer player) {
        if (!scheduler.canPlaceNow()) {
            if (pendingPlan != null) { // keep aiming while cooling down / verifying
                baritone.getLookBehavior().updateTarget(pendingPlan.rotation, true);
                if (Baritone.settings().antiCheatCompat.value) {
                    player.setSprinting(false);
                    baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
                }
            } else {
                PlacementPlan prePlan = findNextPlan(world, player);
                if (prePlan != null) {
                    this.pendingPlan = prePlan;
                    baritone.getLookBehavior().updateTarget(prePlan.rotation, true);
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

        PlacementPlan plan = findNextPlan(world, player);
        if (plan != null) {
            int slot = slotFor(plan.wanted);
            if (slot >= 0) {
                plan.slot = slot;
                return plan;
            } else {
                trackMissing(plan.wanted);
            }
        }
        return null;
    }

    private PathingCommand controlPlacement(Level world, LocalPlayer player, PlacementPlan plan) {
        if (Baritone.settings().antiCheatCompat.value) {
            player.setSprinting(false); // GrimAC: no sprint while fine-aiming/placing
            baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
        }
        this.pendingPlan = plan;
        setSneakHeld(plan.sneak);

        float dYaw = Math.abs(Mth.wrapDegrees(plan.rotation.getYaw() - player.getYRot()));
        float dPitch = Math.abs(plan.rotation.getPitch() - player.getXRot());
        float maxTolerance = Baritone.settings().antiCheatCompat.value ? 4.5f : ROTATION_TOLERANCE;
        boolean aligned = dYaw <= maxTolerance && dPitch <= maxTolerance;

        if (plan.sneak && sneakTicks < MIN_SNEAK_TICKS) {
            aligned = false;
        }

        if (!aligned) {
            rotationSettleTicks = 0;
            baritone.getLookBehavior().updateTarget(plan.rotation, true);
            return standStill();
        }

        // Settle ticks — ensures the rotation packet is sent to the server in onPlayerUpdate before placing
        rotationSettleTicks++;
        int requiredSettle = Baritone.settings().antiCheatCompat.value
                ? Math.max(2, Baritone.settings().builderRotationSettleTicks.value)
                : 0;
        if (rotationSettleTicks < requiredSettle) {
            baritone.getLookBehavior().updateTarget(plan.rotation, true);
            return standStill();
        }

        double reach = Baritone.settings().antiCheatCompat.value
                ? Math.min(3.85D, Baritone.settings().builderPlacementReach.value)
                : Baritone.settings().builderPlacementReach.value;

        // Active raycast using player's actual look rotation
        Rotation playerRot = new Rotation(player.getYRot(), player.getXRot());
        HitResult activeHit = RayTraceUtils.rayTraceTowards(player, playerRot, reach, player.isCrouching());
        BlockHitResult bhr = null;
        if (activeHit instanceof BlockHitResult candidate && candidate.getType() == HitResult.Type.BLOCK) {
            if (candidate.getBlockPos().equals(plan.support) && candidate.getDirection() == plan.face) {
                bhr = candidate;
            }
        }
        if (bhr == null) {
            if (Baritone.settings().antiCheatCompat.value) {
                aimWaitTicks++;
                if (aimWaitTicks >= 6) {
                    // Cannot acquire clean raycast hit — back off
                    scheduler.markUnreachable(plan.target, scheduler.currentTick() + 40);
                    pendingPlan = null;
                    rotationSettleTicks = 0;
                    aimWaitTicks = 0;
                    setSneakHeld(false);
                } else {
                    baritone.getLookBehavior().updateTarget(plan.rotation, true);
                }
                return standStill();
            } else {
                bhr = plan.toHitResult();
            }
        }
        aimWaitTicks = 0;

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
            BaritonePlayerController.setCarriedItemSafe(player, slot);
        }

        // Burst placement optimization for ultra-fast #sel fill & singleplayer
        boolean fastMode = mc.hasSingleplayerServer() || !Baritone.settings().antiCheatCompat.value;
        int maxBurst = fastMode ? 3 : 1;
        int placedCount = 0;
        PlacementPlan curPlan = plan;
        BlockHitResult currentBhr = bhr;

        while (curPlan != null && placedCount < maxBurst) {
            InteractionResult result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, currentBhr);
            if (result == null || !result.consumesAction()) {
                ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
                if (!held.isEmpty() && (held.getItem() instanceof net.minecraft.world.item.BucketItem || held.is(net.minecraft.world.item.Items.WATER_BUCKET) || held.is(net.minecraft.world.item.Items.LAVA_BUCKET))) {
                    result = mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
                }
            }
            if (result != null && result.consumesAction()) {
                player.swing(InteractionHand.MAIN_HAND);
                final BlockPos placedTarget = curPlan.target;
                scheduler.notifyPlaced(placedTarget);
                pending.removeIf(t -> t.pos.equals(placedTarget));
                placedCount++;
            } else {
                scheduler.notifyRejected(curPlan.target);
                break;
            }

            if (placedCount >= maxBurst) {
                break;
            }

            // Szybkie znalezienie kolejnego kandydata w tym samym ticku
            PlacementPlan nextCandidate = findNextPlan(world, player);
            if (nextCandidate == null) {
                break;
            }
            float nYaw = Math.abs(Mth.wrapDegrees(nextCandidate.rotation.getYaw() - player.getYRot()));
            float nPitch = Math.abs(nextCandidate.rotation.getPitch() - player.getXRot());
            if (nYaw > 35.0f || nPitch > 35.0f) {
                this.pendingPlan = nextCandidate;
                baritone.getLookBehavior().updateTarget(nextCandidate.rotation, true);
                setSneakHeld(nextCandidate.sneak);
                return standStill();
            }

            int nextSlot = slotFor(nextCandidate.wanted);
            if (nextSlot < 0) {
                break;
            }
            if (player.getInventory().selected != nextSlot) {
                BaritonePlayerController.setCarriedItemSafe(player, nextSlot);
            }
            curPlan = nextCandidate;
            currentBhr = nextCandidate.toHitResult();
        }

        pendingPlan = null;
        rotationSettleTicks = 0;
        baritone.getLookBehavior().updateTarget(null, false); // release look control

        // Pre-aim immediately at the next candidate for the upcoming tick!
        PlacementPlan nextPlan = findNextPlan(world, player);
        if (nextPlan != null) {
            this.pendingPlan = nextPlan;
            baritone.getLookBehavior().updateTarget(nextPlan.rotation, true);
            setSneakHeld(nextPlan.sneak);
        } else {
            setSneakHeld(false);
        }

        return standStill();
    }

    // =====================================================================
    // Phase 1: breaking wrong blocks
    // =====================================================================

    private static final double REACH_MARGIN = 0.3; // zapas na lag/Grim

    private static double distSqrToBlock(Vec3 eye, BlockPos pos) {
        double dx = eye.x - Mth.clamp(eye.x, pos.getX(), pos.getX() + 1.0);
        double dy = eye.y - Mth.clamp(eye.y, pos.getY(), pos.getY() + 1.0);
        double dz = eye.z - Mth.clamp(eye.z, pos.getZ(), pos.getZ() + 1.0);
        return dx * dx + dy * dy + dz * dz;
    }

    private boolean inReach(LocalPlayer player, BlockPos pos) {
        double r = player.blockInteractionRange() - REACH_MARGIN;
        return distSqrToBlock(player.getEyePosition(), pos) <= r * r;
    }

    /** Raycast z oczu do środka i środków 6 ścian; woda jest ignorowana (Fluid.NONE). */
    private boolean hasLineOfSight(Level world, LocalPlayer player, BlockPos pos) {
        Vec3 eye = player.getEyePosition();
        Vec3 c = Vec3.atCenterOf(pos);
        final double o = 0.49;
        Vec3[] pts = {
                c,
                c.add(0, o, 0), c.add(0, -o, 0),
                c.add(o, 0, 0), c.add(-o, 0, 0),
                c.add(0, 0, o), c.add(0, 0, -o)
        };
        for (Vec3 p : pts) {
            BlockHitResult hit = world.clip(new ClipContext(
                    eye, p, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) {
                return true;
            }
        }
        return false;
    }

    private Target findBreakTargetInReach(Level world, LocalPlayer player) {
        if (breaking != null) {
            BlockState curBreak = world.getBlockState(breaking);
            boolean breakDone = isCleared(curBreak);
            if (!breakDone) {
                for (Target t : pending) {
                    if (t.pos.equals(breaking)) {
                        if (t.want != null && BlockStateResolver.statesMatch(t.want, curBreak)) {
                            breakDone = true;
                        }
                        break;
                    }
                }
            }
            if (breakDone || !isBreakable(world, breaking, curBreak)) {
                stopBreaking();
            } else {
                for (Target t : pending) {
                    if (t.pos.equals(breaking)) {
                        return t;
                    }
                }
                stopBreaking();
            }
        }
        BlockPos standPos = player.blockPosition().below();
        Target best = null;
        double bestD = Double.MAX_VALUE;
        Target standTarget = null;

        for (Target t : pending) {
            if (scheduler.isUnreachable(t.pos)) continue;

            BlockState s = world.getBlockState(t.pos);
            boolean needsBreak;
            if (t.want == null) {
                needsBreak = !isCleared(s) && isBreakable(world, t.pos, s);
            } else {
                needsBreak = !BlockStateResolver.statesMatch(t.want, s) && !isCleared(s) && isBreakable(world, t.pos, s);
            }
            if (!needsBreak) continue;
            if (!inReach(player, t.pos)) continue;
            if (!hasLineOfSight(world, player, t.pos)) continue;

            if (t.pos.equals(standPos)) {
                standTarget = t;
                continue;
            }

            double d = distSqrToBlock(player.getEyePosition(), t.pos);
            BlockState aboveState = world.getBlockState(t.pos.above());
            if (aboveState.getBlock() instanceof FallingBlock) {
                // Odsuń priorytet bloku pod żwirem/piaskiem, by najpierw zebrać opadający blok na górze
                d += 50.0;
            }
            if (d < bestD) {
                bestD = d;
                best = t;
            }
        }
        if (best != null) return best;

        // Grunt pod nogami: dopiero gdy na tym poziomie i wyżej nie ma już innych bloków do wykopania
        if (standTarget != null && noOtherWorkAtOrAbove(world, standPos)) {
            return standTarget;
        }
        return null;
    }

    private boolean noOtherWorkAtOrAbove(Level world, BlockPos standPos) {
        for (Target t : pending) {
            if (t.pos.equals(standPos)) continue;
            BlockState s = world.getBlockState(t.pos);
            boolean needsBreak = (t.want == null)
                    ? (!isCleared(s) && isBreakable(world, t.pos, s))
                    : (!BlockStateResolver.statesMatch(t.want, s) && !isCleared(s) && isBreakable(world, t.pos, s));
            if (t.pos.getY() >= standPos.getY() && needsBreak) return false;
        }
        return true;
    }

    private int breakBudget;

    private void equipBestTool(LocalPlayer player, BlockState state, BlockPos pos) {
        if (!Baritone.settings().autoTool.value || player == null || state == null) {
            return;
        }

        // 1. If bypass module is active and autoTool is enabled in bypass config, let AutoToolManager handle it first
        if (baritone.getBypassProcess() != null && baritone.getBypassProcess().isActive()) {
            boolean selected = AutoToolManager.selectBestTool(ctx, pos, baritone.getBypassProcess().getConfig());
            if (selected) {
                return;
            }
        }

        // 2. Standard Baritone hotbar tool selection (slots 0..8)
        ToolSet ts = new ToolSet(player);
        int bestSlot = ts.getBestSlot(state.getBlock(), Baritone.settings().preferSilkTouch.value);

        ItemStack candidateItem = player.getInventory().getItem(bestSlot);
        double candidateSpeed = ToolSet.calculateSpeedVsBlock(candidateItem, state);
        boolean candidateDropsOk = !state.requiresCorrectToolForDrops() || candidateItem.isCorrectToolForDrops(state);

        // 3. Inventory fallback (slots 9..35):
        // If hotbar candidate cannot harvest drops, or is slow (<= 1.0) while a fast tool exists in inventory,
        // and allowInventory is enabled: pull best tool from inventory into hotbar!
        if (Baritone.settings().allowInventory.value && (!candidateDropsOk || candidateSpeed <= 1.0)) {
            int bestInvSlot = -1;
            double bestInvSpeed = candidateSpeed;
            for (int i = 9; i < 36; i++) {
                ItemStack invStack = player.getInventory().getItem(i);
                if (invStack.isEmpty()) continue;
                if (!Baritone.settings().useSwordToMine.value && invStack.is(ItemTags.SWORDS)) continue;
                if (Baritone.settings().itemSaver.value
                        && (invStack.getDamageValue() + Baritone.settings().itemSaverThreshold.value) >= invStack.getMaxDamage()
                        && invStack.getMaxDamage() > 1) {
                    continue;
                }
                if (state.requiresCorrectToolForDrops() && !invStack.isCorrectToolForDrops(state)) {
                    continue;
                }
                double invSpeed = ToolSet.calculateSpeedVsBlock(invStack, state);
                if (invSpeed > bestInvSpeed) {
                    bestInvSpeed = invSpeed;
                    bestInvSlot = i;
                }
            }

            if (bestInvSlot >= 9) {
                int targetHotbar = player.getInventory().selected;
                if (!player.getInventory().getItem(targetHotbar).isEmpty()) {
                    for (int i = 0; i < 9; i++) {
                        if (player.getInventory().getItem(i).isEmpty()) {
                            targetHotbar = i;
                            break;
                        }
                    }
                }
                mc.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, bestInvSlot, targetHotbar, ClickType.SWAP, player);
                bestSlot = targetHotbar;
            }
        }

        if (bestSlot >= 0 && player.getInventory().selected != bestSlot) {
            BaritonePlayerController.setCarriedItemSafe(player, bestSlot);
        }
    }

    private int getMinGrimTicks(BlockState state, BlockPos pos) {
        boolean antiCheat = Baritone.settings().antiCheatCompatibility.value
                || Baritone.settings().grimCompat.value
                || Baritone.settings().antiCheatCompat.value;
        if (!antiCheat) return 1;
        double grimProgress = FastBreakHelper.getGrimDestroyProgress(ctx, pos, state);
        return (grimProgress > 0) ? (int) Math.ceil(1.0 / grimProgress) : 1;
    }

    private Rotation getSafeServerRotation() {
        Rotation serverRot = baritone.getLookBehavior().getServerRotation();
        if (serverRot != null) {
            return serverRot;
        }
        LocalPlayer p = ctx.player();
        if (p != null) {
            return new Rotation(p.getYRot(), p.getXRot());
        }
        return new Rotation(0.0f, 0.0f);
    }

    /**
     * Natychmiastowa synchronizacja kątów klienta i LookBehavior w tym samym ticku (0 ticków opóźnienia).
     * Działa identycznie jak w trybie #praca (FarmProcess.java) z pełnym bypass GrimAC GCD.
     */
    private void applyAimRotation(Rotation rot, boolean blockInteract) {
        baritone.getLookBehavior().updateTarget(rot, blockInteract);
        LocalPlayer player = ctx.player();
        if (player != null) {
            player.setYRot(rot.getYaw());
            player.setXRot(rot.getPitch());
            player.yHeadRot = rot.getYaw();
            player.yBodyRot = rot.getYaw();
        }
    }

    /**
     * Zwraca kolejny blok w kolejce pending (poza currentPos) o najmniejszym koszcie kątowym i dystansowym.
     */
    private Target peekNextBreakTarget(BlockPos currentPos) {
        if (pending == null || pending.isEmpty()) {
            return null;
        }
        LocalPlayer player = ctx.player();
        if (player == null) return null;
        Vec3 predictedEye = player.getEyePosition(1.0f).add(player.getDeltaMovement());
        Rotation curRot = getSafeServerRotation();

        Target bestNext = null;
        double bestScore = Double.MAX_VALUE;

        for (Target candidate : pending) {
            if (candidate == null || candidate.pos.equals(currentPos)) {
                continue;
            }
            if (scheduler.isUnreachable(candidate.pos)) {
                continue;
            }
            Vec3 center = Vec3.atCenterOf(candidate.pos);
            double distSq = predictedEye.distanceToSqr(center);
            if (distSq > 64.0D) { // Okno pre-aimingu do 8m
                continue;
            }

            float yawTo = (float) (Math.toDegrees(Math.atan2(center.z - predictedEye.z, center.x - predictedEye.x)) - 90.0D);
            float pitchTo = (float) (-Math.toDegrees(Math.atan2(center.y - predictedEye.y, Math.hypot(center.x - predictedEye.x, center.z - predictedEye.z))));
            float dYaw = Math.abs(Mth.wrapDegrees(yawTo - curRot.getYaw()));
            float dPitch = Math.abs(pitchTo - curRot.getPitch());

            double score = Math.sqrt(distSq) * 1.1D + (dYaw * 0.03D) + (dPitch * 0.02D);
            if (score < bestScore) {
                bestScore = score;
                bestNext = candidate;
            }
        }
        return bestNext;
    }

    /**
     * Obraca celownik GCD z wyprzedzeniem 1 ticku na następny blok w kolejce (Lookahead Pre-Aiming).
     */
    private void preAimNextTarget(BlockPos excludeCurrentPos) {
        Target nextTarget = peekNextBreakTarget(excludeCurrentPos);
        if (nextTarget == null) {
            return;
        }
        LocalPlayer player = ctx.player();
        if (player == null) return;
        Vec3 predictedEye = player.getEyePosition(1.0f).add(player.getDeltaMovement());
        Rotation curRot = getSafeServerRotation();

        BlockPos nextPos = nextTarget.pos;
        Vec3 targetVec = new Vec3(
                Mth.clamp(predictedEye.x, nextPos.getX() + 0.15D, nextPos.getX() + 0.85D),
                Mth.clamp(predictedEye.y, nextPos.getY() + 0.15D, nextPos.getY() + 0.85D),
                Mth.clamp(predictedEye.z, nextPos.getZ() + 0.15D, nextPos.getZ() + 0.85D)
        );
        float[] snapped = GrimSnapRotationUtil.snapGcd(curRot.getYaw(), curRot.getPitch(), predictedEye, targetVec);
        applyAimRotation(new Rotation(snapped[0], snapped[1]), false);
    }

    private PathingCommand controlBreak(Level world, LocalPlayer player, Target t) {
        if (interBlockCooldown > 0) {
            interBlockCooldown--;
            return standStill();
        }

        if (Baritone.settings().pauseMiningForFallingBlocks.value && world != null) {
            if (!world.getEntitiesOfClass(FallingBlockEntity.class, new AABB(t.pos).inflate(0.5, 1.5, 0.5)).isEmpty()) {
                return standStill();
            }
        }

        BlockState cur = world.getBlockState(t.pos);
        int minGrimTicks = getMinGrimTicks(cur, t.pos);
        boolean antiCheat = Baritone.settings().antiCheatCompatibility.value
                || Baritone.settings().grimCompat.value
                || Baritone.settings().antiCheatCompat.value;

        // Już usunięty (także zastąpiony wodą/lawą albo bąbelkami)
        boolean breakCleared = isCleared(cur) || (t.want != null && BlockStateResolver.statesMatch(t.want, cur));
        if (breakCleared) {
            if (breaking == null || !breaking.equals(t.pos) || !antiCheat || breakTicks >= minGrimTicks) {
                finishTarget(t, antiCheat && breaking != null && breaking.equals(t.pos) && minGrimTicks > 1);
                return standStill();
            }
        }
        // Nie da się rozbić (bedrock itp.) -> wywal z listy
        if (!isBreakable(world, t.pos, cur)) {
            finishTarget(t, false);
            return standStill();
        }

        // AUTO-TOOL: Wybierz najlepsze narzędzie od razu (przed obrotem kamery i przed rozpoczęciem kopania)
        equipBestTool(player, cur, t.pos);

        if (Baritone.settings().antiCheatCompat.value) {
            player.setSprinting(false);
            baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
        }

        // ULTRA-SMOOTH GRIMAC GCD ROTATION (dokładnie jak w #praca)
        double reach = ctx.playerController().getBlockReachDistance();
        if (antiCheat) {
            reach = Math.min(reach, Baritone.settings().grimSafeReach.value);
        }
        Rotation currentRot = getSafeServerRotation();
        GrimSnapRotationUtil.VerifiedBlockAim verifiedAim = GrimSnapRotationUtil.findVerifiedBlockSnapRotation(
                player, currentRot.getYaw(), currentRot.getPitch(), t.pos, reach);

        Direction face;
        if (verifiedAim != null) {
            applyAimRotation(verifiedAim.rotation(), true);
            face = verifiedAim.hitFace();
        } else {
            // Fallback GCD snap do środka bryły bloku
            Vec3 predictedEye = player.getEyePosition(1.0f).add(player.getDeltaMovement());
            float[] preRot = GrimSnapRotationUtil.snapGcd(
                    currentRot.getYaw(),
                    currentRot.getPitch(),
                    predictedEye,
                    Vec3.atCenterOf(t.pos)
            );
            applyAimRotation(new Rotation(preRot[0], preRot[1]), true);
            face = breakFace(player, t.pos);
        }
        baritone.getPlayerContext().playerController().syncHeldItem();

        if (breaking == null || !breaking.equals(t.pos)) {
            stopBreaking();
            breaking = t.pos.immutable();
            breakTicks = 0;
            // Upewnij się, że odpowiednie narzędzie jest w ręce przed obliczeniem perTick
            equipBestTool(player, cur, t.pos);
            float perTick = cur.getDestroyProgress(player, world, t.pos);
            if (perTick <= 0.0F) {
                scheduler.markUnreachable(t.pos, scheduler.currentTick() + 200);
                stopBreaking();
                currentGoalTarget = null;
                currentGoal = null;
                return standStill();
            }
            breakBudget = (int) Math.min(20 * 60 * 5, Math.ceil(1.0F / perTick) * 2 + 40);
            mc.gameMode.startDestroyBlock(t.pos, face);
        } else {
            mc.gameMode.continueDestroyBlock(t.pos, face);
        }
        player.swing(InteractionHand.MAIN_HAND);

        if (++breakTicks > breakBudget) {
            scheduler.markUnreachable(t.pos, scheduler.currentTick() + 100);
            stopBreaking();
            currentGoalTarget = null;
            currentGoal = null;
            return standStill();
        }

        BlockState curNow = world.getBlockState(t.pos);
        boolean breakNowCleared = isCleared(curNow) || (t.want != null && BlockStateResolver.statesMatch(t.want, curNow));
        if (breakNowCleared) {
            if (!antiCheat || breakTicks >= minGrimTicks) {
                finishTarget(t, antiCheat && minGrimTicks > 1);
            }
        }
        return standStill();
    }

    private void finishTarget(Target t, boolean wasMultiTick) {
        stopBreaking();
        if (t.want == null) {
            pending.removeIf(p -> p.pos.equals(t.pos));
        }
        currentGoalTarget = null;
        currentGoal = null;
        breakTicks = 0;
        breakBudget = 0;
        boolean antiCheat = Baritone.settings().antiCheatCompatibility.value
                || Baritone.settings().grimCompat.value
                || Baritone.settings().antiCheatCompat.value;
        if (wasMultiTick) {
            interBlockCooldown = antiCheat ? 5 : 0;
        }

        // God-tier visual feedback (neon amber shockwave na rozbitym bloku)
        com.astra.revolution.visuals.AstraGodTierVisuals.triggerShockwave(
                t.pos.getX() + 0.5D, t.pos.getY() + 0.5D, t.pos.getZ() + 0.5D, 0xFF9900, true);

        // Natychmiastowy Lookahead Pre-Aiming na siatce GCD na kolejny cel w kolejce!
        preAimNextTarget(t.pos);
    }

    private void stopBreaking() {
        if (breaking != null && mc.gameMode != null) {
            mc.gameMode.stopDestroyBlock();
        }
        breaking = null;
        breakTicks = 0;
        breakBudget = 0;
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
        return BaritonePlayerController.getSafeBreakFace(player, pos, best.getOpposite());
    }

    // =====================================================================
    // Phase 3: pathing (BUG 1 skip ladder)
    // =====================================================================

    private Goal breakGoalFor(Level world, BlockPos pos) {
        if (clearMin != null) {
            return new GoalMiningReach(pos, 3.4);
        }
        boolean airAbove = isCleared(world.getBlockState(pos.above()))
                && isCleared(world.getBlockState(pos.above(2)));
        if (airAbove) {
            return new GoalComposite(
                    new GoalMiningReach(pos, 3.4),
                    new GoalGetToBlock(pos.above()) {
                        @Override
                        public boolean isInGoal(int x, int y, int z) {
                            // nie stawaj wyżej ani dokładnie nad blokiem
                            if (y > this.y || (x == this.x && y == this.y && z == this.z)) return false;
                            return super.isInGoal(x, y, z);
                        }
                    });
        }
        return new GoalMiningReach(pos, 3.4);
    }

    private PathingCommand pathToNextWork(LocalPlayer player, Level world) {
        Target best = null;
        double bestDist = Double.MAX_VALUE;
        boolean bestSupported = false;
        int missingCount = 0;
        int totalWithMaterial = 0;
        BetterBlockPos playerFeet = ctx.playerFeet();

        for (Target t : pending) {
            if (scheduler.isUnreachable(t.pos) || resigned.contains(t.pos)) {
                continue;
            }
            BlockState cur = world.getBlockState(t.pos);
            boolean needsBreak = false;
            if (t.want != null) {
                if (BlockStateResolver.statesMatch(t.want, cur)) {
                    continue; // Already placed, do not path to it
                }
                if (!isCleared(cur)) {
                    needsBreak = true; // Obstructing block that needs breaking first
                } else if (!hasMaterial(t.want)) {
                    trackMissing(t.want);
                    missingCount++;
                    continue;
                }
            } else {
                // Clearing mode
                if (!needsBreaking(world, t.pos)) {
                    continue;
                }
                needsBreak = true;
            }
            totalWithMaterial++;

            boolean tSupported = needsBreak || hasSupport(world, t.pos);
            double dist = (playerFeet != null) ? t.pos.distSqr(playerFeet) : 0.0;

            if (best == null) {
                best = t;
                bestDist = dist;
                bestSupported = tSupported;
            } else if (tSupported && !bestSupported) {
                best = t;
                bestDist = dist;
                bestSupported = true;
            } else if (tSupported == bestSupported && dist < bestDist) {
                best = t;
                bestDist = dist;
            }
        }

        if (best == null) {
            if (missingCount > 0 && totalWithMaterial == 0) {
                reportMissingMaterials();
                if (layerMode) {
                    logDirect("Layer missing materials (" + missingSummary() + ") — skipping to next layer");
                    advanceLayerOrFinish();
                    return standStill();
                } else {
                    for (Target t : pending) {
                        if (t.want != null && !hasMaterial(t.want) && !scheduler.isUnreachable(t.pos)) {
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

        if (best.want != null && VantagePointSolver.needsVantage(best.want)) {
            BlockPos vantage = VantagePointSolver.findVantagePoint(world, player, best.pos, best.want);
            if (vantage != null) {
                if (currentGoalTarget == null || !currentGoalTarget.equals(vantage)) {
                    currentGoalTarget = new BetterBlockPos(vantage);
                    currentGoal = new GoalBlock(vantage);
                }
                return new PathingCommand(currentGoal, PathingCommandType.SET_GOAL_AND_PATH);
            }
        }

        if (best.want == null || !isCleared(world.getBlockState(best.pos))) {
            // Clearing mode or obstacle break: path to break goal
            if (currentGoal == null || currentGoalTarget == null || !currentGoalTarget.equals(best.pos)) {
                currentGoalTarget = new BetterBlockPos(best.pos);
                currentGoal = breakGoalFor(world, best.pos);
            }

            BlockPos feet = player.blockPosition();
            if (!currentGoal.isInGoal(feet.getX(), feet.getY(), feet.getZ())) {
                preAimNextTarget(null);
                return new PathingCommand(currentGoal, PathingCommandType.SET_GOAL_AND_PATH);
            }

            // In goal but cannot break: temporarily back off for 40 ticks
            scheduler.markUnreachable(best.pos, scheduler.currentTick() + 40);
            currentGoalTarget = null;
            currentGoal = null;
            return standStill();
        }

        if (new AABB(best.pos).intersects(player.getBoundingBox())) {
            return new PathingCommand(new GoalRunAway(1.5, best.pos), PathingCommandType.SET_GOAL_AND_PATH);
        }

        // Placement mode
        boolean needNewGoal = currentGoal == null
                || currentGoalTarget == null
                || !pendingContains(currentGoalTarget)
                || currentGoal.isInGoal(player.getBlockX(), player.getBlockY(), player.getBlockZ());

        if (needNewGoal || !currentGoalTarget.equals(best.pos)) {
            currentGoalTarget = new BetterBlockPos(best.pos);
            currentGoal = new GoalPlaceBlock(currentGoalTarget);
        }

        BlockPos feet = player.blockPosition();
        if (!currentGoal.isInGoal(feet.getX(), feet.getY(), feet.getZ())) {
            return new PathingCommand(currentGoal, PathingCommandType.SET_GOAL_AND_PATH);
        }

        // In goal, but reached phase 3 (phase 2 could not place the block from here).
        // Back off temporarily so we don't freeze in place!
        scheduler.markUnreachable(best.pos, scheduler.currentTick() + 40);
        currentGoalTarget = null;
        currentGoal = null;
        return standStill();
    }

    private static final class GoalPlaceBlock implements Goal {
        private final BlockPos target;

        GoalPlaceBlock(BlockPos target) {
            this.target = target.immutable();
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            if (x == target.getX() && z == target.getZ()) {
                if (y == target.getY() || y == target.getY() - 1) {
                    return false;
                }
            }
            int dx = x - target.getX();
            int dy = y - target.getY();
            int dz = z - target.getZ();
            return (dx * dx + dz * dz <= 5) && (dy >= -1 && dy <= 1);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            int dx = x - target.getX();
            int dy = y - target.getY();
            int dz = z - target.getZ();
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (x == target.getX() && z == target.getZ()) {
                dist += 5.0;
            }
            return dist;
        }
    }

    private boolean pendingContains(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        for (Target t : pending) {
            if (t.pos.equals(pos)) {
                return true;
            }
        }
        return false;
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
        // If scaffolding is enabled, check if the block directly below can support a temporary scaffold
        if (Baritone.settings().builderScaffolding.value) {
            BlockPos below = pos.below();
            BlockState bs = world.getBlockState(below);
            if (!(bs.getBlock() instanceof AirBlock) && !(bs.getBlock() instanceof LiquidBlock)
                    && !BlockStateResolver.isReplaceable(bs)) {
                return true;
            }
        }
        return false;
    }

    /** BUG 1: after N consecutive CALC_FAILEDs, skip nearby blocks and retry them later. */
    private void skipAroundFailedGoal() {
        long retryAt = scheduler.currentTick() + Baritone.settings().unreachableRetryTicks.value;
        int marked = 0;
        int skipRadius = (clearMin != null) ? 2 : SKIP_RADIUS;
        if (currentGoalTarget == null) {
            if (!pending.isEmpty()) {
                scheduler.markUnreachable(pending.get(0).pos, retryAt);
                marked = 1;
            }
        } else {
            for (Target t : pending) {
                if (!scheduler.isUnreachable(t.pos)
                        && t.pos.distSqr(currentGoalTarget) <= skipRadius * skipRadius) {
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
        if (customSettingsActive) {
            Baritone.settings().mineAvoidWater.value = savedMineAvoidWater;
            Baritone.settings().avoidFluidProximity.value = savedAvoidFluidProximity;
            Baritone.settings().allowDownward.value = savedAllowDownward;
            Baritone.settings().breakFromAbove.value = savedBreakFromAbove;
            Baritone.settings().goalBreakFromAbove.value = savedGoalBreakFromAbove;
            Baritone.settings().okIfWater.value = savedOkIfWater;
            customSettingsActive = false;
        }
        active = false;
        schematic = null;
        clearMin = clearMax = null;
        pendingPlan = null;
        pending = new ArrayList<>();
        updateRenderSnapshot();
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

    private boolean hasMaterial(BlockState want) {
        if (want == null) {
            return false;
        }
        LocalPlayer player = ctx.player();
        if (player == null) {
            return false;
        }
        if (player.getAbilities().instabuild) {
            return true;
        }
        net.minecraft.world.item.Item item = BlockStateResolver.getItemForState(want);
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            return false;
        }
        for (int i = 0; i < 36; i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (!s.isEmpty() && s.getItem() == item) {
                return true;
            }
        }
        return false;
    }

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
        // Creative mode auto-give item if missing
        if (player.getAbilities().instabuild && mc.gameMode != null) {
            int hotbarSlot = findBestHotbarSlot(player);
            int count = (item instanceof net.minecraft.world.item.BucketItem || new ItemStack(item).getMaxStackSize() == 1) ? 1 : 64;
            ItemStack creativeStack = new ItemStack(item, count);
            player.getInventory().setItem(hotbarSlot, creativeStack);
            mc.gameMode.handleCreativeModeItemAdd(creativeStack, 36 + hotbarSlot);
            player.getInventory().selected = hotbarSlot;
            return hotbarSlot;
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
        if (b instanceof LiquidBlock) {
            // Source fluid blocks (water / lava) can be placed via water / lava buckets
            return !state.getFluidState().isSource();
        }
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
    // Visual state accessors (read by PathRenderer for builder overlay)
    // =====================================================================

    /** The scheduler, for visual state queries (last placed pos, tick). */
    public PlacementScheduler getScheduler() { return scheduler; }

    /** Current pending targets list (thread-safe snapshot for PathRenderer). */
    public List<Target> getPendingTargets() { return renderTargetsSnapshot; }

    private void updateRenderSnapshot() {
        if (pending.isEmpty()) {
            this.renderTargetsSnapshot = Collections.emptyList();
        } else {
            int limit = Math.min(pending.size(), 200);
            this.renderTargetsSnapshot = new ArrayList<>(pending.subList(0, limit));
        }
    }

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

    /** Build progress percentage (0 - 100). */
    public int getPercentage() {
        int total = getTotalCount();
        return total > 0 ? (getCompletedCount() * 100 / total) : 0;
    }

    /** Number of blocks remaining to place/clear. */
    public long getRemainingBlocks() {
        return Math.max(0L, (long) getTotalCount() - getCompletedCount());
    }
}
