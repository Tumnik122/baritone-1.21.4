package baritone.bypass;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalTwoBlocks;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.input.Input;
import baritone.bypass.scripting.BypassLuaEngine;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.ContinuousBreakController;
import baritone.pathing.movement.MovementHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Główny proces wydobywczy #bypass pod kątem GrimAC.
 *
 * Realizuje:
 * 1. DESCENDING_STAIRCASE: bezpośrednie bezpieczne schodki w dół do Y=-55 (BEZ Baritone pathfinding).
 * 2. TUNNELING: bezpośrednie kopanie korytarzy 1x2 i branch mining (BEZ Baritone pathfinding).
 * 3. MINING_ORE: wydobycie pobliskich rud ze skanera (płynna rotacja, krótkie cele).
 * 4. RETREATING_MOB: natychmiastowe proste cofanie przed mobem (bez pathfindingu).
 * 5. PATHFINDING_RETURN: powrót do zapisanej pozycji z krótkimi segmentami (max 10-12 bloków).
 * 6. Safety check: emergency disconnect przy niskim zdrowiu i wrogim mobie.
 * 7. WATCHDOG: jeśli bot nie zmienił pozycji przez 3 sekundy -> force reset (przerwij wszystko,
 *    blacklist rudę jeśli był w MINING_ORE, zmień kierunek jeśli TUNNELING, wróć do kopania).
 * 8. BREAK_TIMEOUT = 5000ms: jeśli blok nie jest wykopany w 5s -> skip, następny blok.
 * 9. Podchodzenie do dropu po zniszczeniu rudy: sprawdza ItemEntity w promieniu 2.5 bloka,
 *    płynnie podchodzi w zasięg podniesienia (1.5 bloka) z 2-sekundowym timeoutem.
 * 10. Statystyki HUD: liczniki Diamond, Gold, całkowitych rud i czasu pracy.
 */
public class BypassProcess extends BaritoneProcessHelper implements IBaritoneProcess {

    public enum State {
        DESCENDING_STAIRCASE,   // schodki w dół — bez pathfinding, bezpośrednie kopanie
        ASCENDING_STAIRCASE,    // schodki w górę do Y=-55 — bezpośrednie kopanie
        TUNNELING,              // tunele — bez pathfinding, bezpośrednie kopanie
        MINING_ORE,             // kopie rudę znalezioną przez OreScanner
        COLLECTING_DROP,        // podchodzenie do upuszczonego itemu rudy
        RETREATING_MOB,         // cofanie przed mobem — proste cofanie, nie pathfinding
        PATHFINDING_RETURN,     // reconnect — pathfind do zapisanej pozycji (krótkie segmenty)
        DISCONNECTED,
        IDLE
    }

    private final BypassConfig config = new BypassConfig();
    private final List<String> targetOres = new ArrayList<>();
    private final Set<Block> targetOreBlocks = new HashSet<>();

    private boolean active = false;
    private final BypassStateMachine stateMachine;
    private int oresMined = 0;
    private int diamondCount = 0;
    private int goldCount = 0;
    private long startTime = 0L;

    private Direction tunnelDirection = Direction.NORTH;
    private int segmentLength = 0;
    private int targetSegmentLength = 20;

    // Odgałęzienie boczne (branch mining)
    private boolean inSideBranch = false;
    private Direction sideBranchDirection = Direction.EAST;
    private int sideBranchLength = 0;
    private int targetSideBranchLength = 7;
    private BlockPos branchStartPos = null;

    private BlockPos currentTargetBlock = null;
    private int varianceCooldownTicks = 0;

    // Watchdog pozycji (6 sekund) — resetuje tylko gdy bot stoi bezczynnie (nie niszczy bloku)
    private Vec3 lastWatchdogPos = null;
    private long lastPositionChangeTime = 0L;
    private static final long WATCHDOG_TIMEOUT_MS = 6000L;

    // The ore target and the obstruction currently being broken are separate.
    private final BypassBreakTracker breakTracker = new BypassBreakTracker();
    private BlockPos breakingBlock = null;
    private long breakStartTime = 0L;
    private BypassStaircaseStep staircaseStep;
    private BlockPos tunnelStepTarget;
    private BlockPos placementTarget;
    private int placementRetryTicks;
    private int oreApproachTicks;
    private boolean pathCalculationFailed;
    private boolean navigationActive;
    private int orphanDropScanTicks;
    private final Map<BlockPos, Long> deferredDrops = new HashMap<>();

    // Podchodzenie do dropu po zniszczeniu rudy
    private BlockPos pendingDropPos = null;
    private BypassDropTracker dropTracker;
    private final Set<String> targetDropItems = new HashSet<>();
    private int dropTargetId = -1;

    // Kolejka bloków żyły rudy (Vein Mining)
    private final LinkedList<BlockPos> activeVeinQueue = new LinkedList<>();
    private final Set<BlockPos> veinDiscovered = new HashSet<>();

    // Watchdog utknięcia na przeszkodach
    private BlockPos lastPlayerPos = null;
    private int stuckTicks = 0;

    // Cooldown po watchdog resecie z MINING_ORE — bot kopie tunel przez N ticków zanim znów szuka rud
    private int miningCooldownTicks = 0;
    private static final int MINING_COOLDOWN_AFTER_STUCK = 30; // ~1.5 sekundy — wystarczy żeby odsunąć się od problematycznej rudy

    // Watchdog timeoutu pathfindingu (dla PATHFINDING_RETURN)
    private boolean isPathfinding = false;
    private long pathStartTime = 0L;
    private static final long PATH_TIMEOUT_MS = 3000L;
    private BlockPos savedReconnectTarget = null;

    // ESP Player Detection (GrimAC-safe: cicha detekcja)
    private long lastPlayerWarningTime = 0L;

    // Silnik skryptowy Lua (hot-reloading, automatyzacje)
    private final BypassLuaEngine luaEngine;

    public BypassProcess(Baritone baritone) {
        super(baritone);
        this.luaEngine = new BypassLuaEngine(baritone, this);
        this.stateMachine = new BypassStateMachine(ctx, config, Map.of(
                State.DESCENDING_STAIRCASE, controller(State.DESCENDING_STAIRCASE, this::handleDescendingStaircase),
                State.ASCENDING_STAIRCASE, controller(State.ASCENDING_STAIRCASE, this::handleAscendingStaircase),
                State.TUNNELING, controller(State.TUNNELING, this::handleTunneling),
                State.MINING_ORE, controller(State.MINING_ORE, this::handleMiningOre),
                State.COLLECTING_DROP, controller(State.COLLECTING_DROP, this::handleCollectingDrop),
                State.RETREATING_MOB, controller(State.RETREATING_MOB,
                        () -> new PathingCommand(null, PathingCommandType.REQUEST_PAUSE)),
                State.PATHFINDING_RETURN, controller(State.PATHFINDING_RETURN, this::handlePathfindingReturn)
        ));
    }

