package baritone.bypass;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalItemDrop;
import baritone.api.pathing.goals.GoalNear;
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
import baritone.utils.FastBreakHelper;
import baritone.utils.GrimBreakValidator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.FallingBlockEntity;
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
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.HoneyBlock;
import net.minecraft.world.level.block.InfestedBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.MagmaBlock;
import net.minecraft.world.level.block.SlimeBlock;
import net.minecraft.world.level.block.SoulSandBlock;
import net.minecraft.world.level.block.TntBlock;
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
 * 1. Bezpieczne schodki w dół z prześwitem 3 bloków do poziomu docelowego
 * (domyślnie Y = -50).
 * 2. Kopanie tunelu 1x2 przed siebie z kontrolą dystansu (~1.15m) bez wbiegania
 * w ściany.
 * 3. Aktywne omijanie zagrożeń: lawa, woda, gravel/sand nad głową,
 * dziury/jaskinie bez podłoża.
 * 4. Ciągły skan 5 bloków w każdą stronę w poszukiwaniu diamentów/rud (kostka
 * 11x11x11) + Vein Mining 3x3x3.
 * 5. Ścisły reach <= 3.5m (GrimAC safe reach limit):
 * - Jeśli ruda w zasięgu <= 3.5m: 1-tick GrimSnap GCD i natychmiastowe
 * wykopanie.
 * - Jeśli ruda dalej lub za ścianą: podejście przez Baritone Pathing
 * (GoalOreMining / GoalGetToBlock) jak w #mine.
 * 6. Automatyczne zbieranie dropu i powrót do tunelowania na Y=-50.
 */
public final class BypassProcess extends BaritoneProcessHelper implements IBaritoneProcess {

    public static final double STRICT_REACH = 4.2D;
    public static final double ORE_EYE_REACH = 4.2D;
    private static final double GOAL_APPROACH_EYE_REACH = 2.65D;
    private static final double WALL_STOP_DISTANCE = 1.15D;
    private static final int ORE_SCAN_RADIUS = 5; // Promień 5 bloków w każdą stronę wokół gracza (Argon 4)
    public static final int DEFAULT_TARGET_Y = -58;
    public static final int MIN_SAFE_BEDROCK_Y = -58;
    public static final float TWO_BLOCK_SEAM_PITCH = 26.8f; // Stały kąt w spoinę (Y=1.0) 2 bloków naraz bez skoków kamery
    private static final int MIN_TOOL_DURABILITY = 5;

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

