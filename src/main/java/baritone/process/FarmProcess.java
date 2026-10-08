/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.IFarmProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.api.utils.interfaces.IGoalRenderPos;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.player.BaritonePlayerController;
import baritone.bypass.RotationEngine;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

public final class FarmProcess extends BaritoneProcessHelper implements IFarmProcess {

    // ─────────────────────────────────────────────────────────────────────────
    // STAŁE ROTACJI I SPRAWDZANIA KĄTÓW (Pkt 1)
    // ─────────────────────────────────────────────────────────────────────────
    private static final float MAX_YAW_STEP = 100.0F;
    private static final float MAX_PITCH_STEP = 70.0F;

    // ─────────────────────────────────────────────────────────────────────────
    // POLA STANU
    // ─────────────────────────────────────────────────────────────────────────
    private boolean active;
    private boolean plantOnly = false;
    private volatile List<BlockPos> locations;
    private volatile int scanGeneration;
    private int tickCount;
    private int scanCooldownTicks;
    private int range;
    private BlockPos center;

    // Osobne cele dla zbioru i sadzenia (Pkt 5)
    private BlockPos currentTargetPos = null;
    private int targetTicks = 0;
    private BlockPos currentPlantTargetPos = null;
    private int plantTargetTicks = 0;

    private BlockPos immediateReplantPos = null;
    private int replantAttemptTicks = 0;
    private Item replantItem = null;

    // Blacklista na longach (pos.asLong()) oraz licznik prób odrzuceń (Pkt 7)
    private final Long2LongOpenHashMap blacklistUntil = new Long2LongOpenHashMap();
    private final Long2IntOpenHashMap attempts = new Long2IntOpenHashMap();
    private final Int2LongOpenHashMap itemBlacklist = new Int2LongOpenHashMap();

    private final AtomicBoolean isScanning = new AtomicBoolean(false);

    private BlockPos lastPlayerPos = null;
    private int idleStuckTicks = 0;

    // Ticki procesu zamiast zegara ściennego (Pkt 4)
    private long farmTicks = 0L;
    private long lastProgressTick = 0L;
    private int lastFarmedItemCount = 0;
    private int teleportCooldownTicks = 0;
    private BlockPos preTeleportPos = null;
    private int teleportRetries = 0;

    private int emptyGoalTicks = 0;
    private int waterTicks = 0;
    private boolean pendingCalcFailed = false;
    private int lastNearestDropId = -1;

    // ── Statystyki pszenicy (globalne — dostępne z FarmCommand i HomeProcess) ──
    private static long totalWheatHarvested = 0L; // sumarycznie zebrana pszenica (sztuki)
    private static long farmSessionStartMs = 0L; // czas startu aktualnej sesji farmienia
    private static boolean sessionRunning = false;

    // ── Tryb #praca (eksperymentalny) ──
    private static boolean pracaMode = false;
    private static int pracaHarvestableCount = 0; // liczba dojrzałych roślin w promieniu 100 bloków
    private static int pracaGroundDropsCount = 0; // liczba itemów na ziemi do zebrania
    private static final int PRACA_HARVEST_THRESHOLD = 200; // próg: poniżej tej liczby -> priorytet itemów
    private static final int PRACA_RADIUS = 100;           // promień skanowania

    private int currentDropTargetId = -1;
    private int dropPickupWaitTicks = 0;
    private boolean pracaGroundFirstState = false; // histereza trybu zbierania z ziemi

    public static boolean isPracaModeActive() {
        return pracaMode || Baritone.settings().farmCollectDropsWhenLowCrops.value;
    }
    public static void setPracaMode(boolean v) {
        pracaMode = v;
    }
    public static int getPracaHarvestableCount() { return pracaHarvestableCount; }
    public static int getPracaGroundDropsCount() { return pracaGroundDropsCount; }

    // Zapamiętane opcje użytkownika (Pkt 10)
    private final List<Block> addedDisallowed = new ArrayList<>();
    private boolean prevMineAvoidWater;
    private boolean prevAllowBreak;
    // [FIX A] true = farm() zmieniła globalne ustawienia i onLostControl() musi je
    // przywrócić.
    // cancelEverything()/#stop woła onLostControl() na KAŻDYM procesie (także
    // nieaktywnym), więc bez tej
    // flagi prevAllowBreak (domyślnie false w Javie) wyłączałby kopanie w całym
    // Baritone.
    private boolean settingsApplied = false;

    // [FIX D] Watchdog braku postępu — niezależny od typu celu (patrz sekcje 4b i
    // 13)
    private static final int WATCHDOG_TICKS = 120; // 6 s
    private BlockPos wdAnchor = null;
    private int wdSig = 0;
    private long wdSinceTick = 0L;
    private int wdLevel = 0;

    // Bufor kandydatów i skanera (Pkt 5 i 14)
    private final List<BlockPos> candidates = new ArrayList<>(64);
    private final BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos up = new BlockPos.MutableBlockPos();
    private final LongOpenHashSet toBreakSet = new LongOpenHashSet();
    private final LongOpenHashSet farmlandSet = new LongOpenHashSet();

    // ── Persistentna kolejka tras (fix zakrzywionych ścieżek GoalComposite) ──
    // Zamiast dawać 20 celów naraz (A* zakrzywia trasę do centroidu),
    // podajemy dokładnie 1 cel + 1 fallback. Kolejka jest odświeżana gdy pusta.
    private final java.util.ArrayDeque<BlockPos> harvestQueue = new java.util.ArrayDeque<>(64);
    private final java.util.ArrayDeque<BlockPos> plantQueue   = new java.util.ArrayDeque<>(64);
    private int queueVersion = 0; // inkrementowany przy każdym nowym scanie, żeby unieważnić starą kolejkę

    private static final Set<Item> FARMLAND_PLANTABLE = new HashSet<>(Arrays.asList(
            Items.WHEAT_SEEDS,
            Items.CARROT,
            Items.POTATO,
            Items.BEETROOT_SEEDS,
            Items.MELON_SEEDS,
            Items.PUMPKIN_SEEDS,
            Items.TORCHFLOWER_SEEDS,
            Items.PITCHER_POD));

    private static final Set<Item> PICKUP_DROPPED = new HashSet<>(Arrays.asList(
            Items.WHEAT_SEEDS,
            Items.WHEAT,
            Items.CARROT,
            Items.POTATO,
            Items.POISONOUS_POTATO,
            Items.BEETROOT_SEEDS,
            Items.BEETROOT,
            Items.MELON_SEEDS,
            Items.MELON_SLICE,
            Blocks.MELON.asItem(),
            Items.PUMPKIN_SEEDS,
            Blocks.PUMPKIN.asItem(),
            Items.NETHER_WART,
            Items.COCOA_BEANS,
            Blocks.SUGAR_CANE.asItem(),
            Blocks.BAMBOO.asItem(),
            Blocks.CACTUS.asItem(),
            Items.SWEET_BERRIES,
            Items.GLOW_BERRIES,
            Items.TORCHFLOWER_SEEDS,
            Items.TORCHFLOWER,
            Items.APPLE));

    private static final List<Block> FARM_SOIL_BLOCKS = Arrays.asList(
            Blocks.FARMLAND,
            Blocks.DIRT,
            Blocks.GRASS_BLOCK,
            Blocks.DIRT_PATH,
            Blocks.COARSE_DIRT,
            Blocks.ROOTED_DIRT,
            Blocks.MUD,
            Blocks.MUDDY_MANGROVE_ROOTS,
            Blocks.PODZOL,
            Blocks.MYCELIUM,
            Blocks.SOUL_SAND,
            Blocks.SOUL_SOIL,
            Blocks.SAND,
            Blocks.RED_SAND,
            Blocks.COMPOSTER,
            Blocks.HAY_BLOCK);

    public FarmProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public void farm(int range, BlockPos pos) {
        this.plantOnly = false;
        pracaMode = false;
        pracaGroundFirstState = false;
        if (pos == null) {
            center = baritone.getPlayerContext().playerFeet();
        } else {
            center = pos;
        }
        this.range = range;

        active = true;
        locations = null;
        scanGeneration++;
        scanCooldownTicks = 0;
        currentTargetPos = null;
        targetTicks = 0;
        currentPlantTargetPos = null;
        plantTargetTicks = 0;
        immediateReplantPos = null;
        replantAttemptTicks = 0;
        replantItem = null;
        blacklistUntil.clear();
        attempts.clear();
        itemBlacklist.clear();
        lastPlayerPos = null;
        idleStuckTicks = 0;
        farmTicks = 0L;
        lastProgressTick = 0L;
        lastFarmedItemCount = countFarmedItemsInInventory();
        teleportCooldownTicks = 0;
        preTeleportPos = null;
        teleportRetries = 0;
        emptyGoalTicks = 0;
        waterTicks = 0;
        pendingCalcFailed = false;
        lastNearestDropId = -1;
        wdAnchor = null;
        wdSig = 0;
        wdSinceTick = 0L;
        wdLevel = 0;
        harvestQueue.clear();
        plantQueue.clear();
        queueVersion++;
        // Start statystyk pszenicy
        if (!sessionRunning) {
            farmSessionStartMs = System.currentTimeMillis();
            sessionRunning = true;
        }

        // Pkt 10: Zapamiętaj i przywracaj ustawienia
        // [FIX A] Zapamiętujemy je TYLKO przy pierwszej aktywacji. Ponowne farm() przy
        // aktywnej farmie
        // (np. drugie #farm) zapamiętałoby już zmienione wartości i allowBreak=false
        // zostałoby na stałe;
        // addedDisallowed.clear() zgubiłoby listę bloków do późniejszego usunięcia.
        if (!settingsApplied) {
            prevMineAvoidWater = Baritone.settings().mineAvoidWater.value;
            prevAllowBreak = Baritone.settings().allowBreak.value;
            addedDisallowed.clear();
            settingsApplied = true;
        }
        Baritone.settings().mineAvoidWater.value = true;
        // Wyłącz globalne kopanie przez pather — farma tłucze plony sama przez
        // FarmProcess
        // (rotacja + clickBlock). Pather z allowBreak=false nie będzie kopał żadnych
        // bloków
        // strukturalnych (deepslate, cobblestone itp.) na drodze do celu.
        Baritone.settings().allowBreak.value = false;
        List<Block> disallowed = Baritone.settings().blocksToDisallowBreaking.value;
        for (Block b : FARM_SOIL_BLOCKS) {
            if (!disallowed.contains(b)) {
                disallowed.add(b);
                addedDisallowed.add(b);
            }
        }
        if (!disallowed.contains(Blocks.WATER)) {
            disallowed.add(Blocks.WATER);
            addedDisallowed.add(Blocks.WATER);
        }
    }

    @Override
    public void plant(int range, BlockPos pos) {
        farm(range, pos);
        this.plantOnly = true;
    }