    private IBypassSubController controller(State phase, Supplier<PathingCommand> tick) {
        return new IBypassSubController() {
            @Override
            public void onEnter(IPlayerContext context, BypassConfig settings) {
                RotationEngine.reset();
            }

            @Override
            public PathingCommand onTick(IPlayerContext context, BypassConfig settings) {
                return tick.get();
            }

            @Override
            public void onExit(IPlayerContext context) {
                stopNavigation();
                releaseActions();
                RotationEngine.reset();
                breakingBlock = null;
                breakStartTime = 0L;
                breakTracker.reset();
                staircaseStep = null;
                tunnelStepTarget = null;
                placementTarget = null;
                oreApproachTicks = 0;
                if (phase == State.COLLECTING_DROP) {
                    pendingDropPos = null;
                    dropTracker = null;
                    dropTargetId = -1;
                }
            }

            @Override
            public boolean isFinished() {
                return stateMachine.state() != phase;
            }

            @Override
            public boolean requiresImmediateHalt() {
                return !active || ctx.player() == null || ctx.world() == null;
            }
        };
    }

    public void startMining(List<String> ores) {
        resetSession();
        this.targetOres.clear();
        this.targetOres.addAll(ores);
        resolveTargetBlocks();

        if (ctx.player() != null) {
            this.tunnelDirection = ctx.player().getDirection();
        } else {
            this.tunnelDirection = Direction.NORTH;
        }

        this.segmentLength = 0;
        this.targetSegmentLength = config.minLengthBeforeTurn +
                ThreadLocalRandom.current().nextInt(config.maxLengthBeforeTurn - config.minLengthBeforeTurn + 1);
        this.inSideBranch = false;
        this.branchStartPos = null;
        this.currentTargetBlock = null;
        this.breakingBlock = null;
        this.breakStartTime = 0L;
        this.pendingDropPos = null;
        this.dropTracker = null;
        this.active = true;
        this.isPathfinding = false;
        this.stuckTicks = 0;
        this.lastPlayerPos = null;
        this.lastWatchdogPos = null;
        this.lastPositionChangeTime = System.currentTimeMillis();
        this.startTime = System.currentTimeMillis();

        int currentY = ctx.playerFeet() != null ? ctx.playerFeet().getY() : 64;
        if (currentY > config.yLevel) {
            stateMachine.transition(State.DESCENDING_STAIRCASE);
            logDirect(String.format("§a[Bypass] Uruchomiono schodzenie do Y=%d (obecny Y=%d, kierunek=%s)",
                    config.yLevel, currentY, tunnelDirection));
        } else if (currentY < config.yLevel) {
            stateMachine.transition(State.ASCENDING_STAIRCASE);
            logDirect(String.format("§a[Bypass] Gracz poniżej Y=%d (obecny Y=%d). Uruchomiono wchodzenie po schodkach w górę do Y=%d.",
                    config.yLevel, currentY, config.yLevel));
        } else {
            stateMachine.transition(State.TUNNELING);
            logDirect(String.format("§a[Bypass] Uruchomiono tunelowanie na Y=%d (kierunek=%s)",
                    currentY, tunnelDirection));
        }
    }

    public void resumeFromData(ReconnectData data) {
        if (data == null) return;
        resetSession();
        this.targetOres.clear();
        if (data.ores != null) {
            this.targetOres.addAll(data.ores);
        }
        resolveTargetBlocks();
        try {
            this.tunnelDirection = Direction.valueOf(data.tunnel_direction);
        } catch (Exception e) {
            this.tunnelDirection = Direction.NORTH;
        }
        if (tunnelDirection.getAxis().isVertical()) {
            this.tunnelDirection = Direction.NORTH;
        }
        this.oresMined = data.ores_mined;
        this.active = true;
        this.currentTargetBlock = null;
        this.breakingBlock = null;
        this.breakStartTime = 0L;
        this.pendingDropPos = null;
        this.dropTracker = null;
        this.stuckTicks = 0;
        this.lastWatchdogPos = null;
        this.lastPositionChangeTime = System.currentTimeMillis();
        this.startTime = System.currentTimeMillis();

        BlockPos savedPos = new BlockPos((int) Math.floor(data.x), (int) Math.floor(data.y), (int) Math.floor(data.z));
        BlockPos feet = ctx.playerFeet();
        if (feet != null && feet.distSqr(savedPos) > 25.0) { // > 5 bloków
            stateMachine.transition(State.PATHFINDING_RETURN);
            this.savedReconnectTarget = savedPos;
            this.isPathfinding = true;
            this.pathStartTime = System.currentTimeMillis();
            logDirect(String.format("§a[Bypass] Wznowiono! Powrót do pozycji %s (krótkie segmenty)...", savedPos));
        } else {
            int currentY = feet != null ? feet.getY() : (int) Math.floor(data.y);
            stateMachine.transition(travelState(currentY, config.yLevel));
            logDirect(String.format("§a[Bypass] Wznowiono sesję! Wykopano wcześniej: %d rud.", oresMined));
        }
    }

    /**
     * Zatrzymuje proces #bypass, anuluje wszystkie akcje, czyści kolejki i blacklistę, resetuje watchdog.
     */
    public void stop() {
        this.active = false;
        stateMachine.transition(State.IDLE);
        this.currentTargetBlock = null;
        this.breakingBlock = null;
        this.breakStartTime = 0L;
        this.pendingDropPos = null;
        this.dropTracker = null;
        this.breakTracker.reset();
        this.staircaseStep = null;
        this.tunnelStepTarget = null;
        this.placementTarget = null;
        this.isPathfinding = false;
        this.inSideBranch = false;
        this.branchStartPos = null;
        this.savedReconnectTarget = null;
        this.lastWatchdogPos = null;
        this.lastPositionChangeTime = System.currentTimeMillis();

        // Czyści blacklistę i liczniki prób
        OreScanner.clearBlacklist();

        // Reset rotacji
        RotationEngine.cancel();

        // Anuluj aktywne kopanie i klawisze
        baritone.getInputOverrideHandler().clearAllKeys();
        baritone.getInputOverrideHandler().getBlockBreakHelper().stopBreakingBlock();
        try {
            Minecraft.getInstance().options.keyAttack.setDown(false);
            Minecraft.getInstance().options.keyUse.setDown(false);
        } catch (Throwable ignored) {}

        // Anuluj pathfinding
        baritone.getCustomGoalProcess().setGoal(null);
        baritone.getPathingBehavior().cancelEverything();

        logDirect("§e[BYPASS] Stopped.");
    }

