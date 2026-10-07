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

import java.util.Arrays;

/**
 * A composite of many goals, any one of which satisfies the composite.
 * For example, a GoalComposite of block goals for every oak log in loaded chunks
 * would result in it pathing to the easiest oak log to get to
 *
 * @author avecowa
 */
public class GoalComposite implements Goal {

    /**
     * An array of goals that any one of must be satisfied
     */
    private final Goal[] goals;

    public GoalComposite(Goal... goals) {
        this.goals = goals;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        if (goals.length == 0) {
            return false;
        }
        if (goals.length == 1) {
            return goals[0].isInGoal(x, y, z);
        }
        for (Goal goal : goals) {
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
        for (Goal g : goals) {
            double h = g.heuristic(x, y, z);
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
        for (Goal g : goals) {
            double h = g.heuristic();
            if (h < min) {
                min = h;
            }
        }
        return min;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        GoalComposite goal = (GoalComposite) o;
        return Arrays.equals(goals, goal.goals);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(goals);
    }

    @Override
    public String toString() {
        return "GoalComposite" + Arrays.toString(goals);
    }

    public Goal[] goals() {
        return goals;
    }
}
