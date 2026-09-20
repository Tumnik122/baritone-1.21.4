package baritone.bypass;

import baritone.Baritone;
import baritone.api.Settings;
import baritone.api.utils.Rotation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ThreadLocalRandom;

/**
 * MODUŁ 1: ROTACJE — BYPASS GRIMAC
 *
 * Zapewnia pełną zgodność z detekcją GrimAC:
 * 1. GCD-COMPLIANT ROTATIONS: Oblicza GCD z aktualnego sensitivity Minecrafta.
 *    Wszystkie delty rotacji (yaw & pitch) są kwantyzowane do wielokrotności GCD.
 * 2. PŁYNNIE ROZŁOŻONE: Płynna interpolacja Cubic Bezier rozłożona na 3-8 ticków.
 *    Maksymalna prędkość: ~360°/s (18°/tick).
 * 3. TIMING: Pre-rotation ticks (min 2 ticki przed rozpoczęciem kopania)
 *    oraz post-rotation ticks (utrzymanie rotacji 1-2 ticki po zniszczeniu bloku).
 * 4. JITTER: Losowa mikrokorekta ±0.1° na krok, również skwantyzowana do siatki GCD.
 */
public final class RotationEngine {

    private RotationEngine() {}

    private static Rotation currentSmoothTarget = null;
    private static Rotation startRotation = null;
    private static int totalInterpolationTicks = 0;
    private static int currentInterpolationTick = 0;

    // Timing tracking
    private static int settledTicks = 0;
    private static int postBreakHoldTicks = 0;
    private static BlockPos lastBrokenBlockPos = null;

