package baritone.behavior;

import baritone.api.utils.IPlayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

import java.util.Objects;
import java.util.Random;

/**
 * Silnik rotacji - JEDNA instancja na IPlayerContext (brak współdzielonego stanu / singletona).
 *
 * Rotacja jest aplikowana przez LocalPlayer#turn(), czyli tą samą ścieżką, którą vanilla
 * stosuje dla ruchu myszy: (float) cast, * 0.15F, clamp pitch oraz aktualizacja xRotO/yRotO.
 * Dzięki temu każda zmiana rotacji jest z definicji wielokrotnością kroku czułości (GCD).
 *
 * Wywoływany z wątku klienta, przed LocalPlayer#tick (sendPosition).
 */
public final class GrimRotationEngine {

    private static final float BEZIER_P1 = 0.25F;
    private static final float BEZIER_P2 = 0.75F;

    private final IPlayerContext ctx;
    private final Random random = new Random();

    // f = (s * 0.6 + 0.2)^3 * 8  (MouseHandler#turnPlayer), degPerCount = f * 0.15
    private double mouseFactor;
    private float degPerCount;

    private float startYaw, startPitch;
    private float targetYaw, targetPitch;
    private boolean hasTarget;
    private int ticksTotal, ticksRemaining;

    // Ostatnia rotacja zaaplikowana przez silnik - do wykrywania zmian z zewnątrz (setback, teleport)
    private float lastYaw = Float.NaN, lastPitch = Float.NaN;

