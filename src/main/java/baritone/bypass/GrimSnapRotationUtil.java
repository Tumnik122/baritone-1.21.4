package baritone.bypass;

import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Optional;

/**
 * Ultra-Fast GrimAC-Safe Snap Rotation & Shared Ray Alignment Utility.
 * Opracowane we współpracy z modelem ARGON 4 dla trybów #praca oraz #sel cleararea.
 */
public final class GrimSnapRotationUtil {

    public record VerifiedBlockAim(Rotation rotation, Direction hitFace, Vec3 hitVec) {}

    public record VerifiedPlacementAim(
            Rotation rotation,
            BlockPos supportPos,
            Direction hitFace,
            Vec3 hitVec,
            BlockHitResult hitResult
    ) {}

    private static final double[][] PLACEMENT_FACE_UVS = {
            {0.50D, 0.50D},
            {0.32D, 0.50D}, {0.68D, 0.50D}, {0.50D, 0.32D}, {0.50D, 0.68D},
            {0.32D, 0.32D}, {0.68D, 0.32D}, {0.32D, 0.68D}, {0.68D, 0.68D},
            {0.20D, 0.50D}, {0.80D, 0.50D}, {0.50D, 0.20D}, {0.50D, 0.80D},
            {0.20D, 0.20D}, {0.80D, 0.20D}, {0.20D, 0.80D}, {0.80D, 0.80D}
    };

    private static final double[] CROP_Y_OFFSETS = {0.72D, 0.60D, 0.82D, 0.50D, 0.38D};
    private static final double[] XZ_OFFSETS = {0.50D, 0.35D, 0.65D, 0.25D, 0.75D};
    private static final double[] FACE_GRID = {0.50D, 0.25D, 0.75D, 0.15D, 0.85D};

    private GrimSnapRotationUtil() {}

    /**
     * Wyznacza skwantowaną pod GrimAC (GCD) rotację o minimalnej delcie kątowej,
     * która gwarantuje trafienie promieniem dokładnie w ściankę supportFace bloku supportPos (dla #sel fill / placement).
     */
    public static VerifiedPlacementAim findVerifiedPlacementSnapRotation(
            LocalPlayer player,
            float currentYaw,
            float currentPitch,
            BlockPos supportPos,
            Direction supportFace,
            double maxReach) {

        if (player == null || supportPos == null || supportFace == null || player.level() == null) {
            return null;
        }
        Level world = player.level();
        Vec3 eye = player.getEyePosition(1.0f);
        double maxReachSq = maxReach * maxReach;

        BlockState state = world.getBlockState(supportPos);
        VoxelShape shape = state.getShape(world, supportPos);
        AABB box = shape.isEmpty()
                ? new AABB(supportPos)
                : shape.bounds().move(supportPos);

        if (!isFacePotentiallyVisible(eye, box, supportFace)) {
            return null;
        }

        VerifiedPlacementAim bestAim = null;
        float bestAngularCost = Float.MAX_VALUE;

        for (double[] uv : PLACEMENT_FACE_UVS) {
            Vec3 candidate = pointOnBoxFace(box, supportFace, uv[0], uv[1]);
            if (eye.distanceToSqr(candidate) > maxReachSq) {
                continue;
            }
            float[] snapped = snapGcd(currentYaw, currentPitch, eye, candidate);
            Rotation rot = new Rotation(snapped[0], snapped[1]);
            HitResult hit = RayTraceUtils.rayTraceTowards(player, rot, maxReach, player.isCrouching());
            if (hit instanceof BlockHitResult bhr
                    && bhr.getType() == HitResult.Type.BLOCK
                    && bhr.getBlockPos().equals(supportPos)
                    && bhr.getDirection() == supportFace) {

                float dYaw = Mth.wrapDegrees(snapped[0] - currentYaw);
                float dPitch = snapped[1] - currentPitch;
                float cost = (dYaw * dYaw) + (dPitch * dPitch * 1.15f);

                // Pierwszy punkt (środek ścianki 0.5, 0.5) trafia od razu lub delta jest minimalna -> zwróć natychmiast
                if ((uv[0] == 0.50D && uv[1] == 0.50D) || cost < 16.0f) {
                    return new VerifiedPlacementAim(rot, supportPos, supportFace, bhr.getLocation(), bhr);
                }

                if (cost < bestAngularCost) {
                    bestAngularCost = cost;
                    bestAim = new VerifiedPlacementAim(rot, supportPos, supportFace, bhr.getLocation(), bhr);
                }
            }
        }

        return bestAim;
    }

