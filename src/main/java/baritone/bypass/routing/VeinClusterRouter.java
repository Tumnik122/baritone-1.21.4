package baritone.bypass.routing;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Zero-GC Spatial Grid-Hash + Union-Find Vein Clusterer,
 * Time-Weighted 2-Opt TSP Solver & Stand-Off Hull Selector.
 *
 * Kompatybilny z Minecraft 1.21.4 (Mojang Mappings) oraz GrimAC 1.21.4.
 */
public final class VeinClusterRouter {

    // Promień łączenia bloków w jedną żyłę: sqrt(12) = 3.464 <= 3.5 bloku
    private static final int CLUSTER_DIST_SQ_THRESHOLD = 12;
    private static final double MAX_SCAN_RADIUS_SQ = 48.0 * 48.0;

    // GrimAC-safe eye reach (Vanilla = 4.5, Grim safe = 4.20, margin = 3.95)
    public static final double SAFE_REACH = 3.95D;
    public static final double SAFE_REACH_SQ = SAFE_REACH * SAFE_REACH;
    private static final double PLAYER_EYE_HEIGHT = 1.62D;

    // Parametry kosztu czasowego między klastrami (w tickach)
    private static final double SPRINT_BLOCKS_PER_TICK = 0.2806D; // 5.612 m/s
    private static final double VERTICAL_PENALTY_PER_BLOCK = 4.2D; // schodki/kopanie góra-dół
    private static final double ACTIVE_CLUSTER_COMMIT_BONUS = 45.0D; // anty-ping-pong (~2.25s histerezy)
    private static final int MAX_2OPT_ITERATIONS = 64;
    private static final int MAX_CLUSTERS_FOR_TSP = 40;

    // Prealokowane bufory robocze (Zero-GC w pętli odświeżania)
    private int capacity = 512;
    private long[] orePositions = new long[capacity];
    private int[] ufParent = new int[capacity];
    private int[] ufRank = new int[capacity];
    private int[] rootToClusterIdx = new int[capacity];

    private final Long2ObjectOpenHashMap<IntArrayList> spatialBuckets = new Long2ObjectOpenHashMap<>(256);
    private final List<IntArrayList> bucketPool = new ArrayList<>(256);
    private int bucketPoolCursor = 0;

    private final List<OreCluster> activeRoute = new ArrayList<>(MAX_CLUSTERS_FOR_TSP);
    private final double[][] costMatrix = new double[MAX_CLUSTERS_FOR_TSP + 1][MAX_CLUSTERS_FOR_TSP + 1];
    private final int[] tourOrder = new int[MAX_CLUSTERS_FOR_TSP + 1];
    private final boolean[] visited = new boolean[MAX_CLUSTERS_FOR_TSP + 1];

    // Reużywalne obiekty MutableBlockPos (brak alokacji w pętlach skanujących)
    private final BlockPos.MutableBlockPos scratchPos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos standCheckPos = new BlockPos.MutableBlockPos();

    private long committedClusterAnchor = Long.MIN_VALUE;

    /**
     * Reprezentacja pojedynczej żyły rudy z wyznaczonym punktem Stand-Off.
     */
    public static final class OreCluster {
        public final LongArrayList blocks = new LongArrayList(16);
        public double centroidX;
        public double centroidY;
        public double centroidZ;
        public int minX, minY, minZ, maxX, maxY, maxZ;
        public long anchorBlockLong; // stabilny identyfikator klastra (najmniejszy spakowany long)

        // Wyznaczony optymalny punkt stania (Stand-Off Hull)
        public long bestStandPosLong = Long.MIN_VALUE;
        public final LongArrayList reachableFromStandPos = new LongArrayList(16);
        public boolean hasValidStandSpot = false;

