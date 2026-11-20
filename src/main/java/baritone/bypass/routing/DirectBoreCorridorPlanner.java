package baritone.bypass.routing;

import baritone.pathing.movement.CalculationContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

/**
 * Direct-Bore Fallback (150ms A* Timeout Failsafe).
 * Wyznacza bezpośredni, bezpieczny tunel 1x2 z minimalną liczbą zakrętów (Axis-Consolidated L-Bore
 * oraz 4-spójny 3D Bresenham z lokalnym omijaniem lawy, bedrocka i przepaści).
 * 100% Zero-GC (płaskie tablice long[]).
 */
public final class DirectBoreCorridorPlanner {

    public static final long ASTAR_FALLBACK_TIMEOUT_MS = 150L;
    private static final int MAX_CORRIDOR_NODES = 512;
    private static final int MAX_DETOUR_RADIUS = 3;

    // Prealokowane bufory tras kandydackich (Zero-GC)
    private final long[] bestPath = new long[MAX_CORRIDOR_NODES];
    private int bestPathLength = 0;

    private final long[] scratchPath = new long[MAX_CORRIDOR_NODES];
    private final BlockPos.MutableBlockPos checkPos = new BlockPos.MutableBlockPos();

    /**
     * Planuje bezpośredni korytarz 1x2 ze startu (sx, sy, sz) do celu (gx, gy, gz).
     *
     * @return liczba wygenerowanych węzłów (0 jeśli trasa całkowicie zablokowana np. ścianą bedrocka)
     */
    public int planDirectBore(
            CalculationContext ctx,
            int sx, int sy, int sz,
            int gx, int gy, int gz
    ) {
        bestPathLength = 0;
        double bestScore = Double.MAX_VALUE;

        // Strategia 1: Skonsolidowany korytarz L (najpierw oś X, potem Z — max 1 zakręt)
        int lenXZ = traceAxisConsolidated(ctx, sx, sy, sz, gx, gy, gz, true);
        if (lenXZ > 0) {
            double score = scoreCorridor(ctx, scratchPath, lenXZ);
            if (score < bestScore) {
                bestScore = score;
                copyToBest(lenXZ);
            }
        }

        // Strategia 2: Skonsolidowany korytarz L (najpierw oś Z, potem X — max 1 zakręt)
        int lenZX = traceAxisConsolidated(ctx, sx, sy, sz, gx, gy, gz, false);
        if (lenZX > 0) {
            double score = scoreCorridor(ctx, scratchPath, lenZX);
            if (score < bestScore) {
                bestScore = score;
                copyToBest(lenZX);
            }
        }

        // Strategia 3: 4-spójny 3D Bresenham Supercover (gdy oba korytarze L trafiły na lawę/bedrock)
        if (bestPathLength == 0 || bestScore > 2500.0D) {
            int lenBres = traceCardinalBresenham3D(ctx, sx, sy, sz, gx, gy, gz);
            if (lenBres > 0) {
                double score = scoreCorridor(ctx, scratchPath, lenBres);
                if (score < bestScore) {
                    bestScore = score;
                    copyToBest(lenBres);
                }
            }
        }

        return bestPathLength;
    }

