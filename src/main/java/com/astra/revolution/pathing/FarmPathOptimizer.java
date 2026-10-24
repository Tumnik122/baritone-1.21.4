package com.astra.revolution.pathing;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Silnik wyznaczania tras dla procesu #farm (#praca).
 * Łączy dekompozycję Boustrophedon (ciągły korytarzowy sweeping) z algorytmem
 * Angular-Penalty Nearest-Neighbor + 2-Opt TSP oraz walidacją Raycast Line-of-Sight.
 */
public final class FarmPathOptimizer {

    // Parametry fizyki i zasięgu GrimAC-safe
    public static final double EFFECTIVE_REACH_MIN = 0.65;
    public static final double EFFECTIVE_REACH_MAX = 4.05;
    public static final double CORRIDOR_STEP_WIDTH = 3.5; // Pokrywa 4 rzędy podczas jednego przelotu
    public static final double WAYPOINT_SPACING = 2.0;
    public static final double MAX_SPRINT_HARVEST_ANGLE_DEG = 52.0;

    // Parametry heurystyki TSP oraz kwalifikacji kształtu pola
    private static final double REGULAR_FIELD_FILL_THRESHOLD = 0.65;
    private static final double ANGULAR_PENALTY_WEIGHT = 2.35; // Waga w_theta dla kary kątowej (w blokach na radian)
    private static final int MAX_2OPT_ITERATIONS = 40;

    public enum FieldTopology {
        REGULAR_BOUSTROPHEDON,
        IRREGULAR_ANGULAR_TSP
    }

    public record HarvestTarget(
            BlockPos pos,
            Vec3 hitVec,
            Direction hitFace,
            TargetAction action,
            float requiredYaw,
            float requiredPitch,
            double score
    ) {}

    public enum TargetAction {
        HARVEST_MATURE,
        PLANT_SEED
    }

    public record OptimizedFarmRoute(
            List<BlockPos> spineWaypoints,
            FieldTopology topology,
            int currentWaypointIndex,
            float initialYaw
    ) {
        public boolean isEmpty() {
            return spineWaypoints == null || spineWaypoints.isEmpty();
        }
    }

    /**
     * Główny punkt wejścia budujący zoptymalizowaną trasę po wykrytych blokach pola.
     *
     * @param fieldBlocks Lista wszystkich bloków należących do farmy (Farmland + dojrzałe plony)
     * @param playerPos   Aktualna pozycja gracza
     * @param currentYaw  Aktualny kąt patrzenia gracza (Yaw)
     * @return Zoptymalizowana trasa złożona z punktów osiowych (spine waypoints)
     */
    public OptimizedFarmRoute buildOptimalRoute(List<BlockPos> fieldBlocks, Vec3 playerPos, float currentYaw) {
        if (fieldBlocks == null || fieldBlocks.isEmpty()) {
            return new OptimizedFarmRoute(Collections.emptyList(), FieldTopology.IRREGULAR_ANGULAR_TSP, 0, currentYaw);
        }

        // Grupowanie po dominującej warstwie Y (obsługa wielopoziomowych farm)
        int dominantY = findDominantYLevel(fieldBlocks, Mth.floor(playerPos.y));
        List<BlockPos> layerBlocks = fieldBlocks.stream()
                .filter(p -> Math.abs(p.getY() - dominantY) <= 1)
                .toList();

        if (layerBlocks.isEmpty()) {
            layerBlocks = fieldBlocks;
        }

        FieldBounds bounds = computeBounds(layerBlocks);
        if (bounds.width() >= 3 || bounds.length() >= 3) {
            List<BlockPos> serpentineSpine = generateNonEmptyBoustrophedonCorridors(layerBlocks, bounds, dominantY, playerPos);
            if (!serpentineSpine.isEmpty()) {
                float startYaw = computeInitialYaw(playerPos, serpentineSpine);
                return new OptimizedFarmRoute(serpentineSpine, FieldTopology.REGULAR_BOUSTROPHEDON, 0, startYaw);
            }
        }
        List<BlockPos> reducedClusters = downsampleToReachClusters(layerBlocks, 2.2);
        List<BlockPos> tspSpine = generateAngularTspRoute(reducedClusters, playerPos, currentYaw);
        float startYaw = computeInitialYaw(playerPos, tspSpine);
        return new OptimizedFarmRoute(tspSpine, FieldTopology.IRREGULAR_ANGULAR_TSP, 0, startYaw);
    }

    /**
     * Generuje korytarze Boustrophedon o szerokości 3.5m dla niepustych alejek (Argon 4).
     * Zapobiega generowaniu pustych przelotów i likwiduje problem braku ruchu na dużych polach.
     */
    private List<BlockPos> generateNonEmptyBoustrophedonCorridors(List<BlockPos> layerBlocks, FieldBounds bounds, int yLevel, Vec3 playerPos) {
        List<BlockPos> waypoints = new ArrayList<>();
        if (layerBlocks == null || layerBlocks.isEmpty()) {
            return waypoints;
        }

        boolean sweepAlongX = bounds.width() >= bounds.length();
        int secondaryMin = sweepAlongX ? bounds.minZ() : bounds.minX();

        // Grupujemy bloki w pasy (lanes) o szerokości CORRIDOR_STEP_WIDTH (3.5 bloku)
        java.util.TreeMap<Integer, int[]> laneRanges = new java.util.TreeMap<>(); // bin -> [minPrim, maxPrim, sumSec, count]

        for (BlockPos p : layerBlocks) {
            int prim = sweepAlongX ? p.getX() : p.getZ();
            int sec = sweepAlongX ? p.getZ() : p.getX();
            int bin = (int) Math.floor((sec - secondaryMin) / CORRIDOR_STEP_WIDTH);

            int[] r = laneRanges.computeIfAbsent(bin, k -> new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE, 0, 0});
            if (prim < r[0]) r[0] = prim;
            if (prim > r[1]) r[1] = prim;
            r[2] += sec;
            r[3]++;
        }

