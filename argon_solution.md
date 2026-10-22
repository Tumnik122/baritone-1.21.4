### 1. Diagnoza i Architektura Rozwiązania

1. **Eliminacja Path Flappingu (Kotwiczenie Celu A* i kontrakt `equals/hashCode`)**:

  - Baritone w `PathingBehavior` porównuje nowy `Goal` z poprzednim za pomocą `Objects.equals()`. Zmiana docelowego `BlockPos` po ścięciu pojedynczego plonu (lub brak poprawnego `equals`/`hashCode` w niestandardowym `Goal`) wymusza `cancelEverything()`, przerywa sprint i uruchamia wątek A*.
  - **Rozwiązanie**: Wprowadzenie **Row Anchor (Kotwicy Rzędu)**. Zamiast celować w najbliższy plon (który znika po 1 ticku), algorytm wyznacza najdalszy blok w tym samym rzędzie/korytarzu (wzdłuż osi X lub Z, do 14 bloków w przód) i blokuje go w `lockedGoalPos`. Cel A* nie zmienia się, dopóki bot nie zbliży się do kotwicy na dystans mniejszy niż 1.6m lub w promieniu korytarza nie skończą się zadania.
2. **Dual-Action Pipelining (Zbieranie + Sadzenie w jednym ticku bez `REQUEST_PAUSE`)**:

  - Usunięcie blokady `!hasUrgentFarmland`. Wprowadzenie budżetu `MAX_ACTIONS_PER_TICK = 2` rozdzielanego potokowo:

    - **Faza 1 (Trailing/Underfoot Replant)**: Priorytet dla pustego `Farmland`, które zaraz wypadnie z zasięgu (za plecami lub pod stopami bota) – zużywa 1 akcję i ustawia hotbar na nasiona.
    - **Faza 2 (Forward Cone Harvest)**: Ścinanie plonów o zerowej twardości (`Instant-Break`: pszenica, marchew, ziemniaki, buraki) w stożku przed graczem (zasięg do 3.8m), zużywając pozostały budżet akcji bez zatrzymywania ruchu (`SET_GOAL_AND_PATH`).
    - **Płynna rotacja (GrimAC Safe)**: Ograniczenie delty kątowej i unikanie skoków Pitch do 90° (celowanie w górną krawędź hitboxa `Farmland` `Y + 0.9375` oraz środek plonu `Y + 0.35`, utrzymując zbliżony wektor patrzenia podczas biegu).

### 2. Implementacja w `FarmProcess.java` (JDK 21 / Minecraft 1.21.4)

