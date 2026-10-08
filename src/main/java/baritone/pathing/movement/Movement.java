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

package baritone.pathing.movement;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.*;
import baritone.api.utils.input.Input;
import baritone.behavior.PathingBehavior;
import baritone.bypass.RotationEngine;
import baritone.utils.BlockStateInterface;
import baritone.utils.ContinuousBreakController;
import baritone.utils.FastBreakHelper;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public abstract class Movement implements IMovement, MovementHelper {

    public static final Direction[] HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN};

    protected final IBaritone baritone;
    protected final IPlayerContext ctx;

    private MovementState currentState = new MovementState().setStatus(MovementStatus.PREPPING);

    protected final BetterBlockPos src;

    protected final BetterBlockPos dest;

    /**
     * The positions that need to be broken before this movement can ensue
     */
    protected final BetterBlockPos[] positionsToBreak;

    /**
     * The position where we need to place a block before this movement can ensue
     */
    protected final BetterBlockPos positionToPlace;

    private Double cost;

    public List<BlockPos> toBreakCached = null;
    public List<BlockPos> toPlaceCached = null;
    public List<BlockPos> toWalkIntoCached = null;

    private Set<BetterBlockPos> validPositionsCached = null;

    private Boolean calculatedWhileLoaded;

    protected Movement(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest, BetterBlockPos[] toBreak, BetterBlockPos toPlace) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
        this.src = src;
        this.dest = dest;
        this.positionsToBreak = toBreak;
        this.positionToPlace = toPlace;
    }

    protected Movement(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest, BetterBlockPos[] toBreak) {
        this(baritone, src, dest, toBreak, null);
    }

    public double getCost() throws NullPointerException {
        return cost;
    }

    public double getCost(CalculationContext context) {
        if (cost == null) {
            cost = calculateCost(context);
        }
        return cost;
    }

    public abstract double calculateCost(CalculationContext context);

    public double recalculateCost(CalculationContext context) {
        cost = null;
        return getCost(context);
    }

    public void override(double cost) {
        this.cost = cost;
    }

    protected abstract Set<BetterBlockPos> calculateValidPositions();

    public Set<BetterBlockPos> getValidPositions() {
        if (validPositionsCached == null) {
            validPositionsCached = calculateValidPositions();
            Objects.requireNonNull(validPositionsCached);
        }
        return validPositionsCached;
    }

    protected boolean playerInValidPosition() {
        return getValidPositions().contains(ctx.playerFeet()) || getValidPositions().contains(((PathingBehavior) baritone.getPathingBehavior()).pathStart());
    }

    private BetterBlockPos currentMiningPos = null;

    /**
     * Handles the execution of the latest Movement
     * State, and offers a Status to the calling class.
     *
     * @return Status
     */
    @Override
    public MovementStatus update() {
        ctx.player().getAbilities().flying = false;
        currentState = updateState(currentState);
        boolean inLiquid = MovementHelper.isLiquid(ctx, ctx.playerFeet()) || ctx.player().isInWater();
        if (inLiquid) {
            boolean headColliding = ctx.player().verticalCollision && !ctx.player().onGround();

            // Sneaking pulls the player down to the bottom in water; disable sneak while in liquid
            currentState.setInput(Input.SNEAK, false);

            if (headColliding) {
                // If head is actively touching the ceiling, release jump immediately
                currentState.setInput(Input.JUMP, false);
            } else {
                // In water, jump to swim, maintain buoyancy, and push through water currents
                currentState.setInput(Input.JUMP, true);
            }
        }
        if (ctx.player().isInWall() && !hasUnbrokenPositionsToBreak()) {
            ctx.getSelectedBlock().ifPresent(pos -> MovementHelper.switchToBestToolFor(ctx, BlockStateInterface.get(ctx, pos)));
            currentState.setInput(Input.CLICK_LEFT, true);
        }

        // Anticheat compliance (GrimAC / Vulcan):
        // Nigdy nie sprintuj podczas kopania bloków nie-instamine (chroni przed flagami InvalidSprint),
        // chyba ze posiadamy szybki kilof (FastBreak / Efficiency 5+ / Haste)
        if (currentState.getInputStates().getOrDefault(Input.CLICK_LEFT, false)) {
            boolean isInsta = currentMiningPos != null && FastBreakHelper.isInstaBreak(ctx, currentMiningPos, BlockStateInterface.get(ctx, currentMiningPos));
            if (!isInsta && !FastBreakHelper.isFastPickaxe(ctx)) {
                currentState.setInput(Input.SPRINT, false);
            }
        }

        // If the movement target has to force the new rotations, or we aren't using silent move, then force the rotations
        currentState.getTarget().getRotation().ifPresent(rotation ->
                baritone.getLookBehavior().updateTarget(
                        rotation,
                        currentState.getTarget().hasToForceRotations()));
        baritone.getInputOverrideHandler().clearAllKeys();
        currentState.getInputStates().forEach((input, forced) -> {
            baritone.getInputOverrideHandler().setInputForceState(input, forced);
        });
        currentState.getInputStates().clear();

        // If the current status indicates a completed movement and no active path continuation
        if (currentState.getStatus().isComplete() && (!baritone.getPathingBehavior().isPathing() || currentState.getStatus() == MovementStatus.FAILED)) {
            baritone.getInputOverrideHandler().clearAllKeys();
        }

        return currentState.getStatus();
    }

    public boolean hasUnbrokenPositionsToBreak() {
        if (baritone.getFarmProcess().isActive()) {
            return false;
        }
        if (positionsToBreak == null || positionsToBreak.length == 0) {
            return false;
        }
        for (BetterBlockPos pos : positionsToBreak) {
            if (pos != null && !MovementHelper.canWalkThrough(ctx, pos)) {
                BlockState bState = BlockStateInterface.get(ctx, pos);
                if (!bState.getFluidState().isEmpty() || bState.is(net.minecraft.world.level.block.Blocks.BEDROCK)
                        || bState.getDestroySpeed(ctx.world(), pos) < 0) {
                    continue; // Płynów i Bedrocka nie traktujemy jako bloki do wykopania!
                }
                return true;
            }
        }
        return false;
    }

    protected boolean prepared(MovementState state) {
        if (state.getStatus() == MovementStatus.WAITING) {
            return true;
        }
        if (baritone.getFarmProcess().isActive()) {
            currentMiningPos = null;
            return true;
        }

        // If we are already mining a specific block from positionsToBreak and it's not yet broken,
        // stick to it until it breaks to avoid target flapping between multiple blocks.
        if (currentMiningPos != null) {
            BlockState currentBsi = BlockStateInterface.get(ctx, currentMiningPos);
            if (MovementHelper.canWalkThrough(ctx, currentMiningPos) || !currentBsi.getFluidState().isEmpty()
                    || currentBsi.is(net.minecraft.world.level.block.Blocks.BEDROCK) || currentBsi.getDestroySpeed(ctx.world(), currentMiningPos) < 0
                    || (baritone.getFarmProcess().isActive() && MovementHelper.isFarmSoilOrStructure(currentBsi.getBlock()))) {
                currentMiningPos = null;
                // Poprzedni blok rozbity lub bedrock — natychmiast anuluj cel!
            } else {
                Optional<Rotation> reachable = RotationUtils.reachable(ctx, currentMiningPos, ctx.playerController().getBlockReachDistance());
                if (reachable.isPresent()) {
                    Rotation rotTowardsBlock = reachable.get();
                    state.setTarget(new MovementState.MovementTarget(rotTowardsBlock, true));

                    HitResult trace = RayTraceUtils.rayTraceTowards(ctx.player(), ctx.playerRotations(), ctx.playerController().getBlockReachDistance(), false);
                    BlockPos hitBlock = (trace instanceof BlockHitResult bhr && trace.getType() == HitResult.Type.BLOCK) ? bhr.getBlockPos() : null;

                    BlockPos breakTarget = currentMiningPos;
                    if (hitBlock != null && !hitBlock.equals(currentMiningPos)) {
                        BlockState hitState = ctx.world().getBlockState(hitBlock);
                        if (ObstructionHelper.isClearableObstruction(hitState) && ObstructionHelper.isCoveringOrAdjacent(hitBlock, currentMiningPos)) {
                            breakTarget = hitBlock;
                        }
                    }

                    MovementHelper.switchToBestToolFor(ctx, BlockStateInterface.get(ctx, breakTarget));
                    boolean hitsBlock = hitBlock != null && (hitBlock.equals(currentMiningPos) || hitBlock.equals(breakTarget));
                    boolean isLooking = ctx.isLookingAt(currentMiningPos) || ctx.isLookingAt(breakTarget);

                    if (isLooking || hitsBlock || ctx.playerRotations().isReallyCloseTo(rotTowardsBlock)
                            || (FastBreakHelper.isFastBreakActive(ctx) && ContinuousBreakController.shouldHoldThrough(ctx, new BetterBlockPos(breakTarget), rotTowardsBlock))
                            || RotationEngine.isSettled(1)) {
                        state.setInput(Input.CLICK_LEFT, true);
                        ContinuousBreakController.notifyBreaking(ctx, new BetterBlockPos(breakTarget));
                    }
                    return false;
                } else {
                    currentMiningPos = null;
                }
            }
        }

        // Priority 1: If the player's crosshair is ALREADY looking directly at any blocking block
        // from positionsToBreak (or an obstructing snow/vine covering it), attack it immediately rather than forcing a camera flick to another block.
        for (BetterBlockPos blockPos : positionsToBreak) {
            BlockState bState = BlockStateInterface.get(ctx, blockPos);
            if (!bState.getFluidState().isEmpty() || bState.is(net.minecraft.world.level.block.Blocks.BEDROCK)
                    || bState.getDestroySpeed(ctx.world(), blockPos) < 0
                    || (baritone.getFarmProcess().isActive() && MovementHelper.isFarmSoilOrStructure(bState.getBlock()))) {
                continue;
            }
            HitResult trace = RayTraceUtils.rayTraceTowards(ctx.player(), ctx.playerRotations(), ctx.playerController().getBlockReachDistance(), false);
            BlockPos hitBlock = (trace instanceof BlockHitResult bhr && trace.getType() == HitResult.Type.BLOCK) ? bhr.getBlockPos() : null;
            boolean lookingAtObstr = hitBlock != null && ObstructionHelper.isClearableObstruction(ctx.world().getBlockState(hitBlock)) && ObstructionHelper.isCoveringOrAdjacent(hitBlock, blockPos);

            if (!MovementHelper.canWalkThrough(ctx, blockPos) && (ctx.isLookingAt(blockPos) || lookingAtObstr)) {
                currentMiningPos = blockPos;
                BlockPos targetToBreak = lookingAtObstr ? hitBlock : blockPos;
                MovementHelper.switchToBestToolFor(ctx, BlockStateInterface.get(ctx, targetToBreak));
                Optional<Rotation> reachable = RotationUtils.reachable(ctx, blockPos, ctx.playerController().getBlockReachDistance());
                Rotation rot = reachable.orElseGet(() -> ctx.playerRotations());
                state.setTarget(new MovementState.MovementTarget(rot, true));
                state.setInput(Input.CLICK_LEFT, true);
                ContinuousBreakController.notifyBreaking(ctx, new BetterBlockPos(targetToBreak));
                return false;
            }
        }

        boolean somethingInTheWay = false;
        for (BetterBlockPos blockPos : positionsToBreak) {
            BlockState bState = BlockStateInterface.get(ctx, blockPos);
            if (!bState.getFluidState().isEmpty()) {
                continue; // Nie próbuj kopać wody ani lawy
            }
            if (bState.is(net.minecraft.world.level.block.Blocks.BEDROCK) || bState.getDestroySpeed(ctx.world(), blockPos) < 0
                    || (baritone.getFarmProcess().isActive() && MovementHelper.isFarmSoilOrStructure(bState.getBlock()))) {
                if (!MovementHelper.canWalkThrough(ctx, blockPos)) {
                    somethingInTheWay = true; // Bedrock lub gleba farmy blokuje przejście, ale nie możemy jej wykopać
                }
                continue;
            }
            if (!ctx.world().getEntitiesOfClass(FallingBlockEntity.class, new AABB(0, 0, 0, 1, 1.1, 1).move(blockPos)).isEmpty() && Baritone.settings().pauseMiningForFallingBlocks.value) {
                return false;
            }
            if (!MovementHelper.canWalkThrough(ctx, blockPos)) { // can't break air, so don't try
                somethingInTheWay = true;
                Optional<Rotation> reachable = RotationUtils.reachable(ctx, blockPos, ctx.playerController().getBlockReachDistance());
                if (reachable.isPresent()) {
                    currentMiningPos = blockPos;
                    Rotation rotTowardsBlock = reachable.get();
                    state.setTarget(new MovementState.MovementTarget(rotTowardsBlock, true));

                    HitResult trace = RayTraceUtils.rayTraceTowards(ctx.player(), ctx.playerRotations(), ctx.playerController().getBlockReachDistance(), false);
                    BlockPos hitBlock = (trace instanceof BlockHitResult bhr && trace.getType() == HitResult.Type.BLOCK) ? bhr.getBlockPos() : null;
                    BlockPos breakTarget = blockPos;
                    if (hitBlock != null && !hitBlock.equals(blockPos)) {
                        BlockState hitState = ctx.world().getBlockState(hitBlock);
                        if (ObstructionHelper.isClearableObstruction(hitState) && ObstructionHelper.isCoveringOrAdjacent(hitBlock, blockPos)) {
                            breakTarget = hitBlock;
                        }
                    }

                    MovementHelper.switchToBestToolFor(ctx, BlockStateInterface.get(ctx, breakTarget));
                    boolean hitsBlock = hitBlock != null && (hitBlock.equals(blockPos) || hitBlock.equals(breakTarget));
                    boolean isLooking = ctx.isLookingAt(blockPos) || ctx.isLookingAt(breakTarget);

                    if (isLooking || hitsBlock || ctx.playerRotations().isReallyCloseTo(rotTowardsBlock)
                            || (FastBreakHelper.isFastBreakActive(ctx) && ContinuousBreakController.shouldHoldThrough(ctx, new BetterBlockPos(breakTarget), rotTowardsBlock))
                            || RotationEngine.isSettled(1)) {
                        state.setInput(Input.CLICK_LEFT, true);
                        ContinuousBreakController.notifyBreaking(ctx, new BetterBlockPos(breakTarget));
                    }
                    return false;
                }
                //get rekt minecraft
                state.setTarget(new MovementState.MovementTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(),
                        VecUtils.getBlockPosCenter(blockPos), ctx.playerRotations()), true)
                );
                // Also check if crosshair is hitting an obstructing snow or vine:
                HitResult fallbackTrace = RayTraceUtils.rayTraceTowards(ctx.player(), ctx.playerRotations(), ctx.playerController().getBlockReachDistance(), false);
                if (fallbackTrace instanceof BlockHitResult bhr && fallbackTrace.getType() == HitResult.Type.BLOCK) {
                    BlockPos hitPos = bhr.getBlockPos();
                    BlockState hitState = ctx.world().getBlockState(hitPos);
                    if (ObstructionHelper.isClearableObstruction(hitState) && ObstructionHelper.isCoveringOrAdjacent(hitPos, blockPos)) {
                        MovementHelper.switchToBestToolFor(ctx, hitState);
                        state.setInput(Input.CLICK_LEFT, true);
                        ContinuousBreakController.notifyBreaking(ctx, new BetterBlockPos(hitPos));
                        return false;
                    }
                }
                // If strictRaytraceOnly is enabled, do not click through walls without a clear line of sight
                if (!Baritone.settings().strictRaytraceOnly.value) {
                    state.setInput(Input.CLICK_LEFT, true);
                }
                // ANTI-FREEZE: Jeśli blok do rozbicia jest poza zasięgiem ręki, bot MUSI iść do przodu
                // w stronę src (jeśli jeszcze do niego nie doszedł) lub dest, aby zbliżyć się na zasięg bicia!
                // Zapobiega to całkowitemu zamrożeniu bota w miejscu ze Speed 0.0 b/s.
                BetterBlockPos moveDest = (src != null && !ctx.playerFeet().equals(src)) ? src : dest;
                if (moveDest != null) {
                    MovementHelper.moveTowardsWithoutRotation(ctx, state, moveDest);
                }
                return false;
            }
        }
        if (somethingInTheWay) {
            // There's a block or blocks that we can't walk through, but we have no target rotation to reach any
            // So don't return true, actually set state to unreachable
            state.setStatus(MovementStatus.UNREACHABLE);
            return true;
        }
        return true;
    }

    @Override
    public boolean safeToCancel() {
        return safeToCancel(currentState);
    }

    protected boolean safeToCancel(MovementState currentState) {
        return true;
    }

    @Override
    public BetterBlockPos getSrc() {
        return src;
    }

    @Override
    public BetterBlockPos getDest() {
        return dest;
    }

    @Override
    public void reset() {
        currentState = new MovementState().setStatus(MovementStatus.PREPPING);
        currentMiningPos = null;
    }

    /**
     * Calculate latest movement state. Gets called once a tick.
     *
     * @param state The current state
     * @return The new state
     */
    public MovementState updateState(MovementState state) {
        if (!prepared(state)) {
            return state.setStatus(MovementStatus.PREPPING);
        } else if (state.getStatus() == MovementStatus.PREPPING) {
            state.setStatus(MovementStatus.WAITING);
        }

        if (state.getStatus() == MovementStatus.WAITING) {
            state.setStatus(MovementStatus.RUNNING);
        }

        return state;
    }

    @Override
    public BlockPos getDirection() {
        return getDest().subtract(getSrc());
    }

    public void checkLoadedChunk(CalculationContext context) {
        calculatedWhileLoaded = context.bsi.worldContainsLoadedChunk(dest.x, dest.z);
    }

    @Override
    public boolean calculatedWhileLoaded() {
        return calculatedWhileLoaded;
    }

    @Override
    public void resetBlockCache() {
        toBreakCached = null;
        toPlaceCached = null;
        toWalkIntoCached = null;
    }

    public List<BlockPos> toBreak(BlockStateInterface bsi) {
        if (toBreakCached != null) {
            return toBreakCached;
        }
        List<BlockPos> result = new ArrayList<>();
        for (BetterBlockPos positionToBreak : positionsToBreak) {
            BlockState state = bsi.get0(positionToBreak.x, positionToBreak.y, positionToBreak.z);
            if (state.is(net.minecraft.world.level.block.Blocks.BEDROCK)) {
                continue; // Bedrock nigdy nie jest oznaczany jako blok do wykopania!
            }
            if (!MovementHelper.canWalkThrough(bsi, positionToBreak.x, positionToBreak.y, positionToBreak.z)) {
                if (!state.getFluidState().isEmpty()) {
                    continue; // Płynów nie zaznaczamy do rozbicia
                }
                result.add(positionToBreak);
            }
        }
        toBreakCached = result;
        return result;
    }

    public List<BlockPos> toPlace(BlockStateInterface bsi) {
        if (toPlaceCached != null) {
            return toPlaceCached;
        }
        List<BlockPos> result = new ArrayList<>();
        if (positionToPlace != null && !MovementHelper.canWalkOn(bsi, positionToPlace.x, positionToPlace.y, positionToPlace.z)) {
            result.add(positionToPlace);
        }
        toPlaceCached = result;
        return result;
    }

    public List<BlockPos> toWalkInto(BlockStateInterface bsi) { // overridden by movementdiagonal
        if (toWalkIntoCached == null) {
            toWalkIntoCached = new ArrayList<>();
        }
        return toWalkIntoCached;
    }

    public BlockPos[] toBreakAll() {
        return positionsToBreak;
    }
}
