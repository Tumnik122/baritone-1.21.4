/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.api.pathing.goals;

import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.SettingsUtil;
import baritone.api.utils.interfaces.IGoalRenderPos;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Zsynchronizowany, bezalokacyjny i bezpieczny wątkowo cel Stand-Off dla rud (Baritone 1.21.4).
 * Wykorzystuje 3D Amanatides-Woo DDA na VoxelOracle (BlockStateInterface), dzięki czemu
 * A* i MineProcess dzielą dokładnie ten sam kontrakt zasięgu, kolizji i bezpieczeństwa.
 */
public class GoalOreMining implements Goal, IGoalRenderPos {

    public static final double SAFE_STANDOFF_REACH = 3.65D;
    public static final double SAFE_STANDOFF_REACH_SQ = SAFE_STANDOFF_REACH * SAFE_STANDOFF_REACH;
    public static final double PLAYER_EYE_OFFSET = 1.62D;
    private static final int MAX_DDA_STEPS = 16;

    /**
     * Mostek między warstwą API a main (BlockStateInterface + MovementHelper).
     * Musi być bezpieczny dla wątku A* i bezalokacyjny.
     */
    public interface VoxelOracle {
        /** Czy gracz może stabilnie stać w (x, y, z) na bloku (x, y - 1, z). */
        boolean canStandAt(int x, int y, int z);

        /** Czy promień przechodzi swobodnie przez woksel (powietrze, niepełne/przelotowe). */
        boolean isRayPassable(int x, int y, int z);

        /**
         * Czy blok przeszkody (x, y, z) może zostać bezpiecznie wykopany
         * (destroySpeed >= 0, !avoidBreaking, nie podkopuje gracza w przepaść/lawę).
         */
        boolean canSafelyPierce(int bx, int by, int bz, int playerX, int playerY, int playerZ);
    }

    protected final int x;
    protected final int y;
    protected final int z;

    private final int[] targetX;
    private final int[] targetY;
    private final int[] targetZ;
    private final int count;
    private final VoxelOracle oracle;

    public GoalOreMining(BlockPos pos) {
        this(pos, null);
    }

    public GoalOreMining(int x, int y, int z) {
        this(x, y, z, null);
    }

    public GoalOreMining(List<BlockPos> clusterBlocks) {
        this(clusterBlocks, null);
    }

    public GoalOreMining(LongArrayList clusterBlocks) {
        this(clusterBlocks, null);
    }

    public GoalOreMining(BlockPos pos, VoxelOracle oracle) {
        this(pos.getX(), pos.getY(), pos.getZ(), oracle);
    }

