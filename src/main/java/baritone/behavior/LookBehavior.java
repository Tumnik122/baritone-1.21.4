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

package baritone.behavior;

import baritone.Baritone;
import baritone.api.Settings;
import baritone.api.behavior.ILookBehavior;
import baritone.api.behavior.look.IAimProcessor;
import baritone.api.behavior.look.ITickableAimProcessor;
import baritone.api.event.events.*;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.behavior.look.ForkableRandom;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

public final class LookBehavior extends Behavior implements ILookBehavior {

    private Target target;
    private Rotation serverRotation;
    private Rotation prevRotation;
    private final AimProcessor processor;
    private final Deque<Float> smoothYawBuffer;
    private final Deque<Float> smoothPitchBuffer;

    private float lastAppliedYaw;
    private float lastAppliedPitch;
    // Last yaw sent to server – used for velocity-correlated packet jitter
    private float lastSentYaw   = 0f;
    private float lastSentPitch = 0f;
    // Idle hand-tremor: fired every ~200–600 ms regardless of movement
    private long  nextIdleJitterMs = 0L;

    public LookBehavior(Baritone baritone) {
        super(baritone);
        this.processor = new AimProcessor(baritone.getPlayerContext());
        this.smoothYawBuffer   = new ArrayDeque<>();
        this.smoothPitchBuffer = new ArrayDeque<>();
        this.lastAppliedYaw   = 0f;
        this.lastAppliedPitch = 0f;
    }

    @Override
    public void updateTarget(Rotation rotation, boolean blockInteract) {
        this.target = new Target(rotation, Target.Mode.resolve(ctx, blockInteract), blockInteract);
    }

