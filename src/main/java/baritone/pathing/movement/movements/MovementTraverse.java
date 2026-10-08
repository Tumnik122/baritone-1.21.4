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
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.ObstructionHelper;
import baritone.api.utils.VecUtils;
import baritone.api.utils.input.Input;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.utils.BlockStateInterface;
import baritone.utils.ContinuousBreakController;
import baritone.utils.FastBreakHelper;
import com.google.common.collect.ImmutableSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.Set;

public class MovementTraverse extends Movement {

    /**
     * Did we have to place a bridge block or was it always there
     */
    private boolean wasTheBridgeBlockAlwaysThere = true;
    private int wrongYTicks = 0;

    public MovementTraverse(IBaritone baritone, BetterBlockPos from, BetterBlockPos to) {
        super(baritone, from, to, new BetterBlockPos[]{to.above(), to}, to.below());
    }

    @Override
    public void reset() {
        super.reset();
        wasTheBridgeBlockAlwaysThere = true;
        wrongYTicks = 0;
    }

    @Override
    public double calculateCost(CalculationContext context) {
        return cost(context, src.x, src.y, src.z, dest.x, dest.z);
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        return ImmutableSet.of(src, dest, src.above(), dest.above()); // src.above and dest.above mean that we don't get caught in an infinite loop in water
    }