    public GoalOreMining(int x, int y, int z, VoxelOracle oracle) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.count = 1;
        this.targetX = new int[]{x};
        this.targetY = new int[]{y};
        this.targetZ = new int[]{z};
        this.oracle = oracle;
    }

    public GoalOreMining(LongArrayList clusterBlocks, VoxelOracle oracle) {
        this.oracle = oracle;
        this.count = clusterBlocks != null ? clusterBlocks.size() : 0;
        if (this.count == 0) {
            this.x = 0;
            this.y = 0;
            this.z = 0;
            this.targetX = new int[0];
            this.targetY = new int[0];
            this.targetZ = new int[0];
            return;
        }
        this.targetX = new int[count];
        this.targetY = new int[count];
        this.targetZ = new int[count];
        long first = clusterBlocks.getLong(0);
        this.x = BlockPos.getX(first);
        this.y = BlockPos.getY(first);
        this.z = BlockPos.getZ(first);

        for (int i = 0; i < count; i++) {
            long p = clusterBlocks.getLong(i);
            targetX[i] = BlockPos.getX(p);
            targetY[i] = BlockPos.getY(p);
            targetZ[i] = BlockPos.getZ(p);
        }
    }

    public GoalOreMining(List<BlockPos> clusterBlocks, VoxelOracle oracle) {
        this.oracle = oracle;
        this.count = clusterBlocks != null ? clusterBlocks.size() : 0;
        if (this.count == 0) {
            this.x = 0;
            this.y = 0;
            this.z = 0;
            this.targetX = new int[0];
            this.targetY = new int[0];
            this.targetZ = new int[0];
            return;
        }
        this.targetX = new int[count];
        this.targetY = new int[count];
        this.targetZ = new int[count];
        BlockPos first = clusterBlocks.get(0);
        this.x = first.getX();
        this.y = first.getY();
        this.z = first.getZ();

        for (int i = 0; i < count; i++) {
            BlockPos p = clusterBlocks.get(i);
            targetX[i] = p.getX();
            targetY[i] = p.getY();
            targetZ[i] = p.getZ();
        }
    }

    /** Kwadrat dystansu od punktu (px, py, pz) do AABB bloku [bx..bx+1, by..by+1, bz..bz+1]. Zero alokacji. */
    public static double distanceSqToBlock(double px, double py, double pz, int bx, int by, int bz) {
        double clampX = Math.max(bx, Math.min(px, bx + 1.0D));
        double clampY = Math.max(by, Math.min(py, by + 1.0D));
        double clampZ = Math.max(bz, Math.min(pz, bz + 1.0D));
        double dx = px - clampX;
        double dy = py - clampY;
        double dz = pz - clampZ;
        return dx * dx + dy * dy + dz * dz;
    }

    public static double distanceToBlock(double px, double py, double pz, int bx, int by, int bz) {
        return Math.sqrt(distanceSqToBlock(px, py, pz, bx, by, bz));
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        if (count == 0) {
            return false;
        }

        double eyeX = x + 0.5D;
        double eyeY = y + PLAYER_EYE_OFFSET;
        double eyeZ = z + 0.5D;

        boolean checkedFloor = false;

        for (int i = 0; i < count; i++) {
            int tx = targetX[i];
            int ty = targetY[i];
            int tz = targetZ[i];

            // Szybkie odrzucenie w osi pionowej przed obliczeniami geometrii
            int dy = y - ty;
            if (dy < -4 || dy > 2) {
                continue;
            }

            // Szybki test kwadratu dystansu bez Math.sqrt()
            if (distanceSqToBlock(eyeX, eyeY, eyeZ, tx, ty, tz) > SAFE_STANDOFF_REACH_SQ) {
                continue;
            }

            if (oracle == null) {
                return true;
            }

            // Leniwe sprawdzenie podłoża (tylko dla węzłów w sferze zasięgu!)
            if (!checkedFloor) {
                if (!oracle.canStandAt(x, y, z)) {
                    return false;
                }
                checkedFloor = true;
            }

            // Sprawdzenie korytarza 3D DDA: bezpośrednia widoczność LUB bezpieczny korytarz do przebicia
            if (hasClearOrPierceableCorridor(eyeX, eyeY, eyeZ, tx, ty, tz, x, y, z)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Bezalokacyjny 3D Amanatides-Woo DDA.
     * Zwraca true WYŁĄCZNIE gdy docelowa ruda jest widoczna lub KAŻDY blok zasłaniający
     * wzdłuż linii wzroku znajduje się w bezpiecznym zasięgu i spełnia canSafelyPierce().
     */
    private boolean hasClearOrPierceableCorridor(
            double sx, double sy, double sz,
            int tx, int ty, int tz,
            int playerX, int playerY, int playerZ
    ) {
        double ex = tx + 0.5D;
        double ey = ty + 0.5D;
        double ez = tz + 0.5D;

        double dx = ex - sx;
        double dy = ey - sy;
        double dz = ez - sz;
        double lenSq = dx * dx + dy * dy + dz * dz;
        if (lenSq < 1.0E-8D) {
            return true;
        }
        double len = Math.sqrt(lenSq);

        int vx = (int) Math.floor(sx);
        int vy = (int) Math.floor(sy);
        int vz = (int) Math.floor(sz);

        int stepX = dx > 0.0D ? 1 : (dx < 0.0D ? -1 : 0);
        int stepY = dy > 0.0D ? 1 : (dy < 0.0D ? -1 : 0);
        int stepZ = dz > 0.0D ? 1 : (dz < 0.0D ? -1 : 0);

        double tDeltaX = stepX != 0 ? Math.abs(1.0D / dx) : Double.POSITIVE_INFINITY;
        double tDeltaY = stepY != 0 ? Math.abs(1.0D / dy) : Double.POSITIVE_INFINITY;
        double tDeltaZ = stepZ != 0 ? Math.abs(1.0D / dz) : Double.POSITIVE_INFINITY;

        double tMaxX = stepX > 0 ? ((vx + 1.0D) - sx) / dx : (stepX < 0 ? (vx - sx) / dx : Double.POSITIVE_INFINITY);
        double tMaxY = stepY > 0 ? ((vy + 1.0D) - sy) / dy : (stepY < 0 ? (vy - sy) / dy : Double.POSITIVE_INFINITY);
        double tMaxZ = stepZ > 0 ? ((vz + 1.0D) - sz) / dz : (stepZ < 0 ? (vz - sz) / dz : Double.POSITIVE_INFINITY);

        for (int step = 0; step < MAX_DDA_STEPS; step++) {
            if (vx == tx && vy == ty && vz == tz) {
                return true;
            }

            double hitT;
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                hitT = tMaxX;
                vx += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY <= tMaxZ) {
                hitT = tMaxY;
                vy += stepY;
                tMaxY += tDeltaY;
            } else {
                hitT = tMaxZ;
                vz += stepZ;
                tMaxZ += tDeltaZ;
            }

            if (hitT > 1.0D) {
                return true;
            }

            if (vx == tx && vy == ty && vz == tz) {
                return true;
            }

            if (!oracle.isRayPassable(vx, vy, vz)) {
                double hitDist = hitT * len;
                if (hitDist > SAFE_STANDOFF_REACH) {
                    return false;
                }
                if (!oracle.canSafelyPierce(vx, vy, vz, playerX, playerY, playerZ)) {
                    return false;
                }
            }
        }
        return false;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        if (count == 0) {
            return 0.0D;
        }
        double minH = Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            int dx = x - targetX[i];
            int dy = y - targetY[i];
            int dz = z - targetZ[i];
            // Dopasowanie do zakresu pionowego [-4, +2] w isInGoal
            int adjustedDy = (dy < -3) ? (dy + 3) : (dy > 1 ? (dy - 1) : 0);
            double h = GoalBlock.calculate(dx, adjustedDy, dz);
            if (h < minH) {
                minH = h;
            }
        }
        return minH;
    }

    @Override
    public BlockPos getGoalPos() {
        return new BlockPos(x, y, z);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GoalOreMining goal = (GoalOreMining) o;
        return x == goal.x && y == goal.y && z == goal.z && count == goal.count;
    }

    @Override
    public int hashCode() {
        return (int) BetterBlockPos.longHash(x, y, z) * 716508353 + count;
    }

    @Override
    public String toString() {
        return String.format(
                "GoalOreMining{x=%s,y=%s,z=%s,count=%d}",
                SettingsUtil.maybeCensor(x),
                SettingsUtil.maybeCensor(y),
                SettingsUtil.maybeCensor(z),
                count
        );
    }
}
