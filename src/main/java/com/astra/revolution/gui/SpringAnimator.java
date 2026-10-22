package com.astra.revolution.gui;

/**
 * ASTRA REVOLUTION — Analytic Harmonic Spring Oscillator (Second-Order Dynamics).
 *
 * Provides FPS-independent, critically/under-damped spring animation for
 * the Obsidian & Lilac Glass GUI, layer histogram scrubber, and Status Capsule HUD.
 * Uses 4 sub-steps of Symplectic Euler integration to guarantee numerical stability
 * even during sudden frametime spikes.
 */
public final class SpringAnimator {

    private float currentValue;
    private float targetValue;
    private float velocity;
    private final float stiffness; // e.g. 260.0f for snappy UI response
    private final float damping;   // e.g. 24.0f for smooth critically damped settling

    public SpringAnimator(float initialValue, float stiffness, float damping) {
        this.currentValue = initialValue;
        this.targetValue = initialValue;
        this.velocity = 0.0f;
        this.stiffness = stiffness;
        this.damping = damping;
    }

    public SpringAnimator(float initialValue) {
        this(initialValue, 260.0f, 24.0f);
    }

    /**
     * Advances spring state by deltaSeconds using 4 sympletic sub-steps.
     */
    public float update(float deltaSeconds) {
        float dt = Math.min(deltaSeconds, 0.05f); // Clamp against massive lag spikes
        float subDt = dt * 0.25f;

        for (int i = 0; i < 4; i++) {
            float displacement = currentValue - targetValue;
            float springForce = -stiffness * displacement;
            float dampingForce = -damping * velocity;
            float acceleration = springForce + dampingForce;

            velocity += acceleration * subDt;
            currentValue += velocity * subDt;
        }

        // Rest snap check
        if (Math.abs(currentValue - targetValue) < 0.0005f && Math.abs(velocity) < 0.002f) {
            currentValue = targetValue;
            velocity = 0.0f;
        }

        return currentValue;
    }

    public void setTarget(float target) {
        this.targetValue = target;
    }

    public void snapTo(float value) {
        this.currentValue = value;
        this.targetValue = value;
        this.velocity = 0.0f;
    }

    public void impulse(float velocityKick) {
        this.velocity += velocityKick;
    }

    public float getValue() {
        return currentValue;
    }

    public float getTarget() {
        return targetValue;
    }

    public float getVelocity() {
        return velocity;
    }

    public boolean isAtRest() {
        return currentValue == targetValue && velocity == 0.0f;
    }
}
