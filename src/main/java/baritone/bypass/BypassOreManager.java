package baritone.bypass;

import baritone.Baritone;
import baritone.api.pathing.goals.GoalOreMining;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

/**
 * Moduł detekcji i wydobycia rud (diamentów) dla BypassProcess (Argon 4 & dj).
 * Zoptymalizowany pod kątem bypassu GrimAC:
 * - Pełny profil 1x2 (brak blokowania się na wysokości głowy w otworach 1x1)
 * - Bezpieczny krok przed kopaniem (brak ruchu w przód podczas bicia bloków)
 * - Eliminuje błąd "idzie ale ma przed sobą blok i go nie wykopuje"
 */
public final class BypassOreManager {

    private BypassOreManager() {}

    public record MiningAction(BlockPos targetPos, Rotation snapRotation) {
    }

    public static BlockPos findNearestOre(BypassProcess proc, LocalPlayer player, Level level) {
        if (player == null || level == null) return null;
        BlockPos playerPos = player.blockPosition();
        Vec3 eye = player.getEyePosition(1.0F);

        BlockPos bestPos = null;
        double bestScore = Double.MAX_VALUE;
        int radius = 5;

        Direction tunnelDir = proc.getPrimaryMiningDir() != null
                ? proc.getPrimaryMiningDir()
                : (proc.getTunnelDir() != null ? proc.getTunnelDir() : player.getDirection());
        boolean isTunneling = proc.getState() == BypassProcess.State.TUNNELING;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    // TWARDE ODCIĘCIE W TUNELU: całkowicie ignoruj rudy za plecami gracza (dot < 0)
                    if (isTunneling && tunnelDir != null) {
                        int dot = dx * tunnelDir.getStepX() + dz * tunnelDir.getStepZ();
                        if (dot < 0) {
                            continue; // Nigdy nie zawracaj o 180° w trakcie kopania tunelu
                        }
                    }

                    BlockPos pos = playerPos.offset(dx, dy, dz);
                    if (proc.getBlacklistedOres().contains(pos) || OreScanner.isBlacklisted(pos) || isTrappedInBedrock(level, pos))
                        continue;

                    BlockState state = level.getBlockState(pos);
                    if (!isTargetOre(proc, state)) {
                        continue;
                    }

                    Vec3 center = Vec3.atCenterOf(pos);
                    double distSq = eye.distanceToSqr(center);

                    // Dodatkowy filtr FOV w TUNNELING: odrzucaj cele wymagające obrotu > 95° względem kierunku marszu
                    if (isTunneling && tunnelDir != null) {
                        double vx = center.x - player.getX();
                        double vz = center.z - player.getZ();
                        float oreYaw = (float) (Mth.atan2(vz, vx) * (180.0D / Math.PI)) - 90.0F;
                        float yawDiff = Math.abs(Mth.wrapDegrees(oreYaw - tunnelDir.toYRot()));
                        if (yawDiff > 95.0F) {
                            continue;
                        }
                    }

                    boolean isDiamond = state.is(Blocks.DIAMOND_ORE) || state.is(Blocks.DEEPSLATE_DIAMOND_ORE);
                    double score = isDiamond ? distSq * 0.5D : distSq;

                    if (score < bestScore) {
                        bestScore = score;
                        bestPos = pos.immutable();
                    }
                }
            }
        }
        return bestPos;
    }

    public static void enqueueConnectedVein(BypassProcess proc, Level level, BlockPos center) {
        if (level == null || center == null) return;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0)
                        continue;
                    BlockPos neighbor = center.offset(dx, dy, dz);
                    if (!proc.getBlacklistedOres().contains(neighbor)
                            && !OreScanner.isBlacklisted(neighbor)
                            && !proc.getVeinQueue().contains(neighbor)
                            && !neighbor.equals(proc.getActiveOreTarget())
                            && !isTrappedInBedrock(level, neighbor)
                            && isTargetOre(proc, level.getBlockState(neighbor))) {
                        proc.getVeinQueue().addLast(neighbor.immutable());
                    }
                }
            }
        }
    }

    public static boolean isTargetOre(BypassProcess proc, BlockState state) {
        if (state == null || state.isAir())
            return false;
        Block block = state.getBlock();
        if (proc.getTargetOresList().isEmpty()) {
            return block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE;
        }
        for (String ore : proc.getTargetOresList()) {
            String norm = BypassProcess.normalizeOreKey(ore);
            Set<Block> allowed = BypassProcess.ORE_BLOCK_MAP.get(norm);
            if (allowed != null && allowed.contains(block)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isTrappedInBedrock(Level level, BlockPos orePos) {
        if (level == null || orePos == null) return true;

        // 1. Szybki test bezpośrednich 6 ścian
        int breakableNeighbors = 0;
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = orePos.relative(dir);
            if (!isUnbreakableOrHazard(level, neighbor)) {
                breakableNeighbors++;
            }
        }
        if (breakableNeighbors == 0) {
            return true; // Ruda w 100% obudowana bedrockiem lub lawą
        }

        // 2. Mini-BFS (promień 3): weryfikacja, czy istnieje jakiekolwiek podejście 1x2 z przestrzeni zewnętrznej,
        // czy może ruda znajduje się w hermetycznej kopule/kieszeni z bedrocka (Bedrock Dome)
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> queue = new ArrayDeque<>();
        queue.add(orePos);
        visited.add(orePos);

        boolean found1x2Aperture = false;

        while (!queue.isEmpty() && visited.size() < 40) {
            BlockPos current = queue.poll();
            if (!current.equals(orePos)) {
                BlockPos above = current.above();
                if (!isUnbreakableOrHazard(level, current) && !isUnbreakableOrHazard(level, above)) {
                    BlockPos floor = current.below();
                    if (!level.getBlockState(floor).isAir() && !level.getFluidState(floor).is(net.minecraft.tags.FluidTags.LAVA)) {
                        found1x2Aperture = true;
                        break;
                    }
                }
            }

            for (Direction dir : Direction.values()) {
                BlockPos next = current.relative(dir);
                if (orePos.distManhattan(next) <= 3 && visited.add(next)) {
                    if (!isUnbreakableOrHazard(level, next)) {
                        queue.add(next);
                    }
                }
            }
        }

        return !found1x2Aperture;
    }

    public static boolean isUnbreakableOrHazard(Level level, BlockPos pos) {
        if (level == null || pos == null) return true;
        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.LAVA) || state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) {
            return true;
        }
        return !state.isAir() && state.getDestroySpeed(level, pos) < 0.0F;
    }

    public static PathingCommand blacklistAndSwitch(BypassProcess proc, LocalPlayer player, Level level, BlockPos badOre) {
        if (badOre != null) {
            BlockPos imm = badOre.immutable();
            proc.getBlacklistedOres().add(imm);
            OreScanner.blacklist(imm);
        }
        proc.stopMovementInputs();
        proc.setCurrentBreakingBlock(null);
        proc.setOreStagnationTicks(0);
        proc.setOreAttemptTicks(0);
        proc.getInvalidStandingPositions().clear();

        // 1. Sprawdź, czy w kolejce żyły jest inna, dostępna ruda
        while (!proc.getVeinQueue().isEmpty()) {
            BlockPos candidate = proc.getVeinQueue().pollFirst();
            if (candidate != null && !proc.getBlacklistedOres().contains(candidate)
                    && !OreScanner.isBlacklisted(candidate)
                    && !isTrappedInBedrock(level, candidate)
                    && isTargetOre(proc, level.getBlockState(candidate))) {
                proc.setActiveOreTarget(candidate);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }

        // 2. Sprawdź, czy w promieniu 5 bloków jest inna dostępna ruda
        BlockPos nextInRadius = findNearestOre(proc, player, level);
        if (nextInRadius != null) {
            proc.setActiveOreTarget(nextInRadius);
            enqueueConnectedVein(proc, level, nextInRadius);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // 3. Brak dostępnych rud w okolicy -> powrót do tunelowania
        proc.setActiveOreTarget(null);
        proc.getBaritone().getInputOverrideHandler().clearAllKeys();
        proc.setState(BypassProcess.State.TUNNELING);
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    public static PathingCommand tickMiningOre(BypassProcess proc, LocalPlayer player, Level level, boolean calcFailed, boolean isSafeToCancel) {
        if (calcFailed && proc.getActiveOreTarget() != null) {
            return blacklistAndSwitch(proc, player, level, proc.getActiveOreTarget());
        }

        while (proc.getActiveOreTarget() == null || !isTargetOre(proc, level.getBlockState(proc.getActiveOreTarget()))
                || proc.getBlacklistedOres().contains(proc.getActiveOreTarget())
                || isTrappedInBedrock(level, proc.getActiveOreTarget())) {
            proc.getInvalidStandingPositions().clear();
            proc.setOreStagnationTicks(0);
            proc.setOreAttemptTicks(0);
            if (proc.getActiveOreTarget() != null && isTrappedInBedrock(level, proc.getActiveOreTarget())) {
                proc.getBlacklistedOres().add(proc.getActiveOreTarget());
            }
            if (proc.getVeinQueue().isEmpty()) {
                BlockPos nextInRadius = findNearestOre(proc, player, level);
                if (nextInRadius != null) {
                    proc.setActiveOreTarget(nextInRadius);
                    enqueueConnectedVein(proc, level, nextInRadius);
                } else {
                    proc.setActiveOreTarget(null);
                    proc.setCurrentBreakingBlock(null);
                    proc.getBaritone().getInputOverrideHandler().clearAllKeys();
                    proc.setState(BypassProcess.State.COLLECTING_DROP);
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
            } else {
                proc.setActiveOreTarget(proc.getVeinQueue().pollFirst());
            }
        }

        enqueueConnectedVein(proc, level, proc.getActiveOreTarget());
        BlockPos targetOre = proc.getActiveOreTarget();

        if (isTrappedInBedrock(level, targetOre)) {
            return blacklistAndSwitch(proc, player, level, targetOre);
        }

        Vec3 eyePos = player.getEyePosition(1.0F);
        Vec3 oreCenter = Vec3.atCenterOf(targetOre);
        double distSq3D = eyePos.distanceToSqr(oreCenter);
        double dx = (targetOre.getX() + 0.5) - player.getX();
        double dz = (targetOre.getZ() + 0.5) - player.getZ();
        double hDistSq = dx * dx + dz * dz;
        BlockPos feet = player.blockPosition();

        final double DIRECT_MINE_REACH = 3.8D;
        final double DIRECT_MINE_REACH_SQ = DIRECT_MINE_REACH * DIRECT_MINE_REACH; // 14.44 (dj reach 3.8)
        final double MAX_FALLBACK_REACH = 3.8D;

        // 1. ZASADA COMMIT: Dokończ kopanie aktualnie niszczonego bloku w zasięgu 3.8m bez zmiany celu
        if (proc.getCurrentBreakingBlock() != null) {
            BlockState curState = level.getBlockState(proc.getCurrentBreakingBlock());
            if (!curState.isAir() && !BypassNavigator.isBedrockOrUnbreakable(level, proc.getCurrentBreakingBlock())) {
                double breakDistSq = eyePos.distanceToSqr(Vec3.atCenterOf(proc.getCurrentBreakingBlock()));
                if (breakDistSq <= DIRECT_MINE_REACH_SQ) {
                    if (proc.getBaritone().getPathingBehavior().isPathing()) {
                        proc.getBaritone().getPathingBehavior().cancelSegmentIfSafe();
                        proc.getBaritone().getPathingBehavior().secretInternalSetGoal(null);
                    }
                    proc.stopMovementInputs();
                    return proc.mineBlockSafely(player, level, proc.getCurrentBreakingBlock());
                }
            } else {
                proc.setCurrentBreakingBlock(null);
            }
        }

        // 2. Jeśli jesteśmy w zasięgu 3.8m (GrimAC reach 3.8 wg dj), priorytet ma niszczenie rudy
        // bezpośrednio z miejsca bez niepotrzebnego wchodzenia w ścianę!
        if (distSq3D <= DIRECT_MINE_REACH_SQ) {
            Optional<MiningAction> directAction = findMiningAction(proc, player, level, targetOre, DIRECT_MINE_REACH);
            if (directAction.isPresent()) {
                proc.getInvalidStandingPositions().clear();
                proc.setOreStagnationTicks(0);
                proc.setOreAttemptTicks(0);
                MiningAction action = directAction.get();

                if (proc.getBaritone().getPathingBehavior().isPathing()) {
                    proc.getBaritone().getPathingBehavior().cancelSegmentIfSafe();
                    proc.getBaritone().getPathingBehavior().secretInternalSetGoal(null);
                }
                proc.stopMovementInputs();
                return proc.mineBlockSafely(player, level, action.targetPos(), action.snapRotation());
            } else if (distSq3D <= 2.2 * 2.2) {
                // Stoimy tuż przy rudzie (< 2.2m), ale bezpośredni raycast ani żaden occluder nie może być zniszczony,
                // bo rudę zasłania niezniszczalny BEDROCK. Natychmiast dodaj do czarnej listy!
                return blacklistAndSwitch(proc, player, level, targetOre);
            }
        }

        // 3. Kolizja pozioma z jakąkolwiek ścianą (Anti-Stall & 1x2 Profile)
        if (player.horizontalCollision) {
            Direction facing = Direction.fromYRot(player.getYRot());
            BlockPos colFeet = feet.relative(facing);
            BlockPos colHead = colFeet.above();
            BlockPos colBlock = !BypassNavigator.isPassable(level, colHead) ? colHead
                    : (!BypassNavigator.isPassable(level, colFeet) ? colFeet : null);
            if (colBlock != null && !BypassNavigator.isBedrockOrUnbreakable(level, colBlock)) {
                proc.stopMovementInputs();
                return proc.mineBlockSafely(player, level, colBlock);
            }
        }

        // 4. Proximity Approach First (Podejdź do rudy na odległość komfortu < 2.4m)
        // Jeśli ruda jest oddalona w poziomie o więcej niż 0.18m, wykonaj krok w jej stronę
        if (hDistSq > 0.18) {
            Direction stepDir = Math.abs(dx) >= Math.abs(dz)
                    ? (dx > 0 ? Direction.EAST : Direction.WEST)
                    : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
            BlockPos stepFeet = feet.relative(stepDir);
            BlockPos stepHead = stepFeet.above();

            // Sprawdzenie, czy profil 1x2 na torze podejścia nie jest zablokowany bedrockiem
            boolean headUnbreakable = BypassNavigator.isBedrockOrUnbreakable(level, stepHead);
            boolean feetUnbreakable = BypassNavigator.isBedrockOrUnbreakable(level, stepFeet);
            if (headUnbreakable || feetUnbreakable) {
                // Próba obejścia po drugiej osi poziomej
                Direction altDir = Math.abs(dx) >= Math.abs(dz)
                        ? (dz > 0 ? Direction.SOUTH : Direction.NORTH)
                        : (dx > 0 ? Direction.EAST : Direction.WEST);
                BlockPos altFeet = feet.relative(altDir);
                BlockPos altHead = altFeet.above();

                if (BypassNavigator.isBedrockOrUnbreakable(level, altFeet) || BypassNavigator.isBedrockOrUnbreakable(level, altHead)) {
                    // Obie osie poziome zablokowane przez bedrock/niezniszczalny blok -> ruda niedostępna!
                    return blacklistAndSwitch(proc, player, level, targetOre);
                }
                stepDir = altDir;
                stepFeet = altFeet;
                stepHead = altHead;
            }

            boolean headBlocked = !BypassNavigator.isPassable(level, stepHead);
            boolean feetBlocked = !BypassNavigator.isPassable(level, stepFeet);

            // Jeśli na torze podejścia stoi blok: zatrzymaj ruch i wykop go (głowa najpierw, potem nogi)
            if (headBlocked || feetBlocked) {
                BlockPos blockToMine = headBlocked ? stepHead : stepFeet;
                if (!BypassNavigator.isBedrockOrUnbreakable(level, blockToMine)) {
                    proc.stopMovementInputs();
                    return proc.mineBlockSafely(player, level, blockToMine);
                }
            }

            // Tor 1x2 jest czysty! Sprawdź stabilność podłoża
            BlockPos stepFloor = stepFeet.below();
            if (BypassHazardSealer.hasStableFloorSupport(level, stepFloor)) {
                if (proc.getBaritone().getPathingBehavior().isPathing()) {
                    proc.getBaritone().getPathingBehavior().cancelSegmentIfSafe();
                    proc.getBaritone().getPathingBehavior().secretInternalSetGoal(null);
                }
                proc.setCurrentBreakingBlock(null);
                moveSafelyTowardsDirection(proc, player, stepDir);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            } else if (level.getBlockState(stepFloor).canBeReplaced()
                    && BypassHazardSealer.tryBridgeFloor(proc, player, level, stepFloor, feet.below())) {
                proc.stopMovementInputs();
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        } else {
            // Ruda jest bezpośrednio nad lub pod graczem w pionie
            if (targetOre.getY() != feet.getY() && targetOre.getY() != feet.getY() + 1) {
                Optional<MiningAction> verticalAction = findMiningAction(proc, player, level, targetOre, DIRECT_MINE_REACH);
                if (verticalAction.isPresent()) {
                    proc.stopMovementInputs();
                    return proc.mineBlockSafely(player, level, verticalAction.get().targetPos(), verticalAction.get().snapRotation());
                }
            }
        }

        // 5. Fallback: Jeśli bezpośrednie podejście nie mogło ruszyć (np. ruda na skosie lub za rowem),
        // sprawdź czy można ją sięgnąć w powiększonym zasięgu (MAX_FALLBACK_REACH = 3.2D)
        Optional<MiningAction> fallbackAction = findMiningAction(proc, player, level, targetOre, MAX_FALLBACK_REACH);
        if (fallbackAction.isPresent()) {
            proc.getInvalidStandingPositions().clear();
            proc.setOreStagnationTicks(0);
            proc.setOreAttemptTicks(0);
            MiningAction action = fallbackAction.get();

            if (proc.getBaritone().getPathingBehavior().isPathing()) {
                proc.getBaritone().getPathingBehavior().cancelSegmentIfSafe();
                proc.getBaritone().getPathingBehavior().secretInternalSetGoal(null);
            }
            proc.stopMovementInputs();
            return proc.mineBlockSafely(player, level, action.targetPos(), action.snapRotation());
        }

        // 6. Złożone podejście (gdy bezpośredni krok jest zablokowany przez lawę/bedrock lub wymaga skoku)
        // Używamy GoalOreMining (stabilna podłoga w zasięgu ręki) zamiast zbugowanego GoalNear!
        proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
        proc.resetMiningState();

        // Watchdog: bezwzględny limit ticków spędzonych na próbie podejścia/wykopania tej samej rudy.
        // Jeśli przez 40 ticków (2.0s) ruda nie została wykopana ani nie weszliśmy w strefę bicia,
        // ruda jest w niedostępnej wnęce/kopule z bedrocka -> natychmiast Blacklist i powrót do tunelu!
        proc.setOreAttemptTicks(proc.getOreAttemptTicks() + 1);
        if (proc.getOreAttemptTicks() > 40) {
            return blacklistAndSwitch(proc, player, level, proc.getActiveOreTarget());
        }

        // Tymczasowo włącz autoTool dla Baritone A* pathfinding, aby nie bił deepslate pustą ręką
        Baritone.settings().autoTool.value = true;
        Baritone.settings().walkWhileBreaking.value = false;

        return new PathingCommand(new GoalOreMining(proc.getActiveOreTarget()), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    public static void moveSafelyTowardsDirection(BypassProcess proc, LocalPlayer player, Direction stepDir) {
        if (player == null || stepDir == null) return;
        float targetYaw = stepDir.toYRot();

        Rotation gcdMoveRot = BypassNavigator.applyGrimGcd(player.getYRot(), player.getXRot(), targetYaw, BypassProcess.TWO_BLOCK_SEAM_PITCH);
        proc.getBaritone().getLookBehavior().updateTarget(gcdMoveRot, false);

        float yawDelta = Math.abs(Mth.wrapDegrees(player.getYRot() - gcdMoveRot.getYaw()));
        if (yawDelta > 30.0f) {
            proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
            proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
            return;
        }

        proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        if (player.getFoodData().getFoodLevel() > 6 && !player.horizontalCollision) {
            proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.SPRINT, true);
        } else {
            proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
        }
    }

    public static Optional<MiningAction> findMiningAction(BypassProcess proc, LocalPlayer player, Level level, BlockPos orePos, double reach) {
        if (BypassNavigator.isBedrockOrUnbreakable(level, orePos)) {
            proc.getBlacklistedOres().add(orePos);
            return Optional.empty();
        }

        Vec3 eyePos = player.getEyePosition(1.0F);
        double reachSq = reach * reach;
        if (eyePos.distanceToSqr(Vec3.atCenterOf(orePos)) > (reach + 2.0D) * (reach + 2.0D)) {
            return Optional.empty();
        }

        Vec3[] sampleOffsets = new Vec3[]{
                new Vec3(0.5D, 0.5D, 0.5D),
                new Vec3(0.5D, 0.18D, 0.5D),
                new Vec3(0.5D, 0.82D, 0.5D),
                new Vec3(0.18D, 0.5D, 0.5D),
                new Vec3(0.82D, 0.5D, 0.5D),
                new Vec3(0.5D, 0.5D, 0.18D),
                new Vec3(0.5D, 0.5D, 0.82D)
        };

        BlockPos bestOccluder = null;
        Rotation bestOccluderRot = null;
        double bestOccluderDistSq = Double.MAX_VALUE;

        for (Vec3 offset : sampleOffsets) {
            Vec3 targetPt = Vec3.atLowerCornerOf(orePos).add(offset);
            Rotation rawRot = BypassNavigator.calcRotationFromVec3d(eyePos, targetPt, player.getYRot());
            Rotation gcdRot = BypassNavigator.applyGrimGcd(player.getYRot(), player.getXRot(), rawRot.getYaw(), rawRot.getPitch());

            Vec3 lookVec = Vec3.directionFromRotation(gcdRot.getPitch(), gcdRot.getYaw());
            BlockHitResult hit = level.clip(new ClipContext(
                    eyePos,
                    eyePos.add(lookVec.scale(reach)),
                    ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE,
                    player));

            if (hit.getType() == HitResult.Type.BLOCK) {
                BlockPos hitPos = hit.getBlockPos();
                if (hitPos.equals(orePos)) {
                    if (BypassLavaRadar.hasDirectLavaAboveOrBehind(level, orePos) && player.blockPosition().distManhattan(orePos) <= 1) {
                        proc.getBlacklistedOres().add(orePos);
                        return Optional.empty();
                    }
                    return Optional.of(new MiningAction(orePos, gcdRot));
                }

                double hitDistSq = eyePos.distanceToSqr(Vec3.atCenterOf(hitPos));
                if (hitDistSq <= reachSq && hitDistSq < bestOccluderDistSq
                        && !hitPos.equals(player.blockPosition().below())
                        && !BypassLavaRadar.isHazardNear(level, hitPos)
                        && !BypassNavigator.isBedrockOrUnbreakable(level, hitPos)
                        && !level.getBlockState(hitPos).isAir()
                        && level.getBlockState(hitPos).getDestroySpeed(level, hitPos) >= 0) {
                    bestOccluder = hitPos;
                    bestOccluderRot = gcdRot;
                    bestOccluderDistSq = hitDistSq;
                }
            }
        }

        Optional<Rotation> directOreSnap = RotationUtils.reachable(proc.getPlayerContext(), orePos, reach);
        if (directOreSnap.isPresent()) {
            if (BypassLavaRadar.hasDirectLavaAboveOrBehind(level, orePos) && player.blockPosition().distManhattan(orePos) <= 1) {
                proc.getBlacklistedOres().add(orePos);
                return Optional.empty();
            }
            Rotation gcdRot = BypassNavigator.applyGrimGcd(player.getYRot(), player.getXRot(), directOreSnap.get().getYaw(),
                    directOreSnap.get().getPitch());
            return Optional.of(new MiningAction(orePos, gcdRot));
        }

        if (bestOccluder != null) {
            // Zasada 1x2: Jeśli occluder jest na wysokości stóp, a nad nim jest lita ściana (wysokość głowy),
            // najpierw wykop blok głowy, aby odsłonić cały profil 1x2 i zapobiec blokowaniu się głowy w otworze 1x1!
            if (bestOccluder.getY() == player.blockPosition().getY()) {
                BlockPos headOccluder = bestOccluder.above();
                if (!level.getBlockState(headOccluder).isAir() && !BypassNavigator.isPassable(level, headOccluder)
                        && !BypassNavigator.isBedrockOrUnbreakable(level, headOccluder)
                        && eyePos.distanceToSqr(Vec3.atCenterOf(headOccluder)) <= reachSq) {
                    Optional<Rotation> headRot = BypassProcess.findMultipointGrimRotation(player, level, headOccluder, reach);
                    if (headRot.isPresent()) {
                        return Optional.of(new MiningAction(headOccluder, headRot.get()));
                    }
                }
            }
            return Optional.of(new MiningAction(bestOccluder, bestOccluderRot));
        }

        return Optional.empty();
    }
}
