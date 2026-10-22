package com.astra.revolution.pathing;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
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
        double area = (double) bounds.width() * bounds.length();
        double fillRatio = area > 0 ? (double) layerBlocks.size() / area : 0.0;

        if (fillRatio >= REGULAR_FIELD_FILL_THRESHOLD && bounds.width() >= 3 && bounds.length() >= 3) {
            List<BlockPos> serpentineSpine = generateBoustrophedonCorridors(bounds, dominantY, playerPos);
            float startYaw = computeInitialYaw(playerPos, serpentineSpine);
            return new OptimizedFarmRoute(serpentineSpine, FieldTopology.REGULAR_BOUSTROPHEDON, 0, startYaw);
        } else {
            List<BlockPos> reducedClusters = downsampleToReachClusters(layerBlocks, 2.2);
            List<BlockPos> tspSpine = generateAngularTspRoute(reducedClusters, playerPos, currentYaw);
            float startYaw = computeInitialYaw(playerPos, tspSpine);
            return new OptimizedFarmRoute(tspSpine, FieldTopology.IRREGULAR_ANGULAR_TSP, 0, startYaw);
        }
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
            ClientLevel level,
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

    private TargetAction classifyBlockAction(ClientLevel level, BlockPos pos, BlockState state, boolean allowReplant) {
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
            ClientLevel level,
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
}
