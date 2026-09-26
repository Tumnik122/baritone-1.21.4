package baritone.utils.builder;

import baritone.Baritone;
import baritone.api.Settings;
import baritone.api.utils.Rotation;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ThreadLocalRandom;

/**
 * IMPROVEMENT 4 — GrimAC-safe rotation.
 *
 * Never rotates more than maxRotationStepDegrees per tick, adds a tiny amount
 * of noise so the rotation curve doesn't look constant-speed, and snaps
 * (with ±0.05° micro-noise) once within 1° so the builder can rely on the
 * final orientation for directional placement.
 */
public final class SmoothLookHelper {

    private SmoothLookHelper() {}

    public static void apply(LocalPlayer player, Rotation target) {
        apply(player, target, Baritone.settings(), false);
    }

    public static void apply(LocalPlayer player, Rotation target, Settings settings) {
        apply(player, target, settings, false);
    }

    public static void apply(LocalPlayer player, Rotation target, Settings settings, boolean blockInteract) {
        if (settings.antiCheatCompat.value) {
            baritone.bypass.RotationEngine.apply(player, target, settings, blockInteract);
            return;
        }

        ThreadLocalRandom rng = ThreadLocalRandom.current();

        float maxStep = blockInteract ? Math.max(settings.maxRotationStepDegrees.value, 60.0f) : settings.maxRotationStepDegrees.value;
        float noise = blockInteract ? 0f : settings.rotationNoiseDegrees.value.floatValue();
        float curYaw = player.getYRot();
        float curPitch = player.getXRot();

        float dYaw = Mth.wrapDegrees(target.getYaw() - curYaw);
        float dPitch = target.getPitch() - curPitch;

        // Arrived: set exactly (+ micro noise only if not blockInteract)
        if (Math.abs(dYaw) <= (blockInteract ? 0.35f : 1.0f) && Math.abs(dPitch) <= (blockInteract ? 0.35f : 1.0f)) {
            float ny = (!blockInteract && noise > 0) ? (float) rng.nextDouble(-0.05, 0.05) : 0f;
            float np = (!blockInteract && noise > 0) ? (float) rng.nextDouble(-0.05, 0.05) : 0f;
            player.setYRot(Mth.wrapDegrees(target.getYaw() + ny));
            player.setXRot(Mth.clamp(target.getPitch() + np, -90.0f, 90.0f));
            return;
        }

        // Randomize step magnitude a bit (0.82..1.18x) so speed isn't constant
        float yawScale = blockInteract ? 1.0f : (0.82f + rng.nextFloat() * 0.36f);
        float pitchScale = blockInteract ? 1.0f : (0.82f + rng.nextFloat() * 0.36f);
        float yawStep = Math.copySign(Math.min(Math.abs(dYaw), maxStep * yawScale), dYaw);
        float pitchStep = Math.copySign(Math.min(Math.abs(dPitch), maxStep * 0.75f * pitchScale), dPitch);

        float noiseYaw = (!blockInteract && noise > 0) ? (float) rng.nextDouble(-noise, noise) : 0f;
        float noisePitch = (!blockInteract && noise > 0) ? (float) rng.nextDouble(-noise, noise) : 0f;

        player.setYRot(Mth.wrapDegrees(curYaw + yawStep + noiseYaw));
        player.setXRot(Mth.clamp(curPitch + pitchStep + noisePitch, -90.0f, 90.0f));
    }

    /** Plain "look from eye at point" rotation (used for breaking). */
    public static Rotation lookAt(Vec3 eye, Vec3 point) {
        double dx = point.x - eye.x, dy = point.y - eye.y, dz = point.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
        return new Rotation(Mth.wrapDegrees(yaw), Mth.clamp(pitch, -89.5f, 89.5f));
    }
}
