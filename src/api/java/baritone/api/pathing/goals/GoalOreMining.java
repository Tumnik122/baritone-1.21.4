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

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.SettingsUtil;
import baritone.api.utils.interfaces.IGoalRenderPos;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Stand-Off Goal tailored for mining ores without forcing the player to climb into the wall
 * or pillar under themselves. The goal is satisfied from any adjacent standing position
 * on solid ground within safe mining reach (<= 3.85D) of any ore block in the cluster.
 */
public class GoalOreMining implements Goal, IGoalRenderPos {

    public static final double STAND_OFF_REACH = 3.85D;
    public static final double STAND_OFF_REACH_SQ = STAND_OFF_REACH * STAND_OFF_REACH;
    private static final double PLAYER_EYE_OFFSET = 1.62D;

    // Minimalny czas pokonania 1 bloku w prostym tunelu (3.564 * 0.85 = 3.0294)
    private static final double MIN_TICK_PER_BLOCK = 3.564D * 0.85D;
    private static final double SPRINT_ONE_BLOCK_TICKS = 3.564D;
    private static final double JUMP_UP_PENALTY_TICKS = 2.5D;
    private static final double STEP_DOWN_PENALTY_TICKS = 0.8D;

    protected final int x;
    protected final int y;
    protected final int z;

    private final int[] targetX;
    private final int[] targetY;
    private final int[] targetZ;
    private final int count;

    public GoalOreMining(BlockPos pos) {
        this(pos.getX(), pos.getY(), pos.getZ());
    }

    public GoalOreMining(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.count = 1;
        this.targetX = new int[]{x};
        this.targetY = new int[]{y};
        this.targetZ = new int[]{z};
    }

    public GoalOreMining(LongArrayList clusterBlocks) {
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

    public GoalOreMining(List<BlockPos> clusterBlocks) {
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

    /**
     * Calculates the minimum Euclidean distance between a 3D point (e.g. player eyes)
     * and the axis-aligned bounding box of a 1x1x1 block at (bx, by, bz).
     */
    public static double distanceToBlock(double px, double py, double pz, int bx, int by, int bz) {
        double clampX = Math.max(bx, Math.min(px, bx + 1.0));
        double clampY = Math.max(by, Math.min(py, by + 1.0));
        double clampZ = Math.max(bz, Math.min(pz, bz + 1.0));
        double dx = px - clampX;
        double dy = py - clampY;
        double dz = pz - clampZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        if (count == 0) return false;

        // Cel nie może być zaliczony, jeśli gracz nie stoi na stabilnym podłożu (anty-GroundSpoof / air-cut)
        IBaritone ibaritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (ibaritone != null && ibaritone.getPlayerContext() != null) {
            net.minecraft.client.player.LocalPlayer p = ibaritone.getPlayerContext().player();
            if (p != null && !p.onGround() && !p.isInWater()) {
                return false;
            }
            net.minecraft.world.level.Level world = ibaritone.getPlayerContext().world();
            if (world != null) {
                BlockPos standPos = new BlockPos(x, y - 1, z);
                net.minecraft.world.level.block.state.BlockState floor = world.getBlockState(standPos);
                if (floor.getCollisionShape(world, standPos).isEmpty()) {
                    return false;
                }
            }
        }

        double eyeX = x + 0.5D;
        double eyeY = y + PLAYER_EYE_OFFSET;
        double eyeZ = z + 0.5D;

        if (count == 1) {
            double dist = distanceToBlock(eyeX, eyeY, eyeZ, this.x, this.y, this.z);
            if (dist <= STAND_OFF_REACH) {
                int dy = y - this.y;
                int dx = x - this.x;
                int dz = z - this.z;
                if (dy >= -5 && dy <= 2 && Math.abs(dx) <= 3 && Math.abs(dz) <= 3) {
                    return true;
                }
            }
            return false;
        }

        for (int i = 0; i < count; i++) {
            double dist = distanceToBlock(eyeX, eyeY, eyeZ, targetX[i], targetY[i], targetZ[i]);
            if (dist <= STAND_OFF_REACH) {
                int dy = y - targetY[i];
                int dx = x - targetX[i];
                int dz = z - targetZ[i];
                if (dy >= -5 && dy <= 2 && Math.abs(dx) <= 3 && Math.abs(dz) <= 3) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        if (count == 0) return 0.0D;

        if (count == 1) {
            int dx = x - this.x;
            int dy = y - this.y;
            int dz = z - this.z;
            int adjustedDy = (dy < -2) ? dy + 2 : (dy > 0 ? dy - 1 : 0);
            return GoalBlock.calculate(dx, adjustedDy, dz);
        }

        double bestH = Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            double dx = Math.abs(targetX[i] - x);
            double dz = Math.abs(targetZ[i] - z);
            double dy = targetY[i] - y;

            double minXZ = Math.min(dx, dz);
            double maxXZ = Math.max(dx, dz);
            double horizBlocks = Math.max(0.0D, (maxXZ + (Math.sqrt(2.0D) - 1.0D) * minXZ) - (STAND_OFF_REACH - 0.5D));
            double hTicks = horizBlocks * MIN_TICK_PER_BLOCK;

            if (dy > 1.0D) {
                hTicks += (dy - 1.0D) * (SPRINT_ONE_BLOCK_TICKS + JUMP_UP_PENALTY_TICKS);
            } else if (dy < -2.0D) {
                hTicks += (-dy - 2.0D) * (SPRINT_ONE_BLOCK_TICKS + STEP_DOWN_PENALTY_TICKS);
            }

            if (hTicks < bestH) {
                bestH = hTicks;
            }
        }
        return bestH == Double.MAX_VALUE ? 0.0D : bestH;
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
