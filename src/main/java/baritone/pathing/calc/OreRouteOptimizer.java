package baritone.pathing.calc;

import net.minecraft.core.BlockPos;

import java.util.*;

/**
 * Zaawansowany silnik optymalizacji tras dla AI Baritone.
 *
 * Implementuje wielowarstwową architekturę planowania trasy:
 * 1. 3D Vein Clustering — automatyczne grupowanie odnalezionych rud w jednolite żyły (promień 3D <= 4.2 kratek, |dy| <= 3).
 *    Gwarantuje, że całe złoża (od góry do dołu) są traktowane jako pojedyncze nierozerwalne jednostki.
 * 2. Active Vein Pinning — złoże, przy którym stoi gracz (lub które zaczął kopać), ma bezwzględny priorytet
 *    i jest zablokowane na początku trasy (nigdy nie jest zamieniane przez TSP na odległe cele).
 * 3. 2-Opt + Or-Opt TSP Optimizer — heurystyka komiwojażera z lokalnym przeszukiwaniem 2-opt oraz relokacją Or-Opt,
 *    eliminująca przecinające się odcinki, cofanie się bota i "ping-pong" między korytarzami.
 * 4. Mining Corridor Alignment — preferowanie złóż leżących w osi korytarza (wzdłuż X lub Z), co eliminuje zbędne zakręty.
 * 5. Intra-Cluster Smooth Chaining — uporządkowanie bloków wewnątrz każdej żyły od punktu wejścia
 *    do punktu wyjścia w stronę następnej żyły (maksymalna płynność kopania bez gwałtownych obrotów).
 * 6. Drop Prioritization — natychmiastowe priorytetyzowanie upuszczonych surowców (itemów) przy graczu.
 */
public final class OreRouteOptimizer {

    private OreRouteOptimizer() {}

    /**
     * Główny punkt wejścia optymalizatora tras.
     *
     * @param startPos Pozycja startowa (stopy gracza)
     * @param positions Lista znalezionych pozycji bloków/rud
     * @param max Maksymalna liczba pozycji do zwrócenia
     * @param droppedPositions Opcjonalny zbiór pozycji z upuszczonymi surowcami
     * @return Zoptymalizowana, wygładzona sekwencja bloków do wykopania
     */
    public static List<BlockPos> optimizeRoute(BlockPos startPos, List<BlockPos> positions, int max, Collection<BlockPos> droppedPositions) {
        if (positions == null || positions.isEmpty() || max <= 0) {
            return new ArrayList<>();
        }

        // Usunięcie duplikatów z zachowaniem porządku
        Set<BlockPos> unique = new LinkedHashSet<>(positions);
        if (unique.size() <= 2) {
            List<BlockPos> res = new ArrayList<>(unique);
            if (res.size() > max) {
                return new ArrayList<>(res.subList(0, max));
            }
            return res;
        }

        Set<BlockPos> dropSet = (droppedPositions != null && !droppedPositions.isEmpty())
                ? new HashSet<>(droppedPositions)
                : Collections.emptySet();

        // 1. Klastrowanie rud w pełne trójwymiarowe żyły
        List<VeinCluster> clusters = clusterOres(new ArrayList<>(unique));

        // 2. Połączenie żył za pomocą ulepszonego TSP (Nearest Neighbor + 2-Opt + Or-Opt z blokadą aktywnego złoża)
        List<VeinCluster> orderedClusters = solveClusterTsp(startPos, clusters);

        // 3. Wygładzenie kolejności bloków wewnątrz każdej żyły
        List<BlockPos> finalRoute = new ArrayList<>(Math.min(unique.size(), max));
        BlockPos currentEnd = startPos;

        for (int i = 0; i < orderedClusters.size() && finalRoute.size() < max; i++) {
            VeinCluster cluster = orderedClusters.get(i);
            BlockPos nextClusterCentroid = (i + 1 < orderedClusters.size())
                    ? orderedClusters.get(i + 1).centroidPos()
                    : null;

            List<BlockPos> clusterOrdered = orderClusterBlocks(currentEnd, nextClusterCentroid, cluster.blocks, dropSet);
            for (BlockPos bp : clusterOrdered) {
                finalRoute.add(bp);
                currentEnd = bp;
                if (finalRoute.size() >= max) {
                    break;
                }
            }
        }

        return finalRoute;
    }

