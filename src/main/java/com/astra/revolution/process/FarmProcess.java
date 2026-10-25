package com.astra.revolution.process;

import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.bypass.RotationEngine;
import com.astra.revolution.pathing.FarmPathOptimizer;
import com.astra.revolution.pathing.FarmPathOptimizer.HarvestTarget;
import com.astra.revolution.pathing.FarmPathOptimizer.OptimizedFarmRoute;
import com.astra.revolution.pathing.FarmPathOptimizer.TargetAction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Zoptymalizowany proces #farm (#praca) ze zbiorem w pełnym sprincie,
 * natychmiastowym replantem, ochroną gleby i integracją z SellProcess oraz HUD.
 */
public final class FarmProcess implements IBaritoneProcess {

    private static volatile FarmProcess instance;

    public static FarmProcess getInstance() {
        return instance;
    }

    private static final Set<Item> VALID_SEEDS = Set.of(
            Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO,
            Items.BEETROOT_SEEDS, Items.NETHER_WART
    );

    private static final double WAYPOINT_ADVANCE_RADIUS = 1.65;
    private static final int FIELD_RESCAN_INTERVAL_TICKS = 60;
    private static final long BLOCK_COOLDOWN_MS = 50L;

    private final IBaritone baritone;
    private final Minecraft mc;
    private final FarmPathOptimizer pathOptimizer;
    private final SellProcess sellProcess;

    private boolean active = false;
    private BlockPos originCenter = null;
    private int scanRadius = 48;

    private OptimizedFarmRoute currentRoute = null;
    private int currentWaypointIdx = 0;
    private int ticksSinceLastScan = 0;

    // Cooldown na interakcje z tym samym blokiem, zapobiegający spamowaniu pakietów w 1 ticku
    private final Map<BlockPos, Long> recentBlockInteractions = new HashMap<>();

    // Synchronizacja zmiany slotu hotbara (GrimAC 1-tick slot sync guard)
    private int lastSyncedHotbarSlot = -1;
    private int slotSwitchCooldownTicks = 0;

    // Synchronizacja rotacji i interakcji pod GrimAC (Argon 4)
    private BlockPos lockedTargetPos = null;
    private int ticksSyncedOnTarget = 0;
    private int actionCooldownTicks = 0;

    // Telemetria przekazywana do ModernCapsuleHud oraz BaritoneBypassScreen
    public record HudTelemetrySnapshot(
            String processStateLabel,
            String topologyMode,
            double cropsPerHour,
            double earningsPerHour,
            int sellCyclesCompleted,
            int currentWaypoint,
            int totalWaypoints
    ) {}

    private volatile HudTelemetrySnapshot latestTelemetry = new HudTelemetrySnapshot(
            "IDLE", "NONE", 0.0, 0.0, 0, 0, 0
    );

    public FarmProcess(IBaritone baritone, SellProcess sellProcess) {
        this.baritone = baritone;
        this.mc = Minecraft.getInstance();
        this.pathOptimizer = new FarmPathOptimizer();
        this.sellProcess = sellProcess;
        instance = this;
    }

    public void startFarming(BlockPos center, int radius) {
        this.originCenter = center != null ? center.immutable() : (mc.player != null ? mc.player.blockPosition() : BlockPos.ZERO);
        this.scanRadius = Mth.clamp(radius, 8, 128);
        this.active = true;
        this.currentRoute = null;
        this.currentWaypointIdx = 0;
        this.ticksSinceLastScan = FIELD_RESCAN_INTERVAL_TICKS;
    }

    public void stopFarming() {
        this.active = false;
        this.currentRoute = null;
        this.lockedTargetPos = null;
        this.ticksSyncedOnTarget = 0;
        this.actionCooldownTicks = 0;
        baritone.getInputOverrideHandler().clearAllKeys();
        updateHudTelemetry("IDLE");
    }

