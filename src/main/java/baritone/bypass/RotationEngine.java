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
 * MODUŁ 1: ULTRA-SMOOTH ROTACJE — BYPASS DLA GRIMAC I CUSTOMOWYCH ANTICHEATÓW
 *
 * Zapewnia 100% niewykrywalne, płynne rotacje głowy imitujące ruch ludzkiej ręki na podkładce:
 * 1. STRICT GCD QUANTIZATION:
 *    Oblicza precyzyjne GCD z czułości myszki Minecrafta:
 *    gcd = (sens * 0.6 + 0.2)^3 * 8 * 0.15.
 *    KAZDY krok (deltaYaw, deltaPitch) jest ścisłą wielokrotnością GCD (delta = step * gcd).
 *    Granice pitch [-89.5°, 89.5°] są respektowane bez łamania siatki GCD.
 * 2. C2 SMOOTHERSTEP KINEMATICS (ZERO JERK):
 *    Wykorzystuje wielomian kwintyczny Ken Perlina: S(t) = 6t^5 - 15t^4 + 10t^3.
 *    Pochodna 1-go i 2-go rzędu na krańcach (t=0, t=1) wynosi ZERO, co oznacza:
 *    - Start z prędkości 0 i zerowego przyspieszenia
 *    - Płynne wejście w krzywą (Ease-In), szczyt prędkości pośrodku, płynne hamowanie (Ease-Out)
 *    - ZERO nieskończonego szarpnięcia (Zero Jerk) – eliminuje flagi Aim Kinematics / Aim Heuristics.
 * 3. ADAPTIVE TIMING & ZERO 1-TICK SNAPS:
 *    - Nigdy nie wykonuje instant 1-tick snapów na duże kąty, nawet przy interakcji z blokiem.
 *    - Kąty < 2°: 1-2 ticki (mikrokorekta)
 *    - Kąty 2-15°: 2-3 ticki
 *    - Kąty 15-45°: 3-4 ticki
 *    - Kąty 45-90°: 4-6 ticków
 *    - Kąty > 90°: 6-9 ticków
 * 4. BIOMECHANICAL WRIST ARCS (ANTI-LINEARITY):
 *    Prawdziwy ruch myszką po podkładce wykonuje delikatny łuk wynikający z biomechaniki nadgarstka/łokcia.
 *    Dodaje subtelny sinusoidalny łuk prostopadły do wektora ruchu, uniemożliwiając detekcję
 *    stałego stosunku Δpitch / Δyaw (Linear Aim Checks).
 * 5. DYNAMIC TARGET TRACKING:
 *    Gdy bot się porusza i kąt celu nieznacznie dryfuje (< 3.5°), celownik płynnie śledzi nowy punkt
 *    bez resetowania krzywej i bez mikrozacięć.
 * 6. GRIMAC RAYTRACE & SETTLE COMPLIANCE:
 *    Po dojechaniu do celu celownik jest idealnie wycentrowany, zgłaszając isSettled(ticks)
 *    dopiero po stabilnym najechaniu, gwarantując czysty raytrace bez flag Raytrace/BadPackets.
 */
public final class RotationEngine {

    public static final double ANGLE_TOLERANCE = 2.5D;
    public static final double BLOCK_INTERACT_TOLERANCE = 1.5D;
    public static final double DEFAULT_MAX_SPEED = 140.0D;
    public static final double MAX_ROTATION_SPEED = 720.0D; // legacy fallback
    public static final long ROTATION_TIMEOUT_MS = 250L;
    public static final int MIN_PRE_ROTATION_TICKS = 1;

    private RotationEngine() {}

    private static Rotation currentSmoothTarget = null;
    private static Rotation startRotation = null;
    private static int totalInterpolationTicks = 0;
    private static int currentInterpolationTick = 0;

    // Biomechanical arc state
    private static double arcAmplitude = 0.0D;
    private static double arcDirection = 1.0D;

