package baritone.bypass.mine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * Raycast 1:1 z waniliowym BlockGetter#clip (OUTLINE, Fluid.NONE), ale z nakładką
 * "co by było, gdyby te bloki były powietrzem" — bez kopiowania świata.
 * Używa waniliowego BlockGetter.traverseBlocks (DDA po voxelach) i VoxelShape#clip,
 * więc wynik jest identyczny z tym, co policzy klient (pick) i serwerowa replika Grima.
 */
public final class VoxelRay {

    public record Hit(BlockPos pos, Direction face, Vec3 location) {}

    private VoxelRay() {}

    /** @return trafienie albo null (MISS). */
    public static Hit cast(BlockGetter level, Vec3 from, Vec3 to, Set<BlockPos> hypotheticalAir) {
        return BlockGetter.traverseBlocks(from, to, hypotheticalAir, (air, pos) -> {
            if (air != null && !air.isEmpty() && air.contains(pos)) return null;
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) return null;
            BlockHitResult r = state.getShape(level, pos).clip(from, to, pos);
            // pos z traverseBlocks bywa mutowalny — kopiujemy jako immutable
            return r == null ? null : new Hit(r.getBlockPos().immutable(), r.getDirection(), r.getLocation());
        }, air -> null);
    }
}