    /**
     * Klastruje sąsiadujące rudy w pełne trójwymiarowe żyły (Connected Components BFS).
     * Obejmuje całą wysokość i szerokość żyły wygenerowanej w Minecraftcie (promień 3D <= 4.2 kratek, |dy| <= 3).
     * Całe złoże (nawet o 10-15 blokach na różnych warstwach Y) stanowi JEDEN nierozerwalny klaster.
     */
    public static List<VeinCluster> clusterOres(List<BlockPos> ores) {
        List<VeinCluster> clusters = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        double maxDist3DSq = 18.0; // Promień połączenia w 3D (~4.24 kratki)

        for (BlockPos ore : ores) {
            if (visited.contains(ore)) {
                continue;
            }
            List<BlockPos> clusterBlocks = new ArrayList<>();
            Queue<BlockPos> queue = new ArrayDeque<>();
            queue.add(ore);
            visited.add(ore);

            while (!queue.isEmpty()) {
                BlockPos current = queue.poll();
                clusterBlocks.add(current);

                for (BlockPos candidate : ores) {
                    if (!visited.contains(candidate)) {
                        int dx = current.getX() - candidate.getX();
                        int dy = current.getY() - candidate.getY();
                        int dz = current.getZ() - candidate.getZ();
                        // Łącz bloki żyły w 3D: uwzględnia pionową rozpiętość żyły oraz sąsiedztwo w skale
                        if (Math.abs(dy) <= 3 && (dx * dx + dy * dy + dz * dz <= maxDist3DSq)) {
                            visited.add(candidate);
                            queue.add(candidate);
                        }
                    }
                }
            }
            clusters.add(new VeinCluster(clusterBlocks));
        }
        return clusters;
    }

