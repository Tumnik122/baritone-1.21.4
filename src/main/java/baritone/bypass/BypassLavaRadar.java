package baritone.bypass;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * Zaawansowany radar lawy i zagrożeń dla BypassProcess (Argon 4 & dj).
 * 
 * Odpowiada za:
 * 1. Długodystansowy radar (do 30 bloków w przód) z wyznaczaniem strefy bezpiecznego skrętu (5 bloków przed lawą).
 * 2. Analizę sektorów (LEWO vs PRAWO) pod kątem minimalnego dystansu do lawy.
 * 3. Wykrywanie ukrytych wycieków lawy za ścianami oraz odsłoniętej lawy w otwartej przestrzeni.
 */
public final class BypassLavaRadar {

    private BypassLavaRadar() {}

    public static boolean isLavaFluid(Level level, BlockPos pos) {
        if (level == null || pos == null) return false;
        FluidState fs = level.getFluidState(pos);
        return !fs.isEmpty() && (fs.is(FluidTags.LAVA) || fs.is(Fluids.LAVA) || fs.is(Fluids.FLOWING_LAVA));
    }

    public static boolean isLava(Level level, BlockPos pos) {
        return isLavaFluid(level, pos);
    }

    public static boolean isWater(Level level, BlockPos pos) {
        if (level == null || pos == null) return false;
        FluidState fs = level.getFluidState(pos);
        return !fs.isEmpty() && (fs.is(FluidTags.WATER) || fs.is(Fluids.WATER) || fs.is(Fluids.FLOWING_WATER));
    }

    public static boolean isHazardNear(Level level, BlockPos pos) {
        if (level == null || pos == null) return false;
        if (level.getFluidState(pos).is(FluidTags.LAVA)) return true;
        for (Direction dir : Direction.values()) {
            BlockPos adj = pos.relative(dir);
            if (level.getFluidState(adj).is(FluidTags.LAVA)) return true;
        }

        FluidState selfFluid = level.getFluidState(pos);
        if (selfFluid.is(FluidTags.WATER) && selfFluid.isSource()) return true;
        FluidState aboveFluid = level.getFluidState(pos.above());
        if (aboveFluid.is(FluidTags.WATER) && aboveFluid.isSource()) return true;

        return false;
    }

