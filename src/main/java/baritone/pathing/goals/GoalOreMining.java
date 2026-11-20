package baritone.pathing.goals;

import baritone.api.pathing.goals.Goal;
import baritone.api.utils.interfaces.IGoalRenderPos;
import baritone.pathing.calc.SubterraneanPathHeuristic;
import baritone.pathing.movement.CalculationContext;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import java.util.List;

/**
 * Stand-Off Goal dla #mine diamond i #bypass diamond z pełną weryfikacją LOS / Penetralności.
 * 100% Thread-Safe (korzysta z CalculationContext) oraz 100% Zero-GC w pętli A*.
 */
public final class GoalOreMining implements Goal, IGoalRenderPos {

    private static final double STAND_OFF_REACH = 3.85D;
    private static final double STAND_OFF_REACH_SQ = STAND_OFF_REACH * STAND_OFF_REACH;
    private static final double PLAYER_EYE_OFFSET = 1.62D;

    private final CalculationContext ctx;
    private final SubterraneanPathHeuristic subterraneanHeuristic;
    private final int maxPenetrableWallBlocks;

    private final int[] targetX;
    private final int[] targetY;
    private final int[] targetZ;
    private final long[] targetPacked;
    private final int count;

    // Prealokowany scratch pod zapytania o kolizje w wątku A*
    private final BlockPos.MutableBlockPos ddaPos = new BlockPos.MutableBlockPos();

    public GoalOreMining(
            CalculationContext ctx,
            LongArrayList clusterBlocks,
            SubterraneanPathHeuristic subterraneanHeuristic,
            int maxPenetrableWallBlocks
    ) {
        this.ctx = ctx;
        this.subterraneanHeuristic = subterraneanHeuristic;
        this.maxPenetrableWallBlocks = Math.max(0, maxPenetrableWallBlocks);
        this.count = clusterBlocks != null ? clusterBlocks.size() : 0;
        this.targetX = new int[count];
        this.targetY = new int[count];
        this.targetZ = new int[count];
        this.targetPacked = new long[count];

        for (int i = 0; i < count; i++) {
            long p = clusterBlocks.getLong(i);
            targetPacked[i] = p;
            targetX[i] = BlockPos.getX(p);
            targetY[i] = BlockPos.getY(p);
            targetZ[i] = BlockPos.getZ(p);
        }
    }

    public GoalOreMining(
            CalculationContext ctx,
            List<BlockPos> clusterBlocks,
            SubterraneanPathHeuristic subterraneanHeuristic,
            int maxPenetrableWallBlocks
    ) {
        this.ctx = ctx;
        this.subterraneanHeuristic = subterraneanHeuristic;
        this.maxPenetrableWallBlocks = Math.max(0, maxPenetrableWallBlocks);
        this.count = clusterBlocks != null ? clusterBlocks.size() : 0;
        this.targetX = new int[count];
        this.targetY = new int[count];
        this.targetZ = new int[count];
        this.targetPacked = new long[count];

        for (int i = 0; i < count; i++) {
            BlockPos p = clusterBlocks.get(i);
            long packed = p.asLong();
            targetPacked[i] = packed;
            targetX[i] = p.getX();
            targetY[i] = p.getY();
            targetZ[i] = p.getZ();
        }
    }