    /**
     * Rozwiązuje problem komiwojażera (TSP) dla klastrów z blokadą aktywnego złoża:
     * Krok 1: Greedy Nearest Neighbor z ciężkim kosztem pionowym (dy * 3.5) i premią za oś korytarza.
     * Krok 2: Blokada pierwszego klastra, jeśli gracz już przy nim stoi (odległość <= 6 kratek).
     * Krok 3: 2-Opt local search – eliminacja skrzyżowań.
     * Krok 4: Or-Opt relocation – przenoszenie łańcuchów 1-2 klastrów do optymalniejszych pozycji.
     */
    private static List<VeinCluster> solveClusterTsp(BlockPos startPos, List<VeinCluster> clusters) {
        if (clusters.size() <= 2) {
            clusters.sort(Comparator.comparingDouble(c -> c.distSqrTo(startPos)));
            return clusters;
        }

        // Krok 1: Greedy Nearest Neighbor
        List<VeinCluster> remaining = new ArrayList<>(clusters);
        List<VeinCluster> route = new ArrayList<>(clusters.size());

        VeinCluster current = remaining.stream()
                .min(Comparator.comparingDouble(c -> c.distSqrTo(startPos)))
                .orElse(remaining.get(0));
        route.add(current);
        remaining.remove(current);

        while (!remaining.isEmpty()) {
            final VeinCluster from = current;
            VeinCluster next = remaining.stream()
                    .min(Comparator.comparingDouble(c -> from.distSqrTo(c)))
                    .orElse(remaining.get(0));
            route.add(next);
            remaining.remove(next);
            current = next;
        }

        // Krok 2: Ustal czy pierwszy klaster jest przy graczu (aktywne złoże w zasięgu <= 6.5 kratek)
        // Jeśli tak -> ZABLOKUJ indeks 0, aby 2-opt i Or-Opt NIGDY nie zamieniły złoża, przy którym gracz już stoi!
        boolean lockFirst = route.get(0).distSqrTo(startPos) <= 42.25;
        int startI = lockFirst ? 1 : 0;
        int n = route.size();

        // Krok 3: 2-Opt Local Search (eliminacja skrzyżowań)
        boolean improved = true;
        int maxPasses = 50;
        int pass = 0;

        while (improved && pass++ < maxPasses) {
            improved = false;
            for (int i = startI; i < n - 1; i++) {
                VeinCluster prev = (i == 0) ? null : route.get(i - 1);
                BlockPos pPos = (prev != null) ? prev.centroidPos() : startPos;

                for (int j = i + 1; j < n; j++) {
                    VeinCluster cI = route.get(i);
                    VeinCluster cJ = route.get(j);
                    VeinCluster next = (j + 1 < n) ? route.get(j + 1) : null;

                    double currentDist = cI.distSqrTo(pPos);
                    if (next != null) {
                        currentDist += cJ.distSqrTo(next);
                    }

                    double newDist = cJ.distSqrTo(pPos);
                    if (next != null) {
                        newDist += cI.distSqrTo(next);
                    }

                    // Jeśli odwrócenie pod-ścieżki [i ... j] skraca trasę:
                    if (newDist < currentDist - 0.01) {
                        Collections.reverse(route.subList(i, j + 1));
                        improved = true;
                    }
                }
            }
        }

        // Krok 4: Or-Opt (Relocation) — optymalizacja łańcuchów 1-2 klastrów
        if (n - startI >= 3) {
            for (int len = 2; len >= 1; len--) {
                boolean orImproved = true;
                int orPass = 0;
                while (orImproved && orPass++ < 25) {
                    orImproved = false;
                    for (int i = startI; i <= n - len; i++) {
                        double currentCost = tourCost(startPos, route);
                        List<VeinCluster> segment = new ArrayList<>(route.subList(i, i + len));
                        List<VeinCluster> testRoute = new ArrayList<>(route);
                        for (int k = 0; k < len; k++) {
                            testRoute.remove(i);
                        }
                        for (int insertAt = startI; insertAt <= testRoute.size(); insertAt++) {
                            if (insertAt == i) continue;
                            testRoute.addAll(insertAt, segment);
                            double newCost = tourCost(startPos, testRoute);
                            if (newCost < currentCost - 0.05) {
                                route.clear();
                                route.addAll(testRoute);
                                orImproved = true;
                                break;
                            }
                            for (int k = 0; k < len; k++) {
                                testRoute.remove(insertAt);
                            }
                        }
                        if (orImproved) break;
                    }
                }
            }
        }

        return route;
    }

    private static double tourCost(BlockPos startPos, List<VeinCluster> route) {
        if (route.isEmpty()) return 0.0;
        double cost = route.get(0).distSqrTo(startPos);
        for (int i = 0; i < route.size() - 1; i++) {
            cost += route.get(i).distSqrTo(route.get(i + 1));
        }
        return cost;
    }