        public void reset() {
            blocks.clear();
            reachableFromStandPos.clear();
            minX = minY = minZ = Integer.MAX_VALUE;
            maxX = maxY = maxZ = Integer.MIN_VALUE;
            centroidX = centroidY = centroidZ = 0.0;
            anchorBlockLong = Long.MAX_VALUE;
            bestStandPosLong = Long.MIN_VALUE;
            hasValidStandSpot = false;
        }

        public void addBlock(long packedPos) {
            blocks.add(packedPos);
            int x = BlockPos.getX(packedPos);
            int y = BlockPos.getY(packedPos);
            int z = BlockPos.getZ(packedPos);
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (z < minZ) minZ = z;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
            if (z > maxZ) maxZ = z;
            centroidX += x + 0.5;
            centroidY += y + 0.5;
            centroidZ += z + 0.5;
            if (packedPos < anchorBlockLong) {
                anchorBlockLong = packedPos;
            }
        }

        public void finalizeGeometry() {
            int n = blocks.size();
            if (n > 0) {
                double inv = 1.0 / n;
                centroidX *= inv;
                centroidY *= inv;
                centroidZ *= inv;
            }
        }

        public boolean contains(long packedPos) {
            for (int i = 0, s = blocks.size(); i < s; i++) {
                if (blocks.getLong(i) == packedPos) return true;
            }
            return false;
        }
    }

    private final List<OreCluster> clusterPool = new ArrayList<>(64);

    public VeinClusterRouter() {
        for (int i = 0; i < 64; i++) {
            clusterPool.add(new OreCluster());
        }
        for (int i = 0; i < 256; i++) {
            bucketPool.add(new IntArrayList(8));
        }
    }

