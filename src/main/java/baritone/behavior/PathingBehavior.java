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

package baritone.behavior;

import baritone.Baritone;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.event.events.*;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.PathingCommand;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.api.utils.PathCalculationResult;
import baritone.api.utils.interfaces.IGoalRenderPos;
import baritone.pathing.calc.AStarPathFinder;
import baritone.pathing.calc.AbstractNodeCostSearch;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.path.PathExecutor;
import baritone.utils.PathingCommandContext;
import baritone.utils.PathRenderer;
import baritone.utils.pathing.Favoring;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Manages the state machine for pathfinding execution, calculation, and
 * splicing.
 * This behavior is the core orchestrator for Baritone's movement, handling the
 * lifecycle
 * of path segments, lookahead planning, and graceful cancellations.
 */
public final class PathingBehavior extends Behavior implements IPathingBehavior, Helper {

    private PathExecutor current;
    private PathExecutor next;

    private Goal goal;
    private CalculationContext context;

    /* ETA Tracking */
    private int ticksElapsedSoFar;
    private BetterBlockPos startPosition;

    /* State Flags */
    private boolean safeToCancel;
    private boolean pauseRequestedLastTick;
    private boolean unpausedLastTick;
    private boolean pausedThisTick;
    private boolean cancelRequested;
    private boolean calcFailedLastTick;

    /* Concurrency */
    private volatile AbstractNodeCostSearch inProgress;
    private final Object pathCalcLock = new Object();
    private final Object pathPlanLock = new Object();

    private boolean lastAutoJump;
    private BetterBlockPos expectedSegmentStart;

    private final LinkedBlockingQueue<PathEvent> toDispatch = new LinkedBlockingQueue<>();

    public PathingBehavior(Baritone baritone) {
        super(baritone);
    }

    private void queuePathEvent(PathEvent event) {
        toDispatch.add(event);
    }

    private void dispatchEvents() {
        var curr = new ArrayList<PathEvent>();
        toDispatch.drainTo(curr);
        calcFailedLastTick = curr.contains(PathEvent.CALC_FAILED);
        for (var event : curr) {
            baritone.getGameEventHandler().onPathEvent(event);
        }
    }

    @Override
    public void onTick(TickEvent event) {
        dispatchEvents();
        if (event.getType() == TickEvent.Type.OUT) {
            secretInternalSegmentCancel();
            baritone.getPathingControlManager().cancelEverything();
            return;
        }

        expectedSegmentStart = pathStart();
        baritone.getPathingControlManager().preTick();
        tickPath();
        ticksElapsedSoFar++;
        dispatchEvents();
    }

    @Override
    public void onPlayerSprintState(SprintStateEvent event) {
        if (isPathing()) {
            event.setState(current.isSprinting());
        }
    }

    private void tickPath() {
        pausedThisTick = false;
        if (handlePauseRequest()) {
            return;
        }

        if (cancelRequested) {
            cancelRequested = false;
            baritone.getInputOverrideHandler().clearAllKeys();
        }

        synchronized (pathPlanLock) {
            synchronized (pathCalcLock) {
                cancelIrrelevantCalculations();
            }

            if (current == null) {
                return;
            }

            if (handleCurrentPathCompletion()) {
                return;
            }

            if (tryEarlySplice()) {
                return;
            }

            if (Baritone.settings().splicePath.value) {
                current = current.trySplice(next);
            }

            if (next != null && current.getPath().getDest().equals(next.getPath().getDest())) {
                next = null;
            }

            planNextSegmentIfNeeded();
        }
    }

    private boolean handlePauseRequest() {
        if (pauseRequestedLastTick && safeToCancel) {
            pauseRequestedLastTick = false;
            if (unpausedLastTick) {
                baritone.getInputOverrideHandler().clearAllKeys();
                baritone.getInputOverrideHandler().getBlockBreakHelper().stopBreakingBlock();
            }
            unpausedLastTick = false;
            pausedThisTick = true;
            return true;
        }
        unpausedLastTick = true;
        return false;
    }

    private void cancelIrrelevantCalculations() {
        if (inProgress != null) {
            BetterBlockPos calcFrom = inProgress.getStart();
            Optional<IPath> currentBest = inProgress.bestPathSoFar();

            boolean isCurrentEndingAtCalcFrom = current != null && current.getPath().getDest().equals(calcFrom);
            boolean isCalcFromPlayerOrStart = calcFrom.equals(ctx.playerFeet())
                    || calcFrom.equals(expectedSegmentStart);
            boolean isBestPathContainingPlayerOrStart = currentBest.isPresent() &&
                    (currentBest.get().positions().contains(ctx.playerFeet())
                            || currentBest.get().positions().contains(expectedSegmentStart));

            if (!isCurrentEndingAtCalcFrom && !isCalcFromPlayerOrStart && !isBestPathContainingPlayerOrStart) {
                inProgress.cancel();
            }
        }
    }

