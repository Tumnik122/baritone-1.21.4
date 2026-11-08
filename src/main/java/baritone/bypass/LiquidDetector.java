package baritone.bypass;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * Zaawansowany moduł detekcji płynów (lawa / woda) oraz stabilności podłoża.
 * Zabezpiecza bota przed:
 * 1. Wykopywaniem bloków odsłaniających lawę/wodę (góra, dół, boki i lookahead).
 * 2. Krokami w pustą przestrzeń, jaskinie, wąwozy i jeziora lawowe (brak stabilnej podłogi).
 * 3. Zapaleniem się od bloków magmy, ognia i lawy pod kruchym podłożem (żwir/piasek).
 */
public final class LiquidDetector {

    private LiquidDetector() {}

    /**
     * Sprawdza bezpieczeństwo kopania bloku z uwzględnieniem kierunku tunelu i poziomu stóp gracza.
     */
    public static boolean isSafeToMine(Level world, BlockPos target, Direction tunnelDir, int playerFeetY) {
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
                if (!check(world, far) || !check(world, near.above()) || !check(world, far.above())) {
                    return false;
                }
            }
        }

        // 4. Jeśli kopiemy blok na wysokości stóp gracza lub niżej:
        // upewnij się, że pod nim nie znajduje się jezioro lawy lub głęboka szczelina!
        if (target.getY() <= playerFeetY) {
            if (isLavaPitBelow(world, target.below(), 8)) {
                return false;
            }
            // Zabezpieczenie przed przebiciem podłogi do globalnego oceanu lawy (Y = -54 do -64)
            if (target.getY() <= -53 && (isLava(world, target.below()) || isLavaPitBelow(world, target.below(), 4))) {
                return false;
            }
        }

        // 5. Lookahead wzdłuż osi tunelu (czy 1-2 bloki w przód nie ma ściany lawy)
        if (tunnelDir != null) {
            BlockPos ahead = target.relative(tunnelDir);
            if (!check(world, ahead) || !check(world, ahead.above())) {
                return false;
            }
            if (target.getY() <= playerFeetY && isLavaPitBelow(world, ahead.below(), 8)) {
                return false;
            }
        }

        return true;
    }

    public static boolean isSafeToMine(Level world, BlockPos target) {
        return isSafeToMine(world, target, null, target != null ? target.getY() : 64);
    }

    /**
     * Weryfikuje, czy gracz może bezpiecznie postawić stopę na pozycji feetPos.
     * Wymaga:
     * - Solidnej, bezpiecznej podłogi na feetPos.below() (nie powietrze, nie lawa, nie ogień).
     * - Braku wiszącego niepodpartego żwiru/piasku nad lawą.
     * - Braku płynów w przestrzeni stóp i głowy.
     * - Braku jeziora lawy pod podłogą w promieniu pionowym 8 kratek.
     */
    public static boolean isSafeToStep(Level world, BlockPos feetPos) {
        if (world == null || feetPos == null) return false;

        BlockPos floorPos = feetPos.below();
        BlockState floorState = world.getBlockState(floorPos);
        Block floorBlock = floorState.getBlock();

        // 1. Podłoga nie może być powietrzem, płynem, ogniem ani magmą
        if (floorState.isAir() || floorState.canBeReplaced() || isLiquid(world, floorPos)
                || floorBlock instanceof BaseFireBlock || floorBlock.equals(Blocks.MAGMA_BLOCK)) {
            return false;
        }

        // 2. Jeśli podłoga to blok sypki (piasek/żwir), musi mieć solidne podparcie
        if (floorBlock instanceof FallingBlock) {
            int minY = Math.max(world.getMinY(), floorPos.getY() - 6);
            boolean foundSolidBase = false;
            for (int y = floorPos.getY() - 1; y >= minY; y--) {
                BlockPos check = new BlockPos(floorPos.getX(), y, floorPos.getZ());
                BlockState s = world.getBlockState(check);
                if (isLava(world, check) || s.getBlock() instanceof BaseFireBlock || s.getBlock().equals(Blocks.MAGMA_BLOCK)) {
                    return false;
                }
                if (!s.isAir() && !s.canBeReplaced() && !(s.getBlock() instanceof FallingBlock) && s.getFluidState().isEmpty()) {
                    foundSolidBase = true;
                    break;
                }
            }
            if (!foundSolidBase) {
                return false;
            }
        }

        // 3. W przestrzeni stóp i głowy nie może być cieczy
        if (!check(world, feetPos) || !check(world, feetPos.above())) {
            return false;
        }

        // 4. Bezpośrednie sąsiedztwo poziome nie może być lawą
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos adjFeet = feetPos.relative(dir);
            BlockPos adjHead = feetPos.above().relative(dir);
            if (isLava(world, adjFeet) || isLava(world, adjHead)) {
                return false;
            }
        }

        // 5. Skan kolumny pod podłogą pod kątem ukrytych basenów lawy
        if (isLavaPitBelow(world, floorPos, 8)) {
            return false;
        }

        return true;
    }

    /**
     * Skanuje kolumnę w dół w poszukiwaniu lawy zanim napotka solidny, szczelny blok podłoża.
     */
    public static boolean isLavaPitBelow(Level world, BlockPos startPos, int maxDepth) {
        if (world == null || startPos == null) return false;
        int minY = Math.max(world.getMinY(), startPos.getY() - maxDepth);

        for (int y = startPos.getY(); y >= minY; y--) {
            BlockPos checkPos = new BlockPos(startPos.getX(), y, startPos.getZ());
            BlockState state = world.getBlockState(checkPos);
            Block block = state.getBlock();

            if (block.equals(Blocks.LAVA) || state.getFluidState().is(Fluids.LAVA) || state.getFluidState().is(Fluids.FLOWING_LAVA)
                    || block instanceof BaseFireBlock || block.equals(Blocks.MAGMA_BLOCK)) {
                return true;
            }

            // Jeśli trafiliśmy na w 100% litą, bezpieczną skałę (nie sypki żwir/piasek)
            if (!state.isAir() && !state.canBeReplaced() && !(block instanceof FallingBlock) && state.getFluidState().isEmpty()) {
                // Sprawdź czy z boków tego poziomu nie spływa lawa
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockPos adj = checkPos.relative(dir);
                    BlockState adjState = world.getBlockState(adj);
                    if (adjState.getFluidState().is(Fluids.LAVA) || adjState.getFluidState().is(Fluids.FLOWING_LAVA)) {
                        return true;
                    }
                }
                return false;
            }
        }
        return false;
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
     * Sprawdza czy dany blok jest bezpośrednio lawą.
     */
    public static boolean isLava(Level world, BlockPos pos) {
        if (world == null || pos == null) return false;
        BlockState state = world.getBlockState(pos);
        if (state.getBlock().equals(Blocks.LAVA)) return true;
        FluidState fluid = state.getFluidState();
        return fluid.is(Fluids.LAVA) || fluid.is(Fluids.FLOWING_LAVA);
    }

    /**
     * Weryfikuje blok przed postawieniem kroku / kopaniem.
     */
    public static boolean checkBlock(Level world, BlockPos pos) {
        return isSafeToMine(world, pos);
    }
}