    public GoalOreMining(
            CalculationContext ctx,
            BlockPos target,
            SubterraneanPathHeuristic subterraneanHeuristic,
            int maxPenetrableWallBlocks
    ) {
        this.ctx = ctx;
        this.subterraneanHeuristic = subterraneanHeuristic;
        this.maxPenetrableWallBlocks = Math.max(0, maxPenetrableWallBlocks);
        this.count = (target != null) ? 1 : 0;
        this.targetX = new int[count];
        this.targetY = new int[count];
        this.targetZ = new int[count];
        this.targetPacked = new long[count];
        if (target != null) {
            targetPacked[0] = target.asLong();
            targetX[0] = target.getX();
            targetY[0] = target.getY();
            targetZ[0] = target.getZ();
        }
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        if (count == 0) return false;
        double eyeX = x + 0.5D;
        double eyeY = y + PLAYER_EYE_OFFSET;
        double eyeZ = z + 0.5D;

        // Szybki pre-filtr bezpieczeństwa punktu stania (nie zaliczaj celu wisząc w powietrzu/lawie)
        if (!hasSafeStandingSpot(x, y, z)) {
            return false;
        }

        for (int i = 0; i < count; i++) {
            int ox = targetX[i];
            int oy = targetY[i];
            int oz = targetZ[i];

            // Najbliższy punkt na AABB bloku rudy (identycznie jak GrimAC Reach)
            double tx = Mth.clamp(eyeX, ox + 0.05D, ox + 0.95D);
            double ty = Mth.clamp(eyeY, oy + 0.05D, oy + 0.95D);
            double tz = Mth.clamp(eyeZ, oz + 0.05D, oz + 0.95D);

            double dx = tx - eyeX;
            double dy = ty - eyeY;
            double dz = tz - eyeZ;
            if (dx * dx + dy * dy + dz * dz > STAND_OFF_REACH_SQ) {
                continue;
            }

            // Weryfikacja penetralności i braku grubej ściany / bedrocka / lawy za pomocą 3D DDA
            if (ctx == null || isRayPenetrable(eyeX, eyeY, eyeZ, tx, ty, tz, ox, oy, oz)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        if (count == 0) return 0.0D;
        if (subterraneanHeuristic != null) {
            return subterraneanHeuristic.evaluateHeuristic(x, y, z, 2.5D);
        }
        double bestH = Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            double dx = Math.abs(targetX[i] - x);
            double dz = Math.abs(targetZ[i] - z);
            double dy = targetY[i] - y;
            double minXZ = Math.min(dx, dz);
            double maxXZ = Math.max(dx, dz);
            double horiz = Math.max(0.0D, (maxXZ + (Math.sqrt(2.0D) - 1.0D) * minXZ) - (STAND_OFF_REACH - 0.5D));
            double h = horiz * 3.029D;
            if (dy > 1.0D) h += (dy - 1.0D) * 6.0D;
            else if (dy < -2.0D) h += (-dy - 2.0D) * 4.3D;
            if (h < bestH) bestH = h;
        }
        return bestH == Double.MAX_VALUE ? 0.0D : bestH;
    }

    @Override
    public double heuristic() {
        return 0.0D;
    }

    @Override
    public BlockPos getGoalPos() {
        return (count > 0) ? new BlockPos(targetX[0], targetY[0], targetZ[0]) : BlockPos.ZERO;
    }

    /**
     * Bezalokacyjny 3D Amanatides-Woo DDA:
     * Zwraca true wyłącznie wtedy, gdy promień od oczu do rudy napotyka co najwyżej
     * `maxPenetrableWallBlocks` kopiennych i bezpiecznych bloków pośrednich.
     */
    private boolean isRayPenetrable(
            double ex, double ey, double ez,
            double tx, double ty, double tz,
            int targetBx, int targetBy, int targetBz
    ) {
        int vx = Mth.floor(ex);
        int vy = Mth.floor(ey);
        int vz = Mth.floor(ez);

        double dx = tx - ex;
        double dy = ty - ey;
        double dz = tz - ez;

        int stepX = dx > 0.0D ? 1 : (dx < 0.0D ? -1 : 0);
        int stepY = dy > 0.0D ? 1 : (dy < 0.0D ? -1 : 0);
        int stepZ = dz > 0.0D ? 1 : (dz < 0.0D ? -1 : 0);

        double tDeltaX = (stepX != 0) ? Math.abs(1.0D / dx) : Double.MAX_VALUE;
        double tDeltaY = (stepY != 0) ? Math.abs(1.0D / dy) : Double.MAX_VALUE;
        double tDeltaZ = (stepZ != 0) ? Math.abs(1.0D / dz) : Double.MAX_VALUE;

        double nextBoundaryX = (stepX > 0) ? (vx + 1.0D - ex) : (ex - vx);
        double nextBoundaryY = (stepY > 0) ? (vy + 1.0D - ey) : (ey - vy);
        double nextBoundaryZ = (stepZ > 0) ? (vz + 1.0D - ez) : (ez - vz);

        double tMaxX = (stepX != 0) ? (nextBoundaryX * tDeltaX) : Double.MAX_VALUE;
        double tMaxY = (stepY != 0) ? (nextBoundaryY * tDeltaY) : Double.MAX_VALUE;
        double tMaxZ = (stepZ != 0) ? (nextBoundaryZ * tDeltaZ) : Double.MAX_VALUE;

        int interveningSolids = 0;

        // Maksymalnie 14 kroków wokseli przy zasięgu <= 3.85D
        for (int iter = 0; iter < 16; iter++) {
            if (vx == targetBx && vy == targetBy && vz == targetBz) {
                return true;
            }

            // Pomijamy woksela, w którym fizycznie znajdują się oczy gracza
            if (iter > 0) {
                BlockState state = ctx.get(vx, vy, vz);
                if (state.getFluidState().is(Fluids.LAVA)) {
                    return false; // Nigdy nie kop rudy przez lawę
                }

                if (!state.isAir()) {
                    ddaPos.set(vx, vy, vz);
                    if (!state.getCollisionShape(ctx.world, ddaPos).isEmpty()) {
                        // Inny blok rudy z tego samego klastra nie blokuje — i tak go zdejmiemy
                        if (!isPartOfCluster(vx, vy, vz)) {
                            float destroySpeed = state.getDestroySpeed(ctx.world, ddaPos);
                            if (destroySpeed < 0.0F) {
                                return false; // Bedrock / blok niezniszczalny
                            }
                            interveningSolids++;
                            if (interveningSolids > maxPenetrableWallBlocks) {
                                return false; // Za gruba ściana — wymuś dalsze drążenie tunelu!
                            }
                        }
                    }
                }
            }

            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) {
                    if (tMaxX > 1.0D) break;
                    vx += stepX;
                    tMaxX += tDeltaX;
                } else {
                    if (tMaxZ > 1.0D) break;
                    vz += stepZ;
                    tMaxZ += tDeltaZ;
                }
            } else {
                if (tMaxY < tMaxZ) {
                    if (tMaxY > 1.0D) break;
                    vy += stepY;
                    tMaxY += tDeltaY;
                } else {
                    if (tMaxZ > 1.0D) break;
                    vz += stepZ;
                    tMaxZ += tDeltaZ;
                }
            }
        }