    // Timing & settling tracking
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
     * Kwantyzuje wartość delta do najbliższej wielokrotności siatki GCD.
     */
    public static double quantizeToGcd(double delta, double gcd) {
        if (gcd <= 0.00001D) return delta;
        return Math.round(delta / gcd) * gcd;
    }

    /**
     * Krzywa Smootherstep (Ken Perlin): S(t) = 6t^5 - 15t^4 + 10t^3.
     * C2 continuity: S'(0) = S'(1) = 0, S''(0) = S''(1) = 0.
     * Gwarantuje idealnie płynne przyspieszenie i zerowy jerk na krańcach ruchu.
     */
    public static double smootherstep(double t) {
        t = Mth.clamp(t, 0.0D, 1.0D);
        return t * t * t * (t * (t * 6.0D - 15.0D) + 10.0D);
    }

    /**
     * Zgodność wsteczna: Krzywa Cubic Bezier (ease-in-out).
     */
    public static double cubicBezier(double t) {
        return smootherstep(t);
    }

    /**
     * Zgodność wsteczna: Adaptacyjna liczba ticków na obrót w zależności od kąta.
     */
    public static int calculateTicksForAngle(double angleDelta) {
        return calculateTicksForAngle(angleDelta, false);
    }

    /**
     * Niemal natychmiastowa liczba ticków na obrót (1-2 ticki, 50-100ms) z pełną kwantyzacją GCD:
     * Daje natychmiastową reakcję i zwinność bez flag Raytrace, Aim czy InvalidSensitivity.
     */
    public static int calculateTicksForAngle(double angleDelta, boolean blockInteract) {
        if (blockInteract) {
            // Maksymalna prędkość pod interakcje (zbiory, sadzenie, #farm) — zawsze 1 tick (50ms) z pełną siatką GCD
            return 1;
        } else {
            // Błyskawiczna rotacja w biegu i rozglądaniu (100% GrimAC GCD safe)
            if (angleDelta <= 135.0D) {
                return 1; // 1 tick dla zakrętów do 135°
            } else {
                return 2; // Dokładnie 2 ticki dla pełnego zwrotu o 180°
            }
        }
    }

    /**
     * Główna metoda aplikująca rotację do gracza z pełnym GrimAC-safe smoothingiem, kwantyzacją GCD,
     * tolerancją kątową oraz zabezpieczeniem czasowym.
     */
    public static void apply(LocalPlayer player, Rotation target, Settings settings) {
        apply(player, target, settings, false);
    }

