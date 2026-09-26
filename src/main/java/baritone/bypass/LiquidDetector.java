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
     * Sprawdza bezpieczeństwo kopania bloku:
     * 1. 6 bezpośrednich kierunków (góra, dół, północ, południe, wschód, zachód).
     * 2. Blok 2 poziomy wyżej (target.above(2)) - ochrona przed spadającą lawą/wodą.
     * 3. Promień 2 bloków w poziomie - jeśli blok pośredni jest powietrzem lub wymienialny,
     *    płyn wleje się natychmiast po wykopaniu.
     */
    public static boolean isSafeToMine(Level world, BlockPos target) {
        if (world == null || target == null) return false;

        // 1. Bezpośredni blok i 6 sąsiadów
        if (!check(world, target) ||
            !check(world, target.above()) ||
            !check(world, target.below()) ||
            !check(world, target.north()) ||
            !check(world, target.south()) ||
            !check(world, target.east())  ||
            !check(world, target.west())) {
            return false;
        }

        // 2. Blok 2 w górę (spadające płyny)
        if (!check(world, target.above(2))) {
            BlockState aboveState = world.getBlockState(target.above());
            if (aboveState.isAir() || aboveState.canBeReplaced()) {
                return false;
            }
        }

        // 3. Płyny 2 bloki w poziomie i po skosie w górę (przelewanie się płynów)
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos near = target.relative(dir);
            BlockPos far = near.relative(dir);
            BlockState nearState = world.getBlockState(near);

            boolean nearOpen = nearState.isAir() || nearState.canBeReplaced();
            if (nearOpen) {
                if (!check(world, far) || !check(world, near.above())) {
                    return false;
                }
            }
        }

        return true;
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