    private boolean handleCurrentPathCompletion() {
        safeToCancel = current.onTick();
        if (!current.failed() && !current.finished()) {
            return false;
        }

        current = null;
        if (goal == null || goal.isInGoal(ctx.playerFeet())) {
            logDebug("All done. At " + goal);
            queuePathEvent(PathEvent.AT_GOAL);
            next = null;
            if (Baritone.settings().disconnectOnArrival.value) {
                ctx.world().disconnect();
            }
            return true;
        }

        if (next != null && !next.getPath().positions().contains(ctx.playerFeet())
                && !next.getPath().positions().contains(expectedSegmentStart)) {
            logDebug("Discarding next path as it does not contain current position");
            queuePathEvent(PathEvent.DISCARD_NEXT);
            next = null;
        }

        if (next != null) {
            logDebug("Continuing on to planned next path");
            queuePathEvent(PathEvent.CONTINUING_ONTO_PLANNED_NEXT);
            current = next;
            next = null;
            current.onTick();
            return true;
        }

        synchronized (pathCalcLock) {
            if (inProgress != null) {
                queuePathEvent(PathEvent.PATH_FINISHED_NEXT_STILL_CALCULATING);
                return true;
            }
            queuePathEvent(PathEvent.CALC_STARTED);
            findPathInNewThread(expectedSegmentStart, true, context);
        }
        return true;
    }

    private boolean tryEarlySplice() {
        if (safeToCancel && next != null && next.snipsnapifpossible()) {
            logDebug("Splicing into planned next path early...");
            queuePathEvent(PathEvent.SPLICING_ONTO_NEXT_EARLY);
            current = next;
            next = null;
            current.onTick();
            return true;
        }
        return false;
    }

    private void planNextSegmentIfNeeded() {
        synchronized (pathCalcLock) {
            if (inProgress != null || next != null) {
                return;
            }
            if (goal == null || goal.isInGoal(current.getPath().getDest())) {
                return;
            }
            if (ticksRemainingInSegment(false).get() < Baritone.settings().planningTickLookahead.value) {
                logDebug("Path almost over. Planning ahead...");
                queuePathEvent(PathEvent.NEXT_SEGMENT_CALC_STARTED);
                findPathInNewThread(current.getPath().getDest(), false, context);
            }
        }
    }