    @Override
    public boolean isActive() {
        return active && mc.player != null && mc.level != null && mc.getConnection() != null;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;

        if (player == null || level == null || mc.getConnection() == null) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // 1. Jeśli aktywny jest cykl #sell / #fill, deleguj sterowanie i aktualizuj HUD
        if (sellProcess.isActive()) {
            updateHudTelemetry(sellProcess.getCurrentState().getHudLabel());
            return new PathingCommand(null, PathingCommandType.DEFER);
        }

        // 2. Sprawdzenie zapełnienia ekwipunku -> automatyczny zapis Checkpointu i start SellProcess
        if (sellProcess.shouldTriggerAutonomousCycle(player)) {
            float forwardYaw = computeCurrentCorridorYaw(player);
            sellProcess.beginAutonomousCycle(player.blockPosition(), forwardYaw, currentWaypointIdx);
            updateHudTelemetry(sellProcess.getCurrentState().getHudLabel());
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (slotSwitchCooldownTicks > 0) {
            slotSwitchCooldownTicks--;
        }

        cleanupExpiredInteractions();

        // 3. Budowa lub odświeżenie trasy Boustrophedon / 2-Opt TSP
        ticksSinceLastScan++;
        if (currentRoute == null || currentRoute.isEmpty() || currentWaypointIdx >= currentRoute.spineWaypoints().size()) {
            List<BlockPos> fieldBlocks = scanFarmableFieldBlocks(level, originCenter, scanRadius, player.blockPosition().getY());
            if (fieldBlocks.isEmpty()) {
                baritone.getInputOverrideHandler().clearAllKeys();
                updateHudTelemetry("WAITING_GROWTH");
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }

            currentRoute = pathOptimizer.buildOptimalRoute(fieldBlocks, player.position(), player.getYRot());
            currentWaypointIdx = 0;
            ticksSinceLastScan = 0;
        }

        // 4. Wyznaczenie aktualnego punktu osiowego korytarza i postęp bez zatrzymywania
        List<BlockPos> spine = currentRoute.spineWaypoints();
        BlockPos targetWaypoint = spine.get(currentWaypointIdx);

        while (horizontalDistance(player.position(), Vec3.atBottomCenterOf(targetWaypoint)) <= WAYPOINT_ADVANCE_RADIUS
                && currentWaypointIdx < spine.size() - 1) {
            currentWaypointIdx++;
            targetWaypoint = spine.get(currentWaypointIdx);
        }

        float corridorYaw = computeYawTowards(player.position(), Vec3.atBottomCenterOf(targetWaypoint));

        if (actionCooldownTicks > 0) {
            actionCooldownTicks--;
        }

        // 5. Ciągły Zbiór i Natychmiastowy Replant w locie (Continuous Sprint-Harvest)
        Optional<HarvestTarget> inFlightTarget = pathOptimizer.findBestInFlightTarget(
                player,
                level,
                corridorYaw,
                hasSeedsAvailable(player.getInventory()),
                recentBlockInteractions.keySet()
        );

        if (inFlightTarget.isPresent()) {
            HarvestTarget target = inFlightTarget.get();

            if (!target.pos().equals(lockedTargetPos)) {
                lockedTargetPos = target.pos();
                ticksSyncedOnTarget = 0;
            }

            // 1. Obrót z kwantyzacją GCD (Argon 4)
            applyGrimGcdRotation(player, target.requiredYaw(), target.requiredPitch(), 0.55F);

            // 2. Weryfikacja rzeczywistego przecięcia promienia wzroku z blokiem
            BlockHitResult realHit = raycastTargetBlock(player, level, target);
            if (realHit != null) {
                ticksSyncedOnTarget++;
                // Natychmiastowe wykonanie akcji (0-tick delay, rotacja GCD jest już aktywna)
                if (actionCooldownTicks == 0) {
                    executeVerifiedAction(player, level, target, realHit);
                    ticksSyncedOnTarget = 0;
                }
            } else {
                ticksSyncedOnTarget = 0;
            }
        } else {
            lockedTargetPos = null;
            ticksSyncedOnTarget = 0;
            // Gdy brak plonów w bezpośrednim stożku, płynnie wyrównaj wzrok wzdłuż osi korytarza
            applyGrimGcdRotation(player, corridorYaw, 18.0F, 0.32F);
        }

        // 6. Sterowanie ciągłym ruchem + Ochrona Zasiewów (Sprint-Jump Farmland Guard)
        driveContinuousCorridorMovement(player, level, targetWaypoint);

        // Jeśli koniec trasy został osiągnięty, wymuś przeliczenie nowej ścieżki w kolejnym ticku
        if (currentWaypointIdx >= spine.size() - 1
                && horizontalDistance(player.position(), Vec3.atBottomCenterOf(targetWaypoint)) <= WAYPOINT_ADVANCE_RADIUS) {
            currentRoute = null;
        }

        updateHudTelemetry("FARMING");
        return new PathingCommand(new GoalNear(targetWaypoint, 1), PathingCommandType.SET_GOAL_AND_PATH);
    }

    /**
     * Wykonuje zbiór dojrzałego plonu lub natychmiastowe zasianie (immediateReplant) w biegu
     * wyłącznie przy użyciu zweryfikowanego przez raycast wektora BlockHitResult.
     */
    private void executeVerifiedAction(LocalPlayer player, ClientLevel level, HarvestTarget target, BlockHitResult verifiedHit) {
        if (mc.gameMode == null) {
            return;
        }

        long now = System.currentTimeMillis();

        if (target.action() == TargetAction.HARVEST_MATURE) {
            mc.gameMode.startDestroyBlock(verifiedHit.getBlockPos(), verifiedHit.getDirection());
            player.swing(InteractionHand.MAIN_HAND);
            recentBlockInteractions.put(target.pos(), now);
            sellProcess.recordHarvestedItem(1);

            // Natychmiastowy replant w TYM SAMYM TICKU na bloku gleby poniżej (Argon 4)
            BlockPos soilBelow = target.pos().below();
            BlockState soilState = level.getBlockState(soilBelow);
            if (soilState.getBlock() instanceof FarmBlock || soilState.is(Blocks.SOUL_SAND)) {
                InteractionHand seedHand = resolveSeedHand(player, soilState.is(Blocks.SOUL_SAND));
                if (seedHand != null) {
                    BlockHitResult soilHit = new BlockHitResult(
                            new Vec3(soilBelow.getX() + 0.5D, soilBelow.getY() + 0.9375D, soilBelow.getZ() + 0.5D),
                            Direction.UP, soilBelow, false);
                    mc.gameMode.useItemOn(player, seedHand, soilHit);
                    player.swing(seedHand);
                    recentBlockInteractions.put(soilBelow, now);
                }
            }
            actionCooldownTicks = 0;
            return;
        }

        if (target.action() == TargetAction.PLANT_SEED) {
            BlockState soilState = level.getBlockState(verifiedHit.getBlockPos());
            boolean isSoulSand = soilState.is(Blocks.SOUL_SAND);

            // Sprawdzenie, czy odpowiednie nasiona są już w Offhandzie
            InteractionHand seedHand = resolveSeedHand(player, isSoulSand);
            if (seedHand == null) {
                return; // Czekamy 1 tick na synchronizację zmiany slotu hotbara
            }

            mc.gameMode.useItemOn(player, seedHand, verifiedHit);
            player.swing(seedHand);
            recentBlockInteractions.put(verifiedHit.getBlockPos(), now);
            actionCooldownTicks = 0;
        }
    }

    /**
     * Wybiera rękę z nasionami lub przełącza slot w hotbarze z zachowaniem 1-tickowej
     * synchronizacji pakietu ServerboundSetCarriedItemPacket (ochrona przed GrimAC BadPackets).
     */
    private InteractionHand resolveSeedHand(LocalPlayer player, boolean requireNetherWart) {
        ItemStack offhand = player.getOffhandItem();
        if (isMatchingSeed(offhand, requireNetherWart)) {
            return InteractionHand.OFF_HAND;
        }

        Inventory inv = player.getInventory();
        ItemStack mainHand = player.getMainHandItem();
        if (isMatchingSeed(mainHand, requireNetherWart) && slotSwitchCooldownTicks == 0) {
            return InteractionHand.MAIN_HAND;
        }

        // Szukamy odpowiednich nasion w slotach hotbara [0..8]
        for (int slot = 0; slot < 9; slot++) {
            ItemStack candidate = inv.getItem(slot);
            if (isMatchingSeed(candidate, requireNetherWart)) {
                if (inv.selected != slot) {
                    inv.selected = slot;
                    if (player.connection != null && lastSyncedHotbarSlot != slot) {
                        player.connection.send(new ServerboundSetCarriedItemPacket(slot));
                        lastSyncedHotbarSlot = slot;
                    }
                    slotSwitchCooldownTicks = 1; // 1 tick bufora przed wysłaniem BlockPlace
                    return null;
                }
                return InteractionHand.MAIN_HAND;
            }
        }
        return null;
    }

    private boolean isMatchingSeed(ItemStack stack, boolean requireNetherWart) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (requireNetherWart) {
            return stack.is(Items.NETHER_WART);
        }
        return VALID_SEEDS.contains(stack.getItem()) && !stack.is(Items.NETHER_WART) && stack.getItem() instanceof BlockItem;
    }

