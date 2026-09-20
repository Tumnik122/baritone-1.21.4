package baritone.bypass;

import baritone.Baritone;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.utils.ContinuousBreakController;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 2. StaircaseBuilder.java
 * Osobny moduł na bezpieczne schodzenie do Y = -55 ± 2.
 *
 * Zasady działania:
 * - Pattern: 1 blok w przód -> 1 blok w dół -> powtarzaj.
 * - NIGDY nie kopie prosto pod siebie (anti-dig down death).
 * - Przed każdym blokiem wywołuje LiquidDetector.checkBlock().
 * - Jeśli wykryje lawę/wodę poniżej -> próbuje postawić blok (np. cobblestone) i skręca o 90°.
 * - Płynna rotacja na każdy blok przez RotationEngine (min. 2 ticki przed LPM).
 * - Po osiągnięciu Y = -55 ± 2 -> sygnalizuje gotowość do przełączenia na TunnelDigger.
 */
public class StaircaseBuilder {

    private final Baritone baritone;
    private final IPlayerContext ctx;
    private final BypassConfig config;

    private Direction facing = Direction.NORTH;
    private boolean completed = false;
    private BlockPos currentTarget = null;
    private int breakCooldownTicks = 0;

    public StaircaseBuilder(Baritone baritone, BypassConfig config) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
        this.config = config;
    }

    public void start(Direction initialDirection) {
        this.facing = initialDirection != null ? initialDirection : Direction.NORTH;
        this.completed = false;
        this.currentTarget = null;
        this.breakCooldownTicks = 0;
    }

    public boolean isCompleted() {
        return completed;
    }

    public Direction getFacing() {
        return facing;
    }

    /**
     * Główny tick modułu schodzenia. Zwraca PathingCommand do wykonania.
     */
    public PathingCommand onTick() {
        if (ctx.player() == null || ctx.world() == null) return null;
        Level world = ctx.world();
        BlockPos feet = ctx.playerFeet();

        // Sprawdź czy osiągnięto poziom docelowy: Y = -55 ± 2 (czyli <= -53)
        if (feet.getY() <= config.yLevel + 2) {
            completed = true;
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            return null;
        }

        if (breakCooldownTicks > 0) {
            breakCooldownTicks--;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // Krok schodów: 1 w przód, 1 w dół
        BlockPos frontFeet = feet.relative(facing);
        BlockPos frontHead = frontFeet.above();
        BlockPos frontStepDown = frontFeet.below();

        // 1. Sprawdź czy pod spodem lub przed nami nie ma cieczy (LiquidDetector)
        if (!LiquidDetector.isSafeToMine(world, frontFeet) ||
            !LiquidDetector.isSafeToMine(world, frontHead) ||
            !LiquidDetector.isSafeToMine(world, frontStepDown)) {
            
            // Postaw blok zabezpieczający jeśli to możliwe i skręć o 90 stopni
            sealLiquidIfPossible(frontStepDown);
            facing = facing.getClockWise();
            Helper.HELPER.logDirect("§6[StaircaseBuilder] Wykryto płyn na trasie schodów. Skręcam o 90° na kierunek: " + facing);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // 2. Kolejność kopania: góra (głowa) -> dół (stopy) -> stopień pod stopami (zejście)
        BlockPos toBreak = null;
        if (!isPassable(world, frontHead)) {
            toBreak = frontHead;
        } else if (!isPassable(world, frontFeet)) {
            toBreak = frontFeet;
        } else if (!isPassable(world, frontStepDown)) {
            toBreak = frontStepDown;
        }

        if (toBreak != null) {
            currentTarget = toBreak;
            Vec3 targetCenter = Vec3.atCenterOf(toBreak);
            Rotation rot = RotationEngine.lookAt(ctx.player().getEyePosition(), targetCenter);
            RotationEngine.apply(ctx.player(), rot, baritone.settings());

            // Wymagane min. 2 ticki ustabilizowania celownika przed rozpoczęciem łamania bloku
            if (RotationEngine.isSettled(config.preRotationTicks)) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                ContinuousBreakController.notifyBreaking(ctx, new BetterBlockPos(toBreak));

                if (world.getBlockState(toBreak).isAir()) {
                    RotationEngine.notifyBlockBroken(toBreak);
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
                    currentTarget = null;
                    // Losowy offset 0-50ms do czasu następnego bloku
                    this.breakCooldownTicks = (int) (Math.random() * 2);
                }
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // Bloki schodka wykopane - postaw krok w przód i w dół
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
        return new PathingCommand(new GoalBlock(frontStepDown), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    private void sealLiquidIfPossible(BlockPos liquidPos) {
        try {
            if (ctx.player() == null || ctx.world() == null) return;
            // Szukamy bruku lub innego bloku w ręce
            ItemStack main = ctx.player().getMainHandItem();
            if (main.is(Items.COBBLESTONE) || main.is(Items.DIRT) || main.is(Items.STONE)) {
                Vec3 hitVec = Vec3.atCenterOf(liquidPos);
                BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, liquidPos, false);
                ctx.minecraft().gameMode.useItemOn(ctx.player(), InteractionHand.MAIN_HAND, hit);
            }
        } catch (Throwable ignored) {}
    }

    private boolean isPassable(Level world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isAir() || state.canBeReplaced();
    }
}