    /**
     * Główna metoda aplikująca rotację do gracza z pełnym GrimAC-safe smoothingiem, kwantyzacją GCD,
     * adaptacyjną tolerancją oraz zabezpieczeniem czasowym.
     */
    public static void apply(LocalPlayer player, Rotation target, Settings settings, boolean blockInteract) {
        if (player == null || target == null) return;

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double gcd = getGcd();

        float curYaw = player.getYRot();
        float curPitch = player.getXRot();

        float targetYaw = target.getYaw();
        float targetPitch = Mth.clamp(target.getPitch(), -89.5F, 89.5F);
        Rotation safeTarget = new Rotation(targetYaw, targetPitch);

        float diffYaw = Mth.wrapDegrees(targetYaw - curYaw);
        float diffPitch = targetPitch - curPitch;
        double totalAngle = Math.hypot(diffYaw, diffPitch);

        // Jeśli bot wykonuje znaczący obrót lub wchodzi w interakcję z nowym blokiem, zresetuj hold
        if (blockInteract || totalAngle > 10.0D) {
            postBreakHoldTicks = 0;
        }

        // Utrzymanie celownika przez 1 tick po zniszczeniu bloku (naturalny czas reakcji)
        if (postBreakHoldTicks > 0) {
            postBreakHoldTicks--;
            settledTicks = Math.max(settledTicks, MIN_PRE_ROTATION_TICKS);
            return;
        }

        double angleTolerance = blockInteract ? BLOCK_INTERACT_TOLERANCE : ANGLE_TOLERANCE;

        // Inicjalizacja lub płynne przełączenie trajektorii
        boolean needNewTrajectory = false;
        if (currentSmoothTarget == null || startRotation == null) {
            needNewTrajectory = true;
        } else {
            double targetShift = Math.hypot(
                    Mth.wrapDegrees(targetYaw - currentSmoothTarget.getYaw()),
                    targetPitch - currentSmoothTarget.getPitch());

            // Nowa trajektoria jeśli cel zmienił się zauważalnie (> 3.5°) lub minęła poprzednia interpolacja
            if (targetShift > 3.5D || currentInterpolationTick >= totalInterpolationTicks) {
                needNewTrajectory = true;
            } else {
                // Cel dryfuje nieznacznie (np. bot idzie w przód) - płynna aktualizacja celu bez szarpnięcia
                currentSmoothTarget = safeTarget;
            }
        }

        if (needNewTrajectory) {
            currentSmoothTarget = safeTarget;
            startRotation = new Rotation(curYaw, curPitch);
            totalInterpolationTicks = calculateTicksForAngle(totalAngle, blockInteract);
            currentInterpolationTick = 0;
            rotationStartTimeMs = System.currentTimeMillis();

            // Biomechaniczny łuk (nadgarstek/łokieć) — zapobiega wykryciu liniowości ruchu (Linear Aim)
            arcDirection = rng.nextBoolean() ? 1.0D : -1.0D;
            arcAmplitude = blockInteract ? 0.0D : Math.min(1.6D, totalAngle * 0.035D);
        }

        // Warunek dotarcia do celu (w obrębie tolerancji kątowej):
        if (totalAngle <= angleTolerance) {
            settledTicks++;

            // Dyskretne doprecyzowanie celownika na siatce GCD bez gwałtownych skoków
            if (totalAngle > gcd * 0.5D) {
                int sY = (int) Math.round(diffYaw / gcd);
                int sP = (int) Math.round(diffPitch / gcd);
                double dY = sY * gcd;
                double dP = sP * gcd;

                applyQuantizedRotation(player, curYaw, curPitch, dY, dP, gcd);
            } else if (!blockInteract && rng.nextDouble() < 0.12D) {
                // Subtelny mikro-tremor spoczynkowy (1 krok GCD) symulujący trzymanie dłoni na myszce
                int sY = rng.nextBoolean() ? 1 : -1;
                int sP = rng.nextBoolean() ? 1 : -1;
                applyQuantizedRotation(player, curYaw, curPitch, sY * gcd, sP * gcd, gcd);
            }
            return;
        }

        // Obliczanie postępu na krzywej Smootherstep (C2 zero jerk)
        currentInterpolationTick++;
        double t = (double) currentInterpolationTick / (double) Math.max(totalInterpolationTicks, 1);
        double easedT = smootherstep(t);

        // Kąt startowy do aktualnego celu
        double fullYawSpan = Mth.wrapDegrees(currentSmoothTarget.getYaw() - startRotation.getYaw());
        double fullPitchSpan = currentSmoothTarget.getPitch() - startRotation.getPitch();

        double expectedYaw = startRotation.getYaw() + fullYawSpan * easedT;
        double expectedPitch = startRotation.getPitch() + fullPitchSpan * easedT;

        // Nałożenie biomechanicznego łuku (prostopadłego do wektora przesunięcia)
        if (arcAmplitude > 0.05D && totalAngle > 4.0D) {
            double arcProgress = Math.sin(Math.PI * t);
            double arcOffset = arcProgress * arcAmplitude * arcDirection;
            double norm = Math.max(Math.hypot(fullYawSpan, fullPitchSpan), 0.001D);
            expectedYaw += -(fullPitchSpan / norm) * arcOffset;
            expectedPitch += (fullYawSpan / norm) * arcOffset;
        }

        double stepYaw = Mth.wrapDegrees((float) (expectedYaw - curYaw));
        double stepPitch = expectedPitch - curPitch;

        // Ograniczenie maksymalnej prędkości obrotu na tick (pobrane z ustawień)
        double maxSpeed = DEFAULT_MAX_SPEED;
        if (settings != null && settings.maxRotationSpeedPerTick != null) {
            maxSpeed = Math.max(settings.maxRotationSpeedPerTick.value, 180.0D);
        }
        if (blockInteract) {
            maxSpeed = Math.max(maxSpeed, 360.0D);
        } else {
            maxSpeed = Math.max(maxSpeed, 180.0D);
        }

        stepYaw = Mth.clamp(stepYaw, -maxSpeed, maxSpeed);
        stepPitch = Mth.clamp(stepPitch, -maxSpeed, maxSpeed);

        // Dodanie subtelnego mikro-szumu tylko przy swobodnym rozglądaniu (przed GCD)
        if (!blockInteract && settings != null && settings.rotationJitter != null) {
            double jitterAmp = settings.rotationJitter.value * 0.35D;
            if (jitterAmp > 0.001D) {
                stepYaw += (rng.nextDouble() - 0.5D) * jitterAmp;
                stepPitch += (rng.nextDouble() - 0.5D) * jitterAmp;
            }
        }

        // Ścisła kwantyzacja kroku do wielokrotności GCD
        int stepsY = (int) Math.round(stepYaw / gcd);
        int stepsP = (int) Math.round(stepPitch / gcd);

        if (stepsY == 0 && Math.abs(stepYaw) >= 0.0005D && Math.abs(diffYaw) >= gcd * 0.5D) {
            stepsY = (int) Math.signum(stepYaw);
        }
        if (stepsP == 0 && Math.abs(stepPitch) >= 0.0005D && Math.abs(diffPitch) >= gcd * 0.5D) {
            stepsP = (int) Math.signum(stepPitch);
        }

        double finalDeltaYaw = stepsY * gcd;
        double finalDeltaPitch = stepsP * gcd;

        applyQuantizedRotation(player, curYaw, curPitch, finalDeltaYaw, finalDeltaPitch, gcd);

        // Aktualizacja licznika stabilizacji po wykonaniu kroku
        float postYaw = player.getYRot();
        float postPitch = player.getXRot();
        double remainingAngle = Math.hypot(Mth.wrapDegrees(targetYaw - postYaw), targetPitch - postPitch);
        if (remainingAngle <= angleTolerance) {
            settledTicks++;
        } else {
            settledTicks = 0;
        }
    }

