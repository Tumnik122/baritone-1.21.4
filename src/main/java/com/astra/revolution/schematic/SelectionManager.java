package com.astra.revolution.schematic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.AABB;

/**
 * ASTRA REVOLUTION — AREA SELECTION MANAGER
 * Stores and manages two interactive corners (Corner 1 & Corner 2) in the world,
 * providing normalized bounding boxes, dimension math, and directional nudging.
 */
public final class SelectionManager {

    private BlockPos corner1;
    private BlockPos corner2;

    public BlockPos getCorner1() {
        return corner1;
    }

    public void setCorner1(BlockPos corner1) {
        this.corner1 = corner1 != null ? corner1.immutable() : null;
    }

    public BlockPos getCorner2() {
        return corner2;
    }

    public void setCorner2(BlockPos corner2) {
        this.corner2 = corner2 != null ? corner2.immutable() : null;
    }

    public void clearSelection() {
        this.corner1 = null;
        this.corner2 = null;
    }

    public boolean hasAnySelection() {
        return corner1 != null || corner2 != null;
    }

    public boolean hasCompleteSelection() {
        return corner1 != null && corner2 != null;
    }

    public BlockPos getMinPos() {
        if (!hasCompleteSelection()) {
            return corner1 != null ? corner1 : BlockPos.ZERO;
        }
        return new BlockPos(
                Math.min(corner1.getX(), corner2.getX()),
                Math.min(corner1.getY(), corner2.getY()),
                Math.min(corner1.getZ(), corner2.getZ())
        );
    }

    public BlockPos getMaxPos() {
        if (!hasCompleteSelection()) {
            return corner1 != null ? corner1 : BlockPos.ZERO;
        }
        return new BlockPos(
                Math.max(corner1.getX(), corner2.getX()),
                Math.max(corner1.getY(), corner2.getY()),
                Math.max(corner1.getZ(), corner2.getZ())
        );
    }

    public Vec3i getDimensions() {
        if (!hasCompleteSelection()) {
            return Vec3i.ZERO;
        }
        BlockPos min = getMinPos();
        BlockPos max = getMaxPos();
        return new Vec3i(
                (max.getX() - min.getX()) + 1,
                (max.getY() - min.getY()) + 1,
                (max.getZ() - min.getZ()) + 1
        );
    }

    public long getVolume() {
        Vec3i dim = getDimensions();
        return (long) dim.getX() * dim.getY() * dim.getZ();
    }

    public AABB getNormalizedBounds() {
        BlockPos min = getMinPos();
        BlockPos max = getMaxPos();
        return new AABB(
                min.getX(), min.getY(), min.getZ(),
                max.getX() + 1.0D, max.getY() + 1.0D, max.getZ() + 1.0D
        );
    }

    public void nudgeCorner1(int dx, int dy, int dz) {
        if (corner1 != null) {
            corner1 = corner1.offset(dx, dy, dz);
        }
    }

    public void nudgeCorner2(int dx, int dy, int dz) {
        if (corner2 != null) {
            corner2 = corner2.offset(dx, dy, dz);
        }
    }
}
