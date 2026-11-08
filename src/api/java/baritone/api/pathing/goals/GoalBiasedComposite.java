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

package baritone.api.pathing.goals;

import baritone.api.utils.interfaces.IGoalRenderPos;
import net.minecraft.core.BlockPos;

import java.util.Arrays;

/**
 * Sekwencyjna bramka kompozytowa dla celów uporządkowanych (np. trasy TSP wygenerowanej
 * przez OreRouteOptimizer lub bloków wewnątrz klastra rudy).
 *
 * W odróżnieniu od GoalComposite (gdzie wszystkie cele są równorzędne i A* wybiera
 * cel o najniższym koszcie ścieżki w drzewie, burząc kolejność wyznaczoną przez TSP),
 * GoalBiasedComposite nakłada narastającą karę heurystyczną (index * stepPenalty) na dalsze cele.
 *
 * Efekt:
 * - Pierwszy cel (indeks 0) ma karę 0.0 i A* dąży ściśle do niego.
 * - Dalsze cele (indeks 1, 2, ...) służą wyłącznie jako płynny fallback na wypadek,
 *   gdyby pierwszy cel okazał się fizycznie zablokowany, eliminując puste przeliczenia.
 */
public class GoalBiasedComposite implements Goal, IGoalRenderPos {

    private final Goal[] goals;
    private final double stepPenalty;

    public GoalBiasedComposite(double stepPenalty, Goal... goals) {
        this.goals = goals;
        this.stepPenalty = stepPenalty;
    }

    public Goal[] getGoals() {
        return goals;
    }

    public double getStepPenalty() {
        return stepPenalty;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        if (goals.length == 0) {
            return false;
        }
        if (goals.length == 1) {
            return goals[0].isInGoal(x, y, z);
        }
        for (Goal goal : this.goals) {
            if (goal.isInGoal(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        if (goals.length == 0) {
            return Double.MAX_VALUE;
        }
        if (goals.length == 1) {
            return goals[0].heuristic(x, y, z);
        }
        double min = Double.MAX_VALUE;
        for (int i = 0; i < this.goals.length; i++) {
            double h = this.goals[i].heuristic(x, y, z) + (i * this.stepPenalty);
            if (h < min) {
                min = h;
            }
        }
        return min;
    }

    @Override
    public double heuristic() {
        if (goals.length == 0) {
            return Double.MAX_VALUE;
        }
        if (goals.length == 1) {
            return goals[0].heuristic();
        }
        double min = Double.MAX_VALUE;
        for (int i = 0; i < this.goals.length; i++) {
            double h = this.goals[i].heuristic() + (i * this.stepPenalty);
            if (h < min) {
                min = h;
            }
        }
        return min;
    }

    @Override
    public BlockPos getGoalPos() {
        if (goals.length > 0 && goals[0] instanceof IGoalRenderPos grp) {
            return grp.getGoalPos();
        }
        return null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GoalBiasedComposite other)) return false;
        return Double.compare(this.stepPenalty, other.stepPenalty) == 0
                && Arrays.equals(this.goals, other.goals);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(this.goals) + Double.hashCode(this.stepPenalty);
    }

    @Override
    public String toString() {
        return "GoalBiasedComposite{penalty=" + stepPenalty + ", goals=" + Arrays.toString(goals) + "}";
    }
}

