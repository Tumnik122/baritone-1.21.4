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
import baritone.utils.GrimBreakValidator;
import com.astra.revolution.visuals.SpruceTreeVisuals;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
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
    private long blockMiningStartTimeMs = 0L;
    private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap recentlyBrokenBlocks = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();

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
            // Fallback: jeśli drzewo zostało częściowo wycięte i został pojedynczy pień/kłoda (np. przy ziemi)
            for (int dy = 3; dy >= -15; dy--) {
                int y = playerPos.getY() + dy;
                for (int dx = -7; dx <= 7; dx++) {
                    for (int dz = -7; dz <= 7; dz++) {
                        BlockPos checkPos = new BlockPos(playerPos.getX() + dx, y, playerPos.getZ() + dz);
                        if (level.getBlockState(checkPos).is(Blocks.SPRUCE_LOG)) {
                            highestLogY = y;
                            foundMinX = checkPos.getX();
                            foundMinZ = checkPos.getZ();
                            break;
                        }
                    }
                    if (highestLogY != Integer.MIN_VALUE) break;
                }
                if (highestLogY != Integer.MIN_VALUE) break;
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

        // Jeśli gracz znajduje się już przy podstawie drzewa / na ziemi (np. po rozłączeniu / restarcie bota):
        if (player.getY() <= baseSoilY + 3.0D) {
            logDirect("§a[Astra/Spruce2x2] Gracz znajduje się już przy podstawie pnia -> bezpośrednie żniwa (ZERO_WASD_HARVEST_LOOP).");
            transitionTo(State.ZERO_WASD_HARVEST_LOOP);
        } else {
            transitionTo(State.DROP_ONTO_TREE);
        }
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
        boolean allTrunkLogsGone = areAllTrunkLogsChopped(level);

        if (reachedGround && allTrunkLogsGone && noRemainingLogs) {
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

        // Jeśli dotarliśmy w pobliże ziemi (baseSoilY + 2.5), priorytetem jest natychmiastowe ścięcie
        // pozostałości pnia 2x2, aby nie zostawiać ostatniej kłody na ziemi!
        boolean nearGround = (player.getY() <= baseSoilY + 2.5D);

        BlockHitResult primaryHit = nearGround
                ? findBestBreakableTarget(player, level, false)
                : findBestBreakableTarget(player, level, true);

        if (primaryHit != null) {
            BlockPos targetPos = primaryHit.getBlockPos();
            BlockState targetState = level.getBlockState(targetPos);
            boolean isLog = targetState.is(Blocks.SPRUCE_LOG);

            if (isLog) {
                if (!selectAxeOrWarn(player, targetState)) {
                    return cancelProcess();
                }
                this.currentTargetPos = targetPos;
                this.currentTargetType = TargetType.LOG;
            } else {
                selectHoeOrWarn(player, targetState);
                this.currentTargetPos = targetPos;
                this.currentTargetType = TargetType.LEAF;
            }

            applyGcdRotation(player, primaryHit.getLocation());
            startOrContinueBreaking(player, level, targetPos, primaryHit.getDirection());
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // Druga faza: cel alternatywny (drewno jeśli w powietrzu, liście jeśli na ziemi)
        BlockHitResult secondaryHit = nearGround
                ? findBestBreakableTarget(player, level, true)
                : findBestBreakableTarget(player, level, false);

        if (secondaryHit != null) {
            BlockPos targetPos = secondaryHit.getBlockPos();
            BlockState targetState = level.getBlockState(targetPos);
            boolean isLog = targetState.is(Blocks.SPRUCE_LOG);

            if (isLog) {
                if (!selectAxeOrWarn(player, targetState)) {
                    return cancelProcess();
                }
                this.currentTargetPos = targetPos;
                this.currentTargetType = TargetType.LOG;
            } else {
                selectHoeOrWarn(player, targetState);
                this.currentTargetPos = targetPos;
                this.currentTargetType = TargetType.LEAF;
            }

            applyGcdRotation(player, secondaryHit.getLocation());
            startOrContinueBreaking(player, level, targetPos, secondaryHit.getDirection());
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

        // 1. OCZYŚĆ WSZYSTKIE 4 POLA SADZENIA Z POZOSTAŁYCH KŁÓD I ZAWIESZONYCH LIŚCI
        for (BlockPos soilPos : soilPositions) {
            BlockPos saplingPos = soilPos.above();
            BlockState aboveState = level.getBlockState(saplingPos);

            if (!aboveState.isAir() && !aboveState.canBeReplaced() && !aboveState.is(Blocks.SPRUCE_SAPLING)) {
                BlockHitResult hit = raycastBlockVisibleFace(player, level, saplingPos);
                if (hit != null) {
                    if (aboveState.is(Blocks.SPRUCE_LOG)) {
                        selectAxeOrWarn(player, aboveState);
                        this.currentTargetType = TargetType.LOG;
                    } else {
                        selectHoeOrWarn(player, aboveState);
                        this.currentTargetType = TargetType.LEAF;
                    }
                    this.currentTargetPos = saplingPos;
                    applyGcdRotation(player, hit.getLocation());
                    startOrContinueBreaking(player, level, saplingPos, hit.getDirection());
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            }

            // Jeśli bezpośrednio nad polem sadzenia (Y+2) wisi liść lub kłoda blokująca wzrost drzewa 2x2:
            BlockPos above2 = saplingPos.above();
            BlockState above2State = level.getBlockState(above2);
            if (!above2State.isAir() && !above2State.canBeReplaced() && !above2State.is(Blocks.SPRUCE_SAPLING)) {
                BlockHitResult hit2 = raycastBlockVisibleFace(player, level, above2);
                if (hit2 != null) {
                    if (above2State.is(Blocks.SPRUCE_LOG)) {
                        selectAxeOrWarn(player, above2State);
                        this.currentTargetType = TargetType.LOG;
                    } else {
                        selectHoeOrWarn(player, above2State);
                        this.currentTargetType = TargetType.LEAF;
                    }
                    this.currentTargetPos = above2;
                    applyGcdRotation(player, hit2.getLocation());
                    startOrContinueBreaking(player, level, above2, hit2.getDirection());
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            }
        }

        // 2. SPRAWDŹ CZY KTÓREŚ POLE JEST BLOKOWANE PRZEZ HITBOX GRACZA
        boolean playerIntersecting = false;
        for (BlockPos soilPos : soilPositions) {
            BlockPos saplingPos = soilPos.above();
            if (player.getBoundingBox().intersects(saplingPos.getX(), saplingPos.getY(), saplingPos.getZ(),
                    saplingPos.getX() + 1, saplingPos.getY() + 1, saplingPos.getZ() + 1)) {
                playerIntersecting = true;
                break;
            }
        }
        if (playerIntersecting) {
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        } else {
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
        }

        // 3. POSADŹ SADZONKI NA WOLNYCH POLACH
        for (BlockPos soilPos : soilPositions) {
            BlockPos saplingPos = soilPos.above();
            BlockState aboveState = level.getBlockState(saplingPos);

            if (!aboveState.is(Blocks.SPRUCE_SAPLING) && (aboveState.isAir() || aboveState.canBeReplaced())) {
                if (!selectItemFromHotbar(player, Items.SPRUCE_SAPLING)) {
                    logDirect("§c[Astra/Spruce2x2] BŁĄD: Brak sadzonek świerku (spruce_sapling) na hotbarze! Zatrzymuję bota.");
                    return cancelProcess();
                }

                BlockHitResult topFaceHit = raycastTopFaceForPlacement(player, level, soilPos);
                if (topFaceHit == null) {
                    continue;
                }

                this.currentTargetPos = saplingPos;
                this.currentTargetType = TargetType.REPLANT;

                applyGcdRotation(player, topFaceHit.getLocation());

                Minecraft mc = Minecraft.getInstance();
                if (mc.gameMode != null) {
                    mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, topFaceHit);
                    player.swing(InteractionHand.MAIN_HAND);
                    placeDelayTicks = 1; // 1 tick odstępu pod GrimAC (50ms)
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }

        // 4. WERYFIKACJA CZY DOKŁADNIE WSZYSTKIE 4 SADZONKI ZOSTAŁY POSADZONE
        int plantedCount = 0;
        for (BlockPos soilPos : soilPositions) {
            BlockPos saplingPos = soilPos.above();
            if (level.getBlockState(saplingPos).is(Blocks.SPRUCE_SAPLING)) {
                plantedCount++;
            }
        }

        if (plantedCount == 4) {
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
            logDirect("§a[Astra/Spruce2x2] Posadzono 4/4 sadzonki 2x2. Rozpoczynam pędzenie mączką (BONE_MEAL_SPAM).");
            transitionTo(State.BONE_MEAL_SPAM);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // Jeśli posadzono mniej niż 4 (np. 2/4): zrób mikro-krok w tył, aby odsłonić pozostałe pola
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
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

        if (boneMealDelayTicks > 0) {
            boneMealDelayTicks--;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // Rotacja i kliknięcie mączką w tym samym ticku!
        applyGcdRotation(player, saplingHit.getLocation());

        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode != null) {
            mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, saplingHit);
            player.swing(InteractionHand.MAIN_HAND);
            boneMealDelayTicks = ThreadLocalRandom.current().nextInt(1, 3);
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
            recentlyBrokenBlocks.put(lastBreakingPos.asLong(), System.currentTimeMillis() + 3000L);
            lastBreakingPos = null;
            lastBreakingType = TargetType.NONE;
            currentBlockBreakProgress = 0.0f;
            currentBlockDamageTicks = 0;
            interBlockReleaseTicks = 0;
            ctx.playerController().resetBlockRemoving();
            // Brak return - natychmiastowe przejście do niszczenia kolejnego celu w tym samym ticku!
        }

        BlockState targetState = level.getBlockState(pos);
        if (targetState.isAir()) {
            stopDigging();
            return;
        }
        if (recentlyBrokenBlocks.containsKey(pos.asLong())) {
            if (targetState.is(Blocks.SPRUCE_LOG) || targetState.is(Blocks.SPRUCE_LEAVES)) {
                recentlyBrokenBlocks.remove(pos.asLong());
            } else {
                stopDigging();
                return;
            }
        }

        if (mc.gameMode == null) return;

        Direction safeSide = side != null ? side : Direction.UP;

        // 1. ZMIANA BLOKU (z pnia 1 na pień 2 lub z liścia na liść):
        // Błyskawiczny reset poprzedniego bloku bez sztucznego opóźnienia
        if (lastBreakingPos != null && !lastBreakingPos.equals(pos)) {
            ctx.playerController().resetBlockRemoving();
            lastBreakingPos = null;
            lastBreakingType = TargetType.NONE;
            currentBlockDamageTicks = 0;
            currentBlockBreakProgress = 0.0f;
            interBlockReleaseTicks = 0;
            // Brak return - kliknięcie w nowy blok po odczekaniu cooldownu
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
            interBlockReleaseTicks = 0;
            return;
        }

        // 3. TICK 0: PIERWSZY KONTAKT Z NOWYM BLOKIEM
        // Kliknięcie następuje po upłynięciu legalnego odstępu GrimAC (eliminacja FastBreak delay=38..56ms, 91ms, 199ms)
        if (lastBreakingPos == null) {
            if (!GrimBreakValidator.canStartNewBreak(player.tickCount)) {
                return;
            }

            lastBreakingPos = pos.immutable();
            lastBreakingType = currentTargetType;
            currentBlockDamageTicks = 0;
            currentBlockBreakProgress = 0.0f;
            blockMiningStartTimeMs = System.currentTimeMillis();

            int reqTicks = GrimBreakValidator.getRequiredWaitTicks(player, level, pos, targetState);
            minRequiredBreakTicks = (currentTargetType == TargetType.LOG) ? Math.max(6, reqTicks) : Math.max(4, reqTicks);

            // Pakiet START_DESTROY_BLOCK:
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
            recentlyBrokenBlocks.put(lastBreakingPos.asLong(), System.currentTimeMillis() + 3000L);
        }
        boolean wasLog = (lastBreakingType == TargetType.LOG);
        this.currentTargetPos = null;
        this.currentTargetType = TargetType.NONE;
        this.lastBreakingPos = null;
        this.lastBreakingType = TargetType.NONE;
        this.currentBlockBreakProgress = 0.0f;
        this.currentBlockDamageTicks = 0;
        this.interBlockReleaseTicks = 0;
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

        long now = System.currentTimeMillis();
        recentlyBrokenBlocks.long2LongEntrySet().removeIf(e -> e.getLongValue() < now);

        int radius = 4;
        for (int dy = radius; dy >= -radius; dy--) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = eyeBlockPos.offset(dx, dy, dz);

                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }

                    // Zabezpieczenie przed AirLiquidBreak oraz weryfikacja resyncu GrimAC:
                    // Jeśli serwer cofnął zniszczenie kłody lub liścia, zdejmij kwarantannę, aby nie zostawić wiszącego pnia!
                    if (recentlyBrokenBlocks.containsKey(pos.asLong())) {
                        if (state.is(Blocks.SPRUCE_LOG) || state.is(Blocks.SPRUCE_LEAVES)) {
                            recentlyBrokenBlocks.remove(pos.asLong());
                        } else {
                            continue;
                        }
                    }

                    if (leavesMode) {
                        if (!state.is(Blocks.SPRUCE_LEAVES)) {
                            continue;
                        }
                    } else {
                        // Pień: akceptujemy bloki pnia w kolumnie 2x2, a także wszelkie pnie bezpośrednio obok gracza na ziemi
                        if (!state.is(Blocks.SPRUCE_LOG) || (!isWithin2x2TrunkColumn(pos) && eyePos.distanceToSqr(Vec3.atCenterOf(pos)) > 6.0D)) {
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
            // Pień: warstwami od góry do dołu (-Y).
            // KRYTYCZNE (Argon 4 & dj): Kłoda, na której gracz stoi, MUSI być ścięta jako OSTATNIA w danej warstwie!
            // Ścięcie kłody pod stopami jako pierwszej powoduje natychmiastowe opadnięcie gracza i pozostawienie
            // pozostałych 3 kłód warstwy wiszących w powietrzu poza zasięgiem reach (3.9m)!
            BlockPos feetBlock = BlockPos.containing(player.getX(), player.getY() - 0.2D, player.getZ());
            validHits.sort(Comparator
                    .comparingInt((BlockHitResult h) -> -h.getBlockPos().getY())
                    .thenComparingInt((BlockHitResult h) -> {
                        BlockPos p = h.getBlockPos();
                        boolean underFeet = (p.getX() == feetBlock.getX() && p.getZ() == feetBlock.getZ());
                        return underFeet ? 1 : 0; // 0 (inne kłody warstwy) przed 1 (kłoda pod stopami)
                    })
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

        List<Vec3> samplePoints = new ArrayList<>(13);
        samplePoints.add(Vec3.atCenterOf(targetPos));

        for (Direction dir : Direction.values()) {
            samplePoints.add(Vec3.atCenterOf(targetPos).add(
                    dir.getStepX() * 0.46D,
                    dir.getStepY() * 0.46D,
                    dir.getStepZ() * 0.46D
            ));
        }

        // Dodatkowe punkty próbkowania na górnej powierzchni bloku
        samplePoints.add(new Vec3(targetPos.getX() + 0.5D, targetPos.getY() + 0.95D, targetPos.getZ() + 0.5D));
        samplePoints.add(new Vec3(targetPos.getX() + 0.25D, targetPos.getY() + 0.95D, targetPos.getZ() + 0.5D));
        samplePoints.add(new Vec3(targetPos.getX() + 0.75D, targetPos.getY() + 0.95D, targetPos.getZ() + 0.5D));
        samplePoints.add(new Vec3(targetPos.getX() + 0.5D, targetPos.getY() + 0.95D, targetPos.getZ() + 0.25D));
        samplePoints.add(new Vec3(targetPos.getX() + 0.5D, targetPos.getY() + 0.95D, targetPos.getZ() + 0.75D));

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

        // Bezpiecznik dla bloku tuż obok gracza (odległość <= 2.2m) - wybór odsłoniętej ściany
        if (eyePos.distanceToSqr(Vec3.atCenterOf(targetPos)) <= 5.0D) {
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = targetPos.relative(dir);
                BlockState nState = level.getBlockState(neighbor);
                if (nState.isAir() || nState.canBeReplaced()) {
                    Vec3 faceCenter = Vec3.atCenterOf(targetPos).add(dir.getStepX() * 0.5D, dir.getStepY() * 0.5D, dir.getStepZ() * 0.5D);
                    if (eyePos.distanceToSqr(faceCenter) <= MAX_GRIM_REACH_SQ) {
                        return new BlockHitResult(faceCenter, dir, targetPos, false);
                    }
                }
            }
        }

        return null;
    }

    private BlockHitResult raycastTopFaceForPlacement(LocalPlayer player, Level level, BlockPos soilPos) {
        Vec3 eyePos = player.getEyePosition();
        double[] offsets = {0.15D, 0.35D, 0.5D, 0.65D, 0.85D};

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
                        && eyePos.distanceToSqr(hit.getLocation()) <= MAX_GRIM_REACH_SQ) {
                    return new BlockHitResult(hit.getLocation(), Direction.UP, soilPos, false);
                }
            }
        }

        // Fallback: bezpośredni punkt centralny jeśli w zasięgu reach <= 3.9m
        Vec3 centerTop = new Vec3(soilPos.getX() + 0.5D, soilPos.getY() + 1.0D, soilPos.getZ() + 0.5D);
        if (eyePos.distanceToSqr(centerTop) <= MAX_GRIM_REACH_SQ) {
            return new BlockHitResult(centerTop, Direction.UP, soilPos, false);
        }
        return null;
    }

    private void applyGcdRotation(LocalPlayer player, Vec3 targetVec) {
        Vec3 eyePos = player.getEyePosition(1.0F);
        double dx = targetVec.x - eyePos.x;
        double dy = targetVec.y - eyePos.y;
        double dz = targetVec.z - eyePos.z;

        float targetYaw = (float) Mth.wrapDegrees(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float targetPitch = (float) Mth.wrapDegrees(-Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));

        double sensitivity = 0.5D;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options != null && mc.options.sensitivity() != null) {
            sensitivity = mc.options.sensitivity().get();
        }
        double f = sensitivity * 0.6D + 0.2D;
        double gcd = f * f * f * 8.0D * 0.15D;
        if (gcd < 1.0E-5D) gcd = 1.0E-5D;

        float currentYaw = player.getYRot();
        float currentPitch = player.getXRot();

        float deltaYaw = Mth.wrapDegrees(targetYaw - currentYaw);
        float clampedTargetPitch = Mth.clamp(targetPitch, -89.5F, 89.5F);
        float deltaPitch = clampedTargetPitch - currentPitch;

        // Nuker Fast (żądanie dj): wysoka prędkość obrotu (do 180°/tick) z precyzyjną kwantyzacją GCD (GrimAC bypass)
        float maxSpeed = 180.0F;
        deltaYaw = Mth.clamp(deltaYaw, -maxSpeed, maxSpeed);
        deltaPitch = Mth.clamp(deltaPitch, -maxSpeed, maxSpeed);

        int kYaw = (int) Math.round(deltaYaw / gcd);
        int kPitch = (int) Math.round(deltaPitch / gcd);

        // Ochrona przed AimDuplicateLook: jeśli kąt jest w progu GCD, nie wykonuj zbędnych mikro-skoków
        if (kYaw == 0 && kPitch == 0) {
            baritone.getLookBehavior().updateTarget(new Rotation(currentYaw, currentPitch), true);
            return;
        }

        float fastYaw = (float) (currentYaw + kYaw * gcd);
        float fastPitch = (float) (currentPitch + kPitch * gcd);

        while (fastPitch > 89.5F) {
            kPitch--;
            fastPitch = (float) (currentPitch + kPitch * gcd);
        }
        while (fastPitch < -89.5F) {
            kPitch++;
            fastPitch = (float) (currentPitch + kPitch * gcd);
        }

        player.setYRot(fastYaw);
        player.setXRot(fastPitch);
        player.yRotO = fastYaw;
        player.xRotO = fastPitch;

        baritone.getLookBehavior().updateTarget(new Rotation(fastYaw, fastPitch), true);
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
        int logCount = 0;
        if (level.getBlockState(new BlockPos(x, y, z)).is(Blocks.SPRUCE_LOG)) logCount++;
        if (level.getBlockState(new BlockPos(x + 1, y, z)).is(Blocks.SPRUCE_LOG)) logCount++;
        if (level.getBlockState(new BlockPos(x, y, z + 1)).is(Blocks.SPRUCE_LOG)) logCount++;
        if (level.getBlockState(new BlockPos(x + 1, y, z + 1)).is(Blocks.SPRUCE_LOG)) logCount++;
        return logCount >= 2;
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
            if (isSoilBlock(level.getBlockState(new BlockPos(minX, y, minZ)).getBlock())
                    || isSoilBlock(level.getBlockState(new BlockPos(minX + 1, y, minZ)).getBlock())
                    || isSoilBlock(level.getBlockState(new BlockPos(minX, y, minZ + 1)).getBlock())
                    || isSoilBlock(level.getBlockState(new BlockPos(minX + 1, y, minZ + 1)).getBlock())) {
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

    private boolean areAllTrunkLogsChopped(Level level) {
        if (baseSoilY <= 0) return true;
        int maxY = topLogY > baseSoilY ? topLogY : (baseSoilY + 35);
        for (BlockPos soilPos : get2x2SoilPositions()) {
            for (int y = baseSoilY + 1; y <= maxY; y++) {
                BlockPos p = new BlockPos(soilPos.getX(), y, soilPos.getZ());
                if (level.getBlockState(p).is(Blocks.SPRUCE_LOG)) {
                    return false;
                }
            }
        }
        return true;
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
