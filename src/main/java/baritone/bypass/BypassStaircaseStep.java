package baritone.bypass;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.List;

/** A fixed step, retained during the jump instead of being rebuilt from airborne feet. */
record BypassStaircaseStep(BlockPos source, BlockPos destination, List<BlockPos> clearance) {
    static BypassStaircaseStep create(BlockPos feet, Direction direction, boolean ascending) {
        if (direction.getAxis().isVertical()) {
            throw new IllegalArgumentException("A staircase needs a horizontal direction");
        }
        BlockPos source = feet.immutable();
        BlockPos front = source.relative(direction);
        BlockPos destination = ascending ? front.above() : front.below();
        // Break the nearer face before the upper block behind it.
        List<BlockPos> clearance = ascending
                ? List.of(source.above(2), destination, destination.above())
                : List.of(front.above(), front, destination);
        return new BypassStaircaseStep(source, destination, clearance);
    }

    BlockPos support() {
        return destination.below();
    }

    boolean hasLanded(BlockPos feet, boolean onGround) {
        return onGround && feet.equals(destination);
    }
}