    /**
     * Korytarz L-kształtny z równomierną rampą Y (max 1 blok wysokości na 1 blok poziomy — 100% legalne schodki).
     */
    private int traceAxisConsolidated(
            CalculationContext ctx,
            int sx, int sy, int sz,
            int gx, int gy, int gz,
            boolean xFirst
    ) {
        int cx = sx, cy = sy, cz = sz;
        int len = 0;
        scratchPath[len++] = BlockPos.asLong(cx, cy, cz);

        int maxSteps = Math.abs(gx - sx) + Math.abs(gz - sz) + Math.abs(gy - sy) * 2 + 24;
        maxSteps = Math.min(maxSteps, MAX_CORRIDOR_NODES - 1);

        for (int iter = 0; iter < maxSteps; iter++) {
            if (cx == gx && cy == gy && cz == gz) {
                return len;
            }

            int dx = Integer.compare(gx, cx);
            int dz = Integer.compare(gz, cz);
            int dy = Integer.compare(gy, cy);

            int nextX = cx;
            int nextZ = cz;
            if (xFirst) {
                if (dx != 0) nextX += dx;
                else if (dz != 0) nextZ += dz;
            } else {
                if (dz != 0) nextZ += dz;
                else if (dx != 0) nextX += dx;
            }

            // Schodki +1Y/-1Y wykonujemy tylko przy jednoczesnym kroku poziomym (nigdy pionowy szyb 1x1!)
            int nextY = cy;
            if (dy != 0 && (nextX != cx || nextZ != cz)) {
                nextY = cy + dy;
            } else if (dy != 0 && nextX == cx && nextZ == cz) {
                // Jesteśmy dokładnie nad/pod celem — robimy bezpieczny 1-blokowy odskok spiralny na schodek
                int[] spiral = findSafeSpiralStep(ctx, cx, cy, cz, dy);
                if (spiral == null) return 0;
                nextX = spiral[0];
                nextY = spiral[1];
                nextZ = spiral[2];
            }

            if (isTransitionSafe(ctx, cx, cy, cz, nextX, nextY, nextZ)) {
                cx = nextX;
                cy = nextY;
                cz = nextZ;
                scratchPath[len++] = BlockPos.asLong(cx, cy, cz);
            } else {
                // Próba lokalnego ominięcia kieszeni lawy / bedrocka / przepaści
                int detourEndLen = tryHazardDetour(ctx, cx, cy, cz, nextX, nextY, nextZ, len);
                if (detourEndLen <= len) {
                    return 0; // Nie da się ominąć w tym wariancie
                }
                len = detourEndLen;
                long last = scratchPath[len - 1];
                cx = BlockPos.getX(last);
                cy = BlockPos.getY(last);
                cz = BlockPos.getZ(last);
            }

            if (len >= MAX_CORRIDOR_NODES - 1) break;
        }

        // Akceptujemy nawet częściowe zbliżenie, jeśli dotarliśmy w zasięg Stand-Off (<= 3.85D)
        double remSq = (gx - cx) * (gx - cx) + (gy - cy) * (gy - cy) + (gz - cz) * (gz - cz);
        return (remSq <= 14.0D) ? len : 0;
    }

    /**
     * 4-spójny 3D Bresenham Corridor: w każdym kroku porusza się dokładnie o 1 blok kardynalny
     * (i opcjonalnie +-1Y), eliminując nielegalne kroki po przekątnej przez lite narożniki.
     */
    private int traceCardinalBresenham3D(
            CalculationContext ctx,
            int sx, int sy, int sz,
            int gx, int gy, int gz
    ) {
        int cx = sx, cy = sy, cz = sz;
        int len = 0;
        scratchPath[len++] = BlockPos.asLong(cx, cy, cz);

        int dxAbs = Math.abs(gx - sx);
        int dyAbs = Math.abs(gy - sy);
        int dzAbs = Math.abs(gz - sz);

        int stepX = Integer.compare(gx, sx);
        int stepY = Integer.compare(gy, sy);
        int stepZ = Integer.compare(gz, sz);

        double errX = 0.0D, errZ = 0.0D, errY = 0.0D;
        int horizTotal = Math.max(1, dxAbs + dzAbs);
        double rateX = (double) dxAbs / horizTotal;
        double rateZ = (double) dzAbs / horizTotal;
        double rateY = (double) dyAbs / horizTotal;

        int maxSteps = Math.min(horizTotal + dyAbs * 2 + 32, MAX_CORRIDOR_NODES - 1);
        for (int i = 0; i < maxSteps; i++) {
            if (cx == gx && cy == gy && cz == gz) return len;

            errX += rateX;
            errZ += rateZ;
            errY += rateY;

            int nx = cx;
            int ny = cy;
            int nz = cz;

            // Wybierz dominującą oś poziomą bez jednoczesnego ruchu X+Z (czysty korytarz 1x2)
            if (cx != gx && (errX >= errZ || cz == gz)) {
                nx += stepX;
                errX -= 1.0D;
            } else if (cz != gz) {
                nz += stepZ;
                errZ -= 1.0D;
            } else if (cy != gy) {
                int[] spiral = findSafeSpiralStep(ctx, cx, cy, cz, Integer.compare(gy, cy));
                if (spiral == null) return 0;
                nx = spiral[0];
                ny = spiral[1];
                nz = spiral[2];
            }

            if (cy != gy && (nx != cx || nz != cz) && errY >= 0.5D) {
                ny += Integer.compare(gy, cy);
                errY -= 1.0D;
            }

            if (isTransitionSafe(ctx, cx, cy, cz, nx, ny, nz)) {
                cx = nx;
                cy = ny;
                cz = nz;
                scratchPath[len++] = BlockPos.asLong(cx, cy, cz);
            } else {
                int detourLen = tryHazardDetour(ctx, cx, cy, cz, nx, ny, nz, len);
                if (detourLen <= len) return 0;
                len = detourLen;
                long last = scratchPath[len - 1];
                cx = BlockPos.getX(last);
                cy = BlockPos.getY(last);
                cz = BlockPos.getZ(last);
            }

            if (len >= MAX_CORRIDOR_NODES - 1) break;
        }

        double remSq = (gx - cx) * (gx - cx) + (gy - cy) * (gy - cy) + (gz - cz) * (gz - cz);
        return (remSq <= 14.0D) ? len : 0;
    }

