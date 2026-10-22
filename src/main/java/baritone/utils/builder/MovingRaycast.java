package baritone.utils.builder;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Placement verification that accounts for a moving player.
 *
 * Timeline (vanilla order, per client tick N):
 *   1. input handling -> use-item packet is built from the rotation/position state of the END of tick N-1
 *   2. player tick   -> rotation applied, movement applied, move packet sent
 * So: AIM during tick N using the PREDICTED eye of tick N+1, CLICK during tick N+1 and
 * re-verify with the REAL eye + REAL rotation before clicking. Never change rotation on the click tick.
 */
public final class MovingRaycast {

    /** Hard cap for anticheat (GrimAC reach limit: strict 3.65 margin). */
    public static final double MAX_REACH = 3.65;

    public record Aim(float yaw, float pitch) {}

    private MovingRaycast() {}

    /** Eye position one tick from now (first-order estimate: current eye + current velocity). */
    public static Vec3 predictedEye(LocalPlayer p) {
        return p.getEyePosition().add(p.getDeltaMovement());
    }

    public static Vec3 lookVec(float yawDeg, float pitchDeg) {
        double yaw = Math.toRadians(yawDeg), pitch = Math.toRadians(pitchDeg);
        double cp = Math.cos(pitch);
        return new Vec3(-Math.sin(yaw) * cp, -Math.sin(pitch), Math.cos(yaw) * cp);
    }

    public static Aim aimAt(Vec3 eye, Vec3 target) {
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        return new Aim(yaw, pitch);
    }

    /** True iff a vanilla block pick from (eye, yaw, pitch) lands on exactly this support block + face. */
    public static boolean hitsFace(Level level, LocalPlayer p, Vec3 eye, float yaw, float pitch,
                                   BlockPos support, Direction face, double reach) {
        Vec3 end = eye.add(lookVec(yaw, pitch).scale(reach));
        BlockHitResult hit = level.clip(new ClipContext(eye, end,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        return hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(support)
                && hit.getDirection() == face
                && eye.distanceTo(hit.getLocation()) <= reach;
    }

    /** Target cell is replaceable and neither the current nor next-tick player box overlaps it. */
    public static boolean cellFree(Level level, LocalPlayer p, BlockPos cell) {
        if (!level.getBlockState(cell).canBeReplaced()) return false;
        AABB box = new AABB(cell);
        AABB now = p.getBoundingBox();
        AABB next = now.move(p.getDeltaMovement());
        return !box.intersects(now.inflate(0.05)) && !box.intersects(next.inflate(0.05));
    }
}
