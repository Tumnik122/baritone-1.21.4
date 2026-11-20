package com.astra.revolution.pathing;

import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * Bezalokacyjny rdzeń matematyczny dla silników ruchu Astra Revolution (1.21.4).
 * Zapewnia kwantyzację GCD (GrimAC), Centripetal Catmull-Rom (alpha = 0.5)
 * oraz dokładne testy kolizji VoxelShape.
 */
public final class AstraLocomotionMath {

    public static final double RAD_TO_DEG = 180.0D / Math.PI;
    public static final double PLAYER_HALF_WIDTH = 0.30D;
    public static final double CORNER_WALL_MARGIN = 0.18D;
    public static final double SAFE_CORNER_RADIUS = PLAYER_HALF_WIDTH + CORNER_WALL_MARGIN; // 0.48m

    private AstraLocomotionMath() {}

    /**
     * Kwantyzuje docelową rotację do siatki czułości myszy klienta (Vanilla GCD)
     * z opcjonalnym limitem prędkości kątowej na tick.
     */
    public static Rotation quantizeRotationGcd(
            IPlayerContext ctx,
            float targetYaw,
            float targetPitch,
            float currentYaw,
            float currentPitch,
            float maxYawStepPerTick,
            float maxPitchStepPerTick
    ) {
        float deltaYaw = Mth.wrapDegrees(targetYaw - currentYaw);
        float deltaPitch = Mth.clamp(targetPitch, -90.0F, 90.0F) - currentPitch;

        if (maxYawStepPerTick > 0.0F) {
            deltaYaw = Mth.clamp(deltaYaw, -maxYawStepPerTick, maxYawStepPerTick);
        }
        if (maxPitchStepPerTick > 0.0F) {
            deltaPitch = Mth.clamp(deltaPitch, -maxPitchStepPerTick, maxPitchStepPerTick);
        }

        double sensitivity = ctx.minecraft().options.sensitivity().get();
        double f = sensitivity * 0.6D + 0.2D;
        double gcd = f * f * f * 8.0D * 0.15D;

        if (gcd > 1.0E-6D) {
            deltaYaw = (float) (Math.round(deltaYaw / gcd) * gcd);
            deltaPitch = (float) (Math.round(deltaPitch / gcd) * gcd);
        }

        float finalYaw = currentYaw + deltaYaw;
        float finalPitch = Mth.clamp(currentPitch + deltaPitch, -90.0F, 90.0F);
        return new Rotation(finalYaw, finalPitch);
    }

    /**
     * Bezalokacyjny ewaluator Centripetal Catmull-Rom Spline (alpha = 0.5, algorytm Barry-Goldman).
     * Eliminuje przeregulowania i pętle przy nierównych odstępach węzłów (np. proste + przekątne).
     * Wynik (X, Z) zapisywany jest bezpośrednio do tablicy outXZ[2].
     */
    public static void evalCentripetalCatmullRom2D(
            double x0, double z0,
            double x1, double z1,
            double x2, double z2,
            double x3, double z3,
            double u,
            double[] outXZ
    ) {
        double dt01 = centripetalKnotDelta(x0, z0, x1, z1);
        double dt12 = centripetalKnotDelta(x1, z1, x2, z2);
        double dt23 = centripetalKnotDelta(x2, z2, x3, z3);

        double t0 = 0.0D;
        double t1 = dt01;
        double t2 = t1 + dt12;
        double t3 = t2 + dt23;

        double t = t1 + Mth.clamp(u, 0.0D, 1.0D) * (t2 - t1);

        // Poziom 1 (A1, A2, A3)
        double a1x = ((t1 - t) * x0 + (t - t0) * x1) / dt01;
        double a1z = ((t1 - t) * z0 + (t - t0) * z1) / dt01;

        double a2x = ((t2 - t) * x1 + (t - t1) * x2) / dt12;
        double a2z = ((t2 - t) * z1 + (t - t1) * z2) / dt12;

        double a3x = ((t3 - t) * x2 + (t - t2) * x3) / dt23;
        double a3z = ((t3 - t) * z2 + (t - t2) * z3) / dt23;

        // Poziom 2 (B1, B2)
        double dt02 = t2 - t0;
        double dt13 = t3 - t1;

        double b1x = ((t2 - t) * a1x + (t - t0) * a2x) / dt02;
        double b1z = ((t2 - t) * a1z + (t - t0) * a2z) / dt02;

        double b2x = ((t3 - t) * a2x + (t - t1) * a3x) / dt13;
        double b2z = ((t3 - t) * a2z + (t - t1) * a3z) / dt13;

        // Poziom 3 (C)
        outXZ[0] = ((t2 - t) * b1x + (t - t1) * b2x) / dt12;
        outXZ[1] = ((t2 - t) * b1z + (t - t1) * b2z) / dt12;
    }