    /**
     * Wyznacza skwantowaną pod GrimAC (GCD) rotację o minimalnej delcie kątowej,
     * która gwarantuje trafienie promieniem dokładnie w targetPos (dla dowolnego bloku w #sel cleararea / mining).
     */
    public static VerifiedBlockAim findVerifiedBlockSnapRotation(
            LocalPlayer player,
            float currentYaw,
            float currentPitch,
            BlockPos targetPos,
            double maxReach) {

        if (player == null || targetPos == null || player.level() == null) return null;
        Level world = player.level();
        Vec3 eye = player.getEyePosition(1.0f);
        double maxReachSq = maxReach * maxReach;

        BlockState state = world.getBlockState(targetPos);
        VoxelShape shape = state.getShape(world, targetPos);
        AABB box = shape.isEmpty()
                ? new AABB(targetPos)
                : shape.bounds().move(targetPos);

        // 1. Punkt najbliższy linii wzroku na bryle AABB (z marginesem 0.08m od krawędzi przeciw pudłom GCD)
        double marginX = Math.min(0.08D, (box.maxX - box.minX) * 0.25D);
        double marginY = Math.min(0.08D, (box.maxY - box.minY) * 0.25D);
        double marginZ = Math.min(0.08D, (box.maxZ - box.minZ) * 0.25D);

        Vec3 clampedClosest = new Vec3(
                Mth.clamp(eye.x, box.minX + marginX, box.maxX - marginX),
                Mth.clamp(eye.y, box.minY + marginY, box.maxY - marginY),
                Mth.clamp(eye.z, box.minZ + marginZ, box.maxZ - marginZ)
        );

        VerifiedBlockAim fastCheck = testCandidatePoint(player, currentYaw, currentPitch, eye, clampedClosest, targetPos, maxReach, maxReachSq);
        if (fastCheck != null) {
            return fastCheck;
        }

        // 2. Skanowanie widocznych ścian i punktów siatki (wybór kandydata o najmniejszej delcie od aktualnego celownika)
        VerifiedBlockAim bestAim = null;
        float bestAngularCost = Float.MAX_VALUE;

        for (double u : FACE_GRID) {
            for (double v : FACE_GRID) {
                for (Direction face : Direction.values()) {
                    if (!isFacePotentiallyVisible(eye, box, face)) {
                        continue;
                    }
                    Vec3 candidate = pointOnBoxFace(box, face, u, v);
                    VerifiedBlockAim verified = testCandidatePoint(player, currentYaw, currentPitch, eye, candidate, targetPos, maxReach, maxReachSq);
                    if (verified != null) {
                        float dYaw = Mth.wrapDegrees(verified.rotation().getYaw() - currentYaw);
                        float dPitch = verified.rotation().getPitch() - currentPitch;
                        float cost = (dYaw * dYaw) + (dPitch * dPitch * 1.15f);
                        if (cost < bestAngularCost) {
                            bestAngularCost = cost;
                            bestAim = verified;
                            // Jeśli delta jest minimalna (poniżej 4 stopni), zwróć od razu
                            if (cost < 16.0f) {
                                return bestAim;
                            }
                        }
                    }
                }
            }
        }

        return bestAim;
    }

    private static VerifiedBlockAim testCandidatePoint(
            LocalPlayer player,
            float currentYaw,
            float currentPitch,
            Vec3 eye,
            Vec3 candidate,
            BlockPos targetPos,
            double maxReach,
            double maxReachSq) {

        if (eye.distanceToSqr(candidate) > maxReachSq) {
            return null;
        }
        float[] snapped = snapGcd(currentYaw, currentPitch, eye, candidate);
        Rotation rot = new Rotation(snapped[0], snapped[1]);
        HitResult hit = RayTraceUtils.rayTraceTowards(player, rot, maxReach, player.isCrouching());
        if (hit instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK && bhr.getBlockPos().equals(targetPos)) {
            return new VerifiedBlockAim(rot, bhr.getDirection(), bhr.getLocation());
        }
        return null;
    }

    private static boolean isFacePotentiallyVisible(Vec3 eye, AABB box, Direction face) {
        return switch (face) {
            case DOWN -> eye.y <= box.minY + 0.05D;
            case UP -> eye.y >= box.maxY - 0.05D;
            case NORTH -> eye.z <= box.minZ + 0.05D;
            case SOUTH -> eye.z >= box.maxZ - 0.05D;
            case WEST -> eye.x <= box.minX + 0.05D;
            case EAST -> eye.x >= box.maxX - 0.05D;
        };
    }

