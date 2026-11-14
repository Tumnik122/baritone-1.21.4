package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.bypass.AutoToolManager;
import baritone.bypass.GrimSnapRotationUtil;
import baritone.utils.BaritoneProcessHelper;
import com.astra.revolution.visuals.SpruceTreeVisuals;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Moduł SpruceTreeProcess — w pełni autonomiczna farma świerków 2x2.
 * Wersja V2: zoptymalizowana pod natychmiastowe przejście po skoku, niezawodne niszczenie bloków (start/continue digging)
 * oraz integrację z systemem wizualizacji 3D (SpruceTreeVisuals) na shaderach.
 */
public final class SpruceTreeProcess extends BaritoneProcessHelper implements IBaritoneProcess {

    public static final double MAX_GRIM_REACH = 3.9D;
    public static final double MAX_GRIM_REACH_SQ = MAX_GRIM_REACH * MAX_GRIM_REACH;
    private static final int HOME_COOLDOWN_TICKS = 120; // 6.0 sekund (120 ticków)
    private static final int CHUNK_SETTLE_TICKS = 12;

    public enum State {
        DETECT_TREE,
        DROP_ONTO_TREE,
        ZERO_WASD_HARVEST_LOOP,
        REPLANT_2X2,
        BONE_MEAL_SPAM,
        SEND_HOME_1,
        COOLDOWN_6S
    }

    public enum TargetType {
        NONE,
        LEAF,
        LOG,
        REPLANT,
        BONE_MEAL
    }

    private boolean active = false;
    private State state = State.DETECT_TREE;

    // Dane geometryczne wykrytego drzewa 2x2
    private int trunkMinX = 0;
    private int trunkMinZ = 0;
    private int topLogY = -1;
    private int baseSoilY = -1;

    // Aktualny cel niszczenia/stawiania do wizualizacji
    private BlockPos currentTargetPos = null;
    private TargetType currentTargetType = TargetType.NONE;

    // Statystyki i postęp niszczenia dla SpruceTreeVisuals
    private int totalChoppedLogs = 0;
    private int totalChoppedLeaves = 0;
    private float currentBlockBreakProgress = 0.0f;

    // Śledzenie poprzedniego celu do mechaniki puszczenia kliku i dwukliku
    private BlockPos lastBreakingPos = null;
    private TargetType lastBreakingType = TargetType.NONE;
    private int currentBlockDamageTicks = 0;
    private int minRequiredBreakTicks = 1;
    private int interBlockReleaseTicks = 0;

    // Liczniki ticków i kontrola stanów
    private int stateTicks = 0;
    private int cooldownTicks = 0;
    private int boneMealDelayTicks = 0;
    private int placeDelayTicks = 0;
    private double pillarReferenceY = -1.0D;

    // Zamrożona rotacja podczas COOLDOWN_6S
    private float lockedYaw = 0.0F;
    private float lockedPitch = 0.0F;

    public SpruceTreeProcess(Baritone baritone) {
        super(baritone);
    }

    public void start() {
        this.active = true;
        this.state = State.DETECT_TREE;
        this.stateTicks = 0;
        this.cooldownTicks = 0;
        this.topLogY = -1;
        this.baseSoilY = -1;
        this.currentTargetPos = null;
        this.currentTargetType = TargetType.NONE;
        this.totalChoppedLogs = 0;
        this.totalChoppedLeaves = 0;
        this.currentBlockBreakProgress = 0.0f;
        this.currentBlockDamageTicks = 0;
        this.minRequiredBreakTicks = 1;
        this.lastBreakingPos = null;
        this.lastBreakingType = TargetType.NONE;
        this.interBlockReleaseTicks = 0;
        if (ctx.player() != null) {
            this.pillarReferenceY = ctx.player().getY();
        }
        logDirect("§a[Astra/Spruce2x2] Uruchomiono autonomiczny proces farmienia swierkow 2x2 (GrimAC 3.9 reach).");
    }

    public void stop() {
        this.active = false;
        this.currentTargetPos = null;
        this.currentTargetType = TargetType.NONE;
        this.currentBlockBreakProgress = 0.0f;
        this.currentBlockDamageTicks = 0;
        this.lastBreakingPos = null;
        this.lastBreakingType = TargetType.NONE;
        stopDigging();
        baritone.getInputOverrideHandler().clearAllKeys();
        baritone.getLookBehavior().updateTarget(null, false);
        SpruceTreeVisuals.updateState(
                false, SpruceTreeVisuals.FarmStatus.ANALIZA, false,
                false, 0, 0, 0, 0,
                false, 0, null,
                0, 0, 0,
                0, 150, 0
        );
    }

    public boolean isDigging() {
        return this.active && this.currentTargetPos != null && (this.currentTargetType == TargetType.LOG || this.currentTargetType == TargetType.LEAF);
    }

    public State getCurrentState() {
        return this.state;
    }