        if (laneRanges.isEmpty()) {
            return waypoints;
        }

        double playerSec = sweepAlongX ? playerPos.z : playerPos.x;
        int firstBin = laneRanges.firstKey();
        int lastBin = laneRanges.lastKey();
        double distToFirst = Math.abs(playerSec - (secondaryMin + firstBin * CORRIDOR_STEP_WIDTH));
        double distToLast = Math.abs(playerSec - (secondaryMin + lastBin * CORRIDOR_STEP_WIDTH));
        boolean ascendingLanes = distToFirst <= distToLast;

        List<Integer> orderedBins = new ArrayList<>(laneRanges.keySet());
        if (!ascendingLanes) {
            Collections.reverse(orderedBins);
        }

        double playerPrim = sweepAlongX ? playerPos.x : playerPos.z;
        int initialMinPrim = laneRanges.get(orderedBins.get(0))[0];
        int initialMaxPrim = laneRanges.get(orderedBins.get(0))[1];
        boolean forwardSweep = Math.abs(playerPrim - initialMinPrim) <= Math.abs(playerPrim - initialMaxPrim);

        BlockPos lastExit = null;

        for (int bin : orderedBins) {
            int[] r = laneRanges.get(bin);
            int minP = r[0];
            int maxP = r[1];
            int avgSec = r[3] > 0 ? (int) Math.round((double) r[2] / r[3]) : (int) (secondaryMin + bin * CORRIDOR_STEP_WIDTH + 1.5);

            int startPrim = forwardSweep ? minP : maxP;
            int endPrim = forwardSweep ? maxP : minP;
            int stepPrim = forwardSweep ? 2 : -2;

            // Połączenie łukiem z poprzedniej alejki
            if (lastExit != null) {
                int midSec = (lastExit.getZ() + avgSec) / 2;
                if (sweepAlongX) {
                    waypoints.add(new BlockPos(lastExit.getX(), yLevel, midSec));
                } else {
                    waypoints.add(new BlockPos(midSec, yLevel, lastExit.getZ()));
                }
            }

            // Punkty wzdłuż korytarza
            for (int prim = startPrim; forwardSweep ? (prim <= endPrim) : (prim >= endPrim); prim += stepPrim) {
                BlockPos wp = sweepAlongX
                        ? new BlockPos(prim, yLevel, avgSec)
                        : new BlockPos(avgSec, yLevel, prim);
                if (waypoints.isEmpty() || !waypoints.get(waypoints.size() - 1).equals(wp)) {
                    waypoints.add(wp);
                }
            }

            BlockPos laneEnd = sweepAlongX
                    ? new BlockPos(endPrim, yLevel, avgSec)
                    : new BlockPos(avgSec, yLevel, endPrim);
            if (waypoints.isEmpty() || !waypoints.get(waypoints.size() - 1).equals(laneEnd)) {
                waypoints.add(laneEnd);
            }

            lastExit = laneEnd;
            forwardSweep = !forwardSweep; // Serpentine nawrót dla kolejnej alejki
        }