    private static Vec3 pointOnBoxFace(AABB box, Direction face, double u, double v) {
        double x = Mth.lerp(u, box.minX, box.maxX);
        double y = Mth.lerp(v, box.minY, box.maxY);
        double z = Mth.lerp(u, box.minZ, box.maxZ);
        return switch (face) {
            case DOWN -> new Vec3(x, box.minY + 0.002D, Mth.lerp(v, box.minZ, box.maxZ));
            case UP -> new Vec3(x, box.maxY - 0.002D, Mth.lerp(v, box.minZ, box.maxZ));
            case NORTH -> new Vec3(x, y, box.minZ + 0.002D);
            case SOUTH -> new Vec3(x, y, box.maxZ - 0.002D);
            case WEST -> new Vec3(box.minX + 0.002D, y, z);
            case EAST -> new Vec3(box.maxX - 0.002D, y, z);
        };
    }

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
     * Wyznacza punkt na AABB plonu/grządki wymagający minimalnego kąta obrotu myszą
     * z uwzględnieniem pre-aimingu w sprincie (0.65 ticka lookahead) oraz serwerowego marginesu bezpieczeństwa.
     */
    public static Vec3 getOptimalHitVec(LocalPlayer player, BlockPos pos, BlockState state, boolean isPlanting) {
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 vel = player.getDeltaMovement();
        Vec3 predictedEye = eye.add(vel.x * 0.65D, 0.0D, vel.z * 0.65D);

        AABB box;
        if (isPlanting) {
            // Celujemy w górną ściankę Farmlandu (Y = +0.9375) lub Soul Sandu (Y = +1.0)
            double topY = state.getShape(player.level(), pos).isEmpty()
                    ? 0.9375D
                    : state.getShape(player.level(), pos).max(Direction.Axis.Y);
            box = new AABB(
                    pos.getX() + 0.18D, pos.getY() + topY - 0.05D, pos.getZ() + 0.18D,
                    pos.getX() + 0.82D, pos.getY() + topY,         pos.getZ() + 0.82D
            );
        } else {
            VoxelShape shape = state.getShape(player.level(), pos);
            AABB local = shape.isEmpty() ? new AABB(0.1D, 0.0D, 0.1D, 0.9D, 0.8D, 0.9D) : shape.bounds();
            box = new AABB(
                    pos.getX() + Math.min(local.minX + 0.08D, 0.45D),
                    pos.getY() + Math.min(local.minY + 0.08D, 0.35D),
                    pos.getZ() + Math.min(local.minZ + 0.08D, 0.45D),
                    pos.getX() + Math.max(local.maxX - 0.08D, 0.55D),
                    pos.getY() + Math.max(local.maxY - 0.08D, 0.65D),
                    pos.getZ() + Math.max(local.maxZ - 0.08D, 0.55D)
            );
        }

        double targetX = Mth.clamp(predictedEye.x, box.minX, box.maxX);
        double targetY = Mth.clamp(predictedEye.y, box.minY, box.maxY);
        double targetZ = Mth.clamp(predictedEye.z, box.minZ, box.maxZ);
        return new Vec3(targetX, targetY, targetZ);
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
        float deltaPitch = Mth.clamp(rawPitch, -89.5F, 89.5F) - currentPitch;

        // Błyskawiczny obrót do pełnego kąta w 1 ticku (pełna prędkość interakcji GrimAC GCD)
        int mouseX = Math.round(deltaYaw / gcd);
        int mouseY = Math.round(deltaPitch / gcd);

        if (mouseX == 0 && mouseY == 0) {
            return new float[]{currentYaw, currentPitch};
        }

        // GrimAC AimDuplicateLook guard: wymuś min. 1 krok GCD na osi X przy zmianie pitch, jeśli myszka się rusza
        if (mouseX == 0 && mouseY != 0 && Math.abs(deltaYaw) > 1e-4f) {
            mouseX = deltaYaw > 0 ? 1 : -1;
        }

        float nextYaw = currentYaw + (mouseX * gcd);
        float nextPitch = currentPitch + (mouseY * gcd);

        while (nextPitch > 89.5F) {
            mouseY--;
            nextPitch = currentPitch + (mouseY * gcd);
        }
        while (nextPitch < -89.5F) {
            mouseY++;
            nextPitch = currentPitch + (mouseY * gcd);
        }

        return new float[]{nextYaw, nextPitch};
    }