        return (vx == targetBx && vy == targetBy && vz == targetBz);
    }

    /**
     * Pomocnik dla egzekutora (ContinuousBoreExecutor / BypassOreManager):
     * Jeśli gracz stoi w punkcie Stand-Off, zwraca pierwszy blok ściany zasłaniający rudę,
     * aby natychmiast go „obrać” przed wykopaniem właściwego diamentu.
     * Zwraca Long.MIN_VALUE, jeśli droga do rudy jest całkowicie czysta.
     */
    public long findFirstPeelBlock(int standX, int standY, int standZ, int targetIdx) {
        if (targetIdx < 0 || targetIdx >= count) return Long.MIN_VALUE;

        double ex = standX + 0.5D;
        double ey = standY + PLAYER_EYE_OFFSET;
        double ez = standZ + 0.5D;

        int ox = targetX[targetIdx];
        int oy = targetY[targetIdx];
        int oz = targetZ[targetIdx];

        double tx = Mth.clamp(ex, ox + 0.05D, ox + 0.95D);
        double ty = Mth.clamp(ey, oy + 0.05D, oy + 0.95D);
        double tz = Mth.clamp(ez, oz + 0.05D, oz + 0.95D);

        int vx = Mth.floor(ex);
        int vy = Mth.floor(ey);
        int vz = Mth.floor(ez);

        double dx = tx - ex;
        double dy = ty - ey;
        double dz = tz - ez;

        int stepX = dx > 0.0D ? 1 : (dx < 0.0D ? -1 : 0);
        int stepY = dy > 0.0D ? 1 : (dy < 0.0D ? -1 : 0);
        int stepZ = dz > 0.0D ? 1 : (dz < 0.0D ? -1 : 0);

        double tDeltaX = (stepX != 0) ? Math.abs(1.0D / dx) : Double.MAX_VALUE;
        double tDeltaY = (stepY != 0) ? Math.abs(1.0D / dy) : Double.MAX_VALUE;
        double tDeltaZ = (stepZ != 0) ? Math.abs(1.0D / dz) : Double.MAX_VALUE;

        double tMaxX = (stepX != 0) ? (((stepX > 0) ? (vx + 1.0D - ex) : (ex - vx)) * tDeltaX) : Double.MAX_VALUE;
        double tMaxY = (stepY != 0) ? (((stepY > 0) ? (vy + 1.0D - ey) : (ey - vy)) * tDeltaY) : Double.MAX_VALUE;
        double tMaxZ = (stepZ != 0) ? (((stepZ > 0) ? (vz + 1.0D - ez) : (ez - vz)) * tDeltaZ) : Double.MAX_VALUE;

        for (int iter = 0; iter < 16; iter++) {
            if (vx == ox && vy == oy && vz == oz) return Long.MIN_VALUE;

            if (iter > 0) {
                BlockState st = ctx.get(vx, vy, vz);
                if (!st.isAir()) {
                    ddaPos.set(vx, vy, vz);
                    if (!st.getCollisionShape(ctx.world, ddaPos).isEmpty() && !isPartOfCluster(vx, vy, vz)) {
                        return BlockPos.asLong(vx, vy, vz);
                    }
                }
            }

            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) {
                    if (tMaxX > 1.0D) break;
                    vx += stepX;
                    tMaxX += tDeltaX;
                } else {
                    if (tMaxZ > 1.0D) break;
                    vz += stepZ;
                    tMaxZ += tDeltaZ;
                }
            } else {
                if (tMaxY < tMaxZ) {
                    if (tMaxY > 1.0D) break;
                    vy += stepY;
                    tMaxY += tDeltaY;
                } else {
                    if (tMaxZ > 1.0D) break;
                    vz += stepZ;
                    tMaxZ += tDeltaZ;
                }
            }
        }
        return Long.MIN_VALUE;
    }

    private boolean hasSafeStandingSpot(int x, int y, int z) {
        if (ctx == null) return true;
        BlockState floor = ctx.get(x, y - 1, z);
        if (floor.getFluidState().is(Fluids.LAVA)) return false;
        ddaPos.set(x, y - 1, z);
        if (floor.getCollisionShape(ctx.world, ddaPos).isEmpty()) return false;

        if (ctx.get(x, y, z).getFluidState().is(Fluids.LAVA)) return false;
        if (ctx.get(x, y + 1, z).getFluidState().is(Fluids.LAVA)) return false;
        return true;
    }

    private boolean isPartOfCluster(int x, int y, int z) {
        long packed = BlockPos.asLong(x, y, z);
        for (int i = 0; i < count; i++) {
            if (targetPacked[i] == packed) return true;
        }
        return false;
    }
}

