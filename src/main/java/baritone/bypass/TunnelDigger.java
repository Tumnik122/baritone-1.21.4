package baritone.bypass;

import baritone.Baritone;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalStrictDirection;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.utils.ContinuousBreakController;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 3. TunnelDigger.java
 * Osobny moduł na kopanie tuneli i odnóg (Branch Mining):
 *
 * - Tunel 1x2 (szerokość 1, wysokość 2).
 * - Kopie prosto przed sobą w wyznaczonym kierunku.
 * - Licznik bloków: co losowe 15-30 bloków -> skręca o 90° (losowo lewo / prawo).
 * - 15% szans na każdą iterację -> kopie odgałęzienie boczne 5-10 bloków w bok.
 * - Przed każdym blokiem: LiquidDetector + MobDetector check.
 * - Płynna rotacja przez RotationEngine na każdy blok (min. 2 ticki przed LPM).
 * - Po każdym wykopanym bloku wywołuje OreScanner.scanArea().
 * - Kompensacja pingu i losowa wariancja czasu kopania (0-50ms).
 */
public class TunnelDigger {

    private final Baritone baritone;
    private final IPlayerContext ctx;
    private final BypassConfig config;

    private Direction mainDirection = Direction.NORTH;
    private int blocksInSegment = 0;
    private int targetSegmentBlocks = 20;

    // Odgałęzienie boczne (branch)
    private boolean inSideBranch = false;
    private Direction sideBranchDirection = Direction.EAST;
    private int sideBranchLength = 0;
    private int targetSideBranchLength = 7;
    private BlockPos branchStartPos = null;

    private BlockPos currentTarget = null;
    private int varianceCooldownTicks = 0;