        return waypoints;
    }

    /**
     * Generuje ciągły wzór węża (Boustrophedon) z wygładzonymi nawrotami na końcach korytarzy.
     * Orientuje korytarze wzdłuż dłuższej osi pola, aby zminimalizować liczbę nawrotów o 180 stopni.
     */
    private List<BlockPos> generateBoustrophedonCorridors(FieldBounds bounds, int yLevel, Vec3 playerPos) {
        List<BlockPos> waypoints = new ArrayList<>();
        boolean sweepAlongX = bounds.width() >= bounds.length();

        int minX = bounds.minX();
        int maxX = bounds.maxX();
        int minZ = bounds.minZ();
        int maxZ = bounds.maxZ();

        // Ustalenie narożnika startowego najbliższego graczowi
        boolean startFromMinPrimary = sweepAlongX
                ? Math.abs(playerPos.x - minX) <= Math.abs(playerPos.x - maxX)
                : Math.abs(playerPos.z - minZ) <= Math.abs(playerPos.z - maxZ);

        boolean startFromMinSecondary = sweepAlongX
                ? Math.abs(playerPos.z - minZ) <= Math.abs(playerPos.z - maxZ)
                : Math.abs(playerPos.x - minX) <= Math.abs(playerPos.x - maxX);

        int secondaryMin = sweepAlongX ? minZ : minX;
        int secondaryMax = sweepAlongX ? maxZ : maxX;
        int primaryMin = sweepAlongX ? minX : minZ;
        int primaryMax = sweepAlongX ? maxX : maxZ;

        // Odstęp od krawędzi pola = 1.5 kratki (gracz idzie wewnątrz pola i sięga do krawędzi)
        double currentSecondary = startFromMinSecondary
                ? Math.min(secondaryMax, secondaryMin + 1.5)
                : Math.max(secondaryMin, secondaryMax - 1.5);

        double secondaryDirection = startFromMinSecondary ? 1.0 : -1.0;
        boolean forwardSweep = startFromMinPrimary;

        while (true) {
            int secCoord = Mth.floor(currentSecondary);
            int startPrim = forwardSweep ? primaryMin + 1 : primaryMax - 1;
            int endPrim = forwardSweep ? primaryMax - 1 : primaryMin + 1;
            int stepPrim = forwardSweep ? 2 : -2;

            if (primaryMax - primaryMin < 2) {
                startPrim = primaryMin;
                endPrim = primaryMax;
            }

            // Punkty wzdłuż prostego odcinka korytarza
            for (int prim = startPrim; forwardSweep ? (prim <= endPrim) : (prim >= endPrim); prim += stepPrim) {
                BlockPos wp = sweepAlongX
                        ? new BlockPos(prim, yLevel, secCoord)
                        : new BlockPos(secCoord, yLevel, prim);
                if (waypoints.isEmpty() || !waypoints.getLast().equals(wp)) {
                    waypoints.add(wp);
                }
            }

            // Punkt końcowy obecnego rzędu
            BlockPos rowEnd = sweepAlongX
                    ? new BlockPos(endPrim, yLevel, secCoord)
                    : new BlockPos(secCoord, yLevel, endPrim);
            if (waypoints.isEmpty() || !waypoints.getLast().equals(rowEnd)) {
                waypoints.add(rowEnd);
            }

            // Przejście do kolejnego korytarza
            double nextSecondary = currentSecondary + (secondaryDirection * CORRIDOR_STEP_WIDTH);
            boolean outOfBounds = secondaryDirection > 0
                    ? nextSecondary > secondaryMax
                    : nextSecondary < secondaryMin;

            if (outOfBounds) {
                // Jeśli została wąska krawędź na końcu pola, dodaj ostatni przelot wyrównujący
                double distToEdge = secondaryDirection > 0
                        ? (secondaryMax - currentSecondary)
                        : (currentSecondary - secondaryMin);
                if (distToEdge > 1.8) {
                    nextSecondary = secondaryDirection > 0 ? secondaryMax - 0.5 : secondaryMin + 0.5;
                } else {
                    break;
                }
            }

            // Dodanie punktu pośredniego łuku (Smooth Turn Chamfer) zapobiegającego szarpnięciu o 90/180 stopni
            int midSecondary = Mth.floor((currentSecondary + nextSecondary) * 0.5);
            int turnApexPrim = forwardSweep
                    ? Math.min(primaryMax, endPrim + 1)
                    : Math.max(primaryMin, endPrim - 1);

            BlockPos turnApex = sweepAlongX
                    ? new BlockPos(turnApexPrim, yLevel, midSecondary)
                    : new BlockPos(midSecondary, yLevel, turnApexPrim);
            waypoints.add(turnApex);

            currentSecondary = nextSecondary;
            forwardSweep = !forwardSweep;
        }

        return waypoints;
    }

    /**
     * Wyznacza trasę dla pól o nieregularnym kształcie za pomocą zachłannego algorytmu
     * Nearest-Neighbor z karą kątową: Cost(A -> B) = Dist(A, B) + w * |DeltaTheta(A, B)|,
     * a następnie wygładza przecięcia algorytmem 2-Opt.
     */
    private List<BlockPos> generateAngularTspRoute(List<BlockPos> nodes, Vec3 startPos, float startYawDeg) {
        if (nodes.size() <= 2) {
            return new ArrayList<>(nodes);
        }

        List<BlockPos> unvisited = new ArrayList<>(nodes);
        List<BlockPos> tour = new ArrayList<>(nodes.size());

        Vec3 currentPos = startPos;
        double radYaw = Math.toRadians(startYawDeg);
        Vec3 currentHeading = new Vec3(-Math.sin(radYaw), 0.0, Math.cos(radYaw)).normalize();

        while (!unvisited.isEmpty()) {
            BlockPos bestNext = null;
            double bestCost = Double.MAX_VALUE;
            Vec3 bestHeading = currentHeading;

            for (BlockPos candidate : unvisited) {
                Vec3 candVec = Vec3.atBottomCenterOf(candidate);
                Vec3 delta = candVec.subtract(currentPos);
                double horizontalDist = Math.hypot(delta.x, delta.z);

                if (horizontalDist < 1e-4) {
                    bestNext = candidate;
                    bestCost = 0.0;
                    break;
                }

                Vec3 dirToCandidate = new Vec3(delta.x / horizontalDist, 0.0, delta.z / horizontalDist);
                double dot = Mth.clamp(currentHeading.dot(dirToCandidate), -1.0, 1.0);
                double turnAngleRad = Math.acos(dot); // [0, PI]

                double cost = horizontalDist + (ANGULAR_PENALTY_WEIGHT * turnAngleRad);
                if (cost < bestCost) {
                    bestCost = cost;
                    bestNext = candidate;
                    bestHeading = dirToCandidate;
                }
            }

            if (bestNext != null) {
                tour.add(bestNext);
                unvisited.remove(bestNext);
                currentPos = Vec3.atBottomCenterOf(bestNext);
                currentHeading = bestHeading;
            } else {
                break;
            }
        }

        return optimizeTourWithAngular2Opt(tour, startPos, startYawDeg);
    }

    /**
     * Lokalna optymalizacja 2-Opt z zachowaniem funkcji celu uwzględniającej karę kątową.
     */
    private List<BlockPos> optimizeTourWithAngular2Opt(List<BlockPos> initialTour, Vec3 startPos, float startYawDeg) {
        int n = initialTour.size();
        if (n < 4) {
            return initialTour;
        }

        List<BlockPos> route = new ArrayList<>(initialTour);
        boolean improved = true;
        int iteration = 0;

        while (improved && iteration < MAX_2OPT_ITERATIONS) {
            improved = false;
            iteration++;
            double currentTotalCost = evaluateTotalRouteCost(route, startPos, startYawDeg);

            for (int i = 0; i < n - 2; i++) {
                for (int k = i + 2; k < Math.min(n, i + 14); k++) {
                    reverseSegment(route, i + 1, k);
                    double candidateCost = evaluateTotalRouteCost(route, startPos, startYawDeg);
                    if (candidateCost + 0.15 < currentTotalCost) {
                        currentTotalCost = candidateCost;
                        improved = true;
                    } else {
                        // Cofnij odwrócenie, jeśli koszt wzrósł
                        reverseSegment(route, i + 1, k);
                    }
                }
            }
        }
        return route;
    }

    private double evaluateTotalRouteCost(List<BlockPos> route, Vec3 startPos, float startYawDeg) {
        double totalCost = 0.0;
        Vec3 prevPos = startPos;
        double radYaw = Math.toRadians(startYawDeg);
        Vec3 heading = new Vec3(-Math.sin(radYaw), 0.0, Math.cos(radYaw)).normalize();

        for (BlockPos pos : route) {
            Vec3 nextPos = Vec3.atBottomCenterOf(pos);
            Vec3 delta = nextPos.subtract(prevPos);
            double dist = Math.hypot(delta.x, delta.z);
            if (dist > 1e-4) {
                Vec3 nextHeading = new Vec3(delta.x / dist, 0.0, delta.z / dist);
                double dot = Mth.clamp(heading.dot(nextHeading), -1.0, 1.0);
                double angle = Math.acos(dot);
                totalCost += dist + (ANGULAR_PENALTY_WEIGHT * angle);
                heading = nextHeading;
            }
            prevPos = nextPos;
        }
        return totalCost;
    }

    private void reverseSegment(List<BlockPos> list, int left, int right) {
        while (left < right) {
            BlockPos tmp = list.get(left);
            list.set(left, list.get(right));
            list.set(right, tmp);
            left++;
            right--;
        }
    }

    /**
     * Redukuje zagęszczoną chmurę bloków do środków klastrów w promieniu zasięgu,
     * aby gracz nie musiał wchodzić na każdy pojedynczy blok pola.
     */
    private List<BlockPos> downsampleToReachClusters(List<BlockPos> blocks, double clusterRadius) {
        List<BlockPos> sorted = new ArrayList<>(blocks);
        sorted.sort((a, b) -> {
            int cx = Integer.compare(a.getX(), b.getX());
            return cx != 0 ? cx : Integer.compare(a.getZ(), b.getZ());
        });

        List<BlockPos> centroids = new ArrayList<>();
        Set<BlockPos> covered = new HashSet<>();
        double radiusSq = clusterRadius * clusterRadius;

        for (BlockPos pivot : sorted) {
            if (covered.contains(pivot)) {
                continue;
            }
            centroids.add(pivot);
            for (BlockPos other : sorted) {
                if (!covered.contains(other) && pivot.distSqr(other) <= radiusSq) {
                    covered.add(other);
                }
            }
        }
        return centroids;
    }

    /**
     * Skanuje otoczenie gracza w promieniu blockReachDistance (3.9 - 4.05 blk) podczas biegu.
     * Wykorzystuje pełny Raycast Line-of-Sight oraz filtr stożka kierunku ruchu (GrimAC Sprint Guard).
     */
    public Optional<HarvestTarget> findBestInFlightTarget(
            LocalPlayer player,
            Level level,
            float movementYawDeg,
            boolean allowReplant,
            Set<BlockPos> recentlyInteracted
    ) {
        Vec3 eyePos = player.getEyePosition(1.0F);
        double moveYawRad = Math.toRadians(movementYawDeg);
        Vec3 forwardVec = new Vec3(-Math.sin(moveYawRad), 0.0, Math.cos(moveYawRad)).normalize();

        BlockPos playerBlock = player.blockPosition();
        int searchRadius = 4;

        HarvestTarget bestTarget = null;
        double bestScore = Double.MAX_VALUE;

        for (int dx = -searchRadius; dx <= searchRadius; dx++) {
            for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos pos = playerBlock.offset(dx, dy, dz);
                    if (recentlyInteracted.contains(pos)) {
                        continue;
                    }

                    BlockState state = level.getBlockState(pos);
                    TargetAction action = classifyBlockAction(level, pos, state, allowReplant);
                    if (action == null) {
                        continue;
                    }

                    Vec3 targetCenter = action == TargetAction.HARVEST_MATURE
                            ? new Vec3(pos.getX() + 0.5, pos.getY() + 0.35, pos.getZ() + 0.5)
                            : new Vec3(pos.getX() + 0.5, pos.getY() + 0.9375, pos.getZ() + 0.5); // Górna ściana Farmland (15/16 blk)

                    double dist = eyePos.distanceTo(targetCenter);
                    if (dist < EFFECTIVE_REACH_MIN || dist > EFFECTIVE_REACH_MAX) {
                        continue;
                    }

                    // Weryfikacja stożka sprintu (GrimAC nie pozwala sprintować i celować za plecy)
                    Vec3 toTargetHoriz = new Vec3(targetCenter.x - eyePos.x, 0.0, targetCenter.z - eyePos.z);
                    double horizLen = toTargetHoriz.length();
                    double angleFromMoveDeg = 0.0;
                    if (horizLen > 0.25) {
                        double dot = Mth.clamp(forwardVec.dot(toTargetHoriz.scale(1.0 / horizLen)), -1.0, 1.0);
                        angleFromMoveDeg = Math.toDegrees(Math.acos(dot));
                        if (angleFromMoveDeg > MAX_SPRINT_HARVEST_ANGLE_DEG) {
                            continue;
                        }
                    }

                    // Weryfikacja Line-of-Sight Raycast
                    Optional<BlockHitResult> validHit = raycastBlockVisible(player, level, eyePos, pos, targetCenter, action);
                    if (validHit.isEmpty()) {
                        continue;
                    }

                    BlockHitResult hit = validHit.get();
                    float[] rotations = computeRotationsToVec(eyePos, hit.getLocation());
                    float yawDiff = Math.abs(Mth.wrapDegrees(rotations[0] - player.getYRot()));
                    float pitchDiff = Math.abs(rotations[1] - player.getXRot());

                    // Priorytetyzacja: immediateReplant tuż po zbiorze ma wyższy priorytet (niższy koszt)
                    double actionPriorityBonus = (action == TargetAction.PLANT_SEED) ? -1.4 : 0.0;
                    double score = (dist * 0.85) + (yawDiff * 0.065) + (pitchDiff * 0.03) + (angleFromMoveDeg * 0.04) + actionPriorityBonus;

                    if (score < bestScore) {
                        bestScore = score;
                        bestTarget = new HarvestTarget(
                                pos.immutable(),
                                hit.getLocation(),
                                hit.getDirection(),
                                action,
                                rotations[0],
                                rotations[1],
                                score
                        );
                    }
                }
            }
        }

        return Optional.ofNullable(bestTarget);
    }

    private TargetAction classifyBlockAction(Level level, BlockPos pos, BlockState state, boolean allowReplant) {
        Block block = state.getBlock();
        if (block instanceof CropBlock crop) {
            if (crop.isMaxAge(state)) {
                return TargetAction.HARVEST_MATURE;
            }
            return null;
        }
        if (block instanceof NetherWartBlock) {
            if (state.getValue(NetherWartBlock.AGE) >= 3) {
                return TargetAction.HARVEST_MATURE;
            }
            return null;
        }
        if (allowReplant && (block instanceof FarmBlock || block == Blocks.SOUL_SAND)) {
            BlockState aboveState = level.getBlockState(pos.above());
            if (aboveState.isAir()) {
                return TargetAction.PLANT_SEED;
            }
        }
        return null;
    }

    /**
     * Wykonuje dokładny raycast z pozycji oczu gracza do docelowego bloku,
     * sprawdzając, czy żaden blok po drodze nie zasłania linii wzroku (GrimAC Raytrace check).
     */
    private Optional<BlockHitResult> raycastBlockVisible(
            LocalPlayer player,
            Level level,
            Vec3 eyePos,
            BlockPos targetPos,
            Vec3 idealHitVec,
            TargetAction action
    ) {
        ClipContext ctx = new ClipContext(
                eyePos,
                idealHitVec,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player
        );
        BlockHitResult result = level.clip(ctx);

        if (result.getType() == HitResult.Type.BLOCK && result.getBlockPos().equals(targetPos)) {
            if (action == TargetAction.PLANT_SEED && result.getDirection() != Direction.UP) {
                return Optional.of(new BlockHitResult(idealHitVec, Direction.UP, targetPos, false));
            }
            return Optional.of(result);
        }

        // Dla płaskich modeli plonów (np. pszenica lub puste pole) raycast może trafić w blok tuż pod/nad
        if (action == TargetAction.PLANT_SEED && result.getBlockPos().equals(targetPos.above())) {
            return Optional.of(new BlockHitResult(idealHitVec, Direction.UP, targetPos, false));
        }
        return Optional.empty();
    }

    public static float[] computeRotationsToVec(Vec3 eyePos, Vec3 targetVec) {
        double dx = targetVec.x - eyePos.x;
        double dy = targetVec.y - eyePos.y;
        double dz = targetVec.z - eyePos.z;
        double horizDist = Math.hypot(dx, dz);

        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        float pitch = (float) (-(Mth.atan2(dy, horizDist) * (180.0 / Math.PI)));
        return new float[]{Mth.wrapDegrees(yaw), Mth.clamp(pitch, -89.9F, 89.9F)};
    }

    private float computeInitialYaw(Vec3 playerPos, List<BlockPos> waypoints) {
        if (waypoints.isEmpty()) {
            return 0.0F;
        }
        BlockPos first = waypoints.getFirst();
        Vec3 target = Vec3.atBottomCenterOf(first);
        return computeRotationsToVec(playerPos, target)[0];
    }

    private int findDominantYLevel(List<BlockPos> blocks, int fallbackY) {
        return blocks.stream()
                .map(BlockPos::getY)
                .reduce((a, b) -> a)
                .orElse(fallbackY);
    }

    private FieldBounds computeBounds(List<BlockPos> blocks) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : blocks) {
            if (pos.getX() < minX) minX = pos.getX();
            if (pos.getX() > maxX) maxX = pos.getX();
            if (pos.getZ() < minZ) minZ = pos.getZ();
            if (pos.getZ() > maxZ) maxZ = pos.getZ();
        }
        return new FieldBounds(minX, maxX, minZ, maxZ);
    }

    private record FieldBounds(int minX, int maxX, int minZ, int maxZ) {
        int width() {
            return (maxX - minX) + 1;
        }
        int length() {
            return (maxZ - minZ) + 1;
        }
    }

    // ── AKTYWNA TRASA I NAWIGACJA PO OSI KORYTARZA (Spine Waypoints) ──
    private OptimizedFarmRoute activeRoute = null;
    private int currentWaypointIdx = 0;
    private boolean lastHaveSeedsState = false;
    private int lastTargetCount = 0;

    public void setActiveRoute(OptimizedFarmRoute route) {
        this.activeRoute = route;
        this.currentWaypointIdx = 0;
    }

    public OptimizedFarmRoute getActiveRoute() {
        return activeRoute;
    }

    public void resetRoute() {
        this.activeRoute = null;
        this.currentWaypointIdx = 0;
        this.lastHaveSeedsState = false;
        this.lastTargetCount = 0;
    }

    public boolean hasActiveRoute() {
        return activeRoute != null && !activeRoute.isEmpty() && currentWaypointIdx < activeRoute.spineWaypoints().size();
    }

    public BlockPos getCurrentWaypoint() {
        if (!hasActiveRoute()) return null;
        return activeRoute.spineWaypoints().get(currentWaypointIdx);
    }

    /**
     * Rzutuje sztuczny waypoint Boustrophedon na najbliższe rzeczywiste zadanie (kłos lub grządkę).
     * Zapobiega generowaniu celów w powietrzu lub na pustych krawędziach pola (Argon 4).
     */
    public BlockPos snapSpineWaypointToNearestRealTask(Vec3 playerPos, BlockPos rawSpineWp, List<BlockPos> matureCrops, List<BlockPos> emptyFarmland) {
        if (rawSpineWp == null) return null;
        List<BlockPos> candidates = (matureCrops != null && !matureCrops.isEmpty()) ? matureCrops : emptyFarmland;
        if (candidates == null || candidates.isEmpty()) return rawSpineWp;

        BlockPos bestTask = null;
        double bestDistSq = Double.MAX_VALUE;

        for (BlockPos p : candidates) {
            Vec3 v = Vec3.atBottomCenterOf(p);
            // Ignoruj bloki pod stopami gracza (< 1.5m), aby bot nie snapował waypointu w tył lub pod siebie
            if (playerPos != null && v.distanceToSqr(playerPos) < 2.25D) {
                continue;
            }
            double dSq = p.distSqr(rawSpineWp);
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                bestTask = p;
            }
        }
        return (bestTask != null && bestDistSq <= 49.0D) ? bestTask : rawSpineWp;
    }

    public BlockPos snapSpineWaypointToNearestRealTask(BlockPos rawSpineWp, List<BlockPos> matureCrops, List<BlockPos> emptyFarmland) {
        return snapSpineWaypointToNearestRealTask(null, rawSpineWp, matureCrops, emptyFarmland);
    }

    /**
     * Inteligentna aktualizacja trasy:
     * - Jeśli stan nasion uległ zmianie (np. bot podniósł pierwsze nasiona lub skończyły się), natychmiast przebuduj.
     * - Jeśli liczba celów zmieniła się znacząco (np. po załadowaniu pełnego skanu chunków), natychmiast przebuduj.
     * - Jeśli trasa jest pusta lub została ukończona, wyznacz nową.
     * - Nigdy nie resetuje indeksu do 0 za plecy bota (znajduje najbliższy punkt w przód).
     */
    public void updateRoute(List<BlockPos> fieldBlocks, Vec3 playerPos, float currentYaw, boolean haveSeeds, boolean forceRebuild) {
        boolean seedStateChanged = (haveSeeds != lastHaveSeedsState);
        lastHaveSeedsState = haveSeeds;
        int targetCount = fieldBlocks != null ? fieldBlocks.size() : 0;
        boolean countChanged = Math.abs(targetCount - lastTargetCount) > 15;

        if (forceRebuild || seedStateChanged || countChanged || activeRoute == null || activeRoute.isEmpty() || currentWaypointIdx >= activeRoute.spineWaypoints().size()) {
            this.lastTargetCount = targetCount;
            OptimizedFarmRoute newRoute = buildOptimalRoute(fieldBlocks, playerPos, currentYaw);
            if (!newRoute.isEmpty()) {
                this.activeRoute = newRoute;
                this.currentWaypointIdx = findForwardWaypointIndex(playerPos, newRoute.spineWaypoints());
            }
        } else {
            advancePassedWaypoints(playerPos);
        }
    }

    public int findForwardWaypointIndex(Vec3 playerPos, List<BlockPos> waypoints) {
        if (waypoints == null || waypoints.isEmpty()) return 0;
        if (waypoints.size() == 1) return 0;

        int closestIdx = 0;
        double bestDistSq = Double.MAX_VALUE;
        for (int i = 0; i < waypoints.size(); i++) {
            double dSq = playerPos.distanceToSqr(Vec3.atBottomCenterOf(waypoints.get(i)));
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                closestIdx = i;
            }
        }

        // Jeśli jesteśmy bliżej niż 0.85m od najbliższego punktu, bierzemy następny punkt w przód
        if (bestDistSq < 0.7225D && closestIdx + 1 < waypoints.size()) {
            return closestIdx + 1;
        }
        return closestIdx;
    }

    public void advancePassedWaypoints(Vec3 playerPos) {
        if (!hasActiveRoute()) return;
        List<BlockPos> waypoints = activeRoute.spineWaypoints();
        while (currentWaypointIdx + 1 < waypoints.size()) {
            Vec3 curr = Vec3.atBottomCenterOf(waypoints.get(currentWaypointIdx));
            Vec3 next = Vec3.atBottomCenterOf(waypoints.get(currentWaypointIdx + 1));
            Vec3 seg = next.subtract(curr);
            Vec3 toPlayer = playerPos.subtract(curr);

            double segLenSq = seg.x * seg.x + seg.z * seg.z;
            double proj = (segLenSq > 1e-4D) ? ((toPlayer.x * seg.x + toPlayer.z * seg.z) / segLenSq) : 1.0D;

            double distSq = (playerPos.x - curr.x) * (playerPos.x - curr.x) + (playerPos.z - curr.z) * (playerPos.z - curr.z);
            if (distSq < 0.7225D || proj >= 0.85D) {
                currentWaypointIdx++;
            } else {
                break;
            }
        }
    }

    /**
     * Zwraca aktywny punkt osi korytarza Boustrophedon.
     * Przełącza na kolejny punkt, gdy gracz zbliży się na odległość advanceDist.
     */
    public BlockPos pollActiveWaypoint(Vec3 playerPos, double advanceDist) {
        if (!hasActiveRoute()) {
            return null;
        }
        List<BlockPos> waypoints = activeRoute.spineWaypoints();
        while (currentWaypointIdx < waypoints.size()) {
            BlockPos current = waypoints.get(currentWaypointIdx);
            double distSq = playerPos.distanceToSqr(Vec3.atBottomCenterOf(current));
            if (distSq <= advanceDist * advanceDist) {
                currentWaypointIdx++;
            } else {
                return current;
            }
        }
        return null;
    }

    /**
     * Oblicza docelowy kąt stycznej osi korytarza (tangent yaw) w miejscu gracza.
     */
    public float computeCurrentTangentYaw(Vec3 playerPos, float defaultYaw) {
        if (!hasActiveRoute()) {
            return defaultYaw;
        }
        List<BlockPos> waypoints = activeRoute.spineWaypoints();
        if (currentWaypointIdx >= waypoints.size()) {
            return defaultYaw;
        }
        BlockPos target = waypoints.get(currentWaypointIdx);
        Vec3 targetVec = Vec3.atBottomCenterOf(target);
        double dx = targetVec.x - playerPos.x;
        double dz = targetVec.z - playerPos.z;
        if (Math.hypot(dx, dz) < 0.2) {
            if (currentWaypointIdx + 1 < waypoints.size()) {
                BlockPos next = waypoints.get(currentWaypointIdx + 1);
                dx = next.getX() + 0.5 - playerPos.x;
                dz = next.getZ() + 0.5 - playerPos.z;
            }
        }
        if (Math.hypot(dx, dz) < 0.1) {
            return defaultYaw;
        }
        return (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
    }

    public boolean isCorridorClearForDirectWalk(Vec3 playerPos, BlockPos targetPos, Level world) {
        if (world == null || playerPos == null || targetPos == null) return false;
        // Na Farmland gracz ma Y = blockY + 0.9375 -> dodajemy +0.45D, aby poprawnie pobrać blok na poziomie nóg
        double startY = Math.floor(playerPos.y + 0.45D);
        double endY = targetPos.getY();
        if (Math.abs(startY - endY) > 1.35D) return false;
        double dx = (targetPos.getX() + 0.5D) - playerPos.x;
        double dz = (targetPos.getZ() + 0.5D) - playerPos.z;
        double dist = Math.hypot(dx, dz);
        if (dist < 0.35D) return true;
        int steps = Math.max(2, (int) Math.ceil(dist * 2.0));
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            double x = playerPos.x + dx * t;
            double z = playerPos.z + dz * t;
            BlockPos footPos = BlockPos.containing(x, startY + 0.1D, z);
            BlockPos headPos = footPos.above();
            BlockPos groundPos = footPos.below();

            // Woda/lawa w korytarzu lub pod stopami -> oddaj do A*
            if (!world.getFluidState(footPos).isEmpty() || !world.getFluidState(groundPos).isEmpty()) {
                return false;
            }

            BlockState footState = world.getBlockState(footPos);
            BlockState headState = world.getBlockState(headPos);
            if (!footState.isAir() && !(footState.getBlock() instanceof CropBlock) && footState.isSolid()) {
                // Sprawdzenie czy gracz może wejść na 1-blokowy stopień (step-up)
                BlockPos stepUpPos = footPos.above();
                BlockPos stepUpHead = stepUpPos.above();
                BlockState stepUpState = world.getBlockState(stepUpPos);
                BlockState stepUpHeadState = world.getBlockState(stepUpHead);
                if ((stepUpState.isAir() || stepUpState.getBlock() instanceof CropBlock) && (stepUpHeadState.isAir() || !stepUpHeadState.isSolid())) {
                    continue; // 1-blokowy schodek jest wolny do przejścia
                }
                return false;
            }
            if (!headState.isAir() && headState.isSolid()) {
                return false;
            }
            // Brak podłoża (dziura / urwisko)
            if (world.getBlockState(groundPos).getCollisionShape(world, groundPos).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Generator łuku nawrotu 180° Catmull-Rom (alpha = 0.5) z dynamicznym odpychaniem od wody.
     * Zapobiega utracie prędkości na końcach rzędów i wpadaniu do rowów melioracyjnych.
     */
    public List<Vec3> generateSmoothUturnArc(Level world, Vec3 laneExit, Vec3 nextLaneEntry, Vec3 laneDir) {
        Vec3 p0 = laneExit.subtract(laneDir.scale(1.8D));
        Vec3 p1 = pushAwayFromWater(world, laneExit.add(laneDir.scale(0.6D)));
        Vec3 p2 = pushAwayFromWater(world, nextLaneEntry.add(laneDir.scale(0.6D)));
        Vec3 p3 = nextLaneEntry.add(laneDir.scale(-1.8D));

        List<Vec3> arcPoints = new ArrayList<>(6);
        for (int i = 1; i <= 5; i++) {
            double t = i / 5.0D;
            double t2 = t * t;
            double t3 = t2 * t;

            double x = 0.5D * ((2 * p1.x) + (-p0.x + p2.x) * t + (2 * p0.x - 5 * p1.x + 4 * p2.x - p3.x) * t2 + (-p0.x + 3 * p1.x - 3 * p2.x + p3.x) * t3);
            double z = 0.5D * ((2 * p1.z) + (-p0.z + p2.z) * t + (2 * p0.z - 5 * p1.z + 4 * p2.z - p3.z) * t2 + (-p0.z + 3 * p1.z - 3 * p2.z + p3.z) * t3);

            arcPoints.add(pushAwayFromWater(world, new Vec3(x, laneExit.y, z)));
        }
        return arcPoints;
    }

    private Vec3 pushAwayFromWater(Level world, Vec3 pt) {
        if (world == null) return pt;
        BlockPos center = BlockPos.containing(pt);
        double repelX = 0.0D, repelZ = 0.0D;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos check = center.offset(dx, 0, dz);
                BlockPos checkBelow = check.below();
                if (!world.getFluidState(check).isEmpty() || !world.getFluidState(checkBelow).isEmpty()) {
                    double vx = pt.x - (check.getX() + 0.5D);
                    double vz = pt.z - (check.getZ() + 0.5D);
                    double dist = Math.hypot(vx, vz);
                    if (dist < 1.15D && dist > 1e-4D) {
                        double force = (1.15D - dist) / dist;
                        repelX += vx * force;
                        repelZ += vz * force;
                    }
                }
            }
        }
        return new Vec3(pt.x + repelX, pt.y, pt.z + repelZ);
    }

    /**
     * Wyszukuje cele w zadanym zakresie odległości i kąta.
     * Wykorzystywane do:
     * - celów natychmiastowych (reach <= 3.85m, kąt <= 55°)
     * - celów Lookahead Pre-Aiming (reach 3.85m - 6.50m, kąt <= 65°)
     */
    public Optional<HarvestTarget> findTargetInZone(
            LocalPlayer player,
            Level level,
            Vec3 eyePos,
            float movementYawDeg,
            boolean allowReplant,
            Set<BlockPos> recentlyInteracted,
            double minReach,
            double maxReach,
            double maxAngleDeg
    ) {
        double moveYawRad = Math.toRadians(movementYawDeg);
        Vec3 forwardVec = new Vec3(-Math.sin(moveYawRad), 0.0, Math.cos(moveYawRad)).normalize();

        BlockPos playerBlock = BlockPos.containing(eyePos);
        int searchRadius = (int) Math.ceil(maxReach);

        HarvestTarget bestTarget = null;
        double bestScore = Double.MAX_VALUE;

        for (int dx = -searchRadius; dx <= searchRadius; dx++) {
            for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos pos = playerBlock.offset(dx, dy, dz);
                    if (recentlyInteracted != null && recentlyInteracted.contains(pos)) {
                        continue;
                    }

                    BlockState state = level.getBlockState(pos);
                    TargetAction action = classifyBlockAction(level, pos, state, allowReplant);
                    if (action == null) {
                        continue;
                    }

                    Vec3 targetCenter = action == TargetAction.HARVEST_MATURE
                            ? new Vec3(pos.getX() + 0.5, pos.getY() + 0.10, pos.getZ() + 0.5)
                            : new Vec3(pos.getX() + 0.5, pos.getY() + 0.9375, pos.getZ() + 0.5);

                    double dist = eyePos.distanceTo(targetCenter);
                    if (dist < minReach || dist > maxReach) {
                        continue;
                    }

                    Vec3 toTargetHoriz = new Vec3(targetCenter.x - eyePos.x, 0.0, targetCenter.z - eyePos.z);
                    double horizLen = toTargetHoriz.length();
                    double angleFromMoveDeg = 0.0;
                    if (horizLen > 0.25) {
                        double dot = Mth.clamp(forwardVec.dot(toTargetHoriz.scale(1.0 / horizLen)), -1.0, 1.0);
                        angleFromMoveDeg = Math.toDegrees(Math.acos(dot));
                        if (angleFromMoveDeg > maxAngleDeg) {
                            continue;
                        }
                    }

                    float[] rotations = computeRotationsToVec(eyePos, targetCenter);
                    float yawDiff = Math.abs(Mth.wrapDegrees(rotations[0] - player.getYRot()));
                    float pitchDiff = Math.abs(rotations[1] - player.getXRot());

                    double actionPriorityBonus = (action == TargetAction.PLANT_SEED) ? -1.4 : 0.0;
                    double score = (dist * 0.85) + (yawDiff * 0.065) + (pitchDiff * 0.03) + (angleFromMoveDeg * 0.04) + actionPriorityBonus;

                    if (score < bestScore) {
                        bestScore = score;
                        bestTarget = new HarvestTarget(
                                pos.immutable(),
                                targetCenter,
                                Direction.UP,
                                action,
                                rotations[0],
                                rotations[1],
                                score
                        );
                    }
                }
            }
        }
        return Optional.ofNullable(bestTarget);
    }
}