    public void cancel() {
        stop();
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (!active || ctx.player() == null || ctx.world() == null) {
            return null;
        }
        pathCalculationFailed = calcFailed;

        // Lua tick hook
        luaEngine.fireTick();
        if (!active || ctx.player() == null || ctx.world() == null) {
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        // Cooldown po watchdog resecie — blokuje powrót do MINING_ORE przez N ticków
        if (miningCooldownTicks > 0) {
            miningCooldownTicks--;
        }

        BetterBlockPos playerFeetPos = ctx.playerFeet();
        if (playerFeetPos == null) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        int currentY = playerFeetPos.getY();

        // 4. KRYTYCZNA OCHRONA: Cicha detekcja graczy ESP (GrimAC-safe: brak obracania celownika!)
        PlayerEspDetector.PlayerThreat primaryThreat = PlayerEspDetector.getPrimaryThreat(ctx, config);
        if (primaryThreat != null) {
            luaEngine.firePlayerDetected(primaryThreat.player().getScoreboardName(), primaryThreat.distance());
            boolean inCombat = config.checkCombatLog && CombatLogDetector.isPlayerInCombat();

            if (primaryThreat.isDangerous()) {
                luaEngine.fireDanger(String.format(Locale.ROOT, "Player threat: %s (dist: %.1fm, mace: %b)",
                        primaryThreat.player().getScoreboardName(), primaryThreat.distance(), primaryThreat.hasMace()));
                if (inCombat) {
                    if (System.currentTimeMillis() - lastPlayerWarningTime > 3000L) {
                        logDirect(String.format(Locale.ROOT,
                                "§c[Bypass ESP Alert] Gracz %s w pobliżu (%.1fm)! TRWA WALKA (CombatLog na BossBarze)! Odwrót zamiast rozłączenia!",
                                primaryThreat.player().getScoreboardName(), primaryThreat.distance()));
                        lastPlayerWarningTime = System.currentTimeMillis();
                    }
                    stateMachine.transition(State.RETREATING_MOB);
                } else {
                    logDirect(String.format(Locale.ROOT,
                            "§c[Bypass ESP] ZAGROŻENIE: Wykryto gracza %s w odległości %.1f bloków (Mace: %s)! Rozłączam bezpiecznie...",
                            primaryThreat.player().getScoreboardName(), primaryThreat.distance(), primaryThreat.hasMace() ? "TAK" : "NIE"));
                    Path savePath = baritone.getDirectory().resolve(config.saveFile);
                    ReconnectData.save(savePath, ctx.player(), new ArrayList<>(targetOres), tunnelDirection.name(), getState().name(), oresMined);
                    stateMachine.transition(State.DISCONNECTED);
                    stop();
                    if (ctx.player().connection != null && ctx.player().connection.getConnection() != null) {
                        ctx.player().connection.getConnection().disconnect(Component.literal("§c[Bypass ESP] Auto-disconnect: gracz w pobliżu (" + primaryThreat.player().getScoreboardName() + ")"));
                    } else {
                        ctx.world().disconnect();
                    }
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
            } else if (primaryThreat.distance() <= config.playerWarningRadius) {
                if (System.currentTimeMillis() - lastPlayerWarningTime > 10000L) {
                    logDirect(String.format(Locale.ROOT,
                            "§e[Bypass ESP] Uwaga: gracz %s w odległości %.1f bloków. (Cicha detekcja ESP - GrimAC safe)",
                            primaryThreat.player().getScoreboardName(), primaryThreat.distance()));
                    lastPlayerWarningTime = System.currentTimeMillis();
                }
            }
        }

        // 5. KRYTYCZNA OCHRONA (Moby i niskie HP)
        float health = ctx.player().getHealth();
        Entity nearestEnemy = ctx.entitiesStream()
                .filter(e -> e instanceof Enemy)
                .filter(e -> e.isAlive() && !e.isSpectator())
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(ctx.player())))
                .orElse(null);

        double enemyDist = nearestEnemy != null ? nearestEnemy.distanceTo(ctx.player()) : Double.MAX_VALUE;

        if (health <= config.healthThreshold && enemyDist <= config.mobDcDistance) {
            luaEngine.fireDanger(String.format(Locale.ROOT, "Low health (%.1f HP) and enemy mob nearby (%.1fm)", health, enemyDist));
            boolean inCombat = config.checkCombatLog && CombatLogDetector.isPlayerInCombat();
            if (inCombat) {
                logDirect(String.format(Locale.ROOT,
                        "§c[Bypass Alert] Niskie HP (%.1f), mob w pobliżu (%.1fm), ale trwa WALKA (BossBar)! Wycofuję się!",
                        health, enemyDist));
                stateMachine.transition(State.RETREATING_MOB);
                // Skip the generic mob-retreat re-check — we already committed to RETREATING.
                return stateMachine.onTick();
            } else {
                logDirect(String.format(Locale.ROOT,
                        "§c[Bypass] ZAGROŻENIE ŻYCIA: %.1f HP, wrogi mob w odległości %.1f bloków! Zapisuję sesję i natychmiast rozłączam...",
                        health, enemyDist));
                Path savePath = baritone.getDirectory().resolve(config.saveFile);
                ReconnectData.save(savePath, ctx.player(), new ArrayList<>(targetOres), tunnelDirection.name(), getState().name(), oresMined);
                stateMachine.transition(State.DISCONNECTED);
                stop();
                if (ctx.player().connection != null && ctx.player().connection.getConnection() != null) {
                    ctx.player().connection.getConnection().disconnect(Component.literal("§c[Bypass] Auto-disconnect: low health & mob nearby"));
                } else {
                    ctx.world().disconnect();
                }
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
        }


        // 5. Reakcja na moby (RETREATING_MOB / PAUSE)
        if (enemyDist <= config.mobRetreat) {
            stateMachine.transition(State.RETREATING_MOB);
        }

        if (getState() == State.RETREATING_MOB) {
            if (enemyDist > config.mobRetreat + 3.0) {
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
                currentY = ctx.playerFeet().getY();
                stateMachine.transition(travelState(currentY, config.yLevel));
            } else {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }

        if (enemyDist <= config.mobStop) {
            releaseActions();
            lastPositionChangeTime = System.currentTimeMillis();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // Observe the previous tick's interaction BEFORE choosing another ore or block.
        observeBrokenBlock();
        if (!active) {
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        // Collection has its own progress timer; a stationary pickup must not be
        // thrown away by the general movement watchdog.
        Vec3 currentPos = ctx.player().position();
        if (breakingBlock != null || getState() == State.COLLECTING_DROP) {
            lastPositionChangeTime = System.currentTimeMillis();
            lastWatchdogPos = currentPos;
        } else if (lastWatchdogPos == null || currentPos.distanceToSqr(lastWatchdogPos) > 0.04D) {
            lastWatchdogPos = currentPos;
            lastPositionChangeTime = System.currentTimeMillis();
        } else if (System.currentTimeMillis() - lastPositionChangeTime >= WATCHDOG_TIMEOUT_MS) {
            triggerWatchdogReset(currentPos);
            return pause();
        }

        // The drop phase must pass through threat checks on every tick. In particular,
        // do not return early to collect an item when a player or hostile mob arrives.
        if (getState() == State.COLLECTING_DROP) {
            return stateMachine.onTick();
        }

        // 6. Wariancja czasowa kopania (0-50ms)
        if (varianceCooldownTicks > 0) {
            varianceCooldownTicks--;
            releaseActions();
            return pause();
        }

        // 7. OBSŁUGA MASZYNY STANÓW
        return stateMachine.onTick();
    }

    /**
     * Zabezpieczenie WATCHDOG: force reset jeśli bot stoi w miejscu >= 3 sekundy.
     */
    private void triggerWatchdogReset(Vec3 currentPos) {
        logDirect("§c[Bypass Watchdog] Brak postępu przez 6s. Ponawiam bezpieczny ruch...");
        lastWatchdogPos = currentPos;
        lastPositionChangeTime = System.currentTimeMillis();

        baritone.getInputOverrideHandler().clearAllKeys();
        RotationEngine.reset();
        breakingBlock = null;
        breakStartTime = 0L;
        pendingDropPos = null;
        dropTracker = null;
        varianceCooldownTicks = 0;

        if (getState() == State.MINING_ORE || getState() == State.COLLECTING_DROP) {
            if (currentTargetBlock != null) {
                OreScanner.blacklist(currentTargetBlock);
                logDirect("§c[Bypass Watchdog] Zblacklistowano zablokowaną rudę: " + currentTargetBlock);
            }
            currentTargetBlock = null;
            // Cooldown — nie szukaj rud przez ~5 sekund, kopie tunel żeby uciec z obszaru
            miningCooldownTicks = MINING_COOLDOWN_AFTER_STUCK;
            logDirect("§e[Bypass Watchdog] Cooldown kopania rud: " + MINING_COOLDOWN_AFTER_STUCK + " ticków (~1.5s). Wracam na trasę...");
            int currentY = ctx.playerFeet() != null ? ctx.playerFeet().getY() : 64;
            stateMachine.transition(travelState(currentY, config.yLevel));
        } else if (getState() == State.TUNNELING) {
            turnTunnel();
            inSideBranch = false;
            branchStartPos = null;
            sideBranchLength = 0;
        } else if (getState() == State.DESCENDING_STAIRCASE) {
            tunnelDirection = tunnelDirection.getClockWise();
            logDirect("§6[Bypass Watchdog] Schodki w dół zablokowane. Zmiana kierunku na: " + tunnelDirection);
        } else if (getState() == State.ASCENDING_STAIRCASE) {
            tunnelDirection = tunnelDirection.getClockWise();
            logDirect("§6[Bypass Watchdog] Schodki w górę zablokowane. Zmiana kierunku na: " + tunnelDirection);
        } else if (getState() == State.PATHFINDING_RETURN) {
            isPathfinding = false;
            baritone.getCustomGoalProcess().setGoal(null);
            int currentY = ctx.playerFeet() != null ? ctx.playerFeet().getY() : 64;
            stateMachine.transition(travelState(currentY, config.yLevel));
        }

        logDirect("§a[Bypass Watchdog] Reset ukończony, wznawiam fazę: " + getState());
    }

    /**
     * Obsługa zniszczenia bloku rudy — inicjuje podchodzenie do dropu oraz
     * uruchamia algorytm Vein Mining (flood-fill BFS) w poszukiwaniu sąsiednich rud z żyły.
     */
    public void onOreBroken(BlockPos orePos, BlockState stateBeforeBreak) {
        if (!active || stateBeforeBreak == null) return;
        oresMined++;
        String oreName = BuiltInRegistries.BLOCK.getKey(stateBeforeBreak.getBlock()).getPath();
        if (oreName.contains("diamond")) diamondCount++;
        if (oreName.contains("gold")) goldCount++;
        logDirect(String.format("§a[Bypass] Wykopano rudę! Łącznie: %d (Diament: %d, Złoto: %d)",
                oresMined, diamondCount, goldCount));
        currentTargetBlock = null;

        // Odkrywanie sąsiednich bloków żyły (Vein Mining)
        discoverVein(orePos, stateBeforeBreak.getBlock());

        beginCollectingDrop(orePos, BypassDropTracker.itemsForOre(oreName));
        // Scripts may stop/restart the process; don't overwrite their decision afterwards.
        luaEngine.fireOreBroken(oreName, oresMined);
    }

    private void discoverVein(BlockPos origin, Block oreBlock) {
        if (!config.veinMining || origin == null || oreBlock == null || ctx.world() == null) return;
        Level world = ctx.world();
        Queue<BlockPos> queue = new ArrayDeque<>();
        queue.add(origin);
        veinDiscovered.add(origin);

        int maxSearch = config.veinMaxSize;
        while (!queue.isEmpty() && activeVeinQueue.size() < maxSearch) {
            BlockPos current = queue.poll();
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = current.relative(dir);
                if (veinDiscovered.contains(neighbor) || OreScanner.isBlacklisted(neighbor)) {
                    continue;
                }
                veinDiscovered.add(neighbor);

                BlockState state = world.getBlockState(neighbor);
                if (state.getBlock() == oreBlock || targetOreBlocks.contains(state.getBlock())) {
                    if (LiquidDetector.isSafeToMine(world, neighbor)) {
                        activeVeinQueue.add(neighbor.immutable());
                        queue.add(neighbor);
                        if (activeVeinQueue.size() >= maxSearch) break;
                    }
                }
            }
        }
        if (!activeVeinQueue.isEmpty()) {
            logDirect("§a[Bypass Vein] Wykryto żyłę! Dodano " + activeVeinQueue.size() + " sąsiednich rud do kolejki.");
        }
    }

    private BlockPos getNextVeinOre() {
        if (!config.veinMining || ctx.world() == null) return null;
        Level world = ctx.world();
        while (!activeVeinQueue.isEmpty()) {
            BlockPos candidate = activeVeinQueue.poll();
            if (candidate == null || OreScanner.isBlacklisted(candidate)) continue;
            BlockState state = world.getBlockState(candidate);
            if (targetOreBlocks.contains(state.getBlock()) && LiquidDetector.isSafeToMine(world, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private int getEffectiveOreRadius() {
        int base = config.oreRadius;
        if (!config.dynamicOreRadius || ctx.player() == null) return base;
        ItemStack mainHand = ctx.player().getMainHandItem();
        if (mainHand.isEmpty()) return base;
        try {
            int eff = 0;
            for (var entry : mainHand.getEnchantments().entrySet()) {
                String enchantName = entry.getKey().unwrapKey().map(k -> k.location().getPath()).orElse("");
                if (enchantName.contains("efficiency")) {
                    eff = entry.getIntValue();
                    break;
                }
            }
            return Math.min(base + (eff / 2), 8);
        } catch (Throwable t) {
            return base;
        }
    }

    private void beginCollectingDrop(BlockPos pos, Set<String> acceptedItems) {
        stateMachine.transition(State.COLLECTING_DROP);
        pendingDropPos = pos.immutable();
        dropTracker = new BypassDropTracker(acceptedItems);
        dropTargetId = -1;
        varianceCooldownTicks = 0;
        lastPositionChangeTime = System.currentTimeMillis();
        lastWatchdogPos = ctx.player().position();
    }

    private PathingCommand handleCollectingDrop() {
        if (pendingDropPos == null || dropTracker == null) {
            finishCollectingDrop();
            return pause();
        }
        List<ItemEntity> drops = ctx.world().getEntitiesOfClass(ItemEntity.class,
                new AABB(pendingDropPos).inflate(6.0D),
                entity -> entity.isAlive() && !entity.getItem().isEmpty()
                        && dropTracker.accepts(BuiltInRegistries.ITEM.getKey(entity.getItem().getItem()).getPath()));
        ItemEntity closest = drops.stream()
                .min(Comparator.<ItemEntity>comparingInt(e -> e.getId() == dropTargetId ? 0 : 1)
                        .thenComparingDouble(e -> e.distanceToSqr(ctx.player())))
                .orElse(null);
        BypassDropTracker.Result result = dropTracker.tick(closest == null ? -1 : closest.getId(),
                closest == null ? Double.POSITIVE_INFINITY : closest.distanceToSqr(ctx.player()));
        if (result != BypassDropTracker.Result.CONTINUE) {
            if (result == BypassDropTracker.Result.TIMED_OUT) {
                logDirect("§e[Bypass] Nie udało się dojść do dropu. Spróbuję ponownie po obejściu przeszkody.");
                deferDrop();
            }
            finishCollectingDrop();
            return pause();
        }
        if (closest == null) {
            // Wait for delayed spawn/removal packets, without walking along a stale path.
            stopNavigation();
            releaseActions();
            return pause();
        }
        dropTargetId = closest.getId();
        if (!LiquidDetector.isSafeToMine(ctx.world(), closest.blockPosition())) {
            deferDrop();
            finishCollectingDrop();
            return pause();
        }
        if (!InventoryCleaner.canFit(ctx, closest.getItem())) {
            boolean cleaned = InventoryCleaner.cleanIfFull(ctx, config);
            if (!cleaned && !InventoryCleaner.canFit(ctx, closest.getItem())) {
                logDirect("§c[Bypass] Brak miejsca na rudę. Zwolnij miejsce w ekwipunku; zatrzymuję kopanie.");
                stop();
                return pause();
            }
            releaseActions();
            return pause();
        }
        Goal goal = new GoalTwoBlocks(closest.blockPosition());
        if (pathCalculationFailed && navigationActive) {
            deferDrop();
            finishCollectingDrop();
            return pause();
        }
        if (goal.isInGoal(ctx.playerFeet())) {
            stopNavigation();
            // A goal cell is not proof of pickup: move to the item's horizontal
            // position and wait for the matching entity to disappear.
            walkTowards(closest.position(), false);
            return pause();
        }
        return navigateTo(goal);
    }

    private void deferDrop() {
        if (pendingDropPos != null) {
            deferredDrops.put(pendingDropPos, System.currentTimeMillis() + 15000L);
        }
    }

    private void finishCollectingDrop() {
        currentTargetBlock = null;
        lastPositionChangeTime = System.currentTimeMillis();
        BlockPos nextOre = getNextVeinOre();
        if (nextOre == null && miningCooldownTicks <= 0) {
            nextOre = findBestOreNearby(ctx.playerFeet(), getEffectiveOreRadius());
        }
        stateMachine.transition(nextOre == null ? travelState(ctx.playerFeet().getY(), config.yLevel) : State.MINING_ORE);
        currentTargetBlock = nextOre;
    }

    /** Revisit ore drops missed during path movement, without chasing discarded cobblestone. */
    private boolean collectNearbyOreDrop() {
        if (++orphanDropScanTicks < 10 || targetDropItems.isEmpty()) return false;
        orphanDropScanTicks = 0;
        long now = System.currentTimeMillis();
        deferredDrops.entrySet().removeIf(e -> e.getValue() <= now);
        ItemEntity drop = ctx.world().getEntitiesOfClass(ItemEntity.class,
                ctx.player().getBoundingBox().inflate(6.0D), e -> e.isAlive() && !e.getItem().isEmpty()
                        && targetDropItems.contains(BuiltInRegistries.ITEM.getKey(e.getItem().getItem()).getPath())
                        && deferredDrops.keySet().stream().noneMatch(pos -> pos.distSqr(e.blockPosition()) <= 36D))
                .stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(ctx.player()))).orElse(null);
        if (drop == null) return false;
        beginCollectingDrop(drop.blockPosition(), targetDropItems);
        return true;
    }

    private PathingCommand handleDescendingStaircase() {
        return handleStaircase(false);
    }

    private PathingCommand handleStaircase(boolean ascending) {
        BlockPos feet = ctx.playerFeet();
        releaseMovement();
        if (staircaseStep != null && staircaseStep.hasLanded(feet, ctx.player().onGround())) {
            staircaseStep = null;
            placementTarget = null;
            lastPositionChangeTime = System.currentTimeMillis();
        }
        if (staircaseStep == null) {
            if (!ctx.player().onGround()) return pause();
            State needed = travelState(feet.getY(), config.yLevel);
            if (needed != getState()) {
                stateMachine.transition(needed);
                return pause();
            }
            // Prefer an already usable staircase before excavating another one.
            for (Direction dir : List.of(tunnelDirection, tunnelDirection.getClockWise(),
                    tunnelDirection.getCounterClockWise(), tunnelDirection.getOpposite())) {
                BypassStaircaseStep candidate = BypassStaircaseStep.create(feet, dir, ascending);
                if (hasSupport(candidate.support()) && candidate.clearance().stream()
                        .allMatch(pos -> isPassable(ctx.world(), pos) && LiquidDetector.isSafeToMine(ctx.world(), pos))) {
                    tunnelDirection = dir;
                    break;
                }
            }
            staircaseStep = BypassStaircaseStep.create(feet, tunnelDirection, ascending);
        }
        // Don't recompute from airborne feet: that used to move the ceiling/step
        // one block up in the middle of a jump and cancel the jump itself.
        if (ctx.player().onGround() && !feet.equals(staircaseStep.source())
                && !feet.equals(staircaseStep.destination())) {
            staircaseStep = null;
            return pause();
        }
        for (BlockPos pos : staircaseStep.clearance()) {
            if (!LiquidDetector.isSafeToMine(ctx.world(), pos) || isUnbreakable(ctx.world(), pos)) {
                turnStaircase();
                return pause();
            }
        }
        for (BlockPos pos : staircaseStep.clearance()) {
            if (!isPassable(ctx.world(), pos)) {
                mineBlockDirect(pos);
                return pause();
            }
        }
        if (!hasSupport(staircaseStep.support())) {
            // No walking/jumping until a real, safe support block is observed.
            placeStepIfAir(staircaseStep.support());
            return pause();
        }
        walkTowards(Vec3.atBottomCenterOf(staircaseStep.destination()), ascending);
        return pause();
    }

    private void turnStaircase() {
        releaseActions();
        breakTracker.reset();
        breakingBlock = null;
        staircaseStep = null;
        placementTarget = null;
        tunnelDirection = tunnelDirection.getClockWise();
        RotationEngine.reset();
    }

    private PathingCommand handleTunneling() {
        Level world = ctx.world();
        BlockPos feet = ctx.playerFeet();
        if (ctx.player().onGround() && feet.getY() != config.yLevel) {
            stateMachine.transition(travelState(feet.getY(), config.yLevel));
            return pause();
        }
        if (collectNearbyOreDrop()) return pause();
        if (miningCooldownTicks <= 0 && breakingBlock == null) {
            BlockPos ore = getNextVeinOre();
            if (ore == null) {
                ore = findBestOreNearby(feet, getEffectiveOreRadius());
            }
            if (ore != null) {
                stateMachine.transition(State.MINING_ORE);
                currentTargetBlock = ore;
                return pause();
            }
        }
        if (inSideBranch && sideBranchLength >= targetSideBranchLength) {
            if (branchStartPos != null && !feet.equals(branchStartPos)) {
                if (pathCalculationFailed && navigationActive) {
                    turnTunnel();
                    stopNavigation();
                    return pause();
                }
                return navigateTo(new GoalBlock(branchStartPos));
            }
            stopNavigation();
            inSideBranch = false;
            branchStartPos = null;
            sideBranchLength = 0;
            tunnelStepTarget = null;
        }
        if (tunnelStepTarget != null && feet.equals(tunnelStepTarget) && ctx.player().onGround()) {
            tunnelStepTarget = null;
            if (inSideBranch) {
                sideBranchLength++;
                if (sideBranchLength >= targetSideBranchLength) return pause();
            } else {
                segmentLength++;
                checkBranchTriggers(feet);
            }
        }
        Direction dir = inSideBranch ? sideBranchDirection : tunnelDirection;
        if (tunnelStepTarget == null) tunnelStepTarget = feet.relative(dir).immutable();
        BlockPos front = tunnelStepTarget;
        if (!hasSupport(front.below()) || !LiquidDetector.isSafeToMine(world, front)
                || !LiquidDetector.isSafeToMine(world, front.above())
                || isUnbreakable(world, front) || isUnbreakable(world, front.above())) {
            releaseActions();
            turnTunnel();
            return pause();
        }
        // Head first prevents starting to walk underneath a low ceiling.
        for (BlockPos pos : List.of(front.above(), front)) {
            if (!isPassable(world, pos)) {
                mineBlockDirect(pos);
                return pause();
            }
        }
        walkTowards(Vec3.atBottomCenterOf(front), false);
        return pause();
    }

    private PathingCommand handleMiningOre() {
        BlockPos feet = ctx.playerFeet();
        // Keep the vein target while breaking an obstruction. Re-scanning every
        // frame used to replace the ore with the wall (or forget it after break).
        if (currentTargetBlock == null || OreScanner.isBlacklisted(currentTargetBlock)
                || !targetOreBlocks.contains(ctx.world().getBlockState(currentTargetBlock).getBlock())) {
            currentTargetBlock = getNextVeinOre();
            if (currentTargetBlock == null) {
                currentTargetBlock = findBestOreNearby(feet, getEffectiveOreRadius());
            }
            oreApproachTicks = 0;
        }
        if (currentTargetBlock == null) {
            stateMachine.transition(travelState(feet.getY(), config.yLevel));
            return pause();
        }
        BlockPos ore = currentTargetBlock;
        double reach = Math.min(config.reachLimit, ctx.playerController().getBlockReachDistance());
        if (RotationUtils.reachable(ctx, ore, reach).isPresent()) {
            if (!stopNavigation()) return pause();
            mineBlockDirect(ore);
            return pause();
        }
        Vec3 eye = ctx.player().getEyePosition();
        BlockHitResult hit = ctx.world().clip(new net.minecraft.world.level.ClipContext(eye, Vec3.atCenterOf(ore),
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, ctx.player()));
        if (hit.getType() == HitResult.Type.BLOCK) {
            BlockPos obstruction = hit.getBlockPos();
            if (!obstruction.equals(ore) && !obstruction.equals(feet.below())
                    && !isPassable(ctx.world(), obstruction)
                    && RotationUtils.reachable(ctx, obstruction, reach).isPresent()) {
                if (!stopNavigation()) return pause();
                mineBlockDirect(obstruction);
                return pause();
            }
        }
        if (++oreApproachTicks >= 200 || (pathCalculationFailed && navigationActive)) {
            failOreApproach();
            return pause();
        }
        Goal approach = new GoalGetToBlock(ore);
        if (approach.isInGoal(feet)) {
            // An adjacent but unreachable ore needs a different approach, not
            // three failed attempts recorded in three successive game ticks.
            if (oreApproachTicks >= 40) failOreApproach();
            releaseActions();
            return pause();
        }
        return navigateTo(approach);
    }

    private void failOreApproach() {
        if (currentTargetBlock != null) OreScanner.recordFailedAttempt(currentTargetBlock);
        currentTargetBlock = null;
        miningCooldownTicks = MINING_COOLDOWN_AFTER_STUCK;
        stateMachine.transition(travelState(ctx.playerFeet().getY(), config.yLevel));
    }

    private PathingCommand handlePathfindingReturn() {
        BlockPos feet = ctx.playerFeet();
        if (savedReconnectTarget == null || feet.distSqr(savedReconnectTarget) <= 1.0
                || (pathCalculationFailed && navigationActive)) {
            savedReconnectTarget = null;
            stateMachine.transition(travelState(feet.getY(), config.yLevel));
            return pause();
        }
        return navigateTo(new GoalBlock(savedReconnectTarget));
    }

    /** Start an interaction; its result is consumed on a subsequent process tick. */
    private void mineBlockDirect(BlockPos toBreak) {
        if (!stopNavigation()) return;
        releaseMovement();
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
        BlockState state = ctx.world().getBlockState(toBreak);
        if (state.isAir()) return;
        if (isUnbreakable(ctx.world(), toBreak) || !LiquidDetector.isSafeToMine(ctx.world(), toBreak)) {
            recoverFromBlockedTarget(toBreak);
            return;
        }
        if (!toBreak.equals(breakingBlock)) {
            releaseActions();
            breakingBlock = toBreak.immutable();
            breakStartTime = System.currentTimeMillis();
            breakTracker.watch(toBreak, state);
            RotationEngine.reset();
        }
        int previousSlot = ctx.player().getInventory().selected;
        if (!AutoToolManager.selectBestTool(ctx, toBreak, config)) {
            logDirect("§c[Bypass] Brak sprawnego narzędzia dającego drop z tego bloku. Włóż właściwy kilof do hotbara.");
            stop();
            return;
        }
        if (previousSlot != ctx.player().getInventory().selected) {
            releaseActions();
            ctx.playerController().syncHeldItem();
            RotationEngine.reset();
            return; // Synchronize the held item before attacking on a later tick.
        }
        long timeout = BypassBreakTracker.timeoutMillis(state.getDestroyProgress(ctx.player(), ctx.world(), toBreak));
        if (System.currentTimeMillis() - breakStartTime >= timeout) {
            logDirect("§e[Bypass] Brak postępu kopania bloku " + toBreak + ". Zmieniam podejście.");
            recoverFromBlockedTarget(toBreak);
            return;
        }
        double reach = Math.min(config.reachLimit, ctx.playerController().getBlockReachDistance());
        Optional<Rotation> reachable = RotationUtils.reachable(ctx, toBreak, reach);
        Rotation target = reachable.orElseGet(() -> RotationEngine.lookAt(ctx.player().getEyePosition(), Vec3.atCenterOf(toBreak)));
        RotationEngine.apply(ctx.player(), target, baritone.settings());
        HitResult fresh = RayTraceUtils.rayTraceTowards(ctx.player(), ctx.playerRotations(), reach);
        HitResult selected = ctx.objectMouseOver();
        boolean aimed = reachable.isPresent() && RotationEngine.isSettled(config.preRotationTicks)
                && fresh instanceof BlockHitResult f && f.getType() == HitResult.Type.BLOCK && f.getBlockPos().equals(toBreak)
                && selected instanceof BlockHitResult c && c.getType() == HitResult.Type.BLOCK && c.getBlockPos().equals(toBreak);
        // Never substitute an unrelated crosshair hit while rotating. In particular,
        // that could destroy the step we're supposed to jump onto.
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, aimed);
        if (aimed) {
            breakTracker.attacked();
            ContinuousBreakController.notifyBreaking(ctx, new BetterBlockPos(toBreak));
        }
    }

    private void observeBrokenBlock() {
        BypassBreakTracker.BrokenBlock broken = breakTracker.poll(ctx.world()::getBlockState);
        if (broken == null) {
            if (breakingBlock != null && !breakTracker.isTracking()) {
                breakingBlock = null;
                breakStartTime = 0L;
                releaseActions();
            }
            return;
        }
        releaseActions();
        breakingBlock = null;
        breakStartTime = 0L;
        oreApproachTicks = 0;
        lastPositionChangeTime = System.currentTimeMillis();
        OreScanner.resetAttempts(broken.pos());
        RotationEngine.notifyBlockBroken(broken.pos());
        if (targetOreBlocks.contains(broken.state().getBlock())) {
            onOreBroken(broken.pos(), broken.state());
        } else {
            applyBreakVariance();
        }
    }

    private void recoverFromBlockedTarget(BlockPos pos) {
        releaseActions();
        breakTracker.reset();
        breakingBlock = null;
        breakStartTime = 0L;
        if (getState() == State.MINING_ORE) {
            failOreApproach();
        } else if (getState() == State.TUNNELING) {
            turnTunnel();
        } else {
            turnStaircase();
        }
    }

    private BlockPos calculateShortGoal(BlockPos from, BlockPos to, int maxDistance) {
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist <= maxDistance) {
            return to;
        }
        double scale = maxDistance / dist;
        return new BlockPos(
                (int) Math.round(from.getX() + dx * scale),
                (int) Math.round(from.getY() + dy * scale),
                (int) Math.round(from.getZ() + dz * scale)
        );
    }

    private void checkBranchTriggers(BlockPos currentPos) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        if (!inSideBranch && rng.nextDouble() < config.branchChance) {
            inSideBranch = true;
            branchStartPos = currentPos;
            sideBranchDirection = rng.nextBoolean() ? tunnelDirection.getClockWise() : tunnelDirection.getCounterClockWise();
            sideBranchLength = 0;
            targetSideBranchLength = 5 + rng.nextInt(6);
            logDirect(String.format("§b[Bypass] Rozpoczynam odnogę boczną (15%% szansy) na długość %d w kierunku: %s",
                    targetSideBranchLength, sideBranchDirection));
            return;
        }

        if (segmentLength >= targetSegmentLength) {
            turnTunnel();
        }
    }

    private void turnTunnel() {
        releaseActions();
        breakTracker.reset();
        breakingBlock = null;
        tunnelStepTarget = null;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        tunnelDirection = rng.nextBoolean() ? tunnelDirection.getClockWise() : tunnelDirection.getCounterClockWise();
        segmentLength = 0;
        targetSegmentLength = config.minLengthBeforeTurn +
                rng.nextInt(config.maxLengthBeforeTurn - config.minLengthBeforeTurn + 1);
        inSideBranch = false;
        branchStartPos = null;
        logDirect(String.format("§a[Bypass] Skręt głównego tunelu o 90° na kierunek: %s (kolejny odcinek: %d bloków)",
                tunnelDirection, targetSegmentLength));
    }

    private void applyBreakVariance() {
        int varianceMs = ThreadLocalRandom.current().nextInt(51);
        int pingMs = 30;
        try {
            if (ctx.player() != null && ctx.player().connection != null) {
                PlayerInfo info = ctx.player().connection.getPlayerInfo(ctx.player().getUUID());
                if (info != null) {
                    pingMs = info.getLatency();
                }
            }
        } catch (Throwable ignored) {}

        double totalDelayMs = varianceMs + (pingMs / 20.0D);
        this.varianceCooldownTicks = (int) Math.round(totalDelayMs / 50.0D);
    }

    private boolean isPassable(Level world, BlockPos pos) {
        return world != null && pos != null && LiquidDetector.check(world, pos)
                && MovementHelper.canWalkThrough(ctx, new BetterBlockPos(pos));
    }

    private BlockPos findBestOreNearby(BlockPos center, int radius) {
        if (targetOreBlocks.isEmpty() || ctx.world() == null || center == null) return null;
        return OreScanner.findBestOre(ctx.world(), center, targetOreBlocks, radius, config);
    }

    private void resolveTargetBlocks() {
        targetOreBlocks.clear();
        targetDropItems.clear();
        for (String oreName : targetOres) {
            List<String> names = config.getOreBlockNames(oreName);
            for (String name : names) {
                targetDropItems.addAll(BypassDropTracker.itemsForOre(name));
                ResourceLocation loc = ResourceLocation.tryParse(name.contains(":") ? name : "minecraft:" + name);
                if (loc != null) {
                    BuiltInRegistries.BLOCK.getOptional(loc).ifPresent(targetOreBlocks::add);
                }
            }
        }
    }

    @Override
    public boolean isTemporary() {
        return false;
    }

    @Override
    public void onLostControl() {
        active = false;
        stateMachine.transition(State.IDLE);
        currentTargetBlock = null;
        breakingBlock = null;
        breakStartTime = 0L;
        pendingDropPos = null;
        dropTracker = null;
        breakTracker.reset();
        staircaseStep = null;
        tunnelStepTarget = null;
        placementTarget = null;
        navigationActive = false;
        RotationEngine.reset();
        releaseActions();
    }

    @Override
    public double priority() {
        return DEFAULT_PRIORITY + 2.0D;
    }

    @Override
    public String displayName0() {
        return String.format("BYPASS [%s] (%d mined)", getState().name(), oresMined);
    }

    /**
     * Schodki w górę do poziomu Y=-55: bezpośrednie kopanie schodków do góry bez Baritone pathfinding.
     */
    private PathingCommand handleAscendingStaircase() {
        return handleStaircase(true);
    }

    public static boolean isUnbreakable(Level world, BlockPos pos) {
        if (world == null || pos == null) return false;
        BlockState state = world.getBlockState(pos);
        return state.getDestroySpeed(world, pos) < 0 || state.is(Blocks.BEDROCK);
    }

    private int findBuildingBlockInHotbar() {
        if (ctx.player() == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = ctx.player().getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem bi) {
                Block b = bi.getBlock();
                if (b == Blocks.COBBLESTONE || b == Blocks.COBBLED_DEEPSLATE || b == Blocks.DIRT ||
                    b == Blocks.TUFF || b == Blocks.STONE || b == Blocks.DEEPSLATE || b == Blocks.NETHERRACK) {
                    return i;
                }
            }
        }
        return -1;
    }

    private boolean placeStepIfAir(BlockPos stepPos) {
        releaseActions();
        if (hasSupport(stepPos)) return true;
        if (!ctx.world().getBlockState(stepPos).canBeReplaced()
                || !LiquidDetector.isSafeToMine(ctx.world(), stepPos)) return false;
        if (!stepPos.equals(placementTarget)) {
            placementTarget = stepPos.immutable();
            placementRetryTicks = 0;
            RotationEngine.reset();
        }
        if (placementRetryTicks > 0) {
            placementRetryTicks--;
            return false;
        }
        int oldSlot = ctx.player().getInventory().selected;
        int slot = findBuildingBlockInHotbar();
        if (slot < 0) {
            // Reuse Baritone's inventory swapping rather than giving up when
            // the building blocks are in the backpack instead of the hotbar.
            boolean available = baritone.getInventoryBehavior().throwaway(true, BypassProcess::isBuildingBlock);
            if (!available) {
                logDirect("§c[Bypass] Brak bloków na stopień. Dodaj bruk/deepslate do hotbara; zatrzymuję się przed dziurą.");
                stop();
                return false;
            }
        } else {
            ctx.player().getInventory().selected = slot;
        }
        if (oldSlot != ctx.player().getInventory().selected) {
            ctx.playerController().syncHeldItem();
            RotationEngine.reset();
            return false;
        }
        InteractionHand hand = isBuildingBlock(ctx.player().getMainHandItem()) ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
        if (!isBuildingBlock(ctx.player().getItemInHand(hand))) return false; // wait for inventory swap
        double reach = Math.min(config.reachLimit, ctx.playerController().getBlockReachDistance());
        for (Direction direction : List.of(Direction.DOWN, Direction.NORTH, Direction.SOUTH,
                Direction.EAST, Direction.WEST, Direction.UP)) {
            BlockPos against = stepPos.relative(direction);
            if (!MovementHelper.canPlaceAgainst(ctx, against) || !LiquidDetector.check(ctx.world(), against)) continue;
            Direction face = direction.getOpposite();
            Vec3 faceCenter = Vec3.atCenterOf(against).add(face.getStepX() * 0.5D, face.getStepY() * 0.5D, face.getStepZ() * 0.5D);
            Rotation target = RotationEngine.lookAt(ctx.player().getEyePosition(), faceCenter);
            HitResult possible = RayTraceUtils.rayTraceTowards(ctx.player(), target, reach);
            if (!(possible instanceof BlockHitResult possibleBlock) || possible.getType() != HitResult.Type.BLOCK
                    || !possibleBlock.getBlockPos().equals(against)
                    || !possibleBlock.getBlockPos().relative(possibleBlock.getDirection()).equals(stepPos)) continue;
            RotationEngine.apply(ctx.player(), target, baritone.settings());
            if (!RotationEngine.isSettled(config.preRotationTicks)) return false;
            HitResult actual = RayTraceUtils.rayTraceTowards(ctx.player(), ctx.playerRotations(), reach);
            if (actual instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                    && hit.getBlockPos().equals(against) && hit.getBlockPos().relative(hit.getDirection()).equals(stepPos)) {
                ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), hand, hit);
                ctx.player().swing(hand);
                placementRetryTicks = 4;
            }
            // A sent click is not a placed block. The next tick verifies the world.
            return false;
        }
        return false;
    }

    private static boolean isBuildingBlock(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)) return false;
        Block b = item.getBlock();
        return b == Blocks.COBBLESTONE || b == Blocks.COBBLED_DEEPSLATE || b == Blocks.DIRT
                || b == Blocks.TUFF || b == Blocks.STONE || b == Blocks.DEEPSLATE || b == Blocks.NETHERRACK;
    }

    private boolean hasSupport(BlockPos pos) {
        return LiquidDetector.check(ctx.world(), pos) && MovementHelper.canWalkOn(ctx, pos);
    }

    static State travelState(int currentY, int targetY) {
        return currentY > targetY ? State.DESCENDING_STAIRCASE
                : currentY < targetY ? State.ASCENDING_STAIRCASE : State.TUNNELING;
    }

    private PathingCommand pause() {
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private PathingCommand navigateTo(Goal goal) {
        if (!navigationActive) {
            releaseActions();
            breakingBlock = null;
            breakTracker.reset();
            RotationEngine.cancel();
            navigationActive = true;
        }
        // Keep ownership in this process; starting CustomGoalProcess here would
        // compete with the bypass state machine for control of the same player.
        return new PathingCommand(goal, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    private boolean stopNavigation() {
        if (navigationActive) {
            if (!baritone.getPathingBehavior().cancelSegmentIfSafe()) return false;
            navigationActive = false;
        }
        return true;
    }

    private void releaseMovement() {
        for (Input input : List.of(Input.MOVE_FORWARD, Input.MOVE_BACK, Input.MOVE_LEFT,
                Input.MOVE_RIGHT, Input.JUMP, Input.SNEAK, Input.SPRINT)) {
            baritone.getInputOverrideHandler().setInputForceState(input, false);
        }
    }

    private void releaseActions() {
        baritone.getInputOverrideHandler().clearAllKeys();
        baritone.getInputOverrideHandler().getBlockBreakHelper().stopBreakingBlock();
    }

    private void walkTowards(Vec3 destination, boolean ascend) {
        if (!stopNavigation()) return;
        releaseActions();
        Vec3 delta = destination.subtract(ctx.player().position());
        double distance = delta.x * delta.x + delta.z * delta.z;
        if (distance < 0.04D) return;
        float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        RotationEngine.apply(ctx.player(), new Rotation(yaw, ascend ? -10f : 0f), baritone.settings());
        if (!RotationEngine.isSettled(config.preRotationTicks)) return;
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        // Don't jump too early from the far side of the previous step.
        baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, ascend && distance <= 1.44D);
    }

    private void resetSession() {
        stateMachine.transition(State.IDLE);
        stopNavigation();
        releaseActions();
        breakTracker.reset();
        breakingBlock = null;
        currentTargetBlock = null;
        pendingDropPos = null;
        dropTracker = null;
        staircaseStep = null;
        tunnelStepTarget = null;
        placementTarget = null;
        savedReconnectTarget = null;
        inSideBranch = false;
        branchStartPos = null;
        segmentLength = 0;
        sideBranchLength = 0;
        oresMined = 0;
        diamondCount = 0;
        goldCount = 0;
        miningCooldownTicks = 0;
        varianceCooldownTicks = 0;
        orphanDropScanTicks = 0;
        deferredDrops.clear();
        activeVeinQueue.clear();
        veinDiscovered.clear();
        OreScanner.clearBlacklist();
        RotationEngine.reset();
    }

    // Gettery do statusu i HUD
    public BypassConfig getConfig() { return config; }
    public List<String> getTargetOres() { return Collections.unmodifiableList(targetOres); }
    public int getOresMined() { return oresMined; }
    public int getDiamondCount() { return diamondCount; }
    public int getGoldCount() { return goldCount; }
    public long getStartTime() { return startTime; }
    public String getPhase() { return getState().name().toLowerCase(Locale.ROOT); }
    public State getState() { return stateMachine.state(); }
    public Direction getTunnelDirection() { return tunnelDirection; }
    public BypassLuaEngine getLuaEngine() { return luaEngine; }
    public IPlayerContext getPlayerContext() { return ctx; }
}