    public TunnelDigger(Baritone baritone, BypassConfig config) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
        this.config = config;
    }

    public void start(Direction initialDirection) {
        this.mainDirection = initialDirection != null ? initialDirection : Direction.NORTH;
        this.blocksInSegment = 0;
        this.targetSegmentBlocks = config.minLengthBeforeTurn +
                ThreadLocalRandom.current().nextInt(config.maxLengthBeforeTurn - config.minLengthBeforeTurn + 1);
        this.inSideBranch = false;
        this.branchStartPos = null;
        this.currentTarget = null;
        this.varianceCooldownTicks = 0;
    }

    public Direction getActiveDirection() {
        return inSideBranch ? sideBranchDirection : mainDirection;
    }

    public boolean isInSideBranch() {
        return inSideBranch;
    }

    /**
     * Główny tick tunelowania.
     * Zwraca ewentualną listę znalezionych rud z OreScanner do obsłużenia przez BypassProcess.
     */
    public PathingCommand onTick(Set<Block> targetOreBlocks, List<BlockPos> outFoundOres) {
        if (ctx.player() == null || ctx.world() == null) return null;
        Level world = ctx.world();
        BlockPos feet = ctx.playerFeet();
        Direction activeDir = getActiveDirection();

        // 1. Sprawdzenie strefy mobów (MobDetector)
        if (MobDetector.isMobInRetreatRange(ctx, config.mobRetreat)) {
            BlockPos retreatPos = feet.relative(activeDir.getOpposite(), 3);
            return new PathingCommand(new GoalBlock(retreatPos), PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH);
        }
        if (MobDetector.isMobInStopRange(ctx, config.mobStop)) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // Opóźnienie / wariancja czasowa łamania bloków (0-50ms + kompensacja pingu)
        if (varianceCooldownTicks > 0) {
            varianceCooldownTicks--;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // Jeśli skończyliśmy odnogę boczną, wracamy do głównego tunelu
        if (inSideBranch && sideBranchLength >= targetSideBranchLength) {
            if (branchStartPos != null && feet.distSqr(branchStartPos) > 1.0D) {
                return new PathingCommand(new GoalBlock(branchStartPos), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
            } else {
                inSideBranch = false;
                branchStartPos = null;
                sideBranchLength = 0;
                Helper.HELPER.logDirect("§a[TunnelDigger] Zakończono odnogę boczną. Powrót do głównego tunelu (" + mainDirection + ").");
            }
        }

        BlockPos frontFeet = feet.relative(activeDir);
        BlockPos frontHead = frontFeet.above();

        // 2. Weryfikacja bezpieczeństwa cieczy (LiquidDetector)
        if (!LiquidDetector.isSafeToMine(world, frontFeet) || !LiquidDetector.isSafeToMine(world, frontHead)) {
            Helper.HELPER.logDirect("§6[TunnelDigger] Wykryto wodę lub lawę w korytarzu. Zmiana kierunku o 90°...");
            turnTunnel();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // 3. Kopanie tunelu 1x2 (głowa, potem stopy)
        BlockPos toBreak = null;
        if (!isPassable(world, frontHead)) {
            toBreak = frontHead;
        } else if (!isPassable(world, frontFeet)) {
            toBreak = frontFeet;
        }

        if (toBreak != null) {
            currentTarget = toBreak;
            Vec3 targetCenter = Vec3.atCenterOf(toBreak);
            Rotation rot = RotationEngine.lookAt(ctx.player().getEyePosition(), targetCenter);
            RotationEngine.apply(ctx.player(), rot, baritone.settings());

            // Min. 2 ticki stabilizacji przed kliknięciem
            if (RotationEngine.isSettled(config.preRotationTicks)) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                ContinuousBreakController.notifyBreaking(ctx, new BetterBlockPos(toBreak));

                if (world.getBlockState(toBreak).isAir()) {
                    RotationEngine.notifyBlockBroken(toBreak);
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
                    currentTarget = null;

                    // Obliczenie wariancji czasu kopania (0-50ms) i kompensacja pingu
                    applyBreakTimeVariance();

                    // Skanowanie rud po KAŻDYM wykopanym bloku (OreScanner)
                    List<BlockPos> scanned = OreScanner.scanArea(world, feet, targetOreBlocks,
                            ctx.player().getEyePosition(), config.reachLimit, config);
                    if (!scanned.isEmpty() && outFoundOres != null) {
                        outFoundOres.addAll(scanned);
                    }

                    // Aktualizacja liczników
                    if (inSideBranch) {
                        sideBranchLength++;
                    } else {
                        blocksInSegment++;
                        checkBranchTriggers(feet);
                    }
                }
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // Tunel czysty przed nami - krok w przód
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
        return new PathingCommand(new GoalStrictDirection(feet, activeDir), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    private void checkBranchTriggers(BlockPos currentPos) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // 15% szansy na rozpoczęcie bocznego odgałęzienia (5-10 bloków)
        if (!inSideBranch && rng.nextDouble() < 0.15D) {
            inSideBranch = true;
            branchStartPos = currentPos;
            sideBranchDirection = rng.nextBoolean() ? mainDirection.getClockWise() : mainDirection.getCounterClockWise();
            sideBranchLength = 0;
            targetSideBranchLength = 5 + rng.nextInt(6); // 5-10 bloków
            Helper.HELPER.logDirect(String.format("§b[TunnelDigger] Rozpoczynam odnogę boczną (15%% szansy) na długość %d w kierunku: %s",
                    targetSideBranchLength, sideBranchDirection));
            return;
        }

        // Skręt głównego tunelu co 15-30 bloków
        if (blocksInSegment >= targetSegmentBlocks) {
            turnTunnel();
        }
    }

    private void turnTunnel() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        mainDirection = rng.nextBoolean() ? mainDirection.getClockWise() : mainDirection.getCounterClockWise();
        blocksInSegment = 0;
        targetSegmentBlocks = config.minLengthBeforeTurn +
                rng.nextInt(config.maxLengthBeforeTurn - config.minLengthBeforeTurn + 1);
        Helper.HELPER.logDirect(String.format("§a[TunnelDigger] Skręt głównego tunelu o 90° na kierunek: %s (kolejny odcinek: %d bloków)",
                mainDirection, targetSegmentBlocks));
    }

    private void applyBreakTimeVariance() {
        // Losowy offset 0-50ms (0 do 1 ticka)
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
        // Konwersja ms -> ticki (50ms = 1 tick)
        this.varianceCooldownTicks = (int) Math.round(totalDelayMs / 50.0D);
    }

    private boolean isPassable(Level world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isAir() || state.canBeReplaced();
    }
}