    public static boolean hasDirectLavaAboveOrBehind(Level level, BlockPos orePos) {
        if (level == null || orePos == null) return false;
        for (Direction dir : Direction.values()) {
            if (level.getFluidState(orePos.relative(dir)).is(FluidTags.LAVA)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasHiddenHazardBehind(Level level, BlockPos targetPos, Direction miningDir) {
        if (level == null || targetPos == null) return false;
        if (BypassNavigator.isPassable(level, targetPos)) {
            return false;
        }
        if (isLava(level, targetPos)) {
            return true;
        }
        Direction backDir = (miningDir != null) ? miningDir.getOpposite() : null;
        for (Direction dir : Direction.values()) {
            if (dir == backDir) continue;
            if (dir == Direction.DOWN) continue;

            BlockPos neighbor = targetPos.relative(dir);
            if (isLava(level, neighbor)) {
                return true;
            }
            if (dir == Direction.UP && level.getBlockState(neighbor).getBlock() instanceof FallingBlock) {
                if (isLava(level, neighbor.above())) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean hasExposedLavaInOpenSpace(Level level, BlockPos airStartPos, Direction moveDir) {
        if (level == null || airStartPos == null) return false;
        BlockPos headPos = airStartPos.above();
        BlockPos floorPos = airStartPos.below();
        if (isLava(level, airStartPos) || isLava(level, headPos) || isLava(level, floorPos) || isLava(level, headPos.above())) {
            return true;
        }
        if (BypassNavigator.isPassable(level, floorPos) && isLava(level, floorPos.below())) {
            return true;
        }

        Direction backDir = (moveDir != null) ? moveDir.getOpposite() : null;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (dir == backDir) continue;
            if (isLava(level, airStartPos.relative(dir)) || isLava(level, headPos.relative(dir))) {
                return true;
            }
            BlockPos sideAir = airStartPos.relative(dir);
            if (BypassNavigator.isPassable(level, sideAir)) {
                if (isLava(level, sideAir.below()) || isLava(level, sideAir.relative(dir))
                        || (moveDir != null && isLava(level, sideAir.relative(moveDir)))) {
                    return true;
                }
            }
        }
        return false;
    }

    public static int findForwardLavaDistance(Level level, BlockPos feet, Direction forward, int maxRange) {
        if (level == null || feet == null || forward == null) {
            return -1;
        }
        Direction leftDir = forward.getCounterClockWise();
        Direction rightDir = forward.getClockWise();

        for (int d = 1; d <= maxRange; d++) {
            BlockPos stepFeet = feet.relative(forward, d);
            if (!level.hasChunkAt(stepFeet)) {
                break;
            }

            BlockPos stepHead = stepFeet.above();
            BlockPos floorBelow = stepFeet.below();
            BlockPos ceilingAbove = stepHead.above();

            if (isLava(level, stepFeet) || isLava(level, stepHead) || isLava(level, floorBelow) || isLava(level, ceilingAbove)) {
                return d;
            }
            if (isHazardNear(level, stepFeet) || isHazardNear(level, stepHead)) {
                return d;
            }

            BlockPos sideLeftFeet = stepFeet.relative(leftDir);
            BlockPos sideRightFeet = stepFeet.relative(rightDir);
            if (isLava(level, sideLeftFeet) || isLava(level, sideLeftFeet.above())
                    || isLava(level, sideRightFeet) || isLava(level, sideRightFeet.above())) {
                return d;
            }
        }
        return -1;
    }

    public static double evaluateLavaDistanceInSector(Level level, BlockPos feet, Direction branchDir, Direction forwardDir, int maxRange) {
        if (level == null || feet == null || branchDir == null) {
            return 999.0D;
        }
        double minLavaDist = 999.0D;

        for (int b = 1; b <= maxRange; b++) {
            BlockPos base = feet.relative(branchDir, b);
            if (!level.hasChunkAt(base)) {
                break;
            }

            for (int f = -2; f <= 12; f++) {
                BlockPos pos = (forwardDir != null) ? base.relative(forwardDir, f) : base;
                if (!level.hasChunkAt(pos)) {
                    continue;
                }

                if (isLava(level, pos) || isLava(level, pos.above()) || isLava(level, pos.below()) || isHazardNear(level, pos)) {
                    double dist = Math.sqrt(b * b + Math.max(0, f) * Math.max(0, f));
                    if (dist < minLavaDist) {
                        minLavaDist = dist;
                    }
                }
            }
        }
        return minLavaDist;
    }

    public static Direction chooseSafestBranchAwayFromLava(BypassProcess proc, Level level, BlockPos feet, Direction forwardDir, int scanRange) {
        Direction leftDir = forwardDir.getCounterClockWise();
        Direction rightDir = forwardDir.getClockWise();

        boolean leftSafe = BypassNavigator.isSafeBranchDirection(proc, level, feet, leftDir, false);
        boolean rightSafe = BypassNavigator.isSafeBranchDirection(proc, level, feet, rightDir, false);

        double distLeft = evaluateLavaDistanceInSector(level, feet, leftDir, forwardDir, scanRange);
        double distRight = evaluateLavaDistanceInSector(level, feet, rightDir, forwardDir, scanRange);

        String leftDesc = distLeft >= 900.0D ? "CZYSTO (>30m)" : String.format("%.1fm", distLeft);
        String rightDesc = distRight >= 900.0D ? "CZYSTO (>30m)" : String.format("%.1fm", distRight);

        if (leftSafe && rightSafe) {
            if (distLeft > distRight + 2.0D) {
                proc.logDirect(String.format("§a[Bypass Radar] Analiza sektorów: Lewo=%s, Prawo=%s -> Lawa dalej na LEWO (skręt w %s)!",
                        leftDesc, rightDesc, leftDir));
                return leftDir;
            } else if (distRight > distLeft + 2.0D) {
                proc.logDirect(String.format("§a[Bypass Radar] Analiza sektorów: Lewo=%s, Prawo=%s -> Lawa dalej na PRAWO (skręt w %s)!",
                        leftDesc, rightDesc, rightDir));
                return rightDir;
            }
            return BypassNavigator.pickBestBranchDirection(proc, level, feet, leftDir, rightDir);
        }

        if (leftSafe) {
            proc.logDirect(String.format("§a[Bypass Radar] Bezpieczna świeża ściana na LEWO (Lawa: L=%s vs P=%s)", leftDesc, rightDesc));
            return leftDir;
        }
        if (rightSafe) {
            proc.logDirect(String.format("§a[Bypass Radar] Bezpieczna świeża ściana na PRAWO (Lawa: P=%s vs L=%s)", rightDesc, leftDesc));
            return rightDir;
        }

        return distLeft >= distRight ? leftDir : rightDir;
    }

    public static boolean isFrontDirectlyBlockedByHazard(BypassProcess proc, Level level, BlockPos feet, Direction forward) {
        BlockPos frontFeet = feet.relative(forward);
        long frontKey2D = BypassNavigator.to2DKey(frontFeet.getX(), frontFeet.getZ());

        if (proc.getHazardColumns2D().contains(frontKey2D)) {
            return true;
        }

        for (int step = 1; step <= 4; step++) {
            BlockPos stepFeet = feet.relative(forward, step);
            BlockPos stepHead = stepFeet.above();
            BlockPos ceilingAbove = stepHead.above();
            BlockPos floorBelow = stepFeet.below();

            if (isLava(level, stepFeet)
                    || isLava(level, stepHead)
                    || isLava(level, ceilingAbove)
                    || isLava(level, floorBelow)) {
                return true;
            }

            if (step == 1 && (!BypassHazardSealer.hasStableFloorSupport(level, floorBelow) || isLava(level, floorBelow))) {
                return true;
            }

            if (step == 1) {
                if (!BypassNavigator.isPassable(level, stepFeet) && hasHiddenHazardBehind(level, stepFeet, forward)) {
                    return true;
                }
                if (!BypassNavigator.isPassable(level, stepHead) && hasHiddenHazardBehind(level, stepHead, forward)) {
                    return true;
                }
            }

            if (BypassNavigator.isPassable(level, stepFeet) || BypassNavigator.isPassable(level, stepHead)) {
                if (hasExposedLavaInOpenSpace(level, stepFeet, forward)) {
                    return true;
                }
            }
        }

        return false;
    }
}