    @Override
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (current != null) {
            switch (event.getState()) {
                case PRE -> {
                    lastAutoJump = ctx.minecraft().options.autoJump().get();
                    ctx.minecraft().options.autoJump().set(false);
                }
                case POST -> ctx.minecraft().options.autoJump().set(lastAutoJump);
            }
        }
    }

    public void secretInternalSetGoal(Goal goal) {
        this.goal = goal;
    }

    public boolean secretInternalSetGoalAndPath(PathingCommand command) {
        secretInternalSetGoal(command.goal);
        context = (command instanceof PathingCommandContext pcc) ? pcc.desiredCalcContext
                : new CalculationContext(baritone, true);

        if (goal == null || goal.isInGoal(ctx.playerFeet())) {
            return false;
        }

        synchronized (pathPlanLock) {
            if (current != null)
                return false;
            synchronized (pathCalcLock) {
                if (inProgress != null)
                    return false;
                queuePathEvent(PathEvent.CALC_STARTED);
                findPathInNewThread(expectedSegmentStart, true, context);
                return true;
            }
        }
    }

    @Override
    public Goal getGoal() {
        return goal;
    }

    @Override
    public boolean isPathing() {
        return hasPath() && !pausedThisTick;
    }

    @Override
    public PathExecutor getCurrent() {
        return current;
    }

    @Override
    public PathExecutor getNext() {
        return next;
    }

    @Override
    public Optional<AbstractNodeCostSearch> getInProgress() {
        return Optional.ofNullable(inProgress);
    }

    public boolean isSafeToCancel() {
        if (current == null) {
            return !baritone.getElytraProcess().isActive() || baritone.getElytraProcess().isSafeToCancel();
        }
        return safeToCancel;
    }

    public void requestPause() {
        pauseRequestedLastTick = true;
    }

    public boolean cancelSegmentIfSafe() {
        if (isSafeToCancel()) {
            secretInternalSegmentCancel();
            return true;
        }
        return false;
    }

    @Override
    public boolean cancelEverything() {
        boolean doIt = isSafeToCancel();
        if (doIt) {
            secretInternalSegmentCancel();
        }
        baritone.getPathingControlManager().cancelEverything();
        return doIt;
    }

    public boolean calcFailedLastTick() {
        return calcFailedLastTick;
    }

    public void softCancelIfSafe() {
        synchronized (pathPlanLock) {
            getInProgress().ifPresent(AbstractNodeCostSearch::cancel);
            if (!isSafeToCancel()) {
                return;
            }
            current = null;
            next = null;
        }
        cancelRequested = true;
    }

    public void secretInternalSegmentCancel() {
        queuePathEvent(PathEvent.CANCELED);
        synchronized (pathPlanLock) {
            getInProgress().ifPresent(AbstractNodeCostSearch::cancel);
            if (current != null) {
                current = null;
                next = null;
                baritone.getInputOverrideHandler().clearAllKeys();
                baritone.getInputOverrideHandler().getBlockBreakHelper().stopBreakingBlock();
            }
        }
    }

    @Override
    public void forceCancel() {
        cancelEverything();
        secretInternalSegmentCancel();
        synchronized (pathCalcLock) {
            inProgress = null;
        }
    }

    public CalculationContext secretInternalGetCalculationContext() {
        return context;
    }

    /**
     * Intelligently estimates the remaining ticks to reach the goal based on
     * current velocity and heuristic distance.
     * Safely handles edge cases where the player is stuck or moving away from the
     * goal.
     */
    public Optional<Double> estimatedTicksToGoal() {
        BetterBlockPos currentPos = ctx.playerFeet();
        if (goal == null || currentPos == null || startPosition == null) {
            return Optional.empty();
        }
        if (goal.isInGoal(currentPos)) {
            resetEstimatedTicksToGoal();
            return Optional.of(0.0);
        }
        if (ticksElapsedSoFar == 0) {
            return Optional.empty();
        }

        double currentHeur = goal.heuristic(currentPos.x, currentPos.y, currentPos.z);
        double startHeur = goal.heuristic(startPosition.x, startPosition.y, startPosition.z);
        double goalHeur = goal.heuristic();

        // Prevent division by zero and handle cases where we moved away from the goal
        double distanceCovered = Math.max(0.1, startHeur - currentHeur);
        double distanceRemaining = Math.max(0.0, currentHeur - goalHeur);

        if (distanceCovered < 1e-3) {
            return Optional.empty(); // Barely moved towards the goal
        }

        double ticksPerUnit = ticksElapsedSoFar / distanceCovered;
        return Optional.of(distanceRemaining * ticksPerUnit);
    }

    private void resetEstimatedTicksToGoal() {
        resetEstimatedTicksToGoal(expectedSegmentStart);
    }

    private void resetEstimatedTicksToGoal(BlockPos start) {
        resetEstimatedTicksToGoal(new BetterBlockPos(start));
    }

    private void resetEstimatedTicksToGoal(BetterBlockPos start) {
        ticksElapsedSoFar = 0;
        startPosition = start;
    }

    /**
     * Determines the most logical starting block for a new path, accounting for
     * player physics
     * (e.g., standing on the edge of a block or mid-jump).
     */
    public BetterBlockPos pathStart() {
        BetterBlockPos feet = ctx.playerFeet();
        if (MovementHelper.canWalkOn(ctx, feet.below())) {
            return feet;
        }

        if (ctx.player().onGround()) {
            double playerX = ctx.player().position().x;
            double playerZ = ctx.player().position().z;

            var closest = new ArrayList<BetterBlockPos>(9);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    closest.add(new BetterBlockPos(feet.x + dx, feet.y, feet.z + dz));
                }
            }

            closest.sort(Comparator.comparingDouble(pos -> {
                double dx = (pos.x + 0.5D) - playerX;
                double dz = (pos.z + 0.5D) - playerZ;
                return dx * dx + dz * dz;
            }));

            for (int i = 0; i < 4; i++) {
                BetterBlockPos possibleSupport = closest.get(i);
                double xDist = Math.abs((possibleSupport.x + 0.5D) - playerX);
                double zDist = Math.abs((possibleSupport.z + 0.5D) - playerZ);

                if (xDist > 0.8 && zDist > 0.8) {
                    continue;
                }

                if (MovementHelper.canWalkOn(ctx, possibleSupport.below()) &&
                        MovementHelper.canWalkThrough(ctx, possibleSupport) &&
                        MovementHelper.canWalkThrough(ctx, possibleSupport.above())) {
                    return possibleSupport;
                }
            }
        } else if (MovementHelper.canWalkOn(ctx, feet.below().below())) {
            return feet.below();
        }

        return feet;
    }

    private void findPathInNewThread(final BlockPos start, final boolean talkAboutIt, CalculationContext context) {
        if (!Thread.holdsLock(pathCalcLock)) {
            throw new IllegalStateException("Must be called with synchronization on pathCalcLock");
        }
        if (inProgress != null) {
            throw new IllegalStateException("Already calculating a path");
        }
        if (!context.safeForThreadedUse) {
            throw new IllegalStateException("Improper context thread safety level");
        }

        Goal goal = this.goal;
        if (goal == null) {
            logDebug("No goal set for pathfinding");
            return;
        }

        long primaryTimeout = (current == null) ? Baritone.settings().primaryTimeoutMS.value
                : Baritone.settings().planAheadPrimaryTimeoutMS.value;
        long failureTimeout = (current == null) ? Baritone.settings().failureTimeoutMS.value
                : Baritone.settings().planAheadFailureTimeoutMS.value;

        AbstractNodeCostSearch pathfinder = createPathfinder(start, goal, current == null ? null : current.getPath(),
                context);

        if (!Objects.equals(pathfinder.getGoal(), goal)) {
            logDebug("Simplifying " + goal.getClass().getSimpleName() + " to GoalXZ due to distance");
        }

        inProgress = pathfinder;

        Baritone.getExecutor().execute(() -> {
            if (talkAboutIt) {
                logDebug("Starting to search for path from " + start + " to " + goal);
            }

            PathCalculationResult calcResult = pathfinder.calculate(primaryTimeout, failureTimeout);

            synchronized (pathPlanLock) {
                Optional<PathExecutor> executor = calcResult.getPath()
                        .map(p -> new PathExecutor(PathingBehavior.this, p));

                if (current == null) {
                    handleInitialCalculationResult(executor, calcResult, start);
                } else {
                    handleLookaheadCalculationResult(executor, calcResult);
                }

                logCalculationCompletion(talkAboutIt, start, goal);

                synchronized (pathCalcLock) {
                    inProgress = null;
                }
            }
        });
    }

    private void handleInitialCalculationResult(Optional<PathExecutor> executor, PathCalculationResult calcResult,
            BlockPos start) {
        if (executor.isPresent()) {
            if (executor.get().getPath().positions().contains(expectedSegmentStart)) {
                queuePathEvent(PathEvent.CALC_FINISHED_NOW_EXECUTING);
                current = executor.get();
                resetEstimatedTicksToGoal(start);
            } else {
                logDebug("Warning: discarding orphan path segment with incorrect start");
            }
        } else if (calcResult.getType() != PathCalculationResult.Type.CANCELLATION
                && calcResult.getType() != PathCalculationResult.Type.EXCEPTION) {
            queuePathEvent(PathEvent.CALC_FAILED);
        }
    }

    private void handleLookaheadCalculationResult(Optional<PathExecutor> executor, PathCalculationResult calcResult) {
        if (next == null) {
            if (executor.isPresent()) {
                if (executor.get().getPath().getSrc().equals(current.getPath().getDest())) {
                    queuePathEvent(PathEvent.NEXT_SEGMENT_CALC_FINISHED);
                    next = executor.get();
                } else {
                    logDebug("Warning: discarding orphan next segment with incorrect start");
                }
            } else {
                queuePathEvent(PathEvent.NEXT_CALC_FAILED);
            }
        } else {
            logDirect("Warning: PathingBehavior illegal state! Discarding invalid path!");
        }
    }

    private void logCalculationCompletion(boolean talkAboutIt, BlockPos start, Goal goal) {
        if (talkAboutIt && current != null && current.getPath() != null) {
            int nodes = current.getPath().getNumNodesConsidered();
            if (goal.isInGoal(current.getPath().getDest())) {
                logDebug(String.format("Finished finding a path from %s to %s. %d nodes considered", start, goal,
                        nodes));
            } else {
                logDebug(String.format("Found path segment from %s towards %s. %d nodes considered", start, goal,
                        nodes));
            }
        }
    }

    private AbstractNodeCostSearch createPathfinder(BlockPos start, Goal goal, IPath previous,
            CalculationContext context) {
        Goal transformed = goal;
        if (Baritone.settings().simplifyUnloadedYCoord.value && goal instanceof IGoalRenderPos renderPos) {
            BlockPos pos = renderPos.getGoalPos();
            if (!context.bsi.worldContainsLoadedChunk(pos.getX(), pos.getZ())) {
                transformed = new GoalXZ(pos.getX(), pos.getZ());
            }
        }

        Favoring favoring = new Favoring(context.getBaritone().getPlayerContext(), previous, context);
        BetterBlockPos feet = ctx.playerFeet();
        var realStart = new BetterBlockPos(start);
        var sub = feet.subtract(realStart);

        if (feet.getY() == realStart.getY() && Math.abs(sub.getX()) <= 1 && Math.abs(sub.getZ()) <= 1) {
            realStart = feet;
        }

        return new AStarPathFinder(realStart, start.getX(), start.getY(), start.getZ(), transformed, favoring, context);
    }

    @Override
    public void onRenderPass(RenderEvent event) {
        PathRenderer.render(event, this);
    }
}