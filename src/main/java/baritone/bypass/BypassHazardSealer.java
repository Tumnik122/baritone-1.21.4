package baritone.bypass;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Moduł uszczelniania wycieków wody/lawy oraz budowy mostków podłogowych (Argon 4 & dj).
 */
public final class BypassHazardSealer {

    private BypassHazardSealer() {}

    public static final int MAX_SEAL_ATTEMPTS = 5;

    public record PlacementCandidate(
            BlockPos supportPos,
            Direction placeFace,
            Vec3 hitVec,
            Rotation rotation) {
    }

    public static BlockPos findWaterBreachSource(BypassProcess proc, Level level, BlockPos center) {
        if (level == null || center == null) return null;
        FluidState headFluid = level.getFluidState(center.above());

        if (!headFluid.isEmpty() && (headFluid.is(FluidTags.WATER) || headFluid.is(FluidTags.LAVA))) {
            for (BlockPos pos : BlockPos.betweenClosed(center, center.above(2))) {
                if (level.getFluidState(pos).isSource()) {
                    return pos.immutable();
                }
            }
            return center.above().immutable();
        }

        BlockPos frontFeet = center.relative(proc.getTunnelDir());
        BlockPos frontHead = frontFeet.above();

        FluidState frontHeadFluid = level.getFluidState(frontHead);
        FluidState frontFeetFluid = level.getFluidState(frontFeet);

        if (!frontHeadFluid.isEmpty() && (frontHeadFluid.is(FluidTags.LAVA) || frontHeadFluid.isSource())) {
            return frontHead.immutable();
        }
        if (!frontFeetFluid.isEmpty() && (frontFeetFluid.is(FluidTags.LAVA) || frontFeetFluid.isSource())) {
            return frontFeet.immutable();
        }

        return null;
    }

    public static BlockPos findExposedLavaBreach(Level level, BlockPos feet, Direction forward) {
        if (level == null || feet == null || forward == null) return null;
        BlockPos head = feet.above();
        BlockPos frontFeet = feet.relative(forward);
        BlockPos frontHead = frontFeet.above();

        List<BlockPos> corridorCells = new ArrayList<>(4);
        corridorCells.add(feet);
        corridorCells.add(head);
        if (BypassNavigator.isPassable(level, frontFeet)) corridorCells.add(frontFeet);
        if (BypassNavigator.isPassable(level, frontHead)) corridorCells.add(frontHead);

        for (BlockPos cell : corridorCells) {
            if (BypassLavaRadar.isLavaFluid(level, cell) && level.getBlockState(cell).canBeReplaced()) {
                return cell.immutable();
            }
            for (Direction dir : Direction.values()) {
                BlockPos adj = cell.relative(dir);
                if (BypassLavaRadar.isLavaFluid(level, adj) && level.getBlockState(adj).canBeReplaced()
                        && hasSolidNeighborForPlacement(level, adj)) {
                    return adj.immutable();
                }
            }
        }
        return null;
    }

    public static PathingCommand handleSealingHazard(BypassProcess proc, LocalPlayer player, Level level) {
        proc.stopMovementInputs();
        proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);

        BlockPos feet = player.blockPosition();
        BlockPos hazardPos = proc.getHazardSourcePos();