    /**
     * Przebudowuje klastry, rozwiązuje TSP 2-Opt i oblicza optymalny punkt Stand-Off
     * dla bieżącego (pierwszego) klastra na trasie.
     *
     * @param rawOres spakowane współrzędne (BlockPos.asLong) wykrytych rud
     * @param player  lokalny gracz
     * @param level   świat klienta
     * @return uporządkowana lista klastrów do wyczyszczenia
     */
    public List<OreCluster> rebuildRoute(LongArrayList rawOres, LocalPlayer player, Level level) {
        activeRoute.clear();
        if (rawOres.isEmpty()) {
            committedClusterAnchor = Long.MIN_VALUE;
            return activeRoute;
        }

        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();

        // 1. Filtracja promienia 48 bloków i kopiowanie do płaskiej tablicy
        int count = 0;
        ensureCapacity(rawOres.size());
        for (int i = 0, n = rawOres.size(); i < n; i++) {
            long packed = rawOres.getLong(i);
            double dx = (BlockPos.getX(packed) + 0.5) - px;
            double dy = (BlockPos.getY(packed) + 0.5) - py;
            double dz = (BlockPos.getZ(packed) + 0.5) - pz;
            if (dx * dx + dy * dy + dz * dz <= MAX_SCAN_RADIUS_SQ) {
                orePositions[count] = packed;
                ufParent[count] = count;
                ufRank[count] = 0;
                rootToClusterIdx[count] = -1;
                count++;
            }
        }

        if (count == 0) {
            committedClusterAnchor = Long.MIN_VALUE;
            return activeRoute;
        }

        // 2. Klasteryzacja Spatial Grid-Hash (komórki 4x4x4) + Union-Find
        spatialBuckets.clear();
        bucketPoolCursor = 0;

        for (int i = 0; i < count; i++) {
            long p = orePositions[i];
            long cellKey = BlockPos.asLong(
                    BlockPos.getX(p) >> 2,
                    BlockPos.getY(p) >> 2,
                    BlockPos.getZ(p) >> 2
            );
            IntArrayList bucket = spatialBuckets.get(cellKey);
            if (bucket == null) {
                bucket = borrowBucket();
                spatialBuckets.put(cellKey, bucket);
            }
            bucket.add(i);
        }

        for (int i = 0; i < count; i++) {
            long p = orePositions[i];
            int bx = BlockPos.getX(p);
            int by = BlockPos.getY(p);
            int bz = BlockPos.getZ(p);
            int cx = bx >> 2;
            int cy = by >> 2;
            int cz = bz >> 2;

            // Skanujemy wyłącznie 27 sąsiednich komórek siatki
            for (int ox = -1; ox <= 1; ox++) {
                for (int oy = -1; oy <= 1; oy++) {
                    for (int oz = -1; oz <= 1; oz++) {
                        IntArrayList neighborBucket = spatialBuckets.get(
                                BlockPos.asLong(cx + ox, cy + oy, cz + oz)
                        );
                        if (neighborBucket == null) continue;

                        for (int k = 0, bs = neighborBucket.size(); k < bs; k++) {
                            int j = neighborBucket.getInt(k);
                            if (j <= i) continue; // symetria par (i, j)
                            long q = orePositions[j];
                            int dx = bx - BlockPos.getX(q);
                            int dy = by - BlockPos.getY(q);
                            int dz = bz - BlockPos.getZ(q);
                            if (dx * dx + dy * dy + dz * dz <= CLUSTER_DIST_SQ_THRESHOLD) {
                                ufUnion(i, j);
                            }
                        }
                    }
                }
            }
        }

        // 3. Materializacja obiektów OreCluster z puli
        int clusterCount = 0;
        for (int i = 0; i < count; i++) {
            int root = ufFind(i);
            int idx = rootToClusterIdx[root];
            if (idx == -1) {
                if (clusterCount >= clusterPool.size()) {
                    clusterPool.add(new OreCluster());
                }
                idx = clusterCount++;
                rootToClusterIdx[root] = idx;
                clusterPool.get(idx).reset();
            }
            clusterPool.get(idx).addBlock(orePositions[i]);
        }

        for (int i = 0; i < clusterCount; i++) {
            clusterPool.get(i).finalizeGeometry();
        }

        // Jeśli klastrów jest więcej niż MAX_CLUSTERS_FOR_TSP, bierzemy najbliższe graczowi
        List<OreCluster> candidates = new ArrayList<>(Math.min(clusterCount, MAX_CLUSTERS_FOR_TSP));
        if (clusterCount > MAX_CLUSTERS_FOR_TSP) {
            Integer[] order = new Integer[clusterCount];
            for (int i = 0; i < clusterCount; i++) order[i] = i;
            Arrays.sort(order, (a, b) -> Double.compare(
                    estimateTravelTicks(px, py, pz, clusterPool.get(a)),
                    estimateTravelTicks(px, py, pz, clusterPool.get(b))
            ));
            for (int i = 0; i < MAX_CLUSTERS_FOR_TSP; i++) {
                candidates.add(clusterPool.get(order[i]));
            }
        } else {
            for (int i = 0; i < clusterCount; i++) {
                candidates.add(clusterPool.get(i));
            }
        }

        // 4. Rozwiązanie otwartego TSP: Nearest-Insertion + 2-Opt
        solveOpenTsp2Opt(px, py, pz, candidates);

        // 5. Wyznaczenie optymalnego punktu Stand-Off dla pierwszego klastra (lazy dla reszty)
        if (!activeRoute.isEmpty()) {
            OreCluster primary = activeRoute.get(0);
            committedClusterAnchor = primary.anchorBlockLong;
            computeBestStandOffSpot(primary, player, level);
        } else {
            committedClusterAnchor = Long.MIN_VALUE;
        }

        return activeRoute;
    }

