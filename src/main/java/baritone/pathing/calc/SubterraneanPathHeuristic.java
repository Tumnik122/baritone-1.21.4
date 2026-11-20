package baritone.pathing.calc;

import baritone.pathing.movement.CalculationContext;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Adaptacyjna heurystyka podziemna (Directed Tunnel Spear) dla Baritone 1.21.4.
 * Eliminuje pułapkę Air-Sphere Expansion Trap w litym Deepslate/Stone/Tuff,
 * zachowując deterministyczne domykanie węzłów (brak zapętleń) i 0 alokacji w pętli A*.
 */
public final class SubterraneanPathHeuristic {

    private static final double AIR_MIN_TICKS_PER_BLOCK = 3.029D; // 3.564 * 0.85
    private static final double SPRINT_BASE_TICKS = 3.564D;
    private static final double VANILLA_COOLDOWN_TICKS = 5.0D;
    private static final double JUMP_UP_TICKS = 2.5D;
    private static final double STEP_DOWN_TICKS = 0.8D;
    private static final double FUNNEL_TIEBREAKER_WEIGHT = 0.09D;
    private static final double MAX_PROBE_DISTANCE = 64.0D;
    private static final double OCTILE_DIAG_FACTOR = Math.sqrt(2.0D) - 1.0D;

    // Stan skalibrowany raz na początku obliczeń A* (immutable podczas jednego wyszukiwania)
    private int startX, startY, startZ;
    private int goalX, goalY, goalZ;
    private double lineDx, lineDy, lineDz;
    private double invLineLenSq;

    private double calibratedSlopeTicksPerBlock = AIR_MIN_TICKS_PER_BLOCK * 1.15D;
    private double solidDensityRatio = 0.0D;
    private double verticalUpSlope = SPRINT_BASE_TICKS + JUMP_UP_TICKS;
    private double verticalDownSlope = SPRINT_BASE_TICKS + STEP_DOWN_TICKS;

    // Prealokowany scratch do zapytań o kolizję (w wątku A* — pojedynczy wątek na instancję)
    private final BlockPos.MutableBlockPos probePos = new BlockPos.MutableBlockPos();

