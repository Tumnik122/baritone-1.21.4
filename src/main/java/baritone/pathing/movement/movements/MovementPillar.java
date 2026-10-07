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
import baritone.utils.BlockStateInterface;
import com.google.common.collect.ImmutableSet;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

public class MovementPillar extends Movement {

    private int pillarJumpTicks = 0;

    public MovementPillar(IBaritone baritone, BetterBlockPos start, BetterBlockPos end) {
        super(baritone, start, end, new BetterBlockPos[]{start.above(2)}, start);
    }

    @Override
    public void reset() {
        super.reset();
        pillarJumpTicks = 0;
    }

    @Override
    public double calculateCost(CalculationContext context) {
        return cost(context, src.x, src.y, src.z);
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        return ImmutableSet.of(src, dest);
    }

    public static double cost(CalculationContext context, int x, int y, int z) {
        BlockState fromState = context.get(x, y, z);
        Block from = fromState.getBlock();
        boolean ladder = MovementHelper.isClimbable(from);
        BlockState fromDown = context.get(x, y - 1, z);
        if (!ladder) {
            if (MovementHelper.isClimbable(fromDown.getBlock())) {
                return COST_INF; // can't pillar from a ladder or vine onto something that isn't also climbable
            }
            if (fromDown.getBlock() instanceof SlabBlock && fromDown.getValue(SlabBlock.TYPE) == SlabType.BOTTOM) {
                return COST_INF; // can't pillar up from a bottom slab onto a non ladder
            }
        }
        BlockState toBreak = context.get(x, y + 2, z);
        Block toBreakBlock = toBreak.getBlock();
        if (toBreakBlock instanceof FenceGateBlock) { // see issue #172
            return COST_INF;
        }
        BlockState srcUp = null;
        if (MovementHelper.isWater(toBreak) && MovementHelper.isWater(fromState)) { // TODO should this also be allowed if toBreakBlock is air?
            srcUp = context.get(x, y + 1, z);
            if (MovementHelper.isWater(srcUp)) {
                return LADDER_UP_ONE_COST; // allow ascending pillars of water, but only if we're already in one
            }
        }
        double placeCost = 0;
        if (!ladder) {
            // Jeśli bot ścina drzewa (#mine log), całkowity ZAKAZ stawiania klocków pod sobą w powietrzu!
            // Chroni przed budowaniem wież z ziemi w korony drzew i zacinaniem się bota.
            if (context.getBaritone() != null && context.getBaritone().getMineProcess() != null && context.getBaritone().getMineProcess().isCuttingLogs()) {
                return COST_INF;
            }
            // we need to place a block where we started to jump on it
            placeCost = context.costOfPlacingAt(x, y, z, fromState);
            if (placeCost >= COST_INF) {
                return COST_INF;
            }
            if (fromDown.getBlock() instanceof AirBlock) {
                placeCost += 0.1; // slightly (1/200th of a second) penalize pillaring on what's currently air
            }
        }
        if ((MovementHelper.isLiquid(fromState) && !MovementHelper.canPlaceAgainst(context.bsi, x, y - 1, z, fromDown)) || (MovementHelper.isLiquid(fromDown) && context.assumeWalkOnWater)) {
            // otherwise, if we're standing in water, we cannot pillar
            // if we're standing on water and assumeWalkOnWater is true, we cannot pillar
            // if we're standing on water and assumeWalkOnWater is false, we must have ascended to here, or sneak backplaced, so it is possible to pillar again
            return COST_INF;
        }
        if ((from == Blocks.LILY_PAD || from instanceof CarpetBlock) && !fromDown.getFluidState().isEmpty()) {
            // to ascend here we'd have to break the block we are standing on
            return COST_INF;
        }
        double hardness = MovementHelper.getMiningDurationTicks(context, x, y + 2, z, toBreak, true);
        if (hardness >= COST_INF) {
            return COST_INF;
        }
        if (hardness != 0) {
            if (MovementHelper.isClimbable(toBreakBlock)) {
                hardness = 0; // we won't actually need to break the ladder / vine because we're going to use it
            } else {
                BlockState check = context.get(x, y + 3, z); // the block on top of the one we're going to break, could it fall on us?
                if (check.getBlock() instanceof FallingBlock) {
                    // see MovementAscend's identical check for breaking a falling block above our head
                    if (srcUp == null) {
                        srcUp = context.get(x, y + 1, z);
                    }
                    if (!(toBreakBlock instanceof FallingBlock) || !(srcUp.getBlock() instanceof FallingBlock)) {
                        return COST_INF;
                    }
                }
                // this is commented because it may have had a purpose, but it's very unclear what it was. it's from the minebot era.
                //if (!MovementHelper.canWalkOn(context, chkPos, check) || MovementHelper.canWalkThrough(context, chkPos, check)) {//if the block above where we want to break is not a full block, don't do it
                // TODO why does canWalkThrough mean this action is COST_INF?
                // FallingBlock makes sense, and !canWalkOn deals with weird cases like if it were lava
                // but I don't understand why canWalkThrough makes it impossible
                //    return COST_INF;
                //}
            }
        }
        double total;
        if (ladder) {
            total = LADDER_UP_ONE_COST + hardness * 5;
        } else {
            total = JUMP_ONE_BLOCK_COST + placeCost + context.jumpPenalty + hardness;
        }
        if (context.avoidFluidProximity) {
            total += MovementHelper.getFluidProximityPenalty(context, x, y + 1, z);
        }
        return total;
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }

        if (ctx.playerFeet().y < src.y) {
            return state.setStatus(MovementStatus.UNREACHABLE);
        }

        BlockState fromDown = BlockStateInterface.get(ctx, src);
        if (MovementHelper.isWater(fromDown) && MovementHelper.isWater(ctx, dest)) {
            // stay centered while swimming up a water column
            state.setTarget(new MovementState.MovementTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.getBlockPosCenter(dest), ctx.playerRotations()), false));
            Vec3 destCenter = VecUtils.getBlockPosCenter(dest);
            if (Math.abs(ctx.player().position().x - destCenter.x) > 0.2 || Math.abs(ctx.player().position().z - destCenter.z) > 0.2) {
                state.setInput(Input.MOVE_FORWARD, true);
            }
            if (ctx.playerFeet().equals(dest)) {
                return state.setStatus(MovementStatus.SUCCESS);
            }
            return state;
        }
        boolean ladder = MovementHelper.isClimbable(fromDown.getBlock());

        // Podsadzanie pod siebie: celuj DOKŁADNIE w swoje nogi/stopy (Pitch = 90.0F)
        // Zachowaj obecny Yaw gracza, aby głowa nie skręcała gwałtownie w prawo/lewo ani w sufit!
        Rotation downRotation = new Rotation(ctx.playerRotations().getYaw(), 90.0F);
        if (!ladder) {
            state.setTarget(new MovementState.MovementTarget(downRotation, true));
        }

        boolean blockIsThere = MovementHelper.canWalkOn(ctx, src) || ladder;
        if (ladder) {
            if (ctx.playerFeet().equals(dest)) {
                return state.setStatus(MovementStatus.SUCCESS);
            }

            MovementHelper.moveTowards(ctx, state, dest);
            state.setInput(Input.JUMP, true);
            return state;
        } else {
            // Przygotuj blok do podsadzenia
            if (!((Baritone) baritone).getInventoryBehavior().selectThrowawayForLocation(true, src.x, src.y, src.z)) {
                return state.setStatus(MovementStatus.UNREACHABLE);
            }

            // Centrujemy gracza na środku bloku
            double diffX = (dest.getX() + 0.5) - ctx.player().position().x;
            double diffZ = (dest.getZ() + 0.5) - ctx.player().position().z;
            double dist = Math.sqrt(diffX * diffX + diffZ * diffZ);

            if (dist > 0.15) {
                MovementHelper.moveTowards(ctx, state, dest);
            }

            // Skaczemy pionowo w górę - bez niepotrzebnego croucha!
            state.setInput(Input.JUMP, ctx.player().position().y < dest.getY() + 0.15);

            if (!blockIsThere) {
                pillarJumpTicks++;
                if (pillarJumpTicks > 30) {
                    return state.setStatus(MovementStatus.FAILED);
                }
                BlockState frState = BlockStateInterface.get(ctx, src);
                Block fr = frState.getBlock();
                if (!(fr instanceof AirBlock || frState.canBeReplaced())) {
                    RotationUtils.reachable(ctx, src, ctx.playerController().getBlockReachDistance())
                            .map(rot -> new MovementState.MovementTarget(rot, true))
                            .ifPresent(state::setTarget);
                    state.setInput(Input.JUMP, false);
                    state.setInput(Input.CLICK_LEFT, true);
                    blockIsThere = false;
                } else {
                    // Skieruj wzrok prosto w dół pod stopy gracza w trakcie skoku (GrimAC-safe)
                    state.setTarget(new MovementState.MovementTarget(new Rotation(ctx.playerRotations().getYaw(), 89.5F), true));

                    if (ctx.player().position().y >= src.getY() + 1.05) {
                        // Kiedy stopy gracza są ponad poziomem docelowego bloku, postaw blok pod sobą
                        state.setInput(Input.CLICK_RIGHT, true);
                        BlockHitResult hit = new BlockHitResult(
                                new Vec3(src.getX() + 0.5, src.getY(), src.getZ() + 0.5),
                                Direction.UP,
                                src.below(),
                                false
                        );
                        ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, hit);
                    }
                }
            }
        }

        // If we are at our goal and the block below us is placed
        if (ctx.playerFeet().equals(dest) && blockIsThere) {
            return state.setStatus(MovementStatus.SUCCESS);
        }

        return state;
    }

    @Override
    protected boolean prepared(MovementState state) {
        if (ctx.playerFeet().equals(src) || ctx.playerFeet().equals(src.below())) {
            Block block = BlockStateInterface.getBlock(ctx, src.below());
            if (MovementHelper.isClimbable(block)) {
                state.setInput(Input.SNEAK, true);
            }
        }
        if (MovementHelper.isWater(ctx, dest.above())) {
            return true;
        }
        return super.prepared(state);
    }
}
