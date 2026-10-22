package baritone.utils.builder;

import baritone.utils.builder.MovingRaycast.Aim;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Predicate;

/**
 * Two-phase placement state machine:
 *   tick N   : pick a target, command rotation (aim computed from the predicted eye of N+1)
 *   tick N+1 : re-verify with the real eye/rotation; if OK and the pacing gap elapsed -> click
 *              (rotation is NOT touched on the click tick)
 *
 * Pacing: minimum gap alternates 2,3,2,3 ticks (avg 2.5 -> ~8 clicks/s max, never a 0/1-tick burst).
 * The real ceiling while walking is ~4.3 blocks/s anyway (one cell per ~4.6 ticks).
 */
public final class SweepPlacementScheduler {

    public record Plan(BlockPos cell, BlockPos support, Direction face, Aim aim, int plannedTick) {}

    /** aim == null -> leave rotation as is. click == true -> press CLICK_RIGHT this tick. */
    public record Decision(Aim aim, boolean click) {}

    private int tick;
    private int lastClickTick = -100;
    private int clicks;
    private Plan armed;

    public void reset() {
        tick = 0;
        lastClickTick = -100;
        clicks = 0;
        armed = null;
    }

    private int requiredGap() { return 2 + (clicks & 1); }

    public Decision step(Level level, LocalPlayer p, List<BlockPos> candidates, Predicate<BlockPos> needsBlock) {
        tick++;

        if (armed != null) {
            if (!needsBlock.test(armed.cell())) {
                armed = null;
            } else {
                boolean aimOk = MovingRaycast.hitsFace(level, p, p.getEyePosition(), p.getYRot(), p.getXRot(),
                        armed.support(), armed.face(), MovingRaycast.MAX_REACH)
                        && MovingRaycast.cellFree(level, p, armed.cell());
                boolean gapOk = tick - lastClickTick >= requiredGap();

                if (aimOk && gapOk) {
                    lastClickTick = tick;
                    clicks++;
                    armed = null;
                    return new Decision(null, true);      // do not move the crosshair on the click tick
                }
                if (aimOk) return new Decision(null, false); // waiting for the gap, aim still valid
                armed = null;                                 // stale -> re-plan below
            }
        }

        armed = plan(level, p, candidates, needsBlock);
        return new Decision(armed == null ? null : armed.aim(), false);
    }

    private Plan plan(Level level, LocalPlayer p, List<BlockPos> candidates, Predicate<BlockPos> needsBlock) {
        Vec3 eye = MovingRaycast.predictedEye(p);
        for (BlockPos cell : candidates) {
            if (!needsBlock.test(cell) || !MovingRaycast.cellFree(level, p, cell)) continue;

            BlockPos support = cell.below();
            if (!level.getBlockState(support).isFaceSturdy(level, support, Direction.UP)) continue;

            Vec3 point = new Vec3(support.getX() + 0.5, support.getY() + 1.0, support.getZ() + 0.5);
            if (eye.distanceTo(point) > MovingRaycast.MAX_REACH - 0.3) continue; // margin for prediction error

            Aim aim = MovingRaycast.aimAt(eye, point);
            if (!MovingRaycast.hitsFace(level, p, eye, aim.yaw(), aim.pitch(), support, Direction.UP,
                    MovingRaycast.MAX_REACH)) continue;

            return new Plan(cell, support, Direction.UP, aim, tick);
        }
        return null;
    }
}
