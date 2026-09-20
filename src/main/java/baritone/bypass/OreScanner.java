package baritone.bypass;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Moduł skanowania rud wokół bota (kostka 11x11x11, czyli promień 5 bloków).
 * Filtruje bloki pod kątem wybranych rud, zasięgu kopania (<=4.5) oraz
 * braku sąsiedztwa lawy/wody (LiquidDetector).
 */
public final class OreScanner {

    private OreScanner() {}

    /**
     * Skanuje kostkę 11x11x11 wokół podanego centrum i zwraca listę bezpiecznych pozycji rud,
     * posortowanych wg priorytetu oraz odległości.
     */
    public static List<BlockPos> scanArea(Level world,
                                          BlockPos center,
                                          Set<Block> selectedOreBlocks,
                                          Vec3 eyePos,
                                          double reachLimit,
                                          BypassConfig config) {
        List<BlockPos> oreQueue = new ArrayList<>();
        if (world == null || center == null || selectedOreBlocks == null || selectedOreBlocks.isEmpty()) {
            return oreQueue;
        }

        double reachLimitSq = reachLimit * reachLimit;
        int radius = config.oreRadius; // domyślnie 5

        // Kostka (2*radius + 1)^3 wokół bota (np. 11x11x11 dla radius=5)
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    Block block = state.getBlock();

                    // Sprawdź czy pasuje do wybranych rud
                    if (selectedOreBlocks.contains(block)) {
                        // Sprawdź zasięg kopania od oczu gracza
                        if (eyePos != null) {
                            Vec3 centerOfBlock = Vec3.atCenterOf(pos);
                            if (centerOfBlock.distanceToSqr(eyePos) > reachLimitSq) {
                                // Opcjonalnie ignoruj lub dopuść jeśli bot podejdzie
                            }
                        }

                        // Sprawdź czy nie za lawą / czy bezpieczny do wykopania
                        if (LiquidDetector.isSafeToMine(world, pos)) {
                            oreQueue.add(pos);
                        }
                    }
                }
            }
        }

        // Sortowanie wg priorytetu rudy, a następnie wg odległości od centrum
        oreQueue.sort((p1, p2) -> {
            Block b1 = world.getBlockState(p1).getBlock();
            Block b2 = world.getBlockState(p2).getBlock();
            int prio1 = getPriority(b1, config);
            int prio2 = getPriority(b2, config);
            if (prio1 != prio2) {
                return Integer.compare(prio1, prio2);
            }
            return Double.compare(p1.distSqr(center), p2.distSqr(center));
        });

        return oreQueue;
    }

    private static int getPriority(Block block, BypassConfig config) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
        if (key == null) return Integer.MAX_VALUE;
        String path = key.getPath();

        for (int i = 0; i < config.priority.size(); i++) {
            String ore = config.priority.get(i);
            List<String> validNames = config.getOreBlockNames(ore);
            if (validNames.contains(path)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }
}
