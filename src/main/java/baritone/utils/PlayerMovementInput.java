/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.utils;

import baritone.Baritone;
import baritone.api.utils.input.Input;
import net.minecraft.client.player.ClientInput;
import net.minecraft.util.Mth;

import java.util.concurrent.ThreadLocalRandom;

public class PlayerMovementInput extends ClientInput {

    private final InputOverrideHandler handler;
    private float smoothedForward = 0.0F;
    private float smoothedLeft    = 0.0F;

    // ── Micro-pause state ────────────────────────────────────────────────────
    /** Ticks until the next random micro-pause is triggered. */
    private int microPauseCountdown  = nextMicroPauseInterval();
    /** Remaining ticks of the current micro-pause (0 = not pausing). */
    private int microPauseDuration   = 0;

    // ── Key-release simulation ────────────────────────────────────────────────
    /** Sign of forward impulse on the previous tick (+1 / -1 / 0). */
    private int prevForwardSign  = 0;
    /** Sign of left impulse on the previous tick (+1 / -1 / 0). */
    private int prevLeftSign     = 0;
    /** Ticks remaining where we force the axis to 0 (simulate key release). */
    private int dirChangeCooldownForward = 0;
    private int dirChangeCooldownLeft    = 0;

    public PlayerMovementInput(InputOverrideHandler handler) {
        this.handler = handler;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns a random interval [40, 120] ticks between micro-pauses. */
    private static int nextMicroPauseInterval() {
        return 40 + ThreadLocalRandom.current().nextInt(81);
    }

    /** Returns a random micro-pause length [1, 3] ticks. */
    private static int nextMicroPauseDuration() {
        return 1 + ThreadLocalRandom.current().nextInt(3);
    }

    /**
     * Samples a randomised EMA alpha from a Gaussian distribution,
     * clamped to [0.35, 0.80].  Calling this every tick means the
     * smoothing coefficient is never the same two ticks in a row,
     * eliminating the constant-EMA bot signature.
     */
    private static float randomAlpha() {
        float alpha = 0.55F + (float) (ThreadLocalRandom.current().nextGaussian() * 0.08F);
        return Mth.clamp(alpha, 0.35F, 0.80F);
    }

    // ── Main tick ─────────────────────────────────────────────────────────────

    @Override
    public void tick() {
        float targetLeft    = 0.0F;
        float targetForward = 0.0F;
        boolean jumping  = handler.isInputForcedDown(Input.JUMP);

        boolean up    = handler.isInputForcedDown(Input.MOVE_FORWARD);
        boolean down  = handler.isInputForcedDown(Input.MOVE_BACK);
        boolean left  = handler.isInputForcedDown(Input.MOVE_LEFT);
        boolean right = handler.isInputForcedDown(Input.MOVE_RIGHT);

        if (up)    targetForward += 1.0F;
        if (down)  targetForward -= 1.0F;
        if (left)  targetLeft    += 1.0F;
        if (right) targetLeft    -= 1.0F;

        boolean sneaking  = handler.isInputForcedDown(Input.SNEAK);
        if (sneaking) {
            targetLeft    *= 0.3F;
            targetForward *= 0.3F;
        }

        boolean sprinting = handler.isInputForcedDown(Input.SPRINT);

        if (Baritone.settings().naturalMovement.value) {
            // ── 1. Key-release simulation ─────────────────────────────────────
            // When the desired direction on an axis flips sign, spend 1 tick at
            // zero impulse (the player "releases" the old key before pressing the
            // new one).  This only triggers when actually changing direction, not
            // when stopping.
            int curForwardSign = (int) Math.signum(targetForward);
            int curLeftSign    = (int) Math.signum(targetLeft);

            if (curForwardSign != 0 && prevForwardSign != 0
                    && curForwardSign != prevForwardSign) {
                dirChangeCooldownForward = 1;
            }
            if (curLeftSign != 0 && prevLeftSign != 0
                    && curLeftSign != prevLeftSign) {
                dirChangeCooldownLeft = 1;
            }

            if (dirChangeCooldownForward > 0) {
                dirChangeCooldownForward--;
                targetForward = 0.0F;
            }
            if (dirChangeCooldownLeft > 0) {
                dirChangeCooldownLeft--;
                targetLeft = 0.0F;
            }

            prevForwardSign = (int) Math.signum(targetForward);
            prevLeftSign    = (int) Math.signum(targetLeft);

            // ── 2. Random micro-pause ─────────────────────────────────────────
            // Periodically inject a short (1–3 tick) pause to simulate a human
            // briefly easing off the keys – a natural behaviour anticheat entropy
            // analysis looks for.
            if (microPauseDuration > 0) {
                microPauseDuration--;
                // Zero-out target during pause; smoothed values will decay naturally.
                targetForward = 0.0F;
                targetLeft    = 0.0F;
            } else {
                microPauseCountdown--;
                if (microPauseCountdown <= 0) {
                    // Only pause when actually moving (avoid spurious pauses at rest)
                    if (Math.abs(targetForward) > 0.01F || Math.abs(targetLeft) > 0.01F) {
                        microPauseDuration  = nextMicroPauseDuration();
                        microPauseCountdown = nextMicroPauseInterval();
                        targetForward = 0.0F;
                        targetLeft    = 0.0F;
                    } else {
                        microPauseCountdown = nextMicroPauseInterval();
                    }
                }
            }

            // ── 3. Randomised EMA smoothing & Organic Diagonal Blending ─────
            // Using a per-tick random alpha from a Gaussian distribution prevents
            // the constant-coefficient EMA fingerprint that anticheat movement
            // analysers detect over multi-second windows.
            float alpha = randomAlpha();

            // When moving diagonally, human players naturally fluctuate key pressure (±0.02 - ±0.04)
            if (Math.abs(targetForward) > 0.5F && Math.abs(targetLeft) > 0.5F) {
                float strafeJitter = (float) (ThreadLocalRandom.current().nextGaussian() * 0.035F);
                targetLeft += strafeJitter;
                targetLeft = Mth.clamp(targetLeft, -1.0F, 1.0F);
            }

            smoothedForward = smoothedForward * (1.0F - alpha) + targetForward * alpha;
            smoothedLeft    = smoothedLeft    * (1.0F - alpha) + targetLeft    * alpha;

            // Snap to zero to avoid infinite low-magnitude drift
            if (Math.abs(smoothedForward) < 0.02F && targetForward == 0.0F) smoothedForward = 0.0F;
            if (Math.abs(smoothedLeft)    < 0.02F && targetLeft    == 0.0F) smoothedLeft    = 0.0F;

            this.forwardImpulse = smoothedForward;
            this.leftImpulse    = smoothedLeft;
        } else {
            this.forwardImpulse  = targetForward;
            this.leftImpulse     = targetLeft;
            this.smoothedForward = targetForward;
            this.smoothedLeft    = targetLeft;
        }

        this.keyPresses = new net.minecraft.world.entity.player.Input(
                up, down, left, right, jumping, sneaking, sprinting);
    }
}