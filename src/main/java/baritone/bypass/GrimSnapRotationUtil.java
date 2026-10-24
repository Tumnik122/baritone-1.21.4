package baritone.bypass;

import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.Rotation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Ultra-Fast GrimAC-Safe Snap Rotation & Shared Ray Alignment Utility.
 * Opracowane we współpracy z modelem ARGON 4 dla trybu #praca.
 */
public final class GrimSnapRotationUtil {

    private static final double[] CROP_Y_OFFSETS = {0.50D, 0.65D, 0.35D, 0.80D, 0.20D};
    private static final double[] XZ_OFFSETS = {0.50D, 0.35D, 0.65D, 0.25D, 0.75D};

    private GrimSnapRotationUtil() {}

    /**
     * Oblicza krok kwantyzacji GCD z aktualnej czułości myszy w grze.
     * f = 0.6s + 0.2, gcd = 1.2 * f^3 (GrimAC AimProcessor formula)
     */
    public static float getGcd() {
        double s = 0.5D;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.options != null && mc.options.sensitivity() != null) {
                s = mc.options.sensitivity().get();
            }
        } catch (Throwable ignored) {}

        double f = s * 0.6D + 0.2D;
        return (float) Math.max(f * f * f * 1.2D, 0.0001D);
    }

    /**
     * Wykonuje błyskawiczny 1-Tick obrót celownika rzutowany na dyskretną siatkę GCD.
     * Zapobiega flagom GrimAC: BadSensitivity, AimModulo360 oraz AimDuplicateLook.
     */
    public static float[] snapGcd(float currentYaw, float currentPitch, Vec3 eyePos, Vec3 targetVec) {
        double dx = targetVec.x - eyePos.x;
        double dy = targetVec.y - eyePos.y;
        double dz = targetVec.z - eyePos.z;
        double distXZ = Math.hypot(dx, dz);

        float rawYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float rawPitch = (float) (-Math.toDegrees(Math.atan2(dy, distXZ)));

        float gcd = getGcd();
        float deltaYaw = Mth.wrapDegrees(rawYaw - currentYaw);
        float deltaPitch = rawPitch - currentPitch;

        int mouseX = Math.round(deltaYaw / gcd);
        int mouseY = Math.round(deltaPitch / gcd);

        // GrimAC AimDuplicateLook guard: wymuś min. 1 krok GCD na osi X przy zmianie pitch, jeśli myszka się rusza
        if (mouseX == 0 && mouseY != 0 && Math.abs(deltaYaw) > 1e-4f) {
            mouseX = deltaYaw > 0 ? 1 : -1;
        }

        float nextYaw = currentYaw + mouseX * gcd;
        float nextPitch = Mth.clamp(currentPitch + mouseY * gcd, -89.5f, 89.5f);
        return new float[]{nextYaw, nextPitch};
    }

    /**
     * Zwraca skwantowaną GCD rotację gwarantującą trafienie w docelowy BlockPos (i Direction.UP dla REPLANT).
     * Multipoint Ray-Verified Aiming eliminuje fałszywe przecięcia Farmlandu zamiast kłosa pszenicy.
     */
    public static float[] findVerifiedSnapRotation(
            LocalPlayer player, float currentYaw, float currentPitch,
            BlockPos targetPos, boolean isHarvest, double maxReach) {

        if (player == null || targetPos == null) return null;
        Vec3 eye = player.getEyePosition(1.0f);
        double maxReachSq = maxReach * maxReach;

        if (isHarvest) {
            // Szukamy punktu wewnątrz bryły CropBlock (Y + 0.20..0.80), którego po kwantyzacji GCD nie zasłania Farmland
            for (double yOff : CROP_Y_OFFSETS) {
                for (double xOff : XZ_OFFSETS) {
                    for (double zOff : XZ_OFFSETS) {
                        Vec3 candidate = new Vec3(targetPos.getX() + xOff, targetPos.getY() + yOff, targetPos.getZ() + zOff);
                        if (eye.distanceToSqr(candidate) > maxReachSq) continue;

                        float[] snapped = snapGcd(currentYaw, currentPitch, eye, candidate);
                        HitResult hit = RayTraceUtils.rayTraceTowards(player, new Rotation(snapped[0], snapped[1]), maxReach, player.isCrouching());
                        if (hit instanceof BlockHitResult bhr && bhr.getBlockPos().equals(targetPos)) {
                            return snapped;
                        }
                    }
                }
            }
        } else {
            // REPLANT: targetPos to blok Farmland — wymagamy trafienia dokładnie w górną ścianę (Y = +0.9375, Direction.UP)
            double topY = targetPos.getY() + 0.9375D;
            for (double xOff : XZ_OFFSETS) {
                for (double zOff : XZ_OFFSETS) {
                    Vec3 candidate = new Vec3(targetPos.getX() + xOff, topY, targetPos.getZ() + zOff);
                    if (eye.distanceToSqr(candidate) > maxReachSq) continue;

                    float[] snapped = snapGcd(currentYaw, currentPitch, eye, candidate);
                    HitResult hit = RayTraceUtils.rayTraceTowards(player, new Rotation(snapped[0], snapped[1]), maxReach, player.isCrouching());
                    if (hit instanceof BlockHitResult bhr
                            && bhr.getBlockPos().equals(targetPos)
                            && bhr.getDirection() == Direction.UP) {
                        return snapped;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Wyznacza optymalny wektor celowania dla HARVEST oraz REPLANT.
     * Celując w dolną nasadę kłosa (Y + 0.08 do 0.12), promień wzroku gracza
     * automatycznie przecina górną ścianę bloku Farmland pod spodem (Y - 0.0625 = farmland Y + 0.9375).
     * Pozwala to na ścięcie i zasadzenie z minimalną zmianą kąta pitch (<= 2 * gcd).
     */
    public static Vec3 computeSharedHarvestReplantVec(Vec3 predictedEye, BlockPos cropPos, boolean isHarvest) {
        double targetY = isHarvest ? (cropPos.getY() + 0.10D) : (cropPos.getY() - 0.0625D);
        // Clamp XZ do [0.22, 0.78] gwarantuje trafienie w Direction.UP bloku Farmland
        double targetX = Mth.clamp(predictedEye.x, cropPos.getX() + 0.22D, cropPos.getX() + 0.78D);
        double targetZ = Mth.clamp(predictedEye.z, cropPos.getZ() + 0.22D, cropPos.getZ() + 0.78D);
        return new Vec3(targetX, targetY, targetZ);
    }
}