    private static final Map<String, Set<Block>> ORE_BLOCK_MAP = Map.of(
            "diamond", Set.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE),
            "gold", Set.of(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE),
            "iron", Set.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE),
            "copper", Set.of(Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE),
            "coal", Set.of(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE),
            "lapis", Set.of(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE),
            "redstone", Set.of(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE),
            "emerald", Set.of(Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE),
            "debris", Set.of(Blocks.ANCIENT_DEBRIS));

    private static final Map<String, Set<Item>> ORE_ITEM_MAP = Map.of(
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
    private State state = State.IDLE;
    private Direction tunnelDir = Direction.NORTH;

    private BlockPos tunnelAnchorPos = null;
    private BlockPos activeOreTarget = null;
    private BlockPos currentBreakingBlock = null;
    private int activeMiningTicks = 0;
    private int aimStallTicks = 0;

    // Pamięć zablokowanych kierunków dla konkretnych kratek (zapobiega oscylacji
    // L/P w kółko)
    private final Map<BlockPos, EnumSet<Direction>> blockedDirsByNode = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<BlockPos, EnumSet<Direction>> eldest) {
            return size() > 48;
        }
    };
    private Direction pendingTurnDir = null;
    private int turnCooldownTicks = 0;
    private final Deque<BlockPos> veinQueue = new ArrayDeque<>();
    private final Set<BlockPos> blacklistedOres = new HashSet<>();
    private final Set<BlockPos> invalidStandingPositions = new HashSet<>();
    private final List<String> targetOresList = new ArrayList<>(List.of("diamond"));
    private final Set<String> targetDropItems = new HashSet<>(
            Set.of("diamond", "raw_iron", "raw_gold", "ancient_debris"));

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
    private int placeCooldownTicks = 0;

    // Pola dla unikania pustych / wykopanych tuneli oraz Dominant Mining Vector
    // (Argon 4 & dj)
    private Direction primaryMiningDir = Direction.NORTH;
    private final it.unimi.dsi.fastutil.longs.LongSet dugColumns2D = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private final it.unimi.dsi.fastutil.longs.LongSet hazardColumns2D = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private Direction retreatDir = null;
    private BlockPos retreatStartPos = null;
    private int retreatTicks = 0;
    private static final int MAX_RETREAT_BLOCKS = 6;
    private static final int MAX_RETREAT_TICKS = 80;
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
    private int toolSwitchCooldownTicks = 0;
    private boolean originalAutoTool = true;
    private boolean originalAllowPlace = false;
    private int postPlaceMineDelayTicks = 0;
    // Zamrożona rotacja na czas aktywnego kopania pojedynczego bloku (zapobiega
    // jitterowi i ABORT_DESTROY_BLOCK)
    private Rotation lockedMiningRotation = null;
    private int lostSightGraceTicks = 0;
    // Licznik ticków wyhamowania pędu przed obrotem w stanie TURNING
    private int turnBrakeTicks = 0;

    // Bloki postawione przez bota (plomby lawy / mostki), których nie wolno
    // wykopywać z powrotem
    private final Set<BlockPos> protectedSealBlocks = Collections
            .newSetFromMap(new LinkedHashMap<BlockPos, Boolean>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<BlockPos, Boolean> eldest) {
                    return size() > 128;
                }
            });

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
        this.placeCooldownTicks = 0;
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
        Baritone.settings().autoTool.value = false;

        // Zakaz samowolnego stawiania bloków pod nogami przez Baritone Pathing
        // (eliminuje pętlę niszczenia i stawiania)
        Baritone.settings().allowPlace.value = false;
        Baritone.settings().allowBreak.value = true;
        Baritone.settings().allowParkour.value = false;

        // Rejestracja bloków z poziomu Y=-50 jako budulca do pillaringu
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

        // KRYTYCZNY RESET STANU RUCHU (Argon 4 & dj):
        // Czyści wszelkie zablokowane klawisze, stary cel kamery i ścieżki po poprzednim #stop
        baritone.getPathingBehavior().cancelEverything();
        baritone.getInputOverrideHandler().clearAllKeys();
        baritone.getLookBehavior().updateTarget(null, false);
        resetMiningState();
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
            // INTELIGENTNA DETEKCJA POZIOMU (Argon 4 & dj):
            // Jeśli gracz stoi już w głębokim korytarzu diamentowym (Y <= -50),
            // kop od razu w poziomie (State.TUNNELING) na obecnym Y bez prób kopania schodów w suficie/podłodze!
            if (currentY <= -50) {
                this.targetY = currentY;
                this.config.yLevel = currentY;
                this.state = State.TUNNELING;
            } else {
                this.state = currentY > targetY ? State.DESCENDING_STAIRCASE : State.TUNNELING;
            }
            logDirect(String.format("§a[Bypass] Uruchomiono! Poziom docelowy: Y=%d, autostrada: %s, rudy: %s",
                    this.targetY, primaryMiningDir, targetOresList));
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
        LocalPlayer player = ctx.player();
        if (player != null) {
            int currentY = player.blockPosition().getY();
            // Jeśli gracz stoi już w korytarzu na głębokim poziomie (np. -59..-53),
            // kontynuuj tunelowanie natychmiast na obecnym Y bez próby wspinaczki/schodzenia!
            if (currentY >= -60 && currentY <= -50 && configuredY <= -50) {
                configuredY = currentY;
            }
        }
        int y = Math.max(configuredY, MIN_SAFE_BEDROCK_Y);
        start(y, ores);
    }

    public void resumeFromData(ReconnectData data) {
        if (data == null)
            return;
        this.targetY = (int) Math.round(data.y);
        start(this.targetY, data.ores != null ? data.ores : List.of("diamond"));
    }

    public void stop() {
        this.active = false;
        baritone.getPathingBehavior().cancelEverything();
        baritone.getLookBehavior().updateTarget(null, false);
        baritone.getInputOverrideHandler().clearAllKeys();
        if (ctx.playerController() != null) {
            ctx.playerController().resetBlockRemoving();
        }
        resetMiningState();
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

        // 0. WaterSealer: Detekcja wycieku wody/lawy zagrażającego graczowi i
        // natychmiastowe zatkanie
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
        if (state != State.MINING_ORE && state != State.TURNING) {
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
        if (state != State.MINING_ORE && state != State.TURNING) {
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
                    state = Math.abs(diff) > 1 ? (diff > 0 ? State.DESCENDING_STAIRCASE : State.ASCENDING_STAIRCASE)
                            : State.TUNNELING;
                } else {
                    state = State.PATHFINDING_RETURN;
                }
            }
        }

        return switch (state) {
            case DESCENDING_STAIRCASE -> tickDescending(player, level);
            case ASCENDING_STAIRCASE -> tickAscending(player, level);
            case TUNNELING -> tickTunneling(player, level);
            case RETREATING -> tickRetreating(player, level);
            case TURNING -> tickTurning(player, level);
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
     * KROK 1: Bezpieczne schodki 1x3 w dół do targetY (Y = -50) z planowaniem A* i
     * blokadą Bedrocka.
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
        // Zamiast obracać się o 180° w tył (co powodowało spadek z bloku), adaptujemy
        // obecny poziom Y jako bezpieczną podłogę!
        if (stepDown.getY() <= MIN_SAFE_BEDROCK_Y || isBedrockOrUnbreakable(level, stepDown)
                || isBedrockOrUnbreakable(level, frontFeet)) {
            this.targetY = feet.getY();
            this.state = State.TUNNELING;
            this.tunnelDir = this.primaryMiningDir;
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        // 2. Sprawdź realny wyciek (odsłonięta ciecz) vs ukryta lawa za ścianą
        BlockPos exposedBreach = findExposedLavaBreach(level, feet, descendDir);
        if (exposedBreach != null) {
            if (selectThrowawayBlock(player)) {
                this.hazardSourcePos = exposedBreach;
                this.sealFailedAttempts = 0;
                this.state = State.SEALING_HAZARD;
                return handleSealingHazard(player, level);
            }
            return handleLavaOrObstacleEncounter(player, level, feet);
        }

        if (hasHiddenHazardBehind(level, frontHead)
                || hasHiddenHazardBehind(level, frontFeet)
                || hasHiddenHazardBehind(level, stepDown)) {
            return handleLavaOrObstacleEncounter(player, level, feet);
        }

        // 3. Obsługa podłoża stepFloor (rozróżnienie powietrza/cieczy od stabilnego
        // żwiru)
        if (!hasStableFloorSupport(level, stepFloor)) {
            if (level.getBlockState(stepFloor).canBeReplaced()) {
                // Wykop najpierw frontHead, frontFeet i stepDown, aby odsłonić kąt do
                // zmostkowania
                if (!isPassable(level, frontHead))
                    return mineBlockSafely(player, level, frontHead);
                if (!isPassable(level, frontFeet))
                    return mineBlockSafely(player, level, frontFeet);
                if (!isPassable(level, stepDown))
                    return mineBlockSafely(player, level, stepDown);

                // Oparciem dla poziomu stepFloor (Y - 2) jest feet.below(2), a nie
                // feet.below()!
                if (tryBridgeFloor(player, level, stepFloor, feet.below(2))) {
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            }
            this.targetY = feet.getY();
            this.state = State.TUNNELING;
            this.tunnelAnchorPos = feet;
            return handleLavaOrObstacleEncounter(player, level, feet);
        }

        // 4. Kolejność kopania schodków 1x3: sufit nad głową -> przód góra -> przód dół
        // -> docelowy stopień
        List<BlockPos> stairBlocks = List.of(
                feet.above(2),
                frontHead,
                frontFeet,
                stepDown);

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
        resetMiningState();
        tunnelAnchorPos = stepDown;
        moveSafelyTowards(player, Vec3.atBottomCenterOf(stepDown));
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    /**
     * Schodki 1x3 w górę z omijaniem Bedrocka i lawy powracające na targetY (np. Y
     * = -50).
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
                BlockPos forwardAscendTarget = feet.relative(primaryMiningDir, 4)
                        .atY(Math.min(targetY, MIN_SAFE_BEDROCK_Y));
                return new PathingCommand(new baritone.api.pathing.goals.GoalBlock(forwardAscendTarget),
                        PathingCommandType.SET_GOAL_AND_PATH);
            }
        }

        BlockPos headClearance = feet.above(2);
        BlockPos nextStepFeet = feet.relative(tunnelDir).above(1);
        BlockPos nextStepHead = feet.relative(tunnelDir).above(2);

        // Bloki do wykopania w profilu 1x3 w górę (sufit nad głową -> głowa stopnia ->
        // stopy stopnia)
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
                BlockPos forwardAscendTarget = feet.relative(primaryMiningDir, 4)
                        .atY(Math.min(targetY, MIN_SAFE_BEDROCK_Y));
                return new PathingCommand(new baritone.api.pathing.goals.GoalBlock(forwardAscendTarget),
                        PathingCommandType.SET_GOAL_AND_PATH);
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        tunnelAnchorPos = nextStepFeet;
        moveSafelyTowards(player, Vec3.atBottomCenterOf(nextStepFeet));
        boolean canAscendJump = player.onGround() && isPassable(level, feet.above(2))
                && nextStepFeet.getY() > player.getY() + 0.35D;
        baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, canAscendJump);
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private boolean isAscendingDirectionClear(Level level, BlockPos feet, Direction dir) {
        BlockPos headClearance = feet.above(2);
        BlockPos nextStepFeet = feet.relative(dir).above(1);
        BlockPos nextStepHead = feet.relative(dir).above(2);

        if (isBedrockOrUnbreakable(level, headClearance) || isBedrockOrUnbreakable(level, nextStepFeet)
                || isBedrockOrUnbreakable(level, nextStepHead)) {
            return false;
        }
        if (isHazardNear(level, headClearance) || isHazardNear(level, nextStepFeet)
                || isHazardNear(level, nextStepHead)) {
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
     * KROK 2 i 3: Kopanie prostej autostrady 1x2 wzdłuż Dominant Mining Vector
     * (primaryMiningDir)
     * z omijaniem bedrocka w pionie (step up) i bezpiecznym objazdem bocznym
     * (MIN_PARALLEL_SPACING = 3).
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
        if (this.turnCooldownTicks > 0) {
            this.turnCooldownTicks--;
        }

        // 2. Zabezpieczenie przed równoległym korytarzem w odległości < 3 bloków
        // Aktywne TYLKO po wykopaniu co najmniej 6 bloków prosto w nowym kierunku
        // (zapobiega błędnemu wykrywaniu korytarza za plecami zaraz po skręcie o 90
        // stopni)
        if (this.turnCommitCooldown <= 0 && this.consecutiveStraightBlocks >= 6
                && isTooCloseToOldTunnel(feet, primaryMiningDir)) {
            return forceLateralCorrection(player, level, feet);
        }

        BlockPos frontFeet = feet.relative(primaryMiningDir);
        BlockPos frontHead = frontFeet.above();
        BlockPos ceilingAboveFront = frontHead.above();
        BlockPos floorBelowFront = frontFeet.below();

        // 3a. PRIORYTET LAVA PLUGGING: Zatykanie odsłoniętego wycieku lawy budulcem bez
        // obracania
        BlockPos exposedBreach = findExposedLavaBreach(level, feet, primaryMiningDir);
        if (exposedBreach != null) {
            if (selectThrowawayBlock(player)) {
                this.hazardSourcePos = exposedBreach;
                this.sealFailedAttempts = 0;
                this.state = State.SEALING_HAZARD;
                return handleSealingHazard(player, level);
            }
            return handleLavaOrObstacleEncounter(player, level, feet);
        }

        // 3b. Lawa ukryta za litą ścianą przed graczem (nie próbuj zatykać przez skałę
        // - bezpieczny objazd)
        if (hasHiddenHazardBehind(level, frontHead) || hasHiddenHazardBehind(level, frontFeet)) {
            return handleLavaOrObstacleEncounter(player, level, feet);
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

            // W przypadku bajorka lawy, braku podłoża lub przeszkody: inteligentny skręt w
            // lewo/prawo lub odwrót
            return handleLavaOrObstacleEncounter(player, level, feet);
        }

        // 6. Obsługa Gravelu / Sandu: jeśli w kolumnie spada żwir lub wisi nad powietrzem,
        // natychmiast kop żwir łopatą ze stałej pozycji bez resetowania paska postępu!
        if (isFallingBlockSettling(level, frontFeet, frontHead)) {
            stopMovementInputs();
            AABB columnBox = new AABB(
                    frontFeet.getX(), frontFeet.getY(), frontFeet.getZ(),
                    frontFeet.getX() + 1.0, frontHead.getY() + 3.0, frontFeet.getZ() + 1.0
            );
            if (!level.getEntitiesOfClass(net.minecraft.world.entity.item.FallingBlockEntity.class, columnBox).isEmpty()) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }

            BlockPos gravelPos;
            if (this.currentBreakingBlock != null
                    && (this.currentBreakingBlock.equals(frontHead) || this.currentBreakingBlock.equals(frontFeet))
                    && !level.getBlockState(this.currentBreakingBlock).isAir()) {
                gravelPos = this.currentBreakingBlock;
            } else {
                gravelPos = !isPassable(level, frontHead) ? frontHead : frontFeet;
            }

            if (!isPassable(level, gravelPos)) {
                return mineBlockSafely(player, level, gravelPos);
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // 7. Kopanie korytarza 1x2 przed nami
        boolean headBlocked = !isPassable(level, frontHead);
        boolean feetBlocked = !isPassable(level, frontFeet);

        if (headBlocked || feetBlocked) {
            // TRYB SPRINT-MINING (Wydajność 10 + Haste 2): płynny bieg ze sprintem i
            // instamine na spojeniu kratek 1 i 2
            if (canSprintMine(player, level, frontHead, frontFeet)) {
                return executeSprintMining(player, level, frontHead, frontFeet);
            }

            stopMovementInputs();
            BlockPos blockToMine;
            if (this.currentBreakingBlock != null
                    && (this.currentBreakingBlock.equals(frontHead) || this.currentBreakingBlock.equals(frontFeet))
                    && !level.getBlockState(this.currentBreakingBlock).isAir()) {
                blockToMine = this.currentBreakingBlock;
            } else {
                blockToMine = headBlocked ? frontHead : frontFeet;
            }
            return mineBlockSafely(player, level, blockToMine);
        }

        // 8. Ochrona przed wejściem w przepaść lub do jeziora lawy:
        // Pod żadnym pozorem nie stawiaj kroku w przód, jeśli podłoga nie daje oparcia!
        BlockPos stepFloor = frontFeet.below();
        if (!hasStableFloorSupport(level, stepFloor)) {
            if (level.getBlockState(stepFloor).canBeReplaced()
                    && tryBridgeFloor(player, level, stepFloor, feet.below())) {
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            return handleLavaOrObstacleEncounter(player, level, feet);
        }

        // Korytarz czysty -> płynny krok naprzód
        tunnelAnchorPos = frontFeet;
        this.consecutiveStraightBlocks++;
        moveSafelyTowards(player, Vec3.atBottomCenterOf(frontFeet));
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private boolean isFastPickaxeActive(LocalPlayer player) {
        if (FastBreakHelper.hasEfficiency10(ctx) || FastBreakHelper.isFastPickaxe(ctx)) {
            return true;
        }
        ItemStack held = player.getMainHandItem();
        if (isPickaxe(held) && isToolUsable(held)) {
            int eff = FastBreakHelper.getEfficiencyLevel(held);
            boolean hasHaste = player.hasEffect(MobEffects.DIG_SPEED);
            if (eff >= 8 || (eff >= 5 && hasHaste) || eff >= 10) {
                return true;
            }
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (isPickaxe(stack) && isToolUsable(stack)) {
                int eff = FastBreakHelper.getEfficiencyLevel(stack);
                if (eff >= 8 || (eff >= 5 && player.hasEffect(MobEffects.DIG_SPEED)) || eff >= 10) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean canSprintMine(LocalPlayer player, Level level, BlockPos frontHead, BlockPos frontFeet) {
        if (!isFastPickaxeActive(player)) {
            return false;
        }
        BlockState headState = level.getBlockState(frontHead);
        BlockState feetState = level.getBlockState(frontFeet);

        // Gravel / Sand wymaga normalnego, stabilnego kopania łopatą w mineBlockSafely (zakaz sprint-miningu na żwirze)
        if (headState.getBlock() instanceof FallingBlock || feetState.getBlock() instanceof FallingBlock
                || isFallingBlock(headState) || isFallingBlock(feetState)) {
            return false;
        }

        // Wyklucz bedrock, obsydian i bloki o twardości > 4.5
        float headHardness = headState.getDestroySpeed(level, frontHead);
        float feetHardness = feetState.getDestroySpeed(level, frontFeet);
        if (headHardness < 0 || headHardness > 4.5f || feetHardness < 0 || feetHardness > 4.5f) {
            return false;
        }

        // Upewnij się, że podłoga pod stopami i pod kolejnym krokiem jest stabilna
        BlockPos nextFloor = frontFeet.below();
        if (!hasStableFloorSupport(level, nextFloor) || !hasStableFloorSupport(level, player.blockPosition().below())) {
            return false;
        }

        return !isFrontDirectlyBlockedByHazard(level, player.blockPosition(), primaryMiningDir);
    }

    private PathingCommand executeSprintMining(LocalPlayer player, Level level, BlockPos frontHead,
            BlockPos frontFeet) {
        if (isFallingBlockSettling(level, frontFeet, frontHead)) {
            stopMovementInputs();
            AABB columnBox = new AABB(
                    frontFeet.getX(), frontFeet.getY(), frontFeet.getZ(),
                    frontFeet.getX() + 1.0, frontHead.getY() + 3.0, frontFeet.getZ() + 1.0
            );
            if (!level.getEntitiesOfClass(net.minecraft.world.entity.item.FallingBlockEntity.class, columnBox).isEmpty()) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }

            BlockPos gravelPos;
            if (this.currentBreakingBlock != null
                    && (this.currentBreakingBlock.equals(frontHead) || this.currentBreakingBlock.equals(frontFeet))
                    && !level.getBlockState(this.currentBreakingBlock).isAir()) {
                gravelPos = this.currentBreakingBlock;
            } else {
                gravelPos = !isPassable(level, frontHead) ? frontHead : frontFeet;
            }

            if (!isPassable(level, gravelPos)) {
                return mineBlockSafely(player, level, gravelPos);
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        boolean headBlocked = !isPassable(level, frontHead);
        boolean feetBlocked = !isPassable(level, frontFeet);
        double distH = horizontalDistanceToCenter(player.position(), frontFeet);

        // Ustal FAKTYCZNIE niszczony blok (nigdy nie pobieraj stanu z powietrza w frontHead!)
        BlockPos activeBreakTarget = (feetBlocked && distH < 1.05D) ? frontFeet : (headBlocked ? frontHead : frontFeet);
        BlockPos toolTargetPos = activeBreakTarget;

        Minecraft mc = Minecraft.getInstance();
        if (mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            BlockPos hitPos = bhr.getBlockPos();
            BlockState hitState = level.getBlockState(hitPos);
            if (!hitState.isAir()) {
                boolean frontStillBlocked = !level.getBlockState(frontHead).isAir() || !level.getBlockState(frontFeet).isAir();
                // Ochrona celownika: jeśli przed nami wisi blok, a promień przelotowo trafił w blok za nim
                if (frontStillBlocked && !hitPos.equals(frontHead) && !hitPos.equals(frontFeet)) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
                    stopMovementInputs();
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                toolTargetPos = hitPos;
                activeBreakTarget = hitPos;
            }
        }

        BlockState stateToMine = level.getBlockState(toolTargetPos);
        if (stateToMine.isAir()) {
            stateToMine = headBlocked ? level.getBlockState(frontHead) : level.getBlockState(frontFeet);
        }

        // 1. Zapewnij właściwe narzędzie w ręku
        ToolEquipStatus toolStatus = equipBestTool(player, stateToMine);
        if (toolStatus == ToolEquipStatus.FAILED) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            stopMovementInputs();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (toolStatus == ToolEquipStatus.SWITCHED) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // Twarda straż (Hard Guard): zakaz trzymania CLICK_LEFT ze złym lub zużytym narzędziem
        ItemStack held = player.getMainHandItem();
        if ((stateToMine.requiresCorrectToolForDrops() && !held.isCorrectToolForDrops(stateToMine)) || !isToolUsable(held)) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            stopMovementInputs();
            resetMiningState();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // 2. Kąt patrzenia: stały kąt 2-blokowy 26.8° (Argon 4 & dj)
        // Przecina szew Y=1.00 w odległości ~1.2m przed graczem. Promień w biegu najpierw niszczy górny blok,
        // a po jego rozbiciu od razu płynnie penetruje dolny bez żadnych skoków kamery góra/dół!
        float pitch = TWO_BLOCK_SEAM_PITCH;
        float targetYaw = primaryMiningDir.toYRot();

        // Delikatne centrowanie w osi korytarza bez używania klawiszy A/D (eliminacja
        // Simulation)
        BlockPos feetPos = player.blockPosition();
        double centerOffset = (primaryMiningDir.getAxis() == Direction.Axis.Z)
                ? (player.getX() - (feetPos.getX() + 0.5D))
                : (player.getZ() - (feetPos.getZ() + 0.5D));
        if (Math.abs(centerOffset) > 0.05D) {
            float steer = (float) Math.clamp(-centerOffset * 15.0D, -3.0D, 3.0D);
            targetYaw += steer;
        }

        Rotation sprintRot = applyGrimGcd(player.getYRot(), player.getXRot(), targetYaw, pitch);
        baritone.getLookBehavior().updateTarget(sprintRot, true);

        // 3. Ciągły atak lewym przyciskiem myszy z powiadomieniem
        // ContinuousBreakController
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
        ContinuousBreakController.notifyBreaking(ctx, new BetterBlockPos(activeBreakTarget));

        // 4. Płynny ruch w przód (GrimAC-safe: sprint przez InputOverrideHandler, zero bezpośredniego setSprinting)
        baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        boolean allowSprint = isFastPickaxeActive(player)
                && player.getFoodData().getFoodLevel() > 6
                && !player.horizontalCollision;
        baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, allowSprint);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, false);

        this.consecutiveStraightBlocks++;
        this.activeMiningTicks = 0;
        this.aimStallTicks = 0;

        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private boolean isLavaFluid(Level level, BlockPos pos) {
        FluidState fs = level.getFluidState(pos);
        return !fs.isEmpty() && (fs.is(FluidTags.LAVA) || fs.is(Fluids.LAVA) || fs.is(Fluids.FLOWING_LAVA));
    }

    private boolean isLava(Level level, BlockPos pos) {
        return isLavaFluid(level, pos);
    }

    private boolean isFrontDirectlyBlockedByHazard(Level level, BlockPos feet, Direction forward) {
        BlockPos frontFeet = feet.relative(forward);
        BlockPos frontHead = frontFeet.above();
        long frontKey2D = to2DKey(frontFeet.getX(), frontFeet.getZ());

        if (this.hazardColumns2D.contains(frontKey2D)) {
            return true;
        }

        // Skanowanie zagrożeń w osi marszu na dystans do 4 bloków w przód:
        for (int step = 1; step <= 4; step++) {
            BlockPos stepFeet = feet.relative(forward, step);
            BlockPos stepHead = stepFeet.above();
            BlockPos ceilingAbove = stepHead.above();
            BlockPos floorBelow = stepFeet.below();

            // 1. Bezpośrednia lawa w obrębie profilu tunelu
            if (isLava(level, stepFeet)
                    || isLava(level, stepHead)
                    || isLava(level, ceilingAbove)
                    || isLava(level, floorBelow)) {
                return true;
            }

            // 2. Brak podłoża lub lawa w podłodze na pierwszym kroku
            if (step == 1 && (!hasStableFloorSupport(level, floorBelow) || isLava(level, floorBelow))) {
                return true;
            }

            // 3. Blok lity przed nami: sprawdzamy czy jego wykopanie uwolni lawę
            if (step == 1) {
                if (!isPassable(level, stepFeet) && hasHiddenHazardBehind(level, stepFeet, forward)) {
                    return true;
                }
                if (!isPassable(level, stepHead) && hasHiddenHazardBehind(level, stepHead, forward)) {
                    return true;
                }
            }

            // 4. Przestrzeń otwarta przed nami (stary tunel / jaskinia):
            // Sprawdzamy ODSŁONIĘTĄ lawę zamiast ślepej penetracji litej skały
            if (isPassable(level, stepFeet) || isPassable(level, stepHead)) {
                if (hasExposedLavaInOpenSpace(level, stepFeet, forward)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Sprawdza czy w otwartej przestrzeni (stary tunel / skrzyżowanie / jaskinia) znajduje się
     * ODSŁONIĘTA lawa mająca bezpośredni kontakt z powietrzem tunelu (zamiast ślepego cylindra przez litą skałę).
     */
    private boolean hasExposedLavaInOpenSpace(Level level, BlockPos airStartPos, Direction moveDir) {
        BlockPos headPos = airStartPos.above();
        BlockPos floorPos = airStartPos.below();
        if (isLava(level, airStartPos) || isLava(level, headPos) || isLava(level, floorPos) || isLava(level, headPos.above())) {
            return true;
        }
        if (isPassable(level, floorPos) && isLava(level, floorPos.below())) {
            return true;
        }

        Direction backDir = (moveDir != null) ? moveDir.getOpposite() : null;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (dir == backDir) continue;
            if (isLava(level, airStartPos.relative(dir)) || isLava(level, headPos.relative(dir))) {
                return true;
            }
            BlockPos sideAir = airStartPos.relative(dir);
            if (isPassable(level, sideAir)) {
                if (isLava(level, sideAir.below()) || isLava(level, sideAir.relative(dir))
                        || (moveDir != null && isLava(level, sideAir.relative(moveDir)))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Zwraca pozycję do zatkania TYLKO wtedy, gdy lawa faktycznie ma otwarte
     * przejście do korytarza gracza.
     */
    private BlockPos findExposedLavaBreach(Level level, BlockPos feet, Direction forward) {
        BlockPos head = feet.above();
        BlockPos frontFeet = feet.relative(forward);
        BlockPos frontHead = frontFeet.above();

        List<BlockPos> corridorCells = new ArrayList<>(4);
        corridorCells.add(feet);
        corridorCells.add(head);
        // Sprawdzamy sąsiadów frontFeet/frontHead pod kątem wycieku tylko jeśli same są
        // już wykopane!
        if (isPassable(level, frontFeet))
            corridorCells.add(frontFeet);
        if (isPassable(level, frontHead))
            corridorCells.add(frontHead);

        for (BlockPos cell : corridorCells) {
            if (isLavaFluid(level, cell) && level.getBlockState(cell).canBeReplaced()) {
                return cell.immutable();
            }
            for (Direction dir : Direction.values()) {
                BlockPos adj = cell.relative(dir);
                if (isLavaFluid(level, adj) && level.getBlockState(adj).canBeReplaced()
                        && hasSolidNeighborForPlacement(level, adj)) {
                    return adj.immutable();
                }
            }
        }
        return null;
    }

    /**
     * Wykrywa lawę ukrytą za litą ścianą (np. za frontFeet lub frontHead), zanim
     * bot ją wykopie.
     */
    private boolean hasHiddenHazardBehind(Level level, BlockPos targetPos, Direction miningDir) {
        if (isPassable(level, targetPos)) {
            return false; // Powietrza się nie kopie - ściany boczne zostają nienaruszone
        }
        if (isLava(level, targetPos)) {
            return true;
        }
        Direction backDir = (miningDir != null) ? miningDir.getOpposite() : null;
        for (Direction dir : Direction.values()) {
            if (dir == backDir) continue; // Ignoruj stronę gracza
            if (dir == Direction.DOWN) continue; // Lawa pod litym blokiem nie płynie do góry

            BlockPos neighbor = targetPos.relative(dir);
            if (isLava(level, neighbor)) {
                return true;
            }
            if (dir == Direction.UP && level.getBlockState(neighbor).getBlock() instanceof FallingBlock) {
                if (isLava(level, neighbor.above())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasHiddenHazardBehind(Level level, BlockPos solidWallPos) {
        return hasHiddenHazardBehind(level, solidWallPos, primaryMiningDir);
    }

    private boolean hasSolidNeighborForPlacement(Level level, BlockPos targetPos) {
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = targetPos.relative(dir);
            BlockState s = level.getBlockState(neighbor);
            if (!s.canBeReplaced() && s.getFluidState().isEmpty() && !s.isAir()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sprawdza, czy w odległości 1..2 bloków prostopadle do wektora znajduje się
     * już wykopany tunel.
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
        if (feet.getY() >= targetY)
            return false;

        BlockPos stepUpFeet = feet.above().relative(forward);
        BlockPos stepUpHead = stepUpFeet.above();
        BlockPos ceilingAboveHead = feet.above(2);

        if (isBedrockOrUnbreakable(level, ceilingAboveHead))
            return false;
        if (isBedrockOrUnbreakable(level, stepUpFeet) || isBedrockOrUnbreakable(level, stepUpHead))
            return false;
        if (isHazardNear(level, stepUpFeet) || isHazardNear(level, stepUpHead))
            return false;

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
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
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
        baritone.getInputOverrideHandler().clearAllKeys();
        resetMiningState();

        Direction currentDir = primaryMiningDir != null ? primaryMiningDir : Direction.SOUTH;
        this.blockedForwardDir = currentDir;

        // Oznacz blok przed nami w osi marszu jako zagrożenie (zapobiega powrotowi po obrocie)
        BlockPos frontPos = feet.relative(currentDir);
        this.hazardColumns2D.add(to2DKey(frontPos.getX(), frontPos.getZ()));

        BlockPos nodeKey = feet.immutable();
        EnumSet<Direction> blockedAtNode = blockedDirsByNode.computeIfAbsent(nodeKey,
                k -> EnumSet.noneOf(Direction.class));
        blockedAtNode.add(currentDir);
        blockedAtNode.add(currentDir.getOpposite());

        Direction leftDir = currentDir.getCounterClockWise();
        Direction rightDir = currentDir.getClockWise();

        // Krok 1: Szukaj świeżej, niewykopanej ściany (preferowane dla strip-mine)
        boolean leftFresh = !blockedAtNode.contains(leftDir) && isSafeBranchDirection(level, feet, leftDir, false);
        boolean rightFresh = !blockedAtNode.contains(rightDir) && isSafeBranchDirection(level, feet, rightDir, false);

        // Krok 2: Fallback - pozwól ominąć przeszkodę przez własny wykopany wcześniej bezpieczny tunel!
        boolean leftFallback = leftFresh || isSafeBranchDirection(level, feet, leftDir, true);
        boolean rightFallback = rightFresh || isSafeBranchDirection(level, feet, rightDir, true);

        logDirect(String.format("§e[Bypass] Przeszkoda na węźle! Ściany (Nowe/Tunel): L=%s/%s, P=%s/%s",
                (leftFresh ? "§aŚWIEŻA" : "§c-"), (leftFallback ? "§bTUNEL" : "§cZABLOK"),
                (rightFresh ? "§aŚWIEŻA" : "§c-"), (rightFallback ? "§bTUNEL" : "§cZABLOK")));

        // 1. Jeśli świeże ściany są dostępne:
        if (leftFresh && rightFresh) {
            Direction chosen = pickBestBranchDirection(level, feet, leftDir, rightDir);
            logDirect("§a[Bypass] Obie strony bezpieczne od lawy! Skręcam w §f" + chosen.name() + "§a!");
            return initiateTurnState(chosen);
        }
        if (leftFresh) {
            logDirect("§c[Bypass] §ePo prawej stronie przeszkoda! Skręcam natychmiast w §aLEWO§e!");
            return initiateTurnState(leftDir);
        }
        if (rightFresh) {
            logDirect("§c[Bypass] §ePo lewej stronie przeszkoda! Skręcam natychmiast w §aPRAWO§e!");
            return initiateTurnState(rightDir);
        }

        // 2. Jeśli świeże ściany są niedostępne, ale stary tunel jest czysty i bezpieczny:
        if (leftFallback && rightFallback) {
            Direction chosen = pickBestBranchDirection(level, feet, leftDir, rightDir);
            logDirect("§b[Bypass] Używam istniejącego bezpiecznego tunelu do ominięcia przeszkody: §f" + chosen.name());
            return initiateTurnState(chosen);
        }
        if (leftFallback) {
            logDirect("§b[Bypass] Omijanie przeszkody przez bezpieczny tunel po §aLEWEJ§b!");
            return initiateTurnState(leftDir);
        }
        if (rightFallback) {
            logDirect("§b[Bypass] Omijanie przeszkody przez bezpieczny tunel po §aPRAWEJ§b!");
            return initiateTurnState(rightDir);
        }

        // 3. Wszystkie strony na tym węźle są fizycznie zablokowane -> przejdź w State.RETREATING
        if (!leftFallback) blockedAtNode.add(leftDir);
        if (!rightFallback) blockedAtNode.add(rightDir);

        return executeRetreatBackward(player, level, feet, currentDir);
    }

    private PathingCommand initiateTurnState(Direction newDir) {
        baritone.getInputOverrideHandler().clearAllKeys();
        stopMovementInputs();
        resetMiningState();
        this.pendingTurnDir = newDir;
        this.state = State.TURNING;
        this.turnBrakeTicks = 3; // Wymuś 3 ticki pełnego wygaszenia pędu przed jakimkolwiek obrotem
        this.turnCooldownTicks = 15;
        if (ctx.playerController() != null) {
            ctx.playerController().resetBlockRemoving();
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    /**
     * Wywoływane z onTick(), gdy state == State.TURNING.
     * Obraca kamerę zgodnie z Grim GCD i dopiero po zakończeniu obrotu zatwierdza
     * primaryMiningDir.
     */
    private PathingCommand tickTurning(LocalPlayer player, Level level) {
        stopMovementInputs();
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);

        if (this.pendingTurnDir == null) {
            this.state = State.TUNNELING;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // FAZA 1: Pełne wygaszenie pędu poziomego PRZED obrotem kamery (eliminuje
        // impuls 0.0098 w Simulation)
        Vec3 vel = player.getDeltaMovement();
        double horizSpeedSq = vel.x * vel.x + vel.z * vel.z;
        if (this.turnBrakeTicks > 0 || horizSpeedSq > 1.0E-4D) {
            if (this.turnBrakeTicks > 0) {
                this.turnBrakeTicks--;
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // FAZA 2: Czysty obrót kamery na stojąco (zero klawiszy ruchu = zero rozjazdu
        // moveRelative w GrimAC)
        float targetYaw = this.pendingTurnDir.toYRot();
        float targetPitch = 0.0f;

        Rotation gcdRot = applyGrimGcd(player.getYRot(), player.getXRot(), targetYaw, targetPitch);
        baritone.getLookBehavior().updateTarget(gcdRot, true);

        float yawDiff = Math.abs(Mth.wrapDegrees(player.getYRot() - targetYaw));
        if (yawDiff > 2.5f) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // FAZA 3: Kamera patrzy już w nowy kierunek.
        // Jeśli gracz stoi zbyt blisko ściany bocznej NOWEGO tunelu, wycentruj tylko w
        // osi prostopadłej.
        BlockPos feet = player.blockPosition();
        Vec3 blockCenter = new Vec3(feet.getX() + 0.5D, player.getY(), feet.getZ() + 0.5D);
        double lateralError = (this.pendingTurnDir.getAxis() == Direction.Axis.Z)
                ? Math.abs(player.getX() - blockCenter.x)
                : Math.abs(player.getZ() - blockCenter.z);

        if (lateralError > 0.15D) {
            stepTowardsPointWithSneak(player, blockCenter);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        this.primaryMiningDir = this.pendingTurnDir;
        this.tunnelDir = this.pendingTurnDir;
        this.blockedForwardDir = null;
        this.lateralDetourRemaining = 0;
        this.lateralOffsetDir = null;
        this.turnCommitCooldown = 40;
        this.consecutiveStraightBlocks = 0;
        this.pendingTurnDir = null;
        this.state = State.TUNNELING;

        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private PathingCommand executeRetreatBackward(LocalPlayer player, Level level, BlockPos feet,
            Direction forwardDir) {
        // Zapisz węzeł jako zablokowany, aby zapobiec natychmiastowemu zapętleniu
        this.hazardColumns2D.add(to2DKey(feet.getX(), feet.getZ()));

        this.retreatDir = forwardDir.getOpposite();
        this.retreatStartPos = feet.immutable();
        this.retreatTicks = 0;
        this.consecutiveStraightBlocks = 0;
        this.state = State.RETREATING;

        logDirect("§c[Bypass] §4Wszystkie kierunki zablokowane na tym węźle! Wycofuję się do tyłu bezpiecznym tunelem...");
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private PathingCommand tickRetreating(LocalPlayer player, Level level) {
        this.retreatTicks++;
        baritone.getInputOverrideHandler().clearAllKeys();

        if (this.retreatDir == null || this.retreatStartPos == null) {
            this.state = State.TUNNELING;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        BlockPos feet = player.blockPosition();
        int distMoved = feet.distManhattan(this.retreatStartPos);

        Direction leftDir = this.retreatDir.getClockWise();
        Direction rightDir = this.retreatDir.getCounterClockWise();

        // 1. Sprawdź czy po cofnięciu się o minimum 1 blok otworzyło się bezpieczne odejście w bok
        if (distMoved >= 1) {
            boolean canGoLeft = isSafeBranchDirection(level, feet, leftDir, false)
                    || isSafeBranchDirection(level, feet, leftDir, true);
            boolean canGoRight = isSafeBranchDirection(level, feet, rightDir, false)
                    || isSafeBranchDirection(level, feet, rightDir, true);

            if (canGoLeft && canGoRight) {
                Direction chosen = pickBestBranchDirection(level, feet, leftDir, rightDir);
                logDirect("§a[Bypass] Wycofano (" + distMoved + " bl.). Wybieram optymalne odejście: §f" + chosen.name());
                return initiateTurnState(chosen);
            } else if (canGoLeft) {
                logDirect("§a[Bypass] Wycofano (" + distMoved + " bl.). Skręcam w bezpieczne odejście: §f" + leftDir.name());
                return initiateTurnState(leftDir);
            } else if (canGoRight) {
                logDirect("§a[Bypass] Wycofano (" + distMoved + " bl.). Skręcam w bezpieczne odejście: §f" + rightDir.name());
                return initiateTurnState(rightDir);
            }
        }

        // 2. Jeśli cofnęliśmy się o MAX_RETREAT_BLOCKS lub droga w tył jest zablokowana / minął timeout:
        BlockPos backFeet = feet.relative(this.retreatDir);
        BlockPos backHead = backFeet.above();
        boolean backPassable = isPassable(level, backFeet) && isPassable(level, backHead)
                && !isLava(level, backFeet.below()) && !isLava(level, backFeet);

        if (distMoved >= MAX_RETREAT_BLOCKS || !backPassable || this.retreatTicks > MAX_RETREAT_TICKS) {
            logDirect("§e[Bypass] Zakończono wycofywanie (dystans: " + distMoved + " bl.). Obracam wektor kopania o 180°.");
            return initiateTurnState(this.retreatDir);
        }

        // 3. Sprawdź czy korytarz za nami nie został zasypany (np. gravel) - jeśli tak, odkop
        if (!isPassable(level, backHead)) {
            return mineBlockSafely(player, level, backHead);
        }
        if (!isPassable(level, backFeet)) {
            return mineBlockSafely(player, level, backFeet);
        }

        // 4. Fizyczny ruch w tył: trzymamy yaw osi tunelu i cofamy klawiszem MOVE_BACK (brak nagłych snapów 180°)
        Direction lookDir = this.retreatDir.getOpposite();
        float targetYaw = lookDir.toYRot();
        Rotation gcdRot = applyGrimGcd(player.getYRot(), player.getXRot(), targetYaw, 0.0F);
        baritone.getLookBehavior().updateTarget(gcdRot, false);

        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);

        // Centrowanie w osi poprzecznej tunelu
        double centerX = feet.getX() + 0.5D;
        double centerZ = feet.getZ() + 0.5D;
        double dx = centerX - player.getX();
        double dz = centerZ - player.getZ();
        double lateralOffset = dx * leftDir.getStepX() + dz * leftDir.getStepZ();

        if (lateralOffset > 0.12D) {
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, true);
        } else if (lateralOffset < -0.12D) {
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, true);
        }

        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private boolean isSafeBranchDirection(Level level, BlockPos origin, Direction dir) {
        return isSafeBranchDirection(level, origin, dir, false);
    }

    private boolean isSafeBranchDirection(Level level, BlockPos origin, Direction dir, boolean allowExistingTunnels) {
        if (primaryMiningDir != null && dir == primaryMiningDir.getOpposite()) {
            return false;
        }

        // Sprawdzamy pierwsze 2 kroki (zamiast 3, co zawsze kolidowało z sąsiednim pasem w dugColumns2D)
        for (int step = 1; step <= 2; step++) {
            BlockPos sideFeet = origin.relative(dir, step);
            BlockPos sideHead = sideFeet.above();
            BlockPos sideFloor = sideFeet.below();
            BlockPos sideCeiling = sideHead.above();
            long key2D = to2DKey(sideFeet.getX(), sideFeet.getZ());

            // 0. Potwierdzone zagrożenia lawowe / zablokowane węzły
            if (hazardColumns2D.contains(key2D)) {
                return false;
            }

            // 1. Nie wolno kopać w bedrock ani bloki niezniszczalne na 1. kroku
            if (step == 1 && (isBedrockOrUnbreakable(level, sideFeet) || isBedrockOrUnbreakable(level, sideHead))) {
                return false;
            }

            // 2. Podłoże pod nowym korytarzem musi być stabilne (nie wolno wejść w pustkę/lawę)
            if (!hasStableFloorSupport(level, sideFloor) || isLava(level, sideFloor)) {
                return false;
            }

            // 3. Ściana i sufit nie mogą być ciekłą lawą
            if (isLava(level, sideFeet) || isLava(level, sideHead) || isLava(level, sideCeiling)) {
                return false;
            }

            // 4. Zagrożenie ukryte za litą ścianą na 1. kroku:
            if (step == 1 && !isPassable(level, sideFeet) && hasHiddenHazardBehind(level, sideFeet, dir)) {
                return false;
            }
            if (step == 1 && !isPassable(level, sideHead) && hasHiddenHazardBehind(level, sideHead, dir)) {
                return false;
            }

            // 5. Otwarta jaskinia / przestrzeń na 1. lub 2. kroku -> sprawdzamy ODSŁONIĘTĄ lawę
            if (isPassable(level, sideFeet) || isPassable(level, sideHead)) {
                if (hasExposedLavaInOpenSpace(level, sideFeet, dir)) {
                    return false;
                }
            }

            // 6. Sprawdź czy korytarz nie nakłada się na starą wykopaną trasę (tylko na 1. kroku i tylko w normalnym trybie)
            if (!allowExistingTunnels && step == 1 && dugColumns2D.contains(key2D)) {
                return false;
            }
        }

        return true;
    }

    private Direction pickBestBranchDirection(Level level, BlockPos feet, Direction left, Direction right) {
        BlockPos leftFeet = feet.relative(left);
        BlockPos rightFeet = feet.relative(right);

        boolean leftSolid = !isPassable(level, leftFeet) && !isBedrockOrUnbreakable(level, leftFeet);
        boolean rightSolid = !isPassable(level, rightFeet) && !isBedrockOrUnbreakable(level, rightFeet);

        // Preferuj lity kamień (bezpieczne kopanie w ścianę) zamiast otwartej jaskini
        if (leftSolid && !rightSolid)
            return left;
        if (rightSolid && !leftSolid)
            return right;

        boolean leftDug = dugColumns2D.contains(to2DKey(leftFeet.getX(), leftFeet.getZ()));
        boolean rightDug = dugColumns2D.contains(to2DKey(rightFeet.getX(), rightFeet.getZ()));
        if (!leftDug && rightDug)
            return left;
        if (!rightDug && leftDug)
            return right;

        return left;
    }

    private boolean isDetourRouteSafe(Level level, BlockPos origin, Direction dir) {
        return isSafeBranchDirection(level, origin, dir);
    }

    private record MiningAction(BlockPos targetPos, Rotation snapRotation) {
    }

    /**
     * KROK 4 i 5: Wykrywanie rud, ścisły reach <= 3.5 bloka (GrimSnap) oraz
     * podejście przez Baritone jak w #mine.
     */
    private PathingCommand tickMiningOre(LocalPlayer player, Level level, boolean calcFailed, boolean isSafeToCancel) {
        while (activeOreTarget == null || !isTargetOre(level.getBlockState(activeOreTarget))
                || blacklistedOres.contains(activeOreTarget)) {
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

        // 0. Aktywny lock kopania: jeśli już niszczymy blok (np. zasłaniający lub rudę)
        // i nadal istnieje -> kontynuuj niszczenie bez przerywania!
        if (this.currentBreakingBlock != null) {
            BlockState curState = level.getBlockState(this.currentBreakingBlock);
            if (!curState.isAir() && !isBedrockOrUnbreakable(level, this.currentBreakingBlock)) {
                Optional<Rotation> curSnap = findVerifiedGrimSnapRotation(player, level, this.currentBreakingBlock,
                        effectiveReach);
                if (curSnap.isEmpty()) {
                    curSnap = RotationUtils.reachable(ctx, this.currentBreakingBlock, effectiveReach);
                }
                if (curSnap.isPresent()) {
                    ToolEquipStatus status = equipBestTool(player, curState);
                    if (status == ToolEquipStatus.FAILED) {
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
                        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                    }
                    if (status == ToolEquipStatus.SWITCHED) {
                        if (ctx.playerController() != null) {
                            ctx.playerController().resetBlockRemoving();
                        }
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
                        ContinuousBreakController.reset();
                        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                    }
                    if ((curState.requiresCorrectToolForDrops()
                            && !player.getMainHandItem().isCorrectToolForDrops(curState)) || !isToolUsable(player.getMainHandItem())) {
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
                        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                    }
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

            // Zatrzymujemy ruch Baritone, bo przejmujemy bezpośrednią kontrolę nad uderzeniami w rudę
            if (baritone.getPathingBehavior().isPathing()) {
                baritone.getPathingBehavior().cancelEverything();
            }
            stopMovementInputs();

            return mineBlockSafely(player, level, action.targetPos());
        }

        // Ruda poza bezpośrednim strzałem -> zwolnij lewy przycisk i podejdź przez Baritone
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
        resetMiningState();

        // 2. Jeśli A* zgłosił błąd kalkulacji -> blacklist i przejdź dalej
        if (calcFailed) {
            blacklistedOres.add(activeOreTarget);
            activeOreTarget = null;
            this.invalidStandingPositions.clear();
            this.oreStagnationTicks = 0;
            this.oreAttemptTicks = 0;
            baritone.getInputOverrideHandler().clearAllKeys();
            state = State.TUNNELING;
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        // 3. Naliczaj timeout wyłącznie wtedy, gdy bot stoi w miejscu i nie szuka/idzie po ścieżce
        boolean isMoving = player.getDeltaMovement().horizontalDistanceSqr() > 0.0005D;
        if (!isMoving && !baritone.getPathingBehavior().isPathing() && !baritone.getPathingBehavior().getInProgress().isPresent()) {
            if (++this.oreAttemptTicks > 70) {
                blacklistedOres.add(activeOreTarget);
                activeOreTarget = null;
                this.oreAttemptTicks = 0;
                this.invalidStandingPositions.clear();
                baritone.getInputOverrideHandler().clearAllKeys();
                state = State.TUNNELING;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        } else {
            this.oreAttemptTicks = 0;
        }

        // 4. KRYTYCZNE (Argon 4 & dj): Używamy GoalNear(activeOreTarget, 1) z SET_GOAL_AND_PATH!
        // ŻADNEGO sztucznego moveTowardsWithoutSprint z REQUEST_PAUSE, który zamrażał bota na 0.0 bps!
        return new PathingCommand(new GoalNear(activeOreTarget, 1), PathingCommandType.SET_GOAL_AND_PATH);
    }

    private Optional<MiningAction> findMiningAction(LocalPlayer player, Level level, BlockPos orePos, double reach) {
        if (isBedrockOrUnbreakable(level, orePos)) {
            blacklistedOres.add(orePos);
            return Optional.empty();
        }

        Vec3 eyePos = player.getEyePosition(1.0F);
        double reachSq = reach * reach;
        if (eyePos.distanceToSqr(Vec3.atCenterOf(orePos)) > (reach + 1.2D) * (reach + 1.2D)) {
            return Optional.empty();
        }

        // 1. Sprawdź 7 kluczowych punktów na bryle rudy
        Vec3[] sampleOffsets = new Vec3[]{
                new Vec3(0.5D, 0.5D, 0.5D),
                new Vec3(0.5D, 0.18D, 0.5D),
                new Vec3(0.5D, 0.82D, 0.5D),
                new Vec3(0.18D, 0.5D, 0.5D),
                new Vec3(0.82D, 0.5D, 0.5D),
                new Vec3(0.5D, 0.5D, 0.18D),
                new Vec3(0.5D, 0.5D, 0.82D)
        };

        BlockPos bestOccluder = null;
        Rotation bestOccluderRot = null;
        double bestOccluderDistSq = Double.MAX_VALUE;

        for (Vec3 offset : sampleOffsets) {
            Vec3 targetPt = Vec3.atLowerCornerOf(orePos).add(offset);
            if (eyePos.distanceToSqr(targetPt) > reachSq) continue;

            Rotation rawRot = calcRotationFromVec3d(eyePos, targetPt, player.getYRot());
            Rotation gcdRot = applyGrimGcd(player.getYRot(), player.getXRot(), rawRot.getYaw(), rawRot.getPitch());

            Vec3 lookVec = Vec3.directionFromRotation(gcdRot.getPitch(), gcdRot.getYaw());
            BlockHitResult hit = level.clip(new ClipContext(
                    eyePos,
                    eyePos.add(lookVec.scale(reach)),
                    ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE,
                    player));

            if (hit.getType() == HitResult.Type.BLOCK) {
                BlockPos hitPos = hit.getBlockPos();
                if (hitPos.equals(orePos)) {
                    if (hasDirectLavaAboveOrBehind(level, orePos) && player.blockPosition().distManhattan(orePos) <= 1) {
                        blacklistedOres.add(orePos);
                        return Optional.empty();
                    }
                    return Optional.of(new MiningAction(orePos, gcdRot));
                }

                double hitDistSq = eyePos.distanceToSqr(Vec3.atCenterOf(hitPos));
                if (hitDistSq <= reachSq && hitDistSq < bestOccluderDistSq
                        && !hitPos.equals(player.blockPosition().below())
                        && !isHazardNear(level, hitPos)
                        && !isBedrockOrUnbreakable(level, hitPos)
                        && !level.getBlockState(hitPos).isAir()
                        && level.getBlockState(hitPos).getDestroySpeed(level, hitPos) >= 0) {
                    bestOccluder = hitPos;
                    bestOccluderRot = gcdRot;
                    bestOccluderDistSq = hitDistSq;
                }
            }
        }

        // Fallback: standardowy Baritone RotationUtils.reachable z Grim GCD
        Optional<Rotation> directOreSnap = RotationUtils.reachable(ctx, orePos, reach);
        if (directOreSnap.isPresent()) {
            if (hasDirectLavaAboveOrBehind(level, orePos) && player.blockPosition().distManhattan(orePos) <= 1) {
                blacklistedOres.add(orePos);
                return Optional.empty();
            }
            Rotation gcdRot = applyGrimGcd(player.getYRot(), player.getXRot(), directOreSnap.get().getYaw(),
                    directOreSnap.get().getPitch());
            return Optional.of(new MiningAction(orePos, gcdRot));
        }

        // Jeśli ruda jest zasłonięta przez pojedynczy blok ściany/podłogi w reach -> wykop blok zasłaniający
        if (bestOccluder != null) {
            return Optional.of(new MiningAction(bestOccluder, bestOccluderRot));
        }

        return Optional.empty();
    }

    /**
     * Powrót na oś głównego tunelu Y=-50 po wykopaniu całej żyły i zebraniu dropu.
     */
    private PathingCommand tickReturningToTunnel(LocalPlayer player, Level level, boolean calcFailed) {
        if (tunnelAnchorPos == null || calcFailed || player.blockPosition().distManhattan(tunnelAnchorPos) <= 2
                || ++returnStuckTicks > 80) {
            tunnelAnchorPos = null;
            returnStuckTicks = 0;
            this.tunnelDir = this.primaryMiningDir;
            state = (player.blockPosition().getY() - targetY > 1) ? State.DESCENDING_STAIRCASE : State.TUNNELING;
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        return new PathingCommand(new GoalTwoBlocks(tunnelAnchorPos), PathingCommandType.SET_GOAL_AND_PATH);
    }

    // =========================================================================
    // GRIMAC SNAP ROTATION (GCD QUANTIZED + MULTIPOINT VOXEL RAYCAST <= 3.5M)
    // =========================================================================

    private PathingCommand mineBlockSafely(LocalPlayer player, Level level, BlockPos target) {
        BlockState targetState = level.getBlockState(target);
        if (target == null || isBedrockOrUnbreakable(level, target) || targetState.isAir()
                || !level.getFluidState(target).isEmpty()) {
            if (ctx.playerController() != null && this.currentBreakingBlock != null) {
                ctx.playerController().resetBlockRemoving();
            }
            resetMiningState();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        stopMovementInputs();

        // 1. Zmiana docelowego bloku -> bezwzględny reset stanu niszczenia i
        // odblokowanie rotacji
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

        // 2. Dobór narzędzia i obsługa 1-tickowego bufora synchronizacji slota z
        // serwerem
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

        // 3. Kategoryczna ochrona GrimAC: blokada kopania skał (np. Deepslate) łopatą,
        // motyką lub zużytym narzędziem!
        ItemStack heldStack = player.getMainHandItem();
        if ((targetState.requiresCorrectToolForDrops() && !heldStack.isCorrectToolForDrops(targetState)) || !isToolUsable(heldStack)) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            if (ctx.playerController() != null) {
                ctx.playerController().resetBlockRemoving();
            }
            ContinuousBreakController.reset();
            return handleLavaOrObstacleEncounter(player, level, player.blockPosition());
        }

        // 4. Dynamiczny limit ticków zsynchronizowany z walidatorem GrimAC + bufor 20 ticków na lag
        int reqTicks = GrimBreakValidator.getRequiredTicks(
                player, level, target, targetState, player.onGround()
        );
        int maxExpectedTicks = Math.max(60, reqTicks + 40);

        if (this.activeMiningTicks > maxExpectedTicks) {
            if (ctx.playerController() != null) {
                ctx.playerController().resetBlockRemoving();
            }
            resetMiningState();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        double reach = getEffectiveReach();

        // 5. Jeśli mamy już zamrożoną rotację i kopiemy blok, sprawdzamy czy nadal
        // trafia w target
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
                this.lockedMiningRotation = null; // Kąt przestał trafiać, potrzebna rekalkulacja
            }
        }

        // Wylicz nową rotację TYLKO gdy nie mamy jeszcze zablokowanego kąta
        if (this.lockedMiningRotation == null) {
            Optional<Rotation> candidate = findMultipointGrimRotation(player, level, target, reach);
            if (candidate.isEmpty()) {
                Optional<Rotation> fallbackSnap = RotationUtils.reachable(ctx, target, reach);
                if (fallbackSnap.isPresent()) {
                    Rotation gcdRot = applyGrimGcd(
                            player.getYRot(),
                            player.getXRot(),
                            fallbackSnap.get().getYaw(),
                            fallbackSnap.get().getPitch());
                    candidate = Optional.of(gcdRot);
                }
            }
            candidate.ifPresent(rot -> this.lockedMiningRotation = rot);
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

            // Jeśli celownik FAKTYCZNIE trafia w blok (hitValid) lub jest w małej tolerancji rotacji:
            if (hitValid || (yawDelta <= 10.0f && pitchDelta <= 10.0f)) {
                this.aimStallTicks = 0;
                this.lostSightGraceTicks = 0;
                this.activeMiningTicks++;
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                ContinuousBreakController.notifyBreaking(ctx, new BetterBlockPos(target));
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }

            // Jeśli kopanie już trwało, 3 ticki tolerancji zapobiegają zerowaniu postępu
            // przez mikrolag interpolacji
            if (this.activeMiningTicks > 0 && this.lostSightGraceTicks < 3) {
                this.lostSightGraceTicks++;
                this.activeMiningTicks++;
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                ContinuousBreakController.notifyBreaking(ctx, new BetterBlockPos(target));
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }

        // Raycast zablokowany przed rozpoczęciem kopania
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

        // Bezpieczne offsety wewnątrz bryły (unikamy krawędzi, gdzie promień ślizga się
        // po bloku pod stopami)
        double[] yOffsets = { 0.55D, 0.72D, 0.82D, 0.35D };
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

                    Rotation rawRot = calcRotationFromVec3d(eyePos, hitVec, player.getYRot());
                    Rotation gcdRot = applyGrimGcd(player.getYRot(), player.getXRot(), rawRot.getYaw(),
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
                        // Kara za punkty blisko krawędzi bocznych, preferujemy stabilny środek ścianki
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

    public static Optional<Rotation> findVerifiedGrimSnapRotation(LocalPlayer player, Level level, BlockPos pos,
            double maxReach) {
        return findMultipointGrimRotation(player, level, pos, maxReach);
    }

    private void nudgeTowardsVisibleEdge(LocalPlayer player, BlockPos target) {
        Vec3 targetCenter = Vec3.atCenterOf(target);
        double dx = targetCenter.x - player.getX();
        double dz = targetCenter.z - player.getZ();
        float moveYaw = (float) (Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        float yawDiff = Math.abs(Mth.wrapDegrees(player.getYRot() - moveYaw));

        baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
        if (yawDiff < 60.0F) {
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        }
    }

    private void resetMiningState() {
        this.currentBreakingBlock = null;
        this.lockedMiningRotation = null;
        this.activeMiningTicks = 0;
        this.aimStallTicks = 0;
        this.lostSightGraceTicks = 0;
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
        ContinuousBreakController.reset();
    }

    private void stopMovementInputs() {
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, false);
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

    private static BlockPos findObstructingBlockInReach(LocalPlayer player, Level level, BlockPos orePos,
            double maxReach) {
        Vec3 eyePos = player.getEyePosition(1.0F);
        Vec3 oreCenter = Vec3.atCenterOf(orePos);
        Vec3 dir = oreCenter.subtract(eyePos).normalize();
        Vec3 end = eyePos.add(dir.scale(maxReach));

        BlockHitResult hit = level.clip(new ClipContext(
                eyePos,
                end,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player));

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
                    if (blacklistedOres.contains(pos))
                        continue;

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
                    if (dx == 0 && dy == 0 && dz == 0)
                        continue;
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
        if (state == null || state.isAir())
            return false;
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
            if (!entity.isAlive() || entity.isInLava() || ignoredDropIds.containsKey(entity.getId()))
                return false;
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
     * Aktualizuje rotację tylko wtedy, gdy różnica kątów przekracza próg czułości
     * sieciowej.
     * Zapobiega wysyłaniu zduplikowanych pakietów PosRot/Rot (GrimAC
     * AimDuplicateLook).
     */
    private void updateRotationGrimSafe(LocalPlayer player, Rotation targetRot, boolean blockInteract) {
        if (player == null || targetRot == null)
            return;
        float yawDiff = Math.abs(Mth.wrapDegrees(targetRot.getYaw() - player.getYRot()));
        float pitchDiff = Math.abs(targetRot.getPitch() - player.getXRot());

        // Ignoruj mikro-zmiany kątów (< 0.20 stopnia), które po kwantyzacji dają
        // duplikat pakietu rotacji (AimDuplicateLook)
        if (yawDiff < 0.20f && pitchDiff < 0.20f) {
            return;
        }
        baritone.getLookBehavior().updateTarget(targetRot, blockInteract);
    }

    /**
     * Płynne podchodzenie w stronę dropu — zmienia tylko Yaw przy realnym
     * odchyleniu,
     * nie wymusza Pitch w dół na nieruchomy item.
     */
    private void moveSafelyTowardsDrop(LocalPlayer player, Vec3 targetPos) {
        if (player == null || targetPos == null)
            return;
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

        if (drop == null || !drop.isAlive() || ignoredDropIds.containsKey(drop.getId())
                || DropCollectionHelper.isDropUnsafe(level, drop)) {
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

        // PRZYPADEK 2: Drop jest blisko (<= 4.2m), ale zasłania go blok (np. deepslate
        // we wnęce) — rozkop blok!
        BlockPos obstruction = DropCollectionHelper.findObstructingBlock(player, level, drop);
        if (obstruction != null && !isBedrockOrUnbreakable(level, obstruction)) {
            dropStuckTicks = 0;
            lastDropGoal = null;
            return mineBlockSafely(player, level, obstruction);
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
        if (level.getFluidState(pos).is(FluidTags.LAVA))
            return true;
        for (Direction dir : Direction.values()) {
            BlockPos adj = pos.relative(dir);
            if (level.getFluidState(adj).is(FluidTags.LAVA))
                return true;
        }

        // 2. Źródło wody bezpośrednio w bloku lub nad nim to zagrożenie (nie kopiemy w
        // źródło oceanu/podziemnego jeziora)
        FluidState selfFluid = level.getFluidState(pos);
        if (selfFluid.is(FluidTags.WATER) && selfFluid.isSource())
            return true;
        FluidState aboveFluid = level.getFluidState(pos.above());
        if (aboveFluid.is(FluidTags.WATER) && aboveFluid.isSource())
            return true;

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

        // 2. Zagrożenie zalania tunelu: bezpośrednie źródło wody lub jakakolwiek lawa
        // tuż przed botem w kierunku marszu
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

        // Płynąca woda na stopach (feet) lub na podłodze otwartej jaskini NIE aktywuje
        // zatykania!
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

    private record PlacementCandidate(
            BlockPos supportPos,
            Direction placeFace,
            Vec3 hitVec,
            Rotation rotation) {
    }

    /**
     * Szuka widocznej ścianki sąsiedniego bloku oparcia, w którą można kliknąć PPM,
     * aby postawić blok w miejscu targetPos bez naruszania czeków GrimAC RayTrace /
     * FabricatedPlace.
     */
    private Optional<PlacementCandidate> findBestPlacementCandidate(LocalPlayer player, Level level, BlockPos targetPos,
            double reach) {
        Vec3 eyePos = player.getEyePosition(1.0F);
        double[] grid = { 0.5D, 0.3D, 0.7D, 0.2D, 0.8D };

        PlacementCandidate best = null;
        double bestAngDist = Double.MAX_VALUE;

        for (Direction dir : Direction.values()) {
            BlockPos supportPos = targetPos.relative(dir);
            Direction placeFace = dir.getOpposite();

            BlockState supportState = level.getBlockState(supportPos);
            if (supportState.isAir() || supportState.canBeReplaced() || !supportState.getFluidState().isEmpty()) {
                continue;
            }

            for (double u : grid) {
                for (double v : grid) {
                    Vec3 hitVec = getFaceSamplePoint(supportPos, placeFace, u, v);
                    if (eyePos.distanceToSqr(hitVec) > reach * reach) {
                        continue;
                    }

                    Rotation rawRot = calcRotationFromVec3d(eyePos, hitVec, player.getYRot());
                    Rotation gcdRot = applyGrimGcd(player.getYRot(), player.getXRot(), rawRot.getYaw(),
                            rawRot.getPitch());

                    Vec3 lookVec = Vec3.directionFromRotation(gcdRot.getPitch(), gcdRot.getYaw());
                    BlockHitResult clip = level.clip(new ClipContext(
                            eyePos,
                            eyePos.add(lookVec.scale(reach)),
                            ClipContext.Block.OUTLINE,
                            ClipContext.Fluid.NONE,
                            player));

                    if (clip.getType() == HitResult.Type.BLOCK
                            && clip.getBlockPos().equals(supportPos)
                            && clip.getDirection() == placeFace) {
                        double angDist = Math.hypot(
                                Mth.wrapDegrees(gcdRot.getYaw() - player.getYRot()),
                                gcdRot.getPitch() - player.getXRot());
                        if (angDist < bestAngDist) {
                            bestAngDist = angDist;
                            best = new PlacementCandidate(supportPos, placeFace, clip.getLocation(), gcdRot);
                        }
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private Vec3 getFaceSamplePoint(BlockPos pos, Direction face, double u, double v) {
        double x = pos.getX() + switch (face) {
            case WEST -> 0.001D;
            case EAST -> 0.999D;
            default -> u;
        };
        double y = pos.getY() + switch (face) {
            case DOWN -> 0.001D;
            case UP -> 0.999D;
            default -> (face.getAxis() == Direction.Axis.Y ? u : v);
        };
        double z = pos.getZ() + switch (face) {
            case NORTH -> 0.001D;
            case SOUTH -> 0.999D;
            default -> v;
        };
        return new Vec3(x, y, z);
    }

    private boolean isValidPlaceableStack(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
            return false;
        }

        Block block = blockItem.getBlock();

        // 1. Bezwzględne wykluczenie bloków opadających, posiadających TileEntity/GUI
        // oraz specjalnej fizyki
        if (block instanceof FallingBlock
                || block instanceof EntityBlock
                || block instanceof SlimeBlock
                || block instanceof HoneyBlock
                || block instanceof SoulSandBlock
                || block instanceof MagmaBlock
                || block instanceof TntBlock
                || block instanceof InfestedBlock
                || block instanceof LeavesBlock) {
            return false;
        }

        // 2. Akceptacja bloków z listy Baritone acceptableThrowawayItems lub
        // standardowych pełnych skał/ziemi
        if (BaritoneAPI.getSettings().acceptableThrowawayItems.value.contains(blockItem)) {
            return true;
        }

        return block == Blocks.COBBLESTONE
                || block == Blocks.COBBLED_DEEPSLATE
                || block == Blocks.DEEPSLATE
                || block == Blocks.STONE
                || block == Blocks.TUFF
                || block == Blocks.ANDESITE
                || block == Blocks.DIORITE
                || block == Blocks.GRANITE
                || block == Blocks.DIRT
                || block == Blocks.COARSE_DIRT
                || block == Blocks.ROOTED_DIRT
                || block == Blocks.NETHERRACK
                || block == Blocks.BLACKSTONE
                || block == Blocks.BASALT
                || block == Blocks.SMOOTH_BASALT
                || block == Blocks.CALCITE
                || block == Blocks.DRIPSTONE_BLOCK
                || block == Blocks.END_STONE;
    }

    private boolean isHoldingValidPlaceableBlock(LocalPlayer player) {
        return isValidPlaceableStack(player.getMainHandItem());
    }

    private int findValidBlockInRange(LocalPlayer player, int startInclusive, int endExclusive) {
        for (int i = startInclusive; i < endExclusive; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (isValidPlaceableStack(stack)) {
                return i;
            }
        }
        return -1;
    }

    private boolean selectThrowawayBlock(LocalPlayer player, BlockPos targetPos) {
        BlockPos lookupPos = targetPos != null ? targetPos
                : (hazardSourcePos != null ? hazardSourcePos : player.blockPosition());

        // 1. Próba wyboru przez wbudowany menedżer Baritone (obsługuje też przełożenie
        // ze slotów 9-35)
        if (baritone.getInventoryBehavior().selectThrowawayForLocation(true, lookupPos.getX(), lookupPos.getY(),
                lookupPos.getZ())) {
            if (ctx.playerController() != null) {
                ctx.playerController().syncHeldItem();
            }
            if (isHoldingValidPlaceableBlock(player)) {
                return true;
            }
        }

        // 2. Fallback: skanowanie hotbara (0-8)
        int hotbarSlot = findValidBlockInRange(player, 0, 9);
        if (hotbarSlot != -1) {
            if (player.getInventory().selected != hotbarSlot) {
                player.getInventory().selected = hotbarSlot;
                if (ctx.playerController() != null) {
                    ctx.playerController().syncHeldItem();
                }
            }
            return true;
        }

        // 3. Fallback: skanowanie głównego ekwipunku (9-35) i swap do slotu 8 na
        // hotbarze
        int invSlot = findValidBlockInRange(player, 9, 36);
        if (invSlot != -1 && ctx.playerController() != null) {
            int targetHotbarSlot = 8;
            ctx.playerController().windowClick(
                    player.inventoryMenu.containerId,
                    invSlot,
                    targetHotbarSlot,
                    ClickType.SWAP,
                    player);
            player.getInventory().selected = targetHotbarSlot;
            ctx.playerController().syncHeldItem();
            return isHoldingValidPlaceableBlock(player);
        }

        return false;
    }

    private boolean selectThrowawayBlock(LocalPlayer player) {
        return selectThrowawayBlock(player, hazardSourcePos);
    }

    private PathingCommand handleSealingHazard(LocalPlayer player, Level level) {
        BlockPos feet = player.blockPosition();
        stopMovementInputs();
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);

        if (this.hazardSourcePos == null
                || !level.getBlockState(this.hazardSourcePos).canBeReplaced()
                || this.sealFailedAttempts >= MAX_SEAL_ATTEMPTS) {
            boolean stillHazardous = this.hazardSourcePos != null && isLavaFluid(level, this.hazardSourcePos);
            this.hazardSourcePos = null;
            this.sealFailedAttempts = 0;
            this.state = State.TUNNELING;

            if (stillHazardous) {
                return handleLavaOrObstacleEncounter(player, level, feet);
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (player.getBoundingBox().intersects(new AABB(this.hazardSourcePos))) {
            this.hazardSourcePos = null;
            return executeRetreatBackward(player, level, feet,
                    this.primaryMiningDir != null ? this.primaryMiningDir : Direction.SOUTH);
        }

        if (!selectThrowawayBlock(player, this.hazardSourcePos)) {
            this.hazardSourcePos = null;
            this.state = State.TUNNELING;
            return handleLavaOrObstacleEncounter(player, level, feet);
        }

        if (this.placeCooldownTicks > 0) {
            this.placeCooldownTicks--;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        double reach = getEffectiveReach();
        Optional<PlacementCandidate> candidateOpt = findBestPlacementCandidate(player, level, this.hazardSourcePos,
                reach);

        if (candidateOpt.isEmpty()) {
            this.sealFailedAttempts++;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        PlacementCandidate candidate = candidateOpt.get();
        baritone.getLookBehavior().updateTarget(candidate.rotation(), true);

        Vec3 eyePos = player.getEyePosition(1.0F);
        Vec3 currentLook = RotationUtils.calcLookDirectionFromRotation(ctx.playerRotations());
        BlockHitResult liveHit = level.clip(new ClipContext(
                eyePos,
                eyePos.add(currentLook.scale(reach)),
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player));

        float yawDelta = Math.abs(Mth.wrapDegrees(player.getYRot() - candidate.rotation().getYaw()));
        float pitchDelta = Math.abs(player.getXRot() - candidate.rotation().getPitch());

        boolean exactMatch = liveHit.getType() == HitResult.Type.BLOCK
                && liveHit.getBlockPos().equals(candidate.supportPos())
                && liveHit.getDirection() == candidate.placeFace();

        if (exactMatch && yawDelta <= 8.0F && pitchDelta <= 8.0F && isHoldingValidPlaceableBlock(player)) {
            ctx.playerController().processRightClickBlock(player, ctx.world(), InteractionHand.MAIN_HAND, liveHit);
            player.swing(InteractionHand.MAIN_HAND);
            this.placeCooldownTicks = 3;
            this.sealFailedAttempts = 0;
            this.sealCooldownTicks = 3;
        } else {
            this.sealFailedAttempts++;
        }

        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private boolean hasDirectLavaAboveOrBehind(Level level, BlockPos orePos) {
        for (Direction dir : Direction.values()) {
            if (level.getFluidState(orePos.relative(dir)).is(FluidTags.LAVA)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasStableFloorSupport(Level level, BlockPos floorPos) {
        if (level == null || floorPos == null)
            return false;
        BlockState state = level.getBlockState(floorPos);
        if (!state.getFluidState().isEmpty()) {
            return false; // Ciecz w podłodze -> realne zagrożenie
        }
        if (state.getBlock() instanceof FallingBlock) {
            // Żwir/piasek jest stabilny, jeśli blok pod nim nie pozwala na zapadnięcie
            BlockState belowState = level.getBlockState(floorPos.below());
            return !FallingBlock.isFree(belowState);
        }
        if (state.isFaceSturdy(level, floorPos, Direction.UP)) {
            return true;
        }
        // Pojedyncze zagłębienie o 1 blok (np. po wykopanym diamencie w podłodze)
        // NIE jest przepaścią ani lawą, jeśli blok niżej daje stabilne oparcie!
        if (state.isAir() || state.canBeReplaced()) {
            BlockState below = level.getBlockState(floorPos.below());
            if (!below.getFluidState().isEmpty()) {
                return false;
            }
            return below.isFaceSturdy(level, floorPos.below(), Direction.UP);
        }
        return false;
    }

    private boolean isSolidSupport(Level level, BlockPos floorPos) {
        return hasStableFloorSupport(level, floorPos);
    }

    private boolean isPassable(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isAir() || state.getCollisionShape(level, pos).isEmpty();
    }

    private boolean isFallingBlock(BlockState state) {
        return state.getBlock() instanceof FallingBlock || state.is(BlockTags.SAND);
    }

    private boolean isFallingBlockSettling(Level level, BlockPos frontFeet, BlockPos frontHead) {
        AABB columnBox = new AABB(
                frontFeet.getX(), frontFeet.getY(), frontFeet.getZ(),
                frontFeet.getX() + 1.0, frontHead.getY() + 3.0, frontFeet.getZ() + 1.0
        );
        if (!level.getEntitiesOfClass(FallingBlockEntity.class, columnBox).isEmpty()) {
            return true;
        }

        if (level.getBlockState(frontHead).isAir() && isFallingBlock(level.getBlockState(frontHead.above()))) {
            return true;
        }
        if (level.getBlockState(frontFeet).isAir() && isFallingBlock(level.getBlockState(frontHead))) {
            return true;
        }

        return false;
    }

    public static boolean isBedrockOrUnbreakable(Level level, BlockPos pos) {
        if (pos == null || level == null)
            return true;
        if (pos.getY() <= -64)
            return true;
        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.BEDROCK))
            return true;
        if (state.getDestroySpeed(level, pos) < 0.0F)
            return true;
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
     * W normalnym kopaniu dziewiczej skały blok 2 kroki w przód ZAWSZE jest litą
     * skałą.
     * Jeśli 2 kolejne kroki w profilu 1x2 to pusta przestrzeń -> bot patrzy w stary
     * korytarz lub jaskinię!
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
     * Wybiera kierunek w litą skałę (lewo/prawo), eliminując puste przestrzenie i
     * zapętlenia.
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
        if (leftMineable)
            return left;
        if (rightMineable)
            return right;

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
            return false; // Blok został już postawiony
        }

        stopMovementInputs();
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);

        // 1. Ochrona przed postawieniem bloku we własnym hitboxie (cofnięcie na środek
        // defaultSupport)
        AABB targetBlockBox = new AABB(floorPos);
        if (player.getBoundingBox().inflate(-1.0E-4).intersects(targetBlockBox)) {
            if (defaultSupport != null && hasStableFloorSupport(level, defaultSupport)) {
                stepTowardsPointWithSneak(player, Vec3.atBottomCenterOf(defaultSupport.above()));
                return true;
            }
            return false;
        }

        // 2. Wybór i synchronizacja bloku budowlanego (z syncHeldItem)
        if (!selectThrowawayBlock(player, floorPos)) {
            return false;
        }

        // 3. Odczekanie na zatwierdzenie poprzedniego postawienia przez serwer (3-4
        // ticki)
        if (this.placeCooldownTicks > 0) {
            this.placeCooldownTicks--;
            return true;
        }

        double reach = getEffectiveReach();
        Optional<PlacementCandidate> candidateOpt = findBestPlacementCandidate(player, level, floorPos, reach);

        // 4. Gdy ścianka oparcia jest widoczna: celowanie Grim GCD -> weryfikacja clip
        // -> pakiet PPM
        if (candidateOpt.isPresent()) {
            PlacementCandidate candidate = candidateOpt.get();
            baritone.getLookBehavior().updateTarget(candidate.rotation(), true);

            Vec3 eyePos = player.getEyePosition(1.0F);
            Vec3 currentLook = RotationUtils.calcLookDirectionFromRotation(ctx.playerRotations());
            BlockHitResult liveHit = level.clip(new ClipContext(
                    eyePos,
                    eyePos.add(currentLook.scale(reach)),
                    ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE,
                    player));

            float yawDelta = Math.abs(Mth.wrapDegrees(player.getYRot() - candidate.rotation().getYaw()));
            float pitchDelta = Math.abs(player.getXRot() - candidate.rotation().getPitch());

            boolean exactMatch = liveHit.getType() == HitResult.Type.BLOCK
                    && liveHit.getBlockPos().equals(candidate.supportPos())
                    && liveHit.getDirection() == candidate.placeFace();

            if (exactMatch && yawDelta <= 8.0f && pitchDelta <= 8.0f && isHoldingValidPlaceableBlock(player)) {
                ctx.playerController().processRightClickBlock(player, ctx.world(), InteractionHand.MAIN_HAND, liveHit);
                player.swing(InteractionHand.MAIN_HAND);
                this.placeCooldownTicks = 4;
                this.sealCooldownTicks = 4;
            }
            return true;
        }

        // 5. Gdy górna ścianka defaultSupport zasłania przednią: podkradnięcie ze SNEAK
        // do krawędzi (offset 0.44)
        BlockPos supportBase = (defaultSupport != null && hasStableFloorSupport(level, defaultSupport))
                ? defaultSupport
                : player.blockPosition().below();

        if (!hasStableFloorSupport(level, supportBase)) {
            return false;
        }

        double dirX = Math.clamp(floorPos.getX() - supportBase.getX(), -1, 1);
        double dirZ = Math.clamp(floorPos.getZ() - supportBase.getZ(), -1, 1);
        if (dirX == 0 && dirZ == 0) {
            return false;
        }

        // Punkt na krawędzi bloku oparcia, z którego otwiera się kąt widzenia na
        // ściankę boczną
        Vec3 safeEdgePos = new Vec3(
                supportBase.getX() + 0.5 + dirX * 0.44,
                supportBase.getY() + 1.0,
                supportBase.getZ() + 0.5 + dirZ * 0.44);

        // Obracamy kamerę w dół w stronę docelowej luki i podchodzimy ze SNEAK
        Rotation lookDownAtGap = calcRotationFromVec3d(
                player.getEyePosition(1.0F),
                new Vec3(floorPos.getX() + 0.5, floorPos.getY() + 0.85, floorPos.getZ() + 0.5),
                player.getYRot());
        Rotation gcdLook = applyGrimGcd(player.getYRot(), player.getXRot(), lookDownAtGap.getYaw(),
                lookDownAtGap.getPitch());
        baritone.getLookBehavior().updateTarget(gcdLook, true);

        double distSqToEdge = (player.getX() - safeEdgePos.x) * (player.getX() - safeEdgePos.x)
                + (player.getZ() - safeEdgePos.z) * (player.getZ() - safeEdgePos.z);

        if (distSqToEdge > 0.008) {
            stepTowardsPointWithSneak(player, safeEdgePos);
            return true;
        }

        return false;
    }

    /**
     * Przemieszcza gracza ze SNEAK w kierunku punktu docelowego, dobierając klawisz
     * ruchu (W/S/A/D)
     * względem aktualnego kąta kamery, bez wymuszania gwałtownego obrotu o 180
     * stopni.
     */
    private void stepTowardsPointWithSneak(LocalPlayer player, Vec3 targetPoint) {
        double dx = targetPoint.x - player.getX();
        double dz = targetPoint.z - player.getZ();
        if (dx * dx + dz * dz < 0.002) {
            return;
        }

        float desiredYaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float relAngle = Mth.wrapDegrees(desiredYaw - player.getYRot());

        baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);

        if (Math.abs(relAngle) <= 65.0f) {
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        } else if (Math.abs(relAngle) >= 115.0f) {
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
        } else if (relAngle < 0) {
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, true);
        } else {
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, true);
        }
    }

    private void moveSafelyTowards(LocalPlayer player, Vec3 targetCenter) {
        Level level = player.level();
        BlockPos feet = player.blockPosition();

        // 1. Obsługa nawigacji w wodzie (pływanie, wynurzanie, kompensacja prądu)
        if (player.isInWater() || !level.getFluidState(feet).isEmpty()) {
            handleWaterNavigation(player, level, targetCenter);
            return;
        }

        // 2. Normalny ruch lądowy (Argon 4 & dj: lookAhead 2.5 bloku, stały pitch 26.8°, zero flag Simulation)
        Direction moveDir = tunnelDir != null ? tunnelDir : primaryMiningDir;
        if (moveDir == null) moveDir = Direction.NORTH;
        float targetYaw;

        // Oblicz odchylenie boczne od środka korytarza (Argon 4: zero micro-rotations under sprint)
        Direction leftDir = moveDir.getCounterClockWise();
        double dxCenter = targetCenter.x - player.getX();
        double dzCenter = targetCenter.z - player.getZ();
        double lateralError = Math.abs(dxCenter * leftDir.getStepX() + dzCenter * leftDir.getStepZ());

        if (lateralError < 0.20D) {
            // W osi tunelu -> sztywny kąt kardynalny. Zero rotacji bocznych podczas sprintu!
            targetYaw = moveDir.toYRot();
        } else {
            Vec3 dirVec = new Vec3(moveDir.getStepX(), moveDir.getStepY(), moveDir.getStepZ());
            Vec3 lookAheadTarget = targetCenter.add(dirVec.scale(2.5D));
            double dx = lookAheadTarget.x - player.getX();
            double dz = lookAheadTarget.z - player.getZ();
            targetYaw = (float) (Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        }

        Rotation gcdMoveRot = applyGrimGcd(player.getYRot(), player.getXRot(), targetYaw, TWO_BLOCK_SEAM_PITCH);
        baritone.getLookBehavior().updateTarget(gcdMoveRot, true);

        float yawDelta = Math.abs(Mth.wrapDegrees(player.getYRot() - gcdMoveRot.getYaw()));

        // ROZDZIELENIE OBROTU OD RUCHU POD GRIMAC:
        // Zatrzymaj ruch TYLKO przy faktycznym zakręcie 90° na węźle (> 30°), NIGDY przy mikrokorektach w tunelu (2.5° powodowało deadlock)!
        if (yawDelta > 30.0f) {
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
            baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, false);
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, false);
            return;
        }

        // Gdy kamera patrzy w korytarz, wciskamy MOVE_FORWARD
        baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);

        // KATEGORYCZNY ZAKAZ STRAFE (A/D) W TUNELU:
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, false);

        // GRIMAC FIX: Sprint dozwolony tylko gdy faktycznie idziemy prosto w osi, nie kolidujemy poziomo ze ścianą i food > 6
        boolean allowSprint = isFastPickaxeActive(player)
                && player.getFoodData().getFoodLevel() > 6
                && !player.horizontalCollision
                && lateralError < 0.20D
                && yawDelta < 10.0f;
        baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, allowSprint);

        // 3. Early Ascend Assist (skok dozwolony WYŁĄCZNIE przy fizycznym schodku w
        // górę +1Y i wolnym suficie)
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
        if (!player.onGround())
            return false;

        // Skacz TYLKO jeśli cel jest fizycznie powyżej stóp (stopień/schodki w górę
        // +1Y)
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

        // Nigdy nie skacz w tunelu 1x2 (uderzenie głową w sufit flaguje Simulation
        // 0.098)
        if (this.movementStuckTicks >= 20) {
            BlockPos ceiling = player.blockPosition().above(2);
            if (!level.getBlockState(ceiling).isAir() && !isBedrockOrUnbreakable(level, ceiling)) {
                mineBlockSafely(player, level, ceiling);
            }
            this.movementStuckTicks = 0;
        }
    }

    private void moveTowardsWithoutSprint(LocalPlayer player, Vec3 targetCenter) {
        baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
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

    private enum ToolEquipStatus {
        NO_CHANGE,
        SWITCHED,
        FAILED
    }

    private boolean isPickaxe(ItemStack stack) {
        if (stack.isEmpty())
            return false;
        return stack.is(ItemTags.PICKAXES) || stack.isCorrectToolForDrops(Blocks.STONE.defaultBlockState());
    }

    private boolean isToolUsable(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (!stack.isDamageableItem()) {
            return true;
        }
        return (stack.getMaxDamage() - stack.getDamageValue()) > MIN_TOOL_DURABILITY;
    }

    private boolean hasOnlyCriticallyDamagedTools(LocalPlayer player, BlockState state) {
        boolean foundDamaged = false;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.isCorrectToolForDrops(state)) {
                if (isToolUsable(stack)) {
                    return false; // Jest przynajmniej jedno sprawne narzędzie
                }
                foundDamaged = true;
            }
        }
        return foundDamaged;
    }

    /**
     * Sprawdza czy gracz ma jakikolwiek sprawny kilof
     * (drewniany/kamienny/żelazny/diamentowy/netheritowy).
     * Jeśli kilof jest w głównym ekwipunku (sloty 9..35), przenosi go na hotbar.
     */
    private boolean ensureUsablePickaxe(LocalPlayer player) {
        for (int i = 0; i < 9; i++) {
            if (isPickaxe(player.getInventory().getItem(i)) && isToolUsable(player.getInventory().getItem(i))) {
                return true;
            }
        }
        for (int i = 9; i < 36; i++) {
            if (isPickaxe(player.getInventory().getItem(i)) && isToolUsable(player.getInventory().getItem(i))) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.gameMode != null) {
                    int targetHotbarSlot = 0;
                    mc.gameMode.handleInventoryMouseClick(
                            player.inventoryMenu.containerId,
                            i,
                            targetHotbarSlot,
                            ClickType.SWAP,
                            player);
                    return true;
                }
            }
        }
        return false;
    }

    private ToolEquipStatus equipBestTool(LocalPlayer player, BlockState state) {
        if (state.isAir() || !state.getFluidState().isEmpty()) {
            return ToolEquipStatus.NO_CHANGE;
        }

        boolean requiresTool = state.requiresCorrectToolForDrops();
        int currentSlot = player.getInventory().selected;

        int bestHotbarSlot = findBestToolInRange(player, state, 0, 9, requiresTool);

        // Jeśli w hotbarze brakuje wymaganego narzędzia (np. kilofa na Deepslate),
        // poszukaj w głównym ekwipunku (9-35)
        if (bestHotbarSlot == -1 && requiresTool) {
            int invToolSlot = findBestToolInRange(player, state, 9, 36, true);
            if (invToolSlot != -1 && ctx.playerController() != null) {
                int targetHotbarSlot = 1; // Slot na kilof
                ctx.playerController().windowClick(
                        player.inventoryMenu.containerId,
                        invToolSlot,
                        targetHotbarSlot,
                        ClickType.SWAP,
                        player);
                bestHotbarSlot = targetHotbarSlot;
            }
        }

        if (bestHotbarSlot != -1) {
            if (bestHotbarSlot != currentSlot) {
                player.getInventory().selected = bestHotbarSlot;
                if (ctx.playerController() != null) {
                    ctx.playerController().syncHeldItem();
                }
                this.activeMiningTicks = 0;
                return ToolEquipStatus.SWITCHED;
            }
            return ToolEquipStatus.NO_CHANGE;
        }

        // Krytyczna ochrona sprzętu (Argon 4 & dj):
        // Zakaz kopania bloków wymagających narzędzia pustą ręką, łopatą lub zniszczonym kilofem!
        if (requiresTool) {
            baritone.getInputOverrideHandler().clearAllKeys();
            resetMiningState();
            if (hasOnlyCriticallyDamagedTools(player, state)) {
                logDirect("§c[Bypass] Wszystkie kilofy/narzędzia mają krytyczną wytrzymałość (<= " + MIN_TOOL_DURABILITY + ")! Zatrzymano dla ochrony sprzętu.");
            } else {
                logDirect("§c[Bypass] Brak odpowiedniego narzędzia do wykopania: " + state.getBlock().getName().getString() + "! Zatrzymano.");
            }
            onLostControl();
            return ToolEquipStatus.FAILED;
        }

        return ToolEquipStatus.NO_CHANGE;
    }

    private int findBestToolInRange(LocalPlayer player, BlockState state, int startSlot, int endSlot,
            boolean mustBeCorrectTool) {
        int bestSlot = -1;
        float bestScore = -1.0f;

        for (int i = startSlot; i < endSlot; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!isToolUsable(stack)) {
                continue;
            }

            boolean isCorrect = stack.isCorrectToolForDrops(state);
            if (mustBeCorrectTool && !isCorrect) {
                continue;
            }

            float baseSpeed = stack.getDestroySpeed(state);
            float score = baseSpeed;

            // Bonus Efficiency liczy się w MC tylko wtedy, gdy narzędzie faktycznie
            // przyspiesza kopanie danego bloku (baseSpeed > 1.0f)
            if (baseSpeed > 1.0f) {
                int effLevel = FastBreakHelper.getEfficiencyLevel(stack);
                if (effLevel > 0) {
                    score += (float) (effLevel * effLevel + 1);
                }
            }

            if (isCorrect) {
                score += 1000.0f; // Absolutny priorytet dla właściwego narzędzia (kilof na skały/rudy, łopata na
                                  // ziemię/żwir)
            }

            // Unikaj kopania bloków mieczem
            if (stack.getItem() instanceof net.minecraft.world.item.SwordItem) {
                score -= 500.0f;
            }

            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }

        return bestSlot;
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
     * Cel podejścia do rudy gwarantujący dystans oczu <= 2.65m (zawsze w zasięgu
     * reach <= 3.5m).
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
            // Dystans z oczu gracza (y + 1.62) do środka rudy (ox + 0.5, oy + 0.5, oz +
            // 0.5) <= 2.65m
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
            if (this == o)
                return true;
            if (!(o instanceof GoalStrictOreApproach that))
                return false;
            return ox == that.ox && oy == that.oy && oz == that.oz && Objects.equals(excludedFeet, that.excludedFeet);
        }

        @Override
        public int hashCode() {
            return Objects.hash(ox, oy, oz, excludedFeet);
        }

        @Override
        public String toString() {
            return String.format("GoalStrictOreApproach{ore=(%d,%d,%d), excludedCount=%d}", ox, oy, oz,
                    excludedFeet.size());
        }
    }

    @Override
    public double priority() {
        return 6.1D;
    }

    @Override
    public void onLostControl() {
        this.active = false;
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
        resetMiningState();
        baritone.getInputOverrideHandler().clearAllKeys();
        if (ctx.playerController() != null) {
            ctx.playerController().resetBlockRemoving();
        }
    }

    @Override
    public String displayName0() {
        return "BypassProcess [" + state.name() + " | Y=" + targetY + "]";
    }

    // Gettery dla HUD, Lua i zewnętrznych modułów
    public BlockPos getCurrentBreakingBlock() {
        return currentBreakingBlock;
    }

    public BlockPos getCurrentTargetBlock() {
        return activeOreTarget != null ? activeOreTarget : currentBreakingBlock;
    }

    public BypassConfig getConfig() {
        return config;
    }

    public State getState() {
        return state;
    }

    public String getPhase() {
        return state.name().toLowerCase(Locale.ROOT);
    }

    public Direction getTunnelDirection() {
        return tunnelDir;
    }

    public int getOresMined() {
        return oresMined;
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