        if (hazardPos == null
                || !level.getBlockState(hazardPos).canBeReplaced()
                || proc.getSealFailedAttempts() >= MAX_SEAL_ATTEMPTS) {
            boolean stillHazardous = hazardPos != null && BypassLavaRadar.isLavaFluid(level, hazardPos);
            proc.setHazardSourcePos(null);
            proc.setSealFailedAttempts(0);
            proc.setState(BypassProcess.State.TUNNELING);

            if (stillHazardous) {
                return BypassNavigator.handleLavaOrObstacleEncounter(proc, player, level, feet);
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (player.getBoundingBox().intersects(new AABB(hazardPos))) {
            proc.setHazardSourcePos(null);
            return BypassNavigator.executeRetreatBackward(proc, player, level, feet,
                    proc.getPrimaryMiningDir() != null ? proc.getPrimaryMiningDir() : Direction.SOUTH);
        }

        if (!selectThrowawayBlock(proc, player, hazardPos)) {
            proc.setHazardSourcePos(null);
            proc.setState(BypassProcess.State.TUNNELING);
            return BypassNavigator.handleLavaOrObstacleEncounter(proc, player, level, feet);
        }

        if (proc.getPlaceCooldownTicks() > 0) {
            proc.setPlaceCooldownTicks(proc.getPlaceCooldownTicks() - 1);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        double reach = proc.getEffectiveReach();
        Optional<PlacementCandidate> candidateOpt = findBestPlacementCandidate(player, level, hazardPos, reach);

        if (candidateOpt.isEmpty()) {
            proc.setSealFailedAttempts(proc.getSealFailedAttempts() + 1);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        PlacementCandidate candidate = candidateOpt.get();
        proc.getBaritone().getLookBehavior().updateTarget(candidate.rotation(), true);

        Vec3 eyePos = player.getEyePosition(1.0F);
        Vec3 currentLook = RotationUtils.calcLookDirectionFromRotation(proc.getPlayerContext().playerRotations());
        BlockHitResult liveHit = level.clip(new ClipContext(
                eyePos,
                eyePos.add(currentLook.scale(reach)),
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player));

        float yawDelta = Math.abs(Mth.wrapDegrees(player.getYRot() - candidate.rotation().getYaw()));
        float pitchDelta = Math.abs(player.getXRot() - candidate.rotation().getPitch());

        boolean exactMatch = liveHit.getType() == HitResult.Type.BLOCK
                && liveHit.getBlockPos().equals(candidate.supportPos())
                && liveHit.getDirection() == candidate.placeFace();

        if (exactMatch && yawDelta <= 8.0F && pitchDelta <= 8.0F && isHoldingValidPlaceableBlock(player)) {
            proc.getPlayerContext().playerController().processRightClickBlock(player, proc.getPlayerContext().world(), InteractionHand.MAIN_HAND, liveHit);
            player.swing(InteractionHand.MAIN_HAND);
            proc.setPlaceCooldownTicks(3);
            proc.setSealFailedAttempts(0);
            proc.setSealCooldownTicks(3);
        } else {
            proc.setSealFailedAttempts(proc.getSealFailedAttempts() + 1);
        }

        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    public static boolean tryBridgeFloor(BypassProcess proc, LocalPlayer player, Level level, BlockPos floorPos, BlockPos defaultSupport) {
        if (!level.getBlockState(floorPos).canBeReplaced()) {
            return false;
        }

        proc.stopMovementInputs();
        proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
        proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
        proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.SNEAK, true);

        AABB targetBlockBox = new AABB(floorPos);
        if (player.getBoundingBox().inflate(-1.0E-4).intersects(targetBlockBox)) {
            if (defaultSupport != null && hasStableFloorSupport(level, defaultSupport)) {
                stepTowardsPointWithSneak(proc, player, Vec3.atBottomCenterOf(defaultSupport.above()));
                return true;
            }
            return false;
        }

        if (!selectThrowawayBlock(proc, player, floorPos)) {
            return false;
        }

        if (proc.getPlaceCooldownTicks() > 0) {
            proc.setPlaceCooldownTicks(proc.getPlaceCooldownTicks() - 1);
            return true;
        }

        double reach = proc.getEffectiveReach();
        Optional<PlacementCandidate> candidateOpt = findBestPlacementCandidate(player, level, floorPos, reach);

        if (candidateOpt.isPresent()) {
            PlacementCandidate candidate = candidateOpt.get();
            proc.getBaritone().getLookBehavior().updateTarget(candidate.rotation(), true);

            Vec3 eyePos = player.getEyePosition(1.0F);
            Vec3 currentLook = RotationUtils.calcLookDirectionFromRotation(proc.getPlayerContext().playerRotations());
            BlockHitResult liveHit = level.clip(new ClipContext(
                    eyePos,
                    eyePos.add(currentLook.scale(reach)),
                    ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE,
                    player));

            float yawDelta = Math.abs(Mth.wrapDegrees(player.getYRot() - candidate.rotation().getYaw()));
            float pitchDelta = Math.abs(player.getXRot() - candidate.rotation().getPitch());

            boolean exactMatch = liveHit.getType() == HitResult.Type.BLOCK
                    && liveHit.getBlockPos().equals(candidate.supportPos())
                    && liveHit.getDirection() == candidate.placeFace();

            if (exactMatch && yawDelta <= 8.0f && pitchDelta <= 8.0f && isHoldingValidPlaceableBlock(player)) {
                proc.getPlayerContext().playerController().processRightClickBlock(player, proc.getPlayerContext().world(), InteractionHand.MAIN_HAND, liveHit);
                player.swing(InteractionHand.MAIN_HAND);
                proc.setPlaceCooldownTicks(4);
                proc.setSealCooldownTicks(4);
            }
            return true;
        }

        BlockPos supportBase = (defaultSupport != null && hasStableFloorSupport(level, defaultSupport))
                ? defaultSupport
                : player.blockPosition().below();

        if (!hasStableFloorSupport(level, supportBase)) {
            return false;
        }

        double dirX = Math.clamp(floorPos.getX() - supportBase.getX(), -1, 1);
        double dirZ = Math.clamp(floorPos.getZ() - supportBase.getZ(), -1, 1);
        if (dirX == 0 && dirZ == 0) {
            return false;
        }

        Vec3 safeEdgePos = new Vec3(
                supportBase.getX() + 0.5 + dirX * 0.44,
                supportBase.getY() + 1.0,
                supportBase.getZ() + 0.5 + dirZ * 0.44);

        Rotation lookDownAtGap = BypassNavigator.calcRotationFromVec3d(
                player.getEyePosition(1.0F),
                new Vec3(floorPos.getX() + 0.5, floorPos.getY() + 0.85, floorPos.getZ() + 0.5),
                player.getYRot());
        Rotation gcdLook = BypassNavigator.applyGrimGcd(player.getYRot(), player.getXRot(), lookDownAtGap.getYaw(),
                lookDownAtGap.getPitch());
        proc.getBaritone().getLookBehavior().updateTarget(gcdLook, true);

        double distSqToEdge = (player.getX() - safeEdgePos.x) * (player.getX() - safeEdgePos.x)
                + (player.getZ() - safeEdgePos.z) * (player.getZ() - safeEdgePos.z);

        if (distSqToEdge > 0.008) {
            stepTowardsPointWithSneak(proc, player, safeEdgePos);
            return true;
        }

        return false;
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

    public static boolean hasStableFloorSupport(Level level, BlockPos floorPos) {
        if (level == null || floorPos == null) return false;
        BlockState state = level.getBlockState(floorPos);
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        if (state.getBlock() instanceof FallingBlock) {
            BlockState belowState = level.getBlockState(floorPos.below());
            return !FallingBlock.isFree(belowState);
        }
        if (state.isFaceSturdy(level, floorPos, Direction.UP)) {
            return true;
        }
        if (state.isAir() || state.canBeReplaced()) {
            BlockState below = level.getBlockState(floorPos.below());
            if (!below.getFluidState().isEmpty()) {
                return false;
            }
            return below.isFaceSturdy(level, floorPos.below(), Direction.UP);
        }
        return false;
    }

    public static boolean hasSolidNeighborForPlacement(Level level, BlockPos targetPos) {
        if (level == null || targetPos == null) return false;
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = targetPos.relative(dir);
            BlockState s = level.getBlockState(neighbor);
            if (!s.canBeReplaced() && s.getFluidState().isEmpty() && !s.isAir()) {
                return true;
            }
        }
        return false;
    }

    public static Optional<PlacementCandidate> findBestPlacementCandidate(LocalPlayer player, Level level, BlockPos targetPos, double reach) {
        Vec3 eyePos = player.getEyePosition(1.0F);
        double[] grid = { 0.5D, 0.3D, 0.7D, 0.2D, 0.8D };

        PlacementCandidate best = null;
        double bestAngDist = Double.MAX_VALUE;

        for (Direction dir : Direction.values()) {
            BlockPos supportPos = targetPos.relative(dir);
            Direction placeFace = dir.getOpposite();

            BlockState supportState = level.getBlockState(supportPos);
            if (supportState.isAir() || supportState.canBeReplaced() || !supportState.getFluidState().isEmpty()) {
                continue;
            }

            for (double u : grid) {
                for (double v : grid) {
                    Vec3 hitVec = getFaceSamplePoint(supportPos, placeFace, u, v);
                    if (eyePos.distanceToSqr(hitVec) > reach * reach) {
                        continue;
                    }

                    Rotation rawRot = BypassNavigator.calcRotationFromVec3d(eyePos, hitVec, player.getYRot());
                    Rotation gcdRot = BypassNavigator.applyGrimGcd(player.getYRot(), player.getXRot(), rawRot.getYaw(), rawRot.getPitch());

                    Vec3 lookVec = Vec3.directionFromRotation(gcdRot.getPitch(), gcdRot.getYaw());
                    BlockHitResult clip = level.clip(new ClipContext(
                            eyePos,
                            eyePos.add(lookVec.scale(reach)),
                            ClipContext.Block.OUTLINE,
                            ClipContext.Fluid.NONE,
                            player));

                    if (clip.getType() == HitResult.Type.BLOCK
                            && clip.getBlockPos().equals(supportPos)
                            && clip.getDirection() == placeFace) {
                        double angDist = Math.hypot(
                                Mth.wrapDegrees(gcdRot.getYaw() - player.getYRot()),
                                gcdRot.getPitch() - player.getXRot());
                        if (angDist < bestAngDist) {
                            bestAngDist = angDist;
                            best = new PlacementCandidate(supportPos, placeFace, clip.getLocation(), gcdRot);
                        }
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static Vec3 getFaceSamplePoint(BlockPos pos, Direction face, double u, double v) {
        double x = pos.getX() + switch (face) {
            case WEST -> 0.001D;
            case EAST -> 0.999D;
            default -> u;
        };
        double y = pos.getY() + switch (face) {
            case DOWN -> 0.001D;
            case UP -> 0.999D;
            default -> (face.getAxis() == Direction.Axis.Y ? u : v);
        };
        double z = pos.getZ() + switch (face) {
            case NORTH -> 0.001D;
            case SOUTH -> 0.999D;
            default -> v;
        };
        return new Vec3(x, y, z);
    }

    public static boolean isValidPlaceableStack(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
            return false;
        }

        Block block = blockItem.getBlock();
        if (block instanceof FallingBlock
                || block instanceof EntityBlock
                || block instanceof SlimeBlock
                || block instanceof HoneyBlock
                || block instanceof SoulSandBlock
                || block instanceof MagmaBlock
                || block instanceof TntBlock
                || block instanceof InfestedBlock
                || block instanceof LeavesBlock) {
            return false;
        }

        if (BaritoneAPI.getSettings().acceptableThrowawayItems.value.contains(blockItem)) {
            return true;
        }

        return block == Blocks.COBBLESTONE
                || block == Blocks.COBBLED_DEEPSLATE
                || block == Blocks.DEEPSLATE
                || block == Blocks.STONE
                || block == Blocks.TUFF
                || block == Blocks.ANDESITE
                || block == Blocks.DIORITE
                || block == Blocks.GRANITE
                || block == Blocks.DIRT
                || block == Blocks.COARSE_DIRT
                || block == Blocks.ROOTED_DIRT
                || block == Blocks.NETHERRACK
                || block == Blocks.BLACKSTONE
                || block == Blocks.BASALT
                || block == Blocks.SMOOTH_BASALT
                || block == Blocks.CALCITE
                || block == Blocks.DRIPSTONE_BLOCK
                || block == Blocks.END_STONE;
    }

    public static boolean isHoldingValidPlaceableBlock(LocalPlayer player) {
        return isValidPlaceableStack(player.getMainHandItem());
    }

    public static int findValidBlockInRange(LocalPlayer player, int startInclusive, int endExclusive) {
        for (int i = startInclusive; i < endExclusive; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (isValidPlaceableStack(stack)) {
                return i;
            }
        }
        return -1;
    }

    public static boolean selectThrowawayBlock(BypassProcess proc, LocalPlayer player, BlockPos targetPos) {
        BlockPos lookupPos = targetPos != null ? targetPos
                : (proc.getHazardSourcePos() != null ? proc.getHazardSourcePos() : player.blockPosition());

        if (proc.getBaritone().getInventoryBehavior().selectThrowawayForLocation(true, lookupPos.getX(), lookupPos.getY(),
                lookupPos.getZ())) {
            if (proc.getPlayerContext().playerController() != null) {
                proc.getPlayerContext().playerController().syncHeldItem();
            }
            if (isHoldingValidPlaceableBlock(player)) {
                return true;
            }
        }

        int hotbarSlot = findValidBlockInRange(player, 0, 9);
        if (hotbarSlot != -1) {
            if (player.getInventory().selected != hotbarSlot) {
                player.getInventory().selected = hotbarSlot;
                if (proc.getPlayerContext().playerController() != null) {
                    proc.getPlayerContext().playerController().syncHeldItem();
                }
            }
            return true;
        }

        int invSlot = findValidBlockInRange(player, 9, 36);
        if (invSlot != -1 && proc.getPlayerContext().playerController() != null) {
            int targetHotbarSlot = 8;
            proc.getPlayerContext().playerController().windowClick(
                    player.inventoryMenu.containerId,
                    invSlot,
                    targetHotbarSlot,
                    ClickType.SWAP,
                    player);
            player.getInventory().selected = targetHotbarSlot;
            proc.getPlayerContext().playerController().syncHeldItem();
            return isHoldingValidPlaceableBlock(player);
        }

        return false;
    }
}

