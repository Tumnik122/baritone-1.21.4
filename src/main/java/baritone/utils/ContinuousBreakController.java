package baritone.utils;

import baritone.Baritone;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import net.minecraft.util.Mth;

/**
 * Lepkość ciągłego niszczenia ("sticky break").
 *
 * Zamiast puszczać CLICK_LEFT przy każdym przejściu blok→blok,
 * trzymamy przycisk RÓWNIEŻ w czasie obrotu kamery do następnego
 * celu — pod warunkiem, że:
 *   1. przed chwilą aktywnie kopiśmy inny blok (okno breakHoldTicks),
 *   2. nowy cel jest blisko ostatnio rozbitego (słupek/linia drzew),
 *   3. kąt obrotu nie przekracza breakHoldMaxAngle.
 *
 * Efekt: słupek kłód koszony jednym ciągłym ruchem kamery od dołu
 * do "głowy" drzewa; linia drzew — jedno ciągłe trzymanie.
 */
public final class ContinuousBreakController {

    private ContinuousBreakController() {}

    private static BetterBlockPos lastBreakPos = null;
    private static int lastBreakTick = Integer.MIN_VALUE;

    /** Wywołuj CO TICK, gdy faktycznie trzymamy przycisk na bloku (celownik trafia). */
    public static void notifyBreaking(IPlayerContext ctx, BetterBlockPos pos) {
        if (pos == null || ctx == null || ctx.player() == null) {
            return;
        }
        lastBreakPos = pos;
        lastBreakTick = ctx.player().tickCount;
    }

    /**
     * Czy przytrzymać CLICK_LEFT PODCZAS obrotu do następnego celu?
     * (czyli: nie puszczać między blokami)
     */
    public static boolean shouldHoldThrough(IPlayerContext ctx, BetterBlockPos nextTarget, Rotation nextRot) {
        if (lastBreakPos == null || ctx == null || ctx.player() == null || nextTarget == null) {
            return false;
        }
        int since = ctx.player().tickCount - lastBreakTick;
        if (since < 0) {
            return false;
        }
        double dist = Math.sqrt(lastBreakPos.distSqr(nextTarget));
        double angle = nextRot != null ? angleBetween(ctx.playerRotations(), nextRot) : 0.0;

        if (FastBreakHelper.isFastBreakActive(ctx) || FastBreakHelper.isFastPickaxe(ctx)) {
            if (since > 6) {
                return false;
            }
            return dist <= 4.5 && (nextRot == null || angle <= 90.0);
        }

        // Natychmiastowe przejście między sąsiadującymi blokami (np. góra/dół w tunelu 1x2, dist <= 1.8 bloku)
        if (dist <= 2.0 && since <= 4) {
            return nextRot == null || angle <= 75.0;
        }

        if (!Baritone.settings().continuousBreaking.value) {
            return false;
        }
        if (since > Baritone.settings().breakHoldTicks.value) {
            return false;
        }
        if (dist > Baritone.settings().breakHoldMaxDistance.value) {
            return false; // następne drzewo za daleko → normalne celowanie
        }
        return nextRot == null || angle <= Baritone.settings().breakHoldMaxAngle.value;
    }

    /** Zeruj przy: braku celu, końcu procesu, zmianie świata. */
    public static void reset() {
        lastBreakPos = null;
        lastBreakTick = Integer.MIN_VALUE;
    }

    private static double angleBetween(Rotation a, Rotation b) {
        double dy = Mth.wrapDegrees(b.getYaw() - a.getYaw());
        double dp = b.getPitch() - a.getPitch();
        return Math.sqrt(dy * dy + dp * dp);
    }
}
