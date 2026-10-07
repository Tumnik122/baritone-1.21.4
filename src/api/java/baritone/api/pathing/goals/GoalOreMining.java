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
import net.minecraft.core.BlockPos;

/**
 * Goal tailored for mining ores without forcing the player to climb into the wall
 * or pillar under themselves. The goal is satisfied from any adjacent standing position
 * on solid ground within mining reach of the ore block.
 */
public class GoalOreMining implements Goal, IGoalRenderPos {

    protected final int x;
    protected final int y;
    protected final int z;

    public GoalOreMining(BlockPos pos) {
        this(pos.getX(), pos.getY(), pos.getZ());
    }

    public GoalOreMining(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
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
        int dx = x - this.x;
        int dy = y - this.y;
        int dz = z - this.z;

        // Player standing feet at (x, y, z), eye position at (x + 0.5, y + 1.62, z + 0.5)
        double eyeX = x + 0.5;
        double eyeY = y + 1.62;
        double eyeZ = z + 0.5;

        double dist = distanceToBlock(eyeX, eyeY, eyeZ, this.x, this.y, this.z);

        // Safe reach margin: 3.75 blocks (well within 3.9 reach distance)
        if (dist <= 3.75) {
            // Must have a reasonable standing perspective relative to the ore:
            // dy between -5 (ore in ceiling directly above or diagonally above) and +2 (ore in floor)
            // horizontal offset <= 3 blocks
            if (dy >= -5 && dy <= 2 && Math.abs(dx) <= 3 && Math.abs(dz) <= 3) {
                return true;
            }
        }

        return false;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        int dx = x - this.x;
        int dy = y - this.y;
        int dz = z - this.z;
        // Prefer standing on solid ground adjacent to or 1-2 blocks below the ore (eye level)
        int adjustedDy = (dy < -2) ? dy + 2 : (dy > 0 ? dy - 1 : 0);
        return GoalBlock.calculate(dx, adjustedDy, dz);
    }

    @Override
    public BlockPos getGoalPos() {
        return new BlockPos(x, y, z);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        GoalOreMining goal = (GoalOreMining) o;
        return x == goal.x
                && y == goal.y
                && z == goal.z;
    }

    @Override
    public int hashCode() {
        return (int) BetterBlockPos.longHash(x, y, z) * 716508353;
    }

    @Override
    public String toString() {
        return String.format(
                "GoalOreMining{x=%s,y=%s,z=%s}",
                SettingsUtil.maybeCensor(x),
                SettingsUtil.maybeCensor(y),
                SettingsUtil.maybeCensor(z)
        );
    }
}