    /**
     * Zwraca skwantowaną GCD rotację gwarantującą trafienie w docelowy BlockPos (i Direction.UP dla REPLANT).
     * Multipoint Ray-Verified Aiming eliminuje fałszywe przecięcia Farmlandu zamiast kłosa pszenicy.
     */
    public static float[] findVerifiedSnapRotation(
            LocalPlayer player, float currentYaw, float currentPitch,
            BlockPos targetPos, boolean isHarvest, double maxReach) {

        if (player == null || targetPos == null || player.level() == null) return null;
        Vec3 eye = player.getEyePosition(1.0f);
        double maxReachSq = maxReach * maxReach;

        // 1. Szybki pre-check: Closest-Point-on-AABB (0ms w >95% przypadków)
        BlockState targetState = player.level().getBlockState(targetPos);
        Vec3 optimal = getOptimalHitVec(player, targetPos, targetState, !isHarvest);
        if (eye.distanceToSqr(optimal) <= maxReachSq) {
            float[] fastSnapped = snapGcd(currentYaw, currentPitch, eye, optimal);
            HitResult hit = RayTraceUtils.rayTraceTowards(player, new Rotation(fastSnapped[0], fastSnapped[1]), maxReach, player.isCrouching());
            if (hit instanceof BlockHitResult bhr && bhr.getBlockPos().equals(targetPos)) {
                if (isHarvest || bhr.getDirection() == Direction.UP) {
                    return fastSnapped;
                }
            }
        }

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
        double targetY = isHarvest ? (cropPos.getY() + 0.65D) : (cropPos.getY() - 0.0625D);
        double targetX = cropPos.getX() + 0.5D;
        double targetZ = cropPos.getZ() + 0.5D;
        return new Vec3(targetX, targetY, targetZ);
    }

    private static final int[][] GCD_SPIRAL_OFFSETS = {
            {0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {-1, -1}, {1, -1}, {-1, 1},
            {2, 0}, {-2, 0}, {0, 2}, {0, -2},
            {2, 1}, {-2, 1}, {2, -1}, {-2, -1},
            {1, 2}, {-1, 2}, {1, -2}, {-1, -2}
    };

    /**
     * Znajduje 1-tickowy kąt na siatce GCD pod GrimAC, który jednocześnie:
     * 1. Trafia w hitbox kłosa (cropPos = farmlandPos.above()) przed zniszczeniem.
     * 2. Po zniszczeniu kłosa natychmiast trafia w górną ścianę (Direction.UP) Farmlandu (farmlandPos).
     * Umożliwia zbiór i zasianie (Harvest + Instant Replant) w JEDNYM TICKU (50 ms) pod GrimAC!
     */
    public static Rotation findSharedCropAndFarmlandSnap(
            LocalPlayer player,
            BlockPos farmlandPos,
            double reach) {

        if (player == null || farmlandPos == null || player.level() == null) {
            return player != null ? new Rotation(player.getYRot(), player.getXRot()) : new Rotation(0.0F, 0.0F);
        }

        Vec3 eyePos = player.getEyePosition(1.0F);
        // Celujemy w środek górnej powierzchni Farmland (Y + 0.9375), przez nasadę kłosa
        Vec3 sharedSweetSpot = new Vec3(
                farmlandPos.getX() + 0.5D,
                farmlandPos.getY() + 0.9375D,
                farmlandPos.getZ() + 0.5D
        );

        float currentYaw = player.getYRot();
        float currentPitch = player.getXRot();
        float[] baseSnap = snapGcd(currentYaw, currentPitch, eyePos, sharedSweetSpot);
        Rotation baseRot = new Rotation(baseSnap[0], baseSnap[1]);

        AABB farmlandTopBox = new AABB(
                farmlandPos.getX(), farmlandPos.getY(), farmlandPos.getZ(),
                farmlandPos.getX() + 1.0D, farmlandPos.getY() + 0.9375D, farmlandPos.getZ() + 1.0D
        );

        float gcd = getGcd();
        for (int[] offset : GCD_SPIRAL_OFFSETS) {
            float candYaw = baseRot.getYaw() + (offset[0] * gcd);
            float candPitch = Mth.clamp(baseRot.getPitch() + (offset[1] * gcd), -89.5F, 89.5F);

            Rotation candRot = new Rotation(candYaw, candPitch);
            Vec3 lookVec = RotationUtils.calcLookDirectionFromRotation(candRot);
            Vec3 endPos = eyePos.add(lookVec.scale(reach));

            Optional<Vec3> hitVec = farmlandTopBox.clip(eyePos, endPos);
            if (hitVec.isPresent() && Math.abs(hitVec.get().y - (farmlandPos.getY() + 0.9375D)) < 1.0E-3D) {
                // Zweryfikuj czy promień przecina kłos lub sam farmland
                HitResult hit = RayTraceUtils.rayTraceTowards(player, candRot, reach, player.isCrouching());
                if (hit instanceof BlockHitResult bhr) {
                    BlockPos bp = bhr.getBlockPos();
                    if (bp.equals(farmlandPos.above()) || (bp.equals(farmlandPos) && bhr.getDirection() == Direction.UP)) {
                        return candRot;
                    }
                }
            }
        }

        return baseRot;
    }
}
