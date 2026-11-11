package baritone.bypass;

import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.Set;

/**
 * Moduł detekcji i wydobycia rud (diamentów) dla BypassProcess (Argon 4 & dj).
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

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = playerPos.offset(dx, dy, dz);
                    if (proc.getBlacklistedOres().contains(pos))
                        continue;

                    BlockState state = level.getBlockState(pos);
                    if (isTargetOre(proc, state)) {
                        double distSq = eye.distanceToSqr(Vec3.atCenterOf(pos));
                        boolean isDiamond = state.is(Blocks.DIAMOND_ORE) || state.is(Blocks.DEEPSLATE_DIAMOND_ORE);
                        double score = isDiamond ? distSq * 0.5D : distSq;
                        if (score < bestScore) {
                            bestScore = score;
                            bestPos = pos.immutable();
                        }
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
                            && !proc.getVeinQueue().contains(neighbor)
                            && !neighbor.equals(proc.getActiveOreTarget())
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

    public static PathingCommand tickMiningOre(BypassProcess proc, LocalPlayer player, Level level, boolean calcFailed, boolean isSafeToCancel) {
        while (proc.getActiveOreTarget() == null || !isTargetOre(proc, level.getBlockState(proc.getActiveOreTarget()))
                || proc.getBlacklistedOres().contains(proc.getActiveOreTarget())) {
            proc.getInvalidStandingPositions().clear();
            proc.setOreStagnationTicks(0);
            proc.setOreAttemptTicks(0);
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
        double effectiveReach = proc.getEffectiveReach();

        if (proc.getCurrentBreakingBlock() != null) {
            BlockState curState = level.getBlockState(proc.getCurrentBreakingBlock());
            if (!curState.isAir() && !BypassNavigator.isBedrockOrUnbreakable(level, proc.getCurrentBreakingBlock())) {
                if (proc.getBaritone().getPathingBehavior().isPathing()) {
                    proc.getBaritone().getPathingBehavior().cancelSegmentIfSafe();
                    proc.getBaritone().getPathingBehavior().secretInternalSetGoal(null);
                }
                proc.stopMovementInputs();
                return proc.mineBlockSafely(player, level, proc.getCurrentBreakingBlock());
            } else {
                proc.setCurrentBreakingBlock(null);
            }
        }

        Optional<MiningAction> directAction = findMiningAction(proc, player, level, proc.getActiveOreTarget(), effectiveReach);
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

            return proc.mineBlockSafely(player, level, action.targetPos());
        }

        proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
        proc.resetMiningState();

        if (calcFailed) {
            proc.getBlacklistedOres().add(proc.getActiveOreTarget());
            proc.setActiveOreTarget(null);
            proc.getInvalidStandingPositions().clear();
            proc.setOreStagnationTicks(0);
            proc.setOreAttemptTicks(0);
            proc.getBaritone().getInputOverrideHandler().clearAllKeys();
            proc.setState(BypassProcess.State.TUNNELING);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        boolean isMoving = player.getDeltaMovement().horizontalDistanceSqr() > 0.0005D;
        if (!isMoving && !proc.getBaritone().getPathingBehavior().isPathing() && !proc.getBaritone().getPathingBehavior().getInProgress().isPresent()) {
            proc.setOreAttemptTicks(proc.getOreAttemptTicks() + 1);
            if (proc.getOreAttemptTicks() > 70) {
                proc.getBlacklistedOres().add(proc.getActiveOreTarget());
                proc.setActiveOreTarget(null);
                proc.setOreAttemptTicks(0);
                proc.getInvalidStandingPositions().clear();
                proc.getBaritone().getInputOverrideHandler().clearAllKeys();
                proc.setState(BypassProcess.State.TUNNELING);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        } else {
            proc.setOreAttemptTicks(0);
        }

        return new PathingCommand(new GoalNear(proc.getActiveOreTarget(), 1), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    public static Optional<MiningAction> findMiningAction(BypassProcess proc, LocalPlayer player, Level level, BlockPos orePos, double reach) {
        if (BypassNavigator.isBedrockOrUnbreakable(level, orePos)) {
            proc.getBlacklistedOres().add(orePos);
            return Optional.empty();
        }

        Vec3 eyePos = player.getEyePosition(1.0F);
        double reachSq = reach * reach;
        if (eyePos.distanceToSqr(Vec3.atCenterOf(orePos)) > (reach + 1.2D) * (reach + 1.2D)) {
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
            if (eyePos.distanceToSqr(targetPt) > reachSq) continue;

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
            return Optional.of(new MiningAction(bestOccluder, bestOccluderRot));
        }

        return Optional.empty();
    }
}