    /**
     * Otwarty TSP (start = pozycja gracza, węzły 1..K = klastry).
     * Węzeł 0 jest zakotwiczony na pozycji gracza.
     */
    private void solveOpenTsp2Opt(double px, double py, double pz, List<OreCluster> clusters) {
        int k = clusters.size();
        if (k == 0) return;
        if (k == 1) {
            activeRoute.add(clusters.get(0));
            return;
        }

        int n = k + 1; // indeks 0 = gracz, 1..k = klastry

        // Wypełnienie macierzy kosztów w tickach
        costMatrix[0][0] = 0.0;
        for (int i = 0; i < k; i++) {
            OreCluster ci = clusters.get(i);
            double costFromPlayer = estimateTravelTicks(px, py, pz, ci);
            // Premia histerezy dla aktualnie eksplorowanego klastra (eliminuje ping-pong)
            if (ci.anchorBlockLong == committedClusterAnchor) {
                costFromPlayer = Math.max(0.0, costFromPlayer - ACTIVE_CLUSTER_COMMIT_BONUS);
            }
            // Drobna premia za gęstość żyły (duża żyła ma wyższą stopę zwrotu)
            costFromPlayer -= Math.min(ci.blocks.size() * 2.5D, 15.0D);

            costMatrix[0][i + 1] = costFromPlayer;
            costMatrix[i + 1][0] = 0.0; // Otwarty TSP: brak kosztu powrotu na start
        }

        for (int i = 0; i < k; i++) {
            OreCluster ci = clusters.get(i);
            costMatrix[i + 1][i + 1] = 0.0;
            for (int j = i + 1; j < k; j++) {
                OreCluster cj = clusters.get(j);
                double c = estimateClusterToClusterTicks(ci, cj);
                costMatrix[i + 1][j + 1] = c;
                costMatrix[j + 1][i + 1] = c;
            }
        }

        // Faza 1: Nearest-Insertion (lepszy start niż chciwy Nearest-Neighbor)
        Arrays.fill(visited, 0, n, false);
        tourOrder[0] = 0;
        visited[0] = true;
        int tourLen = 1;

        // Najbliższy pierwszy klaster od gracza
        int firstNode = 1;
        double bestFirst = Double.MAX_VALUE;
        for (int i = 1; i < n; i++) {
            if (costMatrix[0][i] < bestFirst) {
                bestFirst = costMatrix[0][i];
                firstNode = i;
            }
        }
        tourOrder[tourLen++] = firstNode;
        visited[firstNode] = true;

        while (tourLen < n) {
            int bestCandidate = -1;
            int bestInsertPos = tourLen;
            double bestDelta = Double.MAX_VALUE;

            for (int cand = 1; cand < n; cand++) {
                if (visited[cand]) continue;

                // Testujemy wstawienie na każdą pozycję 1..tourLen (0 jest zablokowane jako gracz)
                for (int pos = 1; pos <= tourLen; pos++) {
                    int prev = tourOrder[pos - 1];
                    double delta;
                    if (pos == tourLen) {
                        delta = costMatrix[prev][cand];
                    } else {
                        int next = tourOrder[pos];
                        delta = costMatrix[prev][cand] + costMatrix[cand][next] - costMatrix[prev][next];
                    }
                    if (delta < bestDelta) {
                        bestDelta = delta;
                        bestCandidate = cand;
                        bestInsertPos = pos;
                    }
                }
            }

            if (bestCandidate == -1) break;
            System.arraycopy(tourOrder, bestInsertPos, tourOrder, bestInsertPos + 1, tourLen - bestInsertPos);
            tourOrder[bestInsertPos] = bestCandidate;
            visited[bestCandidate] = true;
            tourLen++;
        }

        // Faza 2: 2-Opt Local Search (odwracanie podścieżek w celu rozplątania krawędzi)
        boolean improved = true;
        int iter = 0;
        while (improved && iter < MAX_2OPT_ITERATIONS) {
            improved = false;
            iter++;
            for (int i = 1; i < n - 1; i++) {
                int a = tourOrder[i - 1];
                int b = tourOrder[i];
                for (int j = i + 1; j < n; j++) {
                    int c = tourOrder[j];
                    double oldEdge = costMatrix[a][b];
                    double newEdge = costMatrix[a][c];

                    if (j + 1 < n) {
                        int d = tourOrder[j + 1];
                        oldEdge += costMatrix[c][d];
                        newEdge += costMatrix[b][d];
                    }

                    if (newEdge + 1e-4 < oldEdge) {
                        reverseSegment(tourOrder, i, j);
                        improved = true;
                        break;
                    }
                }
                if (improved) break;
            }
        }

        for (int i = 1; i < n; i++) {
            activeRoute.add(clusters.get(tourOrder[i] - 1));
        }
    }

