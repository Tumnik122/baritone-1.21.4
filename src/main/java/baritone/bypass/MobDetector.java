package baritone.bypass;

import baritone.api.utils.IPlayerContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;

import java.util.Comparator;

/**
 * Moduł detekcji wrogich mobów i zarządzania strefami bezpieczeństwa.
 * - mob_radius: 16
 * - mob_stop: 8 (zatrzymanie kopania)
 * - mob_retreat: 4 (wycofanie się)
 * - mob_resume: 12 (wznowienie pracy)
 * - health_threshold: 10.0 HP (5 serc)
 * - mob_dc_distance: 20 bloków (awaryjne rozłączenie)
 */
public final class MobDetector {

    private MobDetector() {}

    /**
     * Zwraca najbliższego wrogiego moba w zadanym promieniu.
     */
    public static Entity getNearestHostile(IPlayerContext ctx, double maxRadius) {
        if (ctx == null || ctx.player() == null) return null;
        double maxRadiusSq = maxRadius * maxRadius;

        return ctx.entitiesStream()
                .filter(e -> e instanceof Enemy)
                .filter(e -> e.isAlive() && !e.isSpectator())
                .filter(e -> e.distanceToSqr(ctx.player()) <= maxRadiusSq)
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(ctx.player())))
                .orElse(null);
    }

    /**
     * Zwraca odległość do najbliższego wrogiego moba.
     */
    public static double getNearestHostileDistance(IPlayerContext ctx) {
        Entity enemy = getNearestHostile(ctx, 32.0D);
        if (enemy == null || ctx.player() == null) return Double.MAX_VALUE;
        return enemy.distanceTo(ctx.player());
    }

    /**
     * Sprawdza warunek awaryjnego rozłączenia (HP <= threshold i wrogi mob <= dcDistance).
     */
    public static boolean shouldEmergencyDisconnect(IPlayerContext ctx, double healthThreshold, double dcDistance) {
        if (ctx == null || ctx.player() == null) return false;
        if (ctx.player().getHealth() > healthThreshold) return false;

        Entity enemy = getNearestHostile(ctx, dcDistance);
        return enemy != null;
    }

    /**
     * Czy mob jest w strefie zatrzymania kopania (<= mob_stop).
     */
    public static boolean isMobInStopRange(IPlayerContext ctx, double stopRange) {
        return getNearestHostile(ctx, stopRange) != null;
    }

    /**
     * Czy mob jest w strefie ucieczki (<= mob_retreat).
     */
    public static boolean isMobInRetreatRange(IPlayerContext ctx, double retreatRange) {
        return getNearestHostile(ctx, retreatRange) != null;
    }

    /**
     * Czy mob oddalił się na bezpieczną odległość wznowienia (>= mob_resume).
     */
    public static boolean isSafeToResume(IPlayerContext ctx, double resumeRange) {
        return getNearestHostile(ctx, resumeRange) == null;
    }
}