    /**
     * Próbuje ominąć przeszkodę (kieszeń lawy, bedrock, przepaść) przesuwając korytarz w bok lub +1Y.
     */
    private int tryHazardDetour(
            CalculationContext ctx,
            int cx, int cy, int cz,
            int blockedX, int blockedY, int blockedZ,
            int curLen
    ) {
        if (curLen + 4 >= MAX_CORRIDOR_NODES) return curLen;

        int moveDx = blockedX - cx;
        int moveDz = blockedZ - cz;
        // Wektory prostopadłe do kierunku marszu
        int perpX = -moveDz;
        int perpZ = moveDx;
        if (perpX == 0 && perpZ == 0) {
            perpX = 1;
        }

        // Próbujemy obejść przeszkodę: najpierw w prawo, w lewo, potem górą (+1Y)
        for (int side = -1; side <= 1; side += 2) {
            for (int r = 1; r <= MAX_DETOUR_RADIUS; r++) {
                int sx = cx + perpX * side * r;
                int sz = cz + perpZ * side * r;
                int fx = sx + moveDx;
                int fz = sz + moveDz;

                if (isTransitionSafe(ctx, cx, cy, cz, cx + perpX * side, cy, cz + perpZ * side)
                        && isSlotSafe(ctx, sx, cy, sz)
                        && isTransitionSafe(ctx, sx, cy, sz, fx, blockedY, fz)) {
                    int px = cx;
                    int pz = cz;
                    int newLen = curLen;
                    for (int step = 1; step <= r; step++) {
                        px += perpX * side;
                        pz += perpZ * side;
                        scratchPath[newLen++] = BlockPos.asLong(px, cy, pz);
                    }
                    scratchPath[newLen++] = BlockPos.asLong(fx, blockedY, fz);
                    return newLen;
                }
            }
        }

        // Ostatecznie próba przejścia górą (+1Y nad kieszenią lawy)
        if (isTransitionSafe(ctx, cx, cy, cz, blockedX, cy + 1, blockedZ)) {
            scratchPath[curLen++] = BlockPos.asLong(blockedX, cy + 1, blockedZ);
            return curLen;
        }

        return curLen;
    }