    @Override
    public IAimProcessor getAimProcessor() {
        return this.processor;
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.IN) {
            this.processor.tick();
        }
    }

    @Override
    public void onPlayerUpdate(PlayerUpdateEvent event) {

        if (this.target == null) {
            return;
        }

        switch (event.getState()) {
            case PRE: {
                if (this.target.mode == Target.Mode.NONE) {
                    return;
                }

                this.prevRotation = new Rotation(ctx.player().getYRot(), ctx.player().getXRot());
                final Rotation actual = this.processor.peekRotation(this.target.rotation, this.target.blockInteract);

                this.lastAppliedYaw   = actual.getYaw();
                this.lastAppliedPitch = actual.getPitch();

                ctx.player().setYRot(actual.getYaw());
                ctx.player().setXRot(actual.getPitch());
                break;
            }
            case POST: {
                if (this.prevRotation != null) {
                    this.smoothYawBuffer.addLast(this.target.rotation.getYaw());
                    while (this.smoothYawBuffer.size() > Baritone.settings().smoothLookTicks.value) {
                        this.smoothYawBuffer.removeFirst();
                    }
                    this.smoothPitchBuffer.addLast(this.target.rotation.getPitch());
                    while (this.smoothPitchBuffer.size() > Baritone.settings().smoothLookTicks.value) {
                        this.smoothPitchBuffer.removeFirst();
                    }
                    if (this.target.mode == Target.Mode.SERVER) {
                        ctx.player().setYRot(this.prevRotation.getYaw());
                        ctx.player().setXRot(this.prevRotation.getPitch());
                    } else if (ctx.player().isFallFlying() ? Baritone.settings().elytraSmoothLook.value
                            : Baritone.settings().smoothLook.value) {
                        ctx.player().setYRot((float) this.smoothYawBuffer.stream().mapToDouble(d -> d).average()
                                .orElse(this.prevRotation.getYaw()));
                        if (ctx.player().isFallFlying()) {
                            ctx.player().setXRot((float) this.smoothPitchBuffer.stream().mapToDouble(d -> d).average()
                                    .orElse(this.prevRotation.getPitch()));
                        }
                    }
                    this.prevRotation = null;
                }
                this.target = null;
                break;
            }
            default:
                break;
        }
    }

    @Override
    public void onSendPacket(PacketEvent event) {
        if (!(event.getPacket() instanceof ServerboundMovePlayerPacket)) {
            return;
        }

        final ServerboundMovePlayerPacket packet = (ServerboundMovePlayerPacket) event.getPacket();
        if (packet instanceof ServerboundMovePlayerPacket.Rot
                || packet instanceof ServerboundMovePlayerPacket.PosRot) {

            float yaw   = packet.getYRot(0.0f);
            float pitch = packet.getXRot(0.0f);

            if (Baritone.settings().humanizedRotations.value) {
                // ── Velocity-correlated Gaussian jitter ──────────────────────────
                // Real mice produce more jitter the faster they are moving.
                // A flat ±0.02° uniform distribution is a bot fingerprint;
                // we scale a Gaussian jitter by how fast the crosshair just moved.
                float deltaYaw   = Math.abs(Mth.wrapDegrees(yaw   - lastSentYaw));
                float deltaPitch = Math.abs(pitch - lastSentPitch);
                float velocityScale = Math.min(1.0f, (deltaYaw + deltaPitch) / 6.0f);

                float jitterY = (float) (ThreadLocalRandom.current().nextGaussian()
                        * (0.018f + velocityScale * 0.045f));
                float jitterP = (float) (ThreadLocalRandom.current().nextGaussian()
                        * (0.012f + velocityScale * 0.028f));

                // ── Idle hand-tremor ─────────────────────────────────────────────
                // Regardless of rotation speed, fire a tiny random micro-correction
                // every 200–600 ms – simulates the hand resting on the mouse.
                long now = System.currentTimeMillis();
                if (now >= nextIdleJitterMs) {
                    jitterY += (float) (ThreadLocalRandom.current().nextGaussian() * 0.05f);
                    jitterP += (float) (ThreadLocalRandom.current().nextGaussian() * 0.03f);
                    nextIdleJitterMs = now + 200L + ThreadLocalRandom.current().nextLong(401);
                }

                yaw   += jitterY;
                pitch += jitterP;
            }

            lastSentYaw   = yaw;
            lastSentPitch = pitch;
            this.serverRotation = new Rotation(yaw, pitch);
        }
    }

    @Override
    public void onWorldEvent(WorldEvent event) {
        this.serverRotation = null;
        this.target         = null;
    }

    public void pig() {
        if (this.target != null) {
            final Rotation actual = this.processor.peekRotation(this.target.rotation);
            ctx.player().setYRot(actual.getYaw());
        }
    }

    public Optional<Rotation> getEffectiveRotation() {
        if (Baritone.settings().freeLook.value) {
            return Optional.ofNullable(this.serverRotation);
        }
        return Optional.empty();
    }

    @Override
    public void onPlayerRotationMove(RotationMoveEvent event) {
        if (this.target != null) {
            final Rotation actual = this.processor.peekRotation(this.target.rotation);
            event.setYaw(actual.getYaw());
            event.setPitch(actual.getPitch());
        }
    }

    // ── AimProcessor ─────────────────────────────────────────────────────────

    private static final class AimProcessor extends AbstractAimProcessor {

        public AimProcessor(final IPlayerContext ctx) {
            super(ctx);
        }

        @Override
        protected Rotation getPrevRotation() {
            return ctx.playerRotations();
        }
    }

    private static abstract class AbstractAimProcessor implements ITickableAimProcessor {

        protected final IPlayerContext ctx;
        private final ForkableRandom rand;
        private double randomYawOffset;
        private double randomPitchOffset;

        // State for advanced bypass
        private float previousDesiredYaw   = 0f;
        private float previousDesiredPitch = 0f;
        private int   microAdjustTick      = 0;
        private float microYaw             = 0f;
        private float microPitch           = 0f;
        private boolean firstTarget        = true;
        private boolean overshootPhase     = false;
        private float overshootYawOffset   = 0f;
        private float overshootPitchOffset = 0f;
        private int   overshootTicks       = 0;

        public AbstractAimProcessor(IPlayerContext ctx) {
            this.ctx  = ctx;
            this.rand = new ForkableRandom();
        }

        private AbstractAimProcessor(final AbstractAimProcessor source) {
            this.ctx                  = source.ctx;
            this.rand                 = source.rand.fork();
            this.randomYawOffset      = source.randomYawOffset;
            this.randomPitchOffset    = source.randomPitchOffset;
            this.firstTarget          = source.firstTarget;
            this.overshootPhase       = source.overshootPhase;
            this.overshootYawOffset   = source.overshootYawOffset;
            this.overshootPitchOffset = source.overshootPitchOffset;
            this.overshootTicks       = source.overshootTicks;
            this.microAdjustTick      = source.microAdjustTick;
            this.microYaw             = source.microYaw;
            this.microPitch           = source.microPitch;
            this.previousDesiredYaw   = source.previousDesiredYaw;
            this.previousDesiredPitch = source.previousDesiredPitch;
        }

        @Override
        public final Rotation peekRotation(final Rotation rotation) {
            return peekRotation(rotation, false);
        }

        public final Rotation peekRotation(final Rotation rotation, boolean blockInteract) {
            final Rotation prev = this.getPrevRotation();

            float desiredYaw   = rotation.getYaw();
            float desiredPitch = rotation.getPitch();

            if (desiredPitch == prev.getPitch()) {
                desiredPitch = nudgeToLevel(desiredPitch);
            }

            // ── 1. Losowy czas reakcji ───────────────────────────────────────
            if (!blockInteract) {
                if (this.firstTarget) {
                    this.firstTarget = false;
                } else {
                    // ~5 % chance to skip a tick (simulate "thinking pause")
                    if (ThreadLocalRandom.current().nextDouble() < 0.05) {
                        return prev;
                    }
                }
            }

            // ── 2. Target noise ──────────────────────────────────────────────
            // blockInteract uses a larger noise scale than before (0.09 instead of
            // 0.04) – perfect aim at a block surface for seconds is unnatural.
            // The noise is interpolated via Gaussian so it looks like "focus", not
            // pure randomness.
            float noiseScale = blockInteract ? 0.09f : 0.15f;
            float randomTargetOffsetYaw   = (float) (ThreadLocalRandom.current().nextGaussian() * noiseScale);
            float randomTargetOffsetPitch = (float) (ThreadLocalRandom.current().nextGaussian() * (noiseScale * 0.67f));
            desiredYaw   += randomTargetOffsetYaw;
            desiredPitch += randomTargetOffsetPitch;

            // ── 3. Overshoot / correction ────────────────────────────────────
            if (!blockInteract) {
                if (this.overshootTicks > 0) {
                    float correctionFactor = 1.0f - (this.overshootTicks / 5.0f);
                    desiredYaw   += this.overshootYawOffset   * correctionFactor;
                    desiredPitch += this.overshootPitchOffset * correctionFactor;
                    this.overshootTicks--;
                    if (this.overshootTicks == 0) {
                        this.overshootPhase = false;
                    }
                } else if (ThreadLocalRandom.current().nextDouble() < 0.10 && !this.overshootPhase) {
                    this.overshootPhase       = true;
                    this.overshootTicks       = ThreadLocalRandom.current().nextInt(2, 6);
                    this.overshootYawOffset   = (float) (ThreadLocalRandom.current().nextGaussian() * 0.8);
                    this.overshootPitchOffset = (float) (ThreadLocalRandom.current().nextGaussian() * 0.5);
                    desiredYaw   += this.overshootYawOffset;
                    desiredPitch += this.overshootPitchOffset;
                }
            }

            // ── 4. Micro-tremor (hand shake) ─────────────────────────────────
            this.microAdjustTick++;
            if (this.microAdjustTick % 3 == 0) {
                this.microYaw   = (float) (ThreadLocalRandom.current().nextGaussian() * 0.05);
                this.microPitch = (float) (ThreadLocalRandom.current().nextGaussian() * 0.04);
            }
            desiredYaw   += this.microYaw;
            desiredPitch += this.microPitch;

            // ── 5. Walk head-bob ─────────────────────────────────────────────
            // While the player is moving horizontally, add a subtle sinusoidal
            // pitch oscillation that mimics the natural head movement during walking.
            // Amplitude ≈ ±0.45°, period ≈ 460 ms (matches MC walk animation).
            if (!blockInteract) {
                boolean isWalking = ctx.player() != null
                        && (ctx.player().input.forwardImpulse != 0
                            || ctx.player().input.leftImpulse != 0)
                        && ctx.player().onGround();
                if (isWalking) {
                    float walkBob = (float) (Math.sin(System.currentTimeMillis() / 230.0) * 0.45);
                    desiredPitch += walkBob;
                }
            }

            // ── 6. Random offsets (settings) ─────────────────────────────────
            desiredYaw   += this.randomYawOffset;
            desiredPitch += this.randomPitchOffset;

            // ── Compute deltas ────────────────────────────────────────────────
            float deltaYaw   = Mth.wrapDegrees(desiredYaw   - prev.getYaw());
            float deltaPitch = desiredPitch - prev.getPitch();

            if (Baritone.settings().humanizedRotations.value) {
                // ── 7. Variable rotation speed ────────────────────────────────
                float baseMaxSpeed   = Baritone.settings().maxRotationSpeedPerTick.value;
                float speedModifier  = (float) (0.6 + ThreadLocalRandom.current().nextDouble() * 0.8);
                float maxSpeed       = baseMaxSpeed * speedModifier;

                double totalDist = Math.sqrt(deltaYaw * deltaYaw + deltaPitch * deltaPitch);

                if (totalDist > 0.0001) {
                    double normalizedDist   = Math.min(1.0, totalDist / 30.0);
                    double speedFactor      = Math.pow(normalizedDist, 1.2) * 0.9 + 0.1;
                    double randomSpeedJitter = 1.0 + (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.3;
                    double stepLimit        = maxSpeed * speedFactor * randomSpeedJitter;

                    if (totalDist > stepLimit) {
                        deltaYaw   = (float) ((deltaYaw   / totalDist) * stepLimit);
                        deltaPitch = (float) ((deltaPitch / totalDist) * stepLimit);
                    }
                }

                // ── 8. Distance-scaled jitter ─────────────────────────────────
                double jitterBase   = Baritone.settings().rotationJitter.value;
                double distFactor   = Math.min(1.0, totalDist / 40.0);
                double jitterAmount = jitterBase * (0.5 + distFactor * 0.5);
                if (jitterAmount > 0) {
                    deltaYaw   += (float) ((this.rand.nextDouble() - 0.5) * jitterAmount * 1.5);
                    deltaPitch += (float) ((this.rand.nextDouble() - 0.5) * jitterAmount * 1.5);
                }

                // ── 9. Occasional snap (2 % chance) ──────────────────────────
                if (ThreadLocalRandom.current().nextDouble() < 0.02) {
                    deltaYaw   += (float) (ThreadLocalRandom.current().nextGaussian() * 1.2);
                    deltaPitch += (float) (ThreadLocalRandom.current().nextGaussian() * 0.8);
                }
            }

            float newYaw   = this.calculateMouseMove(prev.getYaw(),   deltaYaw);
            float newPitch = Mth.clamp(this.calculateMouseMove(prev.getPitch(), deltaPitch), -90.0F, 90.0F);

            // ── 10. Natural mouse-step rounding ───────────────────────────────
            // Instead of rounding to a fixed 1/1000°, round to the minimum
            // physical mouse step (mouseToAngle(1.0)) so the quantisation matches
            // what the client would send from actual mouse movement.
            float mouseStep = Math.abs(mouseToAngle(1.0));
            if (mouseStep > 0f) {
                newYaw   = Math.round(newYaw   / mouseStep) * mouseStep;
                newPitch = Math.round(newPitch / mouseStep) * mouseStep;
            }

            return new Rotation(newYaw, newPitch);
        }

        @Override
        public final void tick() {
            this.randomYawOffset   = (this.rand.nextDouble() - 0.5) * Baritone.settings().randomLooking.value;
            this.randomPitchOffset = (this.rand.nextDouble() - 0.5) * Baritone.settings().randomLooking.value;

            double random = this.rand.nextDouble() - 0.5;
            if (Math.abs(random) < 0.1) {
                random *= 4;
            }
            this.randomYawOffset += random * Baritone.settings().randomLooking113.value;
        }

        @Override
        public final void advance(int ticks) {
            for (int i = 0; i < ticks; i++) {
                this.tick();
            }
        }

        @Override
        public Rotation nextRotation(final Rotation rotation) {
            final Rotation actual = this.peekRotation(rotation);
            this.tick();
            return actual;
        }

        @Override
        public final ITickableAimProcessor fork() {
            return new AbstractAimProcessor(this) {

                private Rotation prev = AbstractAimProcessor.this.getPrevRotation();

                @Override
                public Rotation nextRotation(final Rotation rotation) {
                    return (this.prev = super.nextRotation(rotation));
                }

                @Override
                protected Rotation getPrevRotation() {
                    return this.prev;
                }
            };
        }

        protected abstract Rotation getPrevRotation();

        private float nudgeToLevel(float pitch) {
            if (pitch < -20) {
                return pitch + 1;
            } else if (pitch > 10) {
                return pitch - 1;
            }
            return pitch;
        }

        private float calculateMouseMove(float current, float delta) {
            double deltaPx = angleToMouse(delta);
            if (Math.abs(deltaPx) < 1.0 && Math.abs(delta) > 0.0005f) {
                deltaPx = Math.signum(delta);
            }
            return current + mouseToAngle(deltaPx);
        }

        private double angleToMouse(float angleDelta) {
            final float minAngleChange = mouseToAngle(1.0);
            if (minAngleChange == 0.0f) {
                return 0.0;
            }
            return Math.round(angleDelta / minAngleChange);
        }

        private float mouseToAngle(double mouseDelta) {
            final double f = ctx.minecraft().options.sensitivity().get() * (double) 0.6f + (double) 0.2f;
            return (float) (mouseDelta * f * f * f * 8.0d) * 0.15f;
        }
    }

    // ── Target ───────────────────────────────────────────────────────────────

    private static class Target {

        public final Rotation rotation;
        public final Mode     mode;
        public final boolean  blockInteract;

        public Target(Rotation rotation, Mode mode, boolean blockInteract) {
            this.rotation      = rotation;
            this.mode          = mode;
            this.blockInteract = blockInteract;
        }

        enum Mode {
            CLIENT,
            SERVER,
            NONE;

            static Mode resolve(IPlayerContext ctx, boolean blockInteract) {
                final Settings settings    = Baritone.settings();
                final boolean antiCheat    = settings.antiCheatCompatibility.value;
                final boolean blockFreeLook = settings.blockFreeLook.value;

                if (ctx.player().isFallFlying()) {
                    return settings.elytraFreeLook.value ? SERVER : CLIENT;
                } else if (settings.freeLook.value) {
                    if (blockInteract) {
                        return blockFreeLook ? SERVER : CLIENT;
                    }
                    // When antiCheat is on, always use SERVER to hide client rotations.
                    // The old 50/50 random CLIENT/SERVER randomness created rare
                    // server↔client rotation anomalies that anticheat logs captured.
                    return antiCheat ? SERVER : (ThreadLocalRandom.current().nextBoolean() ? SERVER : NONE);
                }

                // antiCheat enabled: always SERVER (removed the previous 10 % CLIENT
                // leak which caused detectable rotation-sync spikes in anticheats).
                return antiCheat ? SERVER : CLIENT;
            }
        }
    }
}