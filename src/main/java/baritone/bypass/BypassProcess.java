package baritone.bypass;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.event.events.BlockChangeEvent;
import baritone.api.event.events.PathEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.Goal;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.ContinuousBreakController;
import baritone.utils.FastBreakHelper;
import baritone.utils.GrimBreakValidator;
import baritone.api.utils.Pair;
import baritone.bypass.scripting.BypassLuaEngine;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.*;

/**
 * Główny proces wydobywczy (Argon 4 & dj).
 * Zoptymalizowany pod kątem bypassu GrimAC (GCD quantizing, RayTrace,
 * Line-of-Sight, zero airbreak/simulation flags).
 * 
 * Zdekomponowany na wyspecjalizowane moduły:
 * - {@link BypassNavigator}: poruszanie się, schodki, korytarze, obroty, odwrót
 * - {@link BypassOreManager}: detekcja i bezpieczne wydobycie rud (diamentów)
 * - {@link BypassHazardSealer}: zatykanie wycieków wody/lawy i bridging podłogi
 * - {@link BypassDropCollector}: wyszukiwanie i odzyskiwanie leżącego dropu
 * surowców
 * - {@link BypassLavaRadar}: długodystansowy radar lawy (do 30 bloków) i
 * analiza sektorów
 */
public final class BypassProcess extends BaritoneProcessHelper implements IBaritoneProcess, AbstractGameEventListener {

    public static final double STRICT_REACH = 4.2D;
    public static final double ORE_EYE_REACH = 4.2D;
    public static final int DEFAULT_TARGET_Y = -58;
    public static final int MIN_SAFE_BEDROCK_Y = -58;
    public static final float TWO_BLOCK_SEAM_PITCH = 26.8f;
    private static final int MIN_TOOL_DURABILITY = 5;
    private static final int WATCHDOG_MAX_NO_BLOCK_TICKS = 600; // 30 sekund (600 ticków)

    public enum State {
        DESCENDING_STAIRCASE,
        ASCENDING_STAIRCASE,
        TUNNELING,
        RETREATING,
        MINING_ORE,
        COLLECTING_DROP,
        SEALING_HAZARD,
        TURNING,
        RETREATING_MOB,
        PATHFINDING_RETURN,
        DISCONNECTED,
        IDLE
    }

    public enum ToolEquipStatus {
        NO_CHANGE,
        SWITCHED,
        FAILED
    }