    @Override
    public boolean isPlantOnly() {
        return plantOnly;
    }

    @Override
    public void farmPraca(int range, BlockPos pos) {
        farm(range, pos);
        pracaMode = true;
    }


    private enum Harvest {
        WHEAT((CropBlock) Blocks.WHEAT),
        CARROTS((CropBlock) Blocks.CARROTS),
        POTATOES((CropBlock) Blocks.POTATOES),
        BEETROOT((CropBlock) Blocks.BEETROOTS),
        TORCHFLOWER((CropBlock) Blocks.TORCHFLOWER_CROP),
        PUMPKIN(Blocks.PUMPKIN, state -> true),
        MELON(Blocks.MELON, state -> true),
        NETHERWART(Blocks.NETHER_WART, state -> state.getValue(NetherWartBlock.AGE) >= 3),
        COCOA(Blocks.COCOA, state -> state.getValue(CocoaBlock.AGE) >= 2),
        SUGARCANE(Blocks.SUGAR_CANE, null) {
            @Override
            public boolean readyToHarvest(Level world, BlockPos pos, BlockState state) {
                if (Baritone.settings().replantCrops.value) {
                    return world.getBlockState(pos.below()).getBlock() instanceof SugarCaneBlock;
                }
                return true;
            }
        },
        BAMBOO(Blocks.BAMBOO, null) {
            @Override
            public boolean readyToHarvest(Level world, BlockPos pos, BlockState state) {
                if (Baritone.settings().replantCrops.value) {
                    return world.getBlockState(pos.below()).getBlock() instanceof BambooStalkBlock;
                }
                return true;
            }
        },
        CACTUS(Blocks.CACTUS, null) {
            @Override
            public boolean readyToHarvest(Level world, BlockPos pos, BlockState state) {
                if (Baritone.settings().replantCrops.value) {
                    return world.getBlockState(pos.below()).getBlock() instanceof CactusBlock;
                }
                return true;
            }
        };

        public final Block block;
        public final Predicate<BlockState> readyToHarvest;

        Harvest(CropBlock blockCrops) {
            this(blockCrops, blockCrops::isMaxAge);
        }

        Harvest(Block block, Predicate<BlockState> readyToHarvest) {
            this.block = block;
            this.readyToHarvest = readyToHarvest;
        }

        public boolean readyToHarvest(Level world, BlockPos pos, BlockState state) {
            return readyToHarvest.test(state);
        }
    }

    // Pkt 14: Mapa stała bez klonowania Harvest.values() co wywołanie
    private static final Map<Block, Harvest> HARVEST_BY_BLOCK = new IdentityHashMap<>();
    static {
        for (Harvest h : Harvest.values()) {
            HARVEST_BY_BLOCK.put(h.block, h);
        }
    }

    private boolean readyForHarvest(Level world, BlockPos pos, BlockState state) {
        if (Baritone.settings().farmWheatOnly.value || pracaMode) {
            return state.getBlock() == Blocks.WHEAT && ((CropBlock) Blocks.WHEAT).isMaxAge(state);
        }
        Harvest h = HARVEST_BY_BLOCK.get(state.getBlock());
        return h != null && h.readyToHarvest(world, pos, state);
    }

    private static Item seedFor(Block crop) {
        if (crop == Blocks.WHEAT)
            return Items.WHEAT_SEEDS;
        if (crop == Blocks.CARROTS)
            return Items.CARROT;
        if (crop == Blocks.POTATOES)
            return Items.POTATO;
        if (crop == Blocks.BEETROOTS)
            return Items.BEETROOT_SEEDS;
        if (crop == Blocks.TORCHFLOWER_CROP)
            return Items.TORCHFLOWER_SEEDS;
        if (crop == Blocks.PITCHER_CROP)
            return Items.PITCHER_POD;
        return null;
    }

    private boolean isReplantItem(ItemStack s) {
        return replantItem != null && s != null && !s.isEmpty() && s.getItem() == replantItem;
    }

    private boolean isPlantable(ItemStack stack) {
        if (stack == null || stack.isEmpty())
            return false;
        if (Baritone.settings().farmWheatOnly.value || pracaMode) {
            return stack.getItem() == Items.WHEAT_SEEDS;
        }
        return FARMLAND_PLANTABLE.contains(stack.getItem());
    }

    private boolean hasPlantableSeeds() {
        if (ctx.player() == null)
            return false;
        if (replantItem != null && baritone.getInventoryBehavior().throwaway(false, this::isReplantItem, true)) {
            return true;
        }
        return baritone.getInventoryBehavior().throwaway(false, this::isPlantable, true);
    }

    private boolean isBoneMeal(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem().equals(Items.BONE_MEAL);
    }

    private boolean isNetherWart(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem().equals(Items.NETHER_WART);
    }

    private boolean isCocoa(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem().equals(Items.COCOA_BEANS);
    }