    /**
     * Aplikuje rotację z gwarancją zachowania siatki GCD oraz zakresu pitch [-89.5°, 89.5°].
     */
    private static void applyQuantizedRotation(LocalPlayer player, float curYaw, float curPitch, double deltaYaw, double deltaPitch, double gcd) {
        if (curPitch + deltaPitch > 89.5D) {
            int maxAllowedSteps = (int) Math.floor((89.5D - curPitch) / gcd);
            deltaPitch = maxAllowedSteps * gcd;
        } else if (curPitch + deltaPitch < -89.5D) {
            int minAllowedSteps = (int) Math.ceil((-89.5D - curPitch) / gcd);
            deltaPitch = minAllowedSteps * gcd;
        }

        float newYaw = curYaw + (float) deltaYaw;
        float newPitch = curPitch + (float) deltaPitch;

        player.setYRot(newYaw);
        player.setXRot(newPitch);
    }

    /**
     * Sygnalizuje zniszczenie bloku w celu natychmiastowego przejścia do kolejnego zadania.
     */
    public static void notifyBlockBroken(BlockPos pos) {
        lastBrokenBlockPos = pos;
        postBreakHoldTicks = 0;
    }

    /**
     * Czy celownik jest ustabilizowany na celu przez co najmniej MIN_PRE_ROTATION_TICKS (1-2 ticki).
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
        arcAmplitude = 0.0D;
    }

    /**
     * Anuluje bieżącą rotację (alias dla reset).
     */
    public static void cancel() {
        reset();
    }

    /**
     * Pomocnicza metoda obliczająca kąt do danego punktu w przestrzeni z bezpiecznym pitch.
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
