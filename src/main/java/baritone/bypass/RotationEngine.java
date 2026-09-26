package baritone.bypass;

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
 * 2. PŁYNNIE ROZŁOŻONE: Płynna interpolacja Cubic Bezier z adaptacyjną prędkością:
 *    - Mały kąt (<30°) -> 1 tick, pełna prędkość
 *    - Średni kąt (30-90°) -> 2-3 ticki
 *    - Duży kąt (>90°) -> 3-5 ticków
 * 3. ANGLE_TOLERANCE = 3.0°: Rotacja kończy się gdy jest blisko celu.
 * 4. ROTATION_TIMEOUT_MS = 200: Wymuszenie zakończenia rotacji po 200ms.
 * 5. MAX_ROTATION_SPEED = 720°/s (36°/tick).
 * 6. MIN_PRE_ROTATION_TICKS = 1 tick stabilizacji przed rozpoczęciem kopania.
 */
public final class RotationEngine {

    public static final double ANGLE_TOLERANCE = 3.0D;
    public static final long ROTATION_TIMEOUT_MS = 200L;
    public static final double MAX_ROTATION_SPEED = 720.0D;
    public static final int MIN_PRE_ROTATION_TICKS = 1;

    private RotationEngine() {}

    private static Rotation currentSmoothTarget = null;
    private static Rotation startRotation = null;
    private static int totalInterpolationTicks = 0;
    private static int currentInterpolationTick = 0;

    // Timing tracking
    private static int settledTicks = 0;
    private static int postBreakHoldTicks = 0;
    private static BlockPos lastBrokenBlockPos = null;
    private static long rotationStartTimeMs = 0L;

