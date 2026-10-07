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

package baritone.pathing.movement.movements;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.behavior.WaterClutchBehavior;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.VecUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.pathing.movement.MovementState.MovementTarget;
import baritone.utils.pathing.MutableMoveResult;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.WaterFluid;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public class MovementFall extends Movement {

    private static final ItemStack STACK_BUCKET_WATER = new ItemStack(Items.WATER_BUCKET);
    private static final ItemStack STACK_BUCKET_EMPTY = new ItemStack(Items.BUCKET);

    public MovementFall(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest) {
        super(baritone, src, dest, MovementFall.buildPositionsToBreak(src, dest));
    }

    @Override
    public double calculateCost(CalculationContext context) {
        MutableMoveResult result = new MutableMoveResult();
        MovementDescend.cost(context, src.x, src.y, src.z, dest.x, dest.z, result);
        if (result.y != dest.y) {
            return COST_INF; // doesn't apply to us, this position is a descend not a fall
        }
        return result.cost;
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        Set<BetterBlockPos> set = new HashSet<>();
        set.add(src);
        for (int y = src.y - dest.y; y >= 0; y--) {
            set.add(dest.above(y));
        }
        return set;
    }

    private boolean willPlaceBucket() {
        CalculationContext context = new CalculationContext(baritone);
        MutableMoveResult result = new MutableMoveResult();
        return MovementDescend.dynamicFallCost(context, src.x, src.y, src.z, dest.x, dest.z, 0, context.get(dest.x, src.y - 2, dest.z), result);
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }

        BlockPos playerFeet = ctx.playerFeet();
        Rotation toDest = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.getBlockPosCenter(dest), ctx.playerRotations());
        Rotation targetRotation = null;
        BlockState destState = ctx.world().getBlockState(dest);
        Block destBlock = destState.getBlock();

        if (ctx.world().getBlockState(dest.below()).is(Blocks.MAGMA_BLOCK) && MovementHelper.steppingOnBlocks(ctx).stream().allMatch(block -> MovementHelper.canWalkThrough(ctx, block))) {
            state.setInput(Input.SNEAK, true);
        }

        boolean isWater = destState.getFluidState().getType() instanceof WaterFluid;
        if (!isWater && willPlaceBucket() && !playerFeet.equals(dest)) {
            int bucketSlot = ctx.player().getInventory().findSlotMatchingItem(STACK_BUCKET_WATER);
            if (!Inventory.isHotbarSlot(bucketSlot)) {
                // Sprawdź czy wiadro jest w głównym EQ i przenieś do hotbara
                int invSlot = WaterClutchBehavior.findWaterBucketSlot(ctx.player());
                if (invSlot != -1 && invSlot >= 9) {
                    try {
                        ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, invSlot, ctx.player().getInventory().selected, ClickType.SWAP, ctx.player());
                        bucketSlot = ctx.player().getInventory().selected;
                    } catch (Throwable ignored) {}
                }
            }

            if (bucketSlot == -1 || ctx.world().dimension() == Level.NETHER) {
                return state.setStatus(MovementStatus.UNREACHABLE);
            }

            // PRE-AIM: Natychmiast celuj prosto w dół w miejsce lądowania (Pitch = 90.0F)!
            targetRotation = new Rotation(toDest.getYaw(), 90.0F);

            double distToLanding = ctx.player().position().y - dest.getY();
            if (distToLanding < 3.8D && !ctx.player().onGround()) {
                if (Inventory.isHotbarSlot(bucketSlot)) {
                    ctx.player().getInventory().selected = bucketSlot;
                    ctx.playerController().syncHeldItem();
                }

                state.setInput(Input.CLICK_RIGHT, true);
                BlockHitResult hit = new BlockHitResult(
                        new Vec3(dest.getX() + 0.5D, dest.getY(), dest.getZ() + 0.5D),
                        Direction.UP,
                        dest.below(),
                        false
                );
                net.minecraft.world.InteractionResult res = ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, hit);
                if (!res.consumesAction()) {
                    ctx.playerController().processRightClick(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND);
                }
                ctx.player().swing(InteractionHand.MAIN_HAND);
            }
        }
        if (targetRotation != null) {
            state.setTarget(new MovementTarget(targetRotation, true));
        } else {
            state.setTarget(new MovementTarget(toDest, false));
        }
        if ((playerFeet.equals(dest) || (isWater && (playerFeet.equals(dest.above()) || (playerFeet.getX() == dest.getX() && playerFeet.getZ() == dest.getZ() && Math.abs(ctx.player().position().y - dest.getY()) < 1.8)))) && (ctx.player().position().y - playerFeet.getY() < 0.094 || isWater)) { // 0.094 because lilypads
            if (isWater) { // only match water, not flowing water (which we cannot pick up with a bucket)
                state.setInput(Input.JUMP, true); // Swim up immediately in water
                int emptyBucketSlot = ctx.player().getInventory().findSlotMatchingItem(STACK_BUCKET_EMPTY);
                if (emptyBucketSlot != -1 && !Inventory.isHotbarSlot(emptyBucketSlot)) {
                    // Przenieś puste wiadro z głównego EQ do wybranego slotu hotbara
                    try {
                        ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, emptyBucketSlot, ctx.player().getInventory().selected, ClickType.SWAP, ctx.player());
                        emptyBucketSlot = ctx.player().getInventory().selected;
                    } catch (Throwable ignored) {}
                }
                if (Inventory.isHotbarSlot(emptyBucketSlot)) {
                    ctx.player().getInventory().selected = emptyBucketSlot;
                    ctx.playerController().syncHeldItem();

                    Rotation downRot = new Rotation(ctx.playerRotations().getYaw(), 89.5F);
                    state.setTarget(new MovementTarget(downRot, true));

                    BlockHitResult hit = new BlockHitResult(
                            new Vec3(dest.getX() + 0.5D, dest.getY() + 0.5D, dest.getZ() + 0.5D),
                            Direction.UP,
                            dest,
                            false
                    );
                    ctx.playerController().processRightClick(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND);
                    ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, hit);
                    ctx.player().swing(InteractionHand.MAIN_HAND);

                    // Poczekaj aż woda faktycznie zniknie zanim zakończysz ruch
                    boolean waterStillThere = MovementHelper.isWater(ctx.world().getBlockState(dest));
                    if (!waterStillThere || ctx.player().getInventory().contains(STACK_BUCKET_WATER)) {
                        return state.setStatus(MovementStatus.SUCCESS);
                    }
                    return state; // Kontynuuj zbieranie wody
                } else {
                    if (ctx.player().getDeltaMovement().y >= 0 || ctx.player().position().y >= dest.getY()) {
                        return state.setStatus(MovementStatus.SUCCESS);
                    }
                }
            } else {
                return state.setStatus(MovementStatus.SUCCESS);
            }
        }
        Vec3 destCenter = VecUtils.getBlockPosCenter(dest); // we are moving to the 0.5 center not the edge (like if we were falling on a ladder)
        if (Math.abs(ctx.player().position().x + ctx.player().getDeltaMovement().x - destCenter.x) > 0.1 || Math.abs(ctx.player().position().z + ctx.player().getDeltaMovement().z - destCenter.z) > 0.1) {
            if (!ctx.player().onGround() && Math.abs(ctx.player().getDeltaMovement().y) > 0.4) {
                state.setInput(Input.SNEAK, true);
            }
            state.setInput(Input.MOVE_FORWARD, true);
        }
        Vec3i avoid = Optional.ofNullable(avoid()).map(Direction::getUnitVec3i).orElse(null);
        if (avoid == null) {
            avoid = src.subtract(dest);
        } else {
            double dist = Math.abs(avoid.getX() * (destCenter.x - avoid.getX() / 2.0 - ctx.player().position().x)) + Math.abs(avoid.getZ() * (destCenter.z - avoid.getZ() / 2.0 - ctx.player().position().z));
            if (dist < 0.6) {
                state.setInput(Input.MOVE_FORWARD, true);
            } else if (!ctx.player().onGround()) {
                state.setInput(Input.SNEAK, false);
            }
        }
        if (targetRotation == null) {
            Vec3 destCenterOffset = new Vec3(destCenter.x + 0.125 * avoid.getX(), destCenter.y, destCenter.z + 0.125 * avoid.getZ());
            state.setTarget(new MovementTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), destCenterOffset, ctx.playerRotations()), false));
        }
        return state;
    }

    private Direction avoid() {
        for (int i = 0; i < 15; i++) {
            BlockState state = ctx.world().getBlockState(ctx.playerFeet().below(i));
            if (state.getBlock() == Blocks.LADDER) {
                return state.getValue(LadderBlock.FACING);
            }
        }
        return null;
    }

    @Override
    public boolean safeToCancel(MovementState state) {
        // if we haven't started walking off the edge yet, or if we're in the process of breaking blocks before doing the fall
        // then it's safe to cancel this
        return ctx.playerFeet().equals(src) || state.getStatus() != MovementStatus.RUNNING;
    }

    private static BetterBlockPos[] buildPositionsToBreak(BetterBlockPos src, BetterBlockPos dest) {
        BetterBlockPos[] toBreak;
        int diffX = src.getX() - dest.getX();
        int diffZ = src.getZ() - dest.getZ();
        int diffY = Math.abs(src.getY() - dest.getY());
        toBreak = new BetterBlockPos[diffY + 2];
        for (int i = 0; i < toBreak.length; i++) {
            toBreak[i] = new BetterBlockPos(src.getX() - diffX, src.getY() + 1 - i, src.getZ() - diffZ);
        }
        return toBreak;
    }

    @Override
    protected boolean prepared(MovementState state) {
        if (state.getStatus() == MovementStatus.WAITING) {
            return true;
        }
        // only break if one of the first three needs to be broken
        // specifically ignore the last one which might be water
        for (int i = 0; i < 4 && i < positionsToBreak.length; i++) {
            if (!MovementHelper.canWalkThrough(ctx, positionsToBreak[i])) {
                return super.prepared(state);
            }
        }
        return true;
    }
}
