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
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.input.Input;
import baritone.api.utils.interfaces.IGoalRenderPos;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.player.BaritonePlayerController;
import baritone.bypass.RotationEngine;
import baritone.bypass.GrimSnapRotationUtil;
import com.astra.revolution.pathing.FarmPathOptimizer;
import com.astra.revolution.pathing.AstraMovementFluidityEngine;
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
import net.minecraft.world.level.ClipContext;
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

    // Kolejka replantu FIFO (SequencedSet / LinkedHashSet) zapobiegająca gubieniu grządek przy szybkim ścinaniu
    private final SequencedSet<BlockPos> pendingReplants = new LinkedHashSet<>();
    private static final double CLOSE_SWEEP_RADIUS_SQ = 3.6D * 3.6D; // Strefa 360° bez pomijania kłosów
    private static final float GRIM_ROTATION_TOLERANCE_DEG = 15.0F;

    // GrimAC Server Rotation Stabilization & Mouse Sweep
    private BlockPos lastAimedCropPos = null;
    private int serverAimedTicks = 0;
    private Vec3 laneForwardDir = Vec3.ZERO;
    private Vec3 lastProgressPos = null;

    // ── Stałe zasięgu i prędkości Mouse-First Sweep (Argon 4) ──
    public static final double SWEEP_REACH = 3.65D;
    public static final double SWEEP_REACH_SQ = SWEEP_REACH * SWEEP_REACH;
    public static final double MICRO_PULL_MAX_DIST = 4.25D;
    private int manualPullStallTicks = 0;

    // ── Zakotwiczony wektor kierunku aktualnego rzędu (eliminacja oscylacji przód-tył o 180° — Argon 4) ──
    private float lockedLaneYaw = Float.NaN;

    // ── Strict 1-Target Pipeline (Argon 4: Eliminacja flappingu rotacji i deadlocka GrimAC) ──
    private enum ActionPhase { NONE, HARVEST, REPLANT }
    private BlockPos lockedTarget = null;
    private ActionPhase lockedPhase = ActionPhase.NONE;
    private int lockedTargetTicks = 0;
    // ── Pipeline & Zero-Latency Target Cache (Argon 4) ──
    private BlockPos lookAheadTarget = null;
    private long lastGoalFingerprint = 0L;
    private Goal cachedCompositeGoal = null;
    private int ticksSinceGoalRebuild = 0;

    // Blacklista na longach (pos.asLong()) oraz licznik prób odrzuceń (Pkt 7)
    private final Long2LongOpenHashMap blacklistUntil = new Long2LongOpenHashMap();
    private final Long2IntOpenHashMap attempts = new Long2IntOpenHashMap();
    private final Int2LongOpenHashMap itemBlacklist = new Int2LongOpenHashMap();

    private final AtomicBoolean isScanning = new AtomicBoolean(false);

    // ── Persistent Soil & Farmland Cache (Argon 4: Zero gubienia grządek) ──
    private final LongOpenHashSet persistentFarmlandCache = new LongOpenHashSet();
    private int cachePruneTicker = 0;

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
    private static boolean pracaWheatPriorityState = false; // histereza: skupienie na pszenicy (>100 nasion aż do <=30 nasion)

    // ── Silnik korytarzy Boustrophedon oraz natychmiastowego replantu pod #praca (Argon 4) ──
    private final FarmPathOptimizer farmOptimizer = new FarmPathOptimizer();
    private BlockPos pendingInstantReplantPos = null;
    private int pendingInstantReplantTicks = 0;
    private BlockPos pracaActiveSpineWaypoint = null;
    // ── Cache bloków farmy dla redukcji narzutu CPU na dużych polach ──
    private final List<BlockPos> cachedToBreak = new ArrayList<>();
    private final List<BlockPos> cachedOpenFarmland = new ArrayList<>();
    private final List<BlockPos> cachedBonemealable = new ArrayList<>();
    private final List<BlockPos> cachedOpenSoulsand = new ArrayList<>();
    private final List<BlockPos> cachedOpenLog = new ArrayList<>();
    private int fullFieldScanTicker = 0;
    private int postWaterGraceTicks = 0;
    private Vec3 postWaterSafeAnchor = null;
    private long lastFailsafeTriggerTime = 0L;
    private BlockPos magnetPullTarget = null;
    private int stallTicks = 0;
    private final Map<BlockPos, Long> stalledTargetCooldowns = new HashMap<>();
    private boolean lowLatencySettingsApplied = false;

    private void ensureLowLatencyBaritoneSettings() {
        if (lowLatencySettingsApplied) return;
        lowLatencySettingsApplied = true;

        Baritone.settings().primaryTimeoutMS.value = 85L;
        Baritone.settings().failureTimeoutMS.value = 250L;
        Baritone.settings().planAheadPrimaryTimeoutMS.value = 150L;
        Baritone.settings().planAheadFailureTimeoutMS.value = 350L;
        Baritone.settings().movementTimeoutTicks.value = 40;
        Baritone.settings().costHeuristic.value = 3.65D;
    }

    public static boolean isPracaModeActive() {
        return pracaMode || Baritone.settings().farmCollectDropsWhenLowCrops.value;
    }
    public static void setPracaMode(boolean v) {
        pracaMode = v;
    }
    public static int getPracaHarvestableCount() { return pracaHarvestableCount; }
    public static int getPracaGroundDropsCount() { return pracaGroundDropsCount; }
    public static boolean isPracaWheatPriorityActive() { return pracaWheatPriorityState; }

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
    private static final int WATCHDOG_TICKS = 600; // 30 s na poziom (90 s do poziomu 3 zamiast 15 s)
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

    // ── STABILIZACJA CELU A* (Row Anchor — rekomendacja Argon 4) ──
    private BlockPos stableRowAnchorPos = null;
    private Goal stableRowGoal = null;
    private int stableRowGoalTicks = 0;
    private Vec3 committedWalkDir = null; // Kierunek aktualnego sektora (anti-flap barrier — Argon 4)
    private int sweepDirection = 1;
    private float lastHarvestYaw = Float.NaN;

    // ── Histereza celu podchodzenia (eliminacja oscylacji 180° — Argon 4) ──
    private BlockPos lockedWalkTarget = null;
    private int lockedWalkTicks = 0;
    private double lockedWalkLastDistSq = Double.MAX_VALUE;

    private final Long2IntOpenHashMap failedPlantClicks = new Long2IntOpenHashMap();
    private final Long2LongOpenHashMap unplantableBlacklist = new Long2LongOpenHashMap();
    private static final long UNPLANTABLE_TTL_TICKS = 100L; // 5 sekund zamiast 2400 (2 minut)
    private static final int MAX_PLANT_FAIL_CLICKS = 10;

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
        pracaWheatPriorityState = false;
        if (pos == null) {
            center = baritone.getPlayerContext().playerFeet();
        } else {
            center = pos;
        }
        this.range = range;

        active = true;
        locations = null;
        persistentFarmlandCache.clear();
        cachePruneTicker = 0;
        scanGeneration++;
        scanCooldownTicks = 0;
        currentTargetPos = null;
        targetTicks = 0;
        currentPlantTargetPos = null;
        plantTargetTicks = 0;
        immediateReplantPos = null;
        replantAttemptTicks = 0;
        replantItem = null;
        pendingReplants.clear();
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
        stableRowAnchorPos = null;
        stableRowGoal = null;
        stableRowGoalTicks = 0;
        committedWalkDir = null;
        sweepDirection = 1;
        lastHarvestYaw = Float.NaN;
        failedPlantClicks.clear();
        unplantableBlacklist.clear();
        lastAimedCropPos = null;
        serverAimedTicks = 0;
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
        pendingInstantReplantPos = null;
        pendingInstantReplantTicks = 0;
        pracaActiveSpineWaypoint = null;
        farmOptimizer.resetRoute();
        postWaterGraceTicks = 0;
        postWaterSafeAnchor = null;
        magnetPullTarget = null;
        stallTicks = 0;
        stalledTargetCooldowns.clear();
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

    /**
     * Zwraca true WYŁĄCZNIE dla w 100% dojrzałych plonów (isMaxAge).
     * Niedojrzałe sadzonki są całkowicie ignorowane — bot nigdy przy nich nie staje i nie czeka (Argon 4).
     */
    public static boolean isStrictlyMatureCrop(Level world, BlockPos pos, BlockState state) {
        if (state == null || state.isAir()) return false;
        Block block = state.getBlock();
        if (block instanceof CropBlock crop) {
            return crop.isMaxAge(state);
        }
        if (block == Blocks.NETHER_WART) {
            return state.getValue(NetherWartBlock.AGE) >= 3;
        }
        if (block == Blocks.COCOA) {
            return state.getValue(CocoaBlock.AGE) >= 2;
        }
        if (block == Blocks.MELON || block == Blocks.PUMPKIN) {
            return true;
        }
        if (block instanceof SugarCaneBlock || block instanceof CactusBlock || block instanceof BambooStalkBlock) {
            Block below = world.getBlockState(pos.below()).getBlock();
            return below == block;
        }
        Harvest h = HARVEST_BY_BLOCK.get(block);
        return h != null && h.readyToHarvest(world, pos, state);
    }

    private boolean readyForHarvest(Level world, BlockPos pos, BlockState state) {
        if (Baritone.settings().farmWheatOnly.value) {
            return state != null && state.getBlock() == Blocks.WHEAT && ((CropBlock) Blocks.WHEAT).isMaxAge(state);
        }
        return isStrictlyMatureCrop(world, pos, state);
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
        if (crop == Blocks.NETHER_WART)
            return Items.NETHER_WART;
        if (crop == Blocks.COCOA)
            return Items.COCOA_BEANS;
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
        if (Baritone.settings().farmWheatOnly.value) {
            return stack.getItem() == Items.WHEAT_SEEDS;
        }
        return FARMLAND_PLANTABLE.contains(stack.getItem())
                || stack.is(Items.NETHER_WART)
                || stack.is(Items.COCOA_BEANS);
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

    public int countWheatSeedsInInventory() {
        if (ctx.player() == null || ctx.player().getInventory() == null) {
            return 0;
        }
        int total = 0;
        for (ItemStack stack : ctx.player().getInventory().items) {
            if (stack != null && !stack.isEmpty() && stack.is(Items.WHEAT_SEEDS)) {
                total += stack.getCount();
            }
        }
        for (ItemStack stack : ctx.player().getInventory().offhand) {
            if (stack != null && !stack.isEmpty() && stack.is(Items.WHEAT_SEEDS)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    public boolean hasAnyPlantableInInventory() {
        if (ctx.player() == null || ctx.player().getInventory() == null) {
            return false;
        }
        if (Baritone.settings().farmWheatOnly.value) {
            return countWheatSeedsInInventory() > 0;
        }
        for (ItemStack stack : ctx.player().getInventory().items) {
            if (stack != null && !stack.isEmpty() && isPlantable(stack)) {
                return true;
            }
        }
        for (ItemStack stack : ctx.player().getInventory().offhand) {
            if (stack != null && !stack.isEmpty() && isPlantable(stack)) {
                return true;
            }
        }
        return false;
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

    private Rotation smoothStepRotation(Rotation current, Rotation target, float maxYawStep, float maxPitchStep) {
        // Bezpośredni 1-tickowy snap zgodny z GrimAC GCD (rekomendacja Argon 4: usunięcie limitu 55°/30°)
        return RotationEngine.snapToGcd(current, target);
    }

    private double scoreWalkTarget(BlockPos pos, BetterBlockPos playerPos) {
        double dx = pos.getX() + 0.5D - (ctx.player() != null ? ctx.player().getX() : playerPos.getX() + 0.5D);
        double dz = pos.getZ() + 0.5D - (ctx.player() != null ? ctx.player().getZ() : playerPos.getZ() + 0.5D);
        double dist = Math.hypot(dx, dz);

        // Histereza kierunku (Anti-Flap Barrier - Argon 4):
        // Jeśli bot podąża już w kierunku wyznaczonego sektora (committedWalkDir),
        // cele leżące w przeciwnym kierunku otrzymują 35 bloków kary, uniemożliwiając zawracanie w pół drogi.
        Vec3 refDir = committedWalkDir != null ? committedWalkDir : laneForwardDir;
        if (refDir != null && refDir.lengthSqr() > 0.01D && dist > 0.001D) {
            double dot = (dx * refDir.x + dz * refDir.z) / dist;
            if (dot < 0.1D) {
                dist += 35.0D; // potężna bariera histerezy przeciw flappingowi północ-południe
            }
        }
        return dist;
    }

    private void applyAimRotation(Rotation targetRot, boolean fastMode) {
        boolean isPathing = baritone.getPathingBehavior().isPathing();
        baritone.getLookBehavior().updateTarget(targetRot, true);
        // Bezpośrednią aktualizację kąta gracza (setYRot) wykonujemy TYLKO wtedy, gdy PathExecutor NIE idzie po ścieżce!
        // Gdy bot pokonuje trasę wokół przeszkody, nie nadpisujemy setYRot, aby nie pchać gracza w ścianę.
        if (!isPathing && ctx.player() != null) {
            ctx.player().setYRot(targetRot.getYaw());
            ctx.player().setXRot(targetRot.getPitch());
            ctx.player().yHeadRot = targetRot.getYaw();
            com.astra.revolution.pathing.AstraMovementFluidityEngine.INSTANCE.lockAimForProcess(targetRot, 2);
        }
    }

    private boolean hasCleanColliderLineOfSight(Vec3 eyePos, BlockPos targetPos, Vec3 aimVec) {
        if (ctx.world() == null || ctx.player() == null || aimVec == null || targetPos == null) return false;

        BlockHitResult hit = ctx.world().clip(new ClipContext(
                eyePos,
                aimVec,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                ctx.player()
        ));

        if (hit.getType() == HitResult.Type.MISS) {
            return true;
        }

        BlockPos hitPos = hit.getBlockPos();
        return hitPos.equals(targetPos) || hitPos.equals(targetPos.below());
    }

    public Vec3 resolveVisibleAimPoint(Vec3 eyePos, BlockPos targetPos, boolean isPlanting) {
        Level level = ctx.world();
        if (level == null || targetPos == null || eyePos == null) return null;

        if (isPlanting) {
            BlockPos soilPos = level.getBlockState(targetPos).is(Blocks.FARMLAND) || level.getBlockState(targetPos).is(Blocks.SOUL_SAND)
                    ? targetPos : targetPos.below();
            double topY = soilPos.getY() + 0.9375D;

            Vec3[] candidates = new Vec3[] {
                new Vec3(soilPos.getX() + 0.5D, topY, soilPos.getZ() + 0.5D),
                new Vec3(soilPos.getX() + 0.3D, topY, soilPos.getZ() + 0.3D),
                new Vec3(soilPos.getX() + 0.7D, topY, soilPos.getZ() + 0.3D),
                new Vec3(soilPos.getX() + 0.3D, topY, soilPos.getZ() + 0.7D),
                new Vec3(soilPos.getX() + 0.7D, topY, soilPos.getZ() + 0.7D)
            };

            for (Vec3 pt : candidates) {
                if (hasCleanColliderLineOfSight(eyePos, soilPos, pt)) {
                    return pt;
                }
            }
            return null;
        }

        Vec3[] harvestCandidates = new Vec3[] {
            new Vec3(targetPos.getX() + 0.5D, targetPos.getY() + 0.65D, targetPos.getZ() + 0.5D),
            new Vec3(targetPos.getX() + 0.5D, targetPos.getY() + 0.85D, targetPos.getZ() + 0.5D),
            new Vec3(targetPos.getX() + 0.5D, targetPos.getY() + 0.25D, targetPos.getZ() + 0.5D),
            new Vec3(targetPos.getX() + 0.35D, targetPos.getY() + 0.65D, targetPos.getZ() + 0.35D),
            new Vec3(targetPos.getX() + 0.65D, targetPos.getY() + 0.65D, targetPos.getZ() + 0.65D)
        };

        for (Vec3 pt : harvestCandidates) {
            if (hasCleanColliderLineOfSight(eyePos, targetPos, pt)) {
                return pt;
            }
        }

        return null;
    }

    private boolean shouldYieldRotationToPathing(Vec3 eyePos, BlockPos cropTarget, Vec3 cropAimVec, boolean inDirectReachAndVisible) {
        if (!baritone.getPathingBehavior().isPathing()) {
            return false;
        }

        var executor = baritone.getPathingBehavior().getCurrent();
        if (executor == null) return false;

        var path = executor.getPath();
        int posIndex = executor.getPosition();
        if (path == null || posIndex < 0 || posIndex >= path.length()) return false;

        // 1. Jeśli cel NIE jest w bezpośrednim zasięgu ręki i widoczny (bez ściany):
        if (!inDirectReachAndVisible) {
            if (cropTarget == null || cropAimVec == null || !hasCleanColliderLineOfSight(eyePos, cropTarget, cropAimVec)) {
                return true;
            }
        }

        // 2. Jeśli bot pokonuje przeszkodę na ścieżce (skok, zmiana Y):
        if (posIndex < path.movements().size()) {
            var currentMovement = path.movements().get(posIndex);
            var src = currentMovement.getSrc();
            var dest = currentMovement.getDest();

            if ((src.y != dest.y || (ctx.player() != null && !ctx.player().onGround())) && !inDirectReachAndVisible) {
                return true;
            }

            // 3. Porównaj kąt marszu po ścieżce z kątem do celu
            if (!inDirectReachAndVisible && cropAimVec != null) {
                Vec3 destCenter = new Vec3(dest.x + 0.5D, eyePos.y, dest.z + 0.5D);
                Rotation pathRot = RotationUtils.calcRotationFromVec3d(eyePos, destCenter, ctx.playerRotations());
                Rotation cropRot = RotationUtils.calcRotationFromVec3d(eyePos, cropAimVec, ctx.playerRotations());

                float yawDiff = Math.abs(Mth.wrapDegrees(pathRot.getYaw() - cropRot.getYaw()));
                if (yawDiff > 22.0F) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean canInteractGrimSafe(BlockPos targetPos, Rotation targetRot, double reach, boolean mustBeTopFace) {
        if (ctx.player() == null || targetPos == null || targetRot == null) return false;
        HitResult hit = RayTraceUtils.rayTraceTowards(ctx.player(), targetRot, reach, ctx.player().isCrouching());
        if (hit instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            if (bhr.getBlockPos().equals(targetPos)) {
                return !mustBeTopFace || bhr.getDirection() == Direction.UP;
            }
        }
        return false;
    }

    private boolean isServerRotationAimed(BlockPos pos) {
        if (ctx.player() == null || pos == null) return false;
        Rotation serverRot = baritone.getLookBehavior().getEffectiveRotation().orElseGet(() -> baritone.getLookBehavior().getServerRotation());
        final boolean isGrim = Baritone.settings().antiCheatCompatibility.value
                || Baritone.settings().grimCompat.value
                || Baritone.settings().antiCheatCompat.value;
        if (serverRot == null) {
            if (isGrim) return false;
            serverRot = ctx.playerRotations();
        }
        double safeReach = isGrim ? Math.min(SWEEP_REACH, Baritone.settings().grimSafeReach.value) : Baritone.settings().grimSafeReach.value;
        double reach = Math.min(safeReach, ctx.playerController().getBlockReachDistance());
        HitResult hit = RayTraceUtils.rayTraceTowards(ctx.player(), serverRot, reach, ctx.player().isCrouching());
        return hit instanceof BlockHitResult bhr && bhr.getBlockPos().equals(pos);
    }

    // Siatka 9 punktów próbkowania na górnej ściance Farmlandu (Y = 0.9375) — środek + narożniki + krawędzie:
    private static final double[][] FARMLAND_HITSCAN_OFFSETS = {
        {0.50D, 0.50D}, // Środek (priorytet 1)
        {0.30D, 0.50D}, {0.70D, 0.50D}, {0.50D, 0.30D}, {0.50D, 0.70D}, // Krawędzie wewnętrzne
        {0.22D, 0.22D}, {0.78D, 0.22D}, {0.22D, 0.78D}, {0.78D, 0.78D}  // Narożniki (omijają krawędzie tarasów/ścian)
    };

    /**
     * Walidator GrimAC: promień MUSI trafić bezpośrednio w sam blok farmlandPos (nigdy farmlandPos.above()!)
     * oraz uderzyć wyłącznie w górną ściankę (Direction.UP).
     */
    private boolean isValidFarmlandHit(HitResult hit, BlockPos farmlandPos) {
        if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        return bhr.getBlockPos().equals(farmlandPos) && bhr.getDirection() == Direction.UP;
    }

    /**
     * Zwraca dokładny, niezasłonięty Rotation na górną ściankę (Direction.UP) grządki.
     * Jeśli grządka jest za ścianą, pod progiem ziemi lub zasłonięta przez inny blok — zwraca Optional.empty().
     */
    private Optional<Rotation> getVisiblePlantRotation(BlockPos farmlandPos, double maxReach, Rotation currentRot) {
        if (ctx.player() == null || farmlandPos == null) return Optional.empty();
        Vec3 eyePos = ctx.player().getEyePosition(1.0F);
        double maxReachSq = maxReach * maxReach;
        double topY = farmlandPos.getY() + 0.9375D;

        // Szybki pre-check: jeśli aktualna serwerowa rotacja JUŻ trafia w Direction.UP tej grządki, użyj jej natychmiast
        Rotation sRot = baritone.getLookBehavior().getServerRotation();
        if (sRot != null) {
            HitResult currentServerHit = RayTraceUtils.rayTraceTowards(ctx.player(), sRot, maxReach, ctx.player().isCrouching());
            if (isValidFarmlandHit(currentServerHit, farmlandPos)) {
                return Optional.of(sRot);
            }
        }

        Rotation bestRot = null;
        double bestAngularCost = Double.MAX_VALUE;

        for (double[] offset : FARMLAND_HITSCAN_OFFSETS) {
            Vec3 targetVec = new Vec3(
                farmlandPos.getX() + offset[0],
                topY,
                farmlandPos.getZ() + offset[1]
            );

            if (eyePos.distanceToSqr(targetVec) > maxReachSq) {
                continue;
            }

            Rotation candidateRot = RotationUtils.calcRotationFromVec3d(eyePos, targetVec, currentRot);
            HitResult hit = RayTraceUtils.rayTraceTowards(ctx.player(), candidateRot, maxReach, ctx.player().isCrouching());

            if (isValidFarmlandHit(hit, farmlandPos)) {
                double yawDiff = Math.abs(Mth.wrapDegrees(candidateRot.getYaw() - currentRot.getYaw()));
                double pitchDiff = Math.abs(candidateRot.getPitch() - currentRot.getPitch());
                double cost = yawDiff + (pitchDiff * 0.5D);
                if (cost < bestAngularCost) {
                    bestAngularCost = cost;
                    bestRot = candidateRot;
                    if (offset[0] == 0.50D && offset[1] == 0.50D && cost < 25.0D) {
                        break;
                    }
                }
            }
        }

        return Optional.ofNullable(bestRot);
    }

    private BlockHitResult getServerFarmlandHit(BlockPos farmlandPos) {
        if (ctx.player() == null || farmlandPos == null) return null;
        Rotation serverRot = baritone.getLookBehavior().getEffectiveRotation().orElseGet(() -> baritone.getLookBehavior().getServerRotation());
        final boolean isGrim = Baritone.settings().antiCheatCompatibility.value
                || Baritone.settings().grimCompat.value
                || Baritone.settings().antiCheatCompat.value;
        if (serverRot == null) {
            if (isGrim) return null;
            serverRot = ctx.playerRotations();
        }
        double safeReach = isGrim ? Math.min(SWEEP_REACH, Baritone.settings().grimSafeReach.value) : Baritone.settings().grimSafeReach.value;
        double reach = Math.min(safeReach, ctx.playerController().getBlockReachDistance());
        HitResult hit = RayTraceUtils.rayTraceTowards(ctx.player(), serverRot, reach, ctx.player().isCrouching());
        if (hit instanceof BlockHitResult bhr && isValidFarmlandHit(bhr, farmlandPos)) {
            return bhr;
        }
        return null;
    }

    private BlockHitResult getServerCropHit(BlockPos cropPos) {
        if (ctx.player() == null || cropPos == null) return null;
        Rotation serverRot = baritone.getLookBehavior().getEffectiveRotation().orElseGet(() -> baritone.getLookBehavior().getServerRotation());
        final boolean isGrim = Baritone.settings().antiCheatCompatibility.value
                || Baritone.settings().grimCompat.value
                || Baritone.settings().antiCheatCompat.value;
        if (serverRot == null) {
            if (isGrim) return null;
            serverRot = ctx.playerRotations();
        }
        double safeReach = isGrim ? Math.min(SWEEP_REACH, Baritone.settings().grimSafeReach.value) : Baritone.settings().grimSafeReach.value;
        double reach = Math.min(safeReach, ctx.playerController().getBlockReachDistance());
        HitResult hit = RayTraceUtils.rayTraceTowards(ctx.player(), serverRot, reach, ctx.player().isCrouching());
        if (hit instanceof BlockHitResult bhr && bhr.getBlockPos().equals(cropPos)) {
            return bhr;
        }
        return null;
    }

    private boolean isServerRotationAimedForPlant(BlockPos farmlandPos) {
        return getServerFarmlandHit(farmlandPos) != null;
    }

    private Vec3 getCropAimVec(BlockPos p) {
        BlockState state = ctx.world().getBlockState(p);
        if (state.getBlock() instanceof CropBlock) {
            net.minecraft.world.phys.shapes.VoxelShape shape = state.getShape(ctx.world(), p);
            double maxY = shape.isEmpty() ? 0.8D : shape.max(Direction.Axis.Y);
            // Celujemy w górną koronę kłosa pszenicy (Y + 0.50..0.85), aby promień przechodził PONAD sadzonkami z przodu!
            double targetY = p.getY() + Mth.clamp(maxY * 0.78D, 0.50D, 0.85D);
            return new Vec3(p.getX() + 0.5D, targetY, p.getZ() + 0.5D);
        }

        Vec3 head = ctx.playerHead();
        double targetY = switch (state.getBlock()) {
            case NetherWartBlock ignored -> p.getY() + 0.40D;
            case CocoaBlock ignored -> p.getY() + 0.55D;
            default -> p.getY() + 0.50D;
        };

        double dx = Mth.clamp(head.x - (p.getX() + 0.5D), -0.26D, 0.26D);
        double dz = Mth.clamp(head.z - (p.getZ() + 0.5D), -0.26D, 0.26D);
        return new Vec3(p.getX() + 0.5D + dx, targetY, p.getZ() + 0.5D + dz);
    }

    private Vec3 getClosestBoxPoint(Vec3 eyePos, BlockPos pos, boolean isFarmland) {
        double minX = pos.getX() + 0.15D, maxX = pos.getX() + 0.85D;
        double minY = pos.getY() + (isFarmland ? 0.90D : 0.15D);
        double maxY = pos.getY() + (isFarmland ? 0.9375D : 0.85D);
        double minZ = pos.getZ() + 0.15D, maxZ = pos.getZ() + 0.85D;

        double cx = Mth.clamp(eyePos.x, minX, maxX);
        double cy = Mth.clamp(eyePos.y, minY, maxY);
        double cz = Mth.clamp(eyePos.z, minZ, maxZ);
        return new Vec3(cx, cy, cz);
    }

    private Vec3 getOptimalAimVec(Vec3 eyePos, BlockPos pos, boolean isFarmland) {
        Vec3 baseAim = isFarmland ? getPlantAimVec(pos) : getCropAimVec(pos);
        Vec3 clampedBox = getClosestBoxPoint(eyePos, pos, isFarmland);
        // Interpolacja 35% w strone srodka od najblizszej krawedzi gwarantuje pewny raytrace pod GrimAC bez utraty zasiegu
        return clampedBox.lerp(baseAim, 0.35D);
    }

    private boolean isTargetInHandReach(Vec3 eyePos, BlockPos pos, double maxReach, boolean isFarmland) {
        if (pos == null || eyePos == null) return false;
        double maxReachSq = maxReach * maxReach;
        Vec3 aimVec = resolveVisibleAimPoint(eyePos, pos, isFarmland);
        if (aimVec == null) {
            return false;
        }
        return eyePos.distanceToSqr(aimVec) <= maxReachSq;
    }

    private double getAngularDistance(Rotation currentRot, Vec3 eyePos, Vec3 targetVec) {
        Rotation targetRot = RotationUtils.calcRotationFromVec3d(eyePos, targetVec, currentRot);
        double dYaw = Mth.wrapDegrees(targetRot.getYaw() - currentRot.getYaw());
        double dPitch = targetRot.getPitch() - currentRot.getPitch();
        return Math.hypot(dYaw, dPitch * 1.15D);
    }

    /**
     * Zwraca aktywny cel marszu wzdłuż rzędu (np. zagon upraw).
     */
    private Vec3 getActiveWalkTarget() {
        if (isPracaModeActive()) {
            if (pracaActiveSpineWaypoint != null) {
                return Vec3.atCenterOf(pracaActiveSpineWaypoint);
            }
            if (stableRowAnchorPos != null) {
                return Vec3.atCenterOf(stableRowAnchorPos);
            }
            if (laneForwardDir != null && laneForwardDir.lengthSqr() > 0.01D && ctx.player() != null) {
                return ctx.player().position().add(laneForwardDir.scale(4.0D));
            }
            if (ctx.player() != null) {
                return ctx.player().position();
            }
            return null;
        }
        if (stableRowAnchorPos != null) {
            return Vec3.atCenterOf(stableRowAnchorPos);
        }
        if (laneForwardDir != null && laneForwardDir.lengthSqr() > 0.01D && ctx.player() != null) {
            return ctx.player().position().add(laneForwardDir.scale(4.0D));
        }
        return null;
    }

    /**
     * Omni-Directional Walk (Rozsprzęglenie WASD od ruchu myszką).
     * Przelicza docelowy wektor marszu (travelYaw) względem aktualnego kąta kamery (lookYaw)
     * na kombinację klawiszy W/A/S/D.
     * Dzięki temu myszka może obracać się o 360° wokół bota, a bot nadal idzie stabilnie
     * wzdłuż alejki pola (lane) bez zbaczania w ściany lub wodę!
     */
    private void applyDecoupledMovement(float lookYaw, Vec3 walkTarget) {
        if (ctx.player() == null || walkTarget == null) return;
        double dx = walkTarget.x - ctx.player().getX();
        double dz = walkTarget.z - ctx.player().getZ();
        if (dx * dx + dz * dz < 0.25D) {
            baritone.getInputOverrideHandler().clearAllKeys();
            return;
        }

        float travelYaw = (float) (Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;

        // Ochrona przed wpadnięciem do wody (Argon 4)
        if (ctx.world() != null && wouldMoveIntoWater(ctx.player().position(), ctx.world(), travelYaw, 0.65D)) {
            if (!wouldMoveIntoWater(ctx.player().position(), ctx.world(), travelYaw + 45.0F, 0.65D)) {
                travelYaw += 45.0F;
            } else if (!wouldMoveIntoWater(ctx.player().position(), ctx.world(), travelYaw - 45.0F, 0.65D)) {
                travelYaw -= 45.0F;
            }
        }

        float relAngle = Mth.wrapDegrees(travelYaw - lookYaw);

        var ioh = baritone.getInputOverrideHandler();

        boolean fwd = (relAngle >= -67.5F && relAngle <= 67.5F);
        boolean back = false; // Całkowity zakaz cofania (Argon 4)
        boolean right = (relAngle >= 22.5F && relAngle <= 157.5F);
        boolean left = (relAngle <= -22.5F && relAngle >= -157.5F);

        // Selektywne blokowanie klawiszy pchających gracza w rów z wodą
        if (ctx.world() != null) {
            Vec3 pos = ctx.player().position();
            if (fwd && wouldMoveIntoWater(pos, ctx.world(), lookYaw, 0.50D)) {
                fwd = false;
            }
            if (right && wouldMoveIntoWater(pos, ctx.world(), lookYaw + 90.0F, 0.50D)) {
                right = false;
            }
            if (left && wouldMoveIntoWater(pos, ctx.world(), lookYaw - 90.0F, 0.50D)) {
                left = false;
            }
        }

        boolean sprint = fwd && Math.abs(relAngle) <= 30.0F && Baritone.settings().allowSprint.value;

        ioh.setInputForceState(Input.MOVE_FORWARD, fwd);
        ioh.setInputForceState(Input.MOVE_BACK, false);
        ioh.setInputForceState(Input.MOVE_RIGHT, right);
        ioh.setInputForceState(Input.MOVE_LEFT, left);
        ioh.setInputForceState(Input.SPRINT, sprint);
    }

    private Rotation getSafeServerRotation() {
        Rotation rot = baritone.getLookBehavior().getServerRotation();
        if (rot != null) {
            return rot;
        }
        if (ctx.player() != null) {
            return new Rotation(ctx.player().getYRot(), ctx.player().getXRot());
        }
        return new Rotation(0.0F, 0.0F);
    }

    /**
     * Oblicza, czy kontynuowanie ruchu do przodu (W) w bieżącym ticku spowoduje
     * minięcie nieobsłużonego celu (oddalanie się na granicy zasięgu) lub ucieczkę celu za plecy.
     * Nie hamuje, gdy gracz zbliża się do celu! (Argon 4)
     */
    private boolean shouldBrakeForTarget(
            Vec3 eyePos,
            float currentCamYaw,
            BlockPos target,
            double reachSq,
            boolean actedThisTick
    ) {
        if (actedThisTick || target == null || ctx.player() == null) {
            return false;
        }

        Vec3 targetCenter = Vec3.atCenterOf(target);
        double distCurSqr = eyePos.distanceToSqr(targetCenter);

        // Jeśli cel jest jeszcze poza zasięgiem ręki, musimy iść do przodu, aby do niego podejść
        if (distCurSqr > reachSq) {
            return false;
        }

        // Predykcja pozycji oczu w kolejnym ticku przy wciśniętym W (kierunek kamery + obecny pęd)
        Vec3 forwardStep = Vec3.directionFromRotation(0.0F, currentCamYaw).scale(0.22D);
        Vec3 motion = ctx.player().getDeltaMovement();
        Vec3 predictedDelta = new Vec3(
                forwardStep.x * 0.7D + motion.x * 0.3D,
                0.0D,
                forwardStep.z * 0.7D + motion.z * 0.3D
        );
        Vec3 nextEyePos = eyePos.add(predictedDelta);
        double distNextSqr = nextEyePos.distanceToSqr(targetCenter);

        // Sprawdzamy, czy dystans ROŚNIE (gracz oddala się od celu / mija go)
        boolean isMovingAway = distNextSqr > (distCurSqr + 0.0025D);

        // Margines bezpieczeństwa przed utratą zasięgu (ok. 0.30m przed końcem reach)
        double safeBrakeLimitSq = Math.max(1.0D, reachSq - 0.65D);

        // 1. Hamuj, jeśli mijamy cel i za chwilę wypadnie on poza zasięg ręki
        if (isMovingAway && distNextSqr > safeBrakeLimitSq) {
            return true;
        }

        // 2. Hamuj, jeśli cel jest blisko, ale znajduje się głęboko za plecami / z boku (> 105°),
        // żeby RotationEngine zdążył obrócić głowę zanim odejdziemy za daleko
        double dx = targetCenter.x - eyePos.x;
        double dz = targetCenter.z - eyePos.z;
        float yawToTarget = (float) (Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        float yawError = Math.abs(Mth.wrapDegrees(yawToTarget - currentCamYaw));

        return isMovingAway && yawError > 105.0F && distCurSqr > 2.25D;
    }

    /**
     * Płynne prowadzenie wzdłuż korytarza (Hold-W Continuous Walk).
     * Nie przerywa chodu, gdy w zasięgu znajdują się plony — zatrzymuje W wyłącznie wtedy,
     * gdy flaga brakeForTarget sygnalizuje ryzyko pominięcia kłosa za plecami. (Argon 4)
     */
    private boolean applyForwardOnlyCorridorSteering(
            float currentCamYaw,
            float targetMoveYaw,
            boolean brakeForTarget
    ) {
        var input = baritone.getInputOverrideHandler();
        if (ctx.player() == null) return false;

        if (brakeForTarget) {
            input.setInputForceState(Input.MOVE_FORWARD, false);
            input.setInputForceState(Input.MOVE_BACK, false);
            input.setInputForceState(Input.MOVE_LEFT, false);
            input.setInputForceState(Input.MOVE_RIGHT, false);
            input.setInputForceState(Input.SPRINT, false);
            return false;
        }

        // Ochrona przed wejściem w rów z wodą w korytarzu Boustrophedon
        if (ctx.world() != null && wouldMoveIntoWater(ctx.player().position(), ctx.world(), targetMoveYaw, 0.65D)) {
            if (!wouldMoveIntoWater(ctx.player().position(), ctx.world(), targetMoveYaw + 45.0F, 0.65D)) {
                targetMoveYaw += 45.0F;
            } else if (!wouldMoveIntoWater(ctx.player().position(), ctx.world(), targetMoveYaw - 45.0F, 0.65D)) {
                targetMoveYaw -= 45.0F;
            }
        }

        float yawDiff = Mth.wrapDegrees(targetMoveYaw - currentCamYaw);
        float absYawDiff = Math.abs(yawDiff);

        // Domyślnie trzymamy W w sposób ciągły, chyba że musimy wyhamować przed minięciem celu
        // lub kamera patrzy w stronę przeciwną do korytarza marszu (> 100°)
        boolean pressW = !brakeForTarget && absYawDiff <= 100.0F;
        boolean pressA = false;
        boolean pressD = false;

        // Delikatna korekta boczna (strafe) TYLKO gdy nie celujemy w żaden blok obok
        // W Minecraft: yaw rośnie w prawo (zgodnie z ruchem wskazówek zegara).
        // Zatem yawDiff > 0 oznacza, że cel jest po PRAWEJ stronie (pressD = MOVE_RIGHT),
        // a yawDiff < 0 oznacza, że cel jest po LEWEJ stronie (pressA = MOVE_LEFT).
        if (pressW && lockedTarget == null && absYawDiff > 15.0F && absYawDiff <= 85.0F) {
            if (yawDiff > 0.0F) {
                pressD = true;
            } else {
                pressA = true;
            }
        }

        // Blokuj klawisze, które pchnęłyby bota z krawędzi grządki do wody
        if (ctx.world() != null) {
            Vec3 pos = ctx.player().position();
            if (pressW && wouldMoveIntoWater(pos, ctx.world(), currentCamYaw, 0.50D)) {
                pressW = false;
            }
            if (pressA && wouldMoveIntoWater(pos, ctx.world(), currentCamYaw - 90.0F, 0.50D)) {
                pressA = false;
            }
            if (pressD && wouldMoveIntoWater(pos, ctx.world(), currentCamYaw + 90.0F, 0.50D)) {
                pressD = false;
            }
        }

        boolean onFarmland = isNearOrOnFarmland(ctx.player(), ctx.world());
        // GrimAC Sprint Check: sprint dozwolony wyłącznie przy czystym ruchu do przodu (pressW),
        // bez jednoczesnego strafe'owania pod dużym kątem i bez kolizji ze ścianą
        boolean canSprintSafely = pressW
                && !pressA
                && !pressD
                && !onFarmland
                && absYawDiff <= 30.0F
                && !ctx.player().horizontalCollision
                && !ctx.player().isShiftKeyDown()
                && ctx.player().getFoodData().getFoodLevel() > 6
                && Baritone.settings().allowSprint.value;

        input.setInputForceState(Input.MOVE_FORWARD, pressW);
        input.setInputForceState(Input.MOVE_BACK, false);
        input.setInputForceState(Input.MOVE_LEFT, pressA);
        input.setInputForceState(Input.MOVE_RIGHT, pressD);
        input.setInputForceState(Input.SPRINT, canSprintSafely);
        input.setInputForceState(Input.SNEAK, false);
        if (ctx.player() != null) {
            ctx.player().setSprinting(canSprintSafely);
        }
        return pressW || pressA || pressD;
    }

    /**
     * Stabilizuje cel podejścia (histereza), zapobiegając przełączaniu celów przód-tył o 180° co tick.
     */
    private BlockPos resolveStableWalkTarget(Vec3 playerPos, BlockPos candidatePos, Level world) {
        if (world == null || playerPos == null) return candidatePos;
        float refYaw = Float.isNaN(lockedLaneYaw) ? getSafeServerRotation().getYaw() : lockedLaneYaw;
        double fX = -Math.sin(Math.toRadians(refYaw));
        double fZ =  Math.cos(Math.toRadians(refYaw));

        if (lockedWalkTarget != null) {
            lockedWalkTicks++;
            BlockState st = world.getBlockState(lockedWalkTarget);
            boolean stillValid = readyForHarvest(world, lockedWalkTarget, st)
                    || (st.is(Blocks.FARMLAND) && isPlantableFarmland(lockedWalkTarget));
            double dx = (lockedWalkTarget.getX() + 0.5D) - playerPos.x;
            double dz = (lockedWalkTarget.getZ() + 0.5D) - playerPos.z;
            double distSq = dx * dx + dz * dz;
            double dist = Math.sqrt(distSq);

            double dot = (dist > 1.0E-3D) ? (dx * fX + dz * fZ) / dist : 1.0D;
            // Jeśli cel znalazł się za plecami bota – natychmiast go porzucamy, zakaz cofania!
            if (dot < -0.15D) {
                lockedWalkTarget = null;
                lockedWalkTicks = 0;
            } else {
                if (distSq < lockedWalkLastDistSq - 0.04D) {
                    lockedWalkLastDistSq = distSq;
                    lockedWalkTicks = 0; // Resetuj licznik, dopóki bot realnie zbliża się do celu
                }

                // Utrzymaj cel dopóki jest ważny, nie minęło 25 ticków i cel nie wszedł w bezpośredni zasięg ręki (SWEEP_REACH)
                if (stillValid && lockedWalkTicks <= 25 && distSq >= (SWEEP_REACH * SWEEP_REACH)) {
                    return lockedWalkTarget;
                }
            }
        }

        lockedWalkTarget = candidatePos;
        lockedWalkTicks = 0;
        lockedWalkLastDistSq = (candidatePos != null)
                ? playerPos.distanceToSqr(candidatePos.getX() + 0.5D, playerPos.y, candidatePos.getZ() + 0.5D)
                : Double.MAX_VALUE;
        return lockedWalkTarget;
    }

    private BlockPos findNextWalkTarget(Vec3 playerPos, List<BlockPos> toBreak, List<BlockPos> openFarmland) {
        boolean hasBreak = toBreak != null && !toBreak.isEmpty();
        boolean hasPlant = openFarmland != null && !openFarmland.isEmpty();
        if (!hasBreak && !hasPlant) return null;

        if (stableRowAnchorPos == null && ctx.player() != null) {
            stableRowAnchorPos = ctx.playerFeet();
        }

        float referenceYaw = Float.isNaN(lockedLaneYaw) ? getSafeServerRotation().getYaw() : lockedLaneYaw;
        double forwardX = -Math.sin(Math.toRadians(referenceYaw));
        double forwardZ =  Math.cos(Math.toRadians(referenceYaw));

        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;

        // 1. Oceń dojrzałe plony (toBreak) w pełnych 360 stopniach
        if (hasBreak) {
            for (BlockPos pos : toBreak) {
                if (isBlacklisted(pos)) continue;
                Vec3 center = Vec3.atCenterOf(pos);

                double dx = center.x - playerPos.x;
                double dz = center.z - playerPos.z;
                double horizDist = Math.hypot(dx, dz);

                double dotForward = (horizDist > 1.0E-3D) ? (dx * forwardX + dz * forwardZ) / horizDist : 1.0D;
                double lateralDist = (horizDist > 1.0E-3D) ? Math.abs(dx * forwardZ - dz * forwardX) : 0.0D;

                // Łagodny tie-breaker tylko dla bliskich kłosów (<= 4.5m); dla dalszych (8-15m) brak kary kierunkowej!
                double directionPenalty = (horizDist <= 4.5D)
                        ? ((dotForward < -0.15D) ? 2.0D : (1.0D - dotForward) * 0.8D) + Math.min(lateralDist * 0.3D, 1.5D)
                        : 0.0D;

                // Kara za ścianę/przeszkodę po drodze (Line of Sight):
                // Jeśli kłos jest blisko (<= 8m), ale zasłonięty ścianą lub płotkiem, dodaj 35m kary,
                // aby bot najpierw zbierał kłosy na otwartej przestrzeni, a nie lgnął do ściany!
                double wallPenalty = 0.0D;
                if (horizDist <= 8.0D && ctx.player() != null) {
                    Vec3 ePos = ctx.player().getEyePosition(1.0F);
                    if (!hasCleanColliderLineOfSight(ePos, pos, center)) {
                        wallPenalty = 35.0D;
                    }
                }

                double score = horizDist + directionPenalty + wallPenalty;
                if (score < bestScore) {
                    bestScore = score;
                    best = pos;
                }
            }
        }

        // 2. Oceń puste grządki (openFarmland)
        if (hasPlant) {
            for (BlockPos pos : openFarmland) {
                if (isBlacklisted(pos)) continue;
                double horizDist = Math.hypot((pos.getX() + 0.5D) - playerPos.x, (pos.getZ() + 0.5D) - playerPos.z);
                double wallPenalty = 0.0D;
                if (horizDist <= 8.0D && ctx.player() != null) {
                    Vec3 ePos = ctx.player().getEyePosition(1.0F);
                    Vec3 center = new Vec3(pos.getX() + 0.5D, pos.getY() + 0.9375D, pos.getZ() + 0.5D);
                    if (!hasCleanColliderLineOfSight(ePos, pos, center)) {
                        wallPenalty = 35.0D;
                    }
                }
                double score = horizDist + 1.5D + wallPenalty;

                if (score < bestScore) {
                    bestScore = score;
                    best = pos;
                }
            }
        }

        return best;
    }

    private void resetWatchdogOnProgress() {
        wdSinceTick = farmTicks;
        wdLevel = 0;
        teleportRetries = 0;
        wdAnchor = ctx.playerFeet();
        lastProgressTick = farmTicks;
    }

    private boolean isRotationAlignedForGrim(Rotation targetRot) {
        Rotation current = ctx.playerRotations();
        float yawDiff = Math.abs(Mth.wrapDegrees(current.getYaw() - targetRot.getYaw()));
        float pitchDiff = Math.abs(current.getPitch() - targetRot.getPitch());
        return yawDiff <= GRIM_ROTATION_TOLERANCE_DEG && pitchDiff <= GRIM_ROTATION_TOLERANCE_DEG;
    }

    private Vec3 getPlantAimVec(BlockPos farmlandPos) {
        Vec3 head = ctx.playerHead();
        double dx = Mth.clamp(head.x - (farmlandPos.getX() + 0.5D), -0.28D, 0.28D);
        double dz = Mth.clamp(head.z - (farmlandPos.getZ() + 0.5D), -0.28D, 0.28D);
        return new Vec3(farmlandPos.getX() + 0.5D + dx, farmlandPos.getY() + 0.9375D, farmlandPos.getZ() + 0.5D + dz);
    }

    private void updateSweepState(Rotation targetRotation) {
        float currentYaw = ctx.playerRotations().getYaw();
        if (!Float.isNaN(lastHarvestYaw)) {
            float delta = Mth.wrapDegrees(targetRotation.getYaw() - currentYaw);
            if (Math.abs(delta) > 1.5F) {
                sweepDirection = delta > 0.0F ? 1 : -1;
            }
        }
        lastHarvestYaw = currentYaw;
    }

    private boolean selectSeedsForPlanting() {
        if (ctx.player() == null) return false;
        ItemStack held = ctx.player().getMainHandItem();
        if (!held.isEmpty() && (replantItem == null ? isPlantable(held) : held.is(replantItem))) {
            return true;
        }
        if (!held.isEmpty() && isPlantable(held)) {
            return true;
        }
        boolean selected = (replantItem != null
                && baritone.getInventoryBehavior().throwaway(true, this::isReplantItem, true))
                || baritone.getInventoryBehavior().throwaway(true, this::isPlantable, true);
        if (selected) {
            ctx.playerController().syncHeldItem();
        }
        held = ctx.player().getMainHandItem();
        return !held.isEmpty() && isPlantable(held);
    }

    private boolean hasSufficientLightForCrop(BlockPos farmlandPos) {
        return true;
    }

    private boolean isPlantableFarmland(BlockPos farmlandPos) {
        long key = farmlandPos.asLong();
        if (unplantableBlacklist.containsKey(key)) {
            if (farmTicks < unplantableBlacklist.get(key)) {
                return false;
            }
            unplantableBlacklist.remove(key);
        }
        BlockState st = ctx.world().getBlockState(farmlandPos);
        return st.is(Blocks.FARMLAND) || st.is(Blocks.SOUL_SAND);
    }

    private void markUnplantable(BlockPos pos, List<BlockPos> openFarmland) {
        long key = pos.asLong();
        unplantableBlacklist.put(key, farmTicks + UNPLANTABLE_TTL_TICKS);
        failedPlantClicks.remove(key);
        if (openFarmland != null) {
            openFarmland.remove(pos);
        }
        farmlandSet.remove(key);
        plantQueue.remove(pos);
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

        double dirX;
        double dirZ;
        if (laneForwardDir != null && laneForwardDir.lengthSqr() > 0.01D) {
            dirX = laneForwardDir.x;
            dirZ = laneForwardDir.z;
        } else {
            Vec3 look = ctx.player() != null ? ctx.player().getLookAngle() : new Vec3(1, 0, 0);
            dirX = look.x;
            dirZ = look.z;
            double lLen = Math.sqrt(dirX * dirX + dirZ * dirZ);
            if (lLen > 0.001D) {
                dirX /= lLen;
                dirZ /= lLen;
            } else {
                dirX = 1.0D;
                dirZ = 0.0D;
            }
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
     * Zmierza bezpośrednio do kolumny (x, z) uprawy/grządki.
     * Wymaga znalezienia się dokładnie na tej samej pozycji w poziomie (x == this.x && z == this.z),
     * dzięki czemu bot nie uznaje celu za osiągnięty stojąc 1 blok obok (co wcześniej zamrażało ruch w miejscu).
     */
    /**
     * Kotwiczenie celu na koncu rzedu upraw (Row Anchor - rekomendacja Argon 4).
     * Zapobiega nieustannemu przeliczaniu A* (Path Flapping) co 100-300ms po kazdym scietym klocku.
     */
    private Goal updateOrKeepStableRowGoal(List<BlockPos> matureCrops, List<BlockPos> emptyFarmland, BetterBlockPos playerPos) {
        stableRowGoalTicks++;

        List<BlockPos> pool;
        boolean twoPassMode = Baritone.settings().farmTwoPassMode.value;
        if (twoPassMode && !plantOnly && !matureCrops.isEmpty()) {
            pool = matureCrops;
        } else if (!plantOnly && !matureCrops.isEmpty() && !emptyFarmland.isEmpty() && hasPlantableSeeds()) {
            pool = new ArrayList<>(matureCrops.size() + emptyFarmland.size());
            pool.addAll(matureCrops);
            pool.addAll(emptyFarmland);
        } else if (!plantOnly && !matureCrops.isEmpty()) {
            pool = matureCrops;
        } else {
            pool = emptyFarmland;
        }
        if (pool.isEmpty()) {
            stableRowAnchorPos = null;
            stableRowGoal = null;
            committedWalkDir = null;
            return null;
        }

        // 1. COMMITMENT LOCK (Argon 4: Eliminacja skakania Północ-Południe)
        // Jeśli bot idzie w kierunku kotwicy sektora, utrzymuj cel do 80 ticków (4s)
        // dopóki w pobliżu kotwicy (w promieniu 18m) znajdują się jakiekolwiek zadania do wykonania!
        boolean isStalled = ctx.player() != null && ctx.player().getDeltaMovement().horizontalDistanceSqr() < 0.0009D;
        if (isStalled && stableRowGoalTicks > 15) {
            stableRowAnchorPos = null;
            stableRowGoal = null;
            committedWalkDir = null;
            stableRowGoalTicks = 0;
        } else if (stableRowAnchorPos != null && stableRowGoal != null && stableRowGoalTicks < 80) {
            double distSq = playerPos.distSqr(stableRowAnchorPos.above());
            boolean anchorReached = distSq <= 4.0D;
            boolean hasTasksNear = hasValidTasksNearAnchor(stableRowAnchorPos, matureCrops, emptyFarmland);

            if (!anchorReached && hasTasksNear) {
                return stableRowGoal;
            }
        }

        // 2. Wybór nowego celu z silną histerezą kierunkową (Anti-Flap Barrier)
        BlockPos nearest = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos p : pool) {
            if (isBlacklisted(p)) continue;
            double d = scoreWalkTarget(p, playerPos);
            if (d < bestDist) {
                bestDist = d;
                nearest = p;
            }
        }
        if (nearest == null) {
            stableRowAnchorPos = null;
            stableRowGoal = null;
            committedWalkDir = null;
            return null;
        }

        BlockPos farAnchor = computeFarRowAnchor(nearest, pool);
        if (!farAnchor.equals(stableRowAnchorPos) || stableRowGoal == null) {
            stableRowAnchorPos = farAnchor;
            stableRowGoal = new GoalNear(new BetterBlockPos(farAnchor.above()), 2);
            stableRowGoalTicks = 0;

            // Zapisz wektor kierunku do nowego celu dla bariery histerezy
            double pX = ctx.player() != null ? ctx.player().getX() : playerPos.getX() + 0.5D;
            double pZ = ctx.player() != null ? ctx.player().getZ() : playerPos.getZ() + 0.5D;
            double dX = farAnchor.getX() + 0.5D - pX;
            double dZ = farAnchor.getZ() + 0.5D - pZ;
            double len = Math.hypot(dX, dZ);
            if (len > 0.1D) {
                committedWalkDir = new Vec3(dX / len, 0.0D, dZ / len);
            }
        }
        return stableRowGoal;
    }

    private boolean hasValidTasksNearAnchor(BlockPos anchor, List<BlockPos> matureCrops, List<BlockPos> emptyFarmland) {
        // Promień 18.0m (pokrywa całą 16-metrową alejkę i zapobiega przedwczesnemu porzuceniu rzędu)
        double radiusSq = 18.0D * 18.0D;
        boolean twoPassMode = Baritone.settings().farmTwoPassMode.value;
        if (twoPassMode && !matureCrops.isEmpty()) {
            for (BlockPos p : matureCrops) {
                if (!isBlacklisted(p) && Math.abs(p.getY() - anchor.getY()) <= 1 && p.distSqr(anchor) <= radiusSq) {
                    return true;
                }
            }
            return false;
        }
        for (BlockPos p : matureCrops) {
            if (!isBlacklisted(p) && Math.abs(p.getY() - anchor.getY()) <= 1 && p.distSqr(anchor) <= radiusSq) {
                return true;
            }
        }
        for (BlockPos p : emptyFarmland) {
            if (!isBlacklisted(p) && Math.abs(p.getY() - anchor.getY()) <= 1 && p.distSqr(anchor) <= radiusSq) {
                return true;
            }
        }
        return false;
    }

    private BlockPos computeFarRowAnchor(BlockPos start, List<BlockPos> pool) {
        List<BlockPos> xCorridor = new ArrayList<>();
        List<BlockPos> zCorridor = new ArrayList<>();
        int sX = start.getX();
        int sY = start.getY();
        int sZ = start.getZ();

        for (BlockPos p : pool) {
            if (isBlacklisted(p) || Math.abs(p.getY() - sY) > 1) continue;
            if (Math.abs(p.getZ() - sZ) <= 1 && Math.abs(p.getX() - sX) <= 16) {
                xCorridor.add(p);
            }
            if (Math.abs(p.getX() - sX) <= 1 && Math.abs(p.getZ() - sZ) <= 16) {
                zCorridor.add(p);
            }
        }

        List<BlockPos> dominant = (xCorridor.size() >= zCorridor.size()) ? xCorridor : zCorridor;
        if (dominant.isEmpty()) {
            return start;
        }

        BlockPos far = start;
        double maxDist = 0;
        for (BlockPos p : dominant) {
            double d = start.distSqr(p);
            if (d > maxDist) {
                maxDist = d;
                far = p;
            }
        }
        return far;
    }

    public static final class GoalFarmTarget implements Goal, IGoalRenderPos {
        public final int x;
        public final int y;
        public final int z;

        public GoalFarmTarget(BlockPos pos) {
            this.x = pos.getX();
            this.y = pos.getY();
            this.z = pos.getZ();
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            // Wymagaj wejścia bezpośrednio w kolumnę kłosa/grządki (uwzględniając Farmland Y-1),
            // aby PathExecutor NIE puszczał klawisza W 1.5 kratki przed pszenicą!
            return x == this.x && z == this.z && (y == this.y || y == this.y - 1 || y == this.y + 1);
        }

        @Override
        public double heuristic(int x, int y, int z) {
            if (isInGoal(x, y, z)) {
                return 0.0D;
            }
            int xDiff = x - this.x;
            int yDiff = Math.max(0, Math.abs(y - this.y) - 1);
            int zDiff = z - this.z;
            return GoalBlock.calculate(xDiff, yDiff, zDiff);
        }

        @Override
        public BlockPos getGoalPos() {
            return new BlockPos(this.x, this.y, this.z);
        }

        @Override
        public String toString() {
            return "GoalFarmTarget{" + x + "," + y + "," + z + "}";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof GoalFarmTarget that)) return false;
            return this.x == that.x && this.y == that.y && this.z == that.z;
        }

        @Override
        public int hashCode() {
            return (this.x * 31 + this.y) * 31 + this.z;
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
            int dx = Math.abs(x - pos.getX());
            int dy = Math.abs(y - pos.getY());
            int dz = Math.abs(z - pos.getZ());
            // Zasięg podnoszenia w Minecraft to min. 1.25m od środka gracza.
            // Tolerancja sąsiedniego bloku (dx<=1, dz<=1, dy<=1) gwarantuje, że bot
            // natychmiast zrealizuje cel i wchłonie item nawet na krawędzi farmlandu lub wody.
            return dx <= 1 && dz <= 1 && dy <= 1;
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

        List<BlockPos> validSource = new ArrayList<>();
        List<BlockPos> sameFloorSource = new ArrayList<>();
        final int playerY = playerPos.getY();
        for (BlockPos p : source) {
            if (!isBlacklisted(p)) {
                validSource.add(p);
                // Plony na tej samej wysokości co gracz (w granicach ±1 bloku — uwzględnia zaoraną ziemię i lekkie stopnie)
                if (Math.abs(p.getY() - playerY) <= 1) {
                    sameFloorSource.add(p);
                }
            }
        }
        if (validSource.isEmpty()) {
            return Collections.emptyList();
        }

        // KROK 1: Izolacja piętra. Jeśli na obecnym piętrze (Y ~= playerPos.getY()) są plony,
        // wybieramy cele WYŁĄCZNIE z tego piętra! Nigdy nie mieszamy upraw z sufitu / innego piętra
        // (np. gracz na Y=-57, uprawy nad sufitem na Y=-54).
        List<BlockPos> pool = !sameFloorSource.isEmpty() ? sameFloorSource : validSource;

        List<BlockPos> nearby = new ArrayList<>();
        double maxDistSq = 32.0D * 32.0D;
        for (BlockPos p : pool) {
            if (playerPos.distSqr(p) <= maxDistSq) {
                nearby.add(p);
            }
        }
        if (nearby.isEmpty()) {
            nearby.addAll(pool);
        }

        double forwardX;
        double forwardZ;
        if (laneForwardDir != null && laneForwardDir.lengthSqr() > 0.01D) {
            forwardX = laneForwardDir.x;
            forwardZ = laneForwardDir.z;
        } else {
            Vec3 delta = ctx.player() != null ? ctx.player().getDeltaMovement() : Vec3.ZERO;
            double dirX = delta.x;
            double dirZ = delta.z;
            double speedSq = dirX * dirX + dirZ * dirZ;
            if (speedSq > 0.003D) {
                double len = Math.sqrt(speedSq);
                forwardX = dirX / len;
                forwardZ = dirZ / len;
            } else {
                Vec3 look = ctx.player() != null ? ctx.player().getLookAngle() : new Vec3(1, 0, 0);
                double len = Math.sqrt(look.x * look.x + look.z * look.z);
                if (len > 0.001D) {
                    forwardX = look.x / len;
                    forwardZ = look.z / len;
                } else {
                    forwardX = 1.0D;
                    forwardZ = 0.0D;
                }
            }
        }

        final double playerRealX = ctx.player() != null ? ctx.player().getX() : playerPos.getX() + 0.5D;
        final double playerRealZ = ctx.player() != null ? ctx.player().getZ() : playerPos.getZ() + 0.5D;

        List<BlockPos> forwardCrops = new ArrayList<>();
        for (BlockPos p : nearby) {
            if (p.getX() == playerPos.getX() && p.getZ() == playerPos.getZ() && nearby.size() > 1) {
                continue;
            }
            // Do sprintu w przód kwalifikujemy tylko plony na tym samym piętrze
            if (Math.abs(p.getY() - playerY) > 1) {
                continue;
            }
            double dx = p.getX() + 0.5D - playerRealX;
            double dz = p.getZ() + 0.5D - playerRealZ;
            double distSq = dx * dx + dz * dz;
            if (distSq >= 2.0D) {
                double dist = Math.sqrt(distSq);
                double dot = (dx * forwardX + dz * forwardZ) / dist;
                if (dot > 0.20D) {
                    forwardCrops.add(p);
                }
            }
        }

        List<BlockPos> selected = new ArrayList<>(4);
        if (!forwardCrops.isEmpty()) {
            forwardCrops.sort(Comparator.comparingDouble(p -> {
                double dx = p.getX() + 0.5D - playerRealX;
                double dz = p.getZ() + 0.5D - playerRealZ;
                double distSq = dx * dx + dz * dz;
                double dist = Math.sqrt(distSq);
                double dot = (dx * forwardX + dz * forwardZ) / dist;
                return distSq - (dot * 20.0D);
            }));

            selected.add(forwardCrops.get(0));
            for (int i = 1; i < forwardCrops.size() && selected.size() < 3; i++) {
                BlockPos p = forwardCrops.get(i);
                boolean sufficientlyFar = true;
                for (BlockPos s : selected) {
                    if (p.distSqr(s) < 4.0D) {
                        sufficientlyFar = false;
                        break;
                    }
                }
                if (sufficientlyFar) {
                    selected.add(p);
                }
            }
        } else {
            List<BlockPos> validTurn = new ArrayList<>();
            for (BlockPos p : nearby) {
                if (p.getX() == playerPos.getX() && p.getZ() == playerPos.getZ() && nearby.size() > 1) {
                    continue;
                }
                validTurn.add(p);
            }
            if (validTurn.isEmpty()) {
                validTurn.addAll(nearby);
            }
            validTurn.sort(Comparator.comparingDouble(p -> {
                double dx = p.getX() + 0.5D - playerRealX;
                double dy = p.getY() - playerY;
                double dz = p.getZ() + 0.5D - playerRealZ;
                // Ciężka kara za różnicę wysokości (piętro wyżej/niżej)
                double distSq = dx * dx + dy * dy * 25.0D + dz * dz;
                double dist = Math.sqrt(dx * dx + dz * dz);
                double dot = (dist > 0.1D) ? (dx * forwardX + dz * forwardZ) / dist : 1.0D;
                double turnPenalty = (dot < -0.2D) ? 25.0D : 0.0D;
                return distSq + turnPenalty;
            }));

            int count = Math.min(2, validTurn.size());
            for (int i = 0; i < count; i++) {
                selected.add(validTurn.get(i));
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

    private void triggerFailsafeHomeReturn(String reason) {
        long now = System.currentTimeMillis();
        if (now - lastFailsafeTriggerTime < 15_000L) {
            return;
        }
        lastFailsafeTriggerTime = now;
        preTeleportPos = ctx.playerFeet();
        teleportCooldownTicks = 120;
        waterTicks = 0;
        postWaterGraceTicks = 0;
        postWaterSafeAnchor = null;
        lastProgressTick = farmTicks;
        baritone.getPathingBehavior().softCancelIfSafe();
        baritone.getInputOverrideHandler().clearAllKeys();
        lockedTarget = null;
        pendingInstantReplantPos = null;

        String resumeCmd = isPracaModeActive() ? "praca" : "farm";
        logDirect("§e[Failsafe] " + reason + " -> Uruchamiam powrót Home 2 (Zielone łóżko) i wznowienie: #" + resumeCmd);

        if (baritone.getHomeProcess() != null) {
            baritone.getHomeProcess().teleportToHome2AndResume(resumeCmd);
        } else {
            sendFailsafeCommand();
        }
    }

    private boolean isWaterOrFluid(Level world, BlockPos pos) {
        if (world == null || pos == null) return false;
        if (!world.getFluidState(pos).isEmpty()) return true;
        BlockState state = world.getBlockState(pos);
        return state.is(Blocks.WATER) || state.is(Blocks.WATER_CAULDRON);
    }

    private boolean wouldMoveIntoWater(Vec3 currentPos, Level world, float moveYaw, double probeDist) {
        if (world == null || currentPos == null) return false;
        double rad = Math.toRadians(moveYaw);
        double[] offsets = { -0.22D, 0.0D, 0.22D };
        for (double off : offsets) {
            double perpRad = rad + Math.PI / 2.0D;
            double testX = currentPos.x - Math.sin(rad) * probeDist + Math.cos(perpRad) * off;
            double testZ = currentPos.z + Math.cos(rad) * probeDist + Math.sin(perpRad) * off;
            double testY = currentPos.y;

            BlockPos feetPos = new BlockPos(Mth.floor(testX), Mth.floor(testY), Mth.floor(testZ));
            BlockPos belowPos = feetPos.below();

            BlockState feetState = world.getBlockState(feetPos);
            // Jeśli na wysokości stóp jest solidna powierzchnia (Farmland, płyta, lilia, podłoga), gracz na nią staje bez wpadania
            if (!feetState.getCollisionShape(world, feetPos).isEmpty() && !isWaterOrFluid(world, feetPos)) {
                continue;
            }

            if (isWaterOrFluid(world, feetPos)) {
                return true;
            }
            if (isWaterOrFluid(world, belowPos) && feetState.getCollisionShape(world, feetPos).isEmpty()) {
                return true;
            }
            if (world.getBlockState(belowPos).isAir() && isWaterOrFluid(world, belowPos.below())) {
                return true;
            }
        }
        return false;
    }

    private Vec3 findDeepInlandDrySpot(Vec3 playerPos, Level world) {
        if (world == null || playerPos == null) return null;
        BlockPos feet = new BlockPos(Mth.floor(playerPos.x), Mth.floor(playerPos.y), Mth.floor(playerPos.z));

        BlockPos bestSpot = null;
        double maxWaterDist = -1.0D;
        double minPlayerDist = Double.MAX_VALUE;

        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    BlockPos candidate = feet.offset(dx, dy, dz);
                    if (isWaterOrFluid(world, candidate) || isWaterOrFluid(world, candidate.above())) {
                        continue;
                    }
                    BlockState ground = world.getBlockState(candidate);
                    if (!ground.blocksMotion() && !ground.is(Blocks.FARMLAND)) {
                        continue;
                    }
                    BlockState space = world.getBlockState(candidate.above());
                    if (!space.isAir() && !(space.getBlock() instanceof CropBlock)) {
                        continue;
                    }

                    double waterDist = computeMinDistanceToWater(Vec3.atCenterOf(candidate), world, 3);
                    if (waterDist < 0.9D) {
                        continue;
                    }

                    double playerDist = playerPos.distanceToSqr(Vec3.atBottomCenterOf(candidate.above()));
                    if (waterDist > maxWaterDist || (Math.abs(waterDist - maxWaterDist) < 0.2D && playerDist < minPlayerDist)) {
                        maxWaterDist = waterDist;
                        minPlayerDist = playerDist;
                        bestSpot = candidate.above();
                    }
                }
            }
        }

        if (bestSpot != null) {
            return Vec3.atBottomCenterOf(bestSpot);
        }
        return null;
    }

    private double computeMinDistanceToWater(Vec3 pos, Level world, int radius) {
        if (world == null || pos == null) return Double.MAX_VALUE;
        int bx = Mth.floor(pos.x);
        int by = Mth.floor(pos.y);
        int bz = Mth.floor(pos.z);
        double minDistSq = Double.MAX_VALUE;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos check = new BlockPos(bx + dx, by + dy, bz + dz);
                    if (isWaterOrFluid(world, check)) {
                        double dSq = pos.distanceToSqr(Vec3.atCenterOf(check));
                        if (dSq < minDistSq) {
                            minDistSq = dSq;
                        }
                    }
                }
            }
        }
        return Math.sqrt(minDistSq);
    }

    // ── Anti-Stall & Active Harvest Pull (Argon 4) ──
    private void updateStallWatchdog(net.minecraft.world.entity.player.Player player, boolean actedThisTick) {
        if (player == null) return;
        double horizSpeedBps = Math.hypot(player.getDeltaMovement().x, player.getDeltaMovement().z) * 20.0D;
        stalledTargetCooldowns.entrySet().removeIf(e -> e.getValue() <= System.currentTimeMillis());

        if (!actedThisTick && horizSpeedBps < 0.12D) {
            stallTicks++;
            // Jeśli bot stoi < 0.12 bps przez 12 ticków (0.6s) -> wymuś przeskok waypointu i przełamanie impasu
            if (stallTicks >= 12) {
                if (pracaActiveSpineWaypoint != null) {
                    stalledTargetCooldowns.put(pracaActiveSpineWaypoint, System.currentTimeMillis() + 3000L);
                    farmOptimizer.pollActiveWaypoint(player.position(), 999.0D); // Wymuś kolejny waypoint Boustrophedon
                    pracaActiveSpineWaypoint = null;
                }
                if (magnetPullTarget != null && stallTicks >= 22) {
                    stalledTargetCooldowns.put(magnetPullTarget, System.currentTimeMillis() + 3500L);
                    magnetPullTarget = null;
                }
                if (stallTicks >= 35) {
                    // Całkowite odblokowanie zacięcia (Auto-Recovery bez #stop)
                    blacklistUntil.clear();
                    attempts.clear();
                    itemBlacklist.clear();
                    unplantableBlacklist.clear();
                    stalledTargetCooldowns.clear();
                    locations = null;
                    scanCooldownTicks = 0;
                    stallTicks = 0;
                    lockedTarget = null;
                    lockedPhase = ActionPhase.NONE;
                    lockedTargetTicks = 0;
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                    if (!isNearOrOnFarmland(player, ctx.world())) {
                        baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                    }
                }
            }
        } else {
            stallTicks = 0;
        }
    }

    /**
     * Gdy w zasięgu ręki (2.95m) nie ma celu (przed botem są zielone sadzonki),
     * natychmiast namierza najbliższy dojrzały kłos (toBreak) lub pustą grządkę (openFarmland) w promieniu 32 bloków.
     */
    private BlockPos findActiveMagnetTarget(Vec3 playerPos, float currentYaw, List<BlockPos> toBreak, List<BlockPos> openFarmland, boolean haveSeeds) {
        float referenceYaw = Float.isNaN(lockedLaneYaw) ? currentYaw : lockedLaneYaw;
        double forwardX = -Math.sin(Math.toRadians(referenceYaw));
        double forwardZ =  Math.cos(Math.toRadians(referenceYaw));

        // Utrzymaj dotychczasowy cel magnetyczny (histereza), dopóki nadal jest dojrzałym kłosem lub pustą grządką i NIE jest za plecami
        if (magnetPullTarget != null && !stalledTargetCooldowns.containsKey(magnetPullTarget)) {
            if (toBreak.contains(magnetPullTarget) || (haveSeeds && openFarmland.contains(magnetPullTarget))) {
                double dx = (magnetPullTarget.getX() + 0.5D) - playerPos.x;
                double dz = (magnetPullTarget.getZ() + 0.5D) - playerPos.z;
                double dist = Math.hypot(dx, dz);
                double dotForward = (dist > 1.0E-3D) ? (dx * forwardX + dz * forwardZ) / dist : 1.0D;
                if (dotForward >= -0.15D && dist >= 0.8D && dist <= 32.0D) {
                    return magnetPullTarget;
                }
            }
        }

        // Priorytet 1: Dojrzałe kłosy pszenicy (toBreak). Priorytet 2: Puste grządki (openFarmland), jeśli mamy nasiona.
        List<BlockPos> primaryPool = !toBreak.isEmpty() ? toBreak : (haveSeeds ? openFarmland : Collections.emptyList());
        if (primaryPool.isEmpty()) {
            magnetPullTarget = null;
            return null;
        }

        BlockPos bestPos = null;
        double bestScore = Double.MAX_VALUE;

        for (BlockPos pos : primaryPool) {
            if (stalledTargetCooldowns.containsKey(pos)) {
                continue;
            }
            Vec3 center = Vec3.atBottomCenterOf(pos);
            double dx = center.x - playerPos.x;
            double dz = center.z - playerPos.z;
            double distSq = dx * dx + dz * dz;
            if (distSq > (64.0D * 64.0D)) {
                continue;
            }

            double dist = Math.sqrt(distSq);
            if (dist < 1.0E-3D) continue;

            double dotForward = (dx * forwardX + dz * forwardZ) / dist;
            double lateralDist = Math.abs(dx * forwardZ - dz * forwardX);

            // Cele za plecami bota: łagodny tie-breaker dla bliskich kłosów (<= 4m), 0 kary dla dalszych (pełne 360° widzenia)
            double backwardPenalty = 0.0D;
            if (dist <= 4.0D) {
                backwardPenalty = (dotForward < -0.15D) ? 2.5D : (1.0D - dotForward) * 0.8D;
            }
            double laneDeviationPenalty = (dist <= 6.0D) ? ((lateralDist > 2.25D) ? lateralDist * 1.5D : lateralDist * 0.3D) : 0.0D;

            double score = dist + backwardPenalty + laneDeviationPenalty;
            if (score < bestScore) {
                bestScore = score;
                bestPos = pos;
            }
        }

        if (bestPos != null) {
            double dx = (bestPos.getX() + 0.5D) - playerPos.x;
            double dz = (bestPos.getZ() + 0.5D) - playerPos.z;
            float newTargetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0F);
            if (Float.isNaN(lockedLaneYaw) || Math.hypot(dx, dz) > 6.0D) {
                lockedLaneYaw = newTargetYaw;
            } else {
                float diff = Math.abs(Mth.wrapDegrees(newTargetYaw - lockedLaneYaw));
                if (diff < 70.0F) {
                    lockedLaneYaw = newTargetYaw;
                }
            }
        }

        magnetPullTarget = bestPos;
        return bestPos;
    }

    /**
     * Sprawdza, czy blok lub płyn stanowi bezpośrednie zagrożenie (lawa, ogień, kaktus, krzak słodkich jagód, magma).
     */
    private boolean isHazardBlock(Level world, BlockPos pos) {
        if (world == null || pos == null) return false;
        BlockState state = world.getBlockState(pos);
        if (!state.getFluidState().isEmpty()) {
            return true; // Woda oraz lawa traktowane jako przeszkoda dla bezpośredniego kroku WASD
        }
        Block block = state.getBlock();
        return block == Blocks.LAVA || block == Blocks.FIRE || block == Blocks.SOUL_FIRE
                || block == Blocks.MAGMA_BLOCK || block == Blocks.CACTUS || block == Blocks.SWEET_BERRY_BUSH;
    }

    private boolean isNearOrOnFarmland(net.minecraft.world.entity.player.Player player, Level level) {
        if (player == null || level == null) return false;
        BlockPos base = player.blockPosition();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 0; dy++) {
                    if (level.getBlockState(base.offset(dx, dy, dz)).is(Blocks.FARMLAND)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Sprawdza, czy najbliższe 2.0m w kierunku celu są wolne od ścian, płotków, wody, lawy i przepaści,
     * co pozwala na natychmiastowy ruch WASD bez czekania na wątek A*.
     */
    private boolean isImmediateStepSafe(Vec3 playerPos, Vec3 targetVec, Level world) {
        if (world == null || playerPos == null || targetVec == null) return false;
        double dx = targetVec.x - playerPos.x;
        double dz = targetVec.z - playerPos.z;
        double dist = Math.hypot(dx, dz);
        if (dist < 1e-3D) return true;

        double stepCheckDist = Math.min(dist, 2.2D);
        double dirX = dx / dist;
        double dirZ = dz / dist;
        double baseY = Math.floor(playerPos.y + 0.45D); // Kompensacja wysokości Farmland (0.9375)

        double[] lateralOffsets = { -0.30D, 0.0D, 0.30D };

        for (double d = 0.4D; d <= stepCheckDist; d += 0.45D) {
            for (double lat : lateralOffsets) {
                double cx = playerPos.x + dirX * d - dirZ * lat;
                double cz = playerPos.z + dirZ * d + dirX * lat;
                BlockPos legPos = BlockPos.containing(cx, baseY + 0.2D, cz);
                BlockPos headPos = legPos.above();
                BlockPos floorPos = legPos.below();
                BlockPos twoBelowPos = floorPos.below();

                if (isHazardBlock(world, legPos) || isHazardBlock(world, headPos) || isHazardBlock(world, floorPos)) {
                    return false;
                }
                if (!world.getBlockState(legPos).getCollisionShape(world, legPos).isEmpty()
                        || !world.getBlockState(headPos).getCollisionShape(world, headPos).isEmpty()) {
                    return false;
                }
                // Ochrona przed spadkiem z krawędzi (> 1 blok w dół)
                BlockState floorState = world.getBlockState(floorPos);
                if (floorState.isAir() && world.getBlockState(twoBelowPos).isAir()) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Sprawdza cały odcinek od gracza do punktu docelowego (co 0.40m), a nie tylko pierwsze 2.2m.
     * Wykrywa rowy wodne, lawę, płotki i ściany.
     */
    private boolean isEntireSegmentSafeAndDry(Vec3 start, Vec3 end, Level world) {
        if (world == null || start == null || end == null) return false;
        Vec3 diff = end.subtract(start);
        double horizDist = Math.hypot(diff.x, diff.z);
        if (horizDist < 0.2D) return true;

        int steps = Mth.ceil(horizDist / 0.40D);
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / (double) steps;
            double cx = start.x + diff.x * t;
            double cz = start.z + diff.z * t;
            BlockPos checkFeet = new BlockPos(Mth.floor(cx), Mth.floor(start.y + 0.07D), Mth.floor(cz));
            BlockPos checkGround = checkFeet.below();

            if (!world.getFluidState(checkFeet).isEmpty() || !world.getFluidState(checkGround).isEmpty()) {
                return false; // Woda na trasie -> użyj A*, aby ominąć rów!
            }
            if (isHazardBlock(world, checkFeet) || isHazardBlock(world, checkGround)) {
                return false;
            }
            if (!world.getBlockState(checkFeet).getCollisionShape(world, checkFeet).isEmpty()) {
                return false; // Przeszkoda na wysokości nóg -> użyj A*!
            }
            if (world.getBlockState(checkGround).getCollisionShape(world, checkGround).isEmpty()) {
                return false; // Przepaść / brak podłoża -> użyj A*!
            }
        }
        return true;
    }

    /**
     * Wyznacza bezpieczny wektor opływu wokół źródła wody na polu,
     * aby bot nie zatrzymywał się przed wodą na 0.0 bps, lecz płynnie obchodził ją po sąsiednich grządkach.
     */
    private Vec3 findSafeWaterSidestepTarget(Vec3 playerPos, Vec3 targetPos, Level world) {
        if (playerPos == null || targetPos == null || world == null) return null;
        double dx = targetPos.x - playerPos.x;
        double dz = targetPos.z - playerPos.z;
        double baseAngle = Math.atan2(dz, dx);
        double stepDist = 1.15D;

        // Sprawdzamy odchylenia kątowe omijające oczko wodne (w radianach: ±45°, ±75°, ±90°)
        double[] angleOffsets = {
            Math.toRadians(45.0D), -Math.toRadians(45.0D),
            Math.toRadians(75.0D), -Math.toRadians(75.0D),
            Math.toRadians(90.0D), -Math.toRadians(90.0D)
        };

        for (double offset : angleOffsets) {
            double candAngle = baseAngle + offset;
            Vec3 candidate = new Vec3(
                    playerPos.x + Math.cos(candAngle) * stepDist,
                    playerPos.y,
                    playerPos.z + Math.sin(candAngle) * stepDist
            );
            if (isImmediateStepSafe(playerPos, candidate, world)) {
                return candidate;
            }
        }
        return null;
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
        lockedTarget = null;
        lookAheadTarget = null;
        cachedCompositeGoal = null;
        lastGoalFingerprint = 0L;
        ticksSinceGoalRebuild = 0;
        lockedPhase = ActionPhase.NONE;
        lockedTargetTicks = 0;
        currentTargetPos = null;
        targetTicks = 0;
        currentPlantTargetPos = null;
        plantTargetTicks = 0;
        immediateReplantPos = null;
        pendingReplants.clear();
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
        lastAimedCropPos = null;
        serverAimedTicks = 0;
        laneForwardDir = Vec3.ZERO;
        committedWalkDir = null;
        lastProgressPos = null;
        lockedLaneYaw = Float.NaN;
        magnetPullTarget = null;
        lockedWalkTarget = null;
        lockedWalkTicks = 0;
        // wdLevel celowo NIE jest zerowany — eskalacja watchdoga ma przeżyć reset stanu
    }

    private BlockPos findWaterEscapeTarget(BetterBlockPos feet) {
        if (ctx.world() == null || feet == null) return null;
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        int pX = feet.getX();
        int pY = feet.getY();
        int pZ = feet.getZ();

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                for (int dy = 0; dy <= 2; dy++) {
                    BlockPos check = new BlockPos(pX + dx, pY + dy, pZ + dz);
                    if (!ctx.world().getFluidState(check).isEmpty()) continue;
                    BlockState state = ctx.world().getBlockState(check);
                    if (state.isAir()) continue;
                    if (!state.blocksMotion() && !state.is(Blocks.FARMLAND)) continue;

                    BlockPos up = check.above();
                    if (!ctx.world().getFluidState(up).isEmpty()) continue;
                    BlockState upState = ctx.world().getBlockState(up);
                    if (!upState.getCollisionShape(ctx.world(), up).isEmpty() && !(upState.getBlock() instanceof CropBlock)) {
                        continue;
                    }

                    double d = feet.distSqr(check);
                    if (d < bestDist) {
                        bestDist = d;
                        best = check;
                    }
                }
            }
        }
        return best;
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
            resetWorkState();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
    }

    private PathingCommand onTickInternal(boolean calcFailed, boolean isSafeToCancel) {
        farmTicks++;
        pendingCalcFailed |= calcFailed;
        if (isPracaModeActive()) {
            ensureLowLatencyBaritoneSettings();
        }

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
                    logDirect("§e[Farm] Teleport nie nastąpił (" + teleportRetries + "/3), ponawiam powrót Home 2...");
                    triggerFailsafeHomeReturn("Ponowienie teleportu (" + teleportRetries + "/3)");
                    teleportCooldownTicks = 120;
                } else {
                    logDirect("§e[Farm] Teleport nie powiódł się po 3 próbach – wykonuję miękki reset i wznawiam pracę (bez #stop).");
                    teleportRetries = 0;
                    wdLevel = 0;
                    resetWorkState();
                    blacklistUntil.clear();
                    unplantableBlacklist.clear();
                    itemBlacklist.clear();
                    pendingReplants.clear();
                    resetWatchdogOnProgress();
                    baritone.getPathingBehavior().softCancelIfSafe();
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
            final int effectiveMaxSize = Math.max(maxSize, 65536);

            final List<Block> scan = new ArrayList<>();
            // KRYTYCZNE: Zawsze skanujemy FARMLAND, SOUL_SAND i JUNGLE_LOG, aby puste grządki/podłoża były widoczne dla bota w całym promieniu farmy!
            scan.add(Blocks.FARMLAND);
            scan.add(Blocks.SOUL_SAND);
            scan.add(Blocks.JUNGLE_LOG);
            if (!plantOnly) {
                if (Baritone.settings().farmWheatOnly.value) {
                    scan.add(Blocks.WHEAT);
                } else {
                    for (Harvest harvest : Harvest.values()) {
                        if (!scan.contains(harvest.block)) {
                            scan.add(harvest.block);
                        }
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
        final boolean haveSeeds = hasPlantableSeeds() || hasAnyPlantableInInventory();

        List<BlockPos> toBreak = new ArrayList<>();
        List<BlockPos> openFarmland = new ArrayList<>();
        List<BlockPos> bonemealable = new ArrayList<>();
        List<BlockPos> openSoulsand = new ArrayList<>();
        List<BlockPos> openLog = new ArrayList<>();

        boolean shouldFullRefresh = locations == null || (cachedToBreak.isEmpty() && cachedOpenFarmland.isEmpty())
                || (++fullFieldScanTicker >= 20);

        if (shouldFullRefresh) {
            fullFieldScanTicker = 0;
            cachedToBreak.clear();
            cachedOpenFarmland.clear();
            cachedBonemealable.clear();
            cachedOpenSoulsand.clear();
            cachedOpenLog.clear();
            toBreakSet.clear();
            farmlandSet.clear();

            BetterBlockPos playerFeet = ctx.playerFeet();
            int plX = playerFeet.getX();
            int plZ = playerFeet.getZ();
            double maxActiveDistSqr = 48.0D * 48.0D;

            for (BlockPos pos : locationsSnapshot) {
                if (range != 0 && pos.distSqr(center) > range * range) {
                    continue;
                }
                double pdx = pos.getX() - plX;
                double pdz = pos.getZ() - plZ;
                if (pdx * pdx + pdz * pdz > maxActiveDistSqr) {
                    continue;
                }

                BlockState state = ctx.world().getBlockState(pos);
                if (state.getBlock() == Blocks.FARMLAND) {
                    persistentFarmlandCache.add(pos.asLong());
                    BlockPos above = pos.above();
                    BlockState aboveState = ctx.world().getBlockState(above);
                    if (!plantOnly && readyForHarvest(ctx.world(), above, aboveState)) {
                        if (toBreakSet.add(above.asLong())) {
                            cachedToBreak.add(above);
                        }
                    } else if (aboveState.isAir() || aboveState.canBeReplaced()) {
                        if (farmlandSet.add(pos.asLong())) {
                            cachedOpenFarmland.add(pos);
                        }
                    }
                    continue;
                }
                if (state.getBlock() == Blocks.SOUL_SAND) {
                    persistentFarmlandCache.add(pos.asLong());
                    BlockPos above = pos.above();
                    BlockState aboveState = ctx.world().getBlockState(above);
                    if (!plantOnly && readyForHarvest(ctx.world(), above, aboveState)) {
                        if (toBreakSet.add(above.asLong())) {
                            cachedToBreak.add(above);
                        }
                    } else if (aboveState.isAir() || aboveState.canBeReplaced()) {
                        cachedOpenSoulsand.add(pos);
                    }
                    continue;
                }
                if (state.getBlock() == Blocks.JUNGLE_LOG) {
                    persistentFarmlandCache.add(pos.asLong());
                    for (Direction direction : Direction.Plane.HORIZONTAL) {
                        if (ctx.world().getBlockState(pos.relative(direction)).getBlock() instanceof AirBlock) {
                            cachedOpenLog.add(pos);
                            break;
                        }
                    }
                    continue;
                }
                if (!plantOnly && readyForHarvest(ctx.world(), pos, state)) {
                    if (toBreakSet.add(pos.asLong())) {
                        cachedToBreak.add(pos);
                    }
                    persistentFarmlandCache.add(pos.below().asLong());
                    continue;
                }
                if (haveBoneMeal && state.getBlock() instanceof BonemealableBlock ig) {
                    if (ig.isValidBonemealTarget(ctx.world(), pos, state)
                            && ig.isBonemealSuccess(ctx.world(), ctx.world().random, pos, state)) {
                        cachedBonemealable.add(pos);
                    }
                }
            }

            // Szybki skan uzupełniający do 32 celów, jeśli sektor lokalny 48m jest pusty
            if (cachedToBreak.isEmpty() && cachedOpenFarmland.isEmpty()) {
                for (BlockPos pos : locationsSnapshot) {
                    if (range != 0 && pos.distSqr(center) > range * range) {
                        continue;
                    }
                    BlockState state = ctx.world().getBlockState(pos);
                    if (state.getBlock() == Blocks.FARMLAND) {
                        persistentFarmlandCache.add(pos.asLong());
                        BlockPos above = pos.above();
                        BlockState aboveState = ctx.world().getBlockState(above);
                        if (!plantOnly && readyForHarvest(ctx.world(), above, aboveState)) {
                            if (toBreakSet.add(above.asLong())) {
                                cachedToBreak.add(above);
                            }
                        } else if (aboveState.isAir() || aboveState.canBeReplaced()) {
                            if (farmlandSet.add(pos.asLong())) {
                                cachedOpenFarmland.add(pos);
                            }
                        }
                    } else if (!plantOnly && readyForHarvest(ctx.world(), pos, state)) {
                        if (toBreakSet.add(pos.asLong())) {
                            cachedToBreak.add(pos);
                        }
                        persistentFarmlandCache.add(pos.below().asLong());
                    }
                    if (cachedToBreak.size() >= 32 || cachedOpenFarmland.size() >= 32) {
                        break;
                    }
                }
            }
        }

        toBreak.addAll(cachedToBreak);
        openFarmland.addAll(cachedOpenFarmland);
        bonemealable.addAll(cachedBonemealable);
        openSoulsand.addAll(cachedOpenSoulsand);
        openLog.addAll(cachedOpenLog);

        BetterBlockPos playerPos = ctx.playerFeet();
        int plX = playerPos.getX();
        int plY = playerPos.getY();
        int plZ = playerPos.getZ();

        // ─────────────────────────────────────────────────────────────────
        // 2b. SPRAWDZENIE PERSISTENT SOIL CACHE (Throttled & Distance Capped)
        // ─────────────────────────────────────────────────────────────────
        if (shouldFullRefresh || farmTicks % 5 == 0) {
            double maxSoilDistSqr = 24.0D * 24.0D;
            it.unimi.dsi.fastutil.longs.LongIterator soilIter = persistentFarmlandCache.iterator();
            int soilChecks = 0;
            while (soilIter.hasNext() && ++soilChecks < 300) {
                long soilKey = soilIter.nextLong();
                int sx = BlockPos.getX(soilKey);
                int sy = BlockPos.getY(soilKey);
                int sz = BlockPos.getZ(soilKey);

                double sdx = sx - plX;
                double sdy = sy - plY;
                double sdz = sz - plZ;
                if (sdx * sdx + sdz * sdz > maxSoilDistSqr || Math.abs(sdy) > 16) {
                    continue;
                }
                if (range != 0) {
                    double scdx = sx - center.getX();
                    double scdz = sz - center.getZ();
                    if (scdx * scdx + scdz * scdz > (double) range * range) {
                        continue;
                    }
                }

                mut.set(sx, sy, sz);
                BlockState groundState = ctx.world().getBlockState(mut);
                if (groundState.is(Blocks.FARMLAND)) {
                    up.set(sx, sy + 1, sz);
                    BlockState cropState = ctx.world().getBlockState(up);
                    if (!plantOnly && readyForHarvest(ctx.world(), up, cropState)) {
                        if (toBreakSet.add(up.asLong())) {
                            BlockPos imm = up.immutable();
                            toBreak.add(imm);
                            cachedToBreak.add(imm);
                        }
                    } else if ((cropState.isAir() || cropState.canBeReplaced()) && farmlandSet.add(soilKey)) {
                        BlockPos imm = mut.immutable();
                        openFarmland.add(imm);
                        cachedOpenFarmland.add(imm);
                    }
                } else if (groundState.is(Blocks.SOUL_SAND)) {
                    up.set(sx, sy + 1, sz);
                    BlockState cropState = ctx.world().getBlockState(up);
                    if (!plantOnly && readyForHarvest(ctx.world(), up, cropState)) {
                        if (toBreakSet.add(up.asLong())) {
                            BlockPos imm = up.immutable();
                            toBreak.add(imm);
                            cachedToBreak.add(imm);
                        }
                    } else if (cropState.isAir() || cropState.canBeReplaced()) {
                        BlockPos imm = mut.immutable();
                        openSoulsand.add(imm);
                        cachedOpenSoulsand.add(imm);
                    }
                } else if (groundState.is(Blocks.JUNGLE_LOG)) {
                    for (Direction direction : Direction.Plane.HORIZONTAL) {
                        if (ctx.world().getBlockState(mut.relative(direction)).getBlock() instanceof AirBlock) {
                            BlockPos imm = mut.immutable();
                            openLog.add(imm);
                            cachedOpenLog.add(imm);
                            break;
                        }
                    }
                } else if (!groundState.isAir() && !(groundState.getBlock() instanceof CropBlock)) {
                    // Grządka została zniszczona lub zdeptana w zwykłą ziemię
                    soilIter.remove();
                }
            }
        }

        // Cykliczne oczyszczanie cache z obiektów poza strefą działania gracza (>96m)
        if (++cachePruneTicker >= 100) {
            cachePruneTicker = 0;
            double pruneDistSqr = 96.0D * 96.0D;
            it.unimi.dsi.fastutil.longs.LongIterator pruneIter = persistentFarmlandCache.iterator();
            while (pruneIter.hasNext()) {
                long key = pruneIter.nextLong();
                int kx = BlockPos.getX(key);
                int kz = BlockPos.getZ(key);
                double dkx = kx - plX;
                double dkz = kz - plZ;
                if (dkx * dkx + dkz * dkz > pruneDistSqr) {
                    pruneIter.remove();
                }
            }
        }

        // Aktualizuj kierunek marszu wzdłuż rzędu (Lane Forward Direction)
        Vec3 currentPos = ctx.player().position();
        if (lastProgressPos != null) {
            Vec3 delta = new Vec3(currentPos.x - lastProgressPos.x, 0.0D, currentPos.z - lastProgressPos.z);
            if (delta.lengthSqr() > 0.09D) { // gracz przesunął się o > 0.3 bloku
                Vec3 norm = delta.normalize();
                laneForwardDir = (laneForwardDir.lengthSqr() < 0.01D)
                        ? norm
                        : laneForwardDir.scale(0.75D).add(norm.scale(0.25D)).normalize();
                lastProgressPos = currentPos;
            }
        } else {
            lastProgressPos = currentPos;
            Vec3 look = ctx.player().getViewVector(1.0F);
            laneForwardDir = new Vec3(look.x, 0.0D, look.z).normalize();
        }

        // ─────────────────────────────────────────────────────────────────
        // 3. TIERED LOKALNY SKAN 3D WOKÓŁ GRACZA (95% mniej zapytań getBlockState)
        // ─────────────────────────────────────────────────────────────────
        int pX = playerPos.getX();
        int pY = playerPos.getY();
        int pZ = playerPos.getZ();
        boolean wideScanTick = (farmTicks % 15 == 0) || (toBreak.isEmpty() && openFarmland.isEmpty());
        int hRadius = wideScanTick ? 18 : 5;
        int vRadius = wideScanTick ? 7 : 2;

        for (int dx = -hRadius; dx <= hRadius; dx++) {
            for (int dz = -hRadius; dz <= hRadius; dz++) {
                for (int dy = -vRadius; dy <= vRadius; dy++) {
                    mut.set(pX + dx, pY + dy, pZ + dz);
                    if (range != 0 && mut.distSqr(center) > (double) range * range)
                        continue;
                    BlockState st = ctx.world().getBlockState(mut);
                    if (st.isAir()) continue;

                    if (!plantOnly && readyForHarvest(ctx.world(), mut, st)) {
                        if (toBreakSet.add(mut.asLong())) {
                            BlockPos imm = mut.immutable();
                            toBreak.add(imm);
                            cachedToBreak.add(imm);
                        }
                        persistentFarmlandCache.add(mut.below().asLong());
                    } else if (st.is(Blocks.FARMLAND)) {
                        persistentFarmlandCache.add(mut.asLong());
                        up.set(mut.getX(), mut.getY() + 1, mut.getZ());
                        BlockState upState = ctx.world().getBlockState(up);
                        if (!plantOnly && readyForHarvest(ctx.world(), up, upState)) {
                            if (toBreakSet.add(up.asLong())) {
                                BlockPos upImm = up.immutable();
                                toBreak.add(upImm);
                                cachedToBreak.add(upImm);
                            }
                        } else if ((upState.isAir() || upState.canBeReplaced()) && farmlandSet.add(mut.asLong())) {
                            BlockPos imm = mut.immutable();
                            openFarmland.add(imm);
                            cachedOpenFarmland.add(imm);
                        }
                    } else if (st.is(Blocks.SOUL_SAND)) {
                        persistentFarmlandCache.add(mut.asLong());
                        up.set(mut.getX(), mut.getY() + 1, mut.getZ());
                        BlockState upState = ctx.world().getBlockState(up);
                        if (!plantOnly && readyForHarvest(ctx.world(), up, upState)) {
                            if (toBreakSet.add(up.asLong())) {
                                BlockPos upImm = up.immutable();
                                toBreak.add(upImm);
                                cachedToBreak.add(upImm);
                            }
                        } else if (upState.isAir() || upState.canBeReplaced()) {
                            BlockPos imm = mut.immutable();
                            openSoulsand.add(imm);
                            cachedOpenSoulsand.add(imm);
                        }
                    } else if (st.is(Blocks.JUNGLE_LOG)) {
                        persistentFarmlandCache.add(mut.asLong());
                        for (Direction direction : Direction.Plane.HORIZONTAL) {
                            if (ctx.world().getBlockState(mut.relative(direction)).getBlock() instanceof AirBlock) {
                                BlockPos imm = mut.immutable();
                                openLog.add(imm);
                                cachedOpenLog.add(imm);
                                break;
                            }
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
            // Jeśli bot ma wciąż plony/sadzonki w zasięgu, nie odpalaj gwałtownego failsafe /home 2, lecz zresetuj watchdog
            if (hasWork) {
                lastProgressTick = farmTicks;
                resetWatchdogOnProgress();
            } else {
                triggerFailsafeHomeReturn("Brak postępu przez " + (timeoutSec / 60) + " min");
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
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
        } else if (playerPos.distSqr(wdAnchor) >= 0.25D || baritone.getPathingBehavior().hasPath() || farmed != wdSig || isInventoryFull()) {
            wdAnchor = playerPos;
            wdSig = farmed;
            wdSinceTick = farmTicks;
            wdLevel = 0;
        }

        // ─────────────────────────────────────────────────────────────────
        // 5. BEZPIECZNA UCIECZKA Z WODY ORAZ POST-WATER GRACE (Argon 4)
        // ─────────────────────────────────────────────────────────────────
        if (ctx.player().isInWater() || ctx.player().isUnderWater()) {
            waterTicks++;
            postWaterGraceTicks = 25;
            postWaterSafeAnchor = findDeepInlandDrySpot(ctx.player().position(), ctx.world());
            // Pulsacyjny skok: gdy pod wodą, trzymaj JUMP by wypłynąć.
            // Gdy na powierzchni, skacz co 2 ticki by wyskoczyć na brzeg (wypornościowy swim-hop bez flag GrimAC Jesus/Simulation)
            boolean jumpPulse = ctx.player().isUnderWater() || (waterTicks % 2 == 0);
            baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, jumpPulse);
            baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, true);
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);

            BlockPos escapePos = findWaterEscapeTarget(playerPos);
            if (escapePos != null) {
                Vec3 eyePos = ctx.player().getEyePosition(1.0F);
                Vec3 targetVec = Vec3.atCenterOf(escapePos).add(0, 0.45D, 0);
                Rotation currentRot = (baritone.getLookBehavior().getServerRotation() != null)
                        ? baritone.getLookBehavior().getServerRotation()
                        : ctx.playerRotations();
                Rotation escapeRot = RotationUtils.calcRotationFromVec3d(eyePos, targetVec, currentRot);
                // Pitch lekko w dół (+12.0F) żeby wektor parł w wierzch bloku grządki
                baritone.getLookBehavior().updateTarget(new Rotation(escapeRot.getYaw(), 12.0F), true);
            }

            if (waterTicks > 120) {
                triggerFailsafeHomeReturn("Utknięcie w wodzie (>6s)");
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        } else {
            waterTicks = 0;
        }

        // Post-water grace period: dopóki bot nie oddali się na bezpieczną odległość od wody (>= 1.35m),
        // idzie WYŁĄCZNIE w głąb pola i nie pozwala kamerze celować w kłosy przy krawędzi kanału!
        if (postWaterGraceTicks > 0) {
            postWaterGraceTicks--;
            if (postWaterSafeAnchor == null) {
                postWaterSafeAnchor = findDeepInlandDrySpot(ctx.player().position(), ctx.world());
            }
            double distToWater = computeMinDistanceToWater(ctx.player().position(), ctx.world(), 2);
            if (distToWater < 1.35D && postWaterSafeAnchor != null) {
                Vec3 eye = ctx.player().getEyePosition(1.0F);
                Rotation escapeRot = RotationUtils.calcRotationFromVec3d(eye, postWaterSafeAnchor, getSafeServerRotation());
                baritone.getLookBehavior().updateTarget(new Rotation(escapeRot.getYaw(), 10.0F), true);
                var input = baritone.getInputOverrideHandler();
                input.clearAllKeys();
                input.setInputForceState(Input.MOVE_FORWARD, true);
                input.setInputForceState(Input.SPRINT, false);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            } else if (distToWater >= 1.35D) {
                postWaterGraceTicks = 0;
                postWaterSafeAnchor = null;
            }
        }

        final boolean fastMode = Baritone.settings().farmFastMode.value;
        final boolean isGrim = Baritone.settings().antiCheatCompatibility.value || Baritone.settings().grimCompat.value || Baritone.settings().antiCheatCompat.value;
        double safeReach = isGrim ? Math.min(SWEEP_REACH, Baritone.settings().grimSafeReach.value) : 3.95D;
        double blockReachDistance = Math.min(safeReach, ctx.playerController().getBlockReachDistance());
        final Vec3 head = ctx.playerHead();
        final double reach2 = blockReachDistance * blockReachDistance;
        boolean handledNearbyAction = false;
        boolean acted = false; // Pkt 5: Maksymalnie 1 interakcja pakietowa na tick!

        // SingleItemFarmLoop: Upewnij się, że gracz trzyma nasiona w ręce (0 zmian hotbara podczas ścinania i sadzenia)
        if (haveSeeds && ctx.player() != null && !isPlantable(ctx.player().getMainHandItem())) {
            selectSeedsForPlanting();
        }

        Rotation currentRefRot = (baritone.getLookBehavior().getServerRotation() != null)
                ? baritone.getLookBehavior().getServerRotation()
                : ctx.playerRotations();

        // Dynamiczne wyznaczanie kierunku korytarza (laneForwardDir) wzdłuż ruchu bota:
        Vec3 curVel = ctx.player().getDeltaMovement();
        if (curVel.horizontalDistanceSqr() > 0.005D) {
            laneForwardDir = new Vec3(curVel.x, 0, curVel.z).normalize();
        } else if (baritone.getPathingBehavior().hasPath() && baritone.getPathingBehavior().getCurrent() != null) {
            var current = baritone.getPathingBehavior().getCurrent();
            var path = current.getPath();
            int curIdx = current.getPosition();
            if (path != null && curIdx + 1 < path.movements().size()) {
                BetterBlockPos curMove = path.movements().get(curIdx).getDest();
                BetterBlockPos nextMove = path.movements().get(curIdx + 1).getDest();
                double dx = nextMove.x - curMove.x;
                double dz = nextMove.z - curMove.z;
                if (dx * dx + dz * dz > 0.01D) {
                    laneForwardDir = new Vec3(dx, 0, dz).normalize();
                }
            }
        } else {
            Vec3 look = ctx.player().getLookAngle();
            if (look.horizontalDistanceSqr() > 0.01D) {
                laneForwardDir = new Vec3(look.x, 0, look.z).normalize();
            }
        }

        final int playerY = playerPos.getY();
        final Vec3 eyePos = ctx.player().getEyePosition(1.0F);

        // A. Weryfikacja aktywnego celu z lockiem (Sticky Target Lock między tickami)
        if (lockedTarget != null) {
            boolean valid = false;
            if (!isBlacklisted(lockedTarget) && Math.abs(lockedTarget.getY() - playerY) <= 1) {
                if (lockedPhase == ActionPhase.HARVEST) {
                    if (isTargetInHandReach(eyePos, lockedTarget, blockReachDistance, false)
                            && readyForHarvest(ctx.world(), lockedTarget, ctx.world().getBlockState(lockedTarget))) {
                        valid = true;
                    }
                } else if (lockedPhase == ActionPhase.REPLANT) {
                    BlockState aboveSt = ctx.world().getBlockState(lockedTarget.above());
                    double plantReach = blockReachDistance;
                    if (haveSeeds && isTargetInHandReach(eyePos, lockedTarget, plantReach, true)
                            && isPlantableFarmland(lockedTarget)
                            && (aboveSt.isAir() || aboveSt.canBeReplaced() || aboveSt.getBlock() instanceof CropBlock)) {
                        valid = true;
                    }
                }
            }
            if (!valid) {
                lockedTarget = (lookAheadTarget != null && !isBlacklisted(lookAheadTarget)) ? lookAheadTarget : null;
                lookAheadTarget = null;
                lockedPhase = ActionPhase.NONE;
                lockedTargetTicks = 0;
            }
        }

        // B. Wybór nowego celu TYLKO gdy nie mamy aktywnego locka
        if (lockedTarget == null) {
            Vec3 lookVec = ctx.player().getViewVector(1.0F);
            Vec3 velVec = ctx.player().getDeltaMovement();

            // ==================== ETAP 1: BATCH HARVEST (Bezwzględny priorytet: najpierw zbierz wszystkie dojrzałe kłosy w zasięgu) ====================
            // Zgodnie z instrukcją użytkownika: bot najpierw zbiera dany kłos i WSZYSTKIE INNE dojrzałe kłosy w zasięgu ręki (promień 360°),
            // celując bezpośrednio w koronę kłosa, bez schylania się do ziemi dopóki wokół rosną plony!
            int curPlX = playerPos.getX();
            int curPlZ = playerPos.getZ();
            if (!plantOnly && !toBreak.isEmpty()) {
                candidates.clear();
                for (BlockPos p : toBreak) {
                    if (Math.abs(p.getX() - curPlX) > 5 || Math.abs(p.getZ() - curPlZ) > 5) {
                        continue;
                    }
                    if (!isBlacklisted(p)
                            && Math.abs(p.getY() - playerY) <= 1
                            && isTargetInHandReach(eyePos, p, blockReachDistance, false)
                            && readyForHarvest(ctx.world(), p, ctx.world().getBlockState(p))) {
                        candidates.add(p);
                    }
                }

                if (!candidates.isEmpty()) {
                    FarmPathOptimizer.TargetPair pair = FarmPathOptimizer.selectOptimalTargets(
                            eyePos, lookVec, velVec, lockedTarget, candidates, reach2
                    );
                    lockedTarget = pair.primary().immutable();
                    lookAheadTarget = (pair.lookAhead() != null && !pair.lookAhead().equals(lockedTarget)) ? pair.lookAhead().immutable() : null;
                    lockedPhase = ActionPhase.HARVEST;
                    lockedTargetTicks = 0;
                }
            }

            // ==================== ETAP 2: BATCH REPLANT (Dopiero gdy w zasięgu NIE MA już kłosów do zebrania) ====================
            // Dopiero gdy WSZYSTKIE dojrzałe plony w reachu zostały wykoszone (pole czyste), bot seryjnie obsiewa wolny Farmland w zasięgu
            if (lockedTarget == null && Baritone.settings().replantCrops.value && haveSeeds && !openFarmland.isEmpty()) {
                candidates.clear();
                double plantReach = blockReachDistance;

                for (BlockPos p : openFarmland) {
                    if (Math.abs(p.getX() - curPlX) > 5 || Math.abs(p.getZ() - curPlZ) > 5) {
                        continue;
                    }
                    if (isBlacklisted(p) || Math.abs(p.getY() - playerY) > 1) continue;
                    if (!ctx.world().getBlockState(p.above()).isAir()) continue;
                    if (!isPlantableFarmland(p)) continue;

                    if (isTargetInHandReach(eyePos, p, plantReach, true)) {
                        candidates.add(p);
                    }
                }

                if (!candidates.isEmpty()) {
                    FarmPathOptimizer.TargetPair pair = FarmPathOptimizer.selectOptimalTargets(
                            eyePos, lookVec, velVec, lockedTarget, candidates, reach2
                    );
                    lockedTarget = pair.primary().immutable();
                    lookAheadTarget = (pair.lookAhead() != null && !pair.lookAhead().equals(lockedTarget)) ? pair.lookAhead().immutable() : null;
                    lockedPhase = ActionPhase.REPLANT;
                    lockedTargetTicks = 0;
                }
            }

            // ==================== ETAP 3: LOOKAHEAD PRE-AIMING (Gdy brak celów w zasięgu ręki) ====================
            if (isPracaModeActive() && lockedTarget == null && ctx.player() != null) {
                if (!shouldYieldRotationToPathing(eyePos, null, null, false)) {
                    float spineYaw = farmOptimizer.computeCurrentTangentYaw(ctx.player().position(), ctx.player().getYRot());
                    Vec3 predictedEye = eyePos.add(ctx.player().getDeltaMovement());
                    Optional<FarmPathOptimizer.HarvestTarget> lookahead = farmOptimizer.findTargetInZone(
                            ctx.player(), ctx.world(), predictedEye, spineYaw, Baritone.settings().replantCrops.value && haveSeeds, null, 1.5D, 6.50D, 65.0D);
                    if (lookahead.isPresent()) {
                        FarmPathOptimizer.HarvestTarget lt = lookahead.get();
                        Vec3 aimVec = lt.action() == FarmPathOptimizer.TargetAction.HARVEST_MATURE
                                ? new Vec3(lt.pos().getX() + 0.5D, lt.pos().getY() + 0.65D, lt.pos().getZ() + 0.5D)
                                : new Vec3(lt.pos().getX() + 0.5D, lt.pos().getY() + 0.9375D, lt.pos().getZ() + 0.5D);
                        if (hasCleanColliderLineOfSight(eyePos, lt.pos(), aimVec)) {
                            float[] snap = GrimSnapRotationUtil.snapGcd(ctx.player().getYRot(), ctx.player().getXRot(), predictedEye, aimVec);
                            applyAimRotation(new Rotation(snap[0], snap[1]), true);
                        }
                    }
                }
            }
        }

        // C. Wykonanie DOKŁADNIE JEDNEJ akcji w ticku (Strict 1 Target per Tick)
        if (lockedTarget != null) {
            lockedTargetTicks++;
            handledNearbyAction = true;

            if (lockedPhase == ActionPhase.HARVEST) {
                BlockPos pos = lockedTarget;
                BlockState bState = ctx.world().getBlockState(pos);
                final boolean isInstant = bState.getDestroySpeed(ctx.world(), pos) == 0.0F;

                Vec3 cropAimVec = resolveVisibleAimPoint(head, pos, false);
                if (cropAimVec == null) {
                    lockedTarget = null;
                    lockedPhase = ActionPhase.NONE;
                    lockedTargetTicks = 0;
                    return onTickInternal(calcFailed, isSafeToCancel);
                }
                Optional<Rotation> rot = RotationUtils.reachableOffset(ctx, pos, cropAimVec, blockReachDistance, false);
                if (!rot.isPresent()) {
                    rot = RotationUtils.reachable(ctx, pos, blockReachDistance);
                }
                if (!rot.isPresent() && head.distanceToSqr(cropAimVec) <= reach2) {
                    BlockHitResult hit = ctx.world().clip(new ClipContext(head, cropAimVec,
                            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx.player()));
                    if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos)) {
                        rot = Optional.of(RotationUtils.calcRotationFromVec3d(head, cropAimVec, currentRefRot));
                    }
                }

                if (rot.isPresent()) {
                    BlockPos soilPos = pos.below();
                    Block belowBlock = ctx.world().getBlockState(soilPos).getBlock();
                    boolean isSoil = (belowBlock == Blocks.FARMLAND || belowBlock == Blocks.SOUL_SAND);

                    Rotation smoothRot;
                    if (isPracaModeActive()) {
                        float[] verified = GrimSnapRotationUtil.findVerifiedSnapRotation(
                                ctx.player(),
                                ctx.player().getYRot(),
                                ctx.player().getXRot(),
                                pos, true, blockReachDistance);
                        if (verified != null) {
                            smoothRot = new Rotation(verified[0], verified[1]);
                        } else {
                            float[] snapped = GrimSnapRotationUtil.snapGcd(
                                    ctx.player().getYRot(),
                                    ctx.player().getXRot(),
                                    head, cropAimVec);
                            smoothRot = new Rotation(snapped[0], snapped[1]);
                        }
                    } else {
                        smoothRot = isGrim ? RotationEngine.snapToGcd(currentRefRot, rot.get()) : rot.get();
                    }
                    applyAimRotation(smoothRot, fastMode);
                    updateSweepState(smoothRot);

                    if (!isInstant) {
                        MovementHelper.switchToBestToolFor(ctx, bState);
                    }

                    BlockHitResult cropHit = getServerCropHit(pos);
                    if (cropHit == null && ctx.isLookingAt(pos)) {
                        HitResult chr = ctx.objectMouseOver();
                        if (chr instanceof BlockHitResult bhr && bhr.getBlockPos().equals(pos)) {
                            cropHit = bhr;
                        }
                    }
                    if (cropHit == null) {
                        HitResult directRay = RayTraceUtils.rayTraceTowards(ctx.player(), smoothRot, blockReachDistance, ctx.player().isCrouching());
                        if (directRay instanceof BlockHitResult bhr && bhr.getBlockPos().equals(pos)) {
                            cropHit = bhr;
                        }
                    }

                    if (cropHit != null) {
                        ctx.playerController().syncHeldItem();
                        Direction breakFace = cropHit.getDirection();

                        if (isInstant) {
                            if (ctx.playerController().clickBlock(pos, breakFace)) {
                                ctx.player().swing(InteractionHand.MAIN_HAND);
                                acted = true;
                                resetWatchdogOnProgress();
                                com.astra.revolution.visuals.AstraGodTierVisuals.triggerShockwave(pos.getX() + 0.5D, pos.getY() + 0.1D, pos.getZ() + 0.5D, 0x00FF88, true);
                                if (bState.getBlock() == Blocks.WHEAT) {
                                    totalWheatHarvested++;
                                }
                                toBreak.remove(pos);
                                toBreakSet.remove(pos.asLong());
                                cachedToBreak.remove(pos);
                                RotationEngine.notifyBlockBroken(pos);

                                if (isSoil && Baritone.settings().replantCrops.value && hasPlantableSeeds()) {
                                    Item seed = seedFor(bState.getBlock());
                                    replantItem = seed;
                                    openFarmland.add(soilPos);
                                    farmlandSet.add(soilPos.asLong());
                                    cachedOpenFarmland.add(soilPos);
                                    pendingReplants.add(soilPos);
                                }

                                // Reset locka po ścięciu, aby w kolejnym ticku natychmiast wybrać kolejny kłos w reachu!
                                // PIPELINING (Argon 4): 0 ms natychmiastowe przejście do lookAheadTarget!
                                if (lookAheadTarget != null && !pos.equals(lookAheadTarget) && readyForHarvest(ctx.world(), lookAheadTarget, ctx.world().getBlockState(lookAheadTarget))) {
                                    lockedTarget = lookAheadTarget;
                                    lookAheadTarget = null;
                                    lockedPhase = ActionPhase.HARVEST;
                                    lockedTargetTicks = 0;
                                } else {
                                    lockedTarget = null;
                                    lockedPhase = ActionPhase.NONE;
                                    lockedTargetTicks = 0;
                                }
                            }
                        } else if (ctx.playerController().hasBrokenBlock()) {
                            ctx.playerController().clickBlock(pos, breakFace);
                            ctx.player().swing(InteractionHand.MAIN_HAND);
                            acted = true;
                            resetWatchdogOnProgress();
                            toBreak.remove(pos);
                            toBreakSet.remove(pos.asLong());
                            cachedToBreak.remove(pos);
                            if (lookAheadTarget != null && !pos.equals(lookAheadTarget) && readyForHarvest(ctx.world(), lookAheadTarget, ctx.world().getBlockState(lookAheadTarget))) {
                                lockedTarget = lookAheadTarget;
                                lookAheadTarget = null;
                                lockedPhase = ActionPhase.HARVEST;
                                lockedTargetTicks = 0;
                            } else {
                                lockedTarget = null;
                                lockedPhase = ActionPhase.NONE;
                                lockedTargetTicks = 0;
                            }
                        } else if (ctx.playerController().onPlayerDamageBlock(pos, breakFace)) {
                            ctx.player().swing(InteractionHand.MAIN_HAND);
                        }
                    } else {
                        // GrimAC Safe: Rotacja została ustawiona (applyAimRotation), czekamy 1 tick na wysłanie pakietu w naturalnym sendPosition()
                        handledNearbyAction = true;
                    }

                    if (lockedTargetTicks >= (isInstant ? 2 : 25)) {
                        blacklistFor(pos, 40);
                        lockedTarget = null;
                        lockedPhase = ActionPhase.NONE;
                        lockedTargetTicks = 0;
                    }

                    // Move-While-Act (Argon 4): Hamuj TYLKO wtedy, gdy gracz ucieka poza zasięg celu!
                    if (!acted && !isPracaModeActive() && shouldBrakeForTarget(eyePos, currentRefRot.getYaw(), lockedTarget, reach2, acted)) {
                        baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
                        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
                        if (ctx.player() != null) {
                            ctx.player().setSprinting(false);
                        }
                        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                    }
                } else {
                    lockedTarget = null;
                    lockedPhase = ActionPhase.NONE;
                    lockedTargetTicks = 0;
                }
            } else if (lockedPhase == ActionPhase.REPLANT) {
                BlockPos pos = lockedTarget;
                Vec3 plantAim = resolveVisibleAimPoint(head, pos, true);
                if (plantAim == null) {
                    lockedTarget = null;
                    lockedPhase = ActionPhase.NONE;
                    lockedTargetTicks = 0;
                    return onTickInternal(calcFailed, isSafeToCancel);
                }
                double plantReach = blockReachDistance;
                Optional<Rotation> visRot = getVisiblePlantRotation(pos, plantReach, currentRefRot);

                if (visRot.isPresent() && selectSeedsForPlanting()) {
                    Rotation smoothRot;
                    if (isPracaModeActive()) {
                        float[] verified = GrimSnapRotationUtil.findVerifiedSnapRotation(
                                ctx.player(),
                                ctx.player().getYRot(),
                                ctx.player().getXRot(),
                                pos, false, plantReach);
                        if (verified != null) {
                            smoothRot = new Rotation(verified[0], verified[1]);
                        } else {
                            smoothRot = visRot.get();
                        }
                    } else {
                        smoothRot = isGrim ? RotationEngine.snapToGcd(currentRefRot, visRot.get()) : visRot.get();
                    }
                    applyAimRotation(smoothRot, fastMode);
                    updateSweepState(smoothRot);

                    BlockHitResult sbhr = getServerFarmlandHit(pos);
                    if (sbhr == null && ctx.isLookingAt(pos)) {
                        HitResult chr = ctx.objectMouseOver();
                        if (chr instanceof BlockHitResult bhr && isValidFarmlandHit(bhr, pos)) {
                            sbhr = bhr;
                        }
                    }
                    if (sbhr == null) {
                        HitResult farmRay = RayTraceUtils.rayTraceTowards(ctx.player(), smoothRot, plantReach, ctx.player().isCrouching());
                        if (farmRay instanceof BlockHitResult bhr && isValidFarmlandHit(bhr, pos)) {
                            sbhr = bhr;
                        }
                    }

                    if (sbhr != null) {
                        InteractionResult res = ctx.playerController().processRightClickBlock(
                                ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, sbhr);
                        if (res.consumesAction()) {
                            ctx.player().swing(InteractionHand.MAIN_HAND);
                            com.astra.revolution.visuals.AstraGodTierVisuals.triggerShockwave(pos.getX() + 0.5D, pos.getY() + 0.9375D, pos.getZ() + 0.5D, 0x00F0FF, false);
                            failedPlantClicks.remove(pos.asLong());
                            openFarmland.remove(pos);
                            farmlandSet.remove(pos.asLong());
                            cachedOpenFarmland.remove(pos);
                            pendingReplants.remove(pos);
                            if (plantQueue != null) plantQueue.remove(pos);
                            if (pos.equals(immediateReplantPos)) {
                                immediateReplantPos = null;
                            }
                            resetWatchdogOnProgress();
                            acted = true;
                            // PIPELINING (Argon 4): 0 ms natychmiastowe przejście do lookAheadTarget!
                            if (lookAheadTarget != null && !pos.equals(lookAheadTarget) && isPlantableFarmland(lookAheadTarget)) {
                                lockedTarget = lookAheadTarget;
                                lookAheadTarget = null;
                                lockedPhase = ActionPhase.REPLANT;
                                lockedTargetTicks = 0;
                            } else {
                                lockedTarget = null;
                                lockedPhase = ActionPhase.NONE;
                                lockedTargetTicks = 0;
                            }
                        } else {
                            long key = pos.asLong();
                            int fails = failedPlantClicks.addTo(key, 1) + 1;
                            if (fails >= MAX_PLANT_FAIL_CLICKS || tooManyAttempts(pos)) {
                                markUnplantable(pos, openFarmland);
                                cachedOpenFarmland.remove(pos);
                                lockedTarget = null;
                                lockedPhase = ActionPhase.NONE;
                                lockedTargetTicks = 0;
                            }
                        }
                    } else {
                        // Rotacja w trakcie synchronizacji (nastąpi w następnym ticku przez naturalne sendPosition())
                        handledNearbyAction = true;
                    }

                    if (lockedTargetTicks >= 3) {
                        blacklistFor(pos, 40);
                        lockedTarget = null;
                        lockedPhase = ActionPhase.NONE;
                        lockedTargetTicks = 0;
                    }

                    // Move-While-Act (Argon 4): Hamuj TYLKO wtedy, gdy gracz ucieka poza zasięg celu!
                    if (!acted && !isPracaModeActive() && shouldBrakeForTarget(eyePos, currentRefRot.getYaw(), lockedTarget, reach2, acted)) {
                        baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
                        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
                        if (ctx.player() != null) {
                            ctx.player().setSprinting(false);
                        }
                        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                    }
                } else {
                    lockedTarget = null;
                    lockedPhase = ActionPhase.NONE;
                    lockedTargetTicks = 0;
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
                    applyAimRotation(rot.get(), fastMode);
                    ctx.playerController().syncHeldItem();
                    if (fastMode || ctx.isLookingAt(pos)) {
                        BlockHitResult plantHit = new BlockHitResult(plantTarget, Direction.UP, pos, false);
                        InteractionResult res = ctx.playerController().processRightClickBlock(
                                ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, plantHit);
                        if (res.consumesAction()) {
                            ctx.player().swing(InteractionHand.MAIN_HAND);
                            openSoulsand.remove(pos);
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
        int seedsInInventory = countWheatSeedsInInventory();
        boolean haveSeedsInEq = hasAnyPlantableInInventory();
        boolean hasMatureCrops = !toBreak.isEmpty();

        try {
            for (Entity entity : ctx.entities()) {
                if (entity instanceof ItemEntity ei && !entity.isRemoved() && entity.isAlive()) {
                    if (ei.isInLava()) {
                        continue;
                    }
                    if (Math.abs(ei.getY() - (playerPos.getY() + 0.5D)) > 2.0D) {
                        continue; // Ignoruj itemy na suficie / innym piętrze
                    }
                    if (PICKUP_DROPPED.contains(ei.getItem().getItem())) {
                        if (itemBlacklist.get(ei.getId()) > farmTicks || !hasRoomFor(ei.getItem())) {
                            continue;
                        }

                        Item dropItem = ei.getItem().getItem();
                        boolean isSeeds = (dropItem == Items.WHEAT_SEEDS || dropItem == Items.BEETROOT_SEEDS
                                || dropItem == Items.TORCHFLOWER_SEEDS || dropItem == Items.PITCHER_POD);

                        if (isPracaActive) {
                            double distSq = ei.position().distanceToSqr(ctx.player().position());
                            boolean inCorridor = (distSq <= 12.25D); // Korytarz 3.5m — zbieranie w biegu (nigdy nie zostawiaj plonów!)

                            if (haveSeedsInEq) {
                                // W korytarzu 3.5m zbieramy każdy upuszczony plon i nasiono w biegu
                                if (!inCorridor) {
                                    // Poza korytarzem (> 3.5m): ignoruj nasiona gdy mamy nasiona, i nie zbaczaj daleko po plon gdy kłosy są tuż przed nami
                                    if (isSeeds || (hasMatureCrops && distSq > 20.25D)) {
                                        continue;
                                    }
                                }
                            } else {
                                // Brak nasion: zbieraj nasiona i plony w promieniu do 4.5m nawet przy dojrzałych kłosach
                                if (hasMatureCrops && distSq > 20.25D) {
                                    continue;
                                }
                            }
                        }

                        BlockPos ePos = entity.blockPosition();
                        if (!ctx.player().isInWater()
                                && (entity.isInWater() || !ctx.world().getFluidState(ePos).isEmpty())) {
                            continue;
                        }
                        // Filtr zasięgu: w trybie #praca – promień od centrum farmy;
                        // bez trybu #praca – standardowy promień od gracza.
                        if (isPracaActive) {
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

        // Aktualizacja stanu skupienia na pszenicy/plonach
        pracaWheatPriorityState = haveSeedsInEq;

        // Sortowanie dropów z ziemi:
        // W sytuacji awaryjnej (0 nasion) priorytetyzujemy nasiona najbliżej gracza; w normalnym trybie plony najbliżej gracza
        if (isPracaActive) {
            nearbyDrops.sort((e1, e2) -> {
                boolean isSeed1 = e1.getItem().is(Items.WHEAT_SEEDS) || e1.getItem().is(Items.BEETROOT_SEEDS);
                boolean isSeed2 = e2.getItem().is(Items.WHEAT_SEEDS) || e2.getItem().is(Items.BEETROOT_SEEDS);
                if (!haveSeedsInEq) {
                    if (isSeed1 != isSeed2) {
                        return isSeed1 ? -1 : 1; // Nasiona przed plonami gdy brak nasion!
                    }
                } else {
                    if (isSeed1 != isSeed2) {
                        return isSeed1 ? 1 : -1; // Plony przed nasionami
                    }
                }
                return Double.compare(playerPos.distSqr(e1.blockPosition()), playerPos.distSqr(e2.blockPosition()));
            });
        } else {
            nearbyDrops.sort(Comparator.comparingDouble(e -> playerPos.distSqr(e.blockPosition())));
        }

        pracaGroundDropsCount = nearbyDrops.size();
        lastNearestDropId = nearbyDrops.isEmpty() ? -1 : nearbyDrops.get(0).getId();

        // ── Tryb #praca: decyzyjność zbierania z ziemi vs kłosy na polu ──
        boolean executeGroundFirst;
        if (isPracaActive) {
            // W trybie #praca:
            // 1. Awaryjny zbiór z ziemi TYLKO gdy bot ma 0 nasion w EQ i są wolne grządki do obsiania
            //    oraz w bezpośrednim zasięgu ręki nie ma dojrzałych plonów (które po ścięciu same dają nasiona)
            boolean emergencySeedsNeeded = !haveSeedsInEq && !openFarmland.isEmpty() && !nearbyDrops.isEmpty()
                    && (!hasMatureCrops || playerPos.distSqr(nearbyDrops.get(0).blockPosition()) <= 9.0D);
            // 2. Całe pole zostało skoszone: brak dojrzałych plonów na całym polu ORAZ są leżące dropy
            boolean allCropsHarvested = !hasMatureCrops && !nearbyDrops.isEmpty();
            executeGroundFirst = emergencySeedsNeeded || allCropsHarvested;
        } else {
            int threshold = Baritone.settings().farmLowCropThreshold.value;
            boolean pracaGroundFirst;
            if (pracaGroundFirstState) {
                pracaGroundFirst = isPracaActive && pracaHarvestableCount < (threshold + 10);
            } else {
                pracaGroundFirst = isPracaActive && pracaHarvestableCount < threshold;
            }
            pracaGroundFirstState = pracaGroundFirst;
            boolean hasWheatDrops = false;
            for (ItemEntity drop : nearbyDrops) {
                if (drop.getItem().is(Items.WHEAT)) {
                    hasWheatDrops = true;
                    break;
                }
            }
            executeGroundFirst = pracaGroundFirst;
            if (isPracaActive && pracaWheatPriorityState && !hasWheatDrops && !toBreak.isEmpty()) {
                executeGroundFirst = false;
            }
        }

        if (executeGroundFirst && !nearbyDrops.isEmpty()) {
            ItemEntity closestDrop = nearbyDrops.get(0);
            Vec3 dropPos = closestDrop.position();
            double distToDropSq = ctx.player().position().distanceToSqr(dropPos);

            if (farmTicks % 100 == 0) {
                if (pracaWheatPriorityState) {
                    logDirect(String.format("§e[Praca] Tryb zbierania: priorytet PSZENICY (nasiona w EQ: %d). Zbieram: %d itemów pszenicy z ziemi",
                            seedsInInventory, nearbyDrops.size()));
                } else {
                    logDirect(String.format("§e[Praca] Zbieranie nasion i plonów (nasiona w EQ: %d). Zbieram: %d itemów z ziemi",
                            seedsInInventory, nearbyDrops.size()));
                }
            }

            // Bezpieczne zbieranie itemów z ziemi przez Baritone A* (GoalPickupItem).
            // Zero manipulacji klawiszami chodu, zero sztucznych canceli ścieżki i zero REQUEST_PAUSE.
            // Baritone generuje w 100% zsynchronizowaną fizykę 1:1, zgodną z PredictionEngine GrimAC.
            if (closestDrop.getId() == currentDropTargetId) {
                dropPickupWaitTicks++;
                if (dropPickupWaitTicks > 100) {
                    itemBlacklist.put(closestDrop.getId(), farmTicks + 300);
                    dropPickupWaitTicks = 0;
                    currentDropTargetId = -1;
                }
            } else {
                currentDropTargetId = closestDrop.getId();
                dropPickupWaitTicks = 0;
            }

            int maxPickupGoals = Math.min(3, nearbyDrops.size());
            for (int i = 0; i < maxPickupGoals; i++) {
                ItemEntity ei = nearbyDrops.get(i);
                BlockPos standPos = BlockPos.containing(ei.getX(), ei.getY() + 0.1D, ei.getZ());
                goalz.add(new GoalPickupItem(standPos));
            }
        } else {
            currentDropTargetId = -1;
            dropPickupWaitTicks = 0;

            // ── LANE TRAVERSAL: inteligentny bieg wzdłuż rzędów upraw ──
            // Bot biegnie sprintem wzdłuż rzędów, zbierając i sadząc plony w biegu (20 bloków/s)
            // bez zatrzymywania się na każdym klocku i bez zygzakowania po sąsiednich grządkach.

            // ── STABILNY ROW ANCHOR / BOUSTROPHEDON SPINE GOAL (Argon 4) ──
            if (isPracaModeActive()) {
                // 1. Sprawdź, czy dotychczasowy cel marszu (lockedWalkTarget) jest nadal aktualny i osiągalny.
                // Histereza gwarantuje, że cel (i zielony box) NIE zmienia się co tick podczas biegu!
                boolean currentTargetStillValid = false;
                if (lockedWalkTarget != null && !isBlacklisted(lockedWalkTarget) && stallTicks < 40) {
                    currentTargetStillValid = toBreak.contains(lockedWalkTarget)
                            || (haveSeedsInEq && openFarmland.contains(lockedWalkTarget));
                }

                // 2. Wyznacz nowy cel TYLKO wtedy, gdy poprzedni został już ścięty/zasadzony lub zablokowany
                if (!currentTargetStillValid) {
                    lockedWalkTarget = findNextWalkTarget(
                            ctx.player().position(),
                            toBreak,
                            haveSeedsInEq ? openFarmland : Collections.emptyList()
                    );
                    stallTicks = 0;
                }

                // 3. Dodaj DOKŁADNIE JEDEN stabilny cel do goalz (zero chmary mrugających boxów!)
                if (lockedWalkTarget != null) {
                    goalz.add(new GoalFarmTarget(lockedWalkTarget));
                } else if (pracaActiveSpineWaypoint != null) {
                    goalz.add(new GoalFarmTarget(pracaActiveSpineWaypoint));
                }
            } else if (!toBreak.isEmpty() || (haveSeeds && !openFarmland.isEmpty())) {
                Goal stableGoal = updateOrKeepStableRowGoal(toBreak, openFarmland, playerPos);
                if (stableGoal != null) {
                    goalz.add(stableGoal);
                } else if (pracaActiveSpineWaypoint != null) {
                    goalz.add(new GoalNear(new BetterBlockPos(pracaActiveSpineWaypoint.above()), 2));
                }
            } else if (pracaActiveSpineWaypoint != null) {
                goalz.add(new GoalNear(new BetterBlockPos(pracaActiveSpineWaypoint.above()), 2));
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

            // Normalny tryb: zbieramy pobliskie leżące itemy TYLKO gdy brak aktywnych celów zbioru/sadzenia.
            // Zapobiega to oscylacjom celów (nawrotom o 180° po każde upuszczone nasionko w trakcie biegu rzędem).
            if (goalz.isEmpty() && !nearbyDrops.isEmpty()) {
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

        if (!currentHasWork || lockedTarget != null || acted) {
            idleStuckTicks = 0; // Czyszczenie plonów na miejscu lub czekanie na wzrost to nie zacięcie
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
        boolean isPathing = baritone.getPathingBehavior().hasPath()
                || baritone.getPathingBehavior().getInProgress().isPresent();
        boolean hasMoved = wdAnchor != null && playerPos.distSqr(wdAnchor) >= 0.25D;

        if (acted || !currentHasWork || isPathing || hasMoved) {
            wdSinceTick = farmTicks;
            wdLevel = 0;
            if (hasMoved) {
                wdAnchor = playerPos;
            }
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
                    if (g instanceof IGoalRenderPos grp) {
                        blacklistFor(grp.getGoalPos(), 160);
                    }
                }
                for (ItemEntity drop : nearbyDrops) {
                    if (playerPos.distSqr(drop.blockPosition()) < 16.0D) {
                        itemBlacklist.put(drop.getId(), farmTicks + 200);
                    }
                }
                baritone.getPathingBehavior().softCancelIfSafe();
            } else if (wdLevel == 2) {
                resetWorkState();
                baritone.getPathingBehavior().softCancelIfSafe();
            } else {
                wdLevel = 0;
                triggerFailsafeHomeReturn("Watchdog utknięcia (poziom 3)");
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (pendingCalcFailed) {
            pendingCalcFailed = false;
            for (Goal g : goalz) {
                if (g instanceof IGoalRenderPos grp) {
                    blacklistFor(grp.getGoalPos(), 100);
                }
            }
            if (lastNearestDropId != -1) {
                itemBlacklist.put(lastNearestDropId, farmTicks + 100);
            }
            // W trybie #praca NIE wolno blacklistować okolicznych kłosów/grządek przy pojedynczym błędzie A* (zapobiega IDLE po 1s)
            if (!isPracaModeActive()) {
                toBreak.stream()
                    .filter(p -> Math.abs(p.getY() - playerY) <= 1)
                    .min(Comparator.comparingDouble(playerPos::distSqr))
                    .ifPresent(p -> blacklistFor(p, 60));
                openFarmland.stream()
                    .filter(p -> Math.abs(p.getY() - playerY) <= 1)
                    .min(Comparator.comparingDouble(playerPos::distSqr))
                    .ifPresent(p -> blacklistFor(p, 60));
            }
            currentTargetPos = null;
            targetTicks = 0;
            currentPlantTargetPos = null;
            plantTargetTicks = 0;
            idleStuckTicks = 0;
        }

        // Pkt 7: Zapobieganie zablokowaniu przy pustej liście celów / pełnej blackliście
        if (goalz.isEmpty() && (!toBreak.isEmpty() || !openFarmland.isEmpty())) {
            emptyGoalTicks++;
            if (emptyGoalTicks > (isPracaModeActive() ? 5 : 20)) {
                blacklistUntil.clear();
                attempts.clear();
                harvestQueue.clear();
                plantQueue.clear();
                emptyGoalTicks = 0;
                // Dodaj cele przez selectOptimizedHarvestGoals z zachowaniem izolacji piętra
                if (!plantOnly && !toBreak.isEmpty()) {
                    List<BlockPos> recovered = selectOptimizedHarvestGoals(toBreak, playerPos);
                    for (BlockPos p : recovered) {
                        goalz.add(new GoalFarmTarget(p));
                    }
                } else if (haveSeeds && !openFarmland.isEmpty()) {
                    List<BlockPos> recovered = selectOptimizedHarvestGoals(openFarmland, playerPos);
                    for (BlockPos p : recovered) {
                        goalz.add(new GoalFarmTarget(p));
                    }
                }
            }
        } else {
            emptyGoalTicks = 0;
        }

        // ─────────────────────────────────────────────────────────────────
        // ACTIVE HARVEST PULL & ANTI-STALL MAGNET (Argon 4)
        // ─────────────────────────────────────────────────────────────────
        var player = ctx.player();
        var world = ctx.world();
        Vec3 playerVecPos = player.position();
        boolean hasInReachAction = acted || lockedTarget != null;

        // 1. Aktualizuj Watchdog 0.0 bps — TYLKO realne akcje sieciowe (acted == true) resetują stallTicks!
        updateStallWatchdog(player, acted);

        // 2. Wyznacz aktywny cel przyciągania (najbliższy dojrzały kłos / grządka w promieniu 32m)
        // Gdy bot jest w dedykowanym trybie zbierania dropów z ziemi (executeGroundFirst),
        // ACTIVE HARVEST PULL nie może odciągać bota na odległe kłosy ani stary spine waypoint!
        Rotation currentRot = getSafeServerRotation();
        BlockPos rawPullBlockPos = (executeGroundFirst && !nearbyDrops.isEmpty()) ? null
                : findActiveMagnetTarget(playerVecPos, currentRot.getYaw(), toBreak, openFarmland, haveSeeds);
        BlockPos pullBlockPos = resolveStableWalkTarget(playerVecPos, rawPullBlockPos, world);

        if (pullBlockPos != null && goalz.isEmpty()) {
            goalz.add(new GoalFarmTarget(pullBlockPos));
        }

        if (goalz.isEmpty()) {
            // 1. Zbieranie leżących dropów jeśli są w pobliżu
            if (!nearbyDrops.isEmpty()) {
                ItemEntity closestDrop = nearbyDrops.get(0);
                BlockPos standPos = BlockPos.containing(closestDrop.getX(), closestDrop.getY() + 0.1D, closestDrop.getZ());
                goalz.add(new GoalPickupItem(standPos));
            }
            // 2. Jeśli mamy kłosy lub grządki zablokowane na blackliście -> natychmiast wyczyść blacklistę
            else if (!toBreak.isEmpty() || !openFarmland.isEmpty()) {
                blacklistUntil.clear();
                attempts.clear();
                for (BlockPos p : toBreak) {
                    goalz.add(new GoalGetToBlock(p));
                    if (goalz.size() >= 3) break;
                }
                for (BlockPos p : openFarmland) {
                    goalz.add(new GoalGetToBlock(p));
                    if (goalz.size() >= 6) break;
                }
            }

            // 3. Sprawdzenie czy mamy sadzonki w ekwipunku lub czy na polu mogą być nowe plony
            if (goalz.isEmpty()) {
                if (haveSeeds || hasAnyPlantableInInventory()) {
                    // Natychmiastowe obudzenie skanera świata
                    locations = null;
                    scanCooldownTicks = 0;
                }

                if (isPracaModeActive()) {
                    if (pracaActiveSpineWaypoint != null && playerPos.distSqr(pracaActiveSpineWaypoint) > 4.0D) {
                        goalz.add(new GoalNear(new BetterBlockPos(pracaActiveSpineWaypoint.above()), 2));
                    } else if (laneForwardDir != null && laneForwardDir.lengthSqr() > 0.01D) {
                        BlockPos forwardPos = playerPos.offset((int) Math.round(laneForwardDir.x * 4.0D), 0, (int) Math.round(laneForwardDir.z * 4.0D));
                        goalz.add(new GoalNear(new BetterBlockPos(forwardPos), 1));
                    } else {
                        // Zamiast GoalGetToBlock(playerPos) który zamraża bota w miejscu jak #stop:
                        // wyznacz patrol 4 bloki w kierunku patrzenia gracza, by wybudzić Baritone A*
                        float currentYaw = getSafeServerRotation().getYaw();
                        double rad = Math.toRadians(currentYaw);
                        BlockPos patrolPos = playerPos.offset((int) Math.round(-Math.sin(rad) * 4.0D), 0, (int) Math.round(Math.cos(rad) * 4.0D));
                        goalz.add(new GoalNear(new BetterBlockPos(patrolPos), 1));
                    }
                } else {
                    // Standardowy #farm: jeśli mamy nasiona lub dropy, nigdy nie zamrażaj bota!
                    if (haveSeeds || hasAnyPlantableInInventory() || !nearbyDrops.isEmpty()) {
                        float currentYaw = getSafeServerRotation().getYaw();
                        double rad = Math.toRadians(currentYaw);
                        BlockPos patrolPos = playerPos.offset((int) Math.round(-Math.sin(rad) * 3.0D), 0, (int) Math.round(Math.cos(rad) * 3.0D));
                        goalz.add(new GoalNear(new BetterBlockPos(patrolPos), 1));
                    } else {
                        baritone.getInputOverrideHandler().clearAllKeys();
                        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                    }
                }
            }
        }
        boolean onFarmland = isNearOrOnFarmland(ctx.player(), ctx.world());
        boolean canSprint = Baritone.settings().farmFastMode.value
                && !onFarmland
                && !ctx.player().isInWater()
                && !ctx.player().isCrouching()
                && !ctx.player().onClimbable()
                && baritone.getInputOverrideHandler().isInputForcedDown(Input.MOVE_FORWARD);

        baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, canSprint);
        if (ctx.player() != null) {
            ctx.player().setSprinting(canSprint);
        }
        baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, false);
        if (goalz.isEmpty()) {
            cachedCompositeGoal = null;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        Goal candidateGoal = (goalz.size() == 1)
                ? goalz.get(0)
                : new GoalComposite(goalz.toArray(new Goal[0]));

        // Porównuj przez equals(), a gdy cel się nie zmienił — użyj DOKŁADNIE tej samej referencji cachedCompositeGoal!
        boolean goalChanged = (cachedCompositeGoal == null || !candidateGoal.equals(cachedCompositeGoal));
        isPathing = baritone.getPathingBehavior().isPathing();

        if (goalChanged || stallTicks >= 8) {
            cachedCompositeGoal = candidateGoal;
            ticksSinceGoalRebuild = 0;
            return new PathingCommand(cachedCompositeGoal, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }

        ticksSinceGoalRebuild++;
        // NIGDY nie zwracaj tutaj PathingCommandType.DEFER!
        // SET_GOAL_AND_PATH z niezmienionym obiektem cachedCompositeGoal utrzymuje ciągłą ścieżkę w PathExecutor
        // (klawisz W i Sprint są wciśnięte bez ani jednego ticku przerwy i bez mrugania wizualizacji).
        return new PathingCommand(cachedCompositeGoal, PathingCommandType.SET_GOAL_AND_PATH);
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
        pracaWheatPriorityState = false;
        lockedTarget = null;
        lookAheadTarget = null;
        cachedCompositeGoal = null;
        lockedWalkTarget = null;
        lockedWalkTicks = 0;
        lastGoalFingerprint = 0L;
        ticksSinceGoalRebuild = 0;
        lockedPhase = ActionPhase.NONE;
        lockedTargetTicks = 0;
        currentTargetPos = null;
        targetTicks = 0;
        currentPlantTargetPos = null;
        plantTargetTicks = 0;
        immediateReplantPos = null;
        pendingReplants.clear();
        replantAttemptTicks = 0;
        replantItem = null;
        blacklistUntil.clear();
        attempts.clear();
        itemBlacklist.clear();
        persistentFarmlandCache.clear();
        cachePruneTicker = 0;
        cachedToBreak.clear();
        cachedOpenFarmland.clear();
        cachedBonemealable.clear();
        cachedOpenSoulsand.clear();
        cachedOpenLog.clear();
        fullFieldScanTicker = 0;
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
        stableRowAnchorPos = null;
        stableRowGoal = null;
        stableRowGoalTicks = 0;
        committedWalkDir = null;
        sweepDirection = 1;
        lastHarvestYaw = Float.NaN;
        pendingInstantReplantPos = null;
        pendingInstantReplantTicks = 0;
        pracaActiveSpineWaypoint = null;
        farmOptimizer.resetRoute();
        postWaterGraceTicks = 0;
        postWaterSafeAnchor = null;
        magnetPullTarget = null;
        lockedWalkTarget = null;
        lockedWalkTicks = 0;
        lockedWalkLastDistSq = Double.MAX_VALUE;
        stallTicks = 0;
        manualPullStallTicks = 0;
        stalledTargetCooldowns.clear();
        failedPlantClicks.clear();
        unplantableBlacklist.clear();
        lastAimedCropPos = null;
        serverAimedTicks = 0;
        laneForwardDir = Vec3.ZERO;
        lastProgressPos = null;
        lockedLaneYaw = Float.NaN;
        baritone.getInputOverrideHandler().clearAllKeys();

        // Pkt 10: Przywróć dokładny stan ustawień
        // [FIX A] Tylko jeśli farm() je zmieniła. onLostControl() jest wołane także na
        // nieaktywnym procesie
        // (cancelEverything()/#stop) — bez tej flagi allowBreak trafiałby na `false`
        // (domyślna wartość pola).
        if (lowLatencySettingsApplied) {
            lowLatencySettingsApplied = false;
            Baritone.settings().primaryTimeoutMS.value = 1000L;
            Baritone.settings().failureTimeoutMS.value = 2000L;
            Baritone.settings().planAheadPrimaryTimeoutMS.value = 500L;
            Baritone.settings().planAheadFailureTimeoutMS.value = 2000L;
            Baritone.settings().movementTimeoutTicks.value = 100;
            Baritone.settings().costHeuristic.value = 3.56D;
        }
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