    /**
     * Oblicza GCD z aktualnego mouse sensitivity Minecrafta.
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
        return t * t * (3.0D - 2.0D * t);
    }

    /**
     * Adaptacyjna prędkość obrotu:
     * - Mały kąt (<30°) → 1 tick, full speed
     * - Średni kąt (30-90°) → 2-3 ticki
     * - Duży kąt (>90°) → 3-5 ticków
     */
    public static int calculateTicksForAngle(double angleDelta) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        if (angleDelta < 30.0D) {
            return 1;
        } else if (angleDelta < 90.0D) {
            return 2 + rng.nextInt(2); // 2-3 ticki
        } else {
            return 3 + rng.nextInt(3); // 3-5 ticków
        }
    }

    /**
     * Główna metoda aplikująca rotację do gracza z pełnym GrimAC-safe smoothingiem, kwantyzacją GCD,
     * tolerancją ANGLE_TOLERANCE (3.0°) oraz zabezpieczeniem czasowym ROTATION_TIMEOUT_MS (200ms).
     */
    public static void apply(LocalPlayer player, Rotation target, Settings settings) {
        apply(player, target, settings, false);
    }

    /**
     * Główna metoda aplikująca rotację do gracza z pełnym GrimAC-safe smoothingiem, kwantyzacją GCD,
     * adaptacyjną tolerancją oraz zabezpieczeniem czasowym ROTATION_TIMEOUT_MS (200ms).
     */
    public static void apply(LocalPlayer player, Rotation target, Settings settings, boolean blockInteract) {
        if (player == null || target == null) return;

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double gcd = getGcd();

        float curYaw = player.getYRot();
        float curPitch = player.getXRot();

        float diffYaw = Mth.wrapDegrees(target.getYaw() - curYaw);
        float diffPitch = target.getPitch() - curPitch;
        double totalAngle = Math.hypot(diffYaw, diffPitch);

        // Jeśli bot wchodzi w interakcję z blokiem lub wykonuje duży obrót, anuluj hold po zniszczeniu
        if (blockInteract || totalAngle > 10.0D) {
            postBreakHoldTicks = 0;
        }

        // Utrzymanie rotacji po zniszczeniu bloku (post-rotation hold)
        if (postBreakHoldTicks > 0) {
            postBreakHoldTicks--;
            settledTicks = 0;
            return;
        }

        double angleTolerance = blockInteract ? 0.35D : ANGLE_TOLERANCE;

        // Nowy cel lub zmiana celu o ponad 20° (nie restartuj pętli przy drobnych wahaniach celownika na bloku)
        if (currentSmoothTarget == null || Math.hypot(
                Mth.wrapDegrees(target.getYaw() - currentSmoothTarget.getYaw()),
                target.getPitch() - currentSmoothTarget.getPitch()) > 20.0D) {
            currentSmoothTarget = target;
            startRotation = new Rotation(curYaw, curPitch);
            totalInterpolationTicks = blockInteract ? (totalAngle < 60.0D ? 1 : 2) : calculateTicksForAngle(totalAngle);
            currentInterpolationTick = 0;
            settledTicks = 0;
            rotationStartTimeMs = System.currentTimeMillis();
        } else {
            // Płynna aktualizacja celu bez resetowania postępu interpolacji
            currentSmoothTarget = target;
        }

        // ROTATION_TIMEOUT_MS (200ms) force complete — bot nie może wisieć na rotacji
        long elapsedMs = System.currentTimeMillis() - rotationStartTimeMs;
        if (rotationStartTimeMs > 0L && elapsedMs >= ROTATION_TIMEOUT_MS) {
            double snapYaw = quantizeToGcd(target.getYaw() - curYaw, gcd);
            double snapPitch = quantizeToGcd(target.getPitch() - curPitch, gcd);
            player.setYRot((float) Mth.wrapDegrees(curYaw + snapYaw));
            player.setXRot((float) Mth.clamp(curPitch + snapPitch, -90.0F, 90.0F));
            settledTicks = Math.max(settledTicks + 1, MIN_PRE_ROTATION_TICKS);
            return;
        }

        // Tolerancja celu: po dotarciu ustawiamy dokładną rotację skwantyzowaną do GCD
        if (totalAngle <= angleTolerance) {
            settledTicks++;
            if (!blockInteract) {
                // Mikrokorekta jitter skwantyzowana do GCD przy swobodnym rozglądaniu
                double jx = (rng.nextDouble() - 0.5D) * 0.05D;
                double jy = (rng.nextDouble() - 0.5D) * 0.05D;
                jx = quantizeToGcd(jx, gcd);
                jy = quantizeToGcd(jy, gcd);

                player.setYRot((float) (Mth.wrapDegrees(target.getYaw() + (float) jx)));
                player.setXRot((float) Mth.clamp(target.getPitch() + (float) jy, -90.0F, 90.0F));
            } else {
                // Precyzyjny snap na powierzchnię bloku (zgodny z siatką GCD)
                double dY = quantizeToGcd(diffYaw, gcd);
                double dP = quantizeToGcd(diffPitch, gcd);
                player.setYRot((float) Mth.wrapDegrees(curYaw + (float) dY));
                player.setXRot((float) Mth.clamp(curPitch + (float) dP, -90.0F, 90.0F));
            }
            return;
        }

        currentInterpolationTick++;
        double t = (double) currentInterpolationTick / (double) Math.max(totalInterpolationTicks, 1);
        double easedT = cubicBezier(t);

        double targetYawDelta = Mth.wrapDegrees(currentSmoothTarget.getYaw() - startRotation.getYaw()) * easedT;
        double targetPitchDelta = (currentSmoothTarget.getPitch() - startRotation.getPitch()) * easedT;

        double expectedYaw = startRotation.getYaw() + targetYawDelta;
        double expectedPitch = startRotation.getPitch() + targetPitchDelta;

        double stepYaw = Mth.wrapDegrees((float) (expectedYaw - curYaw));
        double stepPitch = expectedPitch - curPitch;

        // Ograniczenie prędkości: przy interakcji z blokiem pozwalamy na szybszy snap (1200°/s)
        double maxSpeed = blockInteract ? 1200.0D : MAX_ROTATION_SPEED;
        double maxStepPerTick = maxSpeed / 20.0D;
        stepYaw = Mth.clamp(stepYaw, -maxStepPerTick, maxStepPerTick);
        stepPitch = Mth.clamp(stepPitch, -maxStepPerTick, maxStepPerTick);

        if (!blockInteract) {
            // Losowy jitter tylko przy swobodnym rozglądaniu
            double jitterYaw = (rng.nextDouble() - 0.5D) * 0.05D;
            double jitterPitch = (rng.nextDouble() - 0.5D) * 0.05D;
            stepYaw += jitterYaw;
            stepPitch += jitterPitch;
        }

        // Snap do siatki GCD
        stepYaw = quantizeToGcd(stepYaw, gcd);
        stepPitch = quantizeToGcd(stepPitch, gcd);

        float newYaw = (float) Mth.wrapDegrees(curYaw + stepYaw);
        float newPitch = (float) Mth.clamp(curPitch + stepPitch, -90.0D, 90.0D);

        player.setYRot(newYaw);
        player.setXRot(newPitch);

        if (Math.hypot(Mth.wrapDegrees(target.getYaw() - newYaw), target.getPitch() - newPitch) <= angleTolerance) {
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
        postBreakHoldTicks = 2;
    }

    /**
     * Czy celownik jest ustabilizowany na celu przez co najmniej MIN_PRE_ROTATION_TICKS (1 tick),
     * lub upłynął timeout ROTATION_TIMEOUT_MS (200ms).
     */
    public static boolean isSettled(int requiredTicks) {
        int req = Math.max(requiredTicks, MIN_PRE_ROTATION_TICKS);
        return settledTicks >= req;
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
        rotationStartTimeMs = 0L;
    }

    /**
     * Anuluje bieżącą rotację (alias dla reset).
     */
    public static void cancel() {
        reset();
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