    public static double cost(CalculationContext context, int x, int y, int z, int destX, int destZ) {
        BlockState pb0 = context.get(destX, y + 1, destZ);
        BlockState pb1 = context.get(destX, y, destZ);
        BlockState destOn = context.get(destX, y - 1, destZ);
        BlockState srcDown = context.get(x, y - 1, z);
        Block srcDownBlock = srcDown.getBlock();
        boolean standingOnABlock = MovementHelper.mustBeSolidToWalkOn(context, x, y - 1, z, srcDown);
        boolean frostWalker = standingOnABlock && !context.assumeWalkOnWater && MovementHelper.canUseFrostWalker(context, destOn);
        if (frostWalker || MovementHelper.canWalkOn(context, destX, y - 1, destZ, destOn)) { //this is a walk, not a bridge
            double WC = WALK_ONE_BLOCK_COST;
            boolean water = false;
            boolean sneaking = false;
            if (MovementHelper.isWater(pb0) || MovementHelper.isWater(pb1)) {
                if (context.mineAvoidWater) {
                    boolean inWater = MovementHelper.isWater(context.get(x, y, z));
                    if (!inWater) {
                        WC = context.waterWalkSpeed + Baritone.settings().waterAvoidPenalty.value * 2;
                    } else {
                        WC = context.waterWalkSpeed + Baritone.settings().waterAvoidPenalty.value;
                    }
                } else {
                    WC = context.waterWalkSpeed;
                }
                water = true;
            } else {
                if (destOn.getBlock() == Blocks.SOUL_SAND) {
                    WC += (WALK_ONE_OVER_SOUL_SAND_COST - WALK_ONE_BLOCK_COST) / 2;
                } else if (frostWalker) {
                    // with frostwalker we can walk on water without the penalty, if we are sure we won't be using jesus
                } else if (destOn.getBlock() == Blocks.WATER) {
                    if (context.mineAvoidWater) {
                        WC += context.walkOnWaterOnePenalty + Baritone.settings().waterAvoidPenalty.value;
                    } else {
                        WC += context.walkOnWaterOnePenalty;
                    }
                }
                if (srcDownBlock == Blocks.SOUL_SAND) {
                    WC += (WALK_ONE_OVER_SOUL_SAND_COST - WALK_ONE_BLOCK_COST) / 2;
                } else if (context.allowWalkOnMagmaBlocks && srcDownBlock.equals(Blocks.MAGMA_BLOCK)) {
                    sneaking = true;
                    WC += (SNEAK_ONE_BLOCK_COST - WALK_ONE_BLOCK_COST) / 2;
                }
            }
            double hardness1 = MovementHelper.getMiningDurationTicks(context, destX, y, destZ, pb1, false);
            if (hardness1 >= COST_INF) {
                return COST_INF;
            }
            double hardness2 = MovementHelper.getMiningDurationTicks(context, destX, y + 1, destZ, pb0, true); // only include falling on the upper block to break
            if (hardness1 == 0 && hardness2 == 0) {
                if (!water && !sneaking && context.canSprint) {
                    // If there's nothing in the way, and this isn't water, and we aren't sneak placing
                    // We can sprint =D
                    // Don't check for soul sand, since we can sprint on that too
                    WC *= SPRINT_MULTIPLIER;
                }
                if (context.avoidFluidProximity) {
                    WC += MovementHelper.getFluidProximityPenalty(context, destX, y, destZ);
                }
                return WC;
            }
            if (MovementHelper.isClimbable(srcDownBlock)) {
                hardness1 *= 5;
                hardness2 *= 5;
            }
            double totalWalk = WC + hardness1 + hardness2;
            if (context.avoidFluidProximity) {
                totalWalk += MovementHelper.getFluidProximityPenalty(context, destX, y, destZ);
            }
            return totalWalk;
        } else {//this is a bridge, so we need to place a block
            if (MovementHelper.isClimbable(srcDownBlock)) {
                return COST_INF;
            }
            if (Baritone.settings().mineAvoidLava.value && (MovementHelper.isLava(destOn) || MovementHelper.isLavaPitBelow(context.bsi, destX, y - 1, destZ))) {
                return COST_INF;
            }
            if (MovementHelper.isReplaceable(destX, y - 1, destZ, destOn, context.bsi)) {
                boolean throughWater = MovementHelper.isWater(pb0) || MovementHelper.isWater(pb1);
                if (MovementHelper.isWater(destOn) && throughWater && Baritone.settings().assumeWalkOnWater.value) {
                    return COST_INF;
                }
                double placeCost = context.costOfPlacingAt(destX, y - 1, destZ, destOn);
                if (placeCost >= COST_INF) {
                    return COST_INF;
                }
                double hardness1 = MovementHelper.getMiningDurationTicks(context, destX, y, destZ, pb1, false);
                if (hardness1 >= COST_INF) {
                    return COST_INF;
                }
                double hardness2 = MovementHelper.getMiningDurationTicks(context, destX, y + 1, destZ, pb0, true); // only include falling on the upper block to break
                double WC = throughWater ? context.waterWalkSpeed : WALK_ONE_BLOCK_COST;
                for (int i = 0; i < 5; i++) {
                    int againstX = destX + HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i].getStepX();
                    int againstY = y - 1 + HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i].getStepY();
                    int againstZ = destZ + HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i].getStepZ();
                    if (againstX == x && againstZ == z) { // this would be a backplace
                        continue;
                    }
                    if (MovementHelper.canPlaceAgainst(context.bsi, againstX, againstY, againstZ)) { // found a side place option
                        double totalBridge = WC + placeCost + hardness1 + hardness2;
                        if (context.avoidFluidProximity) {
                            totalBridge += MovementHelper.getFluidProximityPenalty(context, destX, y, destZ);
                        }
                        return totalBridge;
                    }
                }
                // now that we've checked all possible directions to side place, we actually need to backplace
                if (srcDownBlock == Blocks.SOUL_SAND || (srcDownBlock instanceof SlabBlock && srcDown.getValue(SlabBlock.TYPE) != SlabType.DOUBLE)) {
                    return COST_INF; // can't sneak and backplace against soul sand or half slabs (regardless of whether it's top half or bottom half) =/
                }
                if (!standingOnABlock) { // standing on water / swimming
                    return COST_INF; // this is obviously impossible
                }
                Block blockSrc = context.getBlock(x, y, z);
                if ((blockSrc == Blocks.LILY_PAD || blockSrc instanceof CarpetBlock) && !srcDown.getFluidState().isEmpty()) {
                    return COST_INF; // we can stand on these but can't place against them
                }
                WC = WC * (SNEAK_ONE_BLOCK_COST / WALK_ONE_BLOCK_COST);//since we are sneak backplacing, we are sneaking lol
                double totalBridge = WC + placeCost + hardness1 + hardness2;
                if (context.avoidFluidProximity) {
                    totalBridge += MovementHelper.getFluidProximityPenalty(context, destX, y, destZ);
                }
                return totalBridge;
            }
            return COST_INF;
        }
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        BlockState pb0 = BlockStateInterface.get(ctx, positionsToBreak[0]);
        BlockState pb1 = BlockStateInterface.get(ctx, positionsToBreak[1]);
        if (state.getStatus() != MovementStatus.RUNNING) {
            // if the setting is enabled
            if (!Baritone.settings().walkWhileBreaking.value) {
                return state;
            }
            // and if we're prepping (aka mining the block in front)
            if (state.getStatus() != MovementStatus.PREPPING) {
                return state;
            }
            // and if it's fine to walk into the blocks in front
            if (MovementHelper.avoidWalkingInto(pb0)) {
                return state;
            }
            if (MovementHelper.avoidWalkingInto(pb1)) {
                return state;
            }
            // and we aren't already pressed up against the block (chyba że szybki kilof - wtedy biegniemy ciągle)
            double dist = Math.max(Math.abs(ctx.player().position().x - (dest.getX() + 0.5D)), Math.abs(ctx.player().position().z - (dest.getZ() + 0.5D)));
            if (dist < 0.83 && !FastBreakHelper.isFastPickaxe(ctx)) {
                return state;
            }
            if (!state.getTarget().getRotation().isPresent() && !FastBreakHelper.isFastPickaxe(ctx)) {
                // this can happen rarely when the server lags and doesn't send the falling sand entity until you've already walked through the block and are now mining the next one
                return state;
            }

            // Keep the exact block-aiming rotation established by prepared(state).
            // Overriding pitch with 26 breaks line-of-sight and prevents CLICK_LEFT from triggering.
            state.setInput(Input.MOVE_FORWARD, true);
            if (FastBreakHelper.isFastPickaxe(ctx)) {
                state.setInput(Input.SPRINT, true);
            }
            return state;
        }

        Block fd = BlockStateInterface.get(ctx, src.below()).getBlock();
        boolean ladder = MovementHelper.isClimbable(fd);

        //sneak may have been set to true in the PREPPING state while mining an adjacent block, but we still want it to be true if the player is about to go on magma
        state.setInput(Input.SNEAK, Baritone.settings().allowWalkOnMagmaBlocks.value && MovementHelper.steppingOnBlocks(ctx).stream().anyMatch(block -> ctx.world().getBlockState(block).is(Blocks.MAGMA_BLOCK)));

        if (pb0.getBlock() instanceof DoorBlock || pb1.getBlock() instanceof DoorBlock) {
            boolean notPassable = pb0.getBlock() instanceof DoorBlock && !MovementHelper.isDoorPassable(ctx, src, dest) || pb1.getBlock() instanceof DoorBlock && !MovementHelper.isDoorPassable(ctx, dest, src);
            boolean canOpen = !(Blocks.IRON_DOOR.equals(pb0.getBlock()) || Blocks.IRON_DOOR.equals(pb1.getBlock()));

            if (notPassable && canOpen) {
                return state.setTarget(new MovementState.MovementTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.calculateBlockCenter(ctx.world(), positionsToBreak[0]), ctx.playerRotations()), true))
                        .setInput(Input.CLICK_RIGHT, true);
            }
        }

        if (pb0.getBlock() instanceof FenceGateBlock || pb1.getBlock() instanceof FenceGateBlock) {
            BlockPos blocked = !MovementHelper.isGatePassable(ctx, positionsToBreak[0], src.above()) ? positionsToBreak[0]
                    : !MovementHelper.isGatePassable(ctx, positionsToBreak[1], src) ? positionsToBreak[1]
                    : null;
            if (blocked != null) {
                Optional<Rotation> rotation = RotationUtils.reachable(ctx, blocked);
                if (rotation.isPresent()) {
                    return state.setTarget(new MovementState.MovementTarget(rotation.get(), true)).setInput(Input.CLICK_RIGHT, true);
                }
            }
        }

        boolean inWater = MovementHelper.isLiquid(ctx, dest) || MovementHelper.isLiquid(ctx, src) || MovementHelper.isLiquid(ctx, ctx.playerFeet()) || ctx.player().isInWater();
        boolean isTheBridgeBlockThere = inWater || MovementHelper.isLiquid(ctx, positionToPlace) || MovementHelper.canWalkOn(ctx, positionToPlace) || ladder || MovementHelper.canUseFrostWalker(ctx, positionToPlace);
        BetterBlockPos feet = ctx.playerFeet();

        // In water, swimming bot can be at dest or dest.above() (floating on surface)
        if (inWater) {
            if (feet.equals(dest) || feet.equals(dest.above()) || (feet.getX() == dest.getX() && feet.getZ() == dest.getZ() && Math.abs(ctx.player().position().y - dest.getY()) < 1.8)) {
                return state.setStatus(MovementStatus.SUCCESS);
            }
        }

        if (feet.getY() != dest.getY() && !ladder && !inWater) {
            if (MovementHelper.isLava(BlockStateInterface.get(ctx, feet)) || MovementHelper.isLava(BlockStateInterface.get(ctx, feet.below()))) {
                return state.setStatus(MovementStatus.FAILED);
            }
            logDebug("Wrong Y coordinate");
            if (feet.getY() < dest.getY()) {
                wrongYTicks++;
                if (wrongYTicks > 15) {
                    return state.setStatus(MovementStatus.FAILED);
                }
                logDebug("In movement traverse");
                MovementHelper.moveTowards(ctx, state, dest);
                if (MovementHelper.canWalkThrough(ctx, feet.above(2))) {
                    state.setInput(Input.JUMP, true);
                }
                return state;
            }
            MovementHelper.moveTowards(ctx, state, dest);
            return state;
        }

        if (isTheBridgeBlockThere) {
            if (feet.equals(dest) || (inWater && (feet.equals(dest.above()) || (feet.getX() == dest.getX() && feet.getZ() == dest.getZ() && Math.abs(ctx.player().position().y - dest.getY()) < 1.8)))) {
                return state.setStatus(MovementStatus.SUCCESS);
            }
            if (Baritone.settings().overshootTraverse.value && (feet.equals(dest.offset(getDirection())) || feet.equals(dest.offset(getDirection()).offset(getDirection())))) {
                return state.setStatus(MovementStatus.SUCCESS);
            }
            Block low = BlockStateInterface.get(ctx, src).getBlock();
            Block high = BlockStateInterface.get(ctx, src.above()).getBlock();
            if (ctx.player().position().y > src.y + 0.1D && !ctx.player().onGround() && (MovementHelper.isClimbable(low) || MovementHelper.isClimbable(high))) {
                // hitting W could cause us to climb the ladder instead of going forward
                // wait until we're on the ground
                return state;
            }
            BlockPos into = dest.subtract(src).offset(dest);
            BlockState intoBelow = BlockStateInterface.get(ctx, into);
            BlockState intoAbove = BlockStateInterface.get(ctx, into.above());
            if (!inWater && (FastBreakHelper.isFastPickaxe(ctx) || !hasUnbrokenPositionsToBreak()) && wasTheBridgeBlockAlwaysThere && (!MovementHelper.isLiquid(ctx, feet) || Baritone.settings().sprintInWater.value) && (!MovementHelper.avoidWalkingInto(intoBelow) || MovementHelper.isWater(intoBelow)) && !MovementHelper.avoidWalkingInto(intoAbove)) {
                state.setInput(Input.SPRINT, true);
            }

            BlockState destDown = BlockStateInterface.get(ctx, dest.below());
            if (feet.getY() != dest.getY() && ladder && MovementHelper.isClimbable(destDown.getBlock())) {
                state.setInput(Input.JUMP, true);
            }
            if (inWater && !MovementHelper.isLiquid(ctx, dest) && MovementHelper.canWalkOn(ctx, dest.below())) {
                // Stepping out of water onto land ledge requires jump in vanilla
                state.setInput(Input.JUMP, true);
            }
            MovementHelper.moveTowards(ctx, state, positionsToBreak[0]);
            return state;
        } else {
            wasTheBridgeBlockAlwaysThere = false;
            Block standingOn = BlockStateInterface.get(ctx, feet.below()).getBlock();
            if (standingOn.equals(Blocks.SOUL_SAND) || standingOn instanceof SlabBlock) { // see issue #118
                double dist = Math.max(Math.abs(dest.getX() + 0.5 - ctx.player().position().x), Math.abs(dest.getZ() + 0.5 - ctx.player().position().z));
                if (dist < 0.85) { // 0.5 + 0.3 + epsilon
                    MovementHelper.moveTowards(ctx, state, dest);
                    return state.setInput(Input.MOVE_FORWARD, false)
                            .setInput(Input.MOVE_BACK, true);
                }
            }
            double dist1 = Math.max(Math.abs(ctx.player().position().x - (dest.getX() + 0.5D)), Math.abs(ctx.player().position().z - (dest.getZ() + 0.5D)));
            PlaceResult p = MovementHelper.attemptToPlaceABlock(state, baritone, dest.below(), false, !Baritone.settings().assumeSafeWalk.value);
            if ((p == PlaceResult.READY_TO_PLACE || dist1 < 0.6) && !Baritone.settings().assumeSafeWalk.value) {
                state.setInput(Input.SNEAK, true);
            }
            switch (p) {
                case READY_TO_PLACE: {
                    if (ctx.player().isCrouching() || Baritone.settings().assumeSafeWalk.value) {
                        state.setInput(Input.CLICK_RIGHT, true);
                    }
                    return state;
                }
                case ATTEMPTING: {
                    if (dist1 > 0.83) {
                        // might need to go forward a bit
                        float yaw = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.getBlockPosCenter(dest), ctx.playerRotations()).getYaw();
                        if (Math.abs(state.getTarget().rotation.getYaw() - yaw) < 0.1) {
                            // but only if our attempted place is straight ahead
                            return state.setInput(Input.MOVE_FORWARD, true);
                        }
                    } else if (ctx.playerRotations().isReallyCloseTo(state.getTarget().rotation)) {
                        // well i guess theres something in the way
                        return state.setInput(Input.CLICK_LEFT, true);
                    }
                    return state;
                }
                default:
                    break;
            }
            if (feet.equals(dest)) {
                // If we are in the block that we are trying to get to, we are sneaking over air and we need to place a block beneath us against the one we just walked off of
                // Out.log(from + " " + to + " " + faceX + "," + faceY + "," + faceZ + " " + whereAmI);
                double faceX = (dest.getX() + src.getX() + 1.0D) * 0.5D;
                double faceY = (dest.getY() + src.getY() - 1.0D) * 0.5D;
                double faceZ = (dest.getZ() + src.getZ() + 1.0D) * 0.5D;
                // faceX, faceY, faceZ is the middle of the face between from and to
                BlockPos goalLook = src.below(); // this is the block we were just standing on, and the one we want to place against

                Rotation backToFace = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), new Vec3(faceX, faceY, faceZ), ctx.playerRotations());
                float pitch = backToFace.getPitch();
                double dist2 = Math.max(Math.abs(ctx.player().position().x - faceX), Math.abs(ctx.player().position().z - faceZ));
                if (dist2 < 0.29) { // see issue #208
                    float yaw = RotationUtils.calcRotationFromVec3d(VecUtils.getBlockPosCenter(dest), ctx.playerHead(), ctx.playerRotations()).getYaw();
                    state.setTarget(new MovementState.MovementTarget(new Rotation(yaw, pitch), true));
                    state.setInput(Input.MOVE_BACK, true);
                } else {
                    state.setTarget(new MovementState.MovementTarget(backToFace, true));
                }
                if (ctx.isLookingAt(goalLook)) {
                    return state.setInput(Input.CLICK_RIGHT, true); // wait to right click until we are able to place
                }
                // Out.log("Trying to look at " + goalLook + ", actually looking at" + Baritone.whatAreYouLookingAt());
                if (ctx.playerRotations().isReallyCloseTo(state.getTarget().rotation)) {
                    state.setInput(Input.CLICK_LEFT, true);
                }
                return state;
            }
            MovementHelper.moveTowardsWithSlightRotation(ctx, state, dest);
            return state;
        }
    }

    @Override
    public boolean safeToCancel(MovementState state) {
        // if we're in the process of breaking blocks before walking forwards
        // or if this isn't a sneak place (the block is already there)
        // then it's safe to cancel this
        return state.getStatus() != MovementStatus.RUNNING || MovementHelper.canWalkOn(ctx, dest.below());
    }

    @Override
    protected boolean prepared(MovementState state) {
        if (ctx.playerFeet().equals(src) || ctx.playerFeet().equals(src.below())) {
            Block block = BlockStateInterface.getBlock(ctx, src.below());
            if (MovementHelper.isClimbable(block)) {
                state.setInput(Input.SNEAK, true);
            }
        }

        // Gdy gracz posiada szybki kilof i kopie prosto w korytarzu:
        // Blokujemy celownik prosto wzdłuż korytarza (yaw = kierunek ruchu, pitch = 11.5° obejmujący oba bloki),
        // trzymamy ciągły bieg w przód i sprint.
        if (FastBreakHelper.isFastPickaxe(ctx) && !baritone.getFarmProcess().isActive() && Baritone.settings().allowBreak.value) {
            boolean headSolid = !MovementHelper.canWalkThrough(ctx, dest.above());
            boolean feetSolid = !MovementHelper.canWalkThrough(ctx, dest);
            if (headSolid || feetSolid) {
                float yaw = (float) Math.toDegrees(Math.atan2(-(dest.getX() - src.getX()), dest.getZ() - src.getZ()));
                double dist = Math.max(Math.abs(ctx.player().position().x - (dest.getX() + 0.5D)), Math.abs(ctx.player().position().z - (dest.getZ() + 0.5D)));
                float pitch = (feetSolid && dist < 0.95) ? 26.0f : 11.5f;
                Rotation straightRot = new Rotation(yaw, pitch);

                BetterBlockPos toBreak = headSolid ? dest.above() : dest;
                HitResult hit = RayTraceUtils.rayTraceTowards(ctx.player(), ctx.playerRotations(), ctx.playerController().getBlockReachDistance(), false);
                BlockPos hitBlock = (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) ? bhr.getBlockPos() : null;
                BetterBlockPos actualTarget = toBreak;
                if (hitBlock != null && ObstructionHelper.isClearableObstruction(ctx.world().getBlockState(hitBlock))) {
                    actualTarget = new BetterBlockPos(hitBlock);
                }
                MovementHelper.switchToBestToolFor(ctx, BlockStateInterface.get(ctx, actualTarget));
                state.setTarget(new MovementState.MovementTarget(straightRot, true));
                state.setInput(Input.CLICK_LEFT, true);
                state.setInput(Input.MOVE_FORWARD, true);
                state.setInput(Input.SPRINT, true);
                ContinuousBreakController.notifyBreaking(ctx, actualTarget);
                return false;
            }
        }

        return super.prepared(state);
    }
}