    /**
     * Oblicza GCD z aktualnego mouse sensitivity Minecrafta.
     * W vanilla Minecraft:
     * float f = sensitivity * 0.6F + 0.2F;
     * float gcd = f * f * f * 8.0F * 0.15F;
     */
    public static double getGcd() {
        double sensitivity = 0.5D;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.options != null) {
                sensitivity = mc.options.sensitivity().get();
            }
        } catch (Throwable ignored) {}

        double f = sensitivity * 0.6D + 0.2D;
        double gcd = f * f * f * 8.0D * 0.15D;
        return Math.max(gcd, 0.0001D);
    }

    /**
     * Kwantyzuje wartość do najbliższej wielokrotności siatki GCD.
     */
    public static double quantizeToGcd(double value, double gcd) {
        if (gcd <= 0.00001D) return value;
        return Math.round(value / gcd) * gcd;
    }

    /**
     * Krzywa Cubic Bezier (ease-in-out).
     */
    public static double cubicBezier(double t) {
        t = Mth.clamp(t, 0.0D, 1.0D);
        // Standardowa łagodna krzywa S: 3t^2 - 2t^3 (lub bezier 0.25, 0.1, 0.25, 1.0)
        return t * t * (3.0D - 2.0D * t);
    }

    /**
     * Oblicza liczbę ticków (3-8) zależnie od całkowitego kąta obrotu.
     */
    public static int calculateTicksForAngle(double angleDelta) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        if (angleDelta < 15.0D) {
            return 3;
        } else if (angleDelta < 45.0D) {
            return 4 + rng.nextInt(2); // 4-5 ticków
        } else if (angleDelta < 90.0D) {
            return 6 + rng.nextInt(2); // 6-7 ticków
        } else {
            return 8;
        }
    }

    /**
     * Główna metoda aplikująca rotację do gracza z pełnym GrimAC-safe smoothingiem i kwantyzacją GCD.
     */
    public static void apply(LocalPlayer player, Rotation target, Settings settings) {
        if (player == null || target == null) return;

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double gcd = getGcd();

        float curYaw = player.getYRot();
        float curPitch = player.getXRot();

        float diffYaw = Mth.wrapDegrees(target.getYaw() - curYaw);
        float diffPitch = target.getPitch() - curPitch;
        double totalAngle = Math.hypot(diffYaw, diffPitch);

        // Utrzymanie rotacji po zniszczeniu bloku (post-rotation hold)
        if (postBreakHoldTicks > 0) {
            postBreakHoldTicks--;
            settledTicks++;
            return;
        }

        // Jeśli cel się zmienił o więcej niż 3°, resetujemy interpolację na nowo
        if (currentSmoothTarget == null || Math.hypot(
                Mth.wrapDegrees(target.getYaw() - currentSmoothTarget.getYaw()),
                target.getPitch() - currentSmoothTarget.getPitch()) > 3.0D) {
            currentSmoothTarget = target;
            startRotation = new Rotation(curYaw, curPitch);
            totalInterpolationTicks = calculateTicksForAngle(totalAngle);
            currentInterpolationTick = 0;
            settledTicks = 0;
        }

        // Gdy jesteśmy już bardzo blisko celu (< 0.8°)
        if (totalAngle <= 0.8D) {
            settledTicks++;
            // Mikrokorekta jitter skwantyzowana do GCD
            double jx = (rng.nextDouble() - 0.5D) * 0.1D;
            double jy = (rng.nextDouble() - 0.5D) * 0.1D;
            jx = quantizeToGcd(jx, gcd);
            jy = quantizeToGcd(jy, gcd);

            player.setYRot((float) (Mth.wrapDegrees(target.getYaw() + (float) jx)));
            player.setXRot((float) Mth.clamp(target.getPitch() + (float) jy, -90.0F, 90.0F));
            return;
        }

        currentInterpolationTick++;
        double t = (double) currentInterpolationTick / (double) Math.max(totalInterpolationTicks, 1);
        double easedT = cubicBezier(t);

        // Docelowa delta w tym kroku
        double targetYawDelta = Mth.wrapDegrees(currentSmoothTarget.getYaw() - startRotation.getYaw()) * easedT;
        double targetPitchDelta = (currentSmoothTarget.getPitch() - startRotation.getPitch()) * easedT;

        double expectedYaw = startRotation.getYaw() + targetYawDelta;
        double expectedPitch = startRotation.getPitch() + targetPitchDelta;

        double stepYaw = Mth.wrapDegrees((float) (expectedYaw - curYaw));
        double stepPitch = expectedPitch - curPitch;

        // Ograniczenie maksymalnej prędkości: ~360°/sekundę (~18°/tick)
        double maxStepPerTick = 18.0D;
        stepYaw = Mth.clamp(stepYaw, -maxStepPerTick, maxStepPerTick);
        stepPitch = Mth.clamp(stepPitch, -maxStepPerTick, maxStepPerTick);

        // Losowy jitter (symulacja drżenia ręki) ±0.1°
        double jitterYaw = (rng.nextDouble() - 0.5D) * 0.1D;
        double jitterPitch = (rng.nextDouble() - 0.5D) * 0.1D;
        stepYaw += jitterYaw;
        stepPitch += jitterPitch;

        // Snap do siatki GCD
        stepYaw = quantizeToGcd(stepYaw, gcd);
        stepPitch = quantizeToGcd(stepPitch, gcd);

        float newYaw = (float) Mth.wrapDegrees(curYaw + stepYaw);
        float newPitch = (float) Mth.clamp(curPitch + stepPitch, -90.0D, 90.0D);

        player.setYRot(newYaw);
        player.setXRot(newPitch);

        if (Math.hypot(Mth.wrapDegrees(target.getYaw() - newYaw), target.getPitch() - newPitch) <= 1.2D) {
            settledTicks++;
        } else {
            settledTicks = 0;
        }
    }

    /**
     * Sygnalizuje zniszczenie bloku w celu utrzymania rotacji przez 1-2 ticki (post-rotation).
     */
    public static void notifyBlockBroken(BlockPos pos) {
        lastBrokenBlockPos = pos;
        postBreakHoldTicks = 2; // utrzymanie 2 ticki
    }

    /**
     * Czy celownik jest ustabilizowany na celu przez co najmniej zadana liczbe tickow (pre-rotation).
     */
    public static boolean isSettled(int requiredTicks) {
        return settledTicks >= requiredTicks;
    }

    /**
     * Resetuje stan rotacji.
     */
    public static void reset() {
        currentSmoothTarget = null;
        startRotation = null;
        totalInterpolationTicks = 0;
        currentInterpolationTick = 0;
        settledTicks = 0;
        postBreakHoldTicks = 0;
    }

    /**
     * Pomocnicza metoda obliczająca kąt do danego punktu w przestrzeni.
     */
    public static Rotation lookAt(Vec3 eye, Vec3 point) {
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
        return new Rotation(Mth.wrapDegrees(yaw), Mth.clamp(pitch, -89.5F, 89.5F));
    }
}
