package baritone.behavior;

import baritone.Baritone;

/**
 * Konfiguracja specyficzna dla GrimAC compatibility.
 * Wszystkie wartości są dostrajane tak, aby nie wywoływać alertów.
 */
public final class GrimCompatSettings {

    // === ROTATION ===
    /** Czułość myszki używana do obliczenia GCD. Musi odpowiadać rzeczywistej wartości gracza. */
    public float mouseSensitivity = 0.5F;

    /** Minimalna liczba ticków na obrót (Grim podejrzewa instant-snap). */
    public int minRotationTicks = 2;

    /** Maksymalna liczba ticków na obrót. */
    public int maxRotationTicks = 6;

    /** Maksymalny kąt na tick (stopnie). Powyżej tego Grim może flagować. */
    public float maxAnglePerTick = 45.0F;

    /** Mikro-jitter yaw (± stopnie). */
    public float jitterYaw = 0.1F;

    /** Mikro-jitter pitch (± stopnie). */
    public float jitterPitch = 0.05F;

    // === MOVEMENT ===
    /** Bezpieczny maksymalny zasięg kopania (kratki). Waniliowy to 4.5, Grim sprawdza 4.0+ε. */
    public float maxReach = 4.0F;

    /** Bezpieczny maksymalny zasięg stawiania bloków. */
    public float maxBlockPlaceReach = 4.0F;

    /** Czy wymuszać sprint tylko na ziemi. */
    public boolean strictSprint = true;

    /** Czy walidować step height (0.6). */
    public boolean strictStep = true;

    /** Czy walidować onGround flag. */
    public boolean strictOnGround = true;

    // === BREAKING ===
    /** Co ile ticków wysyłać swing packet. Wanilia to ~co 5 ticków. */
    public int swingIntervalTicks = 5;

    /** Czy używać bezpiecznego reach (4.0) zamiast waniliowego (4.5). */
    public boolean useSafeReach = true;

    /** Czy walidować LOS przed rozpoczęciem kopania. */
    public boolean validateLineOfSight = true;

    // === PLACEMENT ===
    /** Czy wysyłać interakcję z odpowiednią sekwencją pakietów. */
    public boolean strictPacketOrder = true;

    /** Opóźnienie między start a finish bloku (ticki) — imituje człowieka. */
    public int blockPlaceDelay = 2;

    private static GrimCompatSettings instance;

    public static GrimCompatSettings getInstance() {
        if (instance == null) {
            instance = new GrimCompatSettings();
        }
        try {
            if (Baritone.settings() != null) {
                instance.mouseSensitivity = Baritone.settings().grimMouseSensitivity.value;
                instance.maxReach = Baritone.settings().grimSafeReach.value.floatValue();
                instance.maxBlockPlaceReach = Baritone.settings().grimSafeReach.value.floatValue();
                instance.strictSprint = Baritone.settings().grimStrictSprint.value;
                instance.blockPlaceDelay = Baritone.settings().grimBlockPlaceDelay.value;
            }
        } catch (Throwable ignored) {}
        return instance;
    }
}
