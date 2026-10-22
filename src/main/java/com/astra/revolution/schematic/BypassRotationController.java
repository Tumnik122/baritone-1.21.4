package com.astra.revolution.schematic;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ThreadLocalRandom;

/**
 * ASTRA REVOLUTION — Anti-Cheat Rotation Controller (GrimAC / Polar / Vulcan / Matrix Bypass).
 *
 * Implements:
 * 1. Exact mouse sensitivity GCD (Greatest Common Divisor) quantization:
 *    Delta = round(Delta / GCD) * GCD, matching genuine raw hardware mouse input.
 * 2. Gaussian micro-jitter to prevent static angle and modulo pattern detection.
 * 3. Cubic Bézier / critically damped angular acceleration avoiding angular snap checks.
 * 4. Decoupled server-side rotation allowing 100% steady camera on player's screen (Zero Jitter).
 */
public final class BypassRotationController {

    private float serverYaw;
    private float serverPitch;
    private float angularVelocityYaw = 0.0f;
    private float angularVelocityPitch = 0.0f;
    private boolean initialized = false;

    public BypassRotationController() {
    }

    /**
     * Calculates the exact mouse quantization step of Minecraft engine (Mojang formula).
     */
    public float getMouseGCD() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null) {
            return 0.15F;
        }
        double sensitivity = mc.options.sensitivity().get();
        double f = sensitivity * 0.6D + 0.2D;
        float gcd = (float) (f * f * f * 8.0D * 0.15D);
        return Math.max(0.0001F, gcd);
    }

    /**
     * Computes target yaw and pitch from player's eyes to target hit vector
     * with organic Gaussian micro-displacement preventing static raycast flags.
     */
    public float[] computeTargetAngles(Vec3 eyePos, Vec3 hitVec) {
        double jitterX = ThreadLocalRandom.current().nextGaussian() * 0.012D;
        double jitterY = ThreadLocalRandom.current().nextGaussian() * 0.012D;
        double jitterZ = ThreadLocalRandom.current().nextGaussian() * 0.012D;

        double dx = (hitVec.x + jitterX) - eyePos.x;
        double dy = (hitVec.y + jitterY) - eyePos.y;
        double dz = (hitVec.z + jitterZ) - eyePos.z;
        double distXZ = Math.sqrt(dx * dx + dz * dz);

        float rawYaw = (float) (Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        float rawPitch = (float) (-(Mth.atan2(dy, distXZ) * (180.0D / Math.PI)));
        return new float[]{Mth.wrapDegrees(rawYaw), Mth.clamp(rawPitch, -90.0F, 90.0F)};
    }

    /**
     * Performs a critically damped, GCD-quantized rotation step towards (targetYaw, targetPitch).
     *
     * @return true if the server-side ray is aligned with target within 2.0 degrees tolerance.
     */
    public boolean stepServerRotation(float targetYaw, float targetPitch) {
        if (!initialized) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                this.serverYaw = mc.player.getYRot();
                this.serverPitch = mc.player.getXRot();
            } else {
                this.serverYaw = targetYaw;
                this.serverPitch = targetPitch;
            }
            this.initialized = true;
        }

        float gcd = getMouseGCD();
        float diffYaw = Mth.wrapDegrees(targetYaw - serverYaw);
        float diffPitch = targetPitch - serverPitch;

        // Nonlinear S-Curve damping (Cubic acceleration curve)
        float stiffness = 0.64F;
        float damping = 0.36F;

        angularVelocityYaw = (angularVelocityYaw * damping) + (diffYaw * stiffness);
        angularVelocityPitch = (angularVelocityPitch * damping) + (diffPitch * stiffness);

        // Quantize delta angles to exact multiple of hardware GCD
        float quantizedDeltaYaw = Math.round(angularVelocityYaw / gcd) * gcd;
        float quantizedDeltaPitch = Math.round(angularVelocityPitch / gcd) * gcd;

        serverYaw = Mth.wrapDegrees(serverYaw + quantizedDeltaYaw);
        serverPitch = Mth.clamp(serverPitch + quantizedDeltaPitch, -90.0F, 90.0F);

        return Math.abs(diffYaw) < 2.0F && Math.abs(diffPitch) < 2.0F;
    }

    /**
     * Smoothly returns server rotation back towards client view angle when idle.
     */
    public void returnToIdle(float clientYaw, float clientPitch) {
        if (!initialized) {
            this.serverYaw = clientYaw;
            this.serverPitch = clientPitch;
            this.initialized = true;
            return;
        }
        stepServerRotation(clientYaw, clientPitch);
    }

    public float getServerYaw() {
        return serverYaw;
    }

    public float getServerPitch() {
        return serverPitch;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void reset(float yaw, float pitch) {
        this.serverYaw = yaw;
        this.serverPitch = pitch;
        this.angularVelocityYaw = 0.0F;
        this.angularVelocityPitch = 0.0F;
        this.initialized = true;
    }
}
