package baritone.bypass;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalItemDrop;
import baritone.api.pathing.goals.GoalTwoBlocks;
import baritone.api.pathing.goals.GoalYLevel;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.api.utils.interfaces.IGoalRenderPos;
import baritone.bypass.scripting.BypassLuaEngine;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.ContinuousBreakController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.*;

/**
 * Czysty rdzeń procesu #bypass (Argon 4 & dj architecture):
 * 1. Bezpieczne schodki w dół z prześwitem 3 bloków do poziomu docelowego (domyślnie Y = -50).
 * 2. Kopanie tunelu 1x2 przed siebie z kontrolą dystansu (~1.15m) bez wbiegania w ściany.
 * 3. Aktywne omijanie zagrożeń: lawa, woda, gravel/sand nad głową, dziury/jaskinie bez podłoża.
 * 4. Ciągły skan 5 bloków w każdą stronę w poszukiwaniu diamentów/rud (kostka 11x11x11) + Vein Mining 3x3x3.
 * 5. Ścisły reach <= 3.5m (GrimAC safe reach limit):
 *    - Jeśli ruda w zasięgu <= 3.5m: 1-tick GrimSnap GCD i natychmiastowe wykopanie.
 *    - Jeśli ruda dalej lub za ścianą: podejście przez Baritone Pathing (GoalOreMining / GoalGetToBlock) jak w #mine.
 * 6. Automatyczne zbieranie dropu i powrót do tunelowania na Y=-50.
 */
public final class BypassProcess extends BaritoneProcessHelper implements IBaritoneProcess {

    public static final double STRICT_REACH = 3.5D;
    private static final double GOAL_APPROACH_EYE_REACH = 2.65D;
    private static final double WALL_STOP_DISTANCE = 1.15D;
    private static final int ORE_SCAN_RADIUS = 3; // Ściśle 3 bloki w każdą stronę wokół gracza (kostka 7x7x7)
    public static final int DEFAULT_TARGET_Y = -54;
    public static final int MIN_SAFE_BEDROCK_Y = -54;
    private static final int MIN_TOOL_DURABILITY = 5;

    public enum State {
        DESCENDING_STAIRCASE,
        ASCENDING_STAIRCASE,
        TUNNELING,
        MINING_ORE,
        COLLECTING_DROP,
        SEALING_HAZARD,
        RETREATING_MOB,
        PATHFINDING_RETURN,
        DISCONNECTED,
        IDLE
    }

