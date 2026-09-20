package baritone.utils;

import baritone.api.IBaritone;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import baritone.process.BuilderProcess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Optional;
import java.util.function.Predicate;

public final class BlockBreaker {

    private BlockBreaker() {}

    public static boolean breakBlocks(BuilderProcess proc, Predicate<BlockState> predicate, BetterBlockPos... blocks) {
        return breakBlocks((IBaritone) proc.baritone, predicate, blocks);
    }

    public static boolean breakBlocks(IBaritone baritone, Predicate<BlockState> predicate, BetterBlockPos... blocks) {
        IPlayerContext ctx = baritone.getPlayerContext();
        if (ctx == null || ctx.player() == null || ctx.world() == null) {
            ContinuousBreakController.reset();
            return false;
        }
        double reach = ctx.playerController().getBlockReachDistance();

        // ── 1) Celownik JUŻ trafia w blok do rozbicia → trzymaj przycisk ──
        HitResult result = RayTraceUtils.rayTraceTowards(ctx.player(), ctx.playerRotations(), reach);
        if (result != null && result.getType() == HitResult.Type.BLOCK) {
            BlockPos hit = ((BlockHitResult) result).getBlockPos();
            if (predicate.test(ctx.world().getBlockState(hit))) {
                MovementHelper.switchToBestToolFor(ctx, ctx.world().getBlockState(hit));
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                // NOWE: zapamiętaj aktywne kopanie → uruchamia lepkość
                ContinuousBreakController.notifyBreaking(ctx, new BetterBlockPos(hit));
                return true;
            }
        }

        // ── 2) Cel w zasięgu → obróć kamerę; przy lepkości NIE puszczaj ──
        for (BetterBlockPos pos : blocks) {
            if (pos == null || !predicate.test(ctx.world().getBlockState(pos))) {
                continue;
            }
            Optional<Rotation> rot = RotationUtils.reachable(ctx, pos, reach);
            if (rot.isPresent()) {
                baritone.getLookBehavior().updateTarget(rot.get(), true);
                // NOWE: przed chwilą rozbiliśmy sąsiedni blok (słupek/linia
                // drzew)? Trzymaj CLICK_LEFT przez CAŁY obrót kamery.
                if (ContinuousBreakController.shouldHoldThrough(ctx, pos, rot.get())) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                }
                return false;
            }
        }

        // brak celu → zeruj lepkość (normalne puszczenie przycisku)
        ContinuousBreakController.reset();
        return false;
    }
}