    private static double centripetalKnotDelta(double ax, double az, double bx, double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        double distSq = dx * dx + dz * dz;
        if (distSq < 1.0E-8D) {
            return 1.0D; // Zabezpieczenie przed dzieleniem przez zero dla zduplikowanych węzłów
        }
        // ||P_i+1 - P_i||^0.5 = (distSq)^0.25 = sqrt(sqrt(distSq))
        return Math.sqrt(Math.sqrt(distSq));
    }

    /** Dokładny test kolizji Vanilla (zastępuje przestarzałe BlockState.isSolid()). */
    public static boolean hasCollision(Level world, BlockPos.MutableBlockPos mpos, int x, int y, int z) {
        mpos.set(x, y, z);
        BlockState state = world.getBlockState(mpos);
        if (state.isAir()) return false;
        return !state.getCollisionShape(world, mpos).isEmpty();
    }

    /** Sprawdza, czy blok jest pełnym, bezpiecznym podłożem pod sprint/skok (bez lawy, magmy, miodu, soul sand). */
    public static boolean isFastWalkableFloor(Level world, BlockPos.MutableBlockPos mpos, int x, int y, int z) {
        mpos.set(x, y, z);
        BlockState state = world.getBlockState(mpos);
        if (state.isAir()) return false;
        if (state.getCollisionShape(world, mpos).isEmpty()) return false;
        return !state.is(Blocks.SOUL_SAND)
                && !state.is(Blocks.HONEY_BLOCK)
                && !state.is(Blocks.MAGMA_BLOCK)
                && !state.is(Blocks.CACTUS)
                && !state.is(Blocks.POWDER_SNOW)
                && !state.is(Blocks.SLIME_BLOCK);
    }

    /** Sprawdza, czy woksel na wysokości stóp/ciała spowalnia lub rani gracza. */
    public static boolean isBodyHazardOrSlowdown(Level world, BlockPos.MutableBlockPos mpos, int x, int y, int z) {
        mpos.set(x, y, z);
        BlockState state = world.getBlockState(mpos);
        if (state.isAir()) return false;
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty()) {
            return true; // Woda lub lawa na torze skoku lądowego
        }
        return state.is(Blocks.COBWEB)
                || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.WITHER_ROSE)
                || state.is(Blocks.CACTUS);
    }

    public static boolean isWater(Level world, BlockPos.MutableBlockPos mpos, int x, int y, int z) {
        mpos.set(x, y, z);
        return world.getFluidState(mpos).is(FluidTags.WATER);
    }

    public static boolean isAirAndNoFluid(Level world, BlockPos.MutableBlockPos mpos, int x, int y, int z) {
        mpos.set(x, y, z);
        BlockState state = world.getBlockState(mpos);
        return state.isAir() && state.getFluidState().isEmpty();
    }

    /** Zwraca +1 dla kolumny wznoszącej (Soul Sand), -1 dla wiru (Magma), 0 dla braku bąbelków. */
    public static int getBubbleColumnSign(Level world, BlockPos.MutableBlockPos mpos, int x, int y, int z) {
        mpos.set(x, y, z);
        BlockState state = world.getBlockState(mpos);
        if (!state.is(Blocks.BUBBLE_COLUMN)) {
            return 0;
        }
        return state.getValue(BubbleColumnBlock.DRAG_DOWN) ? -1 : 1;
    }

    /**
     * Bezalokacyjny test kolizji AABB (z marginesem 0.18m od ścian) w punkcie (tx, ty, tz)
     * dla wyprostowanego gracza (wysokość 1.8m, promień 0.48m).
     */
    public static boolean isHorizontalPointClear(Level world, BlockPos.MutableBlockPos mpos, double tx, double baseY, double tz) {
        int minX = Mth.floor(tx - SAFE_CORNER_RADIUS);
        int maxX = Mth.floor(tx + SAFE_CORNER_RADIUS);
        int minZ = Mth.floor(tz - SAFE_CORNER_RADIUS);
        int maxZ = Mth.floor(tz + SAFE_CORNER_RADIUS);
        int minY = Mth.floor(baseY + 0.05D);
        int maxY = Mth.floor(baseY + 1.75D);

        for (int bx = minX; bx <= maxX; bx++) {
            for (int bz = minZ; bz <= maxZ; bz++) {
                for (int by = minY; by <= maxY; by++) {
                    if (hasCollision(world, mpos, bx, by, bz)
                            || isBodyHazardOrSlowdown(world, mpos, bx, by, bz)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}

