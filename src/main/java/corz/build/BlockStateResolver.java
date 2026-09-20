package corz.build;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Klasa adaptera zgodności z prototypem corz.
 * Deleguje logikę bezpośrednio do silnika Baritone BlockStateResolver.
 */
public final class BlockStateResolver {

    private BlockStateResolver() {}

    public static BlockState resolvePlacementState(BlockState original, Level world, BlockPos pos) {
        return baritone.utils.schematic.BlockStateResolver.resolvePlacementState(original, world, pos);
    }
}