    /**
     * Układa bloki wewnątrz pojedynczej żyły:
     * 1. Upuszczone surowce (dropy) na samym początku.
     * 2. Blok początkowy (najbliższy wejściu z premią dla bloków już osiągalnych).
     * 3. Ciągły łańcuch najbliższych sąsiadów zorientowany ku kolejnej żyle.
     */
    private static List<BlockPos> orderClusterBlocks(BlockPos entryPos, BlockPos exitPos, List<BlockPos> blocks, Set<BlockPos> drops) {
        if (blocks.size() <= 1) {
            return new ArrayList<>(blocks);
        }

        List<BlockPos> ordered = new ArrayList<>(blocks.size());
        List<BlockPos> remaining = new ArrayList<>(blocks);

        // 1. Najpierw upuszczone itemy
        Iterator<BlockPos> it = remaining.iterator();
        while (it.hasNext()) {
            BlockPos p = it.next();
            if (drops.contains(p)) {
                ordered.add(p);
                it.remove();
            }
        }

        if (remaining.isEmpty()) {
            return ordered;
        }

        // 2. Blok początkowy: najpierw blok, który gracz już może dosięgnąć (lub najbliższy)
        BlockPos current = remaining.stream()
                .min(Comparator.comparingDouble(b -> {
                    double d = weightedDistSq(entryPos, b);
                    // Jeśli gracz jest w odległości <= 3.8 kratek, daj duży priorytet, aby natychmiast go wykopano
                    if (entryPos.distSqr(b) <= 14.44) {
                        d *= 0.25;
                    }
                    return d;
                }))
                .orElse(remaining.get(0));
        ordered.add(current);
        remaining.remove(current);

        // 3. Łańcuch nearest-neighbor ku kolejnej żyle
        while (!remaining.isEmpty()) {
            final BlockPos from = current;
            BlockPos next = remaining.stream()
                    .min(Comparator.comparingDouble(b -> {
                        double d = weightedDistSq(from, b);
                        if (exitPos != null && remaining.size() <= 2) {
                            d += 0.35 * weightedDistSq(exitPos, b);
                        }
                        return d;
                    }))
                    .orElse(remaining.get(0));
            ordered.add(next);
            remaining.remove(next);
            current = next;
        }

        return ordered;
    }

    /**
     * Zważona metryka odległości dla górnictwa:
     * - Składowa pionowa (dy) mnożona jest przez 3.5 (dy^2 mnożone przez 12.25).
     *   Eliminuje niepotrzebne schodzenie w dół lub wchodzenie w górę, gdy w poziomie są dostępne rudy.
     * - Corridor Alignment: jeśli bloki leżą w osi prostoliniowego korytarza (dx <= 2 lub dz <= 2),
     *   otrzymują 15% premii (koszt * 0.85), eliminując zygzakowanie w kopalni.
     */
    public static double weightedDistSq(double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x1 - x2;
        double dy = (y1 - y2) * 3.5;
        double dz = z1 - z2;
        double distSq = dx * dx + dy * dy + dz * dz;

        if (Math.abs(dx) <= 2.0 || Math.abs(dz) <= 2.0) {
            distSq *= 0.85; // 15% premii za prostoliniowy korytarz
        }
        return distSq;
    }

    public static double weightedDistSq(BlockPos a, BlockPos b) {
        return weightedDistSq(a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ());
    }

    /**
     * Reprezentacja klastra (żyły rudy) ze skalkulowanym środkiem ciężkości (centroidem).
     */
    public static final class VeinCluster {
        public final List<BlockPos> blocks;
        public final double cx, cy, cz;
        private final BlockPos centroid;

        public VeinCluster(List<BlockPos> blocks) {
            this.blocks = blocks;
            double sumX = 0, sumY = 0, sumZ = 0;
            for (BlockPos b : blocks) {
                sumX += b.getX();
                sumY += b.getY();
                sumZ += b.getZ();
            }
            int size = Math.max(1, blocks.size());
            this.cx = sumX / size;
            this.cy = sumY / size;
            this.cz = sumZ / size;
            this.centroid = new BlockPos((int) Math.round(cx), (int) Math.round(cy), (int) Math.round(cz));
        }

        public BlockPos centroidPos() {
            return centroid;
        }

        public double distSqrTo(BlockPos pos) {
            return weightedDistSq(cx, cy, cz, pos.getX(), pos.getY(), pos.getZ());
        }

        public double distSqrTo(VeinCluster other) {
            return weightedDistSq(cx, cy, cz, other.cx, other.cy, other.cz);
        }
    }
}