    /**
     * Utrzymuje płynny ruch naprzód w sprincie i blokuje skakanie na blokach zaoranej ziemi (Farmland).
     */
    private void driveContinuousCorridorMovement(LocalPlayer player, ClientLevel level, BlockPos waypoint) {
        var inputHandler = baritone.getInputOverrideHandler();

        double dist = horizontalDistance(player.position(), Vec3.atBottomCenterOf(waypoint));
        if (dist > 0.35) {
            inputHandler.setInputForceState(Input.MOVE_FORWARD, true);
            inputHandler.setInputForceState(Input.SPRINT, player.getFoodData().getFoodLevel() > 6);
        }

        // Sprint-Jump Guard: całkowity zakaz skakania na Farmland oraz w odległości 1 kratki od niego
        if (isNearOrOnFarmland(player, level)) {
            inputHandler.setInputForceState(Input.JUMP, false);
            player.setJumping(false);
        }
    }

    private boolean isNearOrOnFarmland(LocalPlayer player, ClientLevel level) {
        BlockPos base = player.blockPosition();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 0; dy++) {
                    BlockState state = level.getBlockState(base.offset(dx, dy, dz));
                    if (state.getBlock() instanceof FarmBlock) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Błyskawiczna 1-tick rotacja zgodna z GrimAC GCD (rekomendacja Argon 4).
     */
    private void applyGrimGcdRotation(LocalPlayer player, float targetYaw, float targetPitch, float speedFactor) {
        if (player == null) return;
        Rotation cur = new Rotation(player.getYRot(), player.getXRot());
        Rotation next = RotationEngine.snapToGcd(cur, new Rotation(targetYaw, targetPitch));

        if (Math.abs(next.getYaw() - cur.getYaw()) > 1.0E-5F || Math.abs(next.getPitch() - cur.getPitch()) > 1.0E-5F) {
            player.setYRot(next.getYaw());
            player.setXRot(next.getPitch());
            player.setYHeadRot(next.getYaw());
        }
    }

    private BlockHitResult raycastTargetBlock(LocalPlayer player, ClientLevel level, HarvestTarget target) {
        double maxReach = 4.05D;
        Vec3 eyePos = player.getEyePosition(1.0F);
        Vec3 lookVec = player.getViewVector(1.0F);
        Vec3 endPos = eyePos.add(lookVec.scale(maxReach));

        BlockHitResult hit = level.clip(new ClipContext(
                eyePos,
                endPos,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player
        ));

        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }

        if (target.action() == TargetAction.HARVEST_MATURE) {
            if (hit.getBlockPos().equals(target.pos()) && eyePos.distanceToSqr(hit.getLocation()) <= (maxReach * maxReach)) {
                return hit;
            }
        } else if (target.action() == TargetAction.PLANT_SEED) {
            BlockPos expectedFarmland = target.pos();
            if (hit.getBlockPos().equals(expectedFarmland)
                    && hit.getDirection() == Direction.UP
                    && eyePos.distanceToSqr(hit.getLocation()) <= (maxReach * maxReach)) {
                return hit;
            }
        }
        return null;
    }