    static final Map<String, Set<Block>> ORE_BLOCK_MAP = Map.of(
            "diamond", Set.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE),
            "gold", Set.of(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE),
            "iron", Set.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE),
            "copper", Set.of(Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE),
            "coal", Set.of(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE),
            "lapis", Set.of(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE),
            "redstone", Set.of(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE),
            "emerald", Set.of(Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE),
            "debris", Set.of(Blocks.ANCIENT_DEBRIS));

    static final Map<String, Set<Item>> ORE_ITEM_MAP = Map.of(
            "diamond", Set.of(Items.DIAMOND, Items.DIAMOND_ORE, Items.DEEPSLATE_DIAMOND_ORE),
            "gold", Set.of(Items.RAW_GOLD, Items.GOLD_INGOT, Items.GOLD_NUGGET),
            "iron", Set.of(Items.RAW_IRON, Items.IRON_INGOT),
            "copper", Set.of(Items.RAW_COPPER, Items.COPPER_INGOT),
            "coal", Set.of(Items.COAL),
            "lapis", Set.of(Items.LAPIS_LAZULI),
            "redstone", Set.of(Items.REDSTONE),
            "emerald", Set.of(Items.EMERALD),
            "debris", Set.of(Items.ANCIENT_DEBRIS, Items.NETHERITE_SCRAP));

    public static String normalizeOreKey(String key) {
        if (key == null)
            return "diamond";
        String s = key.toLowerCase(Locale.ROOT).trim();
        if (s.equals("diamonds") || s.equals("diax") || s.equals("diaxy") || s.equals("diamond_ore"))
            return "diamond";
        if (s.equals("gold_ore") || s.equals("zloto"))
            return "gold";
        if (s.equals("iron_ore") || s.equals("zelazo"))
            return "iron";
        if (s.equals("copper_ore") || s.equals("miedz"))
            return "copper";
        if (s.equals("coal_ore") || s.equals("wegiel"))
            return "coal";
        if (s.equals("lapis_ore") || s.equals("lazuryt") || s.equals("lapis_lazuli"))
            return "lapis";
        if (s.equals("redstone_ore") || s.equals("czerwony"))
            return "redstone";
        if (s.equals("emerald_ore") || s.equals("szmaragd") || s.equals("emeralds"))
            return "emerald";
        if (s.equals("ancient_debris") || s.equals("netherite"))
            return "debris";
        if (s.endsWith("_ore"))
            s = s.substring(0, s.length() - 4);
        return s;
    }

    private final BypassConfig config = new BypassConfig();
    private final BypassLuaEngine luaEngine;

    private boolean active = false;
    private int targetY = DEFAULT_TARGET_Y;
    public State state = State.IDLE;
    public String phase = "idle";
    private Direction tunnelDir = Direction.NORTH;

    private BlockPos tunnelAnchorPos = null;
    private BlockPos activeOreTarget = null;
    private BlockPos currentBreakingBlock = null;
    private int activeMiningTicks = 0;
    private int aimStallTicks = 0;
    private int lostSightGraceTicks = 0;
    private Rotation lockedMiningRotation = null;

    private final Map<BlockPos, EnumSet<Direction>> blockedDirsByNode=new LinkedHashMap<>(64,0.75f,true){@Override protected boolean removeEldestEntry(Map.Entry<BlockPos,EnumSet<Direction>>eldest){return size()>48;}};
    private Direction pendingTurnDir = null;
    private int turnCooldownTicks = 0;
    private int turnBrakeTicks = 0;
    private final Deque<BlockPos> veinQueue = new ArrayDeque<>();
    private final Set<BlockPos> blacklistedOres = new HashSet<>();
    private final Set<BlockPos> invalidStandingPositions = new HashSet<>();
    private final List<String> targetOresList = new ArrayList<>(List.of("diamond"));
    private final Set<String> targetDropItems = new HashSet<>(
            Set.of("diamond", "raw_iron", "raw_gold", "ancient_debris"));

    private final Map<Integer, Long> ignoredDropIds = new HashMap<>();
    private int currentDropId = -1;
    private int dropCollectTicks = 0;
    private int dropStuckTicks = 0;
    private double lastDistToDropSq = Double.MAX_VALUE;
    private Goal lastDropGoal = null;

    private BlockPos hazardSourcePos = null;
    private int sealCooldownTicks = 0;
    private int sealFailedAttempts = 0;
    private int placeCooldownTicks = 0;

    private Direction primaryMiningDir = Direction.NORTH;
    private final LongSet dugColumns2D = new LongOpenHashSet();
    private final LongSet hazardColumns2D = new LongOpenHashSet();
    private Direction retreatDir = null;
    private BlockPos retreatStartPos = null;
    private int retreatTicks = 0;
    private int targetRetreatBlocks = 9;
    private int lateralDetourRemaining = 0;
    private Direction lateralOffsetDir = null;
    private Direction blockedForwardDir = null;
    private int turnCommitCooldown = 0;
    private int consecutiveStraightBlocks = 0;
    private BlockPos lastCountedStraightFeet = null;
    private final Set<Long> minedCorridorFeet = new HashSet<>();
    private final Set<BlockPos> protectedSealBlocks = Collections.synchronizedSet(new HashSet<>());

    private Vec3 lastTrackedMovementPos = Vec3.ZERO;
    private int movementStuckTicks = 0;
    private int stuckTicks = 0;
    private int oreStagnationTicks = 0;
    private int oreAttemptTicks = 0;
    private int returnStuckTicks = 0;
    private BlockPos lastPlayerPos = null;
    public int oresMined = 0;
    private int diamondCount = 0;
    private int goldCount = 0;
    private long startTime = 0L;
    private int toolSwitchCooldownTicks = 0;
    private boolean originalAutoTool = true;
    private boolean originalAllowPlace = false;
    private boolean originalWalkWhileBreaking = true;
    private int postPlaceMineDelayTicks = 0;
    private int noBlockBrokenTicks = 0;

    public BypassProcess(Baritone baritone) {
        super(baritone);
        this.luaEngine = new BypassLuaEngine(baritone, this);
        baritone.getGameEventHandler().registerEventListener(this);
    }

    public Baritone getBaritone() {
        return this.baritone;
    }

    public IPlayerContext getPlayerContext() {
        return this.ctx;
    }

    public static final double HARD_MINING_REACH = 4.0D;
    public static final double HARD_MINING_REACH_SQ = HARD_MINING_REACH * HARD_MINING_REACH; // 16.0

    public double getEffectiveReach() {
        return HARD_MINING_REACH;
    }

    public boolean isCommittedToBreaking(LocalPlayer player, Level level) {
        if (currentBreakingBlock == null || level == null || player == null)
            return false;
        BlockState bstate = level.getBlockState(currentBreakingBlock);
        if (bstate.isAir() || !bstate.getFluidState().isEmpty()
                || bstate.getDestroySpeed(level, currentBreakingBlock) < 0.0F) {
            currentBreakingBlock = null;
            return false;
        }
        double distSq = player.getEyePosition(1.0F).distanceToSqr(Vec3.atCenterOf(currentBreakingBlock));
        if (distSq > HARD_MINING_REACH_SQ) {
            currentBreakingBlock = null;
            return false;
        }
        return true;
    }

    public void logDirect(String msg) {
        if (ctx.player() != null) {
            ctx.player().displayClientMessage(Component.literal(msg), false);
        }
    }

    public void markTunnelDug(BlockPos pos) {
        if (pos != null) {
            this.dugColumns2D.add(BypassNavigator.to2DKey(pos.getX(), pos.getZ()));
            if (this.minedCorridorFeet.size() > 5000) {
                this.minedCorridorFeet.clear();
            }
            this.minedCorridorFeet.add(pos.asLong());
        }
    }

    public void resetMiningState() {
        this.currentBreakingBlock = null;
        this.lockedMiningRotation = null;
        this.activeMiningTicks = 0;
        this.aimStallTicks = 0;
        this.lostSightGraceTicks = 0;
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
        ContinuousBreakController.reset();
    }

    public void stopMovementInputs() {
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, false);
    }

    public void resetDropCollectionState() {
        this.currentDropId = -1;
        this.dropCollectTicks = 0;
        this.dropStuckTicks = 0;
        this.lastDistToDropSq = Double.MAX_VALUE;
        this.lastDropGoal = null;
    }

    public void start(int customTargetY, List<String> ores) {
        this.active = true;
        this.targetY = customTargetY;
        this.config.yLevel = customTargetY;
        this.veinQueue.clear();
        this.blacklistedOres.clear();
        this.invalidStandingPositions.clear();
        this.oreStagnationTicks = 0;
        this.oreAttemptTicks = 0;
        this.returnStuckTicks = 0;
        this.activeOreTarget = null;
        this.tunnelAnchorPos = null;
        this.currentBreakingBlock = null;
        this.activeMiningTicks = 0;
        this.aimStallTicks = 0;
        this.blockedDirsByNode.clear();
        this.pendingTurnDir = null;
        this.turnCooldownTicks = 0;
        this.placeCooldownTicks = 0;
        this.stuckTicks = 0;
        this.ignoredDropIds.clear();
        this.currentDropId = -1;
        this.dropCollectTicks = 0;
        this.dropStuckTicks = 0;
        this.lastDistToDropSq = Double.MAX_VALUE;
        this.lastDropGoal = null;
        this.hazardSourcePos = null;
        this.sealCooldownTicks = 0;
        this.sealFailedAttempts = 0;
        this.minedCorridorFeet.clear();
        this.dugColumns2D.clear();
        this.hazardColumns2D.clear();
        this.retreatDir = null;
        this.retreatStartPos = null;
        this.retreatTicks = 0;
        this.lateralDetourRemaining = 0;
        this.lateralOffsetDir = null;
        this.blockedForwardDir = null;
        this.turnCommitCooldown = 0;
        this.consecutiveStraightBlocks = 0;
        this.lastTrackedMovementPos = Vec3.ZERO;
        this.movementStuckTicks = 0;
        this.startTime = System.currentTimeMillis();
        this.toolSwitchCooldownTicks = 0;
        this.postPlaceMineDelayTicks = 0;
        this.lockedMiningRotation = null;
        this.lostSightGraceTicks = 0;
        this.turnBrakeTicks = 0;
        this.protectedSealBlocks.clear();
        this.originalAutoTool = Baritone.settings().autoTool.value;
        this.originalAllowPlace = Baritone.settings().allowPlace.value;
        this.originalWalkWhileBreaking = Baritone.settings().walkWhileBreaking.value;
        Baritone.settings().autoTool.value = false;
        Baritone.settings().allowPlace.value = false;
        Baritone.settings().allowBreak.value = true;
        Baritone.settings().allowParkour.value = false;
        Baritone.settings().walkWhileBreaking.value = false;

        List<Item> throwaway = Baritone.settings().acceptableThrowawayItems.value;
        for (Item item : List.of(
                Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.DEEPSLATE,
                Items.TUFF, Items.STONE, Items.ANDESITE, Items.DIORITE,
                Items.GRANITE, Items.DIRT, Items.NETHERRACK)) {
            if (!throwaway.contains(item)) {
                throwaway.add(item);
            }
        }

        this.targetOresList.clear();
        if (ores != null && !ores.isEmpty()) {
            this.targetOresList.addAll(ores);
        } else {
            this.targetOresList.add("diamond");
        }

        baritone.getPathingBehavior().cancelSegmentIfSafe();
        baritone.getPathingBehavior().secretInternalSetGoal(null);
        baritone.getInputOverrideHandler().clearAllKeys();
        baritone.getLookBehavior().updateTarget(null, false);
        resetMiningState();
        this.noBlockBrokenTicks = 0;
        if (ctx.playerController() != null) {
            ctx.playerController().resetBlockRemoving();
        }

        LocalPlayer player = ctx.player();
        if (player != null) {
            this.tunnelDir = player.getDirection();
            if (this.tunnelDir.getAxis().isVertical()) {
                this.tunnelDir = Direction.NORTH;
            }
            this.primaryMiningDir = this.tunnelDir;
            int currentY = player.blockPosition().getY();
            if (currentY <= -50) {
                this.targetY = currentY;
                this.config.yLevel = currentY;
                this.state = State.TUNNELING;
            } else {
                this.state = currentY > targetY ? State.DESCENDING_STAIRCASE : State.TUNNELING;
            }

            logDirect(String.format("§a[Bypass] Uruchomiono! Poziom docelowy: Y=%d, autostrada: %s, rudy: %s",
                    this.targetY, this.primaryMiningDir, this.targetOresList));
        }

        this.active = true;
    }

    public void startMining(List<String> ores) {
        start(DEFAULT_TARGET_Y, ores);
    }

    public void resumeFromData(ReconnectData data) {
        if (data == null)
            return;
        List<String> ores = data.ores != null ? data.ores : List.of("diamond");
        int y = (int) Math.round(data.y);
        if (y > 0)
            y = DEFAULT_TARGET_Y;
        this.start(y, ores);
        try {
            if (data.tunnel_direction != null) {
                this.tunnelDir = Direction.valueOf(data.tunnel_direction);
                this.primaryMiningDir = this.tunnelDir;
            }
        } catch (Exception ignored) {
        }
        this.oresMined = data.ores_mined;
        logDirect(String.format("§a[Bypass] Wznowiono sesję z pliku reconnect_data.json! Wykopano wcześniej: %d rud.",
                oresMined));
    }

    public void stop() {
        onLostControl();
    }

    @Override
    public boolean isActive() {
        return active;
    }

    public boolean canBeStopped() {
        return true;
    }

    @Override
    public double priority() {
        return 6.1D;
    }

    @Override
    public boolean isTemporary() {
        return false;
    }

    @Override
    public void onLostControl() {
        this.active = false;
        this.noBlockBrokenTicks = 0;
        this.lastCountedStraightFeet = null;
        this.activeOreTarget = null;
        this.currentBreakingBlock = null;
        this.activeMiningTicks = 0;
        this.aimStallTicks = 0;
        this.blockedDirsByNode.clear();
        this.pendingTurnDir = null;
        this.turnCooldownTicks = 0;
        this.placeCooldownTicks = 0;
        this.blockedForwardDir = null;
        this.lateralDetourRemaining = 0;
        this.lateralOffsetDir = null;
        this.turnCommitCooldown = 0;
        this.consecutiveStraightBlocks = 0;
        this.toolSwitchCooldownTicks = 0;
        this.postPlaceMineDelayTicks = 0;
        this.lockedMiningRotation = null;
        this.lostSightGraceTicks = 0;
        this.turnBrakeTicks = 0;
        this.protectedSealBlocks.clear();
        Baritone.settings().autoTool.value = this.originalAutoTool;
        Baritone.settings().allowPlace.value = this.originalAllowPlace;
        Baritone.settings().walkWhileBreaking.value = this.originalWalkWhileBreaking;
        resetMiningState();
        baritone.getInputOverrideHandler().clearAllKeys();
        if (ctx.playerController() != null) {
            ctx.playerController().resetBlockRemoving();
        }
    }

    @Override
    public void onBlockChange(BlockChangeEvent event) {
        if (!this.active || event == null || event.getBlocks() == null) {
            return;
        }
        LocalPlayer player = ctx.player();
        if (player == null) {
            return;
        }
        Vec3 eye = player.getEyePosition(1.0F);
        for (Pair<BlockPos, BlockState> pair : event.getBlocks()) {
            if (pair.second().isAir()) {
                BlockPos pos = pair.first();
                if (eye.distanceToSqr(Vec3.atCenterOf(pos)) <= 49.0D) {
                    this.noBlockBrokenTicks = 0;
                    return;
                }
            }
        }
    }

    @Override
    public void onPathEvent(PathEvent event) {
        if (!isActive())
            return;
        if (event == PathEvent.CALC_FAILED || event == PathEvent.NEXT_CALC_FAILED) {
            if (activeOreTarget != null) {
                BlockPos failedOre = activeOreTarget.immutable();
                blacklistedOres.add(failedOre);
                OreScanner.blacklist(failedOre);
                for (BlockPos q : veinQueue) {
                    blacklistedOres.add(q.immutable());
                    OreScanner.blacklist(q.immutable());
                }
                veinQueue.clear();
                logDirect("§c[Bypass Blacklist] Nie znaleziono ścieżki (CALC_FAILED) do rudy "
                        + failedOre.toShortString() + " -> natychmiastowa czarna lista!");
                activeOreTarget = null;
                resetMiningState();
                stopMovementInputs();
                baritone.getPathingBehavior().cancelSegmentIfSafe();
                baritone.getPathingBehavior().secretInternalSetGoal(null);
                this.tunnelDir = this.primaryMiningDir;
                state = State.TUNNELING;
            }
        }
    }

    @Override
    public String displayName0() {
        return "BypassProcess [" + state.name() + " | Y=" + targetY + "]";
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        LocalPlayer player = ctx.player();
        Level level = ctx.world();
        if (player == null || level == null) {
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        // Błąd wyznaczenia ścieżki (No path found) do aktywnej rudy -> natychmiastowa
        // czarna lista!
        if (calcFailed && activeOreTarget != null) {
            BlockPos failedOre = activeOreTarget.immutable();
            blacklistedOres.add(failedOre);
            OreScanner.blacklist(failedOre);
            for (BlockPos q : veinQueue) {
                blacklistedOres.add(q.immutable());
                OreScanner.blacklist(q.immutable());
            }
            veinQueue.clear();
            logDirect("§c[Bypass Blacklist] Nie znaleziono ścieżki (No path found) do rudy " + failedOre.toShortString()
                    + " -> natychmiastowa czarna lista!");
            activeOreTarget = null;
            resetMiningState();
            state = State.TUNNELING;
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        // 0. Bezpieczeństwo jedzenia
        boolean isEating = player.isUsingItem()
                || (baritone.getAutoEatProcess() != null && baritone.getAutoEatProcess().isEating());
        if (isEating) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
            baritone.getInputOverrideHandler().clearAllKeys();
            resetMiningState();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (!ensureUsablePickaxe(player)) {
            logDirect("§c[Bypass] Brak jakiegokolwiek sprawnego kilofa w ekwipunku. Zatrzymywanie.");
            onLostControl();
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        // 0.1 Watchdog braku wykopanych bloków przez 30 sekund (600 ticków) ->
        // auto-restart
        if (this.state != State.IDLE && this.state != State.DISCONNECTED && player.isAlive()) {
            this.noBlockBrokenTicks++;
            if (this.noBlockBrokenTicks >= WATCHDOG_MAX_NO_BLOCK_TICKS) {
                logDirect(
                        "§c[Bypass Watchdog] Brak wykopanych bloków przez 30 sekund! Automatyczny restart (#stop -> #bypass)...");
                this.noBlockBrokenTicks = 0;
                int restartY = this.targetY;
                List<String> restartOres = new ArrayList<>(this.targetOresList);
                if (restartOres.isEmpty()) {
                    restartOres.add("diamond");
                }
                baritone.getPathingBehavior().cancelSegmentIfSafe();
                baritone.getPathingBehavior().secretInternalSetGoal(null);
                baritone.getLookBehavior().updateTarget(null, false);
                baritone.getInputOverrideHandler().clearAllKeys();
                if (ctx.playerController() != null) {
                    ctx.playerController().resetBlockRemoving();
                }
                resetMiningState();
                ContinuousBreakController.reset();
                this.start(restartY, restartOres);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }

        updateStuckWatchdog(player);

        if (sealCooldownTicks > 0) {
            sealCooldownTicks--;
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
            baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // 0. WaterSealer: Detekcja wycieku wody/lawy
        BlockPos breachPos = BypassHazardSealer.findWaterBreachSource(this, level, player.blockPosition());
        if (breachPos != null) {
            this.hazardSourcePos = breachPos;
            this.state = State.SEALING_HAZARD;
            return BypassHazardSealer.handleSealingHazard(this, player, level);
        } else if (state == State.SEALING_HAZARD) {
            this.state = State.TUNNELING;
            this.hazardSourcePos = null;
        }

        boolean activelyBreaking = isCommittedToBreaking(player, level);
        boolean followingActivePath = baritone.getPathingBehavior().isPathing();

        // 0. ZASADA COMMIT-TO-BREAK (Argon 4 & dj): Jeśli bot zaczął rozbijać blok w
        // reachu <= 3.8m,
        // DOKOŃCZ GO BEZ OBRACANIA SIĘ ZA NOWYMI RUDAMI ANI PRZERYWANIA!
        if (activelyBreaking) {
            Optional<BypassOreManager.MiningAction> keepMining = BypassOreManager.findMiningAction(this, player, level,
                    currentBreakingBlock, HARD_MINING_REACH);
            if (keepMining.isPresent()) {
                stopMovementInputs();
                return mineBlockSafely(player, level, keepMining.get().targetPos(), keepMining.get().snapRotation());
            }
        }

        // 1. Skanuj rudy i dropy TYLKO wtedy, gdy bot NIE rozbija aktualnie bloku i NIE
        // wykonuje aktywnej ścieżki A*
        if (!activelyBreaking && !followingActivePath && state != State.MINING_ORE && state != State.TURNING) {
            ItemEntity drop = BypassDropCollector.findNearbyValuableDrop(this, player, level);
            if (drop != null) {
                if (tunnelAnchorPos == null && state == State.TUNNELING) {
                    tunnelAnchorPos = player.blockPosition();
                }
                state = State.COLLECTING_DROP;
                PathingCommand collectCmd = BypassDropCollector.handleCollectingDrop(this, player, level, drop);
                if (collectCmd != null) {
                    return collectCmd;
                }
            } else if (state == State.COLLECTING_DROP) {
                resetDropCollectionState();
                tunnelAnchorPos = null;
                this.tunnelDir = this.primaryMiningDir;
                int diff = player.blockPosition().getY() - targetY;
                state = Math.abs(diff) > 1 ? (diff > 0 ? State.DESCENDING_STAIRCASE : State.ASCENDING_STAIRCASE)
                        : State.TUNNELING;
                logDirect("§a[Bypass] Zebrano drop diamentów! Natychmiastowy powrót do tunelowania.");
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }

            BlockPos foundOre = BypassOreManager.findNearestOre(this, player, level);
            if (foundOre != null) {
                if (tunnelAnchorPos == null && state == State.TUNNELING) {
                    tunnelAnchorPos = player.blockPosition();
                }
                activeOreTarget = foundOre;
                invalidStandingPositions.clear();
                oreStagnationTicks = 0;
                oreAttemptTicks = 0;
                BypassOreManager.enqueueConnectedVein(this, level, foundOre);
                stopMovementInputs();
                baritone.getPathingBehavior().cancelSegmentIfSafe();
                baritone.getPathingBehavior().secretInternalSetGoal(null);
                state = State.MINING_ORE;
            }
        }

        return switch (state) {
            case DESCENDING_STAIRCASE -> BypassNavigator.tickDescending(this, player, level);
            case ASCENDING_STAIRCASE -> BypassNavigator.tickAscending(this, player, level);
            case TUNNELING -> BypassNavigator.tickTunneling(this, player, level);
            case RETREATING -> BypassNavigator.tickRetreating(this, player, level);
            case TURNING -> BypassNavigator.tickTurning(this, player, level);
            case MINING_ORE -> BypassOreManager.tickMiningOre(this, player, level, calcFailed, isSafeToCancel);
            case COLLECTING_DROP -> {
                ItemEntity drop = BypassDropCollector.findNearbyValuableDrop(this, player, level);
                PathingCommand cmd = BypassDropCollector.handleCollectingDrop(this, player, level, drop);
                yield cmd != null ? cmd : new PathingCommand(null, PathingCommandType.DEFER);
            }
            case SEALING_HAZARD -> BypassHazardSealer.handleSealingHazard(this, player, level);
            case PATHFINDING_RETURN -> {
                // Natychmiastowe przejście do TUNNELING bez zamrażania na 80 ticków
                this.state = State.TUNNELING;
                yield new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            default -> new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        };
    }

    public PathingCommand mineBlockSafely(LocalPlayer player, Level level, BlockPos target) {
        return mineBlockSafely(player, level, target, null);
    }

    public PathingCommand mineBlockSafely(LocalPlayer player, Level level, BlockPos target, Rotation preferredRotation) {
        BlockState targetState = level.getBlockState(target);
        if (target == null || BypassNavigator.isBedrockOrUnbreakable(level, target) || targetState.isAir()
                || !level.getFluidState(target).isEmpty()) {
            if (this.currentBreakingBlock != null && targetState.isAir()) {
                this.noBlockBrokenTicks = 0;
            }
            if (ctx.playerController() != null && this.currentBreakingBlock != null) {
                ctx.playerController().resetBlockRemoving();
            }
            resetMiningState();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        int feetY = player.blockPosition().getY();
        // TWARDA BLOKADA Y-FLOOR (Argon 4 & dj):
        // W trakcie tunelowania bezwzględnie zabroń kopania jakiegokolwiek bloku poniżej poziomu stóp (Y < feetY)
        // oraz bloku podłogi bezpośrednio pod stopami gracza!
        if ((target.getY() < feetY || target.equals(player.blockPosition().below()))
                && (this.state == State.TUNNELING || !BypassOreManager.isTargetOre(this, targetState))) {
            this.currentBreakingBlock = null;
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // Zatrzymujemy ruch tylko jeśli jesteśmy w ścisłym kontakcie z blokiem (< 1.35m) lub mamy kolizję (Argon 4 & dj)
        Vec3 eye = player.getEyePosition(1.0F);
        double distSqTarget = eye.distanceToSqr(Vec3.atCenterOf(target));
        if (distSqTarget <= (1.35 * 1.35) || player.horizontalCollision) {
            stopMovementInputs();
        }

        if (!target.equals(this.currentBreakingBlock)) {
            if (ctx.playerController() != null && this.currentBreakingBlock != null) {
                ctx.playerController().resetBlockRemoving();
            }
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            ContinuousBreakController.reset();
            this.currentBreakingBlock = target.immutable();
            this.lockedMiningRotation = null;
            this.activeMiningTicks = 0;
            this.aimStallTicks = 0;
            this.lostSightGraceTicks = 0;
        }

        ToolEquipStatus toolStatus = equipBestTool(player, targetState);
        if (toolStatus == ToolEquipStatus.FAILED) {
            stopMovementInputs();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (toolStatus == ToolEquipStatus.SWITCHED) {
            if (ctx.playerController() != null) {
                ctx.playerController().resetBlockRemoving();
            }
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            ContinuousBreakController.reset();
            this.lockedMiningRotation = null;
            this.toolSwitchCooldownTicks = 1;
            this.activeMiningTicks = 0;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (this.toolSwitchCooldownTicks > 0) {
            this.toolSwitchCooldownTicks--;
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        ItemStack heldStack = player.getMainHandItem();
        if ((targetState.requiresCorrectToolForDrops() && !heldStack.isCorrectToolForDrops(targetState)) || !isToolUsable(heldStack)) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            if (ctx.playerController() != null) {
                ctx.playerController().resetBlockRemoving();
            }
            ContinuousBreakController.reset();
            return BypassNavigator.handleLavaOrObstacleEncounter(this, player, level, player.blockPosition());
        }

        int reqTicks = GrimBreakValidator.getRequiredTicks(
                player, level, target, targetState, player.onGround()
        );
        int maxExpectedTicks = Math.max(60, reqTicks + 40);

        if (this.activeMiningTicks > maxExpectedTicks) {
            if (ctx.playerController() != null) {
                ctx.playerController().resetBlockRemoving();
            }
            if (this.state == State.MINING_ORE && target != null && target.equals(this.activeOreTarget)) {
                this.blacklistedOres.add(target.immutable());
                this.activeOreTarget = null;
            }
            resetMiningState();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        double reach = getEffectiveReach();

        if (this.lockedMiningRotation != null) {
            Vec3 eyePos = player.getEyePosition(1.0F);
            Vec3 lockedLook = Vec3.directionFromRotation(this.lockedMiningRotation.getPitch(),
                    this.lockedMiningRotation.getYaw());
            BlockHitResult checkClip = level.clip(new ClipContext(
                    eyePos,
                    eyePos.add(lockedLook.scale(reach)),
                    ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE,
                    player));
            if (checkClip.getType() != HitResult.Type.BLOCK || !checkClip.getBlockPos().equals(target)) {
                this.lockedMiningRotation = null;
            }
        }

        if (this.lockedMiningRotation == null) {
            if (preferredRotation != null) {
                Vec3 eyePos = player.getEyePosition(1.0F);
                Vec3 prefLook = Vec3.directionFromRotation(preferredRotation.getPitch(), preferredRotation.getYaw());
                BlockHitResult prefClip = level.clip(new ClipContext(
                        eyePos,
                        eyePos.add(prefLook.scale(reach)),
                        ClipContext.Block.OUTLINE,
                        ClipContext.Fluid.NONE,
                        player));
                if (prefClip.getType() == HitResult.Type.BLOCK && prefClip.getBlockPos().equals(target)) {
                    this.lockedMiningRotation = preferredRotation;
                }
            }
            if (this.lockedMiningRotation == null) {
                Optional<Rotation> candidate = findMultipointGrimRotation(player, level, target, reach);
                if (candidate.isEmpty()) {
                    Optional<Rotation> fallbackSnap = RotationUtils.reachable(ctx, target, reach);
                    if (fallbackSnap.isPresent()) {
                        Rotation gcdRot = BypassNavigator.applyGrimGcd(
                                player.getYRot(),
                                player.getXRot(),
                                fallbackSnap.get().getYaw(),
                                fallbackSnap.get().getPitch());
                        candidate = Optional.of(gcdRot);
                    }
                }
                candidate.ifPresent(rot -> this.lockedMiningRotation = rot);
            }
        }

        if (this.lockedMiningRotation != null) {
            baritone.getLookBehavior().updateTarget(this.lockedMiningRotation, true);

            Vec3 eyePos = player.getEyePosition(1.0F);
            Vec3 currentLook = RotationUtils.calcLookDirectionFromRotation(ctx.playerRotations());
            BlockHitResult currentHit = level.clip(new ClipContext(
                    eyePos,
                    eyePos.add(currentLook.scale(reach)),
                    ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE,
                    player));

            float yawDelta = Math.abs(Mth.wrapDegrees(player.getYRot() - this.lockedMiningRotation.getYaw()));
            float pitchDelta = Math.abs(player.getXRot() - this.lockedMiningRotation.getPitch());
            boolean hitValid = currentHit.getType() == HitResult.Type.BLOCK && currentHit.getBlockPos().equals(target);

            // BRAMKA RAYCASTU (Argon 4 & dj):
            // Zezwól na CLICK_LEFT wyłącznie wtedy, gdy promień trafia w blok o Y >= feetY!
            // W ułamku sekundy po pęknięciu frontFeet (gdy promień minąłby blok i trafił w podłogę Y - 1)
            // CLICK_LEFT zostaje natychmiast odcięty!
            boolean isTunnelOrNonOre = (this.state == State.TUNNELING || !BypassOreManager.isTargetOre(this, targetState));
            if (isTunnelOrNonOre) {
                boolean validBlockHit = currentHit.getType() == HitResult.Type.BLOCK
                        && currentHit.getBlockPos().getY() >= feetY
                        && !currentHit.getBlockPos().equals(player.blockPosition().below());
                if (!validBlockHit) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            }
            if (hitValid || (this.state != State.TUNNELING && yawDelta <= 10.0f && pitchDelta <= 10.0f)) {
                this.lostSightGraceTicks = 0;
                this.aimStallTicks = 0;
                this.activeMiningTicks++;

                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                ContinuousBreakController.notifyBreaking(ctx, new BetterBlockPos(target));
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }

            if (this.activeMiningTicks > 0 && this.lostSightGraceTicks < 3) {
                this.lostSightGraceTicks++;
                this.activeMiningTicks++;
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                ContinuousBreakController.notifyBreaking(ctx, new BetterBlockPos(target));
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }

        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
        this.activeMiningTicks = 0;
        this.aimStallTicks++;

        if (this.lockedMiningRotation == null && this.aimStallTicks > 5) {
            nudgeTowardsVisibleEdge(player, target);
        }

        if (this.aimStallTicks > 45) {
            if (ctx.playerController() != null) {
                ctx.playerController().resetBlockRemoving();
            }
            if (this.state == State.MINING_ORE && target != null && target.equals(this.activeOreTarget)) {
                this.blacklistedOres.add(target.immutable());
                this.activeOreTarget = null;
            }
            resetMiningState();
        }

        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    public static Optional<Rotation> findMultipointGrimRotation(LocalPlayer player, Level level, BlockPos target,
            double reach) {
        Vec3 eyePos = player.getEyePosition(1.0F);
        if (eyePos.distanceToSqr(Vec3.atCenterOf(target)) > (reach + 1.2D) * (reach + 1.2D)) {
            return Optional.empty();
        }

        BlockState state = level.getBlockState(target);
        VoxelShape shape = state.getShape(level, target);
        AABB box = shape.isEmpty() ? new AABB(0, 0, 0, 1, 1, 1) : shape.bounds();

        double[] yOffsets = (target.getY() <= player.blockPosition().getY())
                ? new double[] { 0.88D, 0.78D, 0.68D, 0.58D }
                : new double[] { 0.55D, 0.72D, 0.82D, 0.35D };
        double[] xzOffsets = { 0.50D, 0.35D, 0.65D, 0.25D, 0.75D };

        Rotation bestRot = null;
        double bestScore = Double.MAX_VALUE;

        for (double yFrac : yOffsets) {
            double py = target.getY() + Mth.lerp(yFrac, box.minY, box.maxY);
            for (double xFrac : xzOffsets) {
                double px = target.getX() + Mth.lerp(xFrac, box.minX, box.maxX);
                for (double zFrac : xzOffsets) {
                    double pz = target.getZ() + Mth.lerp(zFrac, box.minZ, box.maxZ);
                    Vec3 hitVec = new Vec3(px, py, pz);
                    if (eyePos.distanceToSqr(hitVec) > reach * reach)
                        continue;

                    Rotation rawRot = BypassNavigator.calcRotationFromVec3d(eyePos, hitVec, player.getYRot());
                    Rotation gcdRot = BypassNavigator.applyGrimGcd(player.getYRot(), player.getXRot(), rawRot.getYaw(),
                            rawRot.getPitch());

                    Vec3 lookVec = Vec3.directionFromRotation(gcdRot.getPitch(), gcdRot.getYaw());
                    BlockHitResult clip = level.clip(new ClipContext(
                            eyePos,
                            eyePos.add(lookVec.scale(reach)),
                            ClipContext.Block.OUTLINE,
                            ClipContext.Fluid.NONE,
                            player));

                    if (clip.getType() == HitResult.Type.BLOCK && clip.getBlockPos().equals(target)) {
                        double angDist = Math.hypot(
                                Mth.wrapDegrees(gcdRot.getYaw() - player.getYRot()),
                                gcdRot.getPitch() - player.getXRot());
                        double edgePenalty = (Math.abs(xFrac - 0.5D) + Math.abs(zFrac - 0.5D)) * 8.0D;
                        double score = angDist + edgePenalty;
                        if (score < bestScore) {
                            bestScore = score;
                            bestRot = gcdRot;
                        }
                    }
                }
            }
        }
        return Optional.ofNullable(bestRot);
    }

    private void nudgeTowardsVisibleEdge(LocalPlayer player, BlockPos target) {
        Vec3 targetCenter = Vec3.atCenterOf(target);
        double dx = targetCenter.x - player.getX();
        double dz = targetCenter.z - player.getZ();
        float moveYaw = (float) (Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        float yawDiff = Math.abs(Mth.wrapDegrees(player.getYRot() - moveYaw));

        if (yawDiff < 60.0F) {
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        }
    }

    public boolean isFastPickaxeActive(LocalPlayer player) {
        if (FastBreakHelper.hasEfficiency10(ctx) || FastBreakHelper.isFastPickaxe(ctx)) {
            return true;
        }
        ItemStack held = player.getMainHandItem();
        if (held.getItem() instanceof PickaxeItem) {
            return held.getHoverName().getString().contains("10");
        }
        return false;
    }

    public boolean isPickaxe(ItemStack stack) {
        if (stack == null || stack.isEmpty())
            return false;
        return stack.getItem() instanceof PickaxeItem;
    }

    public boolean isToolUsable(ItemStack stack) {
        if (stack == null || stack.isEmpty())
            return false;
        if (!stack.isDamageableItem())
            return true;
        int max = stack.getMaxDamage();
        int cur = stack.getDamageValue();
        return (max - cur) > MIN_TOOL_DURABILITY;
    }

    public boolean hasOnlyCriticallyDamagedTools(LocalPlayer player, BlockState state) {
        if (player == null || state == null)
            return false;
        boolean foundCriticallyDamaged = false;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty())
                continue;
            if (stack.isCorrectToolForDrops(state) || stack.getItem() instanceof DiggerItem) {
                if (isToolUsable(stack)) {
                    return false;
                }
                foundCriticallyDamaged = true;
            }
        }
        return foundCriticallyDamaged;
    }

    public boolean ensureUsablePickaxe(LocalPlayer player) {
        if (player == null)
            return false;
        ItemStack held = player.getMainHandItem();
        if (isPickaxe(held) && isToolUsable(held)) {
            return true;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (isPickaxe(stack) && isToolUsable(stack)) {
                player.getInventory().selected = i;
                if (ctx.playerController() != null) {
                    ctx.playerController().syncHeldItem();
                }
                return true;
            }
        }
        for (int i = 9; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (isPickaxe(stack) && isToolUsable(stack)) {
                if (ctx.playerController() != null) {
                    ctx.playerController().windowClick(0, i, player.getInventory().selected,
                            net.minecraft.world.inventory.ClickType.SWAP, player);
                    ctx.playerController().syncHeldItem();
                    return true;
                }
            }
        }
        return false;
    }

    public ToolEquipStatus equipBestTool(LocalPlayer player, BlockState state) {
        if (player == null || state == null)
            return ToolEquipStatus.NO_CHANGE;
        ItemStack held = player.getMainHandItem();

        if (state.isAir()) {
            if (isPickaxe(held) && isToolUsable(held))
                return ToolEquipStatus.NO_CHANGE;
            if (ensureUsablePickaxe(player))
                return ToolEquipStatus.SWITCHED;
            return ToolEquipStatus.NO_CHANGE;
        }

        int currentSlot = player.getInventory().selected;
        int bestHotbarSlot = findBestToolInRange(player, state, 0, 9, currentSlot);

        if (bestHotbarSlot != -1 && bestHotbarSlot != currentSlot) {
            player.getInventory().selected = bestHotbarSlot;
            if (ctx.playerController() != null) {
                ctx.playerController().syncHeldItem();
            }
            return ToolEquipStatus.SWITCHED;
        }

        if (bestHotbarSlot == currentSlot) {
            return ToolEquipStatus.NO_CHANGE;
        }

        int bestInvSlot = findBestToolInRange(player, state, 9, 36, -1);
        if (bestInvSlot != -1) {
            if (ctx.playerController() != null) {
                ctx.playerController().windowClick(0, bestInvSlot, currentSlot,
                        net.minecraft.world.inventory.ClickType.SWAP, player);
                ctx.playerController().syncHeldItem();
                return ToolEquipStatus.SWITCHED;
            }
        }

        if (state.requiresCorrectToolForDrops()) {
            if (!held.isCorrectToolForDrops(state) || !isToolUsable(held)) {
                return ToolEquipStatus.FAILED;
            }
        }

        return ToolEquipStatus.NO_CHANGE;
    }

    private int findBestToolInRange(LocalPlayer player, BlockState state, int startSlot, int endSlot,
            int currentSelected) {
        int bestSlot = -1;
        float bestSpeed = 1.0F;

        if (currentSelected >= startSlot && currentSelected < endSlot) {
            ItemStack held = player.getInventory().getItem(currentSelected);
            if (isToolUsable(held)) {
                float heldSpeed = held.getDestroySpeed(state);
                if (held.isCorrectToolForDrops(state))
                    heldSpeed *= 1.5F;
                bestSpeed = heldSpeed;
                bestSlot = currentSelected;
            }
        }

        for (int i = startSlot; i < endSlot; i++) {
            if (i == currentSelected)
                continue;
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty() || !isToolUsable(stack))
                continue;

            float speed = stack.getDestroySpeed(state);
            boolean correct = stack.isCorrectToolForDrops(state);
            if (correct)
                speed *= 1.5F;

            if (state.requiresCorrectToolForDrops()) {
                if (!correct)
                    continue;
                if (bestSlot == -1 || !player.getInventory().getItem(bestSlot).isCorrectToolForDrops(state)
                        || speed > bestSpeed) {
                    bestSpeed = speed;
                    bestSlot = i;
                }
            } else {
                if (speed > bestSpeed) {
                    bestSpeed = speed;
                    bestSlot = i;
                }
            }
        }
        return bestSlot;
    }

    private void updateStuckWatchdog(LocalPlayer player) {
        BlockPos currentPos = player.blockPosition();
        if (currentPos.equals(lastPlayerPos)) {
            stuckTicks++;
            if (stuckTicks >= 100) {
                stuckTicks = 0;
                baritone.getInputOverrideHandler().clearAllKeys();
                resetMiningState();
                if (state == State.MINING_ORE && activeOreTarget != null) {
                    blacklistedOres.add(activeOreTarget);
                    activeOreTarget = null;
                    state = State.TUNNELING;
                } else if (state == State.COLLECTING_DROP) {
                    resetDropCollectionState();
                    state = State.TUNNELING;
                }
            }
        } else {
            stuckTicks = 0;
            lastPlayerPos = currentPos;
        }
    }

    // =========================================================================
    // GETTERY I SETTERY DLA WYODRĘBNIONYCH MODUŁÓW (Argon 4 & dj)
    // =========================================================================

    public int getTargetY() {
        return targetY;
    }

    public void setTargetY(int y) {
        this.targetY = y;
    }

    public State getState() {
        return state;
    }

    public void setState(State s) {
        this.state = s;
        this.phase = s != null ? s.name().toLowerCase(Locale.ROOT) : "idle";
    }

    public String getPhase() {
        return state != null ? state.name().toLowerCase(Locale.ROOT) : "idle";
    }

    public int getOresMined() {
        return oresMined;
    }

    public Direction getTunnelDir() {
        return tunnelDir;
    }

    public void setTunnelDir(Direction d) {
        this.tunnelDir = d;
    }

    public Direction getPrimaryMiningDir() {
        return primaryMiningDir;
    }

    public void setPrimaryMiningDir(Direction d) {
        this.primaryMiningDir = d;
    }

    public BlockPos getTunnelAnchorPos() {
        return tunnelAnchorPos;
    }

    public void setTunnelAnchorPos(BlockPos p) {
        this.tunnelAnchorPos = p;
    }

    public BlockPos getActiveOreTarget() {
        return activeOreTarget;
    }

    public void setActiveOreTarget(BlockPos p) {
        this.activeOreTarget = p;
    }

    public BlockPos getCurrentBreakingBlock() {
        return currentBreakingBlock;
    }

    public void setCurrentBreakingBlock(BlockPos p) {
        this.currentBreakingBlock = p;
    }

    public int getActiveMiningTicks() {
        return activeMiningTicks;
    }

    public void setActiveMiningTicks(int t) {
        this.activeMiningTicks = t;
    }

    public int getAimStallTicks() {
        return aimStallTicks;
    }

    public void setAimStallTicks(int t) {
        this.aimStallTicks = t;
    }

    public int getTurnBrakeTicks() {
        return turnBrakeTicks;
    }

    public void setTurnBrakeTicks(int t) {
        this.turnBrakeTicks = t;
    }

    public int getTurnCooldownTicks() {
        return turnCooldownTicks;
    }

    public void setTurnCooldownTicks(int t) {
        this.turnCooldownTicks = t;
    }

    public int getTurnCommitCooldown() {
        return turnCommitCooldown;
    }

    public void setTurnCommitCooldown(int t) {
        this.turnCommitCooldown = t;
    }

    public Deque<BlockPos> getVeinQueue() {
        return veinQueue;
    }

    public Set<BlockPos> getBlacklistedOres() {
        return blacklistedOres;
    }

    public Set<BlockPos> getInvalidStandingPositions() {
        return invalidStandingPositions;
    }

    public List<String> getTargetOresList() {
        return targetOresList;
    }

    public Map<Integer, Long> getIgnoredDropIds() {
        return ignoredDropIds;
    }

    public int getCurrentDropId() {
        return currentDropId;
    }

    public void setCurrentDropId(int id) {
        this.currentDropId = id;
    }

    public int getDropCollectTicks() {
        return dropCollectTicks;
    }

    public void setDropCollectTicks(int t) {
        this.dropCollectTicks = t;
    }

    public int getDropStuckTicks() {
        return dropStuckTicks;
    }

    public void setDropStuckTicks(int t) {
        this.dropStuckTicks = t;
    }

    public double getLastDistToDropSq() {
        return lastDistToDropSq;
    }

    public void setLastDistToDropSq(double d) {
        this.lastDistToDropSq = d;
    }

    public Goal getLastDropGoal() {
        return lastDropGoal;
    }

    public void setLastDropGoal(Goal g) {
        this.lastDropGoal = g;
    }

    public BlockPos getHazardSourcePos() {
        return hazardSourcePos;
    }

    public void setHazardSourcePos(BlockPos p) {
        this.hazardSourcePos = p;
    }

    public int getSealCooldownTicks() {
        return sealCooldownTicks;
    }

    public void setSealCooldownTicks(int t) {
        this.sealCooldownTicks = t;
    }

    public int getSealFailedAttempts() {
        return sealFailedAttempts;
    }

    public void setSealFailedAttempts(int t) {
        this.sealFailedAttempts = t;
    }

    public int getPlaceCooldownTicks() {
        return placeCooldownTicks;
    }

    public void setPlaceCooldownTicks(int t) {
        this.placeCooldownTicks = t;
    }

    public LongSet getDugColumns2D() {
        return dugColumns2D;
    }

    public LongSet getHazardColumns2D() {
        return hazardColumns2D;
    }

    public Map<BlockPos, EnumSet<Direction>> getBlockedDirsByNode() {
        return blockedDirsByNode;
    }

    public Direction getPendingTurnDir() {
        return pendingTurnDir;
    }

    public void setPendingTurnDir(Direction d) {
        this.pendingTurnDir = d;
    }

    public Direction getRetreatDir() {
        return retreatDir;
    }

    public void setRetreatDir(Direction d) {
        this.retreatDir = d;
    }

    public BlockPos getRetreatStartPos() {
        return retreatStartPos;
    }

    public void setRetreatStartPos(BlockPos p) {
        this.retreatStartPos = p;
    }

    public int getRetreatTicks() {
        return retreatTicks;
    }

    public void setRetreatTicks(int t) {
        this.retreatTicks = t;
    }

    public int getTargetRetreatBlocks() {
        return targetRetreatBlocks;
    }

    public void setTargetRetreatBlocks(int b) {
        this.targetRetreatBlocks = b;
    }

    public int getLateralDetourRemaining() {
        return lateralDetourRemaining;
    }

    public void setLateralDetourRemaining(int r) {
        this.lateralDetourRemaining = r;
    }

    public Direction getLateralOffsetDir() {
        return lateralOffsetDir;
    }

    public void setLateralOffsetDir(Direction d) {
        this.lateralOffsetDir = d;
    }

    public Direction getBlockedForwardDir() {
        return blockedForwardDir;
    }

    public void setBlockedForwardDir(Direction d) {
        this.blockedForwardDir = d;
    }

    public int getConsecutiveStraightBlocks() {
        return consecutiveStraightBlocks;
    }

    public void setConsecutiveStraightBlocks(int b) {
        this.consecutiveStraightBlocks = b;
    }

    public BlockPos getLastCountedStraightFeet() {
        return lastCountedStraightFeet;
    }

    public void setLastCountedStraightFeet(BlockPos p) {
        this.lastCountedStraightFeet = p;
    }

    public int getMovementStuckTicks() {
        return movementStuckTicks;
    }

    public void setMovementStuckTicks(int t) {
        this.movementStuckTicks = t;
    }

    public Vec3 getLastTrackedMovementPos() {
        return lastTrackedMovementPos;
    }

    public void setLastTrackedMovementPos(Vec3 v) {
        this.lastTrackedMovementPos = v;
    }

    public int getNoBlockBrokenTicks() {
        return noBlockBrokenTicks;
    }

    public void setNoBlockBrokenTicks(int t) {
        this.noBlockBrokenTicks = t;
    }

    public int getStuckTicks() {
        return stuckTicks;
    }

    public void setStuckTicks(int t) {
        this.stuckTicks = t;
    }

    public int getOreStagnationTicks() {
        return oreStagnationTicks;
    }

    public void setOreStagnationTicks(int t) {
        this.oreStagnationTicks = t;
    }

    public int getOreAttemptTicks() {
        return oreAttemptTicks;
    }

    public void setOreAttemptTicks(int t) {
        this.oreAttemptTicks = t;
    }

    public int getReturnStuckTicks() {
        return returnStuckTicks;
    }

    public void setReturnStuckTicks(int t) {
        this.returnStuckTicks = t;
    }

    public Set<BlockPos> getProtectedSealBlocks() {
        return protectedSealBlocks;
    }

    public int getPostPlaceMineDelayTicks() {
        return postPlaceMineDelayTicks;
    }

    public void setPostPlaceMineDelayTicks(int t) {
        this.postPlaceMineDelayTicks = t;
    }

    public void incrementOresMined() {
        this.oresMined++;
    }

    public void incrementDiamondCount(int c) {
        this.diamondCount += c;
    }

    public void incrementGoldCount(int c) {
        this.goldCount += c;
    }

    public BlockPos getCurrentTargetBlock() {
        return activeOreTarget != null ? activeOreTarget : currentBreakingBlock;
    }

    public BypassConfig getConfig() {
        return config;
    }

    public Direction getTunnelDirection() {
        return tunnelDir;
    }

    public int getDiamondCount() {
        return diamondCount;
    }

    public int getGoldCount() {
        return goldCount;
    }

    public long getStartTime() {
        return startTime;
    }

    public List<String> getTargetOres() {
        return Collections.unmodifiableList(targetOresList);
    }

    public Set<String> getTargetDropItems() {
        return Collections.unmodifiableSet(targetDropItems);
    }

    public BypassLuaEngine getLuaEngine() {
        return luaEngine;
    }
}
