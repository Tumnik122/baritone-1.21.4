package baritone.bypass.mine;

import net.minecraft.util.Mth;

/**
 * Siatka rotacji osiągalnych myszą. W 1.21.4 jeden "count" myszy to:
 *   f = sens * 0.6 + 0.2;   krok = f^3 * 8 * 0.15  [stopnie]
 * Realna rotacja = start + k * krok, więc celujemy tylko w punkty tej siatki
 * (inaczej Grim widzi rotację z niewłaściwym GCD, a nasz "trafiający" promień
 * po kwantyzacji na serwerze może już nie trafiać w pożądany punkt).
 */
public record RotationGrid(double stepDeg) {

    public static RotationGrid fromSensitivity(double sensitivity) {
        double f = sensitivity * 0.6 + 0.2;
        return new RotationGrid(f * f * f * 8.0 * 0.15);
    }

    public float snapYaw(float current, float wanted) {
        double d = Mth.wrapDegrees(wanted - current);
        return (float) (current + Math.rint(d / stepDeg) * stepDeg);
    }

    public float snapPitch(float current, float wanted) {
        double d = wanted - current;
        return Mth.clamp((float) (current + Math.rint(d / stepDeg) * stepDeg), -90f, 90f);
    }
}