    public int getTrunkMinX() { return trunkMinX; }
    public int getTrunkMinZ() { return trunkMinZ; }
    public int getTopLogY() { return topLogY; }
    public int getBaseSoilY() { return baseSoilY; }
    public BlockPos getCurrentTargetPos() { return currentTargetPos; }
    public TargetType getCurrentTargetType() { return currentTargetType; }

    @Override
    public boolean isActive() {
        return this.active && ctx.player() != null && ctx.world() != null;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (!isActive()) {
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        LocalPlayer player = ctx.player();
        Level level = ctx.world();
        stateTicks++;

        PathingCommand cmd;
        switch (state) {
            case DETECT_TREE -> {
                cmd = handleDetectTree(player, level);
            }
            case DROP_ONTO_TREE -> {
                cmd = handleDropOntoTree(player, level);
            }
            case ZERO_WASD_HARVEST_LOOP -> {
                cmd = handleZeroWasdHarvest(player, level);
            }
            case REPLANT_2X2 -> {
                cmd = handleReplant2x2(player, level);
            }
            case BONE_MEAL_SPAM -> {
                cmd = handleBoneMealSpam(player, level);
            }
            case SEND_HOME_1 -> {
                cmd = handleSendHome(player);
            }
            case COOLDOWN_6S -> {
                cmd = handleCooldown6s(player);
            }
            default -> {
                cmd = new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }

        updateVisuals(player, level);
        return cmd;
    }

    // =========================================================================
    // KROK 1: SPAWN / DETECT_TREE
    // =========================================================================
    private PathingCommand handleDetectTree(LocalPlayer player, Level level) {
        baritone.getInputOverrideHandler().clearAllKeys();
        stopDigging();

        // Stabilizacja chunków po teleportacji /home 1
        if (stateTicks < CHUNK_SETTLE_TICKS || !player.onGround()) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        pillarReferenceY = Math.max(pillarReferenceY, player.getY());
        BlockPos playerPos = player.blockPosition();

        int foundMinX = 0;
        int foundMinZ = 0;
        int highestLogY = Integer.MIN_VALUE;

        // Skanowanie przestrzeni w dół i wokół słupa w poszukiwaniu układu 2x2 Blocks.SPRUCE_LOG
        for (int dy = 4; dy >= -50; dy--) {
            int y = playerPos.getY() + dy;
            for (int dx = -14; dx <= 14; dx++) {
                for (int dz = -14; dz <= 14; dz++) {
                    int x = playerPos.getX() + dx;
                    int z = playerPos.getZ() + dz;
                    if (is2x2SpruceTrunk(level, x, y, z)) {
                        if (y > highestLogY) {
                            highestLogY = y;
                            foundMinX = x;
                            foundMinZ = z;
                        }
                    }
                }
            }
            if (highestLogY != Integer.MIN_VALUE) {
                break; // Znaleziono najwyższą warstwę pnia 2x2
            }
        }

        if (highestLogY == Integer.MIN_VALUE) {
            if (stateTicks % 50 == 0) {
                logDirect("§e[Astra/Spruce2x2] Oczekiwanie na wykrycie pnia 2x2 spruce_log...");
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        this.trunkMinX = foundMinX;
        this.trunkMinZ = foundMinZ;
        this.topLogY = highestLogY;
        this.baseSoilY = findSoilBaseY(level, foundMinX, highestLogY, foundMinZ);

        logDirect(String.format("§b[Astra/Spruce2x2] Wykryto pien 2x2: X=%d, Z=%d, TopY=%d, SoilY=%d (wysokosc: %d)",
                trunkMinX, trunkMinZ, topLogY, baseSoilY, (topLogY - baseSoilY + 1)));

        transitionTo(State.DROP_ONTO_TREE);
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    // =========================================================================
    // KROK 2: DROP_ONTO_TREE (Skok ze słupa na koronę / pień 2x2)
    // =========================================================================
    private PathingCommand handleDropOntoTree(LocalPlayer player, Level level) {
        Vec3 targetCenter = new Vec3(trunkMinX + 1.0D, topLogY + 1.0D, trunkMinZ + 1.0D);
        double distXZ = Math.hypot(player.getX() - targetCenter.x, player.getZ() - targetCenter.z);

        // KRYTYCZNA POPRAWKA: Sprawdzenie lądowania na koronie drzewa (zarówno na liściach, jak i na drewnie)
        boolean hasDescended = player.getY() <= pillarReferenceY - 1.8D;
        boolean withinCanopyXZ = distXZ <= 2.8D;
        boolean onTreeBlock = isStandingOnTreeOrLeaves(player, level);

        if (player.onGround() && (onTreeBlock || (hasDescended && withinCanopyXZ))) {
            enforceDeadStop(player);
            logDirect("§a[Astra/Spruce2x2] Wylądowano na koronie świerka! Blokada WASD aktywna -> rozpoczynam żniwa.");
            transitionTo(State.ZERO_WASD_HARVEST_LOOP);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        // Gdy gracz jest już tuż nad pniem w locie, puszczamy klawisze ruchu poziomego, aby spaść pionowo
        if (distXZ <= 0.55D) {
            freezeHorizontalMovement();
            applyGcdRotation(player, targetCenter);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // Nakierowanie i ruch do przodu ze słupa w stronę pnia
        applyGcdRotation(player, new Vec3(targetCenter.x, player.getEyeY() - 0.2D, targetCenter.z));
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);

        // Skok jeśli na słupie lub przy krawędzi słupa, aby nadać pęd w stronę korony
        if (player.onGround() && player.getY() >= pillarReferenceY - 0.5D) {
            baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
        } else if (player.horizontalCollision && player.onGround()) {
            baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
        } else {
            baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, false);
        }

        // Bezpiecznik: jeśli po 140 tickach (7 sek) gracz nadal nie wylądował (np. zaciął się na słupie)
        if (stateTicks > 140) {
            logDirect("§e[Astra/Spruce2x2] Przekroczono czas skoku - ponawiam /home 1.");
            transitionTo(State.SEND_HOME_1);
        }

        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    // =========================================================================
    // KROK 3: ZERO_WASD_HARVEST_LOOP (Żniwa bez chodzenia: Motyka -> Siekiera)
    // =========================================================================
    private PathingCommand handleZeroWasdHarvest(LocalPlayer player, Level level) {
        // BEZWZGLĘDNA BLOKADA KLAWISZY WASD
        freezeHorizontalMovement();

        // Sprawdzenie, czy dotarliśmy do podłoża (podzol / dirt) i pień został ścięty
        boolean reachedGround = player.onGround() && (player.getY() <= baseSoilY + 2.5D);
        boolean noRemainingLogs = !hasAnyRemainingTrunkLogsInReach(player, level);

        if (reachedGround && noRemainingLogs) {
            stopDigging();
            logDirect("§a[Astra/Spruce2x2] Pień 2x2 ścięty do ziemi. Przechodzę do sadzenia (REPLANT_2X2).");
            transitionTo(State.REPLANT_2X2);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // KRYTYCZNA BLOKADA CELU (Eliminacja chattering / niedobijania bloków na ostatnim pęknięciu):
        // Jeśli aktualnie kopiemy dany blok, nie jest on powietrzem i nadal mieści się w zasięgu - DOKOŃCZ GO!
        if (lastBreakingPos != null && !level.getBlockState(lastBreakingPos).isAir()) {
            double distSq = player.getEyePosition().distanceToSqr(Vec3.atCenterOf(lastBreakingPos));
            if (distSq <= MAX_GRIM_REACH_SQ) {
                BlockHitResult lockedHit = raycastBlockVisibleFace(player, level, lastBreakingPos);
                if (lockedHit != null) {
                    BlockState lockedState = level.getBlockState(lastBreakingPos);
                    if (lastBreakingType == TargetType.LOG) {
                        selectAxeOrWarn(player, lockedState);
                    } else if (lastBreakingType == TargetType.LEAF) {
                        selectHoeOrWarn(player, lockedState);
                    }
                    this.currentTargetPos = lastBreakingPos;
                    this.currentTargetType = lastBreakingType;
                    applyGcdRotation(player, lockedHit.getLocation());
                    startOrContinueBreaking(player, level, lastBreakingPos, lockedHit.getDirection());
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            }
        }

        // PODFAZA A (Liście - Motyka / Ręka): szukamy widocznych liści spruce_leaves w reachu <= 3.9
        BlockHitResult leafHit = findBestBreakableTarget(player, level, true);
        if (leafHit != null) {
            BlockPos leafPos = leafHit.getBlockPos();
            BlockState leafState = level.getBlockState(leafPos);

            selectHoeOrWarn(player, leafState);

            this.currentTargetPos = leafPos;
            this.currentTargetType = TargetType.LEAF;

            applyGcdRotation(player, leafHit.getLocation());
            startOrContinueBreaking(player, level, leafPos, leafHit.getDirection());
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // PODFAZA B (Pień 2x2 - Siekiera): brak liści w reachu 3.9 -> ścinamy warstwy drewna
        BlockHitResult logHit = findBestBreakableTarget(player, level, false);
        if (logHit != null) {
            BlockPos logPos = logHit.getBlockPos();
            BlockState logState = level.getBlockState(logPos);

            if (!selectAxeOrWarn(player, logState)) {
                return cancelProcess();
            }

            this.currentTargetPos = logPos;
            this.currentTargetType = TargetType.LOG;

            applyGcdRotation(player, logHit.getLocation());
            startOrContinueBreaking(player, level, logPos, logHit.getDirection());
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // PODFAZA C: Chwilowy brak celów (np. gracz opada grawitacyjnie o 1 blok w dół)
        stopDigging();
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    // =========================================================================
    // KROK 4: REPLANT_2X2 (Sadzenie 4 sadzonek na podzolu/ziemi)
    // =========================================================================
    private PathingCommand handleReplant2x2(LocalPlayer player, Level level) {
        freezeHorizontalMovement();
        stopDigging();

        if (placeDelayTicks > 0) {
            placeDelayTicks--;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        List<BlockPos> soilPositions = get2x2SoilPositions();
        for (BlockPos soilPos : soilPositions) {
            BlockPos saplingPos = soilPos.above();
            BlockState aboveState = level.getBlockState(saplingPos);

            // Jeśli na tym bloku gleby nie stoi jeszcze sadzonka ani pień
            if (!aboveState.is(Blocks.SPRUCE_SAPLING) && !aboveState.is(Blocks.SPRUCE_LOG)) {
                // Jeśli zalega liść na miejscu sadzonki, zniszcz go przed posadzeniem
                if (aboveState.is(Blocks.SPRUCE_LEAVES)) {
                    BlockHitResult leafClearHit = raycastBlockVisibleFace(player, level, saplingPos);
                    if (leafClearHit != null) {
                        selectHoeOrWarn(player, aboveState);
                        applyGcdRotation(player, leafClearHit.getLocation());
                        startOrContinueBreaking(player, level, saplingPos, leafClearHit.getDirection());
                        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                    }
                }

                // Wybór sadzonki świerku z hotbara
                if (!selectItemFromHotbar(player, Items.SPRUCE_SAPLING)) {
                    logDirect("§c[Astra/Spruce2x2] BŁĄD: Brak sadzonek świerku (spruce_sapling) na hotbarze! Zatrzymuję bota.");
                    return cancelProcess();
                }

                // Sprawdzenie czy gracz nie blokuje własnym ciałem pola sadzenia
                if (player.getBoundingBox().intersects(saplingPos.getX(), saplingPos.getY(), saplingPos.getZ(),
                        saplingPos.getX() + 1, saplingPos.getY() + 1, saplingPos.getZ() + 1)) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                } else {
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
                }

                this.currentTargetPos = saplingPos;
                this.currentTargetType = TargetType.REPLANT;

                BlockHitResult topFaceHit = raycastTopFaceForPlacement(player, level, soilPos);
                if (topFaceHit == null) {
                    continue;
                }

                applyGcdRotation(player, topFaceHit.getLocation());

                Minecraft mc = Minecraft.getInstance();
                if (mc.gameMode != null) {
                    mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, topFaceHit);
                    player.swing(InteractionHand.MAIN_HAND);
                    placeDelayTicks = 2; // Bezpieczny odstęp pod GrimAC
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }

        // Wszystkie 4 sadzonki są posadzone
        logDirect("§a[Astra/Spruce2x2] Posadzono 4 sadzonki 2x2. Rozpoczynam pędzenie mączką (BONE_MEAL_SPAM).");
        transitionTo(State.BONE_MEAL_SPAM);
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    // =========================================================================
    // KROK 5: BONE_MEAL_SPAM (Pędzenie drzewa co 2-3 ticki)
    // =========================================================================
    private PathingCommand handleBoneMealSpam(LocalPlayer player, Level level) {
        freezeHorizontalMovement();
        stopDigging();

        // Weryfikacja czy drzewo wyrosło (pojawienie się SPRUCE_LOG)
        for (BlockPos soilPos : get2x2SoilPositions()) {
            if (level.getBlockState(soilPos.above()).is(Blocks.SPRUCE_LOG)) {
                logDirect("§a[Astra/Spruce2x2] Drzewo wyrosło! Wysyłam /home 1.");
                transitionTo(State.SEND_HOME_1);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }

        if (!selectItemFromHotbar(player, Items.BONE_MEAL)) {
            logDirect("§c[Astra/Spruce2x2] BŁĄD: Brak mączki kostnej (bone_meal) na hotbarze! Zatrzymuję bota.");
            return cancelProcess();
        }

        BlockHitResult saplingHit = null;
        BlockPos targetSaplingPos = null;
        for (BlockPos soilPos : get2x2SoilPositions()) {
            BlockPos sPos = soilPos.above();
            if (level.getBlockState(sPos).is(Blocks.SPRUCE_SAPLING)) {
                BlockHitResult candidate = raycastBlockVisibleFace(player, level, sPos);
                if (candidate != null) {
                    saplingHit = candidate;
                    targetSaplingPos = sPos;
                    break;
                }
            }
        }

        if (saplingHit == null || targetSaplingPos == null) {
            transitionTo(State.REPLANT_2X2);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        this.currentTargetPos = targetSaplingPos;
        this.currentTargetType = TargetType.BONE_MEAL;

        applyGcdRotation(player, saplingHit.getLocation());

        if (boneMealDelayTicks > 0) {
            boneMealDelayTicks--;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode != null) {
            mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, saplingHit);
            player.swing(InteractionHand.MAIN_HAND);
            boneMealDelayTicks = ThreadLocalRandom.current().nextInt(2, 4);
        }

        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    // =========================================================================
    // KROK 6: TELEPORTACJA /home 1 & COOLDOWN 6S
    // =========================================================================
    private PathingCommand handleSendHome(LocalPlayer player) {
        enforceDeadStop(player);
        stopDigging();

        if (player.connection != null) {
            player.connection.sendCommand("home 1");
        }

        this.cooldownTicks = HOME_COOLDOWN_TICKS;
        this.lockedYaw = player.getYRot();
        this.lockedPitch = player.getXRot();

        logDirect("§b[Astra/Spruce2x2] Wysłano /home 1. Wymuszam bezruch przez 120 ticków (6.0s)...");
        transitionTo(State.COOLDOWN_6S);
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private PathingCommand handleCooldown6s(LocalPlayer player) {
        enforceDeadStop(player);

        if (cooldownTicks > 0) {
            cooldownTicks--;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // Potwierdzenie teleportacji na słup startowy
        if (player.getY() > baseSoilY + 10) {
            logDirect("§a[Astra/Spruce2x2] Teleportacja udana (Y=" + String.format("%.1f", player.getY()) + "). Nowy cykl -> DETECT_TREE.");
            transitionTo(State.DETECT_TREE);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (stateTicks > HOME_COOLDOWN_TICKS + 45) {
            logDirect("§e[Astra/Spruce2x2] Brak zmiany pozycji po teleportacji - ponawiam /home 1.");
            transitionTo(State.SEND_HOME_1);
        }

        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    // =========================================================================
    // AKTYWNE NISZCZENIE BLOKÓW (GrimAC Safe startDestroy + continueDestroy)
    // =========================================================================

    private void startOrContinueBreaking(LocalPlayer player, Level level, BlockPos pos, Direction side) {
        if (pos == null) {
            stopDigging();
            return;
        }

        // Zablokuj równoległe wywoływanie przez vanilla keyAttack oraz InputOverrideHandler
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
        Minecraft mc = Minecraft.getInstance();
        if (mc.options != null && mc.options.keyAttack.isDown()) {
            mc.options.keyAttack.setDown(false);
        }

        // Sprawdzenie, czy poprzedni cel właśnie został zniszczony (stał się powietrzem)
        if (lastBreakingPos != null && level.getBlockState(lastBreakingPos).isAir()) {
            boolean wasLog = (lastBreakingType == TargetType.LOG);
            if (wasLog) {
                totalChoppedLogs++;
            } else if (lastBreakingType == TargetType.LEAF) {
                totalChoppedLeaves++;
            }
            lastBreakingPos = null;
            lastBreakingType = TargetType.NONE;
            currentBlockBreakProgress = 0.0f;
            currentBlockDamageTicks = 0;
            // GrimAC: Dla drewna (non-instamine) wymagane jest pełne 5 ticków (250ms vanilla destroyDelay).
            // Dla liści (instamine) wystarczą 2 ticki.
            interBlockReleaseTicks = wasLog ? 5 : 2;
            ctx.playerController().resetBlockRemoving();
            return;
        }

        BlockState targetState = level.getBlockState(pos);
        if (targetState.isAir()) {
            stopDigging();
            return;
        }

        if (mc.gameMode == null) return;

        Direction safeSide = side != null ? side : Direction.UP;

        // 1. ZMIANA BLOKU (z pnia 1 na pień 2 lub z liścia na liść):
        // Wymuszenie zwolnienia kliku i reset sesji kopania (eliminacja FastBreak delay=0ms)
        if (lastBreakingPos != null && !lastBreakingPos.equals(pos)) {
            boolean wasLog = (lastBreakingType == TargetType.LOG);
            ctx.playerController().resetBlockRemoving();
            lastBreakingPos = null;
            lastBreakingType = TargetType.NONE;
            currentBlockDamageTicks = 0;
            currentBlockBreakProgress = 0.0f;
            interBlockReleaseTicks = wasLog ? 5 : 2;
            return;
        }

        // 2. Jeśli trwa bufor odstępu puszczenia kliku po zniszczeniu lub zmianie celu:
        if (interBlockReleaseTicks > 0) {
            interBlockReleaseTicks--;
            return;
        }

        // Synchronizacja trzymanego narzędzia (eliminacja rozbieżności prędkości w GrimAC)
        ctx.playerController().syncHeldItem();

        // Bezpiecznik zacięcia na bloku (watchdog): jeśli kopiemy blok znacznie dłużej niż wymagany czas (ghost block / desync)
        if (currentBlockDamageTicks > Math.max(50, minRequiredBreakTicks + 30)) {
            ctx.playerController().resetBlockRemoving();
            lastBreakingPos = null;
            lastBreakingType = TargetType.NONE;
            currentBlockDamageTicks = 0;
            currentBlockBreakProgress = 0.0f;
            interBlockReleaseTicks = 2;
            return;
        }

        // 3. TICK 0: PIERWSZY KONTAKT Z NOWYM BLOKIEM
        // Wysyłamy WYŁĄCZNIE pakiet START_DESTROY_BLOCK (clickBlock).
        // NIE wywołujemy onPlayerDamageBlock w tym samym ticku, co całkowicie zapobiega flagom delay=0ms!
        if (lastBreakingPos == null) {
            lastBreakingPos = pos.immutable();
            lastBreakingType = currentTargetType;
            currentBlockDamageTicks = 0;
            currentBlockBreakProgress = 0.0f;

            float damagePerTick = targetState.getDestroyProgress(player, level, pos);
            minRequiredBreakTicks = (damagePerTick >= 1.0f) ? 1 : (int) Math.ceil(1.0f / Math.max(1e-4f, damagePerTick)) + 1;

            ctx.playerController().clickBlock(pos, safeSide);
            player.swing(InteractionHand.MAIN_HAND);
            return;
        }

        // 4. TICK 1+: CIĄGŁE NISZCZENIE BLOKU (continueDestroyBlock)
        currentBlockDamageTicks++;
        ctx.playerController().onPlayerDamageBlock(pos, safeSide);
        player.swing(InteractionHand.MAIN_HAND);

        // Śledzenie postępu niszczenia dla shaderów / HUD
        float delta = targetState.getDestroyProgress(player, level, pos);
        currentBlockBreakProgress = Math.min(1.0f, currentBlockBreakProgress + delta);
    }

    private void stopDigging() {
        if (lastBreakingPos != null && ctx.world() != null && ctx.world().getBlockState(lastBreakingPos).isAir()) {
            if (lastBreakingType == TargetType.LOG) {
                totalChoppedLogs++;
            } else if (lastBreakingType == TargetType.LEAF) {
                totalChoppedLeaves++;
            }
        }
        boolean wasLog = (lastBreakingType == TargetType.LOG);
        this.currentTargetPos = null;
        this.currentTargetType = TargetType.NONE;
        this.lastBreakingPos = null;
        this.lastBreakingType = TargetType.NONE;
        this.currentBlockBreakProgress = 0.0f;
        this.currentBlockDamageTicks = 0;
        this.interBlockReleaseTicks = wasLog ? 5 : 2;
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
        if (ctx.playerController() != null) {
            ctx.playerController().resetBlockRemoving();
        }
    }

    // =========================================================================
    // RAYCAST, REACH 3.9 & WYSZUKIWANIE CELÓW
    // =========================================================================

    private BlockHitResult findBestBreakableTarget(LocalPlayer player, Level level, boolean leavesMode) {
        Vec3 eyePos = player.getEyePosition();
        BlockPos eyeBlockPos = BlockPos.containing(eyePos);
        List<BlockHitResult> validHits = new ArrayList<>();

        int radius = 4;
        for (int dy = radius; dy >= -radius; dy--) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = eyeBlockPos.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);

                    if (leavesMode) {
                        if (!state.is(Blocks.SPRUCE_LEAVES)) {
                            continue;
                        }
                    } else {
                        if (!state.is(Blocks.SPRUCE_LOG) || !isWithin2x2TrunkColumn(pos)) {
                            continue;
                        }
                    }

                    // Sprawdzenie odległości Euklidesowej od oczu gracza
                    double distSq = eyePos.distanceToSqr(Vec3.atCenterOf(pos));
                    if (distSq > MAX_GRIM_REACH_SQ) {
                        continue;
                    }

                    BlockHitResult hit = raycastBlockVisibleFace(player, level, pos);
                    if (hit != null) {
                        validHits.add(hit);
                    }
                }
            }
        }

        if (validHits.isEmpty()) {
            return null;
        }

        if (leavesMode) {
            // Liście: najbliższy cel dla jak najmniejszego obrotu
            validHits.sort(Comparator.comparingDouble(h -> h.getLocation().distanceToSqr(eyePos)));
        } else {
            // Pień: bezwzględnie najwyższa warstwa Y w dół, aby gracz równo opadał
            validHits.sort(Comparator
                    .comparingInt((BlockHitResult h) -> -h.getBlockPos().getY())
                    .thenComparingDouble(h -> h.getLocation().distanceToSqr(eyePos)));
        }

        return validHits.getFirst();
    }

    private BlockHitResult raycastBlockVisibleFace(LocalPlayer player, Level level, BlockPos targetPos) {
        Vec3 eyePos = player.getEyePosition();

        // Jeśli głowa lub ciało gracza znajduje się wewnątrz tego samego bloku
        if (targetPos.equals(BlockPos.containing(eyePos)) || targetPos.equals(player.blockPosition())) {
            return new BlockHitResult(eyePos, Direction.UP, targetPos, false);
        }

        List<Vec3> samplePoints = new ArrayList<>(7);
        samplePoints.add(Vec3.atCenterOf(targetPos));

        for (Direction dir : Direction.values()) {
            samplePoints.add(Vec3.atCenterOf(targetPos).add(
                    dir.getStepX() * 0.48D,
                    dir.getStepY() * 0.48D,
                    dir.getStepZ() * 0.48D
            ));
        }

        for (Vec3 point : samplePoints) {
            if (eyePos.distanceToSqr(point) > MAX_GRIM_REACH_SQ) {
                continue;
            }
            ClipContext context = new ClipContext(
                    eyePos,
                    point,
                    ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE,
                    player
            );
            BlockHitResult result = level.clip(context);
            if (result.getType() == HitResult.Type.BLOCK
                    && result.getBlockPos().equals(targetPos)
                    && eyePos.distanceToSqr(result.getLocation()) <= MAX_GRIM_REACH_SQ) {
                return result;
            }
        }
        return null;
    }

    private BlockHitResult raycastTopFaceForPlacement(LocalPlayer player, Level level, BlockPos soilPos) {
        Vec3 eyePos = player.getEyePosition();
        double[] offsets = {0.5D, 0.35D, 0.65D};

        for (double ox : offsets) {
            for (double oz : offsets) {
                Vec3 topPoint = new Vec3(soilPos.getX() + ox, soilPos.getY() + 0.99D, soilPos.getZ() + oz);
                if (eyePos.distanceToSqr(topPoint) > MAX_GRIM_REACH_SQ) {
                    continue;
                }
                ClipContext context = new ClipContext(
                        eyePos,
                        topPoint,
                        ClipContext.Block.OUTLINE,
                        ClipContext.Fluid.NONE,
                        player
                );
                BlockHitResult hit = level.clip(context);
                if (hit.getType() == HitResult.Type.BLOCK
                        && hit.getBlockPos().equals(soilPos)
                        && hit.getDirection() == Direction.UP
                        && eyePos.distanceToSqr(hit.getLocation()) <= MAX_GRIM_REACH_SQ) {
                    return hit;
                }
            }
        }
        return null;
    }

    private void applyGcdRotation(LocalPlayer player, Vec3 targetVec) {
        Rotation targetRot = RotationUtils.calcRotationFromVec3d(
                player.getEyePosition(),
                targetVec,
                new Rotation(player.getYRot(), player.getXRot())
        );

        double sensitivity = Minecraft.getInstance().options.sensitivity().get();
        double f = sensitivity * 0.6D + 0.2D;
        double gcd = f * f * f * 8.0D * 0.15D;

        float currentYaw = player.getYRot();
        float currentPitch = player.getXRot();

        float deltaYaw = wrapDegrees(targetRot.getYaw() - currentYaw);
        float deltaPitch = targetRot.getPitch() - currentPitch;

        float quantizedYaw = currentYaw + (float) (Math.round(deltaYaw / gcd) * gcd);
        float quantizedPitch = Math.clamp(currentPitch + (float) (Math.round(deltaPitch / gcd) * gcd), -90.0F, 90.0F);

        baritone.getLookBehavior().updateTarget(new Rotation(quantizedYaw, quantizedPitch), true);
        player.setYRot(quantizedYaw);
        player.setXRot(quantizedPitch);
    }

    private static float wrapDegrees(float degrees) {
        float d = degrees % 360.0F;
        if (d >= 180.0F) d -= 360.0F;
        if (d < -180.0F) d += 360.0F;
        return d;
    }

    private void freezeHorizontalMovement() {
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
    }

    private void enforceDeadStop(LocalPlayer player) {
        baritone.getInputOverrideHandler().clearAllKeys();
        baritone.getLookBehavior().updateTarget(null, false);
        if (state == State.COOLDOWN_6S) {
            player.setYRot(lockedYaw);
            player.setXRot(lockedPitch);
        }
        player.setDeltaMovement(0.0D, player.getDeltaMovement().y, 0.0D);
    }

    // =========================================================================
    // ZARZĄDZANIE EKWIPUNKIEM
    // =========================================================================

    private boolean selectHoeOrWarn(LocalPlayer player, BlockState leafState) {
        AutoToolManager.selectBestTool(leafState);
        Inventory inv = player.getInventory();
        ItemStack held = inv.getItem(inv.selected);
        if (held.getDestroySpeed(leafState) > 1.0F) {
            return true;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getDestroySpeed(leafState) > 1.5F) {
                inv.selected = i;
                return true;
            }
        }
        // Brak dedykowanej motyki na hotbarze - nie przerywaj procesu, niszcz liście bieżącym narzędziem lub ręką
        return true;
    }

    private boolean selectAxeOrWarn(LocalPlayer player, BlockState logState) {
        AutoToolManager.selectBestTool(logState);
        Inventory inv = player.getInventory();
        ItemStack held = inv.getItem(inv.selected);
        if (held.getDestroySpeed(logState) > 1.5F) {
            return true;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getDestroySpeed(logState) > 1.5F) {
                inv.selected = i;
                return true;
            }
        }
        logDirect("§c[Astra/Spruce2x2] BŁĄD: Brak siekiery (Axe) na hotbarze!");
        return false;
    }

    private boolean selectItemFromHotbar(LocalPlayer player, Item targetItem) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.is(targetItem)) {
                inv.selected = i;
                return true;
            }
        }
        return false;
    }

    // =========================================================================
    // GEOMETRIA PNIA 2X2
    // =========================================================================

    private boolean is2x2SpruceTrunk(Level level, int x, int y, int z) {
        return level.getBlockState(new BlockPos(x, y, z)).is(Blocks.SPRUCE_LOG)
                && level.getBlockState(new BlockPos(x + 1, y, z)).is(Blocks.SPRUCE_LOG)
                && level.getBlockState(new BlockPos(x, y, z + 1)).is(Blocks.SPRUCE_LOG)
                && level.getBlockState(new BlockPos(x + 1, y, z + 1)).is(Blocks.SPRUCE_LOG);
    }

    public boolean isWithin2x2TrunkColumn(BlockPos pos) {
        return (pos.getX() == trunkMinX || pos.getX() == trunkMinX + 1)
                && (pos.getZ() == trunkMinZ || pos.getZ() == trunkMinZ + 1);
    }

    private boolean isStandingOnTreeOrLeaves(LocalPlayer player, Level level) {
        BlockPos below = BlockPos.containing(player.getX(), player.getY() - 0.15D, player.getZ());
        BlockState belowState = level.getBlockState(below);
        return belowState.is(Blocks.SPRUCE_LOG) || belowState.is(Blocks.SPRUCE_LEAVES);
    }

    private int findSoilBaseY(Level level, int minX, int startY, int minZ) {
        for (int y = startY; y >= startY - 45; y--) {
            Block b = level.getBlockState(new BlockPos(minX, y, minZ)).getBlock();
            if (isSoilBlock(b)) {
                return y;
            }
        }
        return startY - 32;
    }

    private boolean isSoilBlock(Block block) {
        return block == Blocks.PODZOL
                || block == Blocks.DIRT
                || block == Blocks.GRASS_BLOCK
                || block == Blocks.COARSE_DIRT
                || block == Blocks.ROOTED_DIRT
                || block == Blocks.MOSS_BLOCK
                || block == Blocks.MUD;
    }

    private boolean hasAnyRemainingTrunkLogsInReach(LocalPlayer player, Level level) {
        return findBestBreakableTarget(player, level, false) != null;
    }

    private List<BlockPos> get2x2SoilPositions() {
        return List.of(
                new BlockPos(trunkMinX, baseSoilY, trunkMinZ),
                new BlockPos(trunkMinX + 1, baseSoilY, trunkMinZ),
                new BlockPos(trunkMinX, baseSoilY, trunkMinZ + 1),
                new BlockPos(trunkMinX + 1, baseSoilY, trunkMinZ + 1)
        );
    }

    private void transitionTo(State nextState) {
        this.state = nextState;
        this.stateTicks = 0;
    }

    private PathingCommand cancelProcess() {
        stop();
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    @Override
    public void onLostControl() {
        stop();
    }

    @Override
    public String displayName0() {
        return "SpruceTree2x2 [" + state.name() + "]";
    }

    @Override
    public boolean isTemporary() {
        return false;
    }

    @Override
    public double priority() {
        return 2.5D;
    }

    // =========================================================================
    // SYNCHRONIZACJA WIZUALIZACJI 3D/2D (ZERO-GC)
    // =========================================================================

    private void updateVisuals(LocalPlayer player, Level level) {
        if (!active || player == null || level == null) {
            SpruceTreeVisuals.updateState(
                    false, SpruceTreeVisuals.FarmStatus.ANALIZA, false,
                    false, 0, 0, 0, 0,
                    false, 0, null,
                    0, 0, 0,
                    0, 150, 0
            );
            return;
        }

        SpruceTreeVisuals.FarmStatus status;
        switch (state) {
            case DETECT_TREE, DROP_ONTO_TREE -> status = SpruceTreeVisuals.FarmStatus.ANALIZA;
            case ZERO_WASD_HARVEST_LOOP -> status = SpruceTreeVisuals.FarmStatus.ZNIWA;
            case REPLANT_2X2, BONE_MEAL_SPAM -> status = SpruceTreeVisuals.FarmStatus.SADZENIE;
            default -> status = SpruceTreeVisuals.FarmStatus.ANALIZA;
        }

        boolean trunkFound = (topLogY > 0 && baseSoilY > 0);
        int goal = trunkFound ? Math.max(1, (topLogY - baseSoilY)) * 4 : 150;
        boolean isChopping = (currentTargetPos != null && currentTargetType == TargetType.LOG);

        SpruceTreeVisuals.updateState(
                true,
                status,
                isChopping,
                trunkFound, trunkMinX, trunkMinZ, baseSoilY, topLogY,
                trunkFound, // hasLeaves (widoczna korona wokół pnia)
                0, null,   // automatyczny 4-tarasowy profil schodkowy korony liści ze zrzutu
                trunkMinX + 1.0, topLogY + 3.4, trunkMinZ + 1.0,
                totalChoppedLogs, goal, totalChoppedLeaves
        );
    }

    private int getPodState(Level level, int x, int soilY, int z) {
        if (soilY <= 0) return 0;
        BlockPos above = new BlockPos(x, soilY + 1, z);
        if (level.getBlockState(above).is(Blocks.SPRUCE_SAPLING)) {
            return (state == State.BONE_MEAL_SPAM) ? 2 : 1;
        }
        return 0;
    }
}
