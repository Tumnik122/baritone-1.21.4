package baritone.bypass;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Function;

/**
 * Block breaking happens after process.onTick, not inside it. Keep the original
 * state until a later tick observes the result, and consume that result once.
 */
final class BypassBreakTracker {
    record BrokenBlock(BlockPos pos, BlockState state) {}

    private BrokenBlock pending;
    private boolean attacked;

    void watch(BlockPos pos, BlockState state) {
        if (pending == null || !pending.pos().equals(pos)) {
            pending = new BrokenBlock(pos.immutable(), state);
            attacked = false;
        }
    }

    void attacked() {
        attacked = true;
    }

    boolean isTracking() {
        return pending != null;
    }

    BrokenBlock poll(Function<BlockPos, BlockState> states) {
        if (pending == null) {
            return null;
        }
        BlockState now = states.apply(pending.pos());
        if (now.is(pending.state().getBlock())) {
            return null;
        }
        BrokenBlock result = attacked ? pending : null;
        reset();
        return result;
    }

    void reset() {
        pending = null;
        attacked = false;
    }

    static long timeoutMillis(float progressPerTick) {
        if (!Float.isFinite(progressPerTick) || progressPerTick <= 0) {
            return 5000L;
        }
        // Allow ordinary slow tools, deepslate and a reasonable server delay.
        return (long) Math.max(5000D, Math.min(120000D, 100D / progressPerTick + 2000D));
    }
}