    private float computeMouseSensitivityGcd() {
        double sensitivity = mc.options.sensitivity().get();
        double f = sensitivity * 0.6 + 0.2;
        return (float) (f * f * f * 1.2);
    }

    private List<BlockPos> scanFarmableFieldBlocks(ClientLevel level, BlockPos center, int radius, int playerY) {
        List<BlockPos> result = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos pos = new BlockPos(center.getX() + dx, playerY + dy, center.getZ() + dz);
                    BlockState state = level.getBlockState(pos);
                    Block block = state.getBlock();

                    if (block instanceof CropBlock crop && crop.isMaxAge(state)) {
                        result.add(pos.immutable());
                    } else if (block instanceof NetherWartBlock && state.getValue(NetherWartBlock.AGE) >= 3) {
                        result.add(pos.immutable());
                    } else if ((block instanceof FarmBlock || block == Blocks.SOUL_SAND) && level.getBlockState(pos.above()).isAir()) {
                        result.add(pos.immutable());
                    }
                }
            }
        }
        return result;
    }

    private boolean hasSeedsAvailable(Inventory inv) {
        for (ItemStack s : inv.items) {
            if (!s.isEmpty() && VALID_SEEDS.contains(s.getItem())) {
                return true;
            }
        }
        for (ItemStack s : inv.offhand) {
            if (!s.isEmpty() && VALID_SEEDS.contains(s.getItem())) {
                return true;
            }
        }
        return false;
    }

    private void cleanupExpiredInteractions() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<BlockPos, Long>> it = recentBlockInteractions.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() > BLOCK_COOLDOWN_MS) {
                it.remove();
            }
        }
    }

    private float computeCurrentCorridorYaw(LocalPlayer player) {
        if (currentRoute != null && !currentRoute.isEmpty() && currentWaypointIdx < currentRoute.spineWaypoints().size()) {
            BlockPos wp = currentRoute.spineWaypoints().get(currentWaypointIdx);
            return computeYawTowards(player.position(), Vec3.atBottomCenterOf(wp));
        }
        return player.getYRot();
    }

    private float computeYawTowards(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return Mth.wrapDegrees((float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F);
    }

    private double horizontalDistance(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }

    private void updateHudTelemetry(String stateLabel) {
        String topology = (currentRoute != null) ? currentRoute.topology().name() : "SCANNING";
        int totalWp = (currentRoute != null) ? currentRoute.spineWaypoints().size() : 0;

        this.latestTelemetry = new HudTelemetrySnapshot(
                stateLabel,
                topology,
                sellProcess.getCropsPerHour(),
                sellProcess.getEarningsPerHour(),
                sellProcess.getCompletedSellCycles(),
                currentWaypointIdx,
                totalWp
        );
    }

    /**
     * Udostępnia niemutowalny snapshot telemetrii dla ModernCapsuleHud oraz BaritoneBypassScreen.
     */
    public HudTelemetrySnapshot getTelemetrySnapshot() {
        return latestTelemetry;
    }

    public SellProcess getSellProcess() {
        return sellProcess;
    }

    @Override
    public void onLostControl() {
        baritone.getInputOverrideHandler().clearAllKeys();
    }

    @Override
    public String displayName0() {
        return "AstraContinuousFarm [" + latestTelemetry.processStateLabel() + "]";
    }

    @Override
    public boolean isTemporary() {
        return false;
    }

    @Override
    public double priority() {
        return 2.0;
    }
}
