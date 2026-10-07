/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.event.events.BlockChangeEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.*;
import baritone.api.process.IMineProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.*;
import baritone.api.utils.input.Input;
import baritone.cache.CachedChunk;
import baritone.pathing.calc.OreRouteOptimizer;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.BlockStateInterface;
import baritone.utils.ContinuousBreakController;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.stream.Collectors;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

/**
 * Mine blocks of a certain type
 *
 * @author leijurv
 */
public final class MineProcess extends BaritoneProcessHelper implements IMineProcess, AbstractGameEventListener {

    private static final List<Vec3i> PROXIMITY_OFFSETS_7 = new ArrayList<>();
    static {
        int r = 7;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dy * dy + dz * dz <= r * r) {
                        PROXIMITY_OFFSETS_7.add(new Vec3i(dx, dy, dz));
                    }
                }
            }
        }
        PROXIMITY_OFFSETS_7.sort(Comparator.comparingInt(v -> v.getX() * v.getX() + v.getY() * v.getY() + v.getZ() * v.getZ()));
    }

    private BlockOptionalMetaLookup filter;
    private List<BlockPos> knownOreLocations;
    private List<BlockPos> blacklist; // inaccessible
    private Map<BlockPos, Long> anticipatedDrops;
    private BlockPos branchPoint;
    private GoalRunAway branchPointRunaway;
    private int desiredQuantity;
    private int tickCount;
    private long lastFoundOresLogTime = 0;
    private int lastFoundOresCount = -1;
    private Goal currentGoal;
    private BlockPos currentPrimaryTarget;
    private final Set<BlockPos> activeVeinBlocks = new LinkedHashSet<>();

    /**
     * Sprawdza i aktualizuje aktywne złoże (Vein Lock).
     * Jeśli bot zbliżył się do złoża lub zaczął je kopać, wszystkie powiązane
     * bloki tej żyły zostają zablokowane w activeVeinBlocks.
     * Bot NIE opuści tego złoża, dopóki wszystkie bloki nie zostaną wykopane!
     */
    private void updateActiveVein(CalculationContext context, List<BlockPos> locs, BlockOptionalMetaLookup filter) {
        if (filter == null) {
            activeVeinBlocks.clear();
            return;
        }

        // 1. Usuń ze złoża bloki, które zostały wykopane (są powietrzem) lub trafiły na blacklistę
        activeVeinBlocks.removeIf(pos -> {
            if (blacklist.contains(pos)) return true;
            BlockState state = context.bsi.get0(pos);
            return !filter.has(state) || !plausibleToBreak(context, pos);
        });

        BlockPos playerFeet = ctx.playerFeet();
        if (playerFeet == null) return;

        // 2. Jeśli aktywne złoże ma jeszcze bloki: dynamicznie zbadaj sąsiadów wykopanych bloków
        // (np. gdy wykopanie jednej rudy odsłoniło kolejne rudy w głębi ściany)
        if (!activeVeinBlocks.isEmpty()) {
            expandActiveVein(context, filter, locs);
        } else {
            // 3. Jeśli złoże jest puste, sprawdź czy gracz stoi przy nowym złożu (promień do 4.8 kratek)
            Optional<BlockPos> nearbyOre = locs.stream()
                    .filter(pos -> !blacklist.contains(pos))
                    .filter(pos -> {
                        BlockState state = context.bsi.get0(pos);
                        return filter.has(state) && plausibleToBreak(context, pos);
                    })
                    .filter(pos -> pos.distSqr(playerFeet) <= 23.04)
                    .min(Comparator.comparingDouble(playerFeet::distSqr));

            if (nearbyOre.isPresent()) {
                lockVeinCluster(nearbyOre.get(), locs, context, filter);
            }
        }
    }

    private void lockVeinCluster(BlockPos seed, List<BlockPos> allLocs, CalculationContext context, BlockOptionalMetaLookup filter) {
        activeVeinBlocks.clear();
        activeVeinBlocks.add(seed);
        Queue<BlockPos> queue = new ArrayDeque<>();
        queue.add(seed);

        while (!queue.isEmpty() && activeVeinBlocks.size() < 64) {
            BlockPos current = queue.poll();
            for (BlockPos cand : allLocs) {
                if (!activeVeinBlocks.contains(cand) && !blacklist.contains(cand)) {
                    int dx = current.getX() - cand.getX();
                    int dy = current.getY() - cand.getY();
                    int dz = current.getZ() - cand.getZ();
                    if (Math.abs(dy) <= 3 && (dx * dx + dy * dy + dz * dz <= 18.0)) {
                        BlockState state = context.bsi.get0(cand);
                        if (filter.has(state) && plausibleToBreak(context, cand)) {
                            activeVeinBlocks.add(cand);
                            queue.add(cand);
                        }
                    }
                }
            }
        }
        expandActiveVein(context, filter, allLocs);
    }

    private void expandActiveVein(CalculationContext context, BlockOptionalMetaLookup filter, List<BlockPos> allLocs) {
        if (activeVeinBlocks.isEmpty() || filter == null) return;
        List<BlockPos> currentList = new ArrayList<>(activeVeinBlocks);
        for (BlockPos pos : currentList) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos n = pos.offset(dx, dy, dz);
                        if (!activeVeinBlocks.contains(n) && !blacklist.contains(n)) {
                            if (context.bsi.worldContainsLoadedChunk(n.getX(), n.getZ())) {
                                BlockState state = context.bsi.get0(n);
                                if (filter.has(state) && plausibleToBreak(context, n)) {
                                    activeVeinBlocks.add(n);
                                    if (!allLocs.contains(n)) {
                                        allLocs.add(n);
                                    }
                                    if (!knownOreLocations.contains(n)) {
                                        knownOreLocations.add(n);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static class MiningAction {
        final BlockPos orePos;
        final BlockPos breakTarget;
        final Rotation rotation;

        MiningAction(BlockPos orePos, BlockPos breakTarget, Rotation rotation) {
            this.orePos = orePos;
            this.breakTarget = breakTarget;
            this.rotation = rotation;
        }
    }

    private Optional<MiningAction> findMiningAction(double reach, List<BlockPos> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }

        Vec3 eyes = ctx.player().getEyePosition(1.0F);

        List<BlockPos> orderedCandidates = candidates;
        if (isCuttingLogs()) {
            // Gdy ścinamy drzewa: najpierw ścinaj najwyższy klocek pnia w zasięgu ręki (od góry do dołu!)
            // Zapobiega to utracie kąta widzenia i zostawianiu wiszących pni
            orderedCandidates = new ArrayList<>(candidates);
            orderedCandidates.sort((a, b) -> Integer.compare(b.getY(), a.getY()));
        }

        // 1. Sprawdz, czy ktorakolwiek docelowa ruda jest bezposrednio osiagalna (widoczna)
        for (BlockPos pos : orderedCandidates) {
            if (BlockStateInterface.get(ctx, pos).getBlock() instanceof AirBlock) continue;
            if (MovementHelper.avoidBreaking(baritone.bsi, pos.getX(), pos.getY(), pos.getZ(), baritone.bsi.get0(pos))) continue;

            Optional<Rotation> rot = RotationUtils.reachable(ctx, pos, reach);
            if (rot.isPresent()) {
                HitResult trace = RayTraceUtils.rayTraceTowards(ctx.player(), rot.get(), reach, false);
                BlockPos hit = (trace instanceof BlockHitResult bhr && trace.getType() == HitResult.Type.BLOCK) ? bhr.getBlockPos() : pos;
                return Optional.of(new MiningAction(pos, hit, rot.get()));
            }
        }

        // 2. Jesli zadna ruda nie jest bezposrednio widoczna, sprawdz rudy znajdujace sie w zasiegu (<= 3.8 kratek),
        // ktore sa zasloniete przez mozliwy do rozbicia blok sciany (np. Deepslate, Stone, Tuff itp.).
        // Umozliwia to botowi wykopanie sciany ze stabilnego podloza bez zapetlania skakania!
        for (BlockPos pos : orderedCandidates) {
            if (BlockStateInterface.get(ctx, pos).getBlock() instanceof AirBlock) continue;
            if (MovementHelper.avoidBreaking(baritone.bsi, pos.getX(), pos.getY(), pos.getZ(), baritone.bsi.get0(pos))) continue;

            double dist = GoalOreMining.distanceToBlock(eyes.x, eyes.y, eyes.z, pos.getX(), pos.getY(), pos.getZ());
            if (dist > reach - 0.1) continue;

            Vec3 center = VecUtils.calculateBlockCenter(ctx.world(), pos);
            Vec3[] targets = new Vec3[]{
                    center,
                    new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5), // dol
                    new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5), // gora
                    new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ()), // polnoc
                    new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 1.0), // poludnie
                    new Vec3(pos.getX(), pos.getY() + 0.5, pos.getZ() + 0.5), // zachod
                    new Vec3(pos.getX() + 1.0, pos.getY() + 0.5, pos.getZ() + 0.5)  // wschod
            };

            for (Vec3 t : targets) {
                if (eyes.distanceTo(t) > reach) continue;
                Rotation rot = RotationUtils.calcRotationFromVec3d(eyes, t, ctx.playerRotations());
                HitResult trace = RayTraceUtils.rayTraceTowards(ctx.player(), rot, reach, false);
                if (trace instanceof BlockHitResult bhr && trace.getType() == HitResult.Type.BLOCK) {
                    BlockPos hitBlock = bhr.getBlockPos();
                    if (hitBlock.equals(pos)) {
                        return Optional.of(new MiningAction(pos, pos, rot));
                    }
                    BlockState hitState = ctx.world().getBlockState(hitBlock);
                    if (!hitState.isAir() && hitState.getDestroySpeed(ctx.world(), hitBlock) >= 0) {
                        if (!MovementHelper.avoidBreaking(baritone.bsi, hitBlock.getX(), hitBlock.getY(), hitBlock.getZ(), hitState)) {
                            if (!(baritone.getFarmProcess().isActive() && MovementHelper.isFarmSoilOrStructure(hitState.getBlock()))) {
                                return Optional.of(new MiningAction(pos, hitBlock, rot));
                            }
                        }
                    }
                }
            }
        }

        return Optional.empty();
    }

    public MineProcess(Baritone baritone) {
        super(baritone);
        baritone.getGameEventHandler().registerEventListener(this);
    }

    @Override
    public boolean isActive() {
        return filter != null;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (desiredQuantity > 0) {
            int curr = ctx.player().getInventory().items.stream()
                    .filter(stack -> filter.has(stack))
                    .mapToInt(ItemStack::getCount).sum();
            if (curr >= desiredQuantity) {
                logDirect("Have " + curr + " valid items");
                cancel();
                return null;
            }
        }
        if (calcFailed) {
            if (currentPrimaryTarget != null) {
                blacklist.add(currentPrimaryTarget);
                currentPrimaryTarget = null;
                currentGoal = null;
            }
            if (!knownOreLocations.isEmpty() && Baritone.settings().blacklistClosestOnFailure.value) {
                logDirect("Unable to find any path to " + filter + ", blacklisting presumably unreachable closest instance...");
                if (Baritone.settings().notificationOnMineFail.value) {
                    logNotification("Unable to find any path to " + filter + ", blacklisting presumably unreachable closest instance...", true);
                }
                knownOreLocations.stream().min(Comparator.comparingDouble(ctx.playerFeet()::distSqr)).ifPresent(blacklist::add);
                knownOreLocations.removeIf(blacklist::contains);
            } else {
                logDirect("Unable to find any path to " + filter + ", canceling mine");
                if (Baritone.settings().notificationOnMineFail.value) {
                    logNotification("Unable to find any path to " + filter + ", canceling mine", true);
                }
                cancel();
                return null;
            }
        }

        updateLoucaSystem();

        // Aktywne dynamiczne wykrywanie rud (Anti-Xray) co pol sekundy (10 tickow) w promieniu 7 kratek
        int antiXrayInterval = Baritone.settings().antiXrayScanIntervalTicks.value;
        if (antiXrayInterval > 0 && tickCount % antiXrayInterval == 0) {
            scanNearbyAntiXray();
        }

        int mineGoalUpdateInterval = Baritone.settings().mineGoalUpdateInterval.value;
        List<BlockPos> curr = new ArrayList<>(knownOreLocations);
        CalculationContext scanContext = new CalculationContext(baritone);
        updateActiveVein(scanContext, curr, filter);

        if (mineGoalUpdateInterval != 0 && tickCount++ % mineGoalUpdateInterval == 0 && !baritone.getPathingBehavior().isPathing()) { // big brain
            CalculationContext context = new CalculationContext(baritone, true);
            List<BlockPos> dropped = droppedItemsScan();
            Baritone.getExecutor().execute(() -> rescan(curr, context, dropped));
        }
        if (Baritone.settings().legitMine.value) {
            if (!addNearby()) {
                cancel();
                return null;
            }
        }
        double reach = Math.min(3.9, ctx.playerController().getBlockReachDistance());
        Optional<BlockPos> shaft = curr.stream()
                .filter(pos -> pos.getX() == ctx.playerFeet().getX() && pos.getZ() == ctx.playerFeet().getZ())
                .filter(pos -> pos.getY() >= ctx.playerFeet().getY())
                .filter(pos -> !(BlockStateInterface.get(ctx, pos).getBlock() instanceof AirBlock)) // after breaking a block, it takes mineGoalUpdateInterval ticks for it to actually update this list =(
                .min(Comparator.comparingDouble(ctx.playerFeet().above()::distSqr));
        if (shaft.isPresent() && ctx.player().onGround()) {
            BlockPos pos = shaft.get();
            BlockState state = baritone.bsi.get0(pos);
            if (!MovementHelper.avoidBreaking(baritone.bsi, pos.getX(), pos.getY(), pos.getZ(), state)) {
                Optional<Rotation> rot = RotationUtils.reachable(ctx, pos, reach);
                if (rot.isPresent() && isSafeToCancel) {
                    baritone.getInputOverrideHandler().clearAllKeys();
                    baritone.getLookBehavior().updateTarget(rot.get(), true);

                    HitResult trace = RayTraceUtils.rayTraceTowards(ctx.player(), ctx.playerRotations(), reach, false);
                    BlockPos hitBlock = (trace instanceof BlockHitResult bhr && trace.getType() == HitResult.Type.BLOCK) ? bhr.getBlockPos() : null;
                    BlockPos breakTarget = pos;
                    if (hitBlock != null && !hitBlock.equals(pos)) {
                        BlockState hitState = ctx.world().getBlockState(hitBlock);
                        if (ObstructionHelper.isClearableObstruction(hitState) && ObstructionHelper.isCoveringOrAdjacent(hitBlock, pos)) {
                            breakTarget = hitBlock;
                        }
                    }

                    MovementHelper.switchToBestToolFor(ctx, ctx.world().getBlockState(breakTarget));
                    boolean hitsTarget = hitBlock != null && (hitBlock.equals(pos) || hitBlock.equals(breakTarget));
                    boolean lookingAtTarget = ctx.isLookingAt(pos) || ctx.isLookingAt(breakTarget);

                    if (lookingAtTarget || hitsTarget || ctx.playerRotations().isReallyCloseTo(rot.get())
                            || ContinuousBreakController.shouldHoldThrough(ctx, new BetterBlockPos(breakTarget), rot.get())) {
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                        ContinuousBreakController.notifyBreaking(ctx, new BetterBlockPos(breakTarget));
                    }
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            }
        }

        // Vein Lock & Direct Reach: Jeśli jakakolwiek docelowa ruda (zwłaszcza z aktywnego złoża)
        // jest w bezpośrednim zasięgu gracza (bez ruszania się), wykop ją natychmiast!
        // Używamy bezpiecznego reach 3.9, aby bot nie próbował celować w rudę poza faktycznym zasięgiem.
        Optional<MiningAction> miningAction = Optional.empty();

        if (!activeVeinBlocks.isEmpty()) {
            miningAction = findMiningAction(reach, new ArrayList<>(activeVeinBlocks));
        }

        if (!miningAction.isPresent()) {
            miningAction = findMiningAction(reach, curr);
        }

        if (miningAction.isPresent()) {
            // Natychmiast zwalniamy klawisz skoku — cel jest w zasięgu ze stabilnego podłoża!
            baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, false);

            boolean isGroundedOrLiquid = ctx.player().onGround() || ctx.player().isInWater()
                    || MovementHelper.isClimbable(BlockStateInterface.getBlock(ctx, ctx.playerFeet()));
            if (isGroundedOrLiquid) {
                MiningAction action = miningAction.get();
                BlockPos pos = action.orePos;
                BlockPos breakTarget = action.breakTarget;
                Rotation rot = action.rotation;

                if (activeVeinBlocks.isEmpty()) {
                    lockVeinCluster(pos, curr, scanContext, filter);
                }

                baritone.getInputOverrideHandler().clearAllKeys();
                baritone.getLookBehavior().updateTarget(rot, true);

                MovementHelper.switchToBestToolFor(ctx, ctx.world().getBlockState(breakTarget));

                HitResult trace = RayTraceUtils.rayTraceTowards(ctx.player(), ctx.playerRotations(), reach, false);
                BlockPos hitBlock = (trace instanceof BlockHitResult bhr && trace.getType() == HitResult.Type.BLOCK) ? bhr.getBlockPos() : null;

                boolean hitsTarget = hitBlock != null && (hitBlock.equals(pos) || hitBlock.equals(breakTarget));
                boolean lookingAtTarget = ctx.isLookingAt(pos) || ctx.isLookingAt(breakTarget);

                if (lookingAtTarget || hitsTarget || ctx.playerRotations().isReallyCloseTo(rot)
                        || ContinuousBreakController.shouldHoldThrough(ctx, new BetterBlockPos(breakTarget), rot)) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                    ContinuousBreakController.notifyBreaking(ctx, new BetterBlockPos(breakTarget));
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }
        PathingCommand command = updateGoal();
        if (command == null) {
            // none in range
            // maybe say something in chat? (ahem impact)
            cancel();
            return null;
        }
        return command;
    }


    private void updateLoucaSystem() {
        Map<BlockPos, Long> copy = new HashMap<>(anticipatedDrops);
        ctx.getSelectedBlock().ifPresent(pos -> {
            if (knownOreLocations.contains(pos)) {
                copy.put(pos, System.currentTimeMillis() + Baritone.settings().mineDropLoiterDurationMSThanksLouca.value);
            }
        });
        // elaborate dance to avoid concurrentmodificationexcepption since rescan thread reads this
        // don't want to slow everything down with a gross lock do we now
        for (BlockPos pos : anticipatedDrops.keySet()) {
            if (copy.get(pos) < System.currentTimeMillis()) {
                copy.remove(pos);
            }
        }
        anticipatedDrops = copy;
    }

    @Override
    public void onLostControl() {
        activeVeinBlocks.clear();
        mine(0, (BlockOptionalMetaLookup) null);
    }

    @Override
    public String displayName0() {
        if (filter == null) {
            return "Mine";
        }
        var blocks = filter.blocks();
        if (blocks == null || blocks.isEmpty()) {
            return "Mine";
        }
        String first = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(blocks.get(0).getBlock()).getPath();
        if (blocks.size() == 1) {
            return "Mine " + first;
        }
        return "Mine " + first + " (+" + (blocks.size() - 1) + ")";
    }

    public static boolean isLogFilter(BlockOptionalMetaLookup filter) {
        if (filter == null) return false;
        var blocks = filter.blocks();
        if (blocks == null || blocks.isEmpty()) return false;
        for (var b : blocks) {
            String path = BuiltInRegistries.BLOCK.getKey(b.getBlock()).getPath();
            if (path.endsWith("_log") || path.endsWith("_wood") || path.endsWith("_stem") || path.endsWith("_hyphae")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isCuttingLogs() {
        return isActive() && isLogFilter(filter);
    }

    private PathingCommand updateGoal() {
        BlockOptionalMetaLookup filter = filterFilter();
        if (filter == null) {
            return null;
        }

        boolean legit = Baritone.settings().legitMine.value;

        // CAŁKOWITY VEIN LOCK (Dokończ całe złoże przed przejściem do innego):
        // Jeśli bot posiada aktywne złoże (activeVeinBlocks), cel ścieżki i planowanie ograniczamy
        // W 100% wyłącznie do bloków tego złoża! Bot NIE MOŻE przejść do innego odległego złoża!
        if (!activeVeinBlocks.isEmpty()) {
            CalculationContext context = new CalculationContext(baritone);
            List<BlockPos> veinList = new ArrayList<>(activeVeinBlocks);
            Goal goal = new GoalComposite(veinList.stream().map(loc -> coalesce(loc, veinList, context)).toArray(Goal[]::new));
            currentGoal = goal;
            currentPrimaryTarget = veinList.get(0);
            return new PathingCommand(goal, legit ? PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH : PathingCommandType.SET_GOAL_AND_PATH);
        }

        // Blokada pętli ciągłego replanowania (Anti-Replan Loop):
        // Jeśli bot aktywnie idzie po wyznaczonej trasie lub oblicza ścieżkę do celu (hasPath lub inProgress),
        // a cel główny nadal istnieje w świecie i nie został jeszcze wykopany:
        // NIE przeliczaj na nowo TSP i NIE soft-canceluj trasy! Pozwól botowi płynnie iść do celu.
        boolean isPathingOrPlanning = baritone.getPathingBehavior().hasPath() || baritone.getPathingBehavior().getInProgress().isPresent();
        if (isPathingOrPlanning && currentGoal != null && currentPrimaryTarget != null) {
            BlockState targetState = baritone.bsi.get0(currentPrimaryTarget);
            if (filter.has(targetState) && !blacklist.contains(currentPrimaryTarget)) {
                return new PathingCommand(currentGoal, PathingCommandType.SET_GOAL_AND_PATH);
            }
        }

        List<BlockPos> locs = knownOreLocations;
        if (!locs.isEmpty()) {
            CalculationContext context = new CalculationContext(baritone);
            expandVeins(context, locs, filter);
            List<BlockPos> locs2 = prune(context, new ArrayList<>(locs), filter, Baritone.settings().mineMaxOreLocationsCount.value, blacklist, droppedItemsScan());
            if (!locs2.isEmpty()) {
                List<BlockPos> activeWindow = getActiveGoalWindow(locs2, 12);
                Goal goal = new GoalComposite(activeWindow.stream().map(loc -> coalesce(loc, locs2, context)).toArray(Goal[]::new));
                currentGoal = goal;
                currentPrimaryTarget = locs2.get(0);
                knownOreLocations = locs2;

                return new PathingCommand(goal, legit ? PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH : PathingCommandType.SET_GOAL_AND_PATH);
            }
            knownOreLocations = locs2;
            currentGoal = null;
            currentPrimaryTarget = null;
        }
        // we don't know any ore locations at the moment
        if (!legit && !Baritone.settings().exploreForBlocks.value && !Baritone.settings().antiXrayBypass.value) {
            return null;
        }
        // only when we should explore for blocks or are in legit mode we do this
        int y = getOptimalYLevelForFilter(filter);
        if (branchPoint == null) {
            /*if (!baritone.getPathingBehavior().isPathing() && playerFeet().y == y) {
                // cool, path is over and we are at desired y
                branchPoint = playerFeet();
                branchPointRunaway = null;
            } else {
                return new GoalYLevel(y);
            }*/
            branchPoint = ctx.playerFeet();
        }
        // TODO shaft mode, mine 1x1 shafts to either side
        // TODO also, see if the GoalRunAway with maintain Y at 11 works even from the surface
        if (branchPointRunaway == null) {
            branchPointRunaway = new GoalRunAway(1, y, branchPoint) {
                @Override
                public boolean isInGoal(int x, int y, int z) {
                    return false;
                }

                @Override
                public double heuristic() {
                    return Double.NEGATIVE_INFINITY;
                }
            };
        }
        // Jeśli bot drąży tunel poszukiwawczy (Anti-Xray / exploreForBlocks), kontynuuj bez ciągłego rewalidowania i kasowania
        return new PathingCommand(branchPointRunaway, PathingCommandType.SET_GOAL_AND_PATH);
    }

    private void rescan(List<BlockPos> already, CalculationContext context, List<BlockPos> dropped) {
        BlockOptionalMetaLookup filter = filterFilter();
        if (filter == null) {
            return;
        }
        if (Baritone.settings().legitMine.value) {
            return;
        }
        if (dropped == null) {
            dropped = ctx.minecraft().isSameThread() ? droppedItemsScan() : Collections.emptyList();
        }
        List<BlockPos> locs = new ArrayList<>(searchWorld(context, filter, Baritone.settings().mineMaxOreLocationsCount.value, already, blacklist, dropped));
        locs.addAll(dropped);
        if (locs.isEmpty()) {
            if (ctx.minecraft().isSameThread()) {
                scanNearbyAntiXray();
            } else {
                ctx.minecraft().execute(this::scanNearbyAntiXray);
            }
            if (!knownOreLocations.isEmpty()) {
                return;
            }
            if (Baritone.settings().antiXrayBypass.value || Baritone.settings().exploreForBlocks.value) {
                logDirect("§e[Anti-Xray] Brak widocznych rud w pamieci chunkow (serwer maskuje zloza).");
                logDirect(String.format("§a[Anti-Xray] Aktywne poszukiwanie na poziomie Y=%d oraz skanowanie %d kratek co 0.5s!",
                        getOptimalYLevelForFilter(filter), Baritone.settings().antiXrayScanRadius.value));
            } else {
                logDirect("No locations for " + filter + " known, cancelling");
                if (Baritone.settings().notificationOnMineFail.value) {
                    logNotification("No locations for " + filter + " known, cancelling", true);
                }
                cancel();
                return;
            }
        } else {
            long now = System.currentTimeMillis();
            if (now - lastFoundOresLogTime > 4000L || Math.abs(locs.size() - lastFoundOresCount) > 10) {
                logDirect(String.format("§a[Wykrywanie] Znaleziono §f%d §azłóż w załadowanych chunkach! Obliczam optymalną trasę...", locs.size()));
                lastFoundOresLogTime = now;
                lastFoundOresCount = locs.size();
            }
        }
        knownOreLocations = new ArrayList<>(locs);
    }

    private boolean internalMiningGoal(BlockPos pos, CalculationContext context, List<BlockPos> locs) {
        // Here, BlockStateInterface is used because the position may be in a cached chunk (the targeted block is one that is kept track of)
        if (locs.contains(pos)) {
            return true;
        }
        BlockState state = context.bsi.get0(pos);
        if (Baritone.settings().internalMiningAirException.value && state.getBlock() instanceof AirBlock) {
            return true;
        }
        return filter.has(state) && plausibleToBreak(context, pos);
    }

    private Goal coalesce(BlockPos loc, List<BlockPos> locs, CalculationContext context) {
        if (droppedItemsScan().contains(loc)) {
            return new GoalBlock(loc);
        }
        if (!Baritone.settings().forceInternalMining.value) {
            // GoalOreMining pozwala na kopanie rudy z sąsiadującej stabilnej powierzchni (podłogi)
            // w zasięgu 3.9 bez konieczności wskakiwania w ścianę lub stawiania pod sobą klocków (eliminacja pętli skakania).
            return new GoalOreMining(loc);
        }
        boolean assumeVerticalShaftMine = !(baritone.bsi.get0(loc.above()).getBlock() instanceof FallingBlock);
        boolean upwardGoal = internalMiningGoal(loc.above(), context, locs);
        boolean downwardGoal = internalMiningGoal(loc.below(), context, locs);
        boolean doubleDownwardGoal = internalMiningGoal(loc.below(2), context, locs);
        if (upwardGoal == downwardGoal) { // symmetric
            if (doubleDownwardGoal && assumeVerticalShaftMine) {
                // we have a checkerboard like pattern
                // this one, and the one two below it
                // therefore it's fine to path to immediately below this one, since your feet will be in the doubleDownwardGoal
                // but only if assumeVerticalShaftMine
                return new GoalThreeBlocks(loc);
            } else {
                // this block has nothing interesting two below, but is symmetric vertically so we can get either feet or head into it
                return new GoalTwoBlocks(loc);
            }
        }
        if (upwardGoal) {
            // downwardGoal known to be false
            // ignore the gap then potential doubleDownward, because we want to path feet into this one and head into upwardGoal
            return new GoalBlock(loc);
        }
        // upwardGoal known to be false, downwardGoal known to be true
        if (doubleDownwardGoal && assumeVerticalShaftMine) {
            // this block and two below it are goals
            // path into the center of the one below, because that includes directly below this one
            return new GoalTwoBlocks(loc.below());
        }
        // upwardGoal false, downwardGoal true, doubleDownwardGoal false
        // just this block and the one immediately below, no others
        return new GoalBlock(loc.below());
    }

    private static class GoalThreeBlocks extends GoalTwoBlocks {

        public GoalThreeBlocks(BlockPos pos) {
            super(pos);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            return x == this.x && (y == this.y || y == this.y - 1 || y == this.y - 2) && z == this.z;
        }

        @Override
        public double heuristic(int x, int y, int z) {
            int xDiff = x - this.x;
            int yDiff = y - this.y;
            int zDiff = z - this.z;
            return GoalBlock.calculate(xDiff, yDiff < -1 ? yDiff + 2 : yDiff == -1 ? 0 : yDiff, zDiff);
        }

        @Override
        public boolean equals(Object o) {
            return super.equals(o);
        }

        @Override
        public int hashCode() {
            return super.hashCode() * 393857768;
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalThreeBlocks{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }
    }

    public List<BlockPos> droppedItemsScan() {
        if (!Baritone.settings().mineScanDroppedItems.value) {
            return Collections.emptyList();
        }
        if (!ctx.minecraft().isSameThread()) {
            return Collections.emptyList();
        }
        List<BlockPos> ret = new ArrayList<>();
        BlockStateInterface bsi = new BlockStateInterface(ctx);
        for (Entity entity : ((ClientLevel) ctx.world()).entitiesForRendering()) {
            if (entity instanceof ItemEntity) {
                ItemEntity ei = (ItemEntity) entity;
                if (filter.has(ei.getItem())) {
                    BlockPos bp = entity.blockPosition();
                    if (Baritone.settings().mineAvoidLava.value) {
                        BlockState state = bsi.get0(bp);
                        BlockState below = bsi.get0(bp.below());
                        if (MovementHelper.isLava(state) || MovementHelper.isLava(below)
                                || MovementHelper.isLavaPitBelow(bsi, bp.getX(), bp.getY(), bp.getZ())) {
                            continue; // Ignoruj przedmioty w lawie lub nad jeziorem lawy
                        }
                    }
                    if (Baritone.settings().mineAvoidWater.value) {
                        BlockState state = bsi.get0(bp);
                        BlockState below = bsi.get0(bp.below());
                        if (MovementHelper.isWater(state) || MovementHelper.isWater(below)) {
                            continue; // Ignoruj przedmioty w wodzie
                        }
                    }
                    ret.add(bp);
                }
            }
        }
        for (BlockPos pos : anticipatedDrops.keySet()) {
            if (Baritone.settings().mineAvoidLava.value) {
                BlockState state = bsi.get0(pos);
                BlockState below = bsi.get0(pos.below());
                if (MovementHelper.isLava(state) || MovementHelper.isLava(below)
                        || MovementHelper.isLavaPitBelow(bsi, pos.getX(), pos.getY(), pos.getZ())) {
                    continue;
                }
            }
            if (Baritone.settings().mineAvoidWater.value) {
                BlockState state = bsi.get0(pos);
                BlockState below = bsi.get0(pos.below());
                if (MovementHelper.isWater(state) || MovementHelper.isWater(below)) {
                    continue;
                }
            }
            ret.add(pos);
        }
        return ret;
    }

    public static List<BlockPos> searchWorld(CalculationContext ctx, BlockOptionalMetaLookup filter, int max, List<BlockPos> alreadyKnown, List<BlockPos> blacklist, List<BlockPos> dropped) {
        List<BlockPos> locs = new ArrayList<>();
        List<Block> untracked = new ArrayList<>();
        for (BlockOptionalMeta bom : filter.blocks()) {
            Block block = bom.getBlock();
            if (CachedChunk.BLOCKS_TO_KEEP_TRACK_OF.contains(block)) {
                BetterBlockPos pf = ctx.baritone.getPlayerContext().playerFeet();

                // maxRegionDistanceSq 2 means adjacent directly or adjacent diagonally; nothing further than that
                locs.addAll(ctx.worldData.getCachedWorld().getLocationsOf(
                        BlockUtils.blockToString(block),
                        Baritone.settings().maxCachedWorldScanCount.value,
                        pf.x,
                        pf.z,
                        2
                ));
            } else {
                untracked.add(block);
            }
        }

        List<BlockPos> initial = prune(ctx, locs, filter, max, blacklist, dropped);
        locs = new ArrayList<>(initial);

        if (!untracked.isEmpty() || (Baritone.settings().extendCacheOnThreshold.value && locs.size() < max)) {
            List<BlockPos> scanned = BaritoneAPI.getProvider().getWorldScanner().scanChunkRadius(
                    ctx.getBaritone().getPlayerContext(),
                    filter,
                    max,
                    10,
                    32
            );
            if (scanned != null && !scanned.isEmpty()) {
                locs.addAll(scanned);
            }
        }

        if (alreadyKnown != null && !alreadyKnown.isEmpty()) {
            locs.addAll(alreadyKnown);
        }

        expandVeins(ctx, locs, filter);

        return new ArrayList<>(prune(ctx, locs, filter, max, blacklist, dropped));
    }

    public static void expandVeins(CalculationContext ctx, List<BlockPos> locs, BlockOptionalMetaLookup filter) {
        if (locs == null || locs.isEmpty() || filter == null) {
            return;
        }
        Set<BlockPos> knownSet = new HashSet<>(locs);
        Queue<BlockPos> queue = new ArrayDeque<>(locs);
        int added = 0;
        int maxAdditions = 128; // expand up to 128 additional connected vein blocks

        while (!queue.isEmpty() && added < maxAdditions) {
            BlockPos current = queue.poll();
            // Check all 26 surrounding neighbors (orthogonal, edge-adjacent, and corner-adjacent)
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos neighbor = current.offset(dx, dy, dz);
                        if (knownSet.add(neighbor)) {
                            if (ctx.bsi.worldContainsLoadedChunk(neighbor.getX(), neighbor.getZ())) {
                                BlockState state = ctx.bsi.get0(neighbor);
                                if (filter.has(state) && plausibleToBreak(ctx, neighbor)) {
                                    locs.add(neighbor);
                                    queue.add(neighbor);
                                    added++;
                                    if (added >= maxAdditions) {
                                        return;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Skanuje otoczenie gracza w promieniu 7 kratek (domyslnie) w poszukiwaniu rud,
     * ktore zostaly ujawnione przez ruch gracza (np. na serwerach z Anti-Xray).
     * Dziala co 0.5s (10 tickow) niezaleznie od tego, czy bot sie porusza.
     */
    public boolean scanNearbyAntiXray() {
        if (!isActive() || filter == null) {
            return false;
        }
        BlockPos playerFeet = ctx.playerFeet();
        if (playerFeet == null || ctx.world() == null) {
            return false;
        }
        CalculationContext context = new CalculationContext(baritone, true);
        List<BlockPos> newlyFound = new ArrayList<>();
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        int px = playerFeet.getX();
        int py = playerFeet.getY();
        int pz = playerFeet.getZ();

        int radius = Baritone.settings().antiXrayScanRadius.value;
        int radiusSq = radius * radius;

        for (Vec3i off : PROXIMITY_OFFSETS_7) {
            if (off.getX() * off.getX() + off.getY() * off.getY() + off.getZ() * off.getZ() > radiusSq) {
                continue;
            }
            mut.set(px + off.getX(), py + off.getY(), pz + off.getZ());
            if (!context.bsi.worldContainsLoadedChunk(mut.getX(), mut.getZ())) {
                continue;
            }
            BlockState state = context.bsi.get0(mut.getX(), mut.getY(), mut.getZ());
            if (filter.has(state)) {
                BlockPos pos = mut.immutable();
                if (!blacklist.contains(pos) && !knownOreLocations.contains(pos) && !newlyFound.contains(pos)) {
                    if (plausibleToBreak(context, pos)) {
                        newlyFound.add(pos);
                    }
                }
            }
        }

        if (!newlyFound.isEmpty()) {
            boolean wasEmpty = (knownOreLocations == null || knownOreLocations.isEmpty());
            List<BlockPos> combined = new ArrayList<>();
            if (knownOreLocations != null) {
                combined.addAll(knownOreLocations);
            }
            combined.addAll(newlyFound);
            expandVeins(context, combined, filter);
            knownOreLocations = new ArrayList<>(prune(context, combined, filter, Baritone.settings().mineMaxOreLocationsCount.value, blacklist, droppedItemsScan()));

            BlockPos first = newlyFound.get(0);
            BlockState st = context.bsi.get0(first.getX(), first.getY(), first.getZ());
            String blockName = BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
            logDirect(String.format("§a[Anti-Xray] Wykryto nowa rude w promieniu %d kratek: §f%s §7(nowe: %d, lacznie w pamieci: %d)",
                    radius, blockName, newlyFound.size(), knownOreLocations.size()));

            if (wasEmpty || branchPointRunaway != null) {
                branchPoint = null;
                branchPointRunaway = null;
            }
            return true;
        }
        return false;
    }

    /**
     * Zwraca optymalny poziom Y dla poszukiwania danej rudy, aby bot nie schodzil
     * bezsensownie na y=-59 gdy szuka zelaza, wegla lub drewna.
     */
    private int getOptimalYLevelForFilter(BlockOptionalMetaLookup filter) {
        if (filter != null) {
            for (BlockOptionalMeta bom : filter.blocks()) {
                Block b = bom.getBlock();
                if (b == Blocks.DIAMOND_ORE || b == Blocks.DEEPSLATE_DIAMOND_ORE) {
                    return -58;
                }
                if (b == Blocks.ANCIENT_DEBRIS) {
                    return 15;
                }
                if (b == Blocks.IRON_ORE || b == Blocks.DEEPSLATE_IRON_ORE || b == Blocks.RAW_IRON_BLOCK) {
                    int currentY = ctx.playerFeet().getY();
                    return currentY < 0 ? -16 : 16;
                }
                if (b == Blocks.GOLD_ORE || b == Blocks.DEEPSLATE_GOLD_ORE || b == Blocks.RAW_GOLD_BLOCK) {
                    return -16;
                }
                if (b == Blocks.COPPER_ORE || b == Blocks.DEEPSLATE_COPPER_ORE || b == Blocks.RAW_COPPER_BLOCK) {
                    return 48;
                }
                if (b == Blocks.COAL_ORE || b == Blocks.DEEPSLATE_COAL_ORE) {
                    return 48;
                }
                if (b == Blocks.REDSTONE_ORE || b == Blocks.DEEPSLATE_REDSTONE_ORE) {
                    return -58;
                }
                if (b == Blocks.LAPIS_ORE || b == Blocks.DEEPSLATE_LAPIS_ORE) {
                    return 0;
                }
                if (b == Blocks.EMERALD_ORE || b == Blocks.DEEPSLATE_EMERALD_ORE) {
                    return 100;
                }
                if (b == Blocks.NETHER_GOLD_ORE || b == Blocks.NETHER_QUARTZ_ORE) {
                    return 15;
                }
                String path = BuiltInRegistries.BLOCK.getKey(b).getPath();
                if (path.endsWith("_log") || path.endsWith("_stem") || path.endsWith("_wood") || path.endsWith("_hyphae")) {
                    return ctx.playerFeet().getY();
                }
            }
        }
        return Baritone.settings().legitMineYLevel.value;
    }

    @Override
    public void onBlockChange(BlockChangeEvent event) {
        if (!isActive() || filter == null || event == null || event.getBlocks() == null) {
            return;
        }
        boolean foundNew = false;
        CalculationContext context = new CalculationContext(baritone, true);
        if (knownOreLocations == null) {
            knownOreLocations = new ArrayList<>();
        }
        List<BlockPos> combined = new ArrayList<>(knownOreLocations);
        for (Pair<BlockPos, BlockState> pair : event.getBlocks()) {
            BlockPos pos = pair.first();
            BlockState state = pair.second();
            if (filter.has(state) && !blacklist.contains(pos) && !combined.contains(pos)) {
                if (plausibleToBreak(context, pos)) {
                    combined.add(pos);
                    foundNew = true;
                }
            }
        }
        if (foundNew) {
            expandVeins(context, combined, filter);
            knownOreLocations = new ArrayList<>(prune(context, combined, filter, Baritone.settings().mineMaxOreLocationsCount.value, blacklist, droppedItemsScan()));
            if (branchPointRunaway != null) {
                branchPoint = null;
                branchPointRunaway = null;
            }
            logDirect(String.format("§a[Świat] Wykryto ujawniona rude z aktualizacji bloku! (lacznie w pamieci: %d)", knownOreLocations.size()));
        }
    }

    private boolean addNearby() {
        List<BlockPos> dropped = droppedItemsScan();
        knownOreLocations.addAll(dropped);
        BlockPos playerFeet = ctx.playerFeet();
        BlockStateInterface bsi = new BlockStateInterface(ctx);


        BlockOptionalMetaLookup filter = filterFilter();
        if (filter == null) {
            return false;
        }

        int searchDist = 10;
        double fakedBlockReachDistance = 20; // at least 10 * sqrt(3) with some extra space to account for positioning within the block
        for (int x = playerFeet.getX() - searchDist; x <= playerFeet.getX() + searchDist; x++) {
            for (int y = playerFeet.getY() - searchDist; y <= playerFeet.getY() + searchDist; y++) {
                for (int z = playerFeet.getZ() - searchDist; z <= playerFeet.getZ() + searchDist; z++) {
                    // crucial to only add blocks we can see because otherwise this
                    // is an x-ray and it'll get caught
                    if (filter.has(bsi.get0(x, y, z))) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if ((Baritone.settings().legitMineIncludeDiagonals.value && knownOreLocations.stream().anyMatch(ore -> ore.distSqr(pos) <= 2 /* sq means this is pytha dist <= sqrt(2) */)) || RotationUtils.reachable(ctx, pos, fakedBlockReachDistance).isPresent()) {
                            knownOreLocations.add(pos);
                        }
                    }
                }
            }
        }
        expandVeins(new CalculationContext(baritone), knownOreLocations, filter);
        knownOreLocations = prune(new CalculationContext(baritone), knownOreLocations, filter, Baritone.settings().mineMaxOreLocationsCount.value, blacklist, dropped);
        return true;
    }

    /**
     * Sprawdza, czy pień drzewa pos jest bezpiecznie osiągalny ze stabilnego podłoża pod nim / w pobliżu.
     * Zapobiega planowaniu kopania pni wiszących 6-10 kratek w powietrzu w koronach wysokich drzew (np. świerki).
     */
    public static boolean isReachableGroundLog(CalculationContext ctx, BlockPos pos) {
        int posX = pos.getX();
        int posY = pos.getY();
        int posZ = pos.getZ();

        for (int dy = 1; dy <= 5; dy++) {
            int checkY = posY - dy;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int x = posX + dx;
                    int z = posZ + dz;
                    BlockState state = ctx.get(x, checkY, z);
                    Block block = state.getBlock();
                    if (block instanceof AirBlock || block instanceof LeavesBlock) {
                        continue;
                    }
                    String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
                    if (path.endsWith("_log") || path.endsWith("_wood") || path.endsWith("_stem") || path.endsWith("_hyphae")) {
                        continue; // kolejny pień nie jest stabilnym gruntem (zostanie ścięty)
                    }
                    if (MovementHelper.canWalkOn(ctx, x, checkY, z, state)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static List<BlockPos> prune(CalculationContext ctx, List<BlockPos> locs2, BlockOptionalMetaLookup filter, int max, List<BlockPos> blacklist, List<BlockPos> dropped) {
        dropped.removeIf(drop -> {
            if (Baritone.settings().mineAvoidLava.value && MovementHelper.isLavaPitBelow(ctx.bsi, drop.getX(), drop.getY(), drop.getZ())) {
                return true;
            }
            if (Baritone.settings().mineAvoidWater.value) {
                BlockState state = ctx.get(drop.getX(), drop.getY(), drop.getZ());
                BlockState below = ctx.get(drop.getX(), drop.getY() - 1, drop.getZ());
                if (MovementHelper.isWater(state) || MovementHelper.isWater(below)) {
                    return true;
                }
            }
            for (BlockPos pos : locs2) {
                if (pos.distSqr(drop) <= 9 && filter.has(ctx.get(pos.getX(), pos.getY(), pos.getZ())) && MineProcess.plausibleToBreak(ctx, pos)) {
                    return true;
                }
            }
            return false;
        });
        List<BlockPos> locs = locs2
                .stream()
                .distinct()

                // remove any that aren't actually what we want
                .filter(pos -> {
                    if (dropped.contains(pos)) {
                        return true;
                    }
                    if (ctx.bsi.worldContainsLoadedChunk(pos.getX(), pos.getZ())) {
                        BlockState state = ctx.get(pos.getX(), pos.getY(), pos.getZ());
                        return filter.has(state);
                    }
                    return !Baritone.settings().legitMine.value;
                })

                // remove any that are implausible to mine (encased in bedrock, or touching lava)
                .filter(pos -> MineProcess.plausibleToBreak(ctx, pos))

                // Jeśli kopiemy pnie drzew: odrzuć pnie wiszące wysoko w koronach bez stabilnego gruntu pod spodem
                .filter(pos -> {
                    if (isLogFilter(filter)) {
                        return isReachableGroundLog(ctx, pos);
                    }
                    return true;
                })

                .filter(pos -> {
                    if (Baritone.settings().allowOnlyExposedOres.value) {
                        return isNextToAir(ctx, pos);
                    } else {
                        return true;
                    }
                })

                .filter(pos -> pos.getY() >= Baritone.settings().minYLevelWhileMining.value + ctx.world.dimensionType().minY())

                .filter(pos -> pos.getY() <= Baritone.settings().maxYLevelWhileMining.value)

                .filter(pos -> !blacklist.contains(pos))

                .sorted(Comparator.comparingDouble(ctx.getBaritone().getPlayerContext().player().blockPosition()::distSqr))
                .collect(Collectors.toList());

        return new ArrayList<>(orderIntoGreedyChain(ctx.getBaritone().getPlayerContext().player().blockPosition(), locs, max, dropped));
    }

    /**
     * Zwraca aktywne okno celów (bieżąca żyła + bezpośrednie sąsiedztwo),
     * aby A* pathfinder nie liczył 256 heurystyk na każdy przeszukiwany węzeł.
     */
    public static List<BlockPos> getActiveGoalWindow(List<BlockPos> fullList, int maxWindow) {
        if (fullList == null || fullList.isEmpty()) {
            return new ArrayList<>();
        }
        if (fullList.size() <= maxWindow) {
            return new ArrayList<>(fullList);
        }
        List<BlockPos> window = new ArrayList<>();
        BlockPos first = fullList.get(0);
        window.add(first);

        for (int i = 1; i < fullList.size(); i++) {
            BlockPos pos = fullList.get(i);
            // Uwzględnij wyłącznie sąsiednie bloki z tej samej żyły (odległość w 3D <= 4.24 lub weightedDistSq <= 36)
            if (OreRouteOptimizer.weightedDistSq(first, pos) <= 36.0 || first.distSqr(pos) <= 18.0) {
                window.add(pos);
                if (window.size() >= maxWindow) {
                    break;
                }
            }
        }
        // Jeśli cała żyła first ma mniej bloków niż maxWindow, dopełnij tylko kolejnymi najbliższymi
        if (window.size() < 3) {
            for (int i = 1; i < fullList.size() && window.size() < Math.min(maxWindow, 6); i++) {
                BlockPos pos = fullList.get(i);
                if (!window.contains(pos)) {
                    window.add(pos);
                }
            }
        }
        return window;
    }

    /**
     * Układa listę rud w optymalny, ciągły łańcuch za pomocą silnika OreRouteOptimizer
     * (Vein Clustering + 2-Opt TSP + Intra-Cluster Smoothing).
     */
    public static List<BlockPos> orderIntoGreedyChain(BlockPos startPos, List<BlockPos> unvisited, int max) {
        return OreRouteOptimizer.optimizeRoute(startPos, unvisited, max, Collections.emptyList());
    }

    public static List<BlockPos> orderIntoGreedyChain(BlockPos startPos, List<BlockPos> unvisited, int max, Collection<BlockPos> dropped) {
        return OreRouteOptimizer.optimizeRoute(startPos, unvisited, max, dropped);
    }

    public static boolean isNextToAir(CalculationContext ctx, BlockPos pos) {
        int radius = Baritone.settings().allowOnlyExposedOresDistance.value;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) <= radius
                            && MovementHelper.isTransparent(ctx.getBlock(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }


    public static boolean plausibleToBreak(CalculationContext ctx, BlockPos pos) {
        BlockState state = ctx.bsi.get0(pos);
        if (state.is(Blocks.BEDROCK)) {
            return false;
        }
        if (MovementHelper.getMiningDurationTicks(ctx, pos.getX(), pos.getY(), pos.getZ(), state, true) >= COST_INF) {
            return false;
        }
        if (MovementHelper.avoidBreaking(ctx.bsi, pos.getX(), pos.getY(), pos.getZ(), state)) {
            return false;
        }

        // bedrock above and below makes it implausible, otherwise we're good
        return !(ctx.bsi.get0(pos.above()).getBlock() == Blocks.BEDROCK && ctx.bsi.get0(pos.below()).getBlock() == Blocks.BEDROCK);
    }

    @Override
    public void mineByName(int quantity, String... blocks) {
        mine(quantity, new BlockOptionalMetaLookup(blocks));
    }

    @Override
    public void mine(int quantity, BlockOptionalMetaLookup filter) {
        this.filter = filter;
        if (this.filterFilter() == null) {
            this.filter = null;
        }
        this.desiredQuantity = quantity;
        this.knownOreLocations = new ArrayList<>();
        this.blacklist = new ArrayList<>();
        this.branchPoint = null;
        this.branchPointRunaway = null;
        this.anticipatedDrops = new HashMap<>();
        this.currentGoal = null;
        this.currentPrimaryTarget = null;
        this.activeVeinBlocks.clear();
        if (filter != null) {
            rescan(new ArrayList<>(), new CalculationContext(baritone), droppedItemsScan());
        }
    }

    private BlockOptionalMetaLookup filterFilter() {
        if (this.filter == null) {
            return null;
        }
        if (!Baritone.settings().allowBreak.value) {
            BlockOptionalMetaLookup f = new BlockOptionalMetaLookup(this.filter.blocks()
                    .stream()
                    .filter(e -> Baritone.settings().allowBreakAnyway.value.contains(e.getBlock()))
                    .toArray(BlockOptionalMeta[]::new));
            if (f.blocks().isEmpty()) {
                logDirect("Unable to mine when allowBreak is false and target block is not in allowBreakAnyway!");
                return null;
            }
            return f;
        }
        return filter;
    }
}