    public GrimRotationEngine(IPlayerContext ctx) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
        Minecraft mc = Minecraft.getInstance();
        if (mc.options != null && mc.options.sensitivity() != null) {
            setSensitivity(mc.options.sensitivity().get());
        } else {
            setSensitivity(0.5D);
        }
    }

    /**
     * Kompatybilność wsteczna - zwraca instancję dla danego IPlayerContext.
     */
    public static GrimRotationEngine getInstance(IPlayerContext ctx) {
        return new GrimRotationEngine(ctx);
    }

    public void setMouseSensitivity(float sensitivity) {
        setSensitivity((double) sensitivity);
    }

    /** s w zakresie 0..1 (0.5 = "100%" w menu). */
    public void setSensitivity(double sensitivity) {
        double d = Mth.clamp(sensitivity, 0.0D, 1.0D) * 0.6F + 0.2F;
        this.mouseFactor = d * d * d * 8.0D;
        this.degPerCount = (float) (this.mouseFactor * 0.15F);
        if (this.degPerCount < 0.001F) {
            this.degPerCount = 0.001F;
        }
    }

    /** Przerywa ruch i zapomina cel. */
    public void reset() {
        ticksRemaining = 0;
        ticksTotal = 0;
        hasTarget = false;
        lastYaw = Float.NaN;
        lastPitch = Float.NaN;
    }

    public void syncFromPlayer(LocalPlayer player) {
        if (player != null && !isRotating()) {
            this.lastYaw = player.getYRot();
            this.lastPitch = player.getXRot();
        }
    }

    public void targetRotation(float yaw, float pitch) {
        assertClientThread();
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) return;
        LocalPlayer p = ctx != null ? ctx.player() : null;
        if (p == null) return;

        pitch = Mth.clamp(pitch, -90.0F, 90.0F);

        float yawDelta = Mth.wrapDegrees(yaw - p.getYRot());
        float pitchDelta = pitch - p.getXRot();

        int yawCounts = toCounts(yawDelta);
        int pitchCounts = toCounts(pitchDelta);

        float newTargetYaw = p.getYRot() + countsToDeg(yawCounts);
        float newTargetPitch = Mth.clamp(p.getXRot() + countsToDeg(pitchCounts), -90.0F, 90.0F);

        if (yawCounts == 0 && pitchCounts == 0) {
            targetYaw = p.getYRot();
            targetPitch = p.getXRot();
            hasTarget = true;
            ticksRemaining = 0;
            return;
        }

        if (isRotating()
                && Math.abs(Mth.wrapDegrees(newTargetYaw - targetYaw)) < degPerCount
                && Math.abs(newTargetPitch - targetPitch) < degPerCount) {
            return;
        }

        targetYaw = newTargetYaw;
        targetPitch = newTargetPitch;
        hasTarget = true;
        startYaw = p.getYRot();
        startPitch = p.getXRot();
        lastYaw = startYaw;
        lastPitch = startPitch;

        float total = (float) Math.hypot(yawDelta, pitchDelta);
        if (total <= 30.0F) {
            ticksTotal = 1;
        } else if (total <= 80.0F) {
            ticksTotal = 2;
        } else if (total <= 140.0F) {
            ticksTotal = 2 + random.nextInt(2);
        } else {
            ticksTotal = 3;
        }
        ticksRemaining = ticksTotal;
    }

    public void tick() {
        if (ticksRemaining <= 0) return;
        assertClientThread();
        LocalPlayer p = ctx != null ? ctx.player() : null;
        if (p == null) {
            reset();
            return;
        }
        if (changedExternally(p)) {
            reset();
            return;
        }

        int idx = ticksTotal - ticksRemaining;
        float e = cubicBezier((idx + 1) / (float) ticksTotal);

        float wantYaw = startYaw + Mth.wrapDegrees(targetYaw - startYaw) * e;
        float wantPitch = startPitch + (targetPitch - startPitch) * e;

        int yc = toCounts(wantYaw - p.getYRot());
        int pc = toCounts(wantPitch - p.getXRot());

        if (ticksRemaining > 1) {
            yc += jitterCounts();
            pc += jitterCounts();
        }

        p.turn(yc * mouseFactor, pc * mouseFactor);

        lastYaw = p.getYRot();
        lastPitch = p.getXRot();
        ticksRemaining--;
    }

    public void snapTo(float yaw, float pitch) {
        assertClientThread();
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) return;
        LocalPlayer p = ctx != null ? ctx.player() : null;
        if (p == null) return;
        int yc = toCounts(Mth.wrapDegrees(yaw - p.getYRot()));
        int pc = toCounts(Mth.clamp(pitch, -90.0F, 90.0F) - p.getXRot());
        p.turn(yc * mouseFactor, pc * mouseFactor);
        reset();
        targetYaw = p.getYRot();
        targetPitch = p.getXRot();
        hasTarget = true;
    }

    public float getCurrentYaw() {
        LocalPlayer p = ctx != null ? ctx.player() : null;
        return p != null ? p.getYRot() : 0.0F;
    }

    public float getCurrentPitch() {
        LocalPlayer p = ctx != null ? ctx.player() : null;
        return p != null ? p.getXRot() : 0.0F;
    }

    public boolean isRotating() {
        return ticksRemaining > 0;
    }

    public boolean hasReachedTarget() {
        LocalPlayer p = ctx != null ? ctx.player() : null;
        if (p == null || !hasTarget) return false;
        float tol = Math.max(degPerCount, 0.25F);
        return Math.abs(Mth.wrapDegrees(targetYaw - p.getYRot())) <= tol
                && Math.abs(targetPitch - p.getXRot()) <= tol;
    }

    private int toCounts(float deltaDeg) {
        return Math.round(deltaDeg / degPerCount);
    }

    private float countsToDeg(int counts) {
        return (float) (counts * mouseFactor) * 0.15F;
    }

    private int jitterCounts() {
        int r = random.nextInt(4);
        return r == 0 ? -1 : (r == 1 ? 1 : 0);
    }

    private boolean changedExternally(LocalPlayer p) {
        return !Float.isNaN(lastYaw) && (Math.abs(Mth.wrapDegrees(p.getYRot() - lastYaw)) > 0.01F || Math.abs(p.getXRot() - lastPitch) > 0.01F);
    }

    private static float cubicBezier(float t) {
        float inv = 1.0F - t;
        return 3.0F * inv * inv * t * BEZIER_P1 + 3.0F * inv * t * t * BEZIER_P2 + t * t * t;
    }

    private static void assertClientThread() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && !mc.isSameThread()) {
            throw new IllegalStateException("GrimRotationEngine must be used on the client thread");
        }
    }
}
