package baritone.utils.builder;

import baritone.api.IBaritone;

import java.util.WeakHashMap;

/**
 * BUG 1 — cross-version CALC_FAILED signal. PathingBehavior /
 * PathingControlManager increment this wherever a path calculation ends in
 * CALC_FAILED and reset it when a path succeeds. BuilderProcess consumes both
 * this tracker and the onTick(calcFailed) flag.
 */
public final class PathingFailureTracker {

    private static final WeakHashMap<IBaritone, PathingFailureTracker> INSTANCES = new WeakHashMap<>();

    public static PathingFailureTracker get(IBaritone baritone) {
        synchronized (INSTANCES) {
            return INSTANCES.computeIfAbsent(baritone, b -> new PathingFailureTracker());
        }
    }

    private int consecutiveFailures;

    private PathingFailureTracker() {}

    public void onCalcFailed() {
        consecutiveFailures++;
    }

    public void onPathSuccess() {
        consecutiveFailures = 0;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }
}
