package baritone.bypass;

import baritone.Baritone;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.utils.FastBreakHelper;
import baritone.utils.ContinuousBreakController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Moduł nawigacji, drążenia korytarzy, schodków, obrotów i manewrów omijania
 * przeszkód (Argon 4 & dj).
 */
public final class BypassNavigator {

    private BypassNavigator() {
    }

    public static final int MIN_PARALLEL_SPACING = 3;
    public static final int MAX_RETREAT_BLOCKS = 11;
    public static final int MAX_RETREAT_TICKS = 140;

    public static long to2DKey(int x, int z) {
        return (((long) x) << 32) | (z & 0xffffffffL);
    }

    public static boolean isPassable(Level level, BlockPos pos) {
        if (level == null || pos == null)
            return false;
        BlockState state = level.getBlockState(pos);
        if (state.isAir())
            return true;
        return state.getCollisionShape(level, pos).isEmpty() && level.getFluidState(pos).isEmpty();
    }

    public static boolean isBedrockOrUnbreakable(Level level, BlockPos pos) {
        if (pos == null || level == null)
            return true;
        if (pos.getY() <= -64)
            return true;
        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.BEDROCK) || state.is(Blocks.BARRIER))
            return true;
        return state.getDestroySpeed(level, pos) < 0.0f;
    }

    public static Rotation calcRotationFromVec3d(Vec3 orig, Vec3 dest, float currentYaw) {
        double dx = dest.x - orig.x;
        double dy = dest.y - orig.y;
        double dz = dest.z - orig.z;
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, distXZ));
        return new Rotation(yaw, pitch);
    }

    public static Rotation applyGrimGcd(float prevYaw, float prevPitch, float targetYaw, float targetPitch) {
        Minecraft mc = Minecraft.getInstance();
        double mouseSens = mc.options.sensitivity().get();
        double gcdFactor = mouseSens * 0.6D + 0.2D;
        double gcd = gcdFactor * gcdFactor * gcdFactor * 8.0D * 0.15D;

        float deltaYaw = targetYaw - prevYaw;
        float deltaPitch = targetPitch - prevPitch;

        float fixedDeltaYaw = (float) (Math.round(deltaYaw / gcd) * gcd);
        float fixedDeltaPitch = (float) (Math.round(deltaPitch / gcd) * gcd);

        return new Rotation(prevYaw + fixedDeltaYaw, Math.clamp(prevPitch + fixedDeltaPitch, -90.0f, 90.0f));
    }

    public static double horizontalDistanceToCenter(Vec3 playerPos, BlockPos blockPos) {
        double dx = (blockPos.getX() + 0.5D) - playerPos.x;
        double dz = (blockPos.getZ() + 0.5D) - playerPos.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public static boolean isFallingBlock(BlockState state) {
        return state.getBlock() instanceof FallingBlock || state.is(BlockTags.SAND);
    }

    public static boolean isFallingBlockSettling(Level level, BlockPos frontFeet, BlockPos frontHead) {
        AABB columnBox = new AABB(
                frontFeet.getX(), frontFeet.getY(), frontFeet.getZ(),
                frontFeet.getX() + 1.0, frontHead.getY() + 3.0, frontFeet.getZ() + 1.0);
        if (!level.getEntitiesOfClass(FallingBlockEntity.class, columnBox).isEmpty()) {
            return true;
        }

        if (level.getBlockState(frontHead).isAir() && isFallingBlock(level.getBlockState(frontHead.above()))) {
            return true;
        }
        if (level.getBlockState(frontFeet).isAir() && isFallingBlock(level.getBlockState(frontHead))) {
            return true;
        }

        return false;
    }

    public static PathingCommand tickDescending(BypassProcess proc, LocalPlayer player, Level level) {
        BlockPos feet = player.blockPosition();
        if (feet.getY() <= proc.getTargetY() || feet.getY() <= BypassProcess.MIN_SAFE_BEDROCK_Y) {
            proc.setState(BypassProcess.State.TUNNELING);
            proc.setTunnelAnchorPos(feet);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        Direction descendDir = proc.getPrimaryMiningDir() != null ? proc.getPrimaryMiningDir() : proc.getTunnelDir();
        proc.setTunnelDir(descendDir);

        BlockPos frontHead = feet.relative(descendDir).above();
        BlockPos frontFeet = feet.relative(descendDir);
        BlockPos stepDown = frontFeet.below();
        BlockPos stepFloor = stepDown.below();

        if (stepDown.getY() <= BypassProcess.MIN_SAFE_BEDROCK_Y || isBedrockOrUnbreakable(level, stepDown)
                || isBedrockOrUnbreakable(level, frontFeet)) {
            proc.setTargetY(feet.getY());
            proc.setState(BypassProcess.State.TUNNELING);
            proc.setTunnelDir(proc.getPrimaryMiningDir());
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        BlockPos exposedBreach = BypassHazardSealer.findExposedLavaBreach(level, feet, descendDir);
        if (exposedBreach != null) {
            if (BypassHazardSealer.selectThrowawayBlock(proc, player, exposedBreach)) {
                proc.setHazardSourcePos(exposedBreach);
                proc.setSealFailedAttempts(0);
                proc.setState(BypassProcess.State.SEALING_HAZARD);
                return BypassHazardSealer.handleSealingHazard(proc, player, level);
            }
            return handleLavaOrObstacleEncounter(proc, player, level, feet);
        }

        if (BypassLavaRadar.hasHiddenHazardBehind(level, frontHead, descendDir)
                || BypassLavaRadar.hasHiddenHazardBehind(level, frontFeet, descendDir)
                || BypassLavaRadar.hasHiddenHazardBehind(level, stepDown, descendDir)) {
            return handleLavaOrObstacleEncounter(proc, player, level, feet);
        }

        if (!BypassHazardSealer.hasStableFloorSupport(level, stepFloor)) {
            if (level.getBlockState(stepFloor).canBeReplaced()) {
                if (!isPassable(level, frontHead))
                    return proc.mineBlockSafely(player, level, frontHead);
                if (!isPassable(level, frontFeet))
                    return proc.mineBlockSafely(player, level, frontFeet);
                if (!isPassable(level, stepDown))
                    return proc.mineBlockSafely(player, level, stepDown);

                if (BypassHazardSealer.tryBridgeFloor(proc, player, level, stepFloor, feet.below(2))) {
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            }
            proc.setTargetY(feet.getY());
            proc.setState(BypassProcess.State.TUNNELING);
            proc.setTunnelAnchorPos(feet);
            return handleLavaOrObstacleEncounter(proc, player, level, feet);
        }

        List<BlockPos> stairBlocks = List.of(
                feet.above(2),
                frontHead,
                frontFeet,
                stepDown);

        for (BlockPos target : stairBlocks) {
            if (isBedrockOrUnbreakable(level, target)) {
                proc.setTargetY(feet.getY());
                proc.setState(BypassProcess.State.TUNNELING);
                proc.setTunnelDir(proc.getPrimaryMiningDir());
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            if (!isPassable(level, target)) {
                return proc.mineBlockSafely(player, level, target);
            }
        }

        proc.resetMiningState();
        proc.setTunnelAnchorPos(stepDown);
        moveSafelyTowards(proc, player, Vec3.atBottomCenterOf(stepDown));
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    public static PathingCommand tickAscending(BypassProcess proc, LocalPlayer player, Level level) {
        BlockPos feet = player.blockPosition();
        if (feet.getY() >= proc.getTargetY()) {
            proc.getBaritone().getInputOverrideHandler().clearAllKeys();
            if (proc.getCurrentBreakingBlock() != null) {
                proc.setCurrentBreakingBlock(null);
                if (proc.getPlayerContext().playerController() != null) {
                    proc.getPlayerContext().playerController().resetBlockRemoving();
                }
            }
            proc.setState(BypassProcess.State.TUNNELING);
            proc.setTunnelAnchorPos(feet);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        Direction curTunnelDir = proc.getTunnelDir();
        if (!isAscendingDirectionClear(level, feet, curTunnelDir)) {
            rotateAscendingDirectionSafe(proc, level, feet);
            curTunnelDir = proc.getTunnelDir();
            if (!isAscendingDirectionClear(level, feet, curTunnelDir)) {
                if (feet.getY() >= BypassProcess.MIN_SAFE_BEDROCK_Y) {
                    proc.setTargetY(feet.getY());
                    proc.setState(BypassProcess.State.TUNNELING);
                    proc.setTunnelDir(proc.getPrimaryMiningDir());
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                BlockPos forwardAscendTarget = feet.relative(proc.getPrimaryMiningDir(), 4)
                        .atY(Math.min(proc.getTargetY(), BypassProcess.MIN_SAFE_BEDROCK_Y));
                return new PathingCommand(new GoalBlock(forwardAscendTarget), PathingCommandType.SET_GOAL_AND_PATH);
            }
        }

        BlockPos headClearance = feet.above(2);
        BlockPos nextStepFeet = feet.relative(curTunnelDir).above(1);
        BlockPos nextStepHead = feet.relative(curTunnelDir).above(2);

        List<BlockPos> ascendBlocks = List.of(headClearance, nextStepHead, nextStepFeet);
        for (BlockPos target : ascendBlocks) {
            if (isBedrockOrUnbreakable(level, target)) {
                rotateAscendingDirectionSafe(proc, level, feet);
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            if (!isPassable(level, target)) {
                return proc.mineBlockSafely(player, level, target);
            }
        }

        if (proc.getCurrentBreakingBlock() != null) {
            proc.setCurrentBreakingBlock(null);
            if (proc.getPlayerContext().playerController() != null) {
                proc.getPlayerContext().playerController().resetBlockRemoving();
            }
        }
        proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);

        BlockPos stepBase = feet.relative(curTunnelDir);
        if (!isSolidSupport(level, stepBase)) {
            if (!BypassHazardSealer.tryBridgeFloor(proc, player, level, stepBase, feet.below())) {
                if (feet.getY() >= BypassProcess.MIN_SAFE_BEDROCK_Y) {
                    proc.setTargetY(feet.getY());
                    proc.setState(BypassProcess.State.TUNNELING);
                    proc.setTunnelDir(proc.getPrimaryMiningDir());
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                BlockPos forwardAscendTarget = feet.relative(proc.getPrimaryMiningDir(), 4)
                        .atY(Math.min(proc.getTargetY(), BypassProcess.MIN_SAFE_BEDROCK_Y));
                return new PathingCommand(new GoalBlock(forwardAscendTarget), PathingCommandType.SET_GOAL_AND_PATH);
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        proc.setTunnelAnchorPos(nextStepFeet);
        moveSafelyTowards(proc, player, Vec3.atBottomCenterOf(nextStepFeet));
        boolean canAscendJump = player.onGround() && isPassable(level, feet.above(2))
                && nextStepFeet.getY() > player.getY() + 0.35D;
        proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.JUMP, canAscendJump);
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    public static boolean isAscendingDirectionClear(Level level, BlockPos feet, Direction dir) {
        BlockPos headClearance = feet.above(2);
        BlockPos nextStepFeet = feet.relative(dir).above(1);
        BlockPos nextStepHead = feet.relative(dir).above(2);

        if (isBedrockOrUnbreakable(level, headClearance) || isBedrockOrUnbreakable(level, nextStepFeet)
                || isBedrockOrUnbreakable(level, nextStepHead)) {
            return false;
        }
        if (BypassLavaRadar.isHazardNear(level, headClearance) || BypassLavaRadar.isHazardNear(level, nextStepFeet)
                || BypassLavaRadar.isHazardNear(level, nextStepHead)) {
            return false;
        }
        return true;
    }

    public static void rotateAscendingDirectionSafe(BypassProcess proc, Level level, BlockPos feet) {
        Direction cur = proc.getTunnelDir();
        Direction left = cur.getCounterClockWise();
        Direction right = cur.getClockWise();

        if (isAscendingDirectionClear(level, feet, right)) {
            proc.setTunnelDir(right);
        } else if (isAscendingDirectionClear(level, feet, left)) {
            proc.setTunnelDir(left);
        } else {
            proc.setTunnelDir(cur.getOpposite());
        }
    }

    public static boolean isSolidSupport(Level level, BlockPos pos) {
        if (level == null || pos == null)
            return false;
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && state.isSolid() && level.getFluidState(pos).isEmpty();
    }

    public static PathingCommand tickTunneling(BypassProcess proc, LocalPlayer player, Level level) {
        BlockPos feet = player.blockPosition();

        if (feet.getY() > proc.getTargetY() + 1 && feet.getY() > BypassProcess.MIN_SAFE_BEDROCK_Y) {
            proc.setState(BypassProcess.State.DESCENDING_STAIRCASE);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (feet.getY() < proc.getTargetY()) {
            if (feet.getY() >= BypassProcess.MIN_SAFE_BEDROCK_Y) {
                proc.setTargetY(feet.getY());
            } else {
                proc.setState(BypassProcess.State.ASCENDING_STAIRCASE);
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
        }

        proc.markTunnelDug(feet);

        // 0. Samoratowanie przed zasypaniem żwirem / piaskiem (Argon 4 & dj)
        BlockPos ownHead = feet.above();
        if (!isPassable(level, ownHead) && !isBedrockOrUnbreakable(level, ownHead)) {
            proc.stopMovementInputs();
            return proc.mineBlockSafely(player, level, ownHead);
        }
        if (!isPassable(level, feet) && !isBedrockOrUnbreakable(level, feet)) {
            proc.stopMovementInputs();
            return proc.mineBlockSafely(player, level, feet);
        }

        // 1. Obsługa aktywnego objazdu bocznego
        if (proc.getLateralDetourRemaining() > 0 && proc.getLateralOffsetDir() != null) {
            return tickLateralDetour(proc, player, level, feet);
        }

        proc.setTunnelDir(proc.getPrimaryMiningDir());

        // Centrowanie po powrocie z bocznej wnęki (Argon 4 & dj):
        // Jeśli gracz stoi na prostej drodze (lateralDist <= 0.35m), czyścimy kotwicę,
        // aby nigdy do niej nie zawracał.
        // Jeśli po wykopaniu rudy/dropu stoi w bocznej kieszeni (> 0.65m), celujemy na
        // oś tunelu 1.5m PRZED graczem.
        if (proc.getTunnelAnchorPos() != null && proc.getPrimaryMiningDir() != null) {
            BlockPos anchor = proc.getTunnelAnchorPos();
            Direction tunnelDir = proc.getPrimaryMiningDir();
            Direction left = tunnelDir.getCounterClockWise();
            double dxAnchor = (anchor.getX() + 0.5D) - player.getX();
            double dzAnchor = (anchor.getZ() + 0.5D) - player.getZ();
            double signedLateral = dxAnchor * left.getStepX() + dzAnchor * left.getStepZ();
            double lateralDist = Math.abs(signedLateral);

            if (lateralDist <= 0.35D) {
                proc.setTunnelAnchorPos(null);
            } else if (lateralDist > 0.65D) {
                double targetX = player.getX() + left.getStepX() * signedLateral + tunnelDir.getStepX() * 1.5D;
                double targetZ = player.getZ() + left.getStepZ() * signedLateral + tunnelDir.getStepZ() * 1.5D;
                Vec3 forwardAxisTarget = new Vec3(targetX, player.getY(), targetZ);
                moveSafelyTowards(proc, player, forwardAxisTarget);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }

        if (proc.getTurnCommitCooldown() > 0) {
            proc.setTurnCommitCooldown(proc.getTurnCommitCooldown() - 1);
        }
        if (proc.getTurnCooldownTicks() > 0) {
            proc.setTurnCooldownTicks(proc.getTurnCooldownTicks() - 1);
        }

        if (!feet.equals(proc.getLastCountedStraightFeet())) {
            proc.setLastCountedStraightFeet(feet.immutable());
            proc.setConsecutiveStraightBlocks(proc.getConsecutiveStraightBlocks() + 1);
            proc.setNoBlockBrokenTicks(0);
        }

        // 2. Ochrona przed zbyt bliskim korytarzem równoległym
        Direction primaryDir = proc.getPrimaryMiningDir();
        if (proc.getTurnCommitCooldown() <= 0 && proc.getConsecutiveStraightBlocks() >= 6
                && isTooCloseToOldTunnel(proc, feet, primaryDir)) {
            return forceLateralCorrection(proc, player, level, feet);
        }

        BlockPos frontFeet = feet.relative(primaryDir);
        BlockPos frontHead = frontFeet.above();

        // 2b. RADAR DŁUGODYSTANSOWY (Argon 4 & dj): 5 bloków przed lawą
        int forwardLavaDist = BypassLavaRadar.findForwardLavaDistance(level, feet, primaryDir, 30);
        if (forwardLavaDist > 0) {
            Direction bestTurnDir = BypassLavaRadar.chooseSafestBranchAwayFromLava(proc, level, feet, primaryDir, 30);
            boolean canTurnHere = isSafeBranchDirection(proc, level, feet, bestTurnDir, false);

            if (forwardLavaDist <= 5) {
                if (canTurnHere) {
                    proc.logDirect(String.format(
                            "§6[Bypass Radar] Wykryto lawę za %d bloków (strefa skrętu 5 bloków)! Skręt w stronę: %s",
                            forwardLavaDist, bestTurnDir));
                    proc.getHazardColumns2D().add(to2DKey(frontFeet.getX(), frontFeet.getZ()));
                    return initiateTurnState(proc, bestTurnDir);
                } else if (forwardLavaDist <= 2) {
                    proc.logDirect(String.format(
                            "§c[Bypass Radar] Brak bezpiecznego skrętu na %d bloków przed lawą! Wycofuję się...",
                            forwardLavaDist));
                    return executeRetreatBackward(proc, player, level, feet, primaryDir);
                }
            }
        }

        // 3a. PRIORYTET LAVA PLUGGING: Zatykanie odsłoniętego wycieku lawy
        BlockPos exposedBreach = BypassHazardSealer.findExposedLavaBreach(level, feet, primaryDir);
        if (exposedBreach != null) {
            if (BypassHazardSealer.selectThrowawayBlock(proc, player, exposedBreach)) {
                proc.setHazardSourcePos(exposedBreach);
                proc.setSealFailedAttempts(0);
                proc.setState(BypassProcess.State.SEALING_HAZARD);
                return BypassHazardSealer.handleSealingHazard(proc, player, level);
            }
            return handleLavaOrObstacleEncounter(proc, player, level, feet);
        }

        // 3b. Lawa ukryta za litą ścianą
        if (BypassLavaRadar.hasHiddenHazardBehind(level, frontHead, primaryDir)
                || BypassLavaRadar.hasHiddenHazardBehind(level, frontFeet, primaryDir)) {
            return handleLavaOrObstacleEncounter(proc, player, level, feet);
        }

        // 4. Sprawdź przeszkody w osi głównej (Bedrock / Lawa)
        boolean feetBedrock = isBedrockOrUnbreakable(level, frontFeet);
        boolean headBedrock = isBedrockOrUnbreakable(level, frontHead);
        boolean frontHazard = BypassLavaRadar.isFrontDirectlyBlockedByHazard(proc, level, feet, primaryDir);

        if (frontHazard || feetBedrock || headBedrock) {
            if ((feetBedrock || headBedrock) && !frontHazard && canShiftVertical(level, feet, primaryDir)) {
                return executeVerticalStep(proc, player, level, feet, primaryDir);
            }
            return handleLavaOrObstacleEncounter(proc, player, level, feet);
        }

        // 6. Obsługa Gravelu / Sandu
        if (isFallingBlockSettling(level, frontFeet, frontHead)) {
            proc.stopMovementInputs();
            AABB columnBox = new AABB(
                    frontFeet.getX(), frontFeet.getY(), frontFeet.getZ(),
                    frontFeet.getX() + 1.0, frontHead.getY() + 3.0, frontFeet.getZ() + 1.0);
            if (!level.getEntitiesOfClass(FallingBlockEntity.class, columnBox).isEmpty()) {
                proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }

            BlockPos gravelPos;
            if (proc.getCurrentBreakingBlock() != null
                    && (proc.getCurrentBreakingBlock().equals(frontHead)
                            || proc.getCurrentBreakingBlock().equals(frontFeet))
                    && !level.getBlockState(proc.getCurrentBreakingBlock()).isAir()) {
                gravelPos = proc.getCurrentBreakingBlock();
            } else {
                gravelPos = !isPassable(level, frontHead) ? frontHead : frontFeet;
            }

            if (!isPassable(level, gravelPos)) {
                return proc.mineBlockSafely(player, level, gravelPos);
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // 7. Kopanie korytarza 1x2 przed nami
        boolean headBlocked = !isPassable(level, frontHead);
        boolean feetBlocked = !isPassable(level, frontFeet);

        if (headBlocked || feetBlocked) {
            if (canSprintMine(proc, player, level, frontHead, frontFeet)) {
                return executeSprintMining(proc, player, level, frontHead, frontFeet);
            }

            BlockPos blockToMine;
            if (proc.getCurrentBreakingBlock() != null
                    && (proc.getCurrentBreakingBlock().equals(frontHead)
                            || proc.getCurrentBreakingBlock().equals(frontFeet))
                    && !level.getBlockState(proc.getCurrentBreakingBlock()).isAir()) {
                blockToMine = proc.getCurrentBreakingBlock();
            } else {
                blockToMine = headBlocked ? frontHead : frontFeet;
            }

            // Płynny pęd (Momentum Mining): jeśli gracz jest dalej niż 1.35m od bloku,
            // kontynuuj płynny chód naprzód zamiast gwałtownego hamowania w miejscu (Argon
            // 4 & dj)
            Vec3 eye = player.getEyePosition(1.0F);
            double distSq = eye.distanceToSqr(Vec3.atCenterOf(blockToMine));
            if (distSq > (1.35 * 1.35) && !player.horizontalCollision) {
                moveSafelyTowards(proc, player, Vec3.atBottomCenterOf(frontFeet));
            } else {
                proc.stopMovementInputs();
                holdPositionCentered(proc, player, frontFeet, primaryDir);
            }

            Rotation safeRot = calcSafeTunnelMiningRotation(proc, player, blockToMine, primaryDir);
            return proc.mineBlockSafely(player, level, blockToMine, safeRot);
        }

        // 8. Ochrona przed wejściem w przepaść lub do jeziora lawy
        BlockPos stepFloor = frontFeet.below();
        if (!BypassHazardSealer.hasStableFloorSupport(level, stepFloor)) {
            if (level.getBlockState(stepFloor).canBeReplaced()
                    && BypassHazardSealer.tryBridgeFloor(proc, player, level, stepFloor, feet.below())) {
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            return handleLavaOrObstacleEncounter(proc, player, level, feet);
        }

        // Korytarz czysty -> płynny krok naprzód
        proc.setTunnelAnchorPos(frontFeet);
        proc.setConsecutiveStraightBlocks(proc.getConsecutiveStraightBlocks() + 1);
        moveSafelyTowards(proc, player, Vec3.atBottomCenterOf(frontFeet));
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    /**
     * Oblicza bezpieczny kąt Pitch (GrimAC-safe) dla bloków korytarza 1x2
     * (frontHead / frontFeet),
     * dynamicznie dostosowany do odległości dFace (Reach = 4.0m) (Argon 4 & dj).
     */
    /**
     * Oblicza bezpieczny kąt Pitch i Yaw (GrimAC-safe) dla bloków korytarza 1x2 (frontHead / frontFeet).
     * Wytyczne dj i Argon 4:
     * - Celownik myszy ZAWSZE celuje tak, by NIGDY nie niszczyć bloku podłogi pod nogami gracza (feet.below()).
     * - Dla bloku głowy (frontHead): kąt pitch wynosi ok. 0°..4° (na wprost).
     * - Dla bloku nóg (frontFeet): kąt pitch jest ściśle ograniczony do 16°..24.5° (górna połowa bloku nóg).
     *   Przy pitch <= 24.5° promień wzroku z wysokości oczu 1.62m nigdy nie dosięga podłogi pod stopami gracza.
     */
    public static Rotation calcSafeTunnelMiningRotation(BypassProcess proc, LocalPlayer player, BlockPos targetPos,
            Direction tunnelDir) {
        Vec3 eyePos = player.getEyePosition(1.0F);
        BlockPos feetPos = player.blockPosition();
        int feetY = feetPos.getY();
        boolean isHeadBlock = targetPos.getY() > feetY;

        // Yaw: osiowy kierunek tunelu + mikro-korekta centrowania
        float baseYaw = tunnelDir.toYRot();
        double centerOffset = (tunnelDir.getAxis() == Direction.Axis.Z)
                ? (player.getX() - (feetPos.getX() + 0.5D))
                : (player.getZ() - (feetPos.getZ() + 0.5D));
        float steer = 0.0f;
        if (Math.abs(centerOffset) > 0.04D) {
            steer = (float) Math.clamp(-centerOffset * 12.0D, -3.5D, 3.5D);
        }
        float targetYaw = baseYaw + steer;

        // Pitch:
        // - frontHead (Y+1): celuj na wprost w środek bloku głowy (Y = feetY + 1.45m), pitch ok. 0°..6°
        // - frontFeet (Y+0): celuj w górną połowę bloku nóg (Y = feetY + 0.75m), pitch max 24.5°
        float targetPitch;
        if (isHeadBlock) {
            double dy = (feetY + 1.45D) - eyePos.y; // ~ -0.17m
            double dHoriz = Math.max(0.6D, horizontalDistanceToCenter(player.position(), targetPos));
            targetPitch = (float) -(Math.atan2(dy, dHoriz) * (180.0D / Math.PI));
            targetPitch = Mth.clamp(targetPitch, -6.0F, 8.0F);
        } else {
            double dy = (feetY + 0.75D) - eyePos.y; // ~ -0.87m
            double dHoriz = Math.max(0.75D, horizontalDistanceToCenter(player.position(), targetPos));
            targetPitch = (float) -(Math.atan2(dy, dHoriz) * (180.0D / Math.PI));
            targetPitch = Mth.clamp(targetPitch, 14.0F, 24.5F);
        }

        return applyGrimGcd(player.getYRot(), player.getXRot(), targetYaw, targetPitch);
    }

    private static int entranceStuckTicks = 0;

    public static void holdPositionCentered(BypassProcess proc, LocalPlayer player, BlockPos tunnelBlock,
            Direction tunnelDir) {
        Direction leftDir = tunnelDir.getCounterClockWise();
        double dx = (tunnelBlock.getX() + 0.5D) - player.getX();
        double dz = (tunnelBlock.getZ() + 0.5D) - player.getZ();
        double signedLat = dx * leftDir.getStepX() + dz * leftDir.getStepZ();

        if (Math.abs(signedLat) > 0.05D) {
            proc.getBaritone().getInputOverrideHandler()
                    .setInputForceState(signedLat > 0 ? Input.MOVE_LEFT : Input.MOVE_RIGHT, true);
            proc.getBaritone().getInputOverrideHandler()
                    .setInputForceState(signedLat > 0 ? Input.MOVE_RIGHT : Input.MOVE_LEFT, false);
        } else {
            proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, false);
            proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, false);
        }
    }

    public static boolean canSprintMine(BypassProcess proc, LocalPlayer player, Level level, BlockPos frontHead,
            BlockPos frontFeet) {
        if (!proc.isFastPickaxeActive(player)) {
            return false;
        }
        BlockState headState = level.getBlockState(frontHead);
        BlockState feetState = level.getBlockState(frontFeet);

        if (headState.getBlock() instanceof FallingBlock || feetState.getBlock() instanceof FallingBlock
                || isFallingBlock(headState) || isFallingBlock(feetState)) {
            return false;
        }

        float headHardness = headState.getDestroySpeed(level, frontHead);
        float feetHardness = feetState.getDestroySpeed(level, frontFeet);
        if (headHardness < 0 || headHardness > 4.5f || feetHardness < 0 || feetHardness > 4.5f) {
            return false;
        }

        BlockPos nextFloor = frontFeet.below();
        if (!BypassHazardSealer.hasStableFloorSupport(level, nextFloor)
                || !BypassHazardSealer.hasStableFloorSupport(level, player.blockPosition().below())) {
            return false;
        }

        return !BypassLavaRadar.isFrontDirectlyBlockedByHazard(proc, level, player.blockPosition(),
                proc.getPrimaryMiningDir());
    }

    public static PathingCommand executeSprintMining(BypassProcess proc, LocalPlayer player, Level level,
            BlockPos frontHead, BlockPos frontFeet) {
        if (isFallingBlockSettling(level, frontFeet, frontHead)) {
            proc.stopMovementInputs();
            AABB columnBox = new AABB(
                    frontFeet.getX(), frontFeet.getY(), frontFeet.getZ(),
                    frontFeet.getX() + 1.0, frontHead.getY() + 3.0, frontFeet.getZ() + 1.0);
            if (!level.getEntitiesOfClass(FallingBlockEntity.class, columnBox).isEmpty()) {
                proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }

            BlockPos gravelPos;
            if (proc.getCurrentBreakingBlock() != null
                    && (proc.getCurrentBreakingBlock().equals(frontHead)
                            || proc.getCurrentBreakingBlock().equals(frontFeet))
                    && !level.getBlockState(proc.getCurrentBreakingBlock()).isAir()) {
                gravelPos = proc.getCurrentBreakingBlock();
            } else {
                gravelPos = !isPassable(level, frontHead) ? frontHead : frontFeet;
            }

            if (!isPassable(level, gravelPos)) {
                return proc.mineBlockSafely(player, level, gravelPos);
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        boolean headBlocked = !isPassable(level, frontHead);
        boolean feetBlocked = !isPassable(level, frontFeet);
        double distH = horizontalDistanceToCenter(player.position(), frontFeet);

        // KRYTYCZNA KOLEJNOŚĆ KOPANIA (dj & Argon 4):
        // ZAWSZE najpierw rozbij blok głowy (frontHead), a dopiero potem blok nóg (frontFeet)!
        // Zapobiega to patrzeniu w dół pod nogi i uszkodzeniu podłogi.
        BlockPos activeBreakTarget = headBlocked ? frontHead : frontFeet;
        BlockPos toolTargetPos = activeBreakTarget;

        Minecraft mc = Minecraft.getInstance();
        if (mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            BlockPos hitPos = bhr.getBlockPos();
            BlockState hitState = level.getBlockState(hitPos);
            if (!hitState.isAir()) {
                if (hitPos.getY() < player.blockPosition().getY()) {
                    proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
                    proc.stopMovementInputs();
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                boolean frontStillBlocked = !level.getBlockState(frontHead).isAir()
                        || !level.getBlockState(frontFeet).isAir();
                if (frontStillBlocked && !hitPos.equals(frontHead) && !hitPos.equals(frontFeet)) {
                    proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
                    proc.stopMovementInputs();
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                toolTargetPos = hitPos;
                activeBreakTarget = hitPos;
            }
        }

        BlockState stateToMine = level.getBlockState(toolTargetPos);
        if (stateToMine.isAir()) {
            stateToMine = headBlocked ? level.getBlockState(frontHead) : level.getBlockState(frontFeet);
        }

        BypassProcess.ToolEquipStatus toolStatus = proc.equipBestTool(player, stateToMine);
        if (toolStatus == BypassProcess.ToolEquipStatus.FAILED) {
            proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            proc.stopMovementInputs();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (toolStatus == BypassProcess.ToolEquipStatus.SWITCHED) {
            proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        ItemStack held = player.getMainHandItem();
        if ((stateToMine.requiresCorrectToolForDrops() && !held.isCorrectToolForDrops(stateToMine))
                || !proc.isToolUsable(held)) {
            proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            proc.stopMovementInputs();
            proc.resetMiningState();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        Direction primaryMiningDir = proc.getPrimaryMiningDir();
        Rotation safeRot = calcSafeTunnelMiningRotation(proc, player, activeBreakTarget, primaryMiningDir);
        float targetYaw = safeRot.getYaw();

        BlockPos feetPos = player.blockPosition();
        double centerOffset = (primaryMiningDir.getAxis() == Direction.Axis.Z)
                ? (player.getX() - (feetPos.getX() + 0.5D))
                : (player.getZ() - (feetPos.getZ() + 0.5D));
        if (Math.abs(centerOffset) > 0.05D) {
            float steer = (float) Math.clamp(-centerOffset * 15.0D, -3.0D, 3.0D);
            targetYaw += steer;
        }

        Rotation sprintRot = applyGrimGcd(player.getYRot(), player.getXRot(), targetYaw, safeRot.getPitch());
        proc.getBaritone().getLookBehavior().updateTarget(sprintRot, true);

        // BEZWZGLĘDNA OCHRONA PODŁOGI (dj & Argon 4):
        // Nie klikaj lewym jeśli celownik trafi w podłogę pod nogami gracza!
        boolean canClick = true;
        if (mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            if (bhr.getBlockPos().getY() < player.blockPosition().getY() || bhr.getBlockPos().equals(player.blockPosition().below())) {
                canClick = false;
            }
        }

        proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, canClick);
        if (canClick) {
            ContinuousBreakController.notifyBreaking(proc.getPlayerContext(), new BetterBlockPos(activeBreakTarget));
        }

        proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
        proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        boolean allowSprint = proc.isFastPickaxeActive(player)
                && player.getFoodData().getFoodLevel() > 6
                && !player.horizontalCollision;
        proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.SPRINT, allowSprint);
        proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, false);
        proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, false);
        proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.JUMP, false);

        proc.setConsecutiveStraightBlocks(proc.getConsecutiveStraightBlocks() + 1);
        proc.setActiveMiningTicks(0);
        proc.setAimStallTicks(0);

        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    public static boolean isTooCloseToOldTunnel(BypassProcess proc, BlockPos feet, Direction forward) {
        Direction right = forward.getClockWise();
        Direction left = forward.getCounterClockWise();

        for (int offset = 1; offset < MIN_PARALLEL_SPACING; offset++) {
            BlockPos checkRight = feet.relative(right, offset);
            BlockPos checkLeft = feet.relative(left, offset);

            if (proc.getDugColumns2D().contains(to2DKey(checkRight.getX(), checkRight.getZ()))
                    || proc.getDugColumns2D().contains(to2DKey(checkLeft.getX(), checkLeft.getZ()))) {
                return true;
            }
        }
        return false;
    }

    public static PathingCommand forceLateralCorrection(BypassProcess proc, LocalPlayer player, Level level,
            BlockPos feet) {
        Direction primaryMiningDir = proc.getPrimaryMiningDir();
        Direction right = primaryMiningDir.getClockWise();
        Direction left = primaryMiningDir.getCounterClockWise();

        boolean rightBlocked = proc.getDugColumns2D()
                .contains(to2DKey(feet.relative(right).getX(), feet.relative(right).getZ()));
        Direction escapeSide = rightBlocked ? left : right;

        proc.setLateralOffsetDir(escapeSide);
        proc.setLateralDetourRemaining(MIN_PARALLEL_SPACING);
        proc.setTurnCommitCooldown(40);
        proc.setConsecutiveStraightBlocks(0);
        return tickLateralDetour(proc, player, level, feet);
    }

    public static boolean canShiftVertical(Level level, BlockPos feet, Direction forward) {
        BlockPos stepUpFeet = feet.above().relative(forward);
        BlockPos stepUpHead = stepUpFeet.above();
        BlockPos ceilingAboveHead = feet.above(2);

        if (isBedrockOrUnbreakable(level, ceilingAboveHead))
            return false;
        if (isBedrockOrUnbreakable(level, stepUpFeet) || isBedrockOrUnbreakable(level, stepUpHead))
            return false;
        if (BypassLavaRadar.isHazardNear(level, stepUpFeet) || BypassLavaRadar.isHazardNear(level, stepUpHead))
            return false;

        return true;
    }

    public static PathingCommand executeVerticalStep(BypassProcess proc, LocalPlayer player, Level level, BlockPos feet,
            Direction forward) {
        BlockPos aboveHead = feet.above(2);
        if (!isPassable(level, aboveHead)) {
            return proc.mineBlockSafely(player, level, aboveHead);
        }

        BlockPos stepUpFeet = feet.above().relative(forward);
        BlockPos stepUpHead = stepUpFeet.above();

        if (!isPassable(level, stepUpHead)) {
            return proc.mineBlockSafely(player, level, stepUpHead);
        }
        if (!isPassable(level, stepUpFeet)) {
            return proc.mineBlockSafely(player, level, stepUpFeet);
        }

        proc.setTunnelAnchorPos(stepUpFeet);
        moveSafelyTowards(proc, player, Vec3.atBottomCenterOf(stepUpFeet));
        boolean canStepJump = player.onGround() && isPassable(level, feet.above(2));
        proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.JUMP, canStepJump);
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    public static PathingCommand tickLateralDetour(BypassProcess proc, LocalPlayer player, Level level, BlockPos feet) {
        Direction lateralOffsetDir = proc.getLateralOffsetDir();
        if (lateralOffsetDir == null) {
            proc.setLateralDetourRemaining(0);
            return handleLavaOrObstacleEncounter(proc, player, level, feet);
        }

        BlockPos targetFeet = feet.relative(lateralOffsetDir);
        BlockPos targetHead = targetFeet.above();

        if (isBedrockOrUnbreakable(level, targetFeet) || isBedrockOrUnbreakable(level, targetHead)
                || BypassLavaRadar.isHazardNear(level, targetFeet) || BypassLavaRadar.isHazardNear(level, targetHead)) {
            proc.setLateralDetourRemaining(0);
            proc.setLateralOffsetDir(null);
            return handleLavaOrObstacleEncounter(proc, player, level, feet);
        }

        if (!isPassable(level, targetHead)) {
            return proc.mineBlockSafely(player, level, targetHead);
        }
        if (!isPassable(level, targetFeet)) {
            return proc.mineBlockSafely(player, level, targetFeet);
        }

        proc.markTunnelDug(targetFeet);
        moveSafelyTowards(proc, player, Vec3.atBottomCenterOf(targetFeet));
        proc.setLateralDetourRemaining(proc.getLateralDetourRemaining() - 1);

        if (proc.getLateralDetourRemaining() <= 0) {
            proc.setLateralDetourRemaining(0);
            proc.setLateralOffsetDir(null);
            proc.setTunnelDir(proc.getPrimaryMiningDir());
        }

        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    public static boolean isSafeBranchDirection(BypassProcess proc, Level level, BlockPos origin, Direction dir,
            boolean allowExistingTunnels) {
        BlockPos checkFeet = origin.relative(dir);
        BlockPos checkHead = checkFeet.above();
        BlockPos checkCeiling = checkHead.above();
        BlockPos checkFloor = checkFeet.below();

        if (!allowExistingTunnels && proc.getDugColumns2D().contains(to2DKey(checkFeet.getX(), checkFeet.getZ()))) {
            return false;
        }

        if (proc.getHazardColumns2D().contains(to2DKey(checkFeet.getX(), checkFeet.getZ()))) {
            return false;
        }

        if (isBedrockOrUnbreakable(level, checkFeet) || isBedrockOrUnbreakable(level, checkHead)) {
            return false;
        }

        if (BypassLavaRadar.isHazardNear(level, checkFeet) || BypassLavaRadar.isHazardNear(level, checkHead)
                || BypassLavaRadar.isHazardNear(level, checkCeiling)
                || BypassLavaRadar.isHazardNear(level, checkFloor)) {
            return false;
        }

        BlockPos probeFeet = checkFeet.relative(dir);
        BlockPos probeHead = probeFeet.above();
        if (BypassLavaRadar.isHazardNear(level, probeFeet) || BypassLavaRadar.isHazardNear(level, probeHead)) {
            return false;
        }

        return true;
    }

    public static boolean isLookingDownExistingTunnel(BypassProcess proc, Level level, BlockPos feet, Direction dir) {
        int emptyCount = 0;
        BlockPos probe = feet;
        for (int i = 0; i < 4; i++) {
            probe = probe.relative(dir);
            if (isPassable(level, probe) && isPassable(level, probe.above())) {
                emptyCount++;
            }
            if (proc.getDugColumns2D().contains(to2DKey(probe.getX(), probe.getZ()))) {
                emptyCount += 2;
            }
        }
        return emptyCount >= 3;
    }

    public static Direction pickBestBranchDirection(BypassProcess proc, Level level, BlockPos feet, Direction leftDir,
            Direction rightDir) {
        boolean leftIsTunnel = isLookingDownExistingTunnel(proc, level, feet, leftDir);
        boolean rightIsTunnel = isLookingDownExistingTunnel(proc, level, feet, rightDir);

        if (leftIsTunnel && !rightIsTunnel)
            return rightDir;
        if (!leftIsTunnel && rightIsTunnel)
            return leftDir;

        return BypassLavaRadar.chooseSafestBranchAwayFromLava(proc, level, feet, proc.getPrimaryMiningDir(), 30);
    }

    public static PathingCommand handleLavaOrObstacleEncounter(BypassProcess proc, LocalPlayer player, Level level,
            BlockPos feet) {
        proc.getBaritone().getInputOverrideHandler().clearAllKeys();
        proc.resetMiningState();

        Direction currentDir = proc.getPrimaryMiningDir() != null ? proc.getPrimaryMiningDir() : Direction.SOUTH;
        proc.setBlockedForwardDir(currentDir);

        BlockPos frontPos = feet.relative(currentDir);
        proc.getHazardColumns2D().add(to2DKey(frontPos.getX(), frontPos.getZ()));

        BlockPos nodeKey = feet.immutable();
        EnumSet<Direction> blockedAtNode = proc.getBlockedDirsByNode().computeIfAbsent(nodeKey,
                k -> EnumSet.noneOf(Direction.class));
        blockedAtNode.add(currentDir);
        blockedAtNode.add(currentDir.getOpposite());

        Direction leftDir = currentDir.getCounterClockWise();
        Direction rightDir = currentDir.getClockWise();

        boolean leftFresh = !blockedAtNode.contains(leftDir)
                && isSafeBranchDirection(proc, level, feet, leftDir, false);
        boolean rightFresh = !blockedAtNode.contains(rightDir)
                && isSafeBranchDirection(proc, level, feet, rightDir, false);

        boolean leftFallback = leftFresh || isSafeBranchDirection(proc, level, feet, leftDir, true);
        boolean rightFallback = rightFresh || isSafeBranchDirection(proc, level, feet, rightDir, true);

        proc.logDirect(String.format("§e[Bypass] Przeszkoda na węźle! Ściany: L=%s/%s, P=%s/%s",
                (leftFresh ? "§aŚWIEŻA" : "§c-"), (leftFallback ? "§bTUNEL" : "§cZABLOK"),
                (rightFresh ? "§aŚWIEŻA" : "§c-"), (rightFallback ? "§bTUNEL" : "§cZABLOK")));

        if (leftFresh && rightFresh) {
            Direction chosen = BypassLavaRadar.chooseSafestBranchAwayFromLava(proc, level, feet, currentDir, 30);
            proc.logDirect("§a[Bypass] Obie strony bezpieczne od lawy! Skręcam w stronę: §f" + chosen.name());
            return initiateTurnState(proc, chosen);
        }
        if (leftFresh) {
            proc.logDirect("§e[Bypass] Skręcam natychmiast w §aLEWO§e!");
            return initiateTurnState(proc, leftDir);
        }
        if (rightFresh) {
            proc.logDirect("§e[Bypass] Skręcam natychmiast w §aPRAWO§e!");
            return initiateTurnState(proc, rightDir);
        }

        if (leftFallback && rightFallback) {
            Direction chosen = pickBestBranchDirection(proc, level, feet, leftDir, rightDir);
            proc.logDirect("§b[Bypass] Używam bezpiecznego tunelu do ominięcia: §f" + chosen.name());
            return initiateTurnState(proc, chosen);
        }
        if (leftFallback) {
            proc.logDirect("§b[Bypass] Omijanie przeszkody przez bezpieczny tunel po §aLEWEJ§b!");
            return initiateTurnState(proc, leftDir);
        }
        if (rightFallback) {
            proc.logDirect("§b[Bypass] Omijanie przeszkody przez bezpieczny tunel po §aPRAWEJ§b!");
            return initiateTurnState(proc, rightDir);
        }

        if (!leftFallback)
            blockedAtNode.add(leftDir);
        if (!rightFallback)
            blockedAtNode.add(rightDir);

        return executeRetreatBackward(proc, player, level, feet, currentDir);
    }

    public static PathingCommand initiateTurnState(BypassProcess proc, Direction newDir) {
        proc.getBaritone().getInputOverrideHandler().clearAllKeys();
        proc.stopMovementInputs();
        proc.resetMiningState();
        proc.setPendingTurnDir(newDir);
        proc.setState(BypassProcess.State.TURNING);
        proc.setTurnBrakeTicks(3);
        proc.setTurnCooldownTicks(15);
        if (proc.getPlayerContext().playerController() != null) {
            proc.getPlayerContext().playerController().resetBlockRemoving();
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    public static PathingCommand tickTurning(BypassProcess proc, LocalPlayer player, Level level) {
        proc.stopMovementInputs();
        proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
        proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
        proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
        proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.SPRINT, false);

        Direction pendingDir = proc.getPendingTurnDir();
        if (pendingDir == null) {
            proc.setState(BypassProcess.State.TUNNELING);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        Vec3 vel = player.getDeltaMovement();
        double horizSpeedSq = vel.x * vel.x + vel.z * vel.z;
        if (proc.getTurnBrakeTicks() > 0 || horizSpeedSq > 1.0E-4D) {
            if (proc.getTurnBrakeTicks() > 0) {
                proc.setTurnBrakeTicks(proc.getTurnBrakeTicks() - 1);
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        float targetYaw = pendingDir.toYRot();
        float targetPitch = 0.0f;

        Rotation gcdRot = applyGrimGcd(player.getYRot(), player.getXRot(), targetYaw, targetPitch);
        proc.getBaritone().getLookBehavior().updateTarget(gcdRot, true);

        float yawDiff = Math.abs(Mth.wrapDegrees(player.getYRot() - targetYaw));
        if (yawDiff > 2.5f) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        BlockPos feet = player.blockPosition();
        Vec3 blockCenter = new Vec3(feet.getX() + 0.5D, player.getY(), feet.getZ() + 0.5D);
        double lateralError = (pendingDir.getAxis() == Direction.Axis.Z)
                ? Math.abs(player.getX() - blockCenter.x)
                : Math.abs(player.getZ() - blockCenter.z);

        if (lateralError > 0.15D) {
            stepTowardsPointWithSneak(proc, player, blockCenter);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        proc.setPrimaryMiningDir(pendingDir);
        proc.setTunnelDir(pendingDir);
        proc.setBlockedForwardDir(null);
        proc.setLateralDetourRemaining(0);
        proc.setLateralOffsetDir(null);
        proc.setTurnCommitCooldown(40);
        proc.setConsecutiveStraightBlocks(0);
        proc.setPendingTurnDir(null);
        proc.setState(BypassProcess.State.TUNNELING);

        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    public static PathingCommand executeRetreatBackward(BypassProcess proc, LocalPlayer player, Level level,
            BlockPos feet, Direction forwardDir) {
        proc.getHazardColumns2D().add(to2DKey(feet.getX(), feet.getZ()));

        proc.setRetreatDir(forwardDir.getOpposite());
        proc.setRetreatStartPos(feet.immutable());
        proc.setRetreatTicks(0);
        proc.setConsecutiveStraightBlocks(0);
        proc.setLastCountedStraightFeet(null);
        proc.setState(BypassProcess.State.RETREATING);

        int targetBlocks = ThreadLocalRandom.current().nextInt(7, 12);
        proc.setTargetRetreatBlocks(targetBlocks);

        proc.logDirect(String.format(
                "§c[Bypass] Wycofuję się o §b%d§c bloków w tył bezpiecznym tunelem (strefa buforowa)...",
                targetBlocks));
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    public static PathingCommand tickRetreating(BypassProcess proc, LocalPlayer player, Level level) {
        proc.setRetreatTicks(proc.getRetreatTicks() + 1);
        proc.getBaritone().getInputOverrideHandler().clearAllKeys();

        Direction retreatDir = proc.getRetreatDir();
        BlockPos retreatStartPos = proc.getRetreatStartPos();
        if (retreatDir == null || retreatStartPos == null) {
            proc.setState(BypassProcess.State.TUNNELING);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        BlockPos feet = player.blockPosition();
        int distMoved = feet.distManhattan(retreatStartPos);

        Direction forwardDir = retreatDir.getOpposite();
        Direction leftDir = forwardDir.getCounterClockWise();
        Direction rightDir = forwardDir.getClockWise();

        BlockPos backFeet = feet.relative(retreatDir);
        BlockPos backHead = backFeet.above();
        boolean backPassable = isPassable(level, backFeet) && isPassable(level, backHead)
                && !BypassLavaRadar.isLava(level, backFeet.below()) && !BypassLavaRadar.isLava(level, backFeet);

        int maxBlocks = proc.getTargetRetreatBlocks() > 0 ? proc.getTargetRetreatBlocks() : 9;

        if (distMoved >= maxBlocks || !backPassable || proc.getRetreatTicks() > MAX_RETREAT_TICKS) {
            boolean leftFresh = isSafeBranchDirection(proc, level, feet, leftDir, false);
            boolean rightFresh = isSafeBranchDirection(proc, level, feet, rightDir, false);

            Direction chosenTurn;
            if (leftFresh && rightFresh) {
                chosenTurn = BypassLavaRadar.chooseSafestBranchAwayFromLava(proc, level, feet, forwardDir, 30);
            } else if (leftFresh) {
                chosenTurn = leftDir;
            } else if (rightFresh) {
                chosenTurn = rightDir;
            } else {
                boolean leftIsDug = proc.getDugColumns2D()
                        .contains(to2DKey(feet.relative(leftDir).getX(), feet.relative(leftDir).getZ()));
                boolean rightIsDug = proc.getDugColumns2D()
                        .contains(to2DKey(feet.relative(rightDir).getX(), feet.relative(rightDir).getZ()));
                if (!leftIsDug && rightIsDug) {
                    chosenTurn = leftDir;
                } else if (!rightIsDug && leftIsDug) {
                    chosenTurn = rightDir;
                } else {
                    chosenTurn = BypassLavaRadar.chooseSafestBranchAwayFromLava(proc, level, feet, forwardDir, 30);
                }
            }

            proc.setPrimaryMiningDir(chosenTurn);
            proc.setTunnelDir(chosenTurn);
            proc.setBlockedForwardDir(null);
            proc.setRetreatDir(null);
            proc.setRetreatStartPos(null);
            proc.setRetreatTicks(0);
            proc.setTurnCommitCooldown(30);
            proc.setConsecutiveStraightBlocks(0);

            proc.logDirect(String.format("§a[Bypass] Wyjście z odwrotu: nowy kierunek korytarza: §b%s", chosenTurn));
            return initiateTurnState(proc, chosenTurn);
        }

        // Płynne wycofywanie tyłem wzdłuż osi tunelu (MOVE_BACK, zero odwracania
        // kamery)
        float targetYaw = forwardDir.toYRot();
        float targetPitch = 0.0f;
        Rotation gcdRot = applyGrimGcd(player.getYRot(), player.getXRot(), targetYaw, targetPitch);
        proc.getBaritone().getLookBehavior().updateTarget(gcdRot, true);

        Vec3 blockCenter = new Vec3(feet.getX() + 0.5D, player.getY(), feet.getZ() + 0.5D);
        double lateralDist = (forwardDir.getAxis() == Direction.Axis.Z)
                ? (player.getX() - blockCenter.x)
                : (player.getZ() - blockCenter.z);

        proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
        proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
        proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.SPRINT, false);

        if (Math.abs(lateralDist) > 0.12D) {
            boolean moveRight = (forwardDir.getAxis() == Direction.Axis.Z)
                    ? (lateralDist < 0)
                    : (lateralDist > 0);
            proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, moveRight);
            proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, !moveRight);
        } else {
            proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, false);
            proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, false);
        }

        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    public static void stepTowardsPointWithSneak(BypassProcess proc, LocalPlayer player, Vec3 targetPoint) {
        double dx = targetPoint.x - player.getX();
        double dz = targetPoint.z - player.getZ();
        if (dx * dx + dz * dz < 0.002) {
            return;
        }

        float desiredYaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float relAngle = Mth.wrapDegrees(desiredYaw - player.getYRot());

        proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.SNEAK, true);

        if (Math.abs(relAngle) <= 65.0f) {
            proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        } else if (Math.abs(relAngle) >= 115.0f) {
            proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
        } else if (relAngle < 0) {
            proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, true);
        } else {
            proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, true);
        }
    }

    public static void moveSafelyTowards(BypassProcess proc, LocalPlayer player, Vec3 targetCenter) {
        Level level = player.level();
        BlockPos feet = player.blockPosition();

        if (player.isInWater() || !level.getFluidState(feet).isEmpty()) {
            handleWaterNavigation(proc, player, level, targetCenter);
            return;
        }

        Direction moveDir = proc.getTunnelDir() != null ? proc.getTunnelDir() : proc.getPrimaryMiningDir();
        if (moveDir == null)
            moveDir = Direction.NORTH;

        double dx = targetCenter.x - player.getX();
        double dz = targetCenter.z - player.getZ();

        // Składowa wzdłuż osi marszu
        double forwardDist = dx * moveDir.getStepX() + dz * moveDir.getStepZ();

        // KRYTYCZNE ZABEZPIECZENIE (Argon 4 & dj):
        // Jeśli gracz przekroczył środek bloku docelowego .50 (np. jest na .65, a
        // targetCenter to .50),
        // wektor dx/dz bez zabezpieczenia zmienia znak na ujemny i odwraca celownik o
        // 180° za siebie!
        // Wypychamy punkt patrzenia o min. 2.0m w przód wzdłuż moveDir:
        if (forwardDist < 1.2D) {
            double pushForward = 2.0D - forwardDist;
            dx += moveDir.getStepX() * pushForward;
            dz += moveDir.getStepZ() * pushForward;
        }

        Direction leftDir = moveDir.getCounterClockWise();
        double signedLateralError = dx * leftDir.getStepX() + dz * leftDir.getStepZ();
        double absLateralError = Math.abs(signedLateralError);

        // Wykrywanie zahaczenia o framugę wejścia do tunelu (kolizja pozioma lub zerowa
        // prędkość przy odchyleniu)
        double hSpeedSq = player.getDeltaMovement().horizontalDistanceSqr();
        boolean hittingFrame = player.horizontalCollision || (hSpeedSq < 0.0015D && absLateralError > 0.10D);
        if (hittingFrame) {
            entranceStuckTicks++;
        } else {
            entranceStuckTicks = 0;
        }

        // CIĄGŁA KOREKCJA BOCZNA: próg 0.05m (5 cm) gwarantuje zmieszczenie hitboxa
        // 0.6m w otwór 1.0m
        boolean strafeLeft = false;
        boolean strafeRight = false;
        if (absLateralError > 0.05D) {
            strafeLeft = signedLateralError > 0;
            strafeRight = signedLateralError < 0;
        }

        float baseYaw = moveDir.toYRot();
        float targetYaw = baseYaw;

        // MECHANIZM WŚLIZGIWANIA W TUNEL (Gdy bot zahaczy o narożnik wejścia) (Argon 4
        // & dj)
        if (entranceStuckTicks > 0 && absLateralError > 0.10D) {
            if (entranceStuckTicks >= 2 && entranceStuckTicks <= 4) {
                proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
                proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
            } else {
                proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
                proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
            }
            proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
            float slideOffset = (float) Mth.clamp(signedLateralError * -35.0D, -22.0D, 22.0D);
            targetYaw = baseYaw + slideOffset;
        } else {
            proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
            proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
            if (absLateralError >= 0.10D) {
                float gentleSteer = (float) Mth.clamp(signedLateralError * -25.0D, -18.0D, 18.0D);
                targetYaw = baseYaw + gentleSteer;
            }
        }

        // Trzymaj celownik WYSOKO podczas marszu (pitch = 8.0f), nigdy w dół pod nogi!
        Rotation gcdMoveRot = applyGrimGcd(player.getYRot(), player.getXRot(), targetYaw, 8.0F);
        proc.getBaritone().getLookBehavior().updateTarget(gcdMoveRot, true);

        float yawDelta = Math.abs(Mth.wrapDegrees(player.getYRot() - gcdMoveRot.getYaw()));

        proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
        proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, strafeLeft);
        proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, strafeRight);

        BlockPos step1 = player.blockPosition().relative(moveDir);
        BlockPos step2 = step1.relative(moveDir);
        boolean corridorClear2Blocks = isPassable(level, step1)
                && isPassable(level, step1.above())
                && isPassable(level, step2)
                && isPassable(level, step2.above());

        boolean allowSprint = (corridorClear2Blocks || proc.isFastPickaxeActive(player))
                && player.getFoodData().getFoodLevel() > 6
                && !player.horizontalCollision
                && absLateralError < 0.35D
                && yawDelta < 15.0f;
        proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.SPRINT, allowSprint);

        boolean hasHeadroom = isPassable(level, feet.above(2));
        if (hasHeadroom && shouldEarlyJump(player, level, targetCenter, moveDir)) {
            proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.JUMP, true);
        } else {
            proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.JUMP, false);
        }

        updateMovementStuckWatchdog(proc, player, level, moveDir);
    }

    public static void handleWaterNavigation(BypassProcess proc, LocalPlayer player, Level level, Vec3 targetCenter) {
        BlockPos feet = player.blockPosition();
        FluidState fluid = level.getFluidState(feet);
        Vec3 flow = fluid.getFlow(level, feet);

        Vec3 toTarget = targetCenter.subtract(player.position()).normalize();
        Vec3 compensated = toTarget.subtract(flow.scale(0.65D)).normalize();
        float compensatedYaw = (float) (Math.toDegrees(Math.atan2(compensated.z, compensated.x)) - 90.0);

        Rotation gcdWaterRot = applyGrimGcd(player.getYRot(), player.getXRot(), compensatedYaw, 0.0F);
        proc.getBaritone().getLookBehavior().updateTarget(gcdWaterRot, false);
        proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, false);
        proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, false);

        double fluidHeight = player.getFluidHeight(FluidTags.WATER);
        boolean needAscend = targetCenter.y > player.getY() + 0.2D
                || player.isUnderWater()
                || fluidHeight > 0.35D;

        if (needAscend) {
            proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.JUMP, true);
        } else {
            proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.JUMP, false);
        }
    }

    public static boolean shouldEarlyJump(LocalPlayer player, Level level, Vec3 targetCenter, Direction dir) {
        if (!player.onGround())
            return false;
        if (targetCenter.y <= player.getY() + 0.35D)
            return false;

        Vec3 lookAhead = player.position().add(dir.getStepX() * 0.45D, 0.0D, dir.getStepZ() * 0.45D);
        BlockPos aheadPos = BlockPos.containing(lookAhead.x, player.getY(), lookAhead.z);

        boolean stepSolid = !level.getBlockState(aheadPos).getCollisionShape(level, aheadPos).isEmpty();
        boolean headRoomFree = isPassable(level, aheadPos.above())
                && isPassable(level, aheadPos.above(2))
                && isPassable(level, player.blockPosition().above(2));

        return stepSolid && headRoomFree;
    }

    public static void updateMovementStuckWatchdog(BypassProcess proc, LocalPlayer player, Level level, Direction dir) {
        if (player.position().distanceToSqr(proc.getLastTrackedMovementPos()) < 0.0016D) {
            proc.setMovementStuckTicks(proc.getMovementStuckTicks() + 1);
        } else {
            proc.setMovementStuckTicks(0);
            proc.setLastTrackedMovementPos(player.position());
        }

        if (proc.getMovementStuckTicks() >= 20) {
            BlockPos ceiling = player.blockPosition().above(2);
            if (!level.getBlockState(ceiling).isAir() && !isBedrockOrUnbreakable(level, ceiling)) {
                proc.mineBlockSafely(player, level, ceiling);
            }
            proc.setMovementStuckTicks(0);
        }
    }
}