    private int countFarmedItemsInInventory() {
        if (ctx.player() == null || ctx.player().getInventory() == null) {
            return 0;
        }
        int total = 0;
        for (ItemStack stack : ctx.player().getInventory().items) {
            if (stack != null && !stack.isEmpty() && PICKUP_DROPPED.contains(stack.getItem())) {
                total += stack.getCount();
            }
        }
        for (ItemStack stack : ctx.player().getInventory().offhand) {
            if (stack != null && !stack.isEmpty() && PICKUP_DROPPED.contains(stack.getItem())) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private boolean isInventoryFull() {
        return ctx.player().getInventory().getFreeSlot() == -1;
    }

    private boolean hasRoomFor(ItemStack drop) {
        Inventory inv = ctx.player().getInventory();
        if (inv.getFreeSlot() != -1)
            return true;
        for (ItemStack s : inv.items) {
            if (ItemStack.isSameItemSameComponents(s, drop) && s.getCount() < s.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERY ROTACJI (Pkt 1)
    // ─────────────────────────────────────────────────────────────────────────
    private boolean isBehind(BlockPos pos) {
        Vec3 head = ctx.playerHead();
        double dx = pos.getX() + 0.5D - head.x;
        double dz = pos.getZ() + 0.5D - head.z;
        if (dx * dx + dz * dz < 0.64D) {
            return false; // yaw nieokreślony tuż pod nami – nigdy nie odrzucaj
        }
        float yaw = (float) (Mth.atan2(dz, dx) * 57.29577951308232D) - 90.0F;
        return Math.abs(Mth.wrapDegrees(yaw - ctx.playerRotations().getYaw())) > 85.0F;
    }

    private Rotation limitedTurn(Rotation target) {
        Rotation cur = ctx.playerRotations();
        if (!Baritone.settings().antiCheatCompat.value) {
            return target;
        }
        float dYaw = Mth.wrapDegrees(target.getYaw() - cur.getYaw());
        float dPitch = target.getPitch() - cur.getPitch();

        // Błyskawiczna rotacja celująca (do 120° yaw i 75° pitch na tick) z zachowaniem
        // pełnej siatki GCD GrimAC
        float maxTurnYaw = 120.0F;
        float maxTurnPitch = 75.0F;
        float stepYaw = Mth.clamp(dYaw, -maxTurnYaw, maxTurnYaw);
        float stepPitch = Mth.clamp(dPitch, -maxTurnPitch, maxTurnPitch);

        double gcd = RotationEngine.getGcd();
        double qYaw = RotationEngine.quantizeToGcd(stepYaw, gcd);
        double qPitch = RotationEngine.quantizeToGcd(stepPitch, gcd);

        float newYaw = (float) (cur.getYaw() + qYaw);
        float newPitch = (float) Mth.clamp(cur.getPitch() + qPitch, -89.5D, 89.5D);

        return new Rotation(newYaw, newPitch);
    }

    private void applyAimRotation(Rotation targetRot, boolean fastMode) {
        if (fastMode) {
            // Delegate entirely to LookBehavior so only one rotation packet
            // is sent per tick through the normal game loop.
            // Sending an extra ServerboundMovePlayerPacket.Rot here caused
            // GrimAC TickTimer(flying, packets=2) and AimDuplicateLook flags.
            baritone.getLookBehavior().updateTarget(targetRot, true);
        } else {
            baritone.getLookBehavior().updateTarget(limitedTurn(targetRot), true);
        }
    }

    private double aimScore(BlockPos p) {
        if (p.equals(currentTargetPos))
            return -10000.0D;
        Vec3 head = ctx.playerHead();
        // Odległość jako dominujący czynnik — zawsze zbieramy najbliższą pszenicę
        double distSq = head.distanceToSqr(p.getX() + 0.5D, p.getY() + 0.5D, p.getZ() + 0.5D);
        Rotation to = RotationUtils.calcRotationFromVec3d(head, Vec3.atCenterOf(p), ctx.playerRotations());
        double yaw = Math.abs(Mth.wrapDegrees(to.getYaw() - ctx.playerRotations().getYaw()));
        double pitch = Math.abs(to.getPitch() - ctx.playerRotations().getPitch());
        // Mały bonus za kąt, ale odległość zawsze dominuje
        return distSq + (isBehind(p) ? 4.0D : (yaw / 180.0D) * 2.0D) + (pitch / 90.0D) * 1.0D;
    }

    private double plantAimScore(BlockPos p) {
        if (p.equals(currentPlantTargetPos))
            return -10000.0D;
        Vec3 head = ctx.playerHead();
        double distSq = head.distanceToSqr(p.getX() + 0.5D, p.getY() + 0.5D, p.getZ() + 0.5D);
        Rotation to = RotationUtils.calcRotationFromVec3d(head, Vec3.atCenterOf(p), ctx.playerRotations());
        double yaw = Math.abs(Mth.wrapDegrees(to.getYaw() - ctx.playerRotations().getYaw()));
        double pitch = Math.abs(to.getPitch() - ctx.playerRotations().getPitch());
        return distSq + (isBehind(p) ? 3.0D : (yaw / 180.0D) * 1.5D) + (pitch / 90.0D) * 0.8D;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERY BLACKLISTY (Pkt 7)
    // ─────────────────────────────────────────────────────────────────────────
    private boolean isBlacklisted(BlockPos p) {
        return blacklistUntil.get(p.asLong()) > farmTicks;
    }

    private void blacklistFor(BlockPos p, int ticks) {
        blacklistUntil.put(p.asLong(), farmTicks + ticks);
    }

    private boolean tooManyAttempts(BlockPos p) {
        long k = p.asLong();
        int n = attempts.get(k) + 1;
        attempts.put(k, n);
        if (n >= 8) {
            attempts.remove(k);
            blacklistFor(p, 160); // 8 sekund ochrony przed desyncem
            return true;
        }
        return false;
    }

    /**
     * Filtruje listę celów pod kątem odległości od gracza z zachowaniem priorytetu bliskich bloków.
     * Zapobiega planowaniu dalekich tras (np. 80 bloków dalej), gdy tuż obok gracza rośnie dojrzała pszenica.
     */
    private List<BlockPos> getNearbyValidBlocks(List<BlockPos> source, BetterBlockPos playerPos, int maxDistance) {
        if (source.isEmpty()) {
            return Collections.emptyList();
        }
        double maxDistSqr = (double) maxDistance * maxDistance;
        List<BlockPos> nearby = new ArrayList<>();
        List<BlockPos> allValid = new ArrayList<>();
        for (BlockPos pos : source) {
            if (!isBlacklisted(pos)) {
                allValid.add(pos);
                if (playerPos.distSqr(pos) <= maxDistSqr) {
                    nearby.add(pos);
                }
            }
        }
        if (!nearby.isEmpty()) {
            return nearby;
        }
        double medDistSqr = 48.0D * 48.0D;
        List<BlockPos> medium = new ArrayList<>();
        for (BlockPos pos : allValid) {
            if (playerPos.distSqr(pos) <= medDistSqr) {
                medium.add(pos);
            }
        }
        if (!medium.isEmpty()) {
            return medium;
        }
        return allValid;
    }

    /**
     * Optymalizuje trasę zbiorów/sadzenia wzdłuż naturalnych rzędów pola (Strip
     * Farming / Serpentine TSP).
     * Eliminuje nawroty o 180° i chaotyczne skakanie po losowych klockach wokół
     * gracza.
     */
    private List<BlockPos> buildOptimizedRoute(List<BlockPos> source, BetterBlockPos playerPos, int maxCount) {
        if (source.isEmpty()) {
            return Collections.emptyList();
        }
        if (source.size() <= 1) {
            return source;
        }

        List<BlockPos> pool = new ArrayList<>(source);
        List<BlockPos> route = new ArrayList<>(Math.min(maxCount, pool.size()));

        Vec3 look = ctx.player() != null ? ctx.player().getLookAngle() : new Vec3(1, 0, 0);
        double dirX = look.x;
        double dirZ = look.z;
        double lLen = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (lLen > 0.001D) {
            dirX /= lLen;
            dirZ /= lLen;
        } else {
            dirX = 1.0D;
            dirZ = 0.0D;
        }

        BlockPos current = null;
        double bestFirstScore = Double.MAX_VALUE;
        int pX = playerPos.getX();
        int pY = playerPos.getY();
        int pZ = playerPos.getZ();

        for (BlockPos p : pool) {
            double dx = p.getX() - pX;
            double dy = p.getY() - pY;
            double dz = p.getZ() - pZ;
            double distSqr = dx * dx + dy * dy * 4.0D + dz * dz;

            double distH = Math.sqrt(dx * dx + dz * dz);
            double dot = (distH > 0.1D) ? (dx * dirX + dz * dirZ) / distH : 1.0D;
            double score = distSqr + (1.0D - dot) * 4.0D;

            if (score < bestFirstScore) {
                bestFirstScore = score;
                current = p;
            }
        }

        if (current == null) {
            current = pool.get(0);
        }

        route.add(current);
        pool.remove(current);

        double lastStepX = current.getX() - pX;
        double lastStepZ = current.getZ() - pZ;
        double sLen = Math.sqrt(lastStepX * lastStepX + lastStepZ * lastStepZ);
        if (sLen > 0.001D) {
            lastStepX /= sLen;
            lastStepZ /= sLen;
        } else {
            lastStepX = dirX;
            lastStepZ = dirZ;
        }

        while (!pool.isEmpty() && route.size() < maxCount) {
            BlockPos next = null;
            double bestScore = Double.MAX_VALUE;
            int cX = current.getX();
            int cY = current.getY();
            int cZ = current.getZ();

            for (BlockPos candidate : pool) {
                double dx = candidate.getX() - cX;
                double dy = candidate.getY() - cY;
                double dz = candidate.getZ() - cZ;
                double distSqr = dx * dx + dy * dy * 6.0D + dz * dz;

                double stepLen = Math.sqrt(dx * dx + dz * dz);
                double alignment = (stepLen > 0.001D) ? (dx * lastStepX + dz * lastStepZ) / stepLen : 1.0D;

                double penalty = (alignment < -0.1D) ? (Math.abs(alignment) * 10.0D) : 0.0D;
                double score = distSqr + penalty;

                if (score < bestScore) {
                    bestScore = score;
                    next = candidate;
                }
            }

            if (next == null)
                break;

            double dx = next.getX() - current.getX();
            double dz = next.getZ() - current.getZ();
            double nsLen = Math.sqrt(dx * dx + dz * dz);
            if (nsLen > 0.001D) {
                lastStepX = dx / nsLen;
                lastStepZ = dz / nsLen;
            }

            route.add(next);
            pool.remove(next);
            current = next;
        }

        return route;
    }

    /**
     * Dedykowany cel Baritone pod zbiory i sadzenie na farmie.
     * Zmierza bezpośrednio do bloku uprawy/grządki (GoalGetToBlock),
     * dzięki czemu bot idzie wzdłuż ścieżki i podchodzi do każdej uprawy po kolei,
     * nie zatrzymując się przedwcześnie w odległości 2 bloków.
     */
    public static final class GoalFarmTarget extends GoalGetToBlock {
        public GoalFarmTarget(BlockPos pos) {
            super(pos);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            if (y > this.y) {
                return false;
            }
            return super.isInGoal(x, y, z);
        }
    }

    /**
     * Dedykowany cel do podnoszenia leżących przedmiotów na farmie.
     * Wymaga wejścia w ten sam słupek X,Z (na poziomie podłogi lub o 1 niżej na farmlandzie),
     * dzięki czemu hitbox gracza przecina item i natychmiast go zbiera bez zatrzymywania się obok.
     */
    public static final class GoalPickupItem implements Goal, IGoalRenderPos {
        private final BlockPos pos;

        public GoalPickupItem(BlockPos pos) {
            this.pos = pos;
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            return x == pos.getX() && z == pos.getZ() && (y == pos.getY() || y == pos.getY() - 1);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            int xDiff = x - pos.getX();
            int yDiff = y - pos.getY();
            int zDiff = z - pos.getZ();
            return GoalBlock.calculate(xDiff, yDiff, zDiff);
        }

        @Override
        public BlockPos getGoalPos() {
            return pos;
        }

        @Override
        public String toString() {
            return "GoalPickupItem{" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + "}";
        }
    }

    /**
     * Inteligentny wybór celów wzdłuż rzędów (Lane Traversal):
     * - Jeśli gracz idzie wzdłuż rzędu, priorytetyzuje plony przed nim (dist >= 1.5 i dot > 0.25),
     *   dzięki czemu bot biegnie sprintem bez zatrzymywania się na każdym klocku.
     * - Plony pod stopami są zbierane automatycznie w biegu w sekcji 7.
     * - Gdy rząd się skończy, wybiera początek kolejnej alei z minimalizacją ostrego nawrotu.
     */
    private List<BlockPos> selectOptimizedHarvestGoals(List<BlockPos> source, BetterBlockPos playerPos) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }

        List<BlockPos> nearby = new ArrayList<>();
        double maxDistSq = 32.0D * 32.0D;
        for (BlockPos p : source) {
            if (!isBlacklisted(p) && playerPos.distSqr(p) <= maxDistSq) {
                nearby.add(p);
            }
        }
        if (nearby.isEmpty()) {
            for (BlockPos p : source) {
                if (!isBlacklisted(p)) nearby.add(p);
            }
            if (nearby.isEmpty()) return Collections.emptyList();
        }

        Vec3 delta = ctx.player() != null ? ctx.player().getDeltaMovement() : Vec3.ZERO;
        double dirX = delta.x;
        double dirZ = delta.z;
        double speedSq = dirX * dirX + dirZ * dirZ;
        if (speedSq > 0.003D) {
            double len = Math.sqrt(speedSq);
            dirX /= len;
            dirZ /= len;
        } else {
            Vec3 look = ctx.player() != null ? ctx.player().getLookAngle() : new Vec3(1, 0, 0);
            dirX = look.x;
            dirZ = look.z;
            double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
            if (len > 0.001D) {
                dirX /= len;
                dirZ /= len;
            } else {
                dirX = 1.0D;
                dirZ = 0.0D;
            }
        }

        final double forwardX = dirX;
        final double forwardZ = dirZ;

        List<BlockPos> forwardCrops = new ArrayList<>();
        for (BlockPos p : nearby) {
            double dx = p.getX() + 0.5D - playerPos.getX();
            double dz = p.getZ() + 0.5D - playerPos.getZ();
            double distSq = dx * dx + dz * dz;
            if (distSq >= 2.25D) {
                double dist = Math.sqrt(distSq);
                double dot = (dx * forwardX + dz * forwardZ) / dist;
                if (dot > 0.25D) {
                    forwardCrops.add(p);
                }
            }
        }

        List<BlockPos> selected = new ArrayList<>(3);
        if (!forwardCrops.isEmpty()) {
            forwardCrops.sort(Comparator.comparingDouble(p -> {
                double dx = p.getX() + 0.5D - playerPos.getX();
                double dz = p.getZ() + 0.5D - playerPos.getZ();
                double distSq = dx * dx + dz * dz;
                double dist = Math.sqrt(distSq);
                double dot = (dx * forwardX + dz * forwardZ) / dist;
                return distSq - (dot * 16.0D);
            }));

            selected.add(forwardCrops.get(0));
            for (int i = 1; i < forwardCrops.size(); i++) {
                BlockPos p = forwardCrops.get(i);
                if (p.distSqr(selected.get(0)) >= 4.0D) {
                    selected.add(p);
                    break;
                }
            }
        } else {
            nearby.sort(Comparator.comparingDouble(p -> {
                double dx = p.getX() + 0.5D - playerPos.getX();
                double dz = p.getZ() + 0.5D - playerPos.getZ();
                double distSq = dx * dx + dz * dz;
                double dist = Math.sqrt(distSq);
                double dot = (dist > 0.1D) ? (dx * forwardX + dz * forwardZ) / dist : 1.0D;
                double turnPenalty = (dot < -0.5D) ? 8.0D : 0.0D;
                return distSq + turnPenalty;
            }));

            int count = Math.min(2, nearby.size());
            for (int i = 0; i < count; i++) {
                selected.add(nearby.get(i));
            }
        }

        return selected;
    }

    private boolean canSafelyJump() {
        return ctx.player().onGround() && !ctx.player().isInWater()
                && !ctx.world().getBlockState(ctx.playerFeet().below()).is(Blocks.FARMLAND);
    }

    private void sendFailsafeCommand() {
        String cmd = Baritone.settings().farmNoGainCommand.value;
        if (cmd == null || cmd.isEmpty())
            return;
        if (ctx.player() != null && ctx.player().connection != null) {
            ctx.player().connection.sendCommand(cmd.startsWith("/") ? cmd.substring(1) : cmd);
        }
    }

    // [FIX D] Krótki opis celów do logu watchdoga (które cele istnieją i w którym z
    // nich stoi bot)
    private String describeGoals(List<Goal> goalz, BetterBlockPos playerPos) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Goal g : goalz) {
            if (shown == 4) {
                sb.append(" …+").append(goalz.size() - 4);
                break;
            }
            if (shown++ > 0)
                sb.append(", ");
            sb.append(g);
            if (g.isInGoal(playerPos.x, playerPos.y, playerPos.z))
                sb.append(" [BOT W CELU]");
        }
        return sb.length() == 0 ? "brak" : sb.toString();
    }

    private void resetWorkState() {
        locations = null;
        scanGeneration++;
        scanCooldownTicks = 0;
        blacklistUntil.clear();
        attempts.clear();
        itemBlacklist.clear();
        currentTargetPos = null;
        targetTicks = 0;
        currentPlantTargetPos = null;
        plantTargetTicks = 0;
        immediateReplantPos = null;
        replantAttemptTicks = 0;
        replantItem = null;
        lastPlayerPos = null;
        idleStuckTicks = 0;
        harvestQueue.clear();
        plantQueue.clear();
        queueVersion++;
        wdAnchor = null;
        wdSig = 0;
        wdSinceTick = farmTicks;
        // wdLevel celowo NIE jest zerowany — eskalacja watchdoga ma przeżyć reset stanu
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GŁÓWNA METODA TICKU
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        try {
            return onTickInternal(calcFailed, isSafeToCancel);
        } catch (Throwable t) {
            logDirect("§c[Farm ERROR] Nieoczekiwany błąd w onTick: " + t.getMessage());
            t.printStackTrace();
            onLostControl();
            baritone.getPathingBehavior().cancelEverything();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
    }

    private PathingCommand onTickInternal(boolean calcFailed, boolean isSafeToCancel) {
        farmTicks++;
        pendingCalcFailed |= calcFailed;

        // Pkt 7: Cykliczny pruning blacklisty
        if (farmTicks % 200 == 0) {
            blacklistUntil.long2LongEntrySet().removeIf(e -> e.getLongValue() <= farmTicks);
            attempts.clear();
            itemBlacklist.int2LongEntrySet().removeIf(e -> e.getLongValue() <= farmTicks);
        }

        // ─────────────────────────────────────────────────────────────────
        // 0. TELEPORT COOLDOWN I WERYFIKACJA (Pkt 4)
        // ─────────────────────────────────────────────────────────────────
        if (teleportCooldownTicks > 0) {
            baritone.getPathingBehavior().softCancelIfSafe();
            baritone.getInputOverrideHandler().clearAllKeys();
            if (--teleportCooldownTicks == 0) {
                if (preTeleportPos != null && ctx.playerFeet().distSqr(preTeleportPos) > 64.0D) {
                    teleportRetries = 0;
                    center = ctx.playerFeet();
                    resetWorkState();
                    lastFarmedItemCount = countFarmedItemsInInventory();
                    lastProgressTick = farmTicks;
                    logDirect("§a[Farm] Pomyślnie przeniesiono na /home 2! Wznawiam farmę.");
                } else if (++teleportRetries <= 3) {
                    logDirect("§e[Farm] Teleport nie nastąpił (" + teleportRetries + "/3), ponawiam komendę.");
                    sendFailsafeCommand();
                    teleportCooldownTicks = 120;
                } else {
                    logDirect("§c[Farm] Teleport nieudany po 3 próbach – zatrzymuję proces.");
                    baritone.getPathingBehavior().cancelEverything();
                    onLostControl();
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // ─────────────────────────────────────────────────────────────────
        // 1. ASYNCHRONICZNE SKANOWANIE ŚWIATA (Pkt 9)
        // ─────────────────────────────────────────────────────────────────
        if (scanCooldownTicks > 0) {
            scanCooldownTicks--;
        }
        boolean needScan = locations == null
                || (scanCooldownTicks == 0 && (++tickCount % 40 == 0 || locations.size() < 10));

        if (needScan && isScanning.compareAndSet(false, true)) {
            scanCooldownTicks = 20;
            final int gen = scanGeneration;
            final int maxSize = Baritone.settings().farmMaxScanSize.value;
            final int effectiveRange = range > 0 ? range : 160;
            final int chunkRadius = Math.max(10, Math.min(32, (effectiveRange + 15) / 16));
            final int effectiveMaxSize = Math.max(maxSize, 16384);

            final List<Block> scan = new ArrayList<>();
            if (!plantOnly) {
                if (Baritone.settings().farmWheatOnly.value || pracaMode) {
                    scan.add(Blocks.WHEAT);
                } else {
                    for (Harvest harvest : Harvest.values()) {
                        scan.add(harvest.block);
                    }
                }
            } else {
                scan.add(Blocks.FARMLAND);
                if (!Baritone.settings().farmWheatOnly.value && !pracaMode) {
                    scan.add(Blocks.JUNGLE_LOG);
                    if (Baritone.settings().replantNetherWart.value) {
                        scan.add(Blocks.SOUL_SAND);
                    }
                }
            }

            Baritone.getExecutor().execute(() -> {
                try {
                    List<BlockPos> res = BaritoneAPI.getProvider().getWorldScanner()
                            .scanChunkRadius(ctx, scan, effectiveMaxSize, 10, chunkRadius);
                    if (res != null && gen == scanGeneration && active) {
                        locations = new ArrayList<>(res);
                    }
                } catch (Exception ignored) {
                } finally {
                    isScanning.set(false);
                }
            });
        }

        if (locations == null) {
            locations = new ArrayList<>();
        }
        List<BlockPos> locationsSnapshot = locations;

        // ─────────────────────────────────────────────────────────────────
        // 2. BUDOWANIE LIST CELÓW Z CACHE'OWANYM STANEM EQ (Pkt 14)
        // ─────────────────────────────────────────────────────────────────
        final boolean haveBoneMeal = baritone.getInventoryBehavior().throwaway(false, this::isBoneMeal);
        final boolean haveWart = baritone.getInventoryBehavior().throwaway(false, this::isNetherWart);
        final boolean haveCocoa = baritone.getInventoryBehavior().throwaway(false, this::isCocoa);
        final boolean haveSeeds = hasPlantableSeeds();

        List<BlockPos> toBreak = new ArrayList<>();
        List<BlockPos> openFarmland = new ArrayList<>();
        List<BlockPos> bonemealable = new ArrayList<>();
        List<BlockPos> openSoulsand = new ArrayList<>();
        List<BlockPos> openLog = new ArrayList<>();

        toBreakSet.clear();
        farmlandSet.clear();

        for (BlockPos pos : locationsSnapshot) {
            if (range != 0 && pos.distSqr(center) > range * range) {
                continue;
            }

            BlockState state = ctx.world().getBlockState(pos);
            boolean airAbove = ctx.world().getBlockState(pos.above()).isAir();
            if (state.getBlock() == Blocks.FARMLAND) {
                if (airAbove) {
                    openFarmland.add(pos);
                    farmlandSet.add(pos.asLong());
                }
                continue;
            }
            if (state.getBlock() == Blocks.SOUL_SAND) {
                if (airAbove) {
                    openSoulsand.add(pos);
                }
                continue;
            }
            if (state.getBlock() == Blocks.JUNGLE_LOG) {
                for (Direction direction : Direction.Plane.HORIZONTAL) {
                    if (ctx.world().getBlockState(pos.relative(direction)).getBlock() instanceof AirBlock) {
                        openLog.add(pos);
                        break;
                    }
                }
                continue;
            }
            if (!plantOnly && readyForHarvest(ctx.world(), pos, state)) {
                toBreak.add(pos);
                toBreakSet.add(pos.asLong());
                continue;
            }
            if (haveBoneMeal && state.getBlock() instanceof BonemealableBlock ig) {
                if (ig.isValidBonemealTarget(ctx.world(), pos, state)
                        && ig.isBonemealSuccess(ctx.world(), ctx.world().random, pos, state)) {
                    bonemealable.add(pos);
                }
            }
        }

        BetterBlockPos playerPos = ctx.playerFeet();

        // ─────────────────────────────────────────────────────────────────
        // 3. LOKALNY SKAN 360° BEZ ALOKACJI (Pkt 14)
        // ─────────────────────────────────────────────────────────────────
        int pX = playerPos.getX();
        int pY = playerPos.getY();
        int pZ = playerPos.getZ();
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    mut.set(pX + dx, pY + dy, pZ + dz);
                    if (range != 0 && mut.distSqr(center) > (double) range * range)
                        continue;
                    BlockState st = ctx.world().getBlockState(mut);
                    if (!plantOnly && readyForHarvest(ctx.world(), mut, st)) {
                        if (toBreakSet.add(mut.asLong()))
                            toBreak.add(mut.immutable());
                    } else if (st.is(Blocks.FARMLAND)) {
                        up.set(mut.getX(), mut.getY() + 1, mut.getZ());
                        if (ctx.world().getBlockState(up).isAir() && farmlandSet.add(mut.asLong())) {
                            openFarmland.add(mut.immutable());
                        }
                    }
                }
            }
        }

        // ── Tryb #praca / farmLowCrop: zlicz dojrzałe plony w promieniu skanowania (domyślnie 100 bloków) ──
        int rCrop = Baritone.settings().farmLowCropRadius.value;
        double rCropSqr = (double) rCrop * rCrop;
        int harvestableInRadius = 0;
        for (BlockPos p : toBreak) {
            if (playerPos.distSqr(p) <= rCropSqr) {
                harvestableInRadius++;
            }
        }
        pracaHarvestableCount = harvestableInRadius;

        // ─────────────────────────────────────────────────────────────────
        // 4. TEST PRZYROSTU PLONÓW / FAILSAFE /home 2 (Pkt 4)
        // ─────────────────────────────────────────────────────────────────
        final boolean hasWork = (!plantOnly && !toBreak.isEmpty())
                || (haveSeeds && !openFarmland.isEmpty())
                || (haveWart && !openSoulsand.isEmpty())
                || (haveCocoa && !openLog.isEmpty())
                || (pracaGroundDropsCount > 0);
        final boolean inRange = range == 0 || playerPos.distSqr(center) <= (double) range * range;

        final int farmed = countFarmedItemsInInventory();
        if (farmed > lastFarmedItemCount) {
            lastProgressTick = farmTicks;
        }
        lastFarmedItemCount = farmed;

        // Czekanie na wzrost roślin w rejonie lub pełny ekwipunek to nie awaria
        if ((!hasWork && inRange) || isInventoryFull()) {
            lastProgressTick = farmTicks;
            if (plantOnly && !haveSeeds && farmTicks % 120 == 0) {
                logDirect("§e[Sadzenie] Oczekiwanie na nasiona w ekwipunku...");
            }
        }

        int timeoutSec = Baritone.settings().farmNoGainTimeoutSeconds.value;
        if (timeoutSec > 0 && (farmTicks - lastProgressTick) > (timeoutSec * 20L)) {
            logDirect("§c[Farm] Brak postępu przez " + (timeoutSec / 60) + " min – awaryjny powrót.");
            preTeleportPos = ctx.playerFeet();
            sendFailsafeCommand();
            teleportCooldownTicks = 100;
            lastProgressTick = farmTicks;
            // [FIX B] NIE cancelEverything(): ono woła FarmProcess.onLostControl() →
            // active=false oraz
            // teleportCooldownTicks=0, czyli farma sama się wyłączała zamiast wznowić po
            // teleporcie.
            baritone.getPathingBehavior().softCancelIfSafe();
            baritone.getInputOverrideHandler().clearAllKeys();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // ─────────────────────────────────────────────────────────────────
        // 4b. [FIX D] POSTĘP DLA WATCHDOGA: ruch ≥3 bloki albo zmiana liczby
        // przedmiotów w EQ
        // (pełny ekwipunek też resetuje watchdog — tak samo jak w failsafe powyżej to
        // nie awaria)
        // ─────────────────────────────────────────────────────────────────
        if (wdAnchor == null) {
            wdAnchor = playerPos;
            wdSig = farmed;
            wdSinceTick = farmTicks;
        } else if (playerPos.distSqr(wdAnchor) >= 9.0D || farmed != wdSig || isInventoryFull()) {
            wdAnchor = playerPos;
            wdSig = farmed;
            wdSinceTick = farmTicks;
            wdLevel = 0;
        }

        // ─────────────────────────────────────────────────────────────────
        // 5. BEZPIECZNA UCIECZKA Z WODY (Pkt 8)
        // ─────────────────────────────────────────────────────────────────
        if (ctx.player().isInWater()) {
            if (++waterTicks <= 60) {
                baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                BlockPos feet = ctx.playerFeet();
                BlockPos escapePos = null;
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockPos check = feet.relative(dir);
                    if (ctx.world().getFluidState(check).isEmpty() && !ctx.world().getBlockState(check).isAir()) {
                        BlockPos checkUp = check.above();
                        if (ctx.world().getBlockState(checkUp).getCollisionShape(ctx.world(), checkUp).isEmpty()) {
                            escapePos = check;
                            break;
                        }
                    }
                }
                if (escapePos != null) {
                    Rotation escapeRot = RotationUtils.calcRotationFromVec3d(
                            ctx.playerHead(),
                            Vec3.atCenterOf(escapePos).add(0, 0.5, 0),
                            ctx.playerRotations());
                    baritone.getLookBehavior().updateTarget(new Rotation(escapeRot.getYaw(), -10.0F), true);
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            } else if (waterTicks > 100) {
                logDirect("§c[Farm] Utknięcie w wodzie – wykonuję awaryjny powrót /home 2!");
                preTeleportPos = ctx.playerFeet();
                sendFailsafeCommand();
                waterTicks = 0;
                teleportCooldownTicks = 100;
                // [FIX B] j.w. — bez cancelEverything(), bo wyłącza farmę
                baritone.getPathingBehavior().softCancelIfSafe();
                baritone.getInputOverrideHandler().clearAllKeys();
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        } else {
            waterTicks = 0;
        }

        baritone.getInputOverrideHandler().clearAllKeys();

        double blockReachDistance = Math.min(3.9, ctx.playerController().getBlockReachDistance());
        final Vec3 head = ctx.playerHead();
        final double reach2 = blockReachDistance * blockReachDistance;
        boolean handledNearbyAction = false;
        boolean acted = false; // Pkt 5: Maksymalnie 1 interakcja pakietowa na tick!

        // ─────────────────────────────────────────────────────────────────
        // 6. NATYCHMIASTOWY REPLANT NA KLOCKU (Pkt 2: Gwarantowane natychmiastowe
        // zasianie)
        // ─────────────────────────────────────────────────────────────────
        if (immediateReplantPos != null && Baritone.settings().replantCrops.value) {
            if (++replantAttemptTicks > 15) {
                immediateReplantPos = null;
                replantAttemptTicks = 0;
            } else {
                BlockState aboveState = ctx.world().getBlockState(immediateReplantPos.above());
                Block soilBlock = ctx.world().getBlockState(immediateReplantPos).getBlock();
                boolean isFarmland = (soilBlock == Blocks.FARMLAND);
                boolean isSoulSand = (soilBlock == Blocks.SOUL_SAND && Baritone.settings().replantNetherWart.value);

                // Jeśli już rośnie tu młoda roślina (np. wiek 0) — zasianie zakończone
                // sukcesem!
                if (aboveState.getBlock() instanceof CropBlock crop && !crop.isMaxAge(aboveState)) {
                    immediateReplantPos = null;
                    replantAttemptTicks = 0;
                } else if (aboveState.getBlock() == Blocks.NETHER_WART
                        && aboveState.getValue(NetherWartBlock.AGE) < 3) {
                    immediateReplantPos = null;
                    replantAttemptTicks = 0;
                } else if (!aboveState.isAir() && !(aboveState.getBlock() instanceof CropBlock)
                        && !(aboveState.getBlock() instanceof NetherWartBlock)) {
                    // Blok nad grządką to przeszkoda (nie powietrze i nie stara roślina czekająca
                    // na usunięcie)
                    immediateReplantPos = null;
                    replantAttemptTicks = 0;
                } else if ((isFarmland && hasPlantableSeeds())
                        || (isSoulSand && baritone.getInventoryBehavior().throwaway(false, this::isNetherWart))) {
                    double topY = isFarmland ? 0.9375D : 1.0D;
                    Vec3 targetOffset = new Vec3(
                            immediateReplantPos.getX() + 0.5D,
                            immediateReplantPos.getY() + topY,
                            immediateReplantPos.getZ() + 0.5D);
                    Optional<Rotation> rot = RotationUtils.reachableOffset(
                            ctx, immediateReplantPos, targetOffset, blockReachDistance, false);
                    if (!rot.isPresent()) {
                        rot = RotationUtils.reachable(ctx, immediateReplantPos, blockReachDistance);
                    }
                    if (!rot.isPresent()) {
                        if (head.distanceToSqr(targetOffset) <= reach2) {
                            rot = Optional
                                    .of(RotationUtils.calcRotationFromVec3d(head, targetOffset, ctx.playerRotations()));
                        }
                    }
                    if (rot.isPresent()) {
                        final boolean fastMode = Baritone.settings().farmFastMode.value;
                        applyAimRotation(rot.get(), fastMode);

                        boolean selected;
                        if (isSoulSand) {
                            selected = baritone.getInventoryBehavior().throwaway(true, this::isNetherWart, true);
                        } else {
                            selected = (replantItem != null
                                    && baritone.getInventoryBehavior().throwaway(true, this::isReplantItem, true))
                                    || baritone.getInventoryBehavior().throwaway(true, this::isPlantable, true);
                        }

                        if (selected) {
                            ctx.playerController().syncHeldItem();
                        }

                        if (selected && (fastMode || ctx.isLookingAt(immediateReplantPos))) {
                            BlockHitResult hit = new BlockHitResult(targetOffset, Direction.UP, immediateReplantPos,
                                    false);
                            InteractionResult res = ctx.playerController().processRightClickBlock(
                                    ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, hit);
                            if (res.consumesAction()) {
                                ctx.player().swing(InteractionHand.MAIN_HAND);
                                openFarmland.remove(immediateReplantPos);
                                if (locations != null)
                                    locations.remove(immediateReplantPos);
                                immediateReplantPos = null;
                                replantAttemptTicks = 0;
                                acted = true;
                                lastProgressTick = farmTicks;
                            } else if (replantAttemptTicks >= 12) {
                                blacklistFor(immediateReplantPos, 100);
                                immediateReplantPos = null;
                                replantAttemptTicks = 0;
                            }
                        }
                        handledNearbyAction = true;
                        if (immediateReplantPos != null && !ctx.isLookingAt(immediateReplantPos)) {
                            if (playerPos.distSqr(immediateReplantPos) > 9.0D) {
                                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                            }
                        }
                    }
                }
            }
        }

        // ─────────────────────────────────────────────────────────────────
        // 7. ZBIERANIE PLONÓW (Pkt 5: Zintegrowana Sekcja 1)
        // ─────────────────────────────────────────────────────────────────
        candidates.clear();
        if (!plantOnly) {
            for (BlockPos p : toBreak) {
                if (!isBlacklisted(p)
                        && head.distanceToSqr(p.getX() + 0.5D, p.getY() + 0.5D, p.getZ() + 0.5D) <= reach2
                        && readyForHarvest(ctx.world(), p, ctx.world().getBlockState(p))) {
                    candidates.add(p);
                }
            }
            if (currentTargetPos != null && !candidates.contains(currentTargetPos)) {
                currentTargetPos = null;
                targetTicks = 0;
            }
            candidates.sort(Comparator.comparingDouble(this::aimScore));

            if (!acted) {
                for (BlockPos pos : candidates) {
                    BlockState bState = ctx.world().getBlockState(pos);
                    if (!readyForHarvest(ctx.world(), pos, bState)) {
                        continue;
                    }
                    final boolean isInstant = bState.getDestroySpeed(ctx.world(), pos) == 0.0F;
                    final boolean fastMode = Baritone.settings().farmFastMode.value;

                    // Dla instant-break (pszenica, marchew itp.) nie wymagamy pełnego raytracing
                    // LOS
                    // — zawsze obliczamy rotację bezpośrednio i wysyłamy pakiet
                    Optional<Rotation> rot = RotationUtils.reachable(ctx, pos, blockReachDistance);
                    if (!rot.isPresent()) {
                        Vec3 centerVec = Vec3.atCenterOf(pos);
                        if (head.distanceToSqr(centerVec) <= reach2) {
                            rot = Optional
                                    .of(RotationUtils.calcRotationFromVec3d(head, centerVec, ctx.playerRotations()));
                        }
                    }
                    // W fast mode lub dla instant crops — zawsze próbuj, nawet bez LOS
                    if (!rot.isPresent() && (fastMode || isInstant)) {
                        Vec3 centerVec = Vec3.atCenterOf(pos);
                        rot = Optional.of(RotationUtils.calcRotationFromVec3d(head, centerVec, ctx.playerRotations()));
                    }
                    if (!rot.isPresent())
                        continue;

                    applyAimRotation(rot.get(), fastMode);
                    if (!isInstant) {
                        MovementHelper.switchToBestToolFor(ctx, bState);
                    }
                    if (pos.equals(currentTargetPos)) {
                        targetTicks++;
                    } else {
                        currentTargetPos = pos;
                        targetTicks = 0;
                    }

                    final boolean aimed = fastMode || ctx.isLookingAt(pos);

                    if (aimed) {
                        ctx.playerController().syncHeldItem();
                        HitResult trace = ctx.objectMouseOver();
                        Direction breakFace = (trace instanceof BlockHitResult bhr && bhr.getBlockPos().equals(pos))
                                ? bhr.getDirection()
                                : BaritonePlayerController.getSafeBreakFace(ctx.player(), pos, Direction.UP);

                        if (isInstant) {
                            if (ctx.playerController().clickBlock(pos, breakFace)) {
                                ctx.player().swing(InteractionHand.MAIN_HAND);
                                acted = true;
                                lastProgressTick = farmTicks;
                                // Licz zebraną pszenicę (tylko Blocks.WHEAT = dojrzała pszenica)
                                if (bState.getBlock() == Blocks.WHEAT) {
                                    // Pszenica daje 1-3 szt., ale nie możemy wiedzieć ile bez symulacji dropu
                                    // Liczymy 1 zebrany blok (= ~1.5 średnio, ale używamy bloków jako jednostki)
                                    totalWheatHarvested++;
                                }
                                if (locations != null)
                                    locations.remove(pos);

                                if (tooManyAttempts(pos)) {
                                    currentTargetPos = null;
                                    targetTicks = 0;
                                } else if (Baritone.settings().replantCrops.value) {
                                    Block belowBlock = ctx.world().getBlockState(pos.below()).getBlock();
                                    if (belowBlock == Blocks.FARMLAND && hasPlantableSeeds()) {
                                        Item seed = seedFor(bState.getBlock());
                                        if (fastMode) {
                                            // FULL ODPAL: Zbiór + natychmiastowe zasianie w TYM SAMYM TICKU!
                                            boolean selected = (seed != null && baritone.getInventoryBehavior()
                                                    .throwaway(true, s -> s.getItem() == seed, true))
                                                    || baritone.getInventoryBehavior().throwaway(true,
                                                            this::isPlantable, true);
                                            if (selected) {
                                                ctx.playerController().syncHeldItem();
                                                Vec3 plantTarget = new Vec3(pos.getX() + 0.5D, pos.getY() - 1 + 0.9375D,
                                                        pos.getZ() + 0.5D);
                                                BlockHitResult plantHit = new BlockHitResult(plantTarget, Direction.UP,
                                                        pos.below(), false);
                                                InteractionResult res = ctx.playerController().processRightClickBlock(
                                                        ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, plantHit);
                                                if (res.consumesAction()) {
                                                    ctx.player().swing(InteractionHand.MAIN_HAND);
                                                    openFarmland.remove(pos.below());
                                                    if (locations != null)
                                                        locations.remove(pos.below());
                                                }
                                            }
                                        } else {
                                            immediateReplantPos = pos.below();
                                            replantAttemptTicks = 0;
                                            replantItem = seed;
                                            currentTargetPos = null;
                                            targetTicks = 0;
                                        }
                                    } else if (Baritone.settings().replantNetherWart.value
                                            && belowBlock == Blocks.SOUL_SAND) {
                                        if (fastMode && baritone.getInventoryBehavior().throwaway(true,
                                                this::isNetherWart, true)) {
                                            ctx.playerController().syncHeldItem();
                                            Vec3 plantTarget = new Vec3(pos.getX() + 0.5D, pos.getY() - 1 + 0.9375D,
                                                    pos.getZ() + 0.5D);
                                            BlockHitResult plantHit = new BlockHitResult(plantTarget, Direction.UP,
                                                    pos.below(), false);
                                            InteractionResult res = ctx.playerController().processRightClickBlock(
                                                    ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, plantHit);
                                            if (res.consumesAction()) {
                                                ctx.player().swing(InteractionHand.MAIN_HAND);
                                                openSoulsand.remove(pos.below());
                                                if (locations != null)
                                                    locations.remove(pos.below());
                                            }
                                        } else {
                                            immediateReplantPos = pos.below();
                                            replantAttemptTicks = 0;
                                            replantItem = Items.NETHER_WART;
                                            currentTargetPos = null;
                                            targetTicks = 0;
                                        }
                                    }
                                }
                            }
                        } else if (ctx.playerController().hasBrokenBlock()) {
                            ctx.playerController().clickBlock(pos, breakFace);
                            ctx.player().swing(InteractionHand.MAIN_HAND);
                            acted = true;
                            lastProgressTick = farmTicks;
                        } else if (ctx.playerController().onPlayerDamageBlock(pos, breakFace)) {
                            ctx.player().swing(InteractionHand.MAIN_HAND);
                        }
                    }

                    // Instant crops (pszenica) — daj więcej szans, blacklistuj po 12 próbach
                    // Ciężkie bloki — po 80 tickach
                    if (targetTicks >= (isInstant ? 12 : 80)) {
                        blacklistFor(pos, 60); // Krótsza blacklista dla pszenic
                        currentTargetPos = null;
                        targetTicks = 0;
                        continue;
                    }
                    handledNearbyAction = true;
                    if (!isInstant || (!aimed && playerPos.distSqr(pos) > 9.0D)) {
                        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                    }
                    break;
                }
            }
        }

        // ─────────────────────────────────────────────────────────────────
        // 8. SADZENIE NA ZAORANEJ ZIEMI (Pkt 5 i 7)
        // ─────────────────────────────────────────────────────────────────
        if (!acted && haveSeeds) {
            List<BlockPos> plantCandidates = new ArrayList<>();
            for (BlockPos p : openFarmland) {
                if (!isBlacklisted(p)
                        && head.distanceToSqr(p.getX() + 0.5D, p.getY() + 0.5D, p.getZ() + 0.5D) <= reach2) {
                    plantCandidates.add(p);
                }
            }
            if (currentPlantTargetPos != null && !plantCandidates.contains(currentPlantTargetPos)) {
                currentPlantTargetPos = null;
                plantTargetTicks = 0;
            }
            plantCandidates.sort(Comparator.comparingDouble(this::plantAimScore));

            for (BlockPos pos : plantCandidates) {
                Vec3 plantTarget = new Vec3(pos.getX() + 0.5D, pos.getY() + 0.9375D, pos.getZ() + 0.5D);
                Optional<Rotation> rot = RotationUtils.reachableOffset(ctx, pos, plantTarget, blockReachDistance,
                        false);
                if (!rot.isPresent()) {
                    rot = RotationUtils.reachable(ctx, pos, blockReachDistance);
                }
                if (!rot.isPresent()) {
                    if (head.distanceToSqr(plantTarget) <= reach2) {
                        rot = Optional
                                .of(RotationUtils.calcRotationFromVec3d(head, plantTarget, ctx.playerRotations()));
                    }
                }
                if (rot.isPresent()) {
                    boolean selected = (replantItem != null
                            && baritone.getInventoryBehavior().throwaway(true, this::isReplantItem, true))
                            || baritone.getInventoryBehavior().throwaway(true, this::isPlantable, true);
                    if (!selected)
                        continue;

                    final boolean fastMode = Baritone.settings().farmFastMode.value;
                    applyAimRotation(rot.get(), fastMode);

                    if (pos.equals(currentPlantTargetPos)) {
                        plantTargetTicks++;
                    } else {
                        currentPlantTargetPos = pos;
                        plantTargetTicks = 0;
                    }

                    ctx.playerController().syncHeldItem();

                    if (fastMode || ctx.isLookingAt(pos)) {
                        BlockHitResult plantHit = new BlockHitResult(plantTarget, Direction.UP, pos, false);
                        InteractionResult res = ctx.playerController().processRightClickBlock(
                                ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, plantHit);
                        if (res.consumesAction()) {
                            ctx.player().swing(InteractionHand.MAIN_HAND);
                            openFarmland.remove(pos);
                            if (locations != null)
                                locations.remove(pos);
                            currentPlantTargetPos = null;
                            plantTargetTicks = 0;
                            acted = true;
                            lastProgressTick = farmTicks;
                            if (tooManyAttempts(pos)) {
                                currentPlantTargetPos = null;
                                plantTargetTicks = 0;
                            }
                        }
                    }

                    if (plantTargetTicks >= (fastMode ? 10 : 35)) {
                        blacklistFor(pos, 60);
                        currentPlantTargetPos = null;
                        plantTargetTicks = 0;
                        continue;
                    }

                    handledNearbyAction = true;
                    if (!fastMode && !ctx.isLookingAt(pos) && playerPos.distSqr(pos) > 9.0D) {
                        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                    }
                    break;
                }
            }
        }

        // ─────────────────────────────────────────────────────────────────
        // 9. SADZENIE BRODAWEK (Soul Sand)
        // ─────────────────────────────────────────────────────────────────
        if (!acted && haveWart) {
            for (BlockPos pos : openSoulsand) {
                if (isBlacklisted(pos)
                        || head.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) > reach2) {
                    continue;
                }
                net.minecraft.world.phys.shapes.VoxelShape shape = ctx.world().getBlockState(pos).getShape(ctx.world(),
                        pos);
                double topY = shape.isEmpty() ? 1.0D : shape.max(Direction.Axis.Y);
                Vec3 plantTarget = new Vec3(pos.getX() + 0.5D, pos.getY() + topY, pos.getZ() + 0.5D);
                Optional<Rotation> rot = RotationUtils.reachableOffset(ctx, pos, plantTarget, blockReachDistance,
                        false);
                if (!rot.isPresent()) {
                    if (head.distanceToSqr(plantTarget) <= reach2) {
                        rot = Optional
                                .of(RotationUtils.calcRotationFromVec3d(head, plantTarget, ctx.playerRotations()));
                    }
                }
                if (rot.isPresent() && baritone.getInventoryBehavior().throwaway(true, this::isNetherWart, true)) {
                    final boolean fastMode = Baritone.settings().farmFastMode.value;
                    applyAimRotation(rot.get(), fastMode);
                    ctx.playerController().syncHeldItem();
                    if (fastMode || ctx.isLookingAt(pos)) {
                        BlockHitResult plantHit = new BlockHitResult(plantTarget, Direction.UP, pos, false);
                        InteractionResult res = ctx.playerController().processRightClickBlock(
                                ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, plantHit);
                        if (res.consumesAction()) {
                            ctx.player().swing(InteractionHand.MAIN_HAND);
                            openSoulsand.remove(pos);
                            if (locations != null)
                                locations.remove(pos);
                            acted = true;
                            lastProgressTick = farmTicks;
                        }
                    }
                    handledNearbyAction = true;
                    break;
                }
            }
        }

        // ─────────────────────────────────────────────────────────────────
        // 10. SADZENIE KAKAO (Pkt 12)
        // ─────────────────────────────────────────────────────────────────
        if (!acted && haveCocoa) {
            outerCocoa: for (BlockPos pos : openLog) {
                if (isBlacklisted(pos)
                        || head.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) > reach2) {
                    continue;
                }
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    if (!(ctx.world().getBlockState(pos.relative(dir)).getBlock() instanceof AirBlock)) {
                        continue;
                    }
                    Vec3 faceCenter = Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(dir.getUnitVec3i()).scale(0.5));
                    Optional<Rotation> rot = RotationUtils.reachableOffset(ctx, pos, faceCenter, blockReachDistance,
                            false);
                    if (rot.isPresent() && baritone.getInventoryBehavior().throwaway(true, this::isCocoa)) {
                        baritone.getLookBehavior().updateTarget(limitedTurn(rot.get()), true);
                        if (ctx.isLookingAt(pos)) {
                            BlockHitResult hit = new BlockHitResult(faceCenter, dir, pos, false);
                            InteractionResult res = ctx.playerController().processRightClickBlock(
                                    ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, hit);
                            if (res.consumesAction()) {
                                ctx.player().swing(InteractionHand.MAIN_HAND);
                                acted = true;
                                lastProgressTick = farmTicks;
                            }
                        }
                        handledNearbyAction = true;
                        break outerCocoa;
                    }
                }
            }
        }

        // ─────────────────────────────────────────────────────────────────
        // 11. MĄCZKA KOSTNA
        // ─────────────────────────────────────────────────────────────────
        if (!acted && haveBoneMeal) {
            for (BlockPos pos : bonemealable) {
                if (isBlacklisted(pos)
                        || head.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) > reach2) {
                    continue;
                }
                Optional<Rotation> rot = RotationUtils.reachable(ctx, pos, blockReachDistance);
                if (rot.isPresent() && baritone.getInventoryBehavior().throwaway(true, this::isBoneMeal)) {
                    baritone.getLookBehavior().updateTarget(limitedTurn(rot.get()), true);
                    if (ctx.isLookingAt(pos)) {
                        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
                        InteractionResult res = ctx.playerController().processRightClickBlock(
                                ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, hit);
                        if (res.consumesAction()) {
                            ctx.player().swing(InteractionHand.MAIN_HAND);
                            acted = true;
                        }
                    }
                    handledNearbyAction = true;
                    break;
                }
            }
        }

        if (!handledNearbyAction) {
            currentTargetPos = null;
            targetTicks = 0;
            currentPlantTargetPos = null;
            plantTargetTicks = 0;
        }

        // ─────────────────────────────────────────────────────────────────
        // 12. GENEROWANIE CELÓW PATHINGU (Pkt 11)
        // ─────────────────────────────────────────────────────────────────
        List<Goal> goalz = new ArrayList<>();

        // A. Podnoszenie leżących itemów (z weryfikacją miejsca w EQ)
        // UWAGA: cele dla itemów są wyjęte spod standardowej blacklisty (blacklistUntil
        // dotyczy
        // BlockPos bloków, nie EntityId). Korzystamy z itemBlacklist (entity ID →
        // farmTicks).
        List<ItemEntity> nearbyDrops = new ArrayList<>();
        // W trybie #praca używamy promienia od centrum farmy (nie od gracza), żeby uniknąć
        // oscylacji celów gdy bot porusza się po farmie.
        boolean isPracaActive = pracaMode || Baritone.settings().farmCollectDropsWhenLowCrops.value;
        int dropRadius = Baritone.settings().farmLowCropRadius.value;
        try {
            for (Entity entity : ctx.entities()) {
                if (entity instanceof ItemEntity ei && !entity.isRemoved() && entity.isAlive()) {
                    if (ei.isInLava()) {
                        continue;
                    }
                    if (PICKUP_DROPPED.contains(ei.getItem().getItem())) {
                        if (itemBlacklist.get(ei.getId()) > farmTicks || !hasRoomFor(ei.getItem())) {
                            continue;
                        }
                        BlockPos ePos = entity.blockPosition();
                        if (!ctx.player().isInWater()
                                && (entity.isInWater() || !ctx.world().getFluidState(ePos).isEmpty())) {
                            continue;
                        }
                        // Filtr zasięgu: w trybie #praca – promień od centrum farmy;
                        // bez trybu #praca – standardowy promień od gracza.
                        if (isPracaActive) {
                            // Używamy centrum farmy jako punktu odniesienia (stabilny punkt)
                            double maxR = dropRadius > 0 ? dropRadius : (range > 0 ? range : 150);
                            if (ePos.distSqr(center) > maxR * maxR) {
                                continue;
                            }
                        } else {
                            if (range != 0 && ePos.distSqr(center) > range * range) {
                                continue;
                            }
                        }
                        nearbyDrops.add(ei);
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        nearbyDrops.sort(Comparator.comparingDouble(e -> playerPos.distSqr(e.blockPosition())));
        pracaGroundDropsCount = nearbyDrops.size();
        lastNearestDropId = nearbyDrops.isEmpty() ? -1 : nearbyDrops.get(0).getId();

        // ── Tryb #praca: sprawdź czy plonów jest mniej niż próg (z histerezą ±10 żeby uniknąć
        // ciągłego przełączania trybu gdy liczba plonów oscyluje przy granicy) ──
        int threshold = Baritone.settings().farmLowCropThreshold.value;
        // Histereza: gdy pracaGroundFirst był włączony, wyłącz go dopiero przy threshold+10
        boolean pracaGroundFirst;
        if (pracaGroundFirstState) {
            // Był włączony — wyłącz tylko gdy plonów jest wyraźnie powyżej progu
            pracaGroundFirst = isPracaActive && pracaHarvestableCount < (threshold + 10);
        } else {
            // Był wyłączony — włącz tylko gdy plonów jest wyraźnie poniżej progu
            pracaGroundFirst = isPracaActive && pracaHarvestableCount < threshold;
        }
        pracaGroundFirstState = pracaGroundFirst;

        if (pracaGroundFirst && !nearbyDrops.isEmpty()) {
            // Anti-stall dla najbliższego dropu (zapobiega blokowaniu bota na niepodnoszalnym itemie)
            ItemEntity closestDrop = nearbyDrops.get(0);
            BlockPos closestStand = BlockPos.containing(closestDrop.getX(), closestDrop.getY() + 0.1D, closestDrop.getZ());
            if (playerPos.distSqr(closestStand) <= 2.25) {
                if (closestDrop.getId() == currentDropTargetId) {
                    dropPickupWaitTicks++;
                    if (dropPickupWaitTicks > 25) {
                        itemBlacklist.put(closestDrop.getId(), farmTicks + 200);
                        dropPickupWaitTicks = 0;
                        currentDropTargetId = -1;
                    }
                } else {
                    currentDropTargetId = closestDrop.getId();
                    dropPickupWaitTicks = 0;
                }
            } else {
                currentDropTargetId = closestDrop.getId();
                dropPickupWaitTicks = 0;
            }

            // W trybie #praca z plonami poniżej progu: priorytet zbierania itemów leżących na ziemi.
            // Max 2 cele na raz — bezpośrednio przechodzimy przez itemy!
            int maxPickupGoals = Math.min(2, nearbyDrops.size());
            for (int i = 0; i < maxPickupGoals; i++) {
                ItemEntity ei = nearbyDrops.get(i);
                if (i > 0 && playerPos.distSqr(ei.blockPosition()) > 35.0D * 35.0D && !toBreak.isEmpty()) {
                    break;
                }
                BlockPos standPos = BlockPos.containing(ei.getX(), ei.getY() + 0.1D, ei.getZ());
                goalz.add(new GoalPickupItem(standPos));
            }
            if (farmTicks % 100 == 0) {
                logDirect(String.format("§e[Praca] Tryb zbierania z ziemi (%d plonów w r=%d < %d). Zbieram: %d itemów",
                        pracaHarvestableCount, dropRadius, threshold, nearbyDrops.size()));
            }

            // Fallback: max 2 pobliskie plony wzdłuż alei
            if (!plantOnly && !toBreak.isEmpty()) {
                List<BlockPos> fallbackGoals = selectOptimizedHarvestGoals(toBreak, playerPos);
                for (BlockPos p : fallbackGoals) {
                    goalz.add(new GoalFarmTarget(p));
                }
            }
        } else {
            currentDropTargetId = -1;
            dropPickupWaitTicks = 0;

            // ── LANE TRAVERSAL: inteligentny bieg wzdłuż rzędów upraw ──
            // Bot biegnie sprintem wzdłuż rzędów, zbierając i sadząc plony w biegu (20 bloków/s)
            // bez zatrzymywania się na każdym klocku i bez zygzakowania po sąsiednich grządkach.

            // A. Dojrzałe plony — trasa wzdłuż rzędów
            if (!plantOnly && !toBreak.isEmpty()) {
                harvestQueue.clear();
                List<BlockPos> goals = selectOptimizedHarvestGoals(toBreak, playerPos);
                for (BlockPos p : goals) {
                    harvestQueue.add(p);
                    goalz.add(new GoalFarmTarget(p));
                }
            }

            // B. Sadzenie pustej ziemi — trasa wzdłuż rzędów
            if (haveSeeds && !openFarmland.isEmpty()) {
                plantQueue.clear();
                List<BlockPos> goals = selectOptimizedHarvestGoals(openFarmland, playerPos);
                for (BlockPos p : goals) {
                    plantQueue.add(p);
                    goalz.add(new GoalFarmTarget(p));
                }
            }
            if (haveWart && !openSoulsand.isEmpty()) {
                List<BlockPos> validSoulsand = getNearbyValidBlocks(openSoulsand, playerPos, 24);
                validSoulsand.sort(Comparator.comparingDouble(playerPos::distSqr));
                int cnt = 0;
                for (BlockPos pos : validSoulsand) {
                    if (!isBlacklisted(pos) && ++cnt <= 2) goalz.add(new GoalFarmTarget(pos));
                }
            }
            if (haveCocoa) {
                for (BlockPos pos : openLog) {
                    if (isBlacklisted(pos))
                        continue;
                    for (Direction direction : Direction.Plane.HORIZONTAL) {
                        if (ctx.world().getBlockState(pos.relative(direction)).getBlock() instanceof AirBlock) {
                            goalz.add(new GoalGetToBlock(pos.relative(direction)));
                        }
                    }
                    break; // tylko jeden cel kokosa na raz
                }
            }
            if (haveBoneMeal) {
                for (BlockPos pos : bonemealable) {
                    if (isBlacklisted(pos))
                        continue;
                    goalz.add(new GoalBlock(pos));
                    break; // jeden cel kostny na raz
                }
            }

            // Normalny tryb: zbieramy pobliskie leżące itemy (max 2 cele przechodząc przez nie)
            int maxPickupGoals = Math.min(2, nearbyDrops.size());
            for (int i = 0; i < maxPickupGoals; i++) {
                ItemEntity ei = nearbyDrops.get(i);
                if (playerPos.distSqr(ei.blockPosition()) > 30.0D * 30.0D) {
                    break;
                }
                BlockPos standPos = BlockPos.containing(ei.getX(), ei.getY() + 0.1D, ei.getZ());
                goalz.add(new GoalPickupItem(standPos));
            }
        }

        // ─────────────────────────────────────────────────────────────────
        // 13. DETEKCJA ZACIĘCIA I OBSŁUGA calcFailed (Pkt 3 i 11)
        // ─────────────────────────────────────────────────────────────────
        final boolean currentHasWork = !goalz.isEmpty();
        final boolean samePlace = lastPlayerPos != null && playerPos.equals(lastPlayerPos);
        lastPlayerPos = playerPos;

        // [FIX C] getInProgress() oznacza tylko TRWAJĄCE OBLICZANIE ścieżki — gdy bot
        // idzie po gotowej
        // ścieżce jest puste, a samePlace jest prawdziwe przez ~80% ticków w obrębie
        // jednego bloku.
        // Bez hasPath() licznik rósł podczas zwykłego marszu i po ~30-60 blokach
        // wywoływał fałszywe "zacięcie".
        final boolean pathBusy = baritone.getPathingBehavior().hasPath()
                || baritone.getPathingBehavior().getInProgress().isPresent();

        if (!currentHasWork) {
            idleStuckTicks = 0; // Czekanie na wzrost roślin to nie zacięcie
        } else if (samePlace && !pathBusy) {
            idleStuckTicks++;
        } else {
            idleStuckTicks = Math.max(0, idleStuckTicks - 2);
        }

        if (idleStuckTicks > 35) {
            idleStuckTicks = 0;
            // Blacklistuj TYLKO bloki, które miały wielokrotnie powtarzane, nieudane próby
            // interakcji
            for (BlockPos p : toBreak) {
                if (playerPos.distSqr(p) < 9.0D && attempts.get(p.asLong()) >= 5) {
                    blacklistFor(p, 160);
                }
            }
            for (BlockPos p : openFarmland) {
                if (playerPos.distSqr(p) < 9.0D && attempts.get(p.asLong()) >= 5) {
                    blacklistFor(p, 160);
                }
            }
            // Blacklistuj itemy w pobliżu które nie dają się podnieść
            for (Entity entity : ctx.entities()) {
                if (entity instanceof ItemEntity ei && !entity.isRemoved()
                        && PICKUP_DROPPED.contains(ei.getItem().getItem())) {
                    if (playerPos.distSqr(ei.blockPosition()) <= 4.0D) {
                        itemBlacklist.put(ei.getId(), farmTicks + 400);
                    }
                }
            }
            currentTargetPos = null;
            targetTicks = 0;
            currentPlantTargetPos = null;
            plantTargetTicks = 0;
            immediateReplantPos = null;
            replantAttemptTicks = 0;
            baritone.getPathingBehavior().softCancelIfSafe();
            if (canSafelyJump()) {
                baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // [FIX D] WATCHDOG niezależny od typu celu. Są aktywne cele, a bot od
        // WATCHDOG_TICKS nie przesunął
        // się o ≥3 bloki, nie zmienił EQ (4b) i nic nie zrobił. Łapie też przypadki, w
        // których licznik
        // idleStuckTicks nie rośnie (pather ciągle liczy/zawodzi albo ścieżka jest,
        // lecz bot stoi).
        // Eskalacja co okno: 1) blacklista okolicy, 2) reset stanu wewnętrznego (jak
        // ponowne #farm),
        // 3) failsafe jak przy braku zysku. Log wskazuje, który cel blokuje bota.
        if (acted || !currentHasWork) {
            wdSinceTick = farmTicks;
            wdLevel = 0;
        } else if (farmTicks - wdSinceTick > WATCHDOG_TICKS) {
            wdSinceTick = farmTicks;
            wdLevel++;
            logDirect("§e[Farm] Watchdog " + wdLevel + "/3: brak postępu od " + (WATCHDOG_TICKS / 20)
                    + " s. Cele: " + describeGoals(goalz, playerPos)
                    + " | itemy: " + nearbyDrops.size()
                    + " | pełny EQ: " + isInventoryFull()
                    + " | ścieżka: " + baritone.getPathingBehavior().hasPath());
            currentTargetPos = null;
            targetTicks = 0;
            currentPlantTargetPos = null;
            plantTargetTicks = 0;
            immediateReplantPos = null;
            replantAttemptTicks = 0;
            if (wdLevel == 1) {
                for (BlockPos p : toBreak)
                    if (playerPos.distSqr(p) < 16.0D && attempts.get(p.asLong()) >= 2)
                        blacklistFor(p, 100);
                for (BlockPos p : openFarmland)
                    if (playerPos.distSqr(p) < 16.0D && attempts.get(p.asLong()) >= 2)
                        blacklistFor(p, 100);
                for (Goal g : goalz) {
                    if (g.isInGoal(playerPos.x, playerPos.y, playerPos.z) && g instanceof IGoalRenderPos grp) {
                        blacklistFor(grp.getGoalPos(), 200);
                    }
                }
                for (ItemEntity drop : nearbyDrops) {
                    if (playerPos.distSqr(drop.blockPosition()) < 16.0D) {
                        itemBlacklist.put(drop.getId(), farmTicks + 200);
                    }
                }
                baritone.getPathingBehavior().softCancelIfSafe();
                if (canSafelyJump()) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                }
            } else if (wdLevel == 2) {
                resetWorkState();
                baritone.getPathingBehavior().softCancelIfSafe();
            } else {
                wdLevel = 0;
                preTeleportPos = ctx.playerFeet();
                sendFailsafeCommand();
                teleportCooldownTicks = 100;
                lastProgressTick = farmTicks;
                baritone.getPathingBehavior().softCancelIfSafe();
                baritone.getInputOverrideHandler().clearAllKeys();
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (pendingCalcFailed) {
            pendingCalcFailed = false;
            if (lastNearestDropId != -1) {
                itemBlacklist.put(lastNearestDropId, farmTicks + 100);
            } else {
                toBreak.stream().min(Comparator.comparingDouble(playerPos::distSqr)).ifPresent(p -> blacklistFor(p, 40));
                openFarmland.stream().min(Comparator.comparingDouble(playerPos::distSqr))
                        .ifPresent(p -> blacklistFor(p, 40));
            }
            currentTargetPos = null;
            targetTicks = 0;
            currentPlantTargetPos = null;
            plantTargetTicks = 0;
            idleStuckTicks = 0;
            if (canSafelyJump()) {
                baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
            }
        }

        // Pkt 7: Zapobieganie zablokowaniu przy pełnej blackliście
        if (goalz.isEmpty() && (!toBreak.isEmpty() || !openFarmland.isEmpty())) {
            emptyGoalTicks++;
            if (emptyGoalTicks > 20) {
                blacklistUntil.clear();
                attempts.clear();
                harvestQueue.clear();
                plantQueue.clear();
                emptyGoalTicks = 0;
                // Dodaj 3 najbliższe po wyczyszczeniu blacklisty
                if (!plantOnly && !toBreak.isEmpty()) {
                    toBreak.stream()
                        .filter(p -> !isBlacklisted(p))
                        .sorted(Comparator.comparingDouble(playerPos::distSqr))
                        .limit(3)
                        .forEach(p -> goalz.add(new GoalFarmTarget(p)));
                }
                if (haveSeeds && !openFarmland.isEmpty()) {
                    openFarmland.stream()
                        .filter(p -> !isBlacklisted(p))
                        .sorted(Comparator.comparingDouble(playerPos::distSqr))
                        .limit(3)
                        .forEach(p -> goalz.add(new GoalFarmTarget(p)));
                }
            }
        } else {
            emptyGoalTicks = 0;
        }

        if (goalz.isEmpty()) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        return new PathingCommand(
                new GoalComposite(goalz.toArray(new Goal[0])),
                PathingCommandType.SET_GOAL_AND_PATH);
    }

    @Override
    public double priority() {
        return 2.5D;
    }

    @Override
    public void onLostControl() {
        active = false;
        plantOnly = false;
        pracaMode = false;
        currentDropTargetId = -1;
        dropPickupWaitTicks = 0;
        pracaGroundFirstState = false;
        currentTargetPos = null;
        targetTicks = 0;
        currentPlantTargetPos = null;
        plantTargetTicks = 0;
        immediateReplantPos = null;
        replantAttemptTicks = 0;
        replantItem = null;
        blacklistUntil.clear();
        attempts.clear();
        itemBlacklist.clear();
        lastPlayerPos = null;
        idleStuckTicks = 0;
        waterTicks = 0;
        emptyGoalTicks = 0;
        scanCooldownTicks = 0;
        teleportCooldownTicks = 0;
        wdAnchor = null;
        wdSig = 0;
        wdSinceTick = 0L;
        wdLevel = 0;
        scanGeneration++;
        harvestQueue.clear();
        plantQueue.clear();
        queueVersion++;
        baritone.getInputOverrideHandler().clearAllKeys();

        // Pkt 10: Przywróć dokładny stan ustawień
        // [FIX A] Tylko jeśli farm() je zmieniła. onLostControl() jest wołane także na
        // nieaktywnym procesie
        // (cancelEverything()/#stop) — bez tej flagi allowBreak trafiałby na `false`
        // (domyślna wartość pola).
        if (settingsApplied) {
            settingsApplied = false;
            Baritone.settings().blocksToDisallowBreaking.value.removeAll(addedDisallowed);
            addedDisallowed.clear();
            Baritone.settings().mineAvoidWater.value = prevMineAvoidWater;
            Baritone.settings().allowBreak.value = prevAllowBreak;
        }
    }

    @Override
    public String displayName0() {
        return plantOnly ? "Planting" : "Farming";
    }

    // ── Publiczne gettery statystyk pszenicy ──
    public static long getTotalWheatHarvested() {
        return totalWheatHarvested;
    }

    public static long getFarmSessionStartMs() {
        return farmSessionStartMs;
    }

    public static boolean isFarmSessionRunning() {
        return sessionRunning;
    }

    /** Resetuje całę sesję (wywoływane np. przez #farm reset stats). */
    public static void resetWheatStats() {
        totalWheatHarvested = 0L;
        farmSessionStartMs = System.currentTimeMillis();
        sessionRunning = false;
    }

    /** Średnio pszenicy na godzinę liczone od startu sesji. */
    public static double getWheatPerHour() {
        if (!sessionRunning || farmSessionStartMs == 0L)
            return 0.0;
        double elapsedHours = (System.currentTimeMillis() - farmSessionStartMs) / 3_600_000.0;
        if (elapsedHours < 0.00028)
            return 0.0; // < 1 sekunda
        return totalWheatHarvested / elapsedHours;
    }
}