    /**
     * Kalibruje heurystykę na starcie AStarPathFinder.calculate().
     * Wykonuje pojedynczy, bezalokacyjny raycast 3D DDA (Feet + Head) w kierunku celu.
     */
    public void calibrateForSearch(
            CalculationContext ctx,
            int sx, int sy, int sz,
            int gx, int gy, int gz
    ) {
        this.startX = sx;
        this.startY = sy;
        this.startZ = sz;
        this.goalX = gx;
        this.goalY = gy;
        this.goalZ = gz;

        this.lineDx = gx - sx;
        this.lineDy = gy - sy;
        this.lineDz = gz - sz;
        double lenSq = lineDx * lineDx + lineDy * lineDy + lineDz * lineDz;
        this.invLineLenSq = (lenSq > 1.0E-6D) ? (1.0D / lenSq) : 0.0D;

        if (lenSq < 4.0D) {
            this.solidDensityRatio = 0.0D;
            this.calibratedSlopeTicksPerBlock = AIR_MIN_TICKS_PER_BLOCK;
            return;
        }

        double totalDist = Math.sqrt(lenSq);
        double probeDist = Math.min(totalDist, MAX_PROBE_DISTANCE);
        double dirX = lineDx / totalDist;
        double dirY = lineDy / totalDist;
        double dirZ = lineDz / totalDist;

        // 3D Amanatides-Woo DDA próbkujący korytarz 1x2 od startu w kierunku celu
        int curX = sx;
        int curY = sy;
        int curZ = sz;

        int stepX = dirX > 0.0D ? 1 : (dirX < 0.0D ? -1 : 0);
        int stepY = dirY > 0.0D ? 1 : (dirY < 0.0D ? -1 : 0);
        int stepZ = dirZ > 0.0D ? 1 : (dirZ < 0.0D ? -1 : 0);

        double tDeltaX = (stepX != 0) ? Math.abs(1.0D / dirX) : Double.MAX_VALUE;
        double tDeltaY = (stepY != 0) ? Math.abs(1.0D / dirY) : Double.MAX_VALUE;
        double tDeltaZ = (stepZ != 0) ? Math.abs(1.0D / dirZ) : Double.MAX_VALUE;

        double tMaxX = (stepX != 0) ? (0.5D * tDeltaX) : Double.MAX_VALUE;
        double tMaxY = (stepY != 0) ? (0.5D * tDeltaY) : Double.MAX_VALUE;
        double tMaxZ = (stepZ != 0) ? (0.5D * tDeltaZ) : Double.MAX_VALUE;

        int totalSteps = 0;
        int solidSteps = 0;
        double accumulatedStepCost = 0.0D;
        double t = 0.0D;

        int maxIter = Mth.ceil(probeDist * 2.0D) + 4;
        for (int i = 0; i < maxIter && t <= probeDist; i++) {
            double feetCost = estimateVoxelBreakTicks(ctx, curX, curY, curZ);
            double headCost = estimateVoxelBreakTicks(ctx, curX, curY + 1, curZ);
            double pairBreakCost = feetCost + headCost;

            totalSteps++;
            if (pairBreakCost > 0.01D) {
                solidSteps++;
                accumulatedStepCost += SPRINT_BASE_TICKS + pairBreakCost;
            } else {
                accumulatedStepCost += AIR_MIN_TICKS_PER_BLOCK;
            }

            if (curX == gx && curY == gy && curZ == gz) {
                break;
            }

            // Krok do następnego woksela
            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) {
                    curX += stepX;
                    t = tMaxX;
                    tMaxX += tDeltaX;
                } else {
                    curZ += stepZ;
                    t = tMaxZ;
                    tMaxZ += tDeltaZ;
                }
            } else {
                if (tMaxY < tMaxZ) {
                    curY += stepY;
                    t = tMaxY;
                    tMaxY += tDeltaY;
                } else {
                    curZ += stepZ;
                    t = tMaxZ;
                    tMaxZ += tDeltaZ;
                }
            }
        }

        if (totalSteps == 0) {
            this.solidDensityRatio = 0.0D;
            this.calibratedSlopeTicksPerBlock = AIR_MIN_TICKS_PER_BLOCK * 1.15D;
            return;
        }

        this.solidDensityRatio = (double) solidSteps / (double) totalSteps;
        double meanProbeCost = accumulatedStepCost / (double) totalSteps;

        // Skalowanie Directed Tunnel Spear:
        // W powietrzu (rho = 0) -> 3.48 ticka/blok (blisko optymalnego A*)
        // W litym Deepslate (rho = 1) -> 0.90 * realny koszt skały (odcina eksplorację dalekich jaskiń)
        double openSlope = AIR_MIN_TICKS_PER_BLOCK * 1.15D;
        double spearSlope = Math.max(openSlope, meanProbeCost * 0.90D);
        this.calibratedSlopeTicksPerBlock = openSlope + solidDensityRatio * (spearSlope - openSlope);

        // Pion w litym kamieniu wymaga rozbicia 3 bloków (feet + head + sufit/próg)
        double avgSingleBlockBreak = solidSteps > 0
                ? Math.max(0.0D, (meanProbeCost - SPRINT_BASE_TICKS) * 0.5D)
                : 0.0D;
        this.verticalUpSlope = this.calibratedSlopeTicksPerBlock + JUMP_UP_TICKS + (solidDensityRatio * avgSingleBlockBreak);
        this.verticalDownSlope = this.calibratedSlopeTicksPerBlock + STEP_DOWN_TICKS + (solidDensityRatio * avgSingleBlockBreak * 0.7D);
    }

    /**
     * Oblicza ważoną heurystykę h(x, y, z) w tickach dla węzła A*.
     * Zero alokacji, O(1) czas wykonania.
     */
    public double evaluateHeuristic(int x, int y, int z, double standOffRadiusBlocks) {
        double dx = Math.abs(goalX - x);
        double dz = Math.abs(goalZ - z);
        double dy = goalY - y;

        double minXZ = Math.min(dx, dz);
        double maxXZ = Math.max(dx, dz);
        double octileHoriz = maxXZ + OCTILE_DIAG_FACTOR * minXZ;
        double effectiveHoriz = Math.max(0.0D, octileHoriz - standOffRadiusBlocks);

        double hTicks = effectiveHoriz * calibratedSlopeTicksPerBlock;

        if (dy > 0.0D) {
            hTicks += dy * verticalUpSlope;
        } else if (dy < 0.0D) {
            hTicks += (-dy) * verticalDownSlope;
        }

        // Soft Corridor Funnel tie-breaker (tylko gdy korytarz biegnie głównie w skale)
        if (solidDensityRatio > 0.35D && invLineLenSq > 0.0D) {
            double rx = x - startX;
            double ry = y - startY;
            double rz = z - startZ;
            double proj = (rx * lineDx + ry * lineDy + rz * lineDz) * invLineLenSq;
            proj = Mth.clamp(proj, 0.0D, 1.0D);

            double px = startX + proj * lineDx;
            double py = startY + proj * lineDy;
            double pz = startZ + proj * lineDz;
            double perpDist = Math.sqrt((x - px) * (x - px) + (y - py) * (y - py) + (z - pz) * (z - pz));

            hTicks += perpDist * FUNNEL_TIEBREAKER_WEIGHT * solidDensityRatio;
        }

        return hTicks;
    }

    private double estimateVoxelBreakTicks(CalculationContext ctx, int x, int y, int z) {
        BlockState state = ctx.get(x, y, z);
        if (state.isAir()) return 0.0D;

        probePos.set(x, y, z);
        if (state.getCollisionShape(ctx.world, probePos).isEmpty()) {
            return 0.0D;
        }

        float destroySpeed = state.getDestroySpeed(ctx.world, probePos);
        if (destroySpeed < 0.0F) {
            return 25.0D; // Niezniszczalny (bedrock) podbija koszt, ale nie robi overflow
        }

        double str = ctx.toolSet.getStrVsBlock(state);
        if (str <= 0.0D) {
            return 20.0D;
        }

        // Przeliczenie na realne ticki niszczenia z uwzględnieniem 5-tickowego cooldownu
        double dmgPerTick = str / (destroySpeed * 30.0D);
        double ticks = (dmgPerTick >= 1.0D)
                ? 1.0D
                : (Math.ceil(1.0D / dmgPerTick) + VANILLA_COOLDOWN_TICKS);

        if (state.is(Blocks.DEEPSLATE) || state.is(Blocks.COBBLED_DEEPSLATE) || state.is(Blocks.TUFF)) {
            ticks = Math.max(ticks, 7.5D);
        }
        return ticks;
    }

    public double getSolidDensityRatio() {
        return solidDensityRatio;
    }

    public double getCalibratedSlope() {
        return calibratedSlopeTicksPerBlock;
    }
}