```java
package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.IFarmProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.block.FarmlandBlock;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class OptimizedFarmHelper {

    private static final int MAX_ACTIONS_PER_TICK = 2;
    private static final double INTERACT_REACH_SQ = 3.8 * 3.8;
    private static final double ROW_CORRIDOR_WIDTH = 1.5;
    private static final int MAX_ROW_LOOKAHEAD = 14;
    private static final int GOAL_LOCK_MAX_TICKS = 45;

    private static final Set<Item> PLANTABLE_SEEDS = Set.of(
            Items.WHEAT_SEEDS,
            Items.CARROT,
            Items.POTATO,
            Items.BEETROOT_SEEDS
    );

    // Stan stabilizacji celu A* (umieść jako pola instancyjne w FarmProcess)
    private BlockPos lockedGoalPos = null;
    private Goal activeStableGoal = null;
    private int goalLockTicks = 0;
    private Rotation lastSmoothRotation = null;

    public record FarmTickCandidates(
            List<BlockPos> matureCrops,
            List<BlockPos> emptyFarmland
    ) {}

    /**
     * Główna metoda wykonawcza wywoływana w onTick() klasy FarmProcess.
     * Zastępuje dotychczasową logikę wyboru celu i interakcji.
     */
    public PathingCommand executeSprintFarmTick(
            Baritone baritone,
            FarmTickCandidates candidates
    ) {
        ClientPlayerEntity player = baritone.getPlayerContext().player();
        ClientWorld world = baritone.getPlayerContext().world();
        ClientPlayerInteractionManager interactionManager = baritone.getPlayerContext().minecraft().interactionManager;

        if (player == null || world == null || interactionManager == null) {
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        baritone.getInputOverrideHandler().clearAllKeys();

        Vec3d eyePos = player.getEyePos();
        Vec3d lookVec = player.getRotationVec(1.0F);
        Vec3d velocity = player.getVelocity();
        Vec3d moveDir = (velocity.horizontalLengthSquared() > 0.005)
                ? new Vec3d(velocity.x, 0, velocity.z).normalize()
                : new Vec3d(lookVec.x, 0, lookVec.z).normalize();

        int actionsRemaining = MAX_ACTIONS_PER_TICK;
        Rotation targetRotationThisTick = null;

        // 1. PIPELINE FAZA 1: Pilne sadzenie (farmland pod graczem lub uciekające z zasięgu za plecami)
        List<BlockPos> reachableFarmland = findReachableFarmland(
                candidates.emptyFarmland(), world, eyePos, moveDir
        );

        for (BlockPos farmPos : reachableFarmland) {
            if (actionsRemaining <= 0) break;

            // Jeżeli grządka ucieka za nas (iloczyn skalarny <= 0.15) lub mamy nadmiar budżetu
            boolean isUrgentTrailing = isTrailingOrUnderfoot(farmPos, player.getPos(), moveDir);
            if (isUrgentTrailing || candidates.matureCrops().isEmpty()) {
                if (selectSeedInHotbar(player)) {
                    Rotation plantRot = computeSafePlantRotation(player, farmPos);
                    applySmoothRotation(baritone, player, plantRot);
                    targetRotationThisTick = plantRot;

                    performPlantPacket(interactionManager, player, farmPos);
                    actionsRemaining--;
                }
            }
        }

        // 2. PIPELINE FAZA 2: Ścinanie w biegu w stożku przed graczem (Instant-Break)
        if (actionsRemaining > 0) {
            List<BlockPos> forwardCrops = findForwardConeCrops(
                    candidates.matureCrops(), world, eyePos, moveDir
            );

            for (BlockPos cropPos : forwardCrops) {
                if (actionsRemaining <= 0) break;

                Rotation breakRot = computeSafeBreakRotation(player, cropPos);
                if (targetRotationThisTick == null) {
                    applySmoothRotation(baritone, player, breakRot);
                    targetRotationThisTick = breakRot;
                }

                // Instant-break dla plonów (0.0f hardness) - używamy attackBlock bez zatrzymywania
                interactionManager.attackBlock(cropPos, Direction.UP);
                player.swingHand(Hand.MAIN_HAND);
                actionsRemaining--;
            }
        }

        // 3. PIPELINE FAZA 3: Dobicie sadzenia pozostałym budżetem (przed graczem)
        if (actionsRemaining > 0 && !reachableFarmland.isEmpty() && hasSeedsInHotbar(player)) {
            for (BlockPos farmPos : reachableFarmland) {
                if (actionsRemaining <= 0) break;
                if (!world.getBlockState(farmPos.up()).isAir()) continue;

                if (selectSeedInHotbar(player)) {
                    Rotation plantRot = computeSafePlantRotation(player, farmPos);
                    if (targetRotationThisTick == null) {
                        applySmoothRotation(baritone, player, plantRot);
                        targetRotationThisTick = plantRot;
                    }
                    performPlantPacket(interactionManager, player, farmPos);
                    actionsRemaining--;
                }
            }
        }

        // 4. STABILNY GOAL A* (Zero Path Flappingu)
        Goal stableGoal = updateOrKeepStableRowGoal(
                player, world, candidates.matureCrops(), candidates.emptyFarmland()
        );

        if (stableGoal == null) {
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        // Wymuszenie ciągłego sprintu jeśli ścieżka jest aktywna i biegniemy prosto
        if (baritone.getPathingBehavior().isPathing() && player.getHungerManager().getFoodLevel() > 6) {
            baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, true);
        }

        // Zawsze zwracamy SET_GOAL_AND_PATH z tym samym obiektem Goal -> Baritone nie resetuje A*
        return new PathingCommand(stableGoal, PathingCommandType.SET_GOAL_AND_PATH);
    }

    /**
     * Kotwiczenie celu na końcu rzędu: zapobiega przeliczaniu A* co 100-300ms.
     */
    private Goal updateOrKeepStableRowGoal(
            ClientPlayerEntity player,
            ClientWorld world,
            List<BlockPos> matureCrops,
            List<BlockPos> emptyFarmland
    ) {
        goalLockTicks++;
        BlockPos playerBlockPos = player.getBlockPos();

        // Sprawdź, czy obecna kotwica nadal jest ważna (nie dotarliśmy do niej i w jej pobliżu są zadania)
        if (lockedGoalPos != null && activeStableGoal != null && goalLockTicks < GOAL_LOCK_MAX_TICKS) {
            double distSqToAnchor = playerBlockPos.getSquaredDistance(lockedGoalPos);
            boolean anchorReached = distSqToAnchor <= (1.6 * 1.6);
            boolean tasksRemainNearAnchor = hasValidTasksNear(
                    lockedGoalPos, world, matureCrops, emptyFarmland
            );

            if (!anchorReached && tasksRemainNearAnchor) {
                return activeStableGoal; // Zwrócenie TEJ SAMEJ instancji -> brak przebudowy A*!
            }
        }

        // Wybór nowej kotwicy rzędu: najpierw znajdź najbliższy klaster pracy, a potem wyznacz koniec jego rzędu
        Optional<BlockPos> nearestWork = findNearestTask(playerBlockPos, matureCrops, emptyFarmland);
        if (nearestWork.isEmpty()) {
            lockedGoalPos = null;
            activeStableGoal = null;
            return null;
        }

        BlockPos rowStart = nearestWork.get();
        BlockPos rowAnchor = computeFarRowAnchor(rowStart, matureCrops, emptyFarmland);

        if (!Objects.equals(lockedGoalPos, rowAnchor) || activeStableGoal == null) {
            lockedGoalPos = rowAnchor;
            // GoalNear(..., 1) implementuje poprawnie equals/hashCode w Baritone
            activeStableGoal = new GoalNear(new BetterBlockPos(rowAnchor), 1);
            goalLockTicks = 0;
        }

        return activeStableGoal;
    }

    /**
     * Znajduje najdalszy punkt w tym samym rzędzie (wzdłuż osi X lub Z),
     * dzięki czemu A* wyznacza jedną prostą ścieżkę przez cały rząd upraw.
     */
    private BlockPos computeFarRowAnchor(
            BlockPos start,
            List<BlockPos> matureCrops,
            List<BlockPos> emptyFarmland
    ) {
        List<BlockPos> allTasks = java.util.stream.Stream.concat(
                matureCrops.stream(),
                emptyFarmland.stream().map(BlockPos::up)
        ).toList();

        // Zlicz zagęszczenie wzdłuż osi X oraz osi Z
        List<BlockPos> xRow = allTasks.stream()
                .filter(p -> Math.abs(p.getY() - start.getY()) <= 1)
                .filter(p -> Math.abs(p.getZ() - start.getZ()) <= ROW_CORRIDOR_WIDTH)
                .filter(p -> Math.abs(p.getX() - start.getX()) <= MAX_ROW_LOOKAHEAD)
                .toList();

        List<BlockPos> zRow = allTasks.stream()
                .filter(p -> Math.abs(p.getY() - start.getY()) <= 1)
                .filter(p -> Math.abs(p.getX() - start.getX()) <= ROW_CORRIDOR_WIDTH)
                .filter(p -> Math.abs(p.getZ() - start.getZ()) <= MAX_ROW_LOOKAHEAD)
                .toList();

        List<BlockPos> dominantRow = (xRow.size() >= zRow.size()) ? xRow : zRow;

        return dominantRow.stream()
                .max(Comparator.comparingDouble(start::getSquaredDistance))
                .orElse(start);
    }

    private boolean hasValidTasksNear(
            BlockPos anchor,
            ClientWorld world,
            List<BlockPos> matureCrops,
            List<BlockPos> emptyFarmland
    ) {
        double radiusSq = 3.5 * 3.5;
        for (BlockPos crop : matureCrops) {
            if (crop.getSquaredDistance(anchor) <= radiusSq && isMatureCrop(world.getBlockState(crop))) {
                return true;
            }
        }
        for (BlockPos farm : emptyFarmland) {
            if (farm.getSquaredDistance(anchor) <= radiusSq && world.getBlockState(farm.up()).isAir()) {
                return true;
            }
        }
        return false;
    }

    private Optional<BlockPos> findNearestTask(
            BlockPos origin,
            List<BlockPos> matureCrops,
            List<BlockPos> emptyFarmland
    ) {
        return java.util.stream.Stream.concat(
                matureCrops.stream(),
                emptyFarmland.stream().map(BlockPos::up)
        ).min(Comparator.comparingDouble(origin::getSquaredDistance));
    }

    private List<BlockPos> findForwardConeCrops(
            List<BlockPos> matureCrops,
            ClientWorld world,
            Vec3d eyePos,
            Vec3d moveDir
    ) {
        return matureCrops.stream()
                .filter(pos -> isMatureCrop(world.getBlockState(pos)))
                .filter(pos -> eyePos.squaredDistanceTo(Vec3d.ofCenter(pos)) <= INTERACT_REACH_SQ)
                .filter(pos -> {
                    Vec3d toTarget = new Vec3d(
                            pos.getX() + 0.5 - eyePos.x,
                            0,
                            pos.getZ() + 0.5 - eyePos.z
                    ).normalize();
                    // Akceptuj plony przed graczem oraz bezpośrednio obok w korytarzu (dot >= -0.15)
                    return moveDir.dotProduct(toTarget) >= -0.15;
                })
                .sorted(Comparator.comparingDouble(pos -> eyePos.squaredDistanceTo(Vec3d.ofCenter(pos))))
                .toList();
    }

    private List<BlockPos> findReachableFarmland(
            List<BlockPos> emptyFarmland,
            ClientWorld world,
            Vec3d eyePos,
            Vec3d moveDir
    ) {
        return emptyFarmland.stream()
                .filter(pos -> world.getBlockState(pos).getBlock() instanceof FarmlandBlock)
                .filter(pos -> world.getBlockState(pos.up()).isAir())
                .filter(pos -> eyePos.squaredDistanceTo(new Vec3d(pos.getX() + 0.5, pos.getY() + 0.9375, pos.getZ() + 0.5)) <= INTERACT_REACH_SQ)
                // Sortuj od najbardziej zagrożonych wypadnięciem z zasięgu (najmniejszy dotProduct względem kierunku ruchu)
                .sorted(Comparator.comparingDouble(pos -> {
                    Vec3d toFarm = new Vec3d(pos.getX() + 0.5 - eyePos.x, 0, pos.getZ() + 0.5 - eyePos.z);
                    return moveDir.dotProduct(toFarm);
                }))
                .toList();
    }

    private boolean isTrailingOrUnderfoot(BlockPos farmPos, Vec3d playerPos, Vec3d moveDir) {
        Vec3d offset = new Vec3d(farmPos.getX() + 0.5 - playerPos.x, 0, farmPos.getZ() + 0.5 - playerPos.z);
        double horizDistSq = offset.lengthSquared();
        if (horizDistSq <= 1.2 * 1.2) return true; // Bezpośrednio pod nogami
        return moveDir.dotProduct(offset.normalize()) <= 0.15; // Ucieka za plecy
    }

    private boolean isMatureCrop(BlockState state) {
        return state.getBlock() instanceof CropBlock crop && crop.isMature(state);
    }

    private Rotation computeSafeBreakRotation(ClientPlayerEntity player, BlockPos cropPos) {
        Vec3d hitVec = new Vec3d(cropPos.getX() + 0.5, cropPos.getY() + 0.35, cropPos.getZ() + 0.5);
        return RotationUtils.calcRotationFromVec3d(player.getEyePos(), hitVec, new Rotation(player.getYaw(), player.getPitch()));
    }

    private Rotation computeSafePlantRotation(ClientPlayerEntity player, BlockPos farmPos) {
        // Górna płaszczyzna Farmland ma wysokość 15/16 = 0.9375
        Vec3d hitVec = new Vec3d(farmPos.getX() + 0.5, farmPos.getY() + 0.9375, farmPos.getZ() + 0.5);
        return RotationUtils.calcRotationFromVec3d(player.getEyePos(), hitVec, new Rotation(player.getYaw(), player.getPitch()));
    }

    /**
     * Wygładzanie rotacji zgodne z GrimAC (ograniczenie skoków kątowych i utrzymanie ciągłości ruchu).
     */
    private void applySmoothRotation(Baritone baritone, ClientPlayerEntity player, Rotation target) {
        Rotation current = (lastSmoothRotation != null)
                ? lastSmoothRotation
                : new Rotation(player.getYaw(), player.getPitch());

        float yawDiff = wrapDegrees(target.getYaw() - current.getYaw());
        float pitchDiff = target.getPitch() - current.getPitch();

        float maxYawStep = 55.0F;
        float maxPitchStep = 35.0F;

        float nextYaw = current.getYaw() + Math.clamp(yawDiff, -maxYawStep, maxYawStep);
        float nextPitch = Math.clamp(current.getPitch() + Math.clamp(pitchDiff, -maxPitchStep, maxPitchStep), -85.0F, 82.0F);

        Rotation smoothed = new Rotation(nextYaw, nextPitch);
        lastSmoothRotation = smoothed;
        baritone.getLookBehavior().updateTarget(smoothed, false); // false = nie blokuj ruchu / sprintu
    }

    private void performPlantPacket(
            ClientPlayerInteractionManager interactionManager,
            ClientPlayerEntity player,
            BlockPos farmPos
    ) {
        Vec3d hitVec = new Vec3d(farmPos.getX() + 0.5, farmPos.getY() + 0.9375, farmPos.getZ() + 0.5);
        BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.UP, farmPos, false);
        interactionManager.interactBlock(player, Hand.MAIN_HAND, hitResult);
        player.swingHand(Hand.MAIN_HAND);
    }

    private boolean hasSeedsInHotbar(ClientPlayerEntity player) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty() && PLANTABLE_SEEDS.contains(stack.getItem())) {
                return true;
            }
        }
        return false;
    }

    private boolean selectSeedInHotbar(ClientPlayerEntity player) {
        ItemStack current = player.getMainHandStack();
        if (!current.isEmpty() && PLANTABLE_SEEDS.contains(current.getItem())) {
            return true;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty() && PLANTABLE_SEEDS.contains(stack.getItem())) {
                player.getInventory().selectedSlot = i;
                return true;
            }
        }
        return false;
    }

    private static float wrapDegrees(float degrees) {
        float d = degrees % 360.0F;
        if (d >= 180.0F) d -= 360.0F;
        if (d < -180.0F) d += 360.0F;
        return d;
    }
}
```