    /**
     * Stand-Off Hull Solver:
     * Znajduje bezpieczny blok stania wokół klastra, z którego można ściąć maksymalną liczbę rud
     * bez ruszania się z miejsca (w bezpiecznym reachu 3.95D i z czystym LOS).
     */
    public void computeBestStandOffSpot(OreCluster cluster, LocalPlayer player, Level level) {
        cluster.reachableFromStandPos.clear();
        cluster.hasValidStandSpot = false;

        int searchMinX = cluster.minX - 4;
        int searchMaxX = cluster.maxX + 4;
        int searchMinY = Math.max(level.getMinY() + 1, cluster.minY - 3);
        int searchMaxY = Math.min(level.getMaxY() - 2, cluster.maxY + 2);
        int searchMinZ = cluster.minZ - 4;
        int searchMaxZ = cluster.maxZ + 4;

        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();

        double bestScore = -Double.MAX_VALUE;
        long bestPosLong = Long.MIN_VALUE;
        LongArrayList tempReachable = new LongArrayList(cluster.blocks.size());

        // Najpierw sprawdzamy bieżącą pozycję gracza – jeśli już sięga rudy, zero ruchu!
        int curX = Mth.floor(px);
        int curY = Mth.floor(py);
        int curZ = Mth.floor(pz);
        double currentScore = evaluateStandCandidate(
                curX, curY, curZ, px, py, pz, cluster, player, level, tempReachable, true
        );
        if (currentScore > -Double.MAX_VALUE) {
            bestScore = currentScore + 18.0D; // Duży bonus za brak potrzeby przemieszczania się
            bestPosLong = BlockPos.asLong(curX, curY, curZ);
            cluster.reachableFromStandPos.addAll(tempReachable);
            cluster.hasValidStandSpot = true;
        }

        // Skanowanie otoczki (Hull) klastra
        for (int sx = searchMinX; sx <= searchMaxX; sx++) {
            for (int sy = searchMinY; sy <= searchMaxY; sy++) {
                for (int sz = searchMinZ; sz <= searchMaxZ; sz++) {
                    if (sx == curX && sy == curY && sz == curZ) continue;

                    // Szybkie odrzucenie przez dystans od centroidu klastra
                    double dcx = (sx + 0.5) - cluster.centroidX;
                    double dcy = (sy + PLAYER_EYE_HEIGHT) - cluster.centroidY;
                    double dcz = (sz + 0.5) - cluster.centroidZ;
                    if (dcx * dcx + dcy * dcy + dcz * dcz > 36.0D) continue;

                    double score = evaluateStandCandidate(
                            sx, sy, sz, px, py, pz, cluster, player, level, tempReachable, false
                    );
                    if (score > bestScore) {
                        bestScore = score;
                        bestPosLong = BlockPos.asLong(sx, sy, sz);
                        cluster.reachableFromStandPos.clear();
                        cluster.reachableFromStandPos.addAll(tempReachable);
                        cluster.hasValidStandSpot = true;
                    }
                }
            }
        }

        cluster.bestStandPosLong = bestPosLong;

        // Sortowanie rud dostępnych z wybranego punktu wg minimalnego obrotu kamery
        if (cluster.hasValidStandSpot && cluster.reachableFromStandPos.size() > 1) {
            sortByAngularDelta(cluster.reachableFromStandPos, player);
        }
    }

    private double evaluateStandCandidate(
            int sx, int sy, int sz,
            double px, double py, double pz,
            OreCluster cluster, LocalPlayer player, Level level,
            LongArrayList outReachable, boolean isCurrentPos
    ) {
        outReachable.clear();

        if (!isCurrentPos && !isSafeStandable(level, sx, sy, sz)) {
            return -Double.MAX_VALUE;
        }

        double eyeX = sx + 0.5;
        double eyeY = sy + PLAYER_EYE_HEIGHT;
        double eyeZ = sz + 0.5;
        Vec3 eyeVec = new Vec3(eyeX, eyeY, eyeZ);

        int directLosHits = 0;
        int peelableHits = 0;

        for (int i = 0, n = cluster.blocks.size(); i < n; i++) {
            long oreLong = cluster.blocks.getLong(i);
            int ox = BlockPos.getX(oreLong);
            int oy = BlockPos.getY(oreLong);
            int oz = BlockPos.getZ(oreLong);

            // Najbliższy punkt na AABB bloku rudy (identycznie jak liczy GrimAC Reach)
            double cx = Mth.clamp(eyeX, ox + 0.05, ox + 0.95);
            double cy = Mth.clamp(eyeY, oy + 0.05, oy + 0.95);
            double cz = Mth.clamp(eyeZ, oz + 0.05, oz + 0.95);

            double distSq = (cx - eyeX) * (cx - eyeX)
                    + (cy - eyeY) * (cy - eyeY)
                    + (cz - eyeZ) * (cz - eyeZ);

            if (distSq > SAFE_REACH_SQ) continue;

            // Weryfikacja Line-of-Sight
            BlockHitResult hit = level.clip(new ClipContext(
                    eyeVec,
                    new Vec3(cx, cy, cz),
                    ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE,
                    player
            ));

            if (hit.getType() == HitResult.Type.BLOCK
                    && hit.getBlockPos().getX() == ox
                    && hit.getBlockPos().getY() == oy
                    && hit.getBlockPos().getZ() == oz) {
                directLosHits++;
                outReachable.add(oreLong);
            } else if (distSq <= 9.0D) {
                // Ruda schowana za innym blokiem tej samej żyły (odsłoni się po 1. uderzeniu)
                long blocker = hit.getBlockPos().asLong();
                if (cluster.contains(blocker)) {
                    peelableHits++;
                    outReachable.add(oreLong);
                }
            }
        }

        if (directLosHits == 0 && peelableHits == 0) {
            return -Double.MAX_VALUE;
        }

        // Ocena punktowa: maksymalizuj liczbę rud z 1 miejsca, minimalizuj dobieg od gracza
        double distFromPlayer = Math.sqrt(
                (sx + 0.5 - px) * (sx + 0.5 - px) + (sz + 0.5 - pz) * (sz + 0.5 - pz)
        );
        double dyPenalty = Math.abs(sy - py) * 3.5D;

        return (directLosHits * 25.0D) + (peelableHits * 14.0D) - (distFromPlayer * 2.8D) - dyPenalty;
    }

