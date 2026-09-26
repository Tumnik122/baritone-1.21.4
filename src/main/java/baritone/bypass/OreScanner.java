package baritone.bypass;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Moduł skanowania rud wokół bota (kostka 11x11x11, czyli promień 5 bloków).
 *
 * Dodatkowo zarządza czarną listą rud (blacklistedOres) oraz licznikami nieudanych prób (MAX_FAILED_ATTEMPTS = 3):
 * - Ruda niemożliwa do wykopania (za daleko, za lawą) trafia na blacklistę i jest pomijana.
 * - Po 3 nieudanych próbach ruda zostaje zblacklistowana, co pozwala botowi wrócić do TUNNELING.
 */
public final class OreScanner {

    public static final int MAX_FAILED_ATTEMPTS = 3;
    private static final int MAX_CACHE_ENTRIES = 512;

    private static final Set<BlockPos> blacklistedOres = ConcurrentHashMap.newKeySet();
    private static final Map<BlockPos, Integer> failedAttempts = new ConcurrentHashMap<>();

    private OreScanner() {}

    /**
     * Dodaje pozycję rudy do czarnej listy (nigdy więcej nie próbuj jej kopać).
     * Posiada limit wielkości (LRU eviction), aby zapobiec wyciekom pamięci w wielogodzinnych sesjach.
     */
    public static void blacklist(BlockPos pos) {
        if (pos == null) return;
        if (blacklistedOres.size() >= MAX_CACHE_ENTRIES) {
            Iterator<BlockPos> it = blacklistedOres.iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        blacklistedOres.add(pos.immutable());
        failedAttempts.remove(pos);
    }

    /**
     * Sprawdza, czy dana ruda znajduje się na czarnej liście.
     */
    public static boolean isBlacklisted(BlockPos pos) {
        return pos != null && blacklistedOres.contains(pos);
    }

    /**
     * Czyści czarną listę i liczniki prób.
     */
    public static void clearBlacklist() {
        blacklistedOres.clear();
        failedAttempts.clear();
    }

    /**
     * Rejestruje nieudaną próbę wykopania rudy.
     * Jeśli liczba prób osiągnie MAX_FAILED_ATTEMPTS (3), automatycznie dodaje rudę do czarnej listy.
     * Zwraca aktualną liczbę prób.
     */
    public static int recordFailedAttempt(BlockPos pos) {
        if (pos == null) return 0;
        BlockPos immutable = pos.immutable();
        if (failedAttempts.size() >= MAX_CACHE_ENTRIES) {
            Iterator<BlockPos> it = failedAttempts.keySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        int attempts = failedAttempts.compute(immutable, (k, v) -> v == null ? 1 : v + 1);
        if (attempts >= MAX_FAILED_ATTEMPTS) {
            blacklist(immutable);
        }
        return attempts;
    }

    /**
     * Zwraca liczbę dotychczasowych nieudanych prób dla danej rudy.
     */
    public static int getFailedAttempts(BlockPos pos) {
        if (pos == null) return 0;
        return failedAttempts.getOrDefault(pos, 0);
    }

    /**
     * Resetuje licznik prób po udanym zniszczeniu bloku.
     */
    public static void resetAttempts(BlockPos pos) {
        if (pos != null) {
            failedAttempts.remove(pos);
        }
    }

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

        int radius = config.oreRadius; // domyślnie 5

        // Kostka (2*radius + 1)^3 wokół bota
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = center.offset(x, y, z);

                    // Pomiń zblacklistowane pozycje
                    if (isBlacklisted(pos)) {
                        continue;
                    }

                    BlockState state = world.getBlockState(pos);
                    Block block = state.getBlock();

                    if (selectedOreBlocks.contains(block)) {
                        // Sprawdź czy nie za lawą / czy bezpieczny do wykopania
                        if (!LiquidDetector.isSafeToMine(world, pos)) {
                            blacklist(pos); // Ruda za płynem/lawą -> natychmiastowa czarna lista
                            continue;
                        }

                        oreQueue.add(pos.immutable());
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

    /**
     * Szuka najlepszej rudy w podanym promieniu (zgodnie z priorytetem i dystansem).
     * Jedyne źródło prawdy dla wyboru rudy w całym systemie bypass.
     */
    public static BlockPos findBestOre(Level world,
                                       BlockPos center,
                                       Set<Block> targetOreBlocks,
                                       int radius,
                                       BypassConfig config) {
        if (world == null || center == null || targetOreBlocks == null || targetOreBlocks.isEmpty()) {
            return null;
        }

        BlockPos bestPos = null;
        int bestPriority = Integer.MAX_VALUE;
        double bestDistSq = Double.MAX_VALUE;

        int cx = center.getX();
        int cy = center.getY();
        int cz = center.getZ();
        int rSq = radius * radius;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dy * dy + dz * dz > rSq) continue;
                    BlockPos p = new BlockPos(cx + dx, cy + dy, cz + dz);

                    if (isBlacklisted(p)) continue;

                    BlockState state = world.getBlockState(p);
                    Block b = state.getBlock();

                    if (targetOreBlocks.contains(b)) {
                        if (!LiquidDetector.isSafeToMine(world, p)) {
                            blacklist(p);
                            continue;
                        }

                        int prio = getPriority(b, config);
                        double dSq = p.distSqr(center);
                        if (prio < bestPriority || (prio == bestPriority && dSq < bestDistSq)) {
                            bestPriority = prio;
                            bestDistSq = dSq;
                            bestPos = p.immutable();
                        }
                    }
                }
            }
        }
        return bestPos;
    }

    public static int getPriority(Block block, BypassConfig config) {
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
