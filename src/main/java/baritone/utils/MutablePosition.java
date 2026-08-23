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

package baritone.utils;

import baritone.api.utils.BetterBlockPos;

/**
 * Thread-local mutable position storage to eliminate object allocation in hot paths.
 * Use this class instead of creating new BlockPos/BetterBlockPos instances when
 * only temporary coordinate storage is needed.
 *
 * @author Baritone Optimization Team
 * @since 1.21.4
 */
public final class MutablePosition {
    
    private static final ThreadLocal<MutablePosition> THREAD_LOCAL = 
        ThreadLocal.withInitial(MutablePosition::new);
    
    public int x, y, z;
    
    private MutablePosition() {
        // Prevent direct instantiation
    }
    
    /**
     * Get the thread-local instance.
     *
     * @return Thread-local MutablePosition instance
     */
    public static MutablePosition get() {
        return THREAD_LOCAL.get();
    }
    
    /**
     * Set all coordinates at once.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @return This instance for chaining
     */
    public MutablePosition set(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }
    
    /**
     * Set from a BetterBlockPos.
     *
     * @param pos Source position
     * @return This instance for chaining
     */
    public MutablePosition set(BetterBlockPos pos) {
        this.x = pos.x;
        this.y = pos.y;
        this.z = pos.z;
        return this;
    }
    
    /**
     * Set from packed long coordinate.
     *
     * @param packed Packed coordinate from BetterBlockPos.serializeToLong()
     * @return This instance for chaining
     */
    public MutablePosition setFromPacked(long packed) {
        this.x = (int) (packed << 38 >> 38);
        this.y = (int) (packed >> 26 & 0xFFFL);
        this.z = (int) (packed << 38 >> 38);
        return this;
    }
    
    /**
     * Offset current position.
     *
     * @param dx X offset
     * @param dy Y offset
     * @param dz Z offset
     * @return This instance for chaining
     */
    public MutablePosition offset(int dx, int dy, int dz) {
        this.x += dx;
        this.y += dy;
        this.z += dz;
        return this;
    }
    
    /**
     * Move up by specified amount.
     *
     * @param amt Amount to move up
     * @return This instance for chaining
     */
    public MutablePosition up(int amt) {
        this.y += amt;
        return this;
    }
    
    /**
     * Move down by specified amount.
     *
     * @param amt Amount to move down
     * @return This instance for chaining
     */
    public MutablePosition down(int amt) {
        this.y -= amt;
        return this;
    }
    
    /**
     * Move north by specified amount.
     *
     * @param amt Amount to move north
     * @return This instance for chaining
     */
    public MutablePosition north(int amt) {
        this.z -= amt;
        return this;
    }
    
    /**
     * Move south by specified amount.
     *
     * @param amt Amount to move south
     * @return This instance for chaining
     */
    public MutablePosition south(int amt) {
        this.z += amt;
        return this;
    }
    
    /**
     * Move east by specified amount.
     *
     * @param amt Amount to move east
     * @return This instance for chaining
     */
    public MutablePosition east(int amt) {
        this.x += amt;
        return this;
    }
    
    /**
     * Move west by specified amount.
     *
     * @param amt Amount to move west
     * @return This instance for chaining
     */
    public MutablePosition west(int amt) {
        this.x -= amt;
        return this;
    }
    
    /**
     * Calculate squared distance to another position.
     *
     * @param other Other position
     * @return Squared Euclidean distance
     */
    public double distSqr(MutablePosition other) {
        int dx = this.x - other.x;
        int dy = this.y - other.y;
        int dz = this.z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }
    
    /**
     * Calculate squared distance to coordinates.
     *
     * @param ox X coordinate
     * @param oy Y coordinate
     * @param oz Z coordinate
     * @return Squared Euclidean distance
     */
    public double distSqr(int ox, int oy, int oz) {
        int dx = this.x - ox;
        int dy = this.y - oy;
        int dz = this.z - oz;
        return dx * dx + dy * dy + dz * dz;
    }
    
    /**
     * Pack coordinates into a long.
     *
     * @return Packed coordinate suitable for HashMap keys
     */
    public long toPacked() {
        return BetterBlockPos.serializeToLong(x, y, z);
    }
    
    /**
     * Reset to origin.
     *
     * @return This instance for chaining
     */
    public MutablePosition reset() {
        this.x = 0;
        this.y = 0;
        this.z = 0;
        return this;
    }
    
    @Override
    public String toString() {
        return String.format("MutablePosition{x=%d,y=%d,z=%d}", x, y, z);
    }
}