    private boolean isSafeStandable(Level level, int x, int y, int z) {
        // Podłoga musi być pełna i stabilna
        standCheckPos.set(x, y - 1, z);
        BlockState floor = level.getBlockState(standCheckPos);
        if (!floor.isSolid() || floor.getFluidState().is(Fluids.LAVA)) return false;

        // Nogi i głowa muszą być puste i bez lawy
        standCheckPos.set(x, y, z);
        BlockState feet = level.getBlockState(standCheckPos);
        if (!feet.getCollisionShape(level, standCheckPos).isEmpty()
                || !feet.getFluidState().isEmpty()) {
            return false;
        }

        standCheckPos.set(x, y + 1, z);
        BlockState head = level.getBlockState(standCheckPos);
        if (!head.getCollisionShape(level, standCheckPos).isEmpty()
                || !head.getFluidState().isEmpty()) {
            return false;
        }

        // Szybki check zagrożeń bocznych (lawa w promieniu 1 bloku)
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            standCheckPos.set(x + dir.getStepX(), y, z + dir.getStepZ());
            if (level.getFluidState(standCheckPos).is(Fluids.LAVA)) return false;
        }
        return true;
    }

    /**
     * Układa bloki rudy w zasięgu ręki wg najmniejszego łącznego obrotu kamery (Insertion Sort).
     */
    private void sortByAngularDelta(LongArrayList ores, LocalPlayer player) {
        float curYaw = player.getYRot();
        float curPitch = player.getXRot();
        Vec3 eye = player.getEyePosition(1.0F);

        int n = ores.size();
        for (int i = 1; i < n; i++) {
            long key = ores.getLong(i);
            float keyCost = angularCost(eye, curYaw, curPitch, key);
            int j = i - 1;
            while (j >= 0 && angularCost(eye, curYaw, curPitch, ores.getLong(j)) > keyCost) {
                ores.set(j + 1, ores.getLong(j));
                j--;
            }
            ores.set(j + 1, key);
        }
    }

    private float angularCost(Vec3 eye, float yaw, float pitch, long packedPos) {
        double dx = (BlockPos.getX(packedPos) + 0.5) - eye.x;
        double dy = (BlockPos.getY(packedPos) + 0.5) - eye.y;
        double dz = (BlockPos.getZ(packedPos) + 0.5) - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float targetYaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        float targetPitch = (float) (-(Mth.atan2(dy, horiz) * (180.0 / Math.PI)));
        float dYaw = Math.abs(Mth.wrapDegrees(targetYaw - yaw));
        float dPitch = Math.abs(targetPitch - pitch);
        return dYaw + 0.6F * dPitch;
    }

    private double estimateTravelTicks(double px, double py, double pz, OreCluster c) {
        double dx = c.centroidX - px;
        double dz = c.centroidZ - pz;
        double dy = Math.abs(c.centroidY - py);
        return (Math.sqrt(dx * dx + dz * dz) / SPRINT_BLOCKS_PER_TICK) + (dy * VERTICAL_PENALTY_PER_BLOCK);
    }

    private double estimateClusterToClusterTicks(OreCluster a, OreCluster b) {
        double dx = a.centroidX - b.centroidX;
        double dz = a.centroidZ - b.centroidZ;
        double dy = Math.abs(a.centroidY - b.centroidY);
        return (Math.sqrt(dx * dx + dz * dz) / SPRINT_BLOCKS_PER_TICK) + (dy * VERTICAL_PENALTY_PER_BLOCK);
    }

    private static void reverseSegment(int[] arr, int left, int right) {
        while (left < right) {
            int tmp = arr[left];
            arr[left] = arr[right];
            arr[right] = tmp;
            left++;
            right--;
        }
    }

    private int ufFind(int x) {
        while (ufParent[x] != x) {
            ufParent[x] = ufParent[ufParent[x]]; // Path halving
            x = ufParent[x];
        }
        return x;
    }

    private void ufUnion(int a, int b) {
        int ra = ufFind(a);
        int rb = ufFind(b);
        if (ra == rb) return;
        if (ufRank[ra] < ufRank[rb]) {
            ufParent[ra] = rb;
        } else if (ufRank[ra] > ufRank[rb]) {
            ufParent[rb] = ra;
        } else {
            ufParent[rb] = ra;
            ufRank[ra]++;
        }
    }

    private IntArrayList borrowBucket() {
        if (bucketPoolCursor >= bucketPool.size()) {
            bucketPool.add(new IntArrayList(8));
        }
        IntArrayList list = bucketPool.get(bucketPoolCursor++);
        list.clear();
        return list;
    }

    private void ensureCapacity(int required) {
        if (required <= capacity) return;
        capacity = Math.max(required, capacity << 1);
        orePositions = new long[capacity];
        ufParent = new int[capacity];
        ufRank = new int[capacity];
        rootToClusterIdx = new int[capacity];
    }

    public void notifyClusterCleared(long anchorLong) {
        if (committedClusterAnchor == anchorLong) {
            committedClusterAnchor = Long.MIN_VALUE;
        }
    }
}