### 3. Podpięcie w `FarmProcess#onTick`

W metodzie `onTick(boolean calcFailed, boolean isSafeToCancel)` w `FarmProcess.java` zastąp sekcję budowania `goalz` oraz blokadę `REQUEST_PAUSE` wywołaniem helpera:

```java
// Pole w klasie FarmProcess:
private final OptimizedFarmHelper sprintFarmHelper = new OptimizedFarmHelper();

// Wewnątrz onTick(...) po zebraniu list toBreak (dojrzałe plony) oraz openFarmland (puste grządki):
if (!toBreak.isEmpty() || !openFarmland.isEmpty()) {
    return sprintFarmHelper.executeSprintFarmTick(
            this.baritone,
            new OptimizedFarmHelper.FarmTickCandidates(toBreak, openFarmland)
    );
}
```

### Kluczowe gwarancje wdrożenia:

- **Brak spamu A***: `updateOrKeepStableRowGoal` zwraca referencyjnie ten sam obiekt `GoalNear` (kotwicę na końcu rzędu do 14 bloków w przód) przez maksymalnie 45 ticków lub do momentu ukończenia korytarza.
- **Brak zatrzymań (`Zero-Stuttering`)**: Całkowite wyeliminowanie `PathingCommandType.REQUEST_PAUSE` oraz parametru `blockInteract = true` w `LookBehavior#updateTarget(smoothed, false)` – gracz utrzymuje pełny pęd sprintu (~5.6 m/s).
- **Zgodność z GrimAC**: Limit `MAX_ACTIONS_PER_TICK = 2`, poprawne wektory trafień (`Y + 0.9375` dla `FarmlandBlock` z `Direction.UP`), płynny clamp delty rotacji bez szarpania Pitch do 90°.