    private static final Map<String, Set<Block>> ORE_BLOCK_MAP = Map.of(
            "diamond", Set.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE),
            "gold", Set.of(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE),
            "iron", Set.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE),
            "copper", Set.of(Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE),
            "coal", Set.of(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE),
            "lapis", Set.of(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE),
            "redstone", Set.of(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE),
            "emerald", Set.of(Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE),
            "debris", Set.of(Blocks.ANCIENT_DEBRIS)
    );

    private static final Map<String, Set<Item>> ORE_ITEM_MAP = Map.of(
            "diamond", Set.of(Items.DIAMOND, Items.DIAMOND_ORE, Items.DEEPSLATE_DIAMOND_ORE),
            "gold", Set.of(Items.RAW_GOLD, Items.GOLD_INGOT, Items.GOLD_NUGGET),
            "iron", Set.of(Items.RAW_IRON, Items.IRON_INGOT),
            "copper", Set.of(Items.RAW_COPPER, Items.COPPER_INGOT),
            "coal", Set.of(Items.COAL),
            "lapis", Set.of(Items.LAPIS_LAZULI),
            "redstone", Set.of(Items.REDSTONE),
            "emerald", Set.of(Items.EMERALD),
            "debris", Set.of(Items.ANCIENT_DEBRIS, Items.NETHERITE_SCRAP)
    );

    public static String normalizeOreKey(String key) {
        if (key == null) return "diamond";
        String s = key.toLowerCase(Locale.ROOT).trim();
        if (s.equals("diamonds") || s.equals("diax") || s.equals("diaxy") || s.equals("diamond_ore")) return "diamond";
        if (s.equals("gold_ore") || s.equals("zloto")) return "gold";
        if (s.equals("iron_ore") || s.equals("zelazo")) return "iron";
        if (s.equals("copper_ore") || s.equals("miedz")) return "copper";
        if (s.equals("coal_ore") || s.equals("wegiel")) return "coal";
        if (s.equals("lapis_ore") || s.equals("lazuryt") || s.equals("lapis_lazuli")) return "lapis";
        if (s.equals("redstone_ore") || s.equals("czerwony")) return "redstone";
        if (s.equals("emerald_ore") || s.equals("szmaragd") || s.equals("emeralds")) return "emerald";
        if (s.equals("ancient_debris") || s.equals("netherite")) return "debris";
        if (s.endsWith("_ore")) s = s.substring(0, s.length() - 4);
        return s;
    }

    private final BypassConfig config = new BypassConfig();
    private final BypassLuaEngine luaEngine;

    private boolean active = false;
    private int targetY = DEFAULT_TARGET_Y;
    private State state = State.IDLE;
    private Direction tunnelDir = Direction.NORTH;

    private BlockPos tunnelAnchorPos = null;
    private BlockPos activeOreTarget = null;
    private BlockPos currentBreakingBlock = null;
    private final Deque<BlockPos> veinQueue = new ArrayDeque<>();
    private final Set<BlockPos> blacklistedOres = new HashSet<>();
    private final Set<BlockPos> invalidStandingPositions = new HashSet<>();
    private final List<String> targetOresList = new ArrayList<>(List.of("diamond"));
    private final Set<String> targetDropItems = new HashSet<>(Set.of("diamond", "raw_iron", "raw_gold", "ancient_debris"));

    // Pola stanu dla COLLECTING_DROP (odzyskiwanie dropu i rozkopywanie wnęk)
    private final Map<Integer, Long> ignoredDropIds = new HashMap<>();
    private int currentDropId = -1;
    private int dropCollectTicks = 0;
    private int dropStuckTicks = 0;
    private double lastDistToDropSq = Double.MAX_VALUE;
    private Goal lastDropGoal = null;

    // Pola stanu dla SEALING_HAZARD (WaterSealer - zatykanie wycieków wody/lawy)
    private BlockPos hazardSourcePos = null;
    private int sealCooldownTicks = 0;
    private int sealFailedAttempts = 0;
    private static final int MAX_SEAL_ATTEMPTS = 5;

    // Pola dla unikania pustych / wykopanych tuneli oraz Dominant Mining Vector (Argon 4 & dj)
    private Direction primaryMiningDir = Direction.NORTH;
    private final it.unimi.dsi.fastutil.longs.LongSet dugColumns2D = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private int lateralDetourRemaining = 0;
    private Direction lateralOffsetDir = null;
    private Direction blockedForwardDir = null;
    private int turnCommitCooldown = 0;
    private int consecutiveStraightBlocks = 0;
    private static final int MIN_PARALLEL_SPACING = 3;
    private final Set<Long> minedCorridorFeet = new HashSet<>();

    // Płynność ruchu, skoki Early Ascend i nawigacja wodna (Argon 4 & dj)
    private Vec3 lastTrackedMovementPos = Vec3.ZERO;
    private int movementStuckTicks = 0;

    private int stuckTicks = 0;
    private int oreStagnationTicks = 0;
    private int oreAttemptTicks = 0;
    private int returnStuckTicks = 0;
    private BlockPos lastPlayerPos = null;
    private int oresMined = 0;
    private int diamondCount = 0;
    private int goldCount = 0;
    private long startTime = 0L;

    public double getEffectiveReach() {
        Minecraft mc = ctx.minecraft();
        if (mc != null && mc.hasSingleplayerServer()) {
            return 4.5D;
        }
        return STRICT_REACH;
    }

    public BypassProcess(Baritone baritone) {
        super(baritone);
        this.luaEngine = new BypassLuaEngine(baritone, this);
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
        this.lateralDetourRemaining = 0;
        this.lateralOffsetDir = null;
        this.blockedForwardDir = null;
        this.turnCommitCooldown = 0;
        this.consecutiveStraightBlocks = 0;
        this.lastTrackedMovementPos = Vec3.ZERO;
        this.movementStuckTicks = 0;
        this.startTime = System.currentTimeMillis();

        // Wymuszenie uprawnień Baritone do podbudowywania się (MovementPillar) i przebijania ścian
        Baritone.settings().allowPlace.value = true;
        Baritone.settings().allowBreak.value = true;
        Baritone.settings().allowParkour.value = false;

        // Rejestracja bloków z poziomu Y=-50 jako budulca do pillaringu
        List<Item> throwaway = Baritone.settings().acceptableThrowawayItems.value;
        for (Item item : List.of(
                Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.DEEPSLATE,
                Items.TUFF, Items.STONE, Items.ANDESITE, Items.DIORITE,
                Items.GRANITE, Items.DIRT, Items.NETHERRACK
        )) {
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

        LocalPlayer player = ctx.player();
        if (player != null) {
            this.tunnelDir = player.getDirection();
            if (this.tunnelDir.getAxis().isVertical()) {
                this.tunnelDir = Direction.NORTH;
            }
            this.primaryMiningDir = this.tunnelDir;
            int currentY = player.blockPosition().getY();
            this.state = currentY > targetY ? State.DESCENDING_STAIRCASE : State.TUNNELING;
            logDirect(String.format("§a[Bypass] Uruchomiono! Poziom docelowy: Y=%d, autostrada: %s, rudy: %s",
                    targetY, primaryMiningDir, targetOresList));
        } else {
            this.primaryMiningDir = Direction.NORTH;
            this.tunnelDir = Direction.NORTH;
            this.state = State.DESCENDING_STAIRCASE;
        }
    }

    public void start(int customTargetY) {
        start(customTargetY, List.of("diamond"));
    }

    public void start() {
        start(DEFAULT_TARGET_Y, List.of("diamond"));
    }

    public void startMining(List<String> ores) {
        int configuredY = (config != null) ? config.yLevel : DEFAULT_TARGET_Y;
        int y = Math.max(configuredY, MIN_SAFE_BEDROCK_Y);
        start(y, ores);
    }

    public void resumeFromData(ReconnectData data) {
        if (data == null) return;
        this.targetY = (int) Math.round(data.y);
        start(this.targetY, data.ores != null ? data.ores : List.of("diamond"));
    }

    public void stop() {
        this.active = false;
        onLostControl();
        logDirect("§e[Bypass] Zatrzymano proces.");
    }

    @Override
    public boolean isActive() {
        return this.active && ctx.player() != null && ctx.world() != null;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        LocalPlayer player = ctx.player();
        Level level = ctx.world();
        if (player == null || level == null) {
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        if (!ensureUsablePickaxe(player)) {
            logDirect("§c[Bypass] Brak jakiegokolwiek sprawnego kilofa w ekwipunku. Zatrzymywanie.");
            onLostControl();
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        updateStuckWatchdog(player);

        if (sealCooldownTicks > 0) {
            sealCooldownTicks--;
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
            baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // 0. WaterSealer: Detekcja wycieku wody/lawy zagrażającego graczowi i natychmiastowe zatkanie
        BlockPos breachPos = findWaterBreachSource(level, player.blockPosition());
        if (breachPos != null) {
            this.hazardSourcePos = breachPos;
            this.state = State.SEALING_HAZARD;
            return handleSealingHazard(player, level);
        } else if (state == State.SEALING_HAZARD) {
            this.state = State.TUNNELING;
            this.hazardSourcePos = null;
        }

        // 1. Priorytet: Ciągłe skanowanie 3 kratek wokół gracza (kostka 7x7x7)
        if (state != State.MINING_ORE) {
            BlockPos foundOre = findNearestOre(player, level);
            if (foundOre != null) {
                if (tunnelAnchorPos == null && state == State.TUNNELING) {
                    tunnelAnchorPos = player.blockPosition();
                }
                activeOreTarget = foundOre;
                invalidStandingPositions.clear();
                oreStagnationTicks = 0;
                oreAttemptTicks = 0;
                enqueueConnectedVein(level, foundOre);
                state = State.MINING_ORE;
            }
        }

        // 2. Zbieranie leżącego dropu diamentów/surowców w pobliżu
        if (state != State.MINING_ORE) {
            ItemEntity drop = findNearbyValuableDrop(player, level);
            if (drop != null) {
                if (tunnelAnchorPos == null && state == State.TUNNELING) {
                    tunnelAnchorPos = player.blockPosition();
                }
                state = State.COLLECTING_DROP;
                PathingCommand collectCmd = handleCollectingDrop(player, level, drop);
                if (collectCmd != null) {
                    return collectCmd;
                }
            } else if (state == State.COLLECTING_DROP) {
                resetDropCollectionState();
                if (tunnelAnchorPos == null || player.blockPosition().distManhattan(tunnelAnchorPos) <= 2) {
                    tunnelAnchorPos = null;
                    int diff = player.blockPosition().getY() - targetY;
                    state = Math.abs(diff) > 1 ? (diff > 0 ? State.DESCENDING_STAIRCASE : State.ASCENDING_STAIRCASE) : State.TUNNELING;
                } else {
                    state = State.PATHFINDING_RETURN;
                }
            }
        }

        return switch (state) {
            case DESCENDING_STAIRCASE -> tickDescending(player, level);
            case ASCENDING_STAIRCASE -> tickAscending(player, level);
            case TUNNELING -> tickTunneling(player, level);
            case MINING_ORE -> tickMiningOre(player, level, calcFailed, isSafeToCancel);
            case COLLECTING_DROP -> {
                ItemEntity drop = findNearbyValuableDrop(player, level);
                PathingCommand cmd = handleCollectingDrop(player, level, drop);
                yield cmd != null ? cmd : new PathingCommand(null, PathingCommandType.DEFER);
            }
            case SEALING_HAZARD -> handleSealingHazard(player, level);
            case PATHFINDING_RETURN -> tickReturningToTunnel(player, level, calcFailed);
            default -> new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        };
    }

    /**
     * KROK 1: Bezpieczne schodki 1x3 w dół do targetY (Y = -50) z planowaniem A* i blokadą Bedrocka.
     * Czyszczenie pełnej wysokości 3 bloków zapobiega blokowaniu głowy o sufit.
     */
    private PathingCommand tickDescending(LocalPlayer player, Level level) {
        BlockPos feet = player.blockPosition();
        if (feet.getY() <= targetY || feet.getY() <= MIN_SAFE_BEDROCK_Y) {
            state = State.TUNNELING;
            tunnelAnchorPos = feet;
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        // ZAWSZE schodzimy wzdłuż głównego kierunku marszu (primaryMiningDir)
        Direction descendDir = this.primaryMiningDir != null ? this.primaryMiningDir : this.tunnelDir;
        this.tunnelDir = descendDir;

        BlockPos frontHead = feet.relative(descendDir).above();
        BlockPos frontFeet = feet.relative(descendDir);
        BlockPos stepDown = frontFeet.below();
        BlockPos stepFloor = stepDown.below();

        // 1. Zabezpieczenie przed Bedrockiem:
        // Jeśli stopień w dół (stepDown) lub przód to bedrock, nie możemy zejść niżej.
        // Zamiast obracać się o 180° w tył (co powodowało spadek z bloku), adaptujemy obecny poziom Y jako bezpieczną podłogę!
        if (stepDown.getY() <= MIN_SAFE_BEDROCK_Y || isBedrockOrUnbreakable(level, stepDown) || isBedrockOrUnbreakable(level, frontFeet)) {
            this.targetY = feet.getY();
            this.state = State.TUNNELING;
            this.tunnelDir = this.primaryMiningDir;
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        // 2. Zagrożenie na przodzie (lawa / woda / brak podłoża pod docelowym stopniem)
        if (isHazardNear(level, frontHead) || isHazardNear(level, frontFeet) || isHazardNear(level, stepDown)
                || !isSolidSupport(level, stepFloor)) {
            if (!isSolidSupport(level, stepFloor) && tryBridgeFloor(player, level, stepFloor, feet.below())) {
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            return resolveObstacleDeadlock(player, level, feet);
        }

        // Kolejność kopania schodków 1x3: sufit nad głową -> przód góra -> przód dół -> docelowy stopień
        List<BlockPos> stairBlocks = List.of(
                feet.above(2),
                frontHead,
                frontFeet,
                stepDown
        );

        for (BlockPos target : stairBlocks) {
            if (isBedrockOrUnbreakable(level, target)) {
                this.targetY = feet.getY();
                this.state = State.TUNNELING;
                this.tunnelDir = this.primaryMiningDir;
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            if (!isPassable(level, target)) {
                return mineBlockSafely(player, level, target);
            }
        }

        // Wszystkie bloki schodka są czyste -> płynny krok w przód i w dół bez spamu A*
        tunnelAnchorPos = stepDown;
        moveSafelyTowards(player, Vec3.atBottomCenterOf(stepDown));
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    /**
     * Schodki 1x3 w górę z omijaniem Bedrocka i lawy powracające na targetY (np. Y = -50).
     */
    private PathingCommand tickAscending(LocalPlayer player, Level level) {
        BlockPos feet = player.blockPosition();
        if (feet.getY() >= targetY) {
            baritone.getInputOverrideHandler().clearAllKeys();
            if (this.currentBreakingBlock != null) {
                this.currentBreakingBlock = null;
                ctx.playerController().resetBlockRemoving();
            }
            state = State.TUNNELING;
            tunnelAnchorPos = feet;
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        // Sprawdź, czy kierunek schodków w górę jest wolny od bedrocka i lawy/wody
        if (!isAscendingDirectionClear(level, feet, tunnelDir)) {
            rotateAscendingDirectionSafe(level, feet);
            if (!isAscendingDirectionClear(level, feet, tunnelDir)) {
                // BEZWZGLĘDNY ZAKAZ cofania się do starej dziury w suficie przez GoalYLevel!
                if (feet.getY() >= MIN_SAFE_BEDROCK_Y) {
                    this.targetY = feet.getY();
                    this.state = State.TUNNELING;
                    this.tunnelDir = this.primaryMiningDir;
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                BlockPos forwardAscendTarget = feet.relative(primaryMiningDir, 4).atY(Math.min(targetY, MIN_SAFE_BEDROCK_Y));
                return new PathingCommand(new baritone.api.pathing.goals.GoalBlock(forwardAscendTarget), PathingCommandType.SET_GOAL_AND_PATH);
            }
        }

        BlockPos headClearance = feet.above(2);
        BlockPos nextStepFeet  = feet.relative(tunnelDir).above(1);
        BlockPos nextStepHead  = feet.relative(tunnelDir).above(2);

        // Bloki do wykopania w profilu 1x3 w górę (sufit nad głową -> głowa stopnia -> stopy stopnia)
        List<BlockPos> ascendBlocks = List.of(headClearance, nextStepHead, nextStepFeet);
        for (BlockPos target : ascendBlocks) {
            if (isBedrockOrUnbreakable(level, target)) {
                rotateAscendingDirectionSafe(level, feet);
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            if (!isPassable(level, target)) {
                return mineBlockSafely(player, level, target);
            }
        }

        // Profil jest czysty -> wchodzimy na stopień
        if (this.currentBreakingBlock != null) {
            this.currentBreakingBlock = null;
            ctx.playerController().resetBlockRemoving();
        }
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);

        BlockPos stepBase = feet.relative(tunnelDir);
        if (!isSolidSupport(level, stepBase)) {
            if (!tryBridgeFloor(player, level, stepBase, feet.below())) {
                if (feet.getY() >= MIN_SAFE_BEDROCK_Y) {
                    this.targetY = feet.getY();
                    this.state = State.TUNNELING;
                    this.tunnelDir = this.primaryMiningDir;
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                BlockPos forwardAscendTarget = feet.relative(primaryMiningDir, 4).atY(Math.min(targetY, MIN_SAFE_BEDROCK_Y));
                return new PathingCommand(new baritone.api.pathing.goals.GoalBlock(forwardAscendTarget), PathingCommandType.SET_GOAL_AND_PATH);
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        tunnelAnchorPos = nextStepFeet;
        moveSafelyTowards(player, Vec3.atBottomCenterOf(nextStepFeet));
        boolean canAscendJump = player.onGround() && isPassable(level, feet.above(2)) && nextStepFeet.getY() > player.getY() + 0.35D;
        baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, canAscendJump);
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    private boolean isAscendingDirectionClear(Level level, BlockPos feet, Direction dir) {
        BlockPos headClearance = feet.above(2);
        BlockPos nextStepFeet  = feet.relative(dir).above(1);
        BlockPos nextStepHead  = feet.relative(dir).above(2);

        if (isBedrockOrUnbreakable(level, headClearance) || isBedrockOrUnbreakable(level, nextStepFeet) || isBedrockOrUnbreakable(level, nextStepHead)) {
            return false;
        }
        if (isHazardNear(level, headClearance) || isHazardNear(level, nextStepFeet) || isHazardNear(level, nextStepHead)) {
            return false;
        }
        return true;
    }

    private void rotateAscendingDirectionSafe(Level level, BlockPos feet) {
        Direction left = tunnelDir.getCounterClockWise();
        Direction right = tunnelDir.getClockWise();

        if (isAscendingDirectionClear(level, feet, right)) {
            tunnelDir = right;
        } else if (isAscendingDirectionClear(level, feet, left)) {
            tunnelDir = left;
        } else {
            // ZAKAZ obracania się o 180° i kopania tunelu wstecz
            tunnelDir = this.primaryMiningDir;
        }
    }

    private static long to2DKey(int x, int z) {
        return (((long) x) << 32) | (z & 0xFFFFFFFFL);
    }

    private void markTunnelDug(BlockPos pos) {
        if (pos != null) {
            this.dugColumns2D.add(to2DKey(pos.getX(), pos.getZ()));
            if (this.minedCorridorFeet.size() > 5000) {
                this.minedCorridorFeet.clear();
            }
            this.minedCorridorFeet.add(pos.asLong());
        }
    }

    /**
     * KROK 2 i 3: Kopanie prostej autostrady 1x2 wzdłuż Dominant Mining Vector (primaryMiningDir)
     * z omijaniem bedrocka w pionie (step up) i bezpiecznym objazdem bocznym (MIN_PARALLEL_SPACING = 3).
     */
    private PathingCommand tickTunneling(LocalPlayer player, Level level) {
        BlockPos feet = player.blockPosition();

        if (feet.getY() > targetY + 1 && feet.getY() > MIN_SAFE_BEDROCK_Y) {
            state = State.DESCENDING_STAIRCASE;
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (feet.getY() < targetY) {
            // Adaptacja poziomu jaskini: jeśli wpadliśmy do jaskini powyżej bedrocka (-54),
            // kontynuuj autostradę na nowym poziomie bez cofania się do starej dziury!
            if (feet.getY() >= MIN_SAFE_BEDROCK_Y) {
                this.targetY = feet.getY();
            } else {
                state = State.ASCENDING_STAIRCASE;
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
        }

        markTunnelDug(feet);

        // 1. Obsługa aktywnego objazdu bocznego (lateral detour)
        if (this.lateralDetourRemaining > 0 && this.lateralOffsetDir != null) {
            return tickLateralDetour(player, level, feet);
        }

        // Zawsze wracaj do głównego wektora autostrady (np. NORTH)
        this.tunnelDir = this.primaryMiningDir;

        if (this.turnCommitCooldown > 0) {
            this.turnCommitCooldown--;
        }

        // 2. Zabezpieczenie przed równoległym korytarzem w odległości < 3 bloków
        // Aktywne TYLKO po wykopaniu co najmniej 6 bloków prosto w nowym kierunku
        // (zapobiega błędnemu wykrywaniu korytarza za plecami zaraz po skręcie o 90 stopni)
        if (this.turnCommitCooldown <= 0 && this.consecutiveStraightBlocks >= 6 && isTooCloseToOldTunnel(feet, primaryMiningDir)) {
            return forceLateralCorrection(player, level, feet);
        }

        BlockPos frontFeet = feet.relative(primaryMiningDir);
        BlockPos frontHead = frontFeet.above();
        BlockPos ceilingAboveFront = frontHead.above();
        BlockPos floorBelowFront = frontFeet.below();

        // 3. PRIORYTET LAVA PLUGGING: Zatykanie lawy/wody budulcem wprost przed botem bez uciekania i obracania!
        BlockPos breachPos = findBreachInFront(level, feet, primaryMiningDir);
        if (breachPos == null) {
            // Sprawdź czy wokół frontFeet lub frontHead nie ma bezpośredniej lawy do zatkania
            breachPos = findAnyLavaHazardNear(level, frontHead);
            if (breachPos == null) {
                breachPos = findAnyLavaHazardNear(level, frontFeet);
            }
        }

        if (breachPos != null) {
            if (selectThrowawayBlock(player)) {
                this.hazardSourcePos = breachPos;
                this.state = State.SEALING_HAZARD;
                return handleSealingHazard(player, level);
            }
            return resolveObstacleDeadlock(player, level, feet);
        }

        // 4. Sprawdź przeszkody w osi głównej (Bedrock / Lawa / Bajorko / Przepaść)
        boolean feetBedrock = isBedrockOrUnbreakable(level, frontFeet);
        boolean headBedrock = isBedrockOrUnbreakable(level, frontHead);
        boolean frontHazard = isFrontDirectlyBlockedByHazard(level, feet, primaryMiningDir);

        if (frontHazard || feetBedrock || headBedrock) {
            // Garb bedrocka w bezpiecznym tunelu: próba step up Y+1
            if ((feetBedrock || headBedrock) && !frontHazard && canShiftVertical(level, feet, primaryMiningDir)) {
                return executeVerticalStep(player, level, feet, primaryMiningDir);
            }

            // W przypadku bajorka lawy, braku podłoża lub przeszkody: inteligentny skręt w lewo/prawo lub odwrót
            return handleLavaOrObstacleEncounter(player, level, feet);
        }

        // 6. Obsługa Gravelu / Sandu: jeśli nad frontHead wisi blok opadający, nie wbiegaj pod niego
        boolean fallingAbove = isFallingBlock(level.getBlockState(ceilingAboveFront));

        // 7. Kopanie korytarza 1x2 przed nami
        boolean headBlocked = !isPassable(level, frontHead);
        boolean feetBlocked = !isPassable(level, frontFeet);

        if (headBlocked || feetBlocked) {
            BlockPos blockToMine = headBlocked ? frontHead : frontFeet;

            double horizDist = horizontalDistanceToCenter(player.position(), frontFeet);
            if (horizDist > WALL_STOP_DISTANCE) {
                moveTowardsWithoutSprint(player, Vec3.atBottomCenterOf(frontFeet));
            } else {
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
            }

            return mineBlockSafely(player, level, blockToMine);
        }

        // 8. Zabezpieczenie przed bieganiem po starych tunelach
        if (isLookingDownExistingTunnel(level, feet, primaryMiningDir)) {
            BlockPos bridgeStep = frontFeet.below();
            if (!isSolidSupport(level, bridgeStep)) {
                return handleLavaOrObstacleEncounter(player, level, feet);
            }
        }

        // Korytarz czysty -> płynny krok naprzód
        tunnelAnchorPos = frontFeet;
        this.consecutiveStraightBlocks++;
        moveSafelyTowards(player, Vec3.atBottomCenterOf(frontFeet));
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    private boolean isFrontDirectlyBlockedByHazard(Level level, BlockPos feet, Direction forward) {
        BlockPos frontFeet = feet.relative(forward);
        BlockPos frontHead = frontFeet.above();
        BlockPos ceilingAboveFront = frontHead.above();
        BlockPos floorBelowFront = frontFeet.below();

        // 1. Bezpośrednia lawa w bloku stóp lub głowy
        if (level.getFluidState(frontFeet).is(FluidTags.LAVA) || level.getFluidState(frontHead).is(FluidTags.LAVA)) {
            return true;
        }

        // 2. Brak podłoża lub lawa w podłodze (przepaść / jezioro lawy)
        if (!isSolidSupport(level, floorBelowFront) || level.getFluidState(floorBelowFront).is(FluidTags.LAVA)) {
            return true;
        }

        // 3. Bezpośrednia lawa w suficie nad blokiem (po wykopaniu zalałaby gracza)
        if (level.getFluidState(ceilingAboveFront).is(FluidTags.LAVA)) {
            return true;
        }

        // 4. Bezpośrednia lawa w bloku za czołem wykopu (w osi marszu)
        BlockPos deepHead = frontHead.relative(forward);
        BlockPos deepFeet = frontFeet.relative(forward);
        if (level.getFluidState(deepHead).is(FluidTags.LAVA) || level.getFluidState(deepFeet).is(FluidTags.LAVA)) {
            return true;
        }

        // 5. Jeśli korytarz jest już otwarty (air), a obok stoi lawa (nie wchodź w otwartą jaskinię z jeziorem)
        if (isPassable(level, frontFeet) && isHazardNear(level, frontFeet)) {
            return true;
        }

        return false;
    }

    private BlockPos findBreachInFront(Level level, BlockPos feet, Direction forward) {
        BlockPos frontFeet = feet.relative(forward);
        BlockPos frontHead = frontFeet.above();

        FluidState headFluid = level.getFluidState(frontHead);
        if (!headFluid.isEmpty() && (headFluid.is(FluidTags.LAVA) || headFluid.isSource())) {
            return frontHead.immutable();
        }

        FluidState feetFluid = level.getFluidState(frontFeet);
        if (!feetFluid.isEmpty() && (feetFluid.is(FluidTags.LAVA) || feetFluid.isSource())) {
            return frontFeet.immutable();
        }

        BlockPos twoStepsFeet = frontFeet.relative(forward);
        BlockPos twoStepsHead = twoStepsFeet.above();

        FluidState head2Fluid = level.getFluidState(twoStepsHead);
        if (!head2Fluid.isEmpty() && (head2Fluid.is(FluidTags.LAVA) || head2Fluid.isSource())) {
            return twoStepsHead.immutable();
        }

        FluidState feet2Fluid = level.getFluidState(twoStepsFeet);
        if (!feet2Fluid.isEmpty() && (feet2Fluid.is(FluidTags.LAVA) || feet2Fluid.isSource())) {
            return twoStepsFeet.immutable();
        }

        return null;
    }

    /**
     * Sprawdza, czy w odległości 1..2 bloków prostopadle do wektora znajduje się już wykopany tunel.
     */
    private boolean isTooCloseToOldTunnel(BlockPos feet, Direction forward) {
        Direction right = forward.getClockWise();
        Direction left = forward.getCounterClockWise();

        for (int offset = 1; offset < MIN_PARALLEL_SPACING; offset++) {
            BlockPos checkRight = feet.relative(right, offset);
            BlockPos checkLeft = feet.relative(left, offset);

            if (dugColumns2D.contains(to2DKey(checkRight.getX(), checkRight.getZ()))
                    || dugColumns2D.contains(to2DKey(checkLeft.getX(), checkLeft.getZ()))) {
                return true;
            }
        }
        return false;
    }

    private PathingCommand forceLateralCorrection(LocalPlayer player, Level level, BlockPos feet) {
        Direction right = primaryMiningDir.getClockWise();
        Direction left = primaryMiningDir.getCounterClockWise();

        boolean rightBlocked = dugColumns2D.contains(to2DKey(feet.relative(right).getX(), feet.relative(right).getZ()));
        Direction escapeSide = rightBlocked ? left : right;

        this.lateralOffsetDir = escapeSide;
        this.lateralDetourRemaining = MIN_PARALLEL_SPACING;
        this.turnCommitCooldown = 40;
        this.consecutiveStraightBlocks = 0;
        return tickLateralDetour(player, level, feet);
    }

    private boolean canShiftVertical(Level level, BlockPos feet, Direction forward) {
        if (feet.getY() >= targetY) return false;

        BlockPos stepUpFeet = feet.above().relative(forward);
        BlockPos stepUpHead = stepUpFeet.above();
        BlockPos ceilingAboveHead = feet.above(2);

        if (isBedrockOrUnbreakable(level, ceilingAboveHead)) return false;
        if (isBedrockOrUnbreakable(level, stepUpFeet) || isBedrockOrUnbreakable(level, stepUpHead)) return false;
        if (isHazardNear(level, stepUpFeet) || isHazardNear(level, stepUpHead)) return false;

        return true;
    }

    private PathingCommand executeVerticalStep(LocalPlayer player, Level level, BlockPos feet, Direction forward) {
        BlockPos aboveHead = feet.above(2);
        if (!isPassable(level, aboveHead)) {
            return mineBlockSafely(player, level, aboveHead);
        }

        BlockPos stepUpFeet = feet.above().relative(forward);
        BlockPos stepUpHead = stepUpFeet.above();

        if (!isPassable(level, stepUpHead)) {
            return mineBlockSafely(player, level, stepUpHead);
        }
        if (!isPassable(level, stepUpFeet)) {
            return mineBlockSafely(player, level, stepUpFeet);
        }

        tunnelAnchorPos = stepUpFeet;
        moveSafelyTowards(player, Vec3.atBottomCenterOf(stepUpFeet));
        boolean canStepJump = player.onGround() && isPassable(level, feet.above(2));
        baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, canStepJump);
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    private PathingCommand initiateLateralDetour(LocalPlayer player, Level level, BlockPos feet) {
        return handleLavaOrObstacleEncounter(player, level, feet);
    }

    private PathingCommand tickLateralDetour(LocalPlayer player, Level level, BlockPos feet) {
        if (lateralOffsetDir == null) {
            this.lateralDetourRemaining = 0;
            return handleLavaOrObstacleEncounter(player, level, feet);
        }

        BlockPos targetFeet = feet.relative(lateralOffsetDir);
        BlockPos targetHead = targetFeet.above();

        if (isBedrockOrUnbreakable(level, targetFeet) || isBedrockOrUnbreakable(level, targetHead)
                || isHazardNear(level, targetFeet) || isHazardNear(level, targetHead)) {
            this.lateralDetourRemaining = 0;
            this.lateralOffsetDir = null;
            return handleLavaOrObstacleEncounter(player, level, feet);
        }

        if (!isPassable(level, targetHead)) {
            return mineBlockSafely(player, level, targetHead);
        }
        if (!isPassable(level, targetFeet)) {
            return mineBlockSafely(player, level, targetFeet);
        }

        markTunnelDug(targetFeet);
        moveSafelyTowards(player, Vec3.atBottomCenterOf(targetFeet));
        this.lateralDetourRemaining--;

        if (this.lateralDetourRemaining <= 0) {
            this.lateralDetourRemaining = 0;
            this.lateralOffsetDir = null;
            this.tunnelDir = this.primaryMiningDir;
        }

        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private PathingCommand resolveObstacleDeadlock(LocalPlayer player, Level level, BlockPos feet) {
        return handleLavaOrObstacleEncounter(player, level, feet);
    }

    private PathingCommand handleLavaOrObstacleEncounter(LocalPlayer player, Level level, BlockPos feet) {
        // Natychmiastowe zatrzymanie wszystkich wektorów ruchu i kopania
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);

        Direction currentDir = primaryMiningDir != null ? primaryMiningDir : Direction.SOUTH;
        this.blockedForwardDir = currentDir;

        Direction leftDir = currentDir.getCounterClockWise();
        Direction rightDir = currentDir.getClockWise();

        boolean leftSafe = isSafeBranchDirection(level, feet, leftDir);
        boolean rightSafe = isSafeBranchDirection(level, feet, rightDir);

        logDirect("§e[Bypass] Wykryto bajorko lawy / przeszkodę! Bezpieczeństwo ścian: LEWO=" 
                + (leftSafe ? "§aWOLNE" : "§cLAWA/BLOK") + "§e, PRAWO=" 
                + (rightSafe ? "§aWOLNE" : "§cLAWA/BLOK"));

        // 1. Jeśli tylko lewo jest bezpieczne (prawo ma lawę) -> skręć w lewo
        if (leftSafe && !rightSafe) {
            logDirect("§c[Bypass] §ePo prawej stronie lawa! Skręcam natychmiast w §aLEWO§e!");
            return turnAndMineNewDirection(player, level, feet, leftDir);
        }

        // 2. Jeśli tylko prawo jest bezpieczne (lewo ma lawę) -> skręć w prawo
        if (rightSafe && !leftSafe) {
            logDirect("§c[Bypass] §ePo lewej stronie lawa! Skręcam natychmiast w §aPRAWO§e!");
            return turnAndMineNewDirection(player, level, feet, rightDir);
        }

        // 3. Jeśli obie strony są bezpieczne -> wybierz lepszą (np. lita skała)
        if (leftSafe && rightSafe) {
            Direction chosen = pickBestBranchDirection(level, feet, leftDir, rightDir);
            logDirect("§a[Bypass] Obie strony bezpieczne od lawy! Skręcam w §f" + chosen.name() + "§a!");
            return turnAndMineNewDirection(player, level, feet, chosen);
        }

        // 4. Jeśli obie strony mają lawę (lawa z przodu, z lewej i z prawej) -> COFNIJ SIĘ DO TYŁU!
        logDirect("§c[Bypass] §4Lawa ze wszystkich stron! Wycofuję się do tyłu bezpiecznym tunelem...");
        return executeRetreatBackward(player, level, feet, currentDir);
    }

    private PathingCommand turnAndMineNewDirection(LocalPlayer player, Level level, BlockPos feet, Direction newDir) {
        this.primaryMiningDir = newDir;
        this.tunnelDir = newDir;
        this.blockedForwardDir = null;
        this.lateralDetourRemaining = 0;
        this.lateralOffsetDir = null;
        this.turnCommitCooldown = 40;
        this.consecutiveStraightBlocks = 0;

        BlockPos targetFeet = feet.relative(newDir);
        BlockPos targetHead = targetFeet.above();

        // 1. Płynny obrót kamery na nową ścianę z GCD
        float targetYaw = newDir.toYRot();
        Rotation gcdRot = applyGrimGcd(player.getYRot(), player.getXRot(), targetYaw, 0.0F);
        baritone.getLookBehavior().updateTarget(gcdRot, true);

        // Kategoryczny zakaz chodzenia podczas obrotu kamery:
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, false);

        float yawDiff = Math.abs(Mth.wrapDegrees(player.getYRot() - targetYaw));
        if (yawDiff > 12.0f) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // 2. Wykopanie nowej ściany (najpierw głowa, potem stopy)
        if (!isPassable(level, targetHead)) {
            return mineBlockSafely(player, level, targetHead);
        }
        if (!isPassable(level, targetFeet)) {
            return mineBlockSafely(player, level, targetFeet);
        }

        // 3. Ściana przekopana -> płynny krok naprzód w nową odnogę
        markTunnelDug(targetFeet);
        moveSafelyTowards(player, Vec3.atBottomCenterOf(targetFeet));
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    private PathingCommand executeRetreatBackward(LocalPlayer player, Level level, BlockPos feet, Direction forwardDir) {
        Direction backDir = forwardDir.getOpposite();
        BlockPos backFeet = feet.relative(backDir);
        BlockPos backHead = backFeet.above();

        this.consecutiveStraightBlocks = 0;

        // Jeśli coś zablokowało korytarz za nami (np. gravel):
        if (!isPassable(level, backHead)) return mineBlockSafely(player, level, backHead);
        if (!isPassable(level, backFeet)) return mineBlockSafely(player, level, backFeet);

        float targetYaw = backDir.toYRot();
        Rotation gcdRot = applyGrimGcd(player.getYRot(), player.getXRot(), targetYaw, 0.0F);
        baritone.getLookBehavior().updateTarget(gcdRot, false);

        // Krok w tył do poprzedniego bloku
        moveSafelyTowards(player, Vec3.atBottomCenterOf(backFeet));
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private boolean isSafeBranchDirection(Level level, BlockPos origin, Direction dir) {
        BlockPos sideFeet = origin.relative(dir);
        BlockPos sideHead = sideFeet.above();
        BlockPos sideFloor = sideFeet.below();
        BlockPos sideCeiling = sideHead.above();

        // 1. Nie wolno kopać w bedrock ani bloki niezniszczalne
        if (isBedrockOrUnbreakable(level, sideFeet) || isBedrockOrUnbreakable(level, sideHead)) {
            return false;
        }

        // 2. Podłoże pod nowym korytarzem musi być stabilne (nie wolno wejść w pustkę/lawę)
        if (!isSolidSupport(level, sideFloor) || level.getFluidState(sideFloor).is(FluidTags.LAVA)) {
            return false;
        }

        // 3. Sama nowa ściana nie może być ciekłą lawą
        if (level.getFluidState(sideFeet).is(FluidTags.LAVA) || level.getFluidState(sideHead).is(FluidTags.LAVA)) {
            return false;
        }

        // 4. Sufit nad nową odnogą nie może być ciekłą lawą
        if (level.getFluidState(sideCeiling).is(FluidTags.LAVA)) {
            return false;
        }

        // 5. Sprawdź 1 blok w głąb w osi nowej odnogi
        BlockPos deepHead = sideHead.relative(dir);
        BlockPos deepFeet = sideFeet.relative(dir);
        if (level.getFluidState(deepHead).is(FluidTags.LAVA) || level.getFluidState(deepFeet).is(FluidTags.LAVA)) {
            return false;
        }

        // 6. Sprawdź czy korytarz nie nakłada się na starą wykopaną trasę
        if (dugColumns2D.contains(to2DKey(sideFeet.getX(), sideFeet.getZ()))) {
            return false;
        }

        return true;
    }

    private Direction pickBestBranchDirection(Level level, BlockPos feet, Direction left, Direction right) {
        BlockPos leftFeet = feet.relative(left);
        BlockPos rightFeet = feet.relative(right);

        boolean leftSolid = !isPassable(level, leftFeet) && !isBedrockOrUnbreakable(level, leftFeet);
        boolean rightSolid = !isPassable(level, rightFeet) && !isBedrockOrUnbreakable(level, rightFeet);

        // Preferuj lity kamień (bezpieczne kopanie w ścianę) zamiast otwartej jaskini
        if (leftSolid && !rightSolid) return left;
        if (rightSolid && !leftSolid) return right;

        return right;
    }

    private boolean isDetourRouteSafe(Level level, BlockPos origin, Direction dir) {
        return isSafeBranchDirection(level, origin, dir);
    }

    private record MiningAction(BlockPos targetPos, Rotation snapRotation) {}

    /**
     * KROK 4 i 5: Wykrywanie rud, ścisły reach <= 3.5 bloka (GrimSnap) oraz podejście przez Baritone jak w #mine.
     */
    private PathingCommand tickMiningOre(LocalPlayer player, Level level, boolean calcFailed, boolean isSafeToCancel) {
        while (activeOreTarget == null || !isTargetOre(level.getBlockState(activeOreTarget)) || blacklistedOres.contains(activeOreTarget)) {
            this.invalidStandingPositions.clear();
            this.oreStagnationTicks = 0;
            this.oreAttemptTicks = 0;
            if (veinQueue.isEmpty()) {
                BlockPos nextInRadius = findNearestOre(player, level);
                if (nextInRadius != null) {
                    activeOreTarget = nextInRadius;
                    enqueueConnectedVein(level, nextInRadius);
                } else {
                    activeOreTarget = null;
                    this.currentBreakingBlock = null;
                    baritone.getInputOverrideHandler().clearAllKeys();
                    state = State.COLLECTING_DROP;
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
            } else {
                activeOreTarget = veinQueue.pollFirst();
            }
        }

        enqueueConnectedVein(level, activeOreTarget);

        double effectiveReach = getEffectiveReach();

        // 0. Aktywny lock kopania: jeśli już niszczymy blok (np. zasłaniający lub rudę) i nadal istnieje -> kontynuuj niszczenie bez przerywania!
        if (this.currentBreakingBlock != null) {
            BlockState curState = level.getBlockState(this.currentBreakingBlock);
            if (!curState.isAir() && !isBedrockOrUnbreakable(level, this.currentBreakingBlock)) {
                Optional<Rotation> curSnap = findVerifiedGrimSnapRotation(player, level, this.currentBreakingBlock, effectiveReach);
                if (curSnap.isEmpty()) {
                    curSnap = RotationUtils.reachable(ctx, this.currentBreakingBlock, effectiveReach);
                }
                if (curSnap.isPresent()) {
                    equipBestTool(player, curState);
                    baritone.getLookBehavior().updateTarget(curSnap.get(), true);
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                    ContinuousBreakController.notifyBreaking(ctx, new BetterBlockPos(this.currentBreakingBlock));
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            } else {
                this.currentBreakingBlock = null;
            }
        }

        // 1. Sprawdź czy ruda (lub blok bezpośrednio ją zasłaniający) ma czysty raycast GrimSnap
        Optional<MiningAction> directAction = findMiningAction(player, level, activeOreTarget, effectiveReach);
        if (directAction.isPresent()) {
            this.invalidStandingPositions.clear();
            this.oreStagnationTicks = 0;
            this.oreAttemptTicks = 0;
            MiningAction action = directAction.get();
            this.currentBreakingBlock = action.targetPos();
            equipBestTool(player, level.getBlockState(action.targetPos()));
            baritone.getLookBehavior().updateTarget(action.snapRotation(), true);
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
            ContinuousBreakController.notifyBreaking(ctx, new BetterBlockPos(action.targetPos()));
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // Zwiększ licznik prób dotarcia do tej rudy
        if (++this.oreAttemptTicks > 40) {
            // Po 40 tickach (~2s) bez możliwości wykopania lub podejścia: pomijamy tę rudę, aby nie blokować bota
            blacklistedOres.add(activeOreTarget);
            activeOreTarget = null;
            this.oreAttemptTicks = 0;
            this.invalidStandingPositions.clear();
            baritone.getInputOverrideHandler().clearAllKeys();
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        // 2. Brak bezpośredniego strzału z obecnej pozycji:
        // Sprawdź czy bot może bezpiecznie wykonać krok w stronę rudy (jeśli stoi na stabilnym podłożu i ruda jest blisko < 4.2m)
        Vec3 eyePos = player.getEyePosition(1.0F);
        double distSqToOre = eyePos.distanceToSqr(Vec3.atCenterOf(activeOreTarget));
        if (distSqToOre <= 4.2D * 4.2D && !isHazardNear(level, activeOreTarget)) {
            double dx = activeOreTarget.getX() + 0.5D - player.getX();
            double dz = activeOreTarget.getZ() + 0.5D - player.getZ();
            if (Math.abs(dx) > 0.3D || Math.abs(dz) > 0.3D) {
                Direction stepDir = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
                BlockPos stepCheck = player.blockPosition().relative(stepDir);
                if (isSolidSupport(level, stepCheck.below()) && isPassable(level, stepCheck) && isPassable(level, stepCheck.above())) {
                    baritone.getInputOverrideHandler().clearAllKeys();
                    moveTowardsWithoutSprint(player, Vec3.atBottomCenterOf(activeOreTarget));
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            }
        }

        // 3. Jeśli A* już idzie w stronę rudy lub oblicza trasę -> pozwól mu działać, NIE anuluj co tick!
        if (baritone.getPathingBehavior().isPathing()) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (baritone.getPathingBehavior().getInProgress().isPresent()) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        this.currentBreakingBlock = null;

        // 4. Jeśli A* nie potrafi wyznaczyć ścieżki do rudy -> czarna lista i przejście dalej
        if (calcFailed) {
            blacklistedOres.add(activeOreTarget);
            activeOreTarget = null;
            this.invalidStandingPositions.clear();
            this.oreStagnationTicks = 0;
            this.oreAttemptTicks = 0;
            baritone.getInputOverrideHandler().clearAllKeys();
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        // 5. Podejście przez Baritone ze ścisłym limitem reach <= 2.65m i wykluczeniem martwych pozycji
        Goal goal = new GoalStrictOreApproach(activeOreTarget, this.invalidStandingPositions);
        return new PathingCommand(goal, PathingCommandType.SET_GOAL_AND_PATH);
    }

    private Optional<MiningAction> findMiningAction(LocalPlayer player, Level level, BlockPos orePos, double reach) {
        if (isBedrockOrUnbreakable(level, orePos)) {
            blacklistedOres.add(orePos);
            return Optional.empty();
        }

        // 1. Bezpośredni strzał w rudę <= reach (najpierw precyzyjny multipoint raycast GrimSnap)
        Optional<Rotation> multiSnap = findVerifiedGrimSnapRotation(player, level, orePos, reach);
        if (multiSnap.isPresent()) {
            if (hasDirectLavaAboveOrBehind(level, orePos) && player.blockPosition().distManhattan(orePos) <= 1) {
                blacklistedOres.add(orePos);
                return Optional.empty();
            }
            return Optional.of(new MiningAction(orePos, multiSnap.get()));
        }

        // Fallback: standardowy Baritone RotationUtils.reachable z Grim GCD
        Optional<Rotation> directOreSnap = RotationUtils.reachable(ctx, orePos, reach);
        if (directOreSnap.isPresent()) {
            if (hasDirectLavaAboveOrBehind(level, orePos) && player.blockPosition().distManhattan(orePos) <= 1) {
                blacklistedOres.add(orePos);
                return Optional.empty();
            }
            Rotation gcdRot = applyGrimGcd(player.getYRot(), player.getXRot(), directOreSnap.get().getYaw(), directOreSnap.get().getPitch());
            return Optional.of(new MiningAction(orePos, gcdRot));
        }

        // 2. Jeśli ruda jest zasłonięta przez pojedynczy blok w ścianie lub podłodze na dystansie <= (reach + 0.7m) -> wykop blok przeszkadzający
        Vec3 eyePos = player.getEyePosition(1.0F);
        if (eyePos.distanceToSqr(Vec3.atCenterOf(orePos)) <= (reach + 0.7D) * (reach + 0.7D)) {
            BlockPos obstructing = findObstructingBlockInReach(player, level, orePos, reach);
            if (obstructing != null && !isHazardNear(level, obstructing) && !isBedrockOrUnbreakable(level, obstructing)) {
                // Nie kop podłogi pod własnymi stopami, jeśli pod nią nie ma podpory
                if (obstructing.equals(player.blockPosition().below()) && !isSolidSupport(level, obstructing.below())) {
                    return Optional.empty();
                }
                Optional<Rotation> obstructSnap = findVerifiedGrimSnapRotation(player, level, obstructing, reach);
                if (obstructSnap.isEmpty()) {
                    obstructSnap = RotationUtils.reachable(ctx, obstructing, reach);
                }
                if (obstructSnap.isPresent()) {
                    Rotation gcdRot = applyGrimGcd(player.getYRot(), player.getXRot(), obstructSnap.get().getYaw(), obstructSnap.get().getPitch());
                    return Optional.of(new MiningAction(obstructing, gcdRot));
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Powrót na oś głównego tunelu Y=-50 po wykopaniu całej żyły i zebraniu dropu.
     */
    private PathingCommand tickReturningToTunnel(LocalPlayer player, Level level, boolean calcFailed) {
        if (tunnelAnchorPos == null || calcFailed || player.blockPosition().distManhattan(tunnelAnchorPos) <= 2 || ++returnStuckTicks > 40) {
            tunnelAnchorPos = null;
            returnStuckTicks = 0;
            this.tunnelDir = this.primaryMiningDir;
            state = (player.blockPosition().getY() - targetY > 1) ? State.DESCENDING_STAIRCASE : State.TUNNELING;
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (baritone.getPathingBehavior().isPathing()) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        return new PathingCommand(new GoalTwoBlocks(tunnelAnchorPos), PathingCommandType.SET_GOAL_AND_PATH);
    }

    // =========================================================================
    // GRIMAC SNAP ROTATION (GCD QUANTIZED + MULTIPOINT VOXEL RAYCAST <= 3.5M)
    // =========================================================================

    private PathingCommand mineBlockSafely(LocalPlayer player, Level level, BlockPos target) {
        if (target == null || isBedrockOrUnbreakable(level, target) || level.getBlockState(target).isAir() || !level.getFluidState(target).isEmpty()) {
            this.currentBreakingBlock = null;
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // KATEGORYCZNE ZATRZYMANIE RUCHU PODCZAS KOPANIA (Zero Simulation Flags):
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, false);

        this.currentBreakingBlock = target;
        double reach = getEffectiveReach();

        Optional<Rotation> snap = findVerifiedGrimSnapRotation(player, level, target, reach);
        if (snap.isEmpty()) {
            Optional<Rotation> fallbackSnap = RotationUtils.reachable(ctx, target, reach);
            if (fallbackSnap.isPresent()) {
                Rotation gcdRot = applyGrimGcd(
                    player.getYRot(),
                    player.getXRot(),
                    fallbackSnap.get().getYaw(),
                    fallbackSnap.get().getPitch()
                );
                snap = Optional.of(gcdRot);
            }
        }

        if (snap.isPresent()) {
            equipBestTool(player, level.getBlockState(target));
            baritone.getLookBehavior().updateTarget(snap.get(), true);

            float targetYaw = snap.get().getYaw();
            float targetPitch = snap.get().getPitch();
            float yawDelta = Math.abs(Mth.wrapDegrees(player.getYRot() - targetYaw));
            float pitchDelta = Math.abs(player.getXRot() - targetPitch);

            // Sprawdź, czy celownik trafia w docelowy blok
            Vec3 eyePos = player.getEyePosition(1.0F);
            Vec3 lookVec = RotationUtils.calcLookDirectionFromRotation(ctx.playerRotations());
            BlockHitResult currentHit = level.clip(new ClipContext(
                eyePos,
                eyePos.add(lookVec.scale(reach)),
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player
            ));

            boolean hitValid = currentHit.getType() == HitResult.Type.BLOCK && currentHit.getBlockPos().equals(target);
            if (hitValid && yawDelta <= 10.0f && pitchDelta <= 10.0f) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
            } else {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
        moveTowardsWithoutSprint(player, Vec3.atBottomCenterOf(target));
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    public static Optional<Rotation> findVerifiedGrimSnapRotation(LocalPlayer player, Level level, BlockPos pos, double maxReach) {
        Vec3 eyePos = player.getEyePosition(1.0F);
        if (eyePos.distanceToSqr(Vec3.atCenterOf(pos)) > (maxReach + 1.0D) * (maxReach + 1.0D)) {
            return Optional.empty();
        }

        BlockState state = level.getBlockState(pos);
        VoxelShape shape = state.getShape(level, pos);
        AABB box = shape.isEmpty() ? new AABB(0, 0, 0, 1, 1, 1) : shape.bounds();

        double[] offsets = {0.5D, 0.3D, 0.7D, 0.2D, 0.8D};
        float currentYaw = player.getYRot();
        float currentPitch = player.getXRot();

        for (double dy : offsets) {
            for (double dx : offsets) {
                for (double dz : offsets) {
                    double px = pos.getX() + Mth.lerp(dx, box.minX, box.maxX);
                    double py = pos.getY() + Mth.lerp(dy, box.minY, box.maxY);
                    double pz = pos.getZ() + Mth.lerp(dz, box.minZ, box.maxZ);

                    Vec3 targetVec = new Vec3(px, py, pz);
                    if (eyePos.distanceToSqr(targetVec) > maxReach * maxReach) {
                        continue;
                    }

                    Rotation raw = calcRotationFromVec3d(eyePos, targetVec, currentYaw);
                    Rotation gcdRot = applyGrimGcd(currentYaw, currentPitch, raw.getYaw(), raw.getPitch());

                    if (verifyRaytraceHit(player, level, pos, gcdRot, maxReach)) {
                        return Optional.of(gcdRot);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static boolean verifyRaytraceHit(LocalPlayer player, Level level, BlockPos expectedPos, Rotation rot, double maxReach) {
        Vec3 eyePos = player.getEyePosition(1.0F);
        Vec3 lookVec = Vec3.directionFromRotation(rot.getPitch(), rot.getYaw());
        Vec3 rayEnd = eyePos.add(lookVec.scale(maxReach));

        BlockHitResult hit = level.clip(new ClipContext(
                eyePos,
                rayEnd,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player
        ));

        return hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(expectedPos)
                && eyePos.distanceToSqr(hit.getLocation()) <= maxReach * maxReach;
    }

    private static Rotation calcRotationFromVec3d(Vec3 orig, Vec3 dest, float currentYaw) {
        double dx = dest.x - orig.x;
        double dy = dest.y - orig.y;
        double dz = dest.z - orig.z;
        double distSq = dx * dx + dz * dz;
        double dist = Math.sqrt(distSq);

        float yaw = (float) (Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        float pitch = (float) (-(Mth.atan2(dy, dist) * (180.0D / Math.PI)));

        float wrappedYaw = currentYaw + Mth.wrapDegrees(yaw - currentYaw);
        return new Rotation(wrappedYaw, Mth.clamp(pitch, -90.0F, 90.0F));
    }

    private static Rotation applyGrimGcd(float prevYaw, float prevPitch, float targetYaw, float targetPitch) {
        Minecraft mc = Minecraft.getInstance();
        double sensitivity = 0.5D;
        if (mc.options != null && mc.options.sensitivity() != null) {
            sensitivity = mc.options.sensitivity().get();
        }
        double f = sensitivity * 0.6D + 0.2D;
        double gcd = f * f * f * 1.2D;

        float deltaYaw = Mth.wrapDegrees(targetYaw - prevYaw);
        float deltaPitch = targetPitch - prevPitch;

        float fixedDeltaYaw = (float) (Math.round(deltaYaw / gcd) * gcd);
        float fixedDeltaPitch = (float) (Math.round(deltaPitch / gcd) * gcd);

        float finalYaw = prevYaw + fixedDeltaYaw;
        float finalPitch = Mth.clamp(prevPitch + fixedDeltaPitch, -90.0F, 90.0F);
        return new Rotation(finalYaw, finalPitch);
    }

    private static BlockPos findObstructingBlockInReach(LocalPlayer player, Level level, BlockPos orePos, double maxReach) {
        Vec3 eyePos = player.getEyePosition(1.0F);
        Vec3 oreCenter = Vec3.atCenterOf(orePos);
        Vec3 dir = oreCenter.subtract(eyePos).normalize();
        Vec3 end = eyePos.add(dir.scale(maxReach));

        BlockHitResult hit = level.clip(new ClipContext(
                eyePos,
                end,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player
        ));

        if (hit.getType() == HitResult.Type.BLOCK && !hit.getBlockPos().equals(orePos)) {
            BlockPos hitPos = hit.getBlockPos();
            if (level.getBlockState(hitPos).getDestroySpeed(level, hitPos) >= 0) {
                return hitPos;
            }
        }
        return null;
    }

    // =========================================================================
    // SKANOWANIE RUD (3 BLOKI W KAŻDĄ STRONĘ - KOSTKA 7x7x7) I VEIN MINING
    // =========================================================================

    private BlockPos findNearestOre(LocalPlayer player, Level level) {
        BlockPos origin = player.blockPosition();
        Vec3 eye = player.getEyePosition(1.0F);
        BlockPos bestPos = null;
        double bestScore = Double.MAX_VALUE;

        for (int dx = -ORE_SCAN_RADIUS; dx <= ORE_SCAN_RADIUS; dx++) {
            for (int dy = -ORE_SCAN_RADIUS; dy <= ORE_SCAN_RADIUS; dy++) {
                for (int dz = -ORE_SCAN_RADIUS; dz <= ORE_SCAN_RADIUS; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (blacklistedOres.contains(pos)) continue;

                    BlockState state = level.getBlockState(pos);
                    if (isTargetOre(state)) {
                        double distSq = eye.distanceToSqr(Vec3.atCenterOf(pos));
                        boolean isDiamond = state.is(Blocks.DIAMOND_ORE) || state.is(Blocks.DEEPSLATE_DIAMOND_ORE);
                        double score = isDiamond ? distSq * 0.5D : distSq;
                        if (score < bestScore) {
                            bestScore = score;
                            bestPos = pos.immutable();
                        }
                    }
                }
            }
        }
        return bestPos;
    }

    private void enqueueConnectedVein(Level level, BlockPos center) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos neighbor = center.offset(dx, dy, dz);
                    if (!blacklistedOres.contains(neighbor)
                            && !veinQueue.contains(neighbor)
                            && !neighbor.equals(activeOreTarget)
                            && isTargetOre(level.getBlockState(neighbor))) {
                        veinQueue.addLast(neighbor.immutable());
                    }
                }
            }
        }
    }

    private boolean isTargetOre(BlockState state) {
        if (state == null || state.isAir()) return false;
        Block block = state.getBlock();
        if (targetOresList.isEmpty()) {
            return block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE;
        }
        for (String ore : targetOresList) {
            String norm = normalizeOreKey(ore);
            Set<Block> allowed = ORE_BLOCK_MAP.get(norm);
            if (allowed != null && allowed.contains(block)) {
                return true;
            }
        }
        return false;
    }

    private ItemEntity findNearbyValuableDrop(LocalPlayer player, Level level) {
        AABB searchBox = player.getBoundingBox().inflate(6.0D, 3.5D, 6.0D);
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, searchBox, entity -> {
            if (!entity.isAlive() || entity.isInLava() || ignoredDropIds.containsKey(entity.getId())) return false;
            Item item = entity.getItem().getItem();
            if (targetOresList.isEmpty()) {
                return item == Items.DIAMOND || item == Items.DIAMOND_ORE || item == Items.DEEPSLATE_DIAMOND_ORE;
            }
            for (String ore : targetOresList) {
                String norm = normalizeOreKey(ore);
                Set<Item> allowed = ORE_ITEM_MAP.get(norm);
                if (allowed != null && allowed.contains(item)) {
                    return true;
                }
            }
            return false;
        });

        return items.stream()
                .filter(item -> !DropCollectionHelper.isDropUnsafe(level, item))
                .min(Comparator.comparingDouble(player::distanceToSqr))
                .orElse(null);
    }

    /**
     * Aktualizuje rotację tylko wtedy, gdy różnica kątów przekracza próg czułości sieciowej.
     * Zapobiega wysyłaniu zduplikowanych pakietów PosRot/Rot (GrimAC AimDuplicateLook).
     */
    private void updateRotationGrimSafe(LocalPlayer player, Rotation targetRot, boolean blockInteract) {
        if (player == null || targetRot == null) return;
        float yawDiff = Math.abs(Mth.wrapDegrees(targetRot.getYaw() - player.getYRot()));
        float pitchDiff = Math.abs(targetRot.getPitch() - player.getXRot());

        // Ignoruj mikro-zmiany kątów (< 0.20 stopnia), które po kwantyzacji dają duplikat pakietu rotacji (AimDuplicateLook)
        if (yawDiff < 0.20f && pitchDiff < 0.20f) {
            return;
        }
        baritone.getLookBehavior().updateTarget(targetRot, blockInteract);
    }

    /**
     * Płynne podchodzenie w stronę dropu — zmienia tylko Yaw przy realnym odchyleniu,
     * nie wymusza Pitch w dół na nieruchomy item.
     */
    private void moveSafelyTowardsDrop(LocalPlayer player, Vec3 targetPos) {
        if (player == null || targetPos == null) return;
        Rotation fullRot = calcRotationFromVec3d(player.getEyePosition(1.0F), targetPos, player.getYRot());
        float yawDiff = Math.abs(Mth.wrapDegrees(fullRot.getYaw() - player.getYRot()));

        if (yawDiff > 3.0f) {
            Rotation walkRot = applyGrimGcd(player.getYRot(), player.getXRot(), fullRot.getYaw(), player.getXRot());
            updateRotationGrimSafe(player, walkRot, false);
        }

        baritone.getInputOverrideHandler().clearAllKeys();
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
    }

    /**
     * Inteligentne zbieranie leżącego dropu surowców:
     * 1. Wolna droga -> bezpieczny marsz.
     * 2. Blok zasłaniający (np. we wnęce) -> rozkopanie przeszkody.
     * 3. Trudny teren -> A* GoalBlock z REVALIDATE zamiast spamu CANCEL.
     */
    private PathingCommand handleCollectingDrop(LocalPlayer player, Level level, ItemEntity drop) {
        long now = System.currentTimeMillis();
        ignoredDropIds.entrySet().removeIf(entry -> now > entry.getValue());

        if (drop == null || !drop.isAlive() || ignoredDropIds.containsKey(drop.getId()) || DropCollectionHelper.isDropUnsafe(level, drop)) {
            if (drop != null) {
                ignoredDropIds.put(drop.getId(), now + 30_000L);
            }
            resetDropCollectionState();
            return null;
        }

        double distSq = player.distanceToSqr(drop);

        if (drop.getId() != currentDropId) {
            currentDropId = drop.getId();
            dropCollectTicks = 0;
            dropStuckTicks = 0;
            lastDistToDropSq = distSq;
            lastDropGoal = null;
        }

        dropCollectTicks++;

        if (distSq < lastDistToDropSq - 0.03) {
            lastDistToDropSq = distSq;
            dropStuckTicks = 0;
        } else {
            dropStuckTicks++;
        }

        // Timeout bezpieczeństwa: jeśli po 100 tickach (5s) nie udało się zebrać dropu,
        // wpisz go na 15s na czarną listę, aby bot nie utknął na stałe
        if (dropCollectTicks > 100) {
            ignoredDropIds.put(drop.getId(), now + 15_000L);
            resetDropCollectionState();
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        // PRZYPADEK 1: Droga do dropu jest krótka i całkowicie wolna od kolizji
        if (distSq <= 6.25 && dropStuckTicks < 15 && DropCollectionHelper.canWalkDirectlyToDrop(player, level, drop)) {
            this.currentBreakingBlock = null;
            moveSafelyTowardsDrop(player, drop.position());
            lastDropGoal = null;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // PRZYPADEK 2: Drop jest blisko (<= 4.2m), ale zasłania go blok (np. deepslate we wnęce) — rozkop blok!
        BlockPos obstruction = DropCollectionHelper.findObstructingBlock(player, level, drop);
        if (obstruction != null && !isBedrockOrUnbreakable(level, obstruction)) {
            baritone.getInputOverrideHandler().clearAllKeys();

            Vec3 hitVec = Vec3.atCenterOf(obstruction);
            Rotation breakRot = calcRotationFromVec3d(player.getEyePosition(1.0F), hitVec, player.getYRot());
            Rotation gcdBreakRot = applyGrimGcd(player.getYRot(), player.getXRot(), breakRot.getYaw(), breakRot.getPitch());
            updateRotationGrimSafe(player, gcdBreakRot, true);

            this.currentBreakingBlock = obstruction;
            equipBestTool(player, level.getBlockState(obstruction));
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
            ContinuousBreakController.notifyBreaking(ctx, new BetterBlockPos(obstruction));

            dropStuckTicks = 0;
            lastDropGoal = null;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // PRZYPADEK 3: Drop wymaga podejścia lub rozkopania ścieżki przez A* Baritone
        this.currentBreakingBlock = null;
        baritone.getInputOverrideHandler().clearAllKeys();

        if (lastDropGoal == null || !lastDropGoal.isInGoal(drop.blockPosition())) {
            lastDropGoal = new GoalItemDrop(drop);
        }

        return new PathingCommand(lastDropGoal, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    private void resetDropCollectionState() {
        currentDropId = -1;
        dropCollectTicks = 0;
        dropStuckTicks = 0;
        lastDistToDropSq = Double.MAX_VALUE;
        lastDropGoal = null;
        this.currentBreakingBlock = null;
        baritone.getInputOverrideHandler().clearAllKeys();
    }

    // =========================================================================
    // DETEKCJA ZAGROŻEŃ (LAWA, WODA, JASKINIE, ŻWIR)
    // =========================================================================

    private boolean isHazardNear(Level level, BlockPos pos) {
        // 1. Lawa w bloku lub sąsiadach to bezwzględne zagrożenie
        if (level.getFluidState(pos).is(FluidTags.LAVA)) return true;
        for (Direction dir : Direction.values()) {
            BlockPos adj = pos.relative(dir);
            if (level.getFluidState(adj).is(FluidTags.LAVA)) return true;
        }

        // 2. Źródło wody bezpośrednio w bloku lub nad nim to zagrożenie (nie kopiemy w źródło oceanu/podziemnego jeziora)
        FluidState selfFluid = level.getFluidState(pos);
        if (selfFluid.is(FluidTags.WATER) && selfFluid.isSource()) return true;
        FluidState aboveFluid = level.getFluidState(pos.above());
        if (aboveFluid.is(FluidTags.WATER) && aboveFluid.isSource()) return true;

        return false;
    }

    // =========================================================================
    // WATER SEALER (USZCZELNIANIE WYCIEKÓW WODY I LAWY POD GRIMAC)
    // =========================================================================

    private BlockPos findWaterBreachSource(Level level, BlockPos center) {
        FluidState headFluid = level.getFluidState(center.above());

        // 1. Zagrożenie życia: bot tonie w wodzie lub płonie w lawie sięgającej głowy
        if (!headFluid.isEmpty() && (headFluid.is(FluidTags.WATER) || headFluid.is(FluidTags.LAVA))) {
            for (BlockPos pos : BlockPos.betweenClosed(center, center.above(2))) {
                if (level.getFluidState(pos).isSource()) {
                    return pos.immutable();
                }
            }
            return center.above().immutable();
        }

        // 2. Zagrożenie zalania tunelu: bezpośrednie źródło wody lub jakakolwiek lawa tuż przed botem w kierunku marszu
        BlockPos frontFeet = center.relative(tunnelDir);
        BlockPos frontHead = frontFeet.above();

        FluidState frontHeadFluid = level.getFluidState(frontHead);
        FluidState frontFeetFluid = level.getFluidState(frontFeet);

        if (!frontHeadFluid.isEmpty() && (frontHeadFluid.is(FluidTags.LAVA) || frontHeadFluid.isSource())) {
            return frontHead.immutable();
        }
        if (!frontFeetFluid.isEmpty() && (frontFeetFluid.is(FluidTags.LAVA) || frontFeetFluid.isSource())) {
            return frontFeet.immutable();
        }

        // Płynąca woda na stopach (feet) lub na podłodze otwartej jaskini NIE aktywuje zatykania!
        return null;
    }

    private void retreatStepBackward(LocalPlayer player, Direction forwardDir) {
        baritone.getInputOverrideHandler().clearAllKeys();
        float targetYaw = forwardDir.toYRot();
        Rotation gcdRot = applyGrimGcd(player.getYRot(), player.getXRot(), targetYaw, player.getXRot());
        baritone.getLookBehavior().updateTarget(gcdRot, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
    }

    private BlockPos findAnyLavaHazardNear(Level level, BlockPos pos) {
        if (level.getFluidState(pos).is(FluidTags.LAVA)) return pos.immutable();
        for (Direction d : Direction.values()) {
            BlockPos adj = pos.relative(d);
            if (level.getFluidState(adj).is(FluidTags.LAVA)) return adj.immutable();
        }
        return null;
    }

    private PathingCommand handleSealingHazard(LocalPlayer player, Level level) {
        if (hazardSourcePos == null || level.getFluidState(hazardSourcePos).isEmpty() || sealFailedAttempts >= MAX_SEAL_ATTEMPTS) {
            this.hazardSourcePos = null;
            this.sealFailedAttempts = 0;
            this.sealCooldownTicks = 2;
            this.state = State.TUNNELING;
            baritone.getInputOverrideHandler().clearAllKeys();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // Jeśli stoimy niebezpiecznie blisko (< 1.15 bloku), cofnij się klawiszem S bez obrotu kamery o 180°
        if (player.position().distanceTo(Vec3.atCenterOf(hazardSourcePos)) < 1.15D) {
            retreatStepBackward(player, primaryMiningDir);
        }

        if (!selectThrowawayBlock(player)) {
            sealFailedAttempts++;
            if (sealFailedAttempts >= MAX_SEAL_ATTEMPTS) {
                this.hazardSourcePos = null;
                this.state = State.TUNNELING;
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        double reach = getEffectiveReach();
        Vec3 eyePos = player.getEyePosition(1.0F);

        // Szukamy litego oparcia wokół komory lawy: tył, dół, boki, góra
        BlockPos supportPos = null;
        Direction hitFace = null;
        Direction[] searchOrder = {
            primaryMiningDir, Direction.DOWN,
            primaryMiningDir.getClockWise(), primaryMiningDir.getCounterClockWise(),
            Direction.UP
        };

        for (Direction dir : searchOrder) {
            BlockPos neighbor = hazardSourcePos.relative(dir);
            BlockState neighborState = level.getBlockState(neighbor);
            if (!neighborState.canBeReplaced() && neighborState.getFluidState().isEmpty() && neighborState.isSolid()) {
                supportPos = neighbor;
                hitFace = dir.getOpposite(); // Ścianka skierowana w stronę hazardSourcePos
                break;
            }
        }

        if (supportPos != null && hitFace != null) {
            Vec3 hitVec = Vec3.atCenterOf(supportPos).add(
                hitFace.getStepX() * 0.49D,
                hitFace.getStepY() * 0.49D,
                hitFace.getStepZ() * 0.49D
            );

            if (eyePos.distanceToSqr(hitVec) <= reach * reach) {
                Rotation raw = calcRotationFromVec3d(eyePos, hitVec, player.getYRot());
                Rotation gcdRot = applyGrimGcd(player.getYRot(), player.getXRot(), raw.getYaw(), raw.getPitch());
                baritone.getLookBehavior().updateTarget(gcdRot, true);
                baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);

                float yawDiff = Math.abs(Mth.wrapDegrees(player.getYRot() - gcdRot.getYaw()));
                float pitchDiff = Math.abs(player.getXRot() - gcdRot.getPitch());
                if (yawDiff < 6.0F && pitchDiff < 6.0F) {
                    BlockHitResult hitResult = new BlockHitResult(hitVec, hitFace, supportPos, false);
                    ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, hitResult);
                    player.swing(InteractionHand.MAIN_HAND);
                    this.sealCooldownTicks = 3;
                    this.sealFailedAttempts = 0;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            }
        }

        // Fallback do standardowego szukania rotacji
        Optional<Rotation> placeRot = findPlacementRotationForBreach(player, level, hazardSourcePos, reach);
        if (placeRot.isPresent()) {
            baritone.getLookBehavior().updateTarget(placeRot.get(), true);
            baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            sealCooldownTicks = 2;
            sealFailedAttempts = 0;
        } else {
            sealFailedAttempts++;
            if (sealFailedAttempts >= MAX_SEAL_ATTEMPTS) {
                this.hazardSourcePos = null;
                this.state = State.TUNNELING;
            }
            sealCooldownTicks = 2;
        }

        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private Optional<Rotation> findPlacementRotationForBreach(LocalPlayer player, Level level, BlockPos breachPos, double maxReach) {
        Vec3 eyePos = player.getEyePosition(1.0F);
        float currentYaw = player.getYRot();
        float currentPitch = player.getXRot();

        for (Direction dir : Direction.values()) {
            BlockPos neighbor = breachPos.relative(dir);
            BlockState neighborState = level.getBlockState(neighbor);
            if (!neighborState.isSolid() || neighborState.canBeReplaced()) {
                continue;
            }
            Direction targetFace = dir.getOpposite();

            Vec3 faceCenter = Vec3.atCenterOf(neighbor).add(
                    targetFace.getStepX() * 0.49D,
                    targetFace.getStepY() * 0.49D,
                    targetFace.getStepZ() * 0.49D
            );

            if (eyePos.distanceToSqr(faceCenter) > maxReach * maxReach) {
                continue;
            }

            Rotation raw = calcRotationFromVec3d(eyePos, faceCenter, currentYaw);
            Rotation gcdRot = applyGrimGcd(currentYaw, currentPitch, raw.getYaw(), raw.getPitch());

            Vec3 lookVec = Vec3.directionFromRotation(gcdRot.getPitch(), gcdRot.getYaw());
            Vec3 rayEnd = eyePos.add(lookVec.scale(maxReach));
            BlockHitResult hit = level.clip(new ClipContext(
                    eyePos, rayEnd, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player
            ));

            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(neighbor)) {
                return Optional.of(gcdRot);
            }
        }

        // Fallback: Baritone standard reachable rotation to solid neighbor
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = breachPos.relative(dir);
            BlockState neighborState = level.getBlockState(neighbor);
            if (!neighborState.isSolid() || neighborState.canBeReplaced()) {
                continue;
            }
            Optional<Rotation> reachRot = RotationUtils.reachable(ctx, neighbor, maxReach);
            if (reachRot.isPresent()) {
                Rotation gcdRot = applyGrimGcd(currentYaw, currentPitch, reachRot.get().getYaw(), reachRot.get().getPitch());
                return Optional.of(gcdRot);
            }
        }

        return Optional.empty();
    }

    private boolean selectThrowawayBlock(LocalPlayer player) {
        if (hazardSourcePos != null && baritone.getInventoryBehavior().selectThrowawayForLocation(true, hazardSourcePos.getX(), hazardSourcePos.getY(), hazardSourcePos.getZ())) {
            return true;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem) {
                Block b = blockItem.getBlock();
                if (b == Blocks.COBBLESTONE || b == Blocks.COBBLED_DEEPSLATE || b == Blocks.DEEPSLATE
                        || b == Blocks.TUFF || b == Blocks.DIRT || b == Blocks.STONE
                        || b == Blocks.ANDESITE || b == Blocks.DIORITE || b == Blocks.GRANITE) {
                    player.getInventory().selected = i;
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasDirectLavaAboveOrBehind(Level level, BlockPos orePos) {
        for (Direction dir : Direction.values()) {
            if (level.getFluidState(orePos.relative(dir)).is(FluidTags.LAVA)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSolidSupport(Level level, BlockPos floorPos) {
        BlockState state = level.getBlockState(floorPos);
        return !state.isAir()
                && level.getFluidState(floorPos).isEmpty()
                && !(state.getBlock() instanceof FallingBlock)
                && state.isFaceSturdy(level, floorPos, Direction.UP);
    }

    private boolean isPassable(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isAir() || state.getCollisionShape(level, pos).isEmpty();
    }

    private boolean isFallingBlock(BlockState state) {
        return state.getBlock() instanceof FallingBlock || state.is(BlockTags.SAND);
    }

    public static boolean isBedrockOrUnbreakable(Level level, BlockPos pos) {
        if (pos == null || level == null) return true;
        if (pos.getY() <= -64) return true;
        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.BEDROCK)) return true;
        if (state.getDestroySpeed(level, pos) < 0.0F) return true;
        return false;
    }

    private void rotateTunnelDirectionSafe(Level level, BlockPos feet) {
        Direction left = tunnelDir.getCounterClockWise();
        Direction right = tunnelDir.getClockWise();
        Direction back = tunnelDir.getOpposite();

        boolean rightSafe = isTunnelDirectionClear(level, feet, right);
        boolean leftSafe = isTunnelDirectionClear(level, feet, left);
        boolean rightVirgin = rightSafe && isSolidMineableWall(level, feet, right);
        boolean leftVirgin = leftSafe && isSolidMineableWall(level, feet, left);

        // 1. Zdecydowany priorytet dla nowej litej skały zamiast powrotu starym tunelem
        long rightKey = feet.relative(right).asLong();
        long leftKey = feet.relative(left).asLong();

        if (rightVirgin && !minedCorridorFeet.contains(rightKey)) {
            minedCorridorFeet.add(rightKey);
            tunnelDir = right;
            return;
        }
        if (leftVirgin && !minedCorridorFeet.contains(leftKey)) {
            minedCorridorFeet.add(leftKey);
            tunnelDir = left;
            return;
        }
        if (rightVirgin) {
            tunnelDir = right;
            return;
        }
        if (leftVirgin) {
            tunnelDir = left;
            return;
        }

        // 2. Druga kolejność: jakiekolwiek bezpieczne przejścia boczne
        if (rightSafe) {
            tunnelDir = right;
        } else if (leftSafe) {
            tunnelDir = left;
        } else {
            // BEZWZGLĘDNY ZAKAZ zawracania o 180° i kopania tunelu wstecz!
            // Jeśli oba boki są zablokowane, zachowaj primaryMiningDir.
            tunnelDir = this.primaryMiningDir;
        }
    }

    /**
     * Zabezpieczenie przed bieganiem po starych/wykopanych tunelach:
     * W normalnym kopaniu dziewiczej skały blok 2 kroki w przód ZAWSZE jest litą skałą.
     * Jeśli 2 kolejne kroki w profilu 1x2 to pusta przestrzeń -> bot patrzy w stary korytarz lub jaskinię!
     */
    private boolean isLookingDownExistingTunnel(Level level, BlockPos feet, Direction dir) {
        BlockPos oneStepFeet = feet.relative(dir);
        BlockPos oneStepHead = oneStepFeet.above();
        BlockPos twoStepsFeet = oneStepFeet.relative(dir);
        BlockPos twoStepsHead = twoStepsFeet.above();

        return isPassable(level, oneStepFeet) && isPassable(level, oneStepHead)
                && isPassable(level, twoStepsFeet) && isPassable(level, twoStepsHead);
    }

    /**
     * Wybiera kierunek w litą skałę (lewo/prawo), eliminując puste przestrzenie i zapętlenia.
     */
    private Direction selectUntouchedBranchDirection(Level level, BlockPos feet, Direction forward) {
        Direction left = forward.getCounterClockWise();
        Direction right = forward.getClockWise();

        boolean leftMineable = isSolidMineableWall(level, feet, left);
        boolean rightMineable = isSolidMineableWall(level, feet, right);

        long leftKey = feet.relative(left).asLong();
        long rightKey = feet.relative(right).asLong();

        // 1. Wybierz stronę, w którą jeszcze nigdy nie wchodziliśmy
        if (leftMineable && !minedCorridorFeet.contains(leftKey)) {
            minedCorridorFeet.add(leftKey);
            return left;
        }
        if (rightMineable && !minedCorridorFeet.contains(rightKey)) {
            minedCorridorFeet.add(rightKey);
            return right;
        }

        // 2. Jeśli obie były w historii, ale nadal są litą skałą do wykopania
        if (leftMineable) return left;
        if (rightMineable) return right;

        return null;
    }

    private boolean isSolidMineableWall(Level level, BlockPos feet, Direction dir) {
        BlockPos wallFeet = feet.relative(dir);
        BlockPos wallHead = wallFeet.above();

        if (isBedrockOrUnbreakable(level, wallFeet) || isBedrockOrUnbreakable(level, wallHead)) {
            return false;
        }
        if (isHazardNear(level, wallFeet) || isHazardNear(level, wallHead)) {
            return false;
        }

        // Ściana musi być litą skałą (nie powietrzem!)
        return !isPassable(level, wallFeet) && !isPassable(level, wallHead);
    }

    private boolean isTunnelDirectionClear(Level level, BlockPos feet, Direction dir) {
        BlockPos targetFeet = feet.relative(dir);
        BlockPos targetHead = targetFeet.above();
        BlockPos floorPos = targetFeet.below();

        if (isBedrockOrUnbreakable(level, targetFeet) || isBedrockOrUnbreakable(level, targetHead)) {
            return false;
        }
        if (isHazardNear(level, targetFeet) || isHazardNear(level, targetHead)) {
            return false;
        }
        return isSolidSupport(level, floorPos);
    }

    private boolean tryBridgeFloor(LocalPlayer player, Level level, BlockPos floorPos, BlockPos defaultSupport) {
        if (!level.getBlockState(floorPos).canBeReplaced()) {
            return false;
        }

        if (!selectThrowawayBlock(player)) {
            return false;
        }

        double reach = getEffectiveReach();
        Vec3 eyePos = player.getEyePosition(1.0F);

        // 1. Znajdź blok oparcia: domyślnie defaultSupport (np. feet.below()), a jeśli brak to ściany boczne lub spód
        BlockPos supportPos = null;
        Direction placeFace = null;

        if (defaultSupport != null && isSolidSupport(level, defaultSupport)) {
            supportPos = defaultSupport;
            for (Direction d : Direction.values()) {
                if (defaultSupport.relative(d).equals(floorPos)) {
                    placeFace = d;
                    break;
                }
            }
        }

        if (supportPos == null || placeFace == null) {
            for (Direction dir : Direction.values()) {
                if (dir == Direction.UP) continue;
                BlockPos neighbor = floorPos.relative(dir);
                if (level.getBlockState(neighbor).isSolid() && !level.getBlockState(neighbor).canBeReplaced()) {
                    supportPos = neighbor;
                    placeFace = dir.getOpposite();
                    break;
                }
            }
        }

        if (supportPos == null || placeFace == null) {
            return false;
        }

        // 2. Punkt celowania na ściance placeFace bloku supportPos (y+0.85 na ściankach poziomych ułatwia trafienie z góry)
        Vec3 hitVec = new Vec3(
                supportPos.getX() + 0.5D + placeFace.getStepX() * 0.5D,
                supportPos.getY() + (placeFace.getAxis().isHorizontal() ? 0.85D : 0.5D + placeFace.getStepY() * 0.5D),
                supportPos.getZ() + 0.5D + placeFace.getStepZ() * 0.5D
        );

        if (eyePos.distanceToSqr(hitVec) > reach * reach) {
            return false;
        }

        // 3. Obliczenie rotacji z Grim GCD
        Rotation rawRot = calcRotationFromVec3d(eyePos, hitVec, player.getYRot());
        Rotation gcdRot = applyGrimGcd(player.getYRot(), player.getXRot(), rawRot.getYaw(), rawRot.getPitch());
        baritone.getLookBehavior().updateTarget(gcdRot, true);
        baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);

        // 4. Raycast weryfikacyjny pod GrimAC
        Vec3 lookVec = Vec3.directionFromRotation(gcdRot.getPitch(), gcdRot.getYaw());
        Vec3 rayEnd = eyePos.add(lookVec.scale(reach));
        BlockHitResult raycast = level.clip(new ClipContext(
                eyePos,
                rayEnd,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player
        ));

        boolean validHit = raycast.getType() == HitResult.Type.BLOCK
                && raycast.getBlockPos().equals(supportPos)
                && raycast.getDirection() == placeFace;

        BlockPos feet = player.blockPosition();
        Direction forward = primaryMiningDir;
        Vec3 edgeCenter = Vec3.atBottomCenterOf(feet).add(forward.getStepX() * 0.38D, 0.0D, forward.getStepZ() * 0.38D);
        double distToEdge = player.position().distanceTo(edgeCenter);

        if (!validHit && distToEdge > 0.08D) {
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
            return true;
        }

        // 5. Postawienie bloku
        if (validHit || distToEdge <= 0.12D) {
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            BlockHitResult finalHit = validHit ? raycast : new BlockHitResult(hitVec, placeFace, supportPos, false);
            ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND, finalHit);
            player.swing(InteractionHand.MAIN_HAND);
            this.sealCooldownTicks = 2;
        }

        return true;
    }

    private void moveSafelyTowards(LocalPlayer player, Vec3 targetCenter) {
        Level level = player.level();
        BlockPos feet = player.blockPosition();

        // 1. Obsługa nawigacji w wodzie (pływanie, wynurzanie, kompensacja prądu)
        if (player.isInWater() || !level.getFluidState(feet).isEmpty()) {
            handleWaterNavigation(player, level, targetCenter);
            return;
        }

        // 2. Normalny ruch lądowy
        Rotation moveRot = calcRotationFromVec3d(player.getEyePosition(1.0F), targetCenter.add(0, player.getEyeHeight(), 0), player.getYRot());
        Rotation gcdMoveRot = applyGrimGcd(player.getYRot(), player.getXRot(), moveRot.getYaw(), 0.0F);
        baritone.getLookBehavior().updateTarget(gcdMoveRot, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);

        // KATEGORYCZNY ZAKAZ STRAFE (A/D) W TUNELU:
        // W korytarzu 1x2 jakiekolwiek trzymanie A/D powoduje tarcie o ścianę i flagi GrimAC Simulation!
        // Centrowanie w osi korytarza odbywa się wyłącznie wektorem patrzenia (gcdMoveRot wprost na targetCenter).
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, false);

        // 3. Early Ascend Assist (skok dozwolony WYŁĄCZNIE przy fizycznym schodku w górę +1Y i wolnym suficie)
        Direction moveDir = tunnelDir != null ? tunnelDir : primaryMiningDir;
        boolean hasHeadroom = isPassable(level, feet.above(2));
        if (hasHeadroom && shouldEarlyJump(player, level, targetCenter, moveDir)) {
            baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
        } else {
            baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, false);
        }

        // 4. Watchdog utknięcia (bez ślepego skakania w sufit)
        updateMovementStuckWatchdog(player, level, moveDir);
    }

    private void handleWaterNavigation(LocalPlayer player, Level level, Vec3 targetCenter) {
        BlockPos feet = player.blockPosition();
        FluidState fluid = level.getFluidState(feet);
        Vec3 flow = fluid.getFlow(level, feet);

        // Kompensacja prądu wody
        Vec3 toTarget = targetCenter.subtract(player.position()).normalize();
        Vec3 compensated = toTarget.subtract(flow.scale(0.65D)).normalize();
        float compensatedYaw = (float) (Math.toDegrees(Math.atan2(compensated.z, compensated.x)) - 90.0);

        Rotation gcdWaterRot = applyGrimGcd(player.getYRot(), player.getXRot(), compensatedYaw, 0.0F);
        baritone.getLookBehavior().updateTarget(gcdWaterRot, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, false);

        // Aktywne utrzymywanie pływalności i wychodzenie na ląd
        double fluidHeight = player.getFluidHeight(FluidTags.WATER);
        boolean needAscend = targetCenter.y > player.getY() + 0.2D
                || player.isUnderWater()
                || fluidHeight > 0.35D;

        if (needAscend) {
            baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
        } else {
            baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, false);
        }
    }

    private boolean shouldEarlyJump(LocalPlayer player, Level level, Vec3 targetCenter, Direction dir) {
        if (!player.onGround()) return false;

        // Skacz TYLKO jeśli cel jest fizycznie powyżej stóp (stopień/schodki w górę +1Y)
        if (targetCenter.y <= player.getY() + 0.35D) {
            return false;
        }

        Vec3 lookAhead = player.position().add(dir.getStepX() * 0.45D, 0.0D, dir.getStepZ() * 0.45D);
        BlockPos aheadPos = BlockPos.containing(lookAhead.x, player.getY(), lookAhead.z);

        boolean stepSolid = !level.getBlockState(aheadPos).getCollisionShape(level, aheadPos).isEmpty();
        boolean headRoomFree = isPassable(level, aheadPos.above())
                && isPassable(level, aheadPos.above(2))
                && isPassable(level, player.blockPosition().above(2));

        return stepSolid && headRoomFree;
    }

    private void applyCornerFeeler(LocalPlayer player, Vec3 targetCenter, Direction tunnelDir) {
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, false);
    }

    private void updateMovementStuckWatchdog(LocalPlayer player, Level level, Direction dir) {
        if (player.position().distanceToSqr(this.lastTrackedMovementPos) < 0.0016D) {
            this.movementStuckTicks++;
        } else {
            this.movementStuckTicks = 0;
            this.lastTrackedMovementPos = player.position();
        }

        // Nigdy nie skacz w tunelu 1x2 (uderzenie głową w sufit flaguje Simulation 0.098)
        if (this.movementStuckTicks >= 20) {
            BlockPos ceiling = player.blockPosition().above(2);
            if (!level.getBlockState(ceiling).isAir() && !isBedrockOrUnbreakable(level, ceiling)) {
                mineBlockSafely(player, level, ceiling);
            }
            this.movementStuckTicks = 0;
        }
    }

    private void moveTowardsWithoutSprint(LocalPlayer player, Vec3 targetCenter) {
        player.setSprinting(false);
        moveSafelyTowards(player, targetCenter);
    }

    private static double horizontalDistanceToCenter(Vec3 playerPos, BlockPos blockPos) {
        double dx = (blockPos.getX() + 0.5D) - playerPos.x;
        double dz = (blockPos.getZ() + 0.5D) - playerPos.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    // =========================================================================
    // OCHRONA KILOFÓW (AUTO-TOOL)
    // =========================================================================

    private boolean isPickaxe(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return stack.is(ItemTags.PICKAXES) || stack.isCorrectToolForDrops(Blocks.STONE.defaultBlockState());
    }

    private boolean isToolUsable(ItemStack stack) {
        return isPickaxe(stack) && (!stack.isDamageableItem() || (stack.getMaxDamage() - stack.getDamageValue()) > MIN_TOOL_DURABILITY);
    }

    /**
     * Sprawdza czy gracz ma jakikolwiek sprawny kilof (drewniany/kamienny/żelazny/diamentowy/netheritowy).
     * Jeśli kilof jest w głównym ekwipunku (sloty 9..35), przenosi go na hotbar.
     */
    private boolean ensureUsablePickaxe(LocalPlayer player) {
        for (int i = 0; i < 9; i++) {
            if (isToolUsable(player.getInventory().getItem(i))) {
                return true;
            }
        }
        for (int i = 9; i < 36; i++) {
            if (isToolUsable(player.getInventory().getItem(i))) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.gameMode != null) {
                    int targetHotbarSlot = 0;
                    mc.gameMode.handleInventoryMouseClick(
                            player.inventoryMenu.containerId,
                            i,
                            targetHotbarSlot,
                            ClickType.SWAP,
                            player
                    );
                    return true;
                }
            }
        }
        return false;
    }

    private void equipBestTool(LocalPlayer player, BlockState state) {
        int bestSlot = -1;
        float bestScore = -1.0F;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            if (stack.isDamageableItem() && (stack.getMaxDamage() - stack.getDamageValue()) <= MIN_TOOL_DURABILITY) {
                continue;
            }

            float speed = stack.getDestroySpeed(state);
            boolean drops = stack.isCorrectToolForDrops(state);
            float score = (drops ? 1000.0F : (isPickaxe(stack) ? 100.0F : 0.0F)) + speed;
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }

        if (bestSlot != -1 && player.getInventory().selected != bestSlot) {
            player.getInventory().selected = bestSlot;
            ctx.playerController().syncHeldItem();
        }
    }

    private void updateStuckWatchdog(LocalPlayer player) {
        BlockPos current = player.blockPosition();
        if (current.equals(lastPlayerPos)) {
            stuckTicks++;
            if (stuckTicks > 120 && state == State.TUNNELING) {
                Level level = ctx.world();
                BlockPos ceiling = current.above(2);
                if (level != null && !level.getBlockState(ceiling).isAir() && !isBedrockOrUnbreakable(level, ceiling)) {
                    mineBlockSafely(player, level, ceiling);
                } else if (level != null) {
                    initiateLateralDetour(player, level, current);
                }
                stuckTicks = 0;
            }
        } else {
            lastPlayerPos = current;
            stuckTicks = 0;
        }
    }

    /**
     * Cel podejścia do rudy gwarantujący dystans oczu <= 2.65m (zawsze w zasięgu reach <= 3.5m).
     * Wyklucza pozycje, z których raycast zawiódł (zapobiega deadlockowi 0.0 bps).
     */
    private static final class GoalStrictOreApproach implements Goal, IGoalRenderPos {
        private final int ox, oy, oz;
        private final Set<BlockPos> excludedFeet;
        private static final double MAX_EYE_DIST_SQ = GOAL_APPROACH_EYE_REACH * GOAL_APPROACH_EYE_REACH;

        public GoalStrictOreApproach(BlockPos orePos, Set<BlockPos> excludedFeet) {
            this.ox = orePos.getX();
            this.oy = orePos.getY();
            this.oz = orePos.getZ();
            this.excludedFeet = Set.copyOf(excludedFeet);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            if (excludedFeet.contains(new BlockPos(x, y, z))) {
                return false;
            }
            // Dystans z oczu gracza (y + 1.62) do środka rudy (ox + 0.5, oy + 0.5, oz + 0.5) <= 2.65m
            double dx = (x + 0.5D) - (ox + 0.5D);
            double dy = (y + 1.62D) - (oy + 0.5D);
            double dz = (z + 0.5D) - (oz + 0.5D);
            return (dx * dx + dy * dy + dz * dz) <= MAX_EYE_DIST_SQ;
        }

        @Override
        public double heuristic(int x, int y, int z) {
            int dx = x - ox;
            int dy = (y + 1) - oy;
            int dz = z - oz;
            return GoalBlock.calculate(dx, dy, dz);
        }

        @Override
        public BlockPos getGoalPos() {
            return new BlockPos(ox, oy, oz);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof GoalStrictOreApproach that)) return false;
            return ox == that.ox && oy == that.oy && oz == that.oz && Objects.equals(excludedFeet, that.excludedFeet);
        }

        @Override
        public int hashCode() {
            return Objects.hash(ox, oy, oz, excludedFeet);
        }

        @Override
        public String toString() {
            return String.format("GoalStrictOreApproach{ore=(%d,%d,%d), excludedCount=%d}", ox, oy, oz, excludedFeet.size());
        }
    }

    @Override
    public double priority() {
        return 6.0D;
    }

    @Override
    public void onLostControl() {
        this.active = false;
        this.activeOreTarget = null;
        this.currentBreakingBlock = null;
        this.blockedForwardDir = null;
        this.lateralDetourRemaining = 0;
        this.lateralOffsetDir = null;
        this.turnCommitCooldown = 0;
        this.consecutiveStraightBlocks = 0;
        baritone.getInputOverrideHandler().clearAllKeys();
    }

    @Override
    public String displayName0() {
        return "BypassProcess [" + state.name() + " | Y=" + targetY + "]";
    }

    // Gettery dla HUD, Lua i zewnętrznych modułów
    public BlockPos getCurrentBreakingBlock() { return currentBreakingBlock; }
    public BlockPos getCurrentTargetBlock() { return activeOreTarget != null ? activeOreTarget : currentBreakingBlock; }
    public BypassConfig getConfig() { return config; }
    public State getState() { return state; }
    public String getPhase() { return state.name().toLowerCase(Locale.ROOT); }
    public Direction getTunnelDirection() { return tunnelDir; }
    public int getOresMined() { return oresMined; }
    public int getDiamondCount() { return diamondCount; }
    public int getGoldCount() { return goldCount; }
    public long getStartTime() { return startTime; }
    public List<String> getTargetOres() { return Collections.unmodifiableList(targetOresList); }
    public Set<String> getTargetDropItems() { return Collections.unmodifiableSet(targetDropItems); }
    public BypassLuaEngine getLuaEngine() { return luaEngine; }
}
