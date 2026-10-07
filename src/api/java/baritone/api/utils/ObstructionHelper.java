package baritone.api.utils;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;

public final class ObstructionHelper {

    private ObstructionHelper() {}

    /**
     * Checks if a block state represents a fragile/clearable obstacle (snow layer or vine)
     * that obstructs line-of-sight or physical movement towards a mining target.
     */
    public static boolean isClearableObstruction(BlockState state) {
        if (state == null) return false;
        Block block = state.getBlock();
        return block instanceof SnowLayerBlock
                || block == Blocks.SNOW
                || block instanceof VineBlock
                || block == Blocks.VINE
                || block instanceof CaveVines
                || block == Blocks.CAVE_VINES
                || block == Blocks.CAVE_VINES_PLANT
                || block == Blocks.TWISTING_VINES
                || block == Blocks.TWISTING_VINES_PLANT
                || block == Blocks.WEEPING_VINES
                || block == Blocks.WEEPING_VINES_PLANT;
    }

    /**
     * Checks if obstructionPos is directly covering, adjacent to, or obstructing targetPos.
     */
    public static boolean isCoveringOrAdjacent(BlockPos obstructionPos, BlockPos targetPos) {
        if (obstructionPos == null || targetPos == null) return false;
        if (obstructionPos.equals(targetPos)) return true;
        // Snow layer directly on top of the target block
        if (obstructionPos.equals(targetPos.above())) return true;
        // Vines or snow adjacent to the target block
        int dx = Math.abs(obstructionPos.getX() - targetPos.getX());
        int dy = Math.abs(obstructionPos.getY() - targetPos.getY());
        int dz = Math.abs(obstructionPos.getZ() - targetPos.getZ());
        return (dx <= 1 && dy <= 1 && dz <= 1 && (dx + dy + dz) <= 2);
    }
}
