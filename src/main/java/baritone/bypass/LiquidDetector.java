package baritone.bypass;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * Moduł detekcji płynów (lawa / woda).
 * Sprawdza bezpieczeństwo kopania we wszystkich 6 kierunkach,
 * zapobiegając zalaniu tunelu lub gracza.
 */
public final class LiquidDetector {

    private LiquidDetector() {}

    /**
     * Sprawdza 6 kierunków od bloku, który zamierzamy wykopać:
     * góra, dół, północ, południe, wschód, zachód.
     */
    public static boolean isSafeToMine(Level world, BlockPos target) {
        if (world == null || target == null) return false;
        return check(world, target.above()) && // góra
               check(world, target.below()) && // dół
               check(world, target.north()) && // północ
               check(world, target.south()) && // południe
               check(world, target.east())  && // wschód
               check(world, target.west());    // zachód
    }

    /**
     * Zwraca true jeśli dany blok NIE zawiera płynu (ani w stanie bloku, ani w FluidState).
     */
    public static boolean check(Level world, BlockPos pos) {
        if (world == null || pos == null) return false;
        BlockState state = world.getBlockState(pos);
        Block block = state.getBlock();

        if (block.equals(Blocks.LAVA) || block.equals(Blocks.WATER)) {
            return false;
        }

        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty()) {
            if (fluid.is(Fluids.LAVA) || fluid.is(Fluids.FLOWING_LAVA) ||
                fluid.is(Fluids.WATER) || fluid.is(Fluids.FLOWING_WATER)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Sprawdza czy dany blok jest bezpośrednio płynem.
     */
    public static boolean isLiquid(Level world, BlockPos pos) {
        return !check(world, pos);
    }

    /**
     * Weryfikuje blok przed postawieniem kroku / kopaniem.
     */
    public static boolean checkBlock(Level world, BlockPos pos) {
        return isSafeToMine(world, pos);
    }
}
