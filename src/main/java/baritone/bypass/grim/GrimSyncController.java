package baritone.bypass.grim;

import baritone.api.utils.Rotation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Warstwa deterministycznej synchronizacji fizyki klienta dla GrimAC 1.21.4.
 * Gwarantuje brak flag: Simulation, Reach, BadPackets, FastBreak, AimGCD.
 */
public final class GrimSyncController {

    public static final double GRIM_MAX_REACH = 3.95D;
    public static final double GRIM_MAX_REACH_SQ = GRIM_MAX_REACH * GRIM_MAX_REACH;
    private static final float MIN_PITCH = -90.0F;
    private static final float MAX_PITCH = 90.0F;

    private float lastSentYaw = 0.0F;
    private float lastSentPitch = 0.0F;
    private boolean lastSprintState = false;
    private int vanillaDestroyCooldownTicks = 0;

    public void onTickStart(LocalPlayer player) {
        if (vanillaDestroyCooldownTicks > 0) {
            vanillaDestroyCooldownTicks--;
        }
        if (player != null) {
            lastSentYaw = player.getYRot();
            lastSentPitch = player.getXRot();
        }
    }

    /**
     * Kwantyzacja rotacji dokładnie według wzoru z MouseHandler.turnPlayer():
     * f = sensitivity * 0.6 + 0.2; gcd = f^3 * 1.2 (8.0 * 0.15).
     */
    public static float[] applyGrimGcdRaw(float currentYaw, float currentPitch, float targetYaw, float targetPitch) {
        double sens = Minecraft.getInstance().options.sensitivity().get();
        double f = sens * 0.6D + 0.2D;
        double gcd = f * f * f * 1.2D;

        if (gcd < 1.0E-5D) {
            gcd = 0.0012D;
        }

        float deltaYaw = Mth.wrapDegrees(targetYaw - currentYaw);
        float deltaPitch = targetPitch - currentPitch;

        // Kwantyzacja do całkowitych wielokrotności kroku myszy (int ticks)
        float quantizedDeltaYaw = (float) (Math.round(deltaYaw / gcd) * gcd);
        float quantizedDeltaPitch = (float) (Math.round(deltaPitch / gcd) * gcd);

        float finalYaw = currentYaw + quantizedDeltaYaw;
        float finalPitch = Mth.clamp(currentPitch + quantizedDeltaPitch, MIN_PITCH, MAX_PITCH);

        return new float[]{finalYaw, finalPitch};
    }

    public static Rotation applyGrimGcd(float currentYaw, float currentPitch, float targetYaw, float targetPitch) {
        float[] res = applyGrimGcdRaw(currentYaw, currentPitch, targetYaw, targetPitch);
        return new Rotation(res[0], res[1]);
    }

    /**
     * Atomowa synchronizacja stanu sprintu dla 1.21.4:
     * Zapobiega rozjazdowi między Input.sprint(), player.isSprinting() i PlayerCommandPacket.
     */
    public void syncSprintState(LocalPlayer player, boolean desiredSprint, boolean movingForward) {
        if (player == null) return;
        boolean legalSprint = desiredSprint
                && movingForward
                && !player.isShiftKeyDown()
                && !player.horizontalCollision
                && player.getFoodData().getFoodLevel() > 6;

        if (player.isSprinting() != legalSprint && (player.onGround() || player.isInWater())) {
            player.setSprinting(legalSprint);
        }
        lastSprintState = legalSprint;
    }

    /**
     * Weryfikuje Line-of-Sight oraz zasięg oczu przed wysłaniem jakiegokolwiek pakietu kopania.
     * Zwraca dokładny BlockHitResult (wraz ze ścianą Direction) lub null, jeśli blok jest zasłonięty / za daleko.
     */
    @Nullable
    public BlockHitResult verifyGrimRayTrace(LocalPlayer player, Level level, BlockPos targetPos) {
        if (player == null || level == null || targetPos == null) return null;
        Vec3 eye = player.getEyePosition(1.0F);

        // Punkt docelowy na AABB bloku najbliższy oczom (z marginesem 0.05 od krawędzi)
        double tx = Mth.clamp(eye.x, targetPos.getX() + 0.05D, targetPos.getX() + 0.95D);
        double ty = Mth.clamp(eye.y, targetPos.getY() + 0.05D, targetPos.getY() + 0.95D);
        double tz = Mth.clamp(eye.z, targetPos.getZ() + 0.05D, targetPos.getZ() + 0.95D);

        if (eye.distanceToSqr(tx, ty, tz) > GRIM_MAX_REACH_SQ) {
            return null;
        }

        BlockHitResult hit = level.clip(new ClipContext(
                eye,
                new Vec3(tx, ty, tz),
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player
        ));

        if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(targetPos)) {
            return hit;
        }

        // Fallback: sprawdzenie środka każdej widocznej ściany (przydatne na rogach korytarza)
        for (Direction dir : Direction.values()) {
            double fx = targetPos.getX() + 0.5D + dir.getStepX() * 0.45D;
            double fy = targetPos.getY() + 0.5D + dir.getStepY() * 0.45D;
            double fz = targetPos.getZ() + 0.5D + dir.getStepZ() * 0.45D;
            if (eye.distanceToSqr(fx, fy, fz) > GRIM_MAX_REACH_SQ) continue;

            BlockHitResult faceHit = level.clip(new ClipContext(
                    eye,
                    new Vec3(fx, fy, fz),
                    ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE,
                    player
            ));
            if (faceHit.getType() == HitResult.Type.BLOCK && faceHit.getBlockPos().equals(targetPos)) {
                return faceHit;
            }
        }

        return null;
    }

    public boolean canStartNewBreak() {
        return vanillaDestroyCooldownTicks == 0;
    }

    /**
     * Rejestruje zakończenie kopania bloku.
     * Jeśli blok nie został rozbity w 1 tick (instamine), nakłada waniliowy cooldown 5 ticków.
     */
    public void onBlockBroken(boolean wasInstamine) {
        if (!wasInstamine) {
            vanillaDestroyCooldownTicks = 5;
        }
    }

    /**
     * Bezpieczne anulowanie kopania (zapobiega desynchronizacji postępu na serwerze).
     */
    public void sendAbortDestroy(LocalPlayer player, BlockPos pos, Direction face) {
        if (player != null && player.connection != null) {
            player.connection.send(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, pos, face
            ));
        }
    }
}