    private int[] findSafeSpiralStep(CalculationContext ctx, int cx, int cy, int cz, int dy) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            int nx = cx + dir.getStepX();
            int nz = cz + dir.getStepZ();
            int ny = cy + dy;
            if (isTransitionSafe(ctx, cx, cy, cz, nx, ny, nz)) {
                return new int[]{nx, ny, nz};
            }
        }
        return null;
    }

    /**
     * Weryfikuje pełne bezpieczeństwo kroku (cx,cy,cz) -> (nx,ny,nz):
     * - brak bedrocka i lawy w slocie stóp, głowy, sufitu (+1Y) i progu (-1Y),
     * - brak lawy stykającej się ze ścianami korytarza,
     * - stabilna podłoga pod docelowym blokiem (lub co najwyżej 2-blokowy spadek na bezpieczny grunt).
     */
    public boolean isTransitionSafe(
            CalculationContext ctx,
            int cx, int cy, int cz,
            int nx, int ny, int nz
    ) {
        if (!isSlotSafe(ctx, nx, ny, nz)) return false;

        int dy = ny - cy;
        if (dy > 0) {
            // Head Clearance Guard: sufit nad obecnym blokiem (cx, cy+2, cz) musi być bezpiecznie kopienny
            if (!isBreakableAndSafe(ctx, cx, cy + 2, cz)) return false;
            // Sprawdzenie czy nad sufitem nie wisi lawa
            if (isLava(ctx, cx, cy + 3, cz)) return false;
        } else if (dy < 0) {
            // Próg wejściowy (nx, cy+1, nz) musi być kopienny (zapobiega uderzeniu głową przy zejściu)
            if (!isBreakableAndSafe(ctx, nx, cy + 1, nz)) return false;
        }

        return true;
    }

    private boolean isSlotSafe(CalculationContext ctx, int x, int y, int z) {
        if (y <= ctx.world.getMinY() + 1 || y >= ctx.world.getMaxY() - 3) return false;

        // 1. Stopy i głowa muszą być kopienne i bez lawy
        if (!isBreakableAndSafe(ctx, x, y, z)) return false;
        if (!isBreakableAndSafe(ctx, x, y + 1, z)) return false;

        // 2. Sufit nad głową nie może być lawą ani żwirem podtrzymującym lawę
        if (isLava(ctx, x, y + 2, z)) return false;
        BlockState ceiling = ctx.get(x, y + 2, z);
        if (ceiling.getBlock() instanceof FallingBlock && isLava(ctx, x, y + 3, z)) {
            return false;
        }

        // 3. Ochrona przed otwarciem ściany z lawą na wysokości stóp lub głowy
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            int ox = x + dir.getStepX();
            int oz = z + dir.getStepZ();
            if (isLava(ctx, ox, y, oz) || isLava(ctx, ox, y + 1, oz)) {
                return false;
            }
        }

        // 4. Bezpieczna podłoga (solidna bezpośrednio pod stopami, nie lawa)
        BlockState floor = ctx.get(x, y - 1, z);
        if (floor.getFluidState().is(Fluids.LAVA)) return false;
        checkPos.set(x, y - 1, z);
        if (floor.getCollisionShape(ctx.world, checkPos).isEmpty()) {
            // Dopuszczamy brak podłogi tylko jeśli 1 blok niżej jest bezpieczny grunt (brak przepaści)
            BlockState subFloor = ctx.get(x, y - 2, z);
            checkPos.set(x, y - 2, z);
            if (subFloor.getFluidState().is(Fluids.LAVA)
                    || subFloor.getCollisionShape(ctx.world, checkPos).isEmpty()) {
                return false;
            }
        }

        return true;
    }

    private boolean isBreakableAndSafe(CalculationContext ctx, int x, int y, int z) {
        BlockState state = ctx.get(x, y, z);
        if (state.getFluidState().is(Fluids.LAVA)) return false;
        if (state.isAir()) return true;

        checkPos.set(x, y, z);
        float speed = state.getDestroySpeed(ctx.world, checkPos);
        return speed >= 0.0F; // < 0 oznacza Bedrock / Barrier / End Portal Frame
    }

    private boolean isLava(CalculationContext ctx, int x, int y, int z) {
        return ctx.get(x, y, z).getFluidState().is(Fluids.LAVA);
    }

    /**
     * Ocenia jakość korytarza w tickach (czas marszu + kopania + kary za zakręty).
     */
    private double scoreCorridor(CalculationContext ctx, long[] path, int len) {
        double total = 0.0D;
        int prevDx = 0, prevDz = 0;

        for (int i = 1; i < len; i++) {
            long prev = path[i - 1];
            long cur = path[i];
            int x1 = BlockPos.getX(prev), y1 = BlockPos.getY(prev), z1 = BlockPos.getZ(prev);
            int x2 = BlockPos.getX(cur), y2 = BlockPos.getY(cur), z2 = BlockPos.getZ(cur);

            total += baritone.pathing.calc.MineCostHeuristic.computeTransitionCostTicks(
                    ctx, prevDx, prevDz, x1, y1, z1, x2, y2, z2
            );
            prevDx = x2 - x1;
            prevDz = z2 - z1;
        }
        return total;
    }

    private void copyToBest(int len) {
        System.arraycopy(scratchPath, 0, bestPath, 0, len);
        bestPathLength = len;
    }

    public long[] getBestPathBuffer() {
        return bestPath;
    }

    public int getBestPathLength() {
        return bestPathLength;
    }
}

