package baritone.pathing.calc;

import baritone.api.pathing.movement.ActionCosts;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Silnik wyceny krawędzi grafu A* w jednostkach czasu rzeczywistego (Ticki gry).
 * Wpinany w MovementTraverse / MovementDiagonal / MovementAscend oraz CalculationContext.
 */
public final class MineCostHeuristic {

    public static final double SPRINT_ONE_BLOCK_TICKS = 3.564D;
    public static final double DIAGONAL_SPRINT_TICKS = 5.040D;
    public static final double STRAIGHT_TUNNEL_MULTIPLIER = 0.85D;

    public static final double JUMP_UP_PENALTY_TICKS = 2.5D;
    public static final double STEP_DOWN_PENALTY_TICKS = 0.8D;

    public static final double TURN_45_PENALTY_TICKS = 1.2D;
    public static final double TURN_90_PENALTY_TICKS = 3.0D;
    public static final double TURN_180_PENALTY_TICKS = 6.0D;

    // Gwarantuje wyższy koszt przebicia Deepslate względem pustego tunelu
    public static final double DEEPSLATE_MIN_FLOOR_TICKS = 15.0D;
    public static final double VANILLA_NON_INSTAMINE_DELAY = 5.0D;

    private MineCostHeuristic() {}

    /**
     * Oblicza dokładny czas przejścia z (x1, y1, z1) do (x2, y2, z2) w tickach,
     * uwzględniając kierunek poprzedniego kroku (prevDx, prevDz) oraz koszt przebicia skały.
     */
    public static double computeTransitionCostTicks(
            CalculationContext ctx,
            int prevDx, int prevDz,
            int x1, int y1, int z1,
            int x2, int y2, int z2
    ) {
        int dx = x2 - x1;
        int dy = y2 - y1;
        int dz = z2 - z1;

        boolean diagonal = (dx != 0 && dz != 0);
        double moveTicks = diagonal ? DIAGONAL_SPRINT_TICKS : SPRINT_ONE_BLOCK_TICKS;

        // 1. Kara za zmianę kierunku / premia za prosty korytarz (Delta Yaw = 0)
        double turnPenalty = 0.0D;
        if (prevDx != 0 || prevDz != 0) {
            int dot = prevDx * dx + prevDz * dz;
            if (dx == prevDx && dz == prevDz) {
                moveTicks *= STRAIGHT_TUNNEL_MULTIPLIER; // Premia 0.85x za prosty bieg
            } else if (dot > 0) {
                turnPenalty = TURN_45_PENALTY_TICKS;
            } else if (dot == 0) {
                turnPenalty = TURN_90_PENALTY_TICKS;
            } else {
                turnPenalty = TURN_180_PENALTY_TICKS;
            }
        }

        // 2. Koszt pionowy (schodki i skoki +1Y)
        double verticalPenalty = 0.0D;
        if (dy > 0) {
            verticalPenalty = JUMP_UP_PENALTY_TICKS * dy;
        } else if (dy < 0) {
            verticalPenalty = STEP_DOWN_PENALTY_TICKS * (-dy);
        }

        // 3. Koszt przebicia przeszkód (Feet + Head w docelowym slocie, plus sufit przy skoku +1Y)
        double breakTicks = 0.0D;
        breakTicks += computeBlockBreakTicks(ctx, x2, y2, z2);
        breakTicks += computeBlockBreakTicks(ctx, x2, y2 + 1, z2);

        if (dy > 0) {
            // Przy wejściu +1Y trzeba również oczyścić blok nad głową obecnej pozycji
            breakTicks += computeBlockBreakTicks(ctx, x1, y1 + 2, z1);
        } else if (dy < 0) {
            // Przy zejściu -1Y trzeba mieć wolną przestrzeń na wysokości y1+1 nad docelowym blokiem
            breakTicks += computeBlockBreakTicks(ctx, x2, y1 + 1, z2);
        }

        if (breakTicks >= ActionCosts.COST_INF) {
            return ActionCosts.COST_INF;
        }

        return moveTicks + turnPenalty + verticalPenalty + breakTicks;
    }

    /**
     * Wylicza czas rozbicia pojedynczego bloku w tickach na bazie CalculationContext (ToolSet).
     */
    public static double computeBlockBreakTicks(CalculationContext ctx, int x, int y, int z) {
        BlockState state = ctx.get(x, y, z);
        if (state.isAir() || state.getCollisionShape(ctx.world, new BlockPos(x, y, z)).isEmpty()) {
            return 0.0D;
        }

        if (MovementHelper.avoidBreaking(ctx.bsi, x, y, z, state)) {
            return ActionCosts.COST_INF;
        }

        double strVsBlock = ctx.toolSet.getStrVsBlock(state);
        if (strVsBlock <= 0.0D) {
            return ActionCosts.COST_INF;
        }

        // Baritone ToolSet zwraca efektywny ułamek postępu na tick (damagePerTick)
        double ticks;
        if (strVsBlock >= 1.0D) {
            ticks = 1.0D; // Instamine: omija 5-tickowy cooldown vanilla
        } else {
            ticks = Math.ceil(1.0D / strVsBlock) + VANILLA_NON_INSTAMINE_DELAY;
        }

        // Wzmocnienie preferencji otwartych tuneli względem Deepslate (minimum 5x koszt powietrza)
        if (state.is(Blocks.DEEPSLATE) || state.is(Blocks.COBBLED_DEEPSLATE) || state.is(Blocks.TUFF)) {
            ticks = Math.max(ticks, DEEPSLATE_MIN_FLOOR_TICKS * 0.5D);
        }

        return ticks;
    }
}

