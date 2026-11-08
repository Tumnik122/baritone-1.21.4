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
import baritone.api.utils.RotationUtils;
import baritone.api.utils.ObstructionHelper;
import baritone.api.utils.input.Input;
import baritone.utils.ContinuousBreakController;
import baritone.utils.FastBreakHelper;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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

        // 1. Sprawdzenie strefy mobów (MobDetector) — bezpośrednie proste cofanie
        if (MobDetector.isMobInRetreatRange(ctx, config.mobRetreat)) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (MobDetector.isMobInStopRange(ctx, config.mobStop)) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // Opóźnienie / wariancja czasowa łamania bloków (pomijane w trybie FastBreak)
        boolean fastBreak = FastBreakHelper.isFastBreakActive(ctx);
        if (fastBreak) {
            varianceCooldownTicks = 0;
        } else if (varianceCooldownTicks > 0) {
            varianceCooldownTicks--;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // Jeśli skończyliśmy odnogę boczną, wracamy do głównego tunelu (ruch bezpośredni)
        if (inSideBranch && sideBranchLength >= targetSideBranchLength) {
            if (branchStartPos != null && feet.distSqr(branchStartPos) > 1.0D) {
                Vec3 toStart = Vec3.atCenterOf(branchStartPos).subtract(ctx.player().position());
                float yaw = (float) Math.toDegrees(Math.atan2(-toStart.x, toStart.z));
                RotationEngine.apply(ctx.player(), new Rotation(yaw, 0f), baritone.settings());
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            } else {
                inSideBranch = false;
                branchStartPos = null;
                sideBranchLength = 0;
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
                Helper.HELPER.logDirect("§a[TunnelDigger] Zakończono odnogę boczną. Powrót do głównego tunelu (" + mainDirection + ").");
            }
        }

        BlockPos frontFeet = feet.relative(activeDir);
        BlockPos frontHead = frontFeet.above();
        BlockPos frontFloor = frontFeet.below();

        // 2. Rygorystyczna weryfikacja bezpieczeństwa cieczy i basenów lawowych (LiquidDetector)
        boolean directLiquidBreach = LiquidDetector.isLiquid(world, frontHead) || LiquidDetector.isLiquid(world, frontFeet);
        if (directLiquidBreach) {
            Helper.HELPER.logDirect("§c[TunnelDigger] WYCIEK CIECZY W TUNELU! Natychmiastowe zatkanie i odwrót...");
            return handleLiquidBreachEmergency(world, feet, frontHead, frontFeet, activeDir);
        }

        boolean headSafe = LiquidDetector.isSafeToMine(world, frontHead, activeDir, feet.getY());
        boolean feetSafe = LiquidDetector.isSafeToMine(world, frontFeet, activeDir, feet.getY());
        boolean lavaPitAhead = LiquidDetector.isLavaPitBelow(world, frontFloor, 8);

        if (!headSafe || !feetSafe || lavaPitAhead) {
            Helper.HELPER.logDirect("§6[TunnelDigger] Zagrożenie lawą/płynem w korytarzu. Zatrzymanie i bezpieczny skręt...");
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
            baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            if (ctx.player().onGround()) {
                baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            }
            rotateToSafeCorridor(world, feet, activeDir);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // 3. Kopanie tunelu 1x2 (głowa, potem stopy - zapobiega duszeniu przez żwir/piasek)
        BlockPos toBreak = null;
        if (!isPassable(world, frontHead)) {
            toBreak = frontHead;
        } else if (!isPassable(world, frontFeet)) {
            toBreak = frontFeet;
        }

        boolean floorSolid = LiquidDetector.isSafeToStep(world, frontFeet);

        // Gdy bot kopie szybkim kilofem:
        // Trzymamy celownik zablokowany prosto w korytarzu (yaw = activeDir, pitch = 11.5°),
        // kopiemy ciągle, ale sprintujemy TYLKO jeśli podłoga przed nami jest pewna i bezpieczna!
        boolean fastPickaxe = FastBreakHelper.isFastPickaxe(ctx);
        if (fastPickaxe) {
            double distH = Math.hypot(ctx.player().getX() - (frontFeet.getX() + 0.5D), ctx.player().getZ() - (frontFeet.getZ() + 0.5D));
            float pitch = 11.5f;
            if (!isPassable(world, frontFeet) && distH < 0.95) {
                pitch = 26.0f; // Szybkie rozbicie bloku tuż pod nogami
            }
            Rotation straightRot = new Rotation(activeDir.toYRot(), pitch);
            RotationEngine.apply(ctx.player(), straightRot, baritone.settings(), true);

            // Sprint dozwolony wyłącznie gdy podłoga pod stopami jest w 100% lita i brak lawy
            BlockPos nextFeet = frontFeet.relative(activeDir);
            boolean allowSprint = floorSolid && LiquidDetector.isSafeToStep(world, nextFeet);

            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, floorSolid && distH > 0.82);
            baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, allowSprint);
            if (!floorSolid) {
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
                baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
                if (ctx.player().onGround()) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
                }
            } else {
                baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
            }
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);

            if (toBreak != null) {
                ContinuousBreakController.notifyBreaking(ctx, new BetterBlockPos(toBreak));
                if (world.getBlockState(toBreak).isAir()) {
                    RotationEngine.notifyBlockBroken(toBreak);
                    currentTarget = null;
                    varianceCooldownTicks = 0;

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

        if (toBreak != null) {
            double distH = Math.hypot(ctx.player().getX() - (frontFeet.getX() + 0.5D), ctx.player().getZ() - (frontFeet.getZ() + 0.5D));
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, distH > 0.82 && floorSolid);
            baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
            if (!floorSolid && distH <= 0.82 && ctx.player().onGround()) {
                baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            }
            if (!toBreak.equals(currentTarget)) {
                currentTarget = toBreak;
            }
            Vec3 targetCenter = Vec3.atCenterOf(toBreak);
            Rotation rot = RotationEngine.lookAt(ctx.player().getEyePosition(), targetCenter);
            RotationEngine.apply(ctx.player(), rot, baritone.settings(), true);

            // W trybie FastBreak brak czekania na stabilizację (0 ticków)
            int requiredSettle = fastBreak ? 0 : config.preRotationTicks;
            if (RotationEngine.isSettled(requiredSettle)) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                ContinuousBreakController.notifyBreaking(ctx, new BetterBlockPos(toBreak));

                if (world.getBlockState(toBreak).isAir()) {
                    RotationEngine.notifyBlockBroken(toBreak);
                    boolean instaOrCont = fastBreak || ContinuousBreakController.shouldHoldThrough(ctx, new BetterBlockPos(toBreak.equals(frontHead) ? frontFeet : frontHead), null);
                    if (!instaOrCont) {
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
                    }
                    currentTarget = null;

                    // Obliczenie wariancji czasu kopania (pomijane w FastBreak i continuous break)
                    if (!instaOrCont) {
                        applyBreakTimeVariance();
                    } else {
                        varianceCooldownTicks = 0;
                    }

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

        // 4. Tunel z przodu wykopany — WERYFIKACJA PODŁOGI PRZED POSTAWIENIEM KROKU!
        if (!floorSolid) {
            // Brak podłogi pod frontFeet (jaskinia, wąwóz, dół z lawą): kategoryczny zakaz ruchu w przód!
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
            baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
            if (ctx.player().onGround()) {
                baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            }
            Helper.HELPER.logDirect("§6[TunnelDigger] Brak stabilnej podłogi przed nami! Bezpieczny skręt tunelu...");
            rotateToSafeCorridor(world, feet, activeDir);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // Korytarz i podłoga zweryfikowane — bezpieczny ruch w przód
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
        baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
        Rotation walkRot = new Rotation(activeDir.toYRot(), 0.0f);
        RotationEngine.apply(ctx.player(), walkRot, baritone.settings());
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);

        // Sprint dozwolony wyłącznie gdy 2 bloki przed nami również są w 100% bezpieczne
        BlockPos twoAhead = frontFeet.relative(activeDir);
        boolean twoAheadSafe = LiquidDetector.isSafeToStep(world, twoAhead);
        baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, fastPickaxe && twoAheadSafe);

        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private void rotateToSafeCorridor(Level world, BlockPos feet, Direction currentDir) {
        Direction left = currentDir.getCounterClockWise();
        Direction right = currentDir.getClockWise();
        int leftScore = evaluateCorridorSafety(world, feet, left, 6);
        int rightScore = evaluateCorridorSafety(world, feet, right, 6);

        Direction chosen = (leftScore >= rightScore && leftScore > 0)
                ? left
                : (rightScore > 0 ? right : currentDir.getOpposite());

        if (inSideBranch) {
            sideBranchDirection = chosen;
        } else {
            mainDirection = chosen;
            blocksInSegment = 0;
            targetSegmentBlocks = config.minLengthBeforeTurn +
                    ThreadLocalRandom.current().nextInt(config.maxLengthBeforeTurn - config.minLengthBeforeTurn + 1);
        }
        Helper.HELPER.logDirect(String.format("§a[TunnelDigger] Wybrano bezpieczny korytarz: %s (lewo: %d pkt, prawo: %d pkt)",
                chosen, leftScore, rightScore));
    }

    private static int evaluateCorridorSafety(Level world, BlockPos origin, Direction dir, int range) {
        int score = 0;
        for (int step = 1; step <= range; step++) {
            BlockPos targetFeet = origin.relative(dir, step);
            BlockPos targetHead = targetFeet.above();
            BlockPos targetFloor = targetFeet.below();

            if (!LiquidDetector.isSafeToMine(world, targetHead, dir, origin.getY())
                    || !LiquidDetector.isSafeToMine(world, targetFeet, dir, origin.getY())
                    || LiquidDetector.isLavaPitBelow(world, targetFloor, 8)) {
                return score;
            }
            if (LiquidDetector.isSafeToStep(world, targetFeet)) {
                score += 2;
            } else {
                score -= 1;
            }
        }
        return score;
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
        if (ctx.world() != null && ctx.player() != null) {
            rotateToSafeCorridor(ctx.world(), ctx.playerFeet(), mainDirection);
        } else {
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            mainDirection = rng.nextBoolean() ? mainDirection.getClockWise() : mainDirection.getCounterClockWise();
            blocksInSegment = 0;
            targetSegmentBlocks = config.minLengthBeforeTurn +
                    rng.nextInt(config.maxLengthBeforeTurn - config.minLengthBeforeTurn + 1);
        }
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

    private PathingCommand handleLiquidBreachEmergency(Level world, BlockPos feet, BlockPos frontHead, BlockPos frontFeet, Direction activeDir) {
        var ioh = baritone.getInputOverrideHandler();
        ioh.setInputForceState(Input.MOVE_FORWARD, false);
        ioh.setInputForceState(Input.SPRINT, false);
        ioh.setInputForceState(Input.CLICK_LEFT, false);
        if (ctx.player().onGround()) {
            ioh.setInputForceState(Input.SNEAK, true);
        }

        BlockPos breach = LiquidDetector.isLiquid(world, frontHead) ? frontHead : frontFeet;

        boolean selected = baritone.getInventoryBehavior().selectThrowawayForLocation(true, breach.getX(), breach.getY(), breach.getZ());
        if (!selected && ctx.player() != null) {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = ctx.player().getInventory().getItem(i);
                if (!stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem) {
                    Block b = blockItem.getBlock();
                    if (b == Blocks.COBBLESTONE || b == Blocks.COBBLED_DEEPSLATE || b == Blocks.DIRT || b == Blocks.STONE || b == Blocks.NETHERRACK) {
                        ctx.player().getInventory().selected = i;
                        selected = true;
                        break;
                    }
                }
            }
        }

        if (selected) {
            Vec3 targetVec = Vec3.atCenterOf(breach).subtract(new Vec3(activeDir.getStepX() * 0.45D, 0.0D, activeDir.getStepZ() * 0.45D));
            Rotation plugRot = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), targetVec, ctx.playerRotations());
            RotationEngine.apply(ctx.player(), plugRot, baritone.settings(), true);
            ioh.setInputForceState(Input.CLICK_RIGHT, true);
        } else {
            ioh.setInputForceState(Input.MOVE_BACK, true);
        }

        rotateToSafeCorridor(world, feet, activeDir);
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private boolean isPassable(Level world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (ObstructionHelper.isClearableObstruction(state)) {
            return false;
        }
        return state.isAir() || state.canBeReplaced();
    }
}
