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

package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalItemDrop;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.IFollowProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Follow an entity or pickup items.
 *
 * Zoptymalizowany pod GrimAC:
 * 1. Target Locking z histerezą odległości i kąta kamery — eliminuje nagłe obroty o 180° i ping-pong.
 * 2. GoalItemDrop z uwzględnieniem AABB podnoszenia (promień 0.85 XZ) zamiast wymuszania wchodzenia w środek bloku.
 * 3. PathingCommandType.DEFER zamiast ciągłego REVALIDATE_GOAL_AND_PATH co tick — likwiduje pętle przeliczania A*.
 *
 * @author leijurv
 */
public final class FollowProcess extends BaritoneProcessHelper implements IFollowProcess {

    private Predicate<Entity> filter;
    private List<Entity> cache;
    private boolean into; // walk straight into the target, regardless of settings

    // Target Locking & Anti-Thrashing State
    private Entity lockedTarget;
    private Goal lastIssuedGoal;

    public FollowProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        scanWorld();
        if (cache == null || cache.isEmpty()) {
            this.lockedTarget = null;
            this.lastIssuedGoal = null;
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        if (into) {
            // Tryb podnoszenia itemów (#pickup) lub zbierania: stabilny wybór z blokadą celu
            Entity bestTarget = selectStableTarget();
            if (bestTarget == null) {
                this.lockedTarget = null;
                this.lastIssuedGoal = null;
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            this.lockedTarget = bestTarget;
            Goal goal = towards(bestTarget);

            // Jeśli cel w ujęciu skwantowanym się nie zmienił, a bot już płynnie idzie do celu:
            // NIE anuluj i NIE rewaliduj ścieżki co tick — pozwól silnikowi ruchu działać płynnie!
            if (goal.equals(this.lastIssuedGoal) && baritone.getPathingBehavior().isPathing() && !calcFailed) {
                return new PathingCommand(goal, PathingCommandType.DEFER);
            }

            this.lastIssuedGoal = goal;
            return new PathingCommand(goal, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }

        // Standardowy follow (np. graczy lub mobów)
        Goal goal = new GoalComposite(cache.stream().map(this::towards).toArray(Goal[]::new));
        return new PathingCommand(goal, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    private Entity selectStableTarget() {
        var player = ctx.player();
        if (player == null || cache.isEmpty()) {
            return null;
        }
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();
        float yawRad = (float) Math.toRadians(player.getYRot());
        double lookX = -Math.sin(yawRad);
        double lookZ = Math.cos(yawRad);

        // 1. Sprawdź czy dotychczas zablokowany cel jest nadal aktywny w świecie
        double lockedScore = Double.MAX_VALUE;
        if (this.lockedTarget != null && this.lockedTarget.isAlive() && this.cache.contains(this.lockedTarget)) {
            lockedScore = scoreEntity(this.lockedTarget, px, py, pz, lookX, lookZ);
        } else {
            this.lockedTarget = null;
        }

        Entity best = this.lockedTarget;
        double bestScore = lockedScore;

        // 2. Znajdź najlepszy cel z uwzględnieniem histerezy
        for (int i = 0, size = this.cache.size(); i < size; i++) {
            Entity candidate = this.cache.get(i);
            if (candidate == this.lockedTarget || !candidate.isAlive()) {
                continue;
            }

            double score = scoreEntity(candidate, px, py, pz, lookX, lookZ);
            // Histereza: nowy cel musi być wyraźnie lepszy (o min. 3.0 jednostki kosztu), by przerwać obecny ruch
            double switchThreshold = (this.lockedTarget != null) ? (bestScore - 3.0) : bestScore;
            if (score < switchThreshold) {
                bestScore = score;
                best = candidate;
            }
        }
        return best != null ? best : this.cache.get(0);
    }

    private static double scoreEntity(Entity e, double px, double py, double pz, double lookX, double lookZ) {
        double dx = e.getX() - px;
        double dy = e.getY() - py;
        double dz = e.getZ() - pz;
        double distXZ = Math.sqrt(dx * dx + dz * dz);

        // GrimAC Fluidity: preferuj przedmioty w stożku wzroku, aby unikać nagłych obrotów o 180°
        double anglePenalty = 0.0;
        if (distXZ > 0.25) {
            double dot = (dx / distXZ) * lookX + (dz / distXZ) * lookZ; // [-1.0 .. 1.0]
            anglePenalty = (1.0 - dot) * 1.5; // Do +3.0 jednostki kary za obrót o 180°
        }
        double airbornePenalty = e.onGround() ? 0.0 : 2.0;

        return distXZ + Math.abs(dy) * 1.5 + anglePenalty + airbornePenalty;
    }

    private Goal towards(Entity following) {
        if (into) {
            if (following instanceof ItemEntity itemEntity) {
                return new GoalItemDrop(itemEntity, ctx.world());
            }
            return new GoalBlock(following.blockPosition());
        }

        BlockPos pos;
        if (Baritone.settings().followOffsetDistance.value == 0) {
            pos = following.blockPosition();
        } else {
            GoalXZ g = GoalXZ.fromDirection(following.position(), Baritone.settings().followOffsetDirection.value, Baritone.settings().followOffsetDistance.value);
            pos = new BetterBlockPos(g.getX(), following.position().y, g.getZ());
        }
        return new GoalNear(pos, Baritone.settings().followRadius.value);
    }

    private boolean followable(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (!entity.isAlive()) {
            return false;
        }
        if (entity.equals(ctx.player())) {
            return false;
        }
        int maxDist = Baritone.settings().followTargetMaxDistance.value;
        if (maxDist != 0 && entity.distanceToSqr(ctx.player()) > maxDist * maxDist) {
            return false;
        }
        return ctx.entitiesStream().anyMatch(entity::equals);
    }

    private void scanWorld() {
        cache = ctx.entitiesStream()
                .filter(this::followable)
                .filter(this.filter)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public boolean isActive() {
        if (filter == null) {
            return false;
        }
        scanWorld();
        return cache != null && !cache.isEmpty();
    }

    @Override
    public void onLostControl() {
        filter = null;
        cache = null;
        lockedTarget = null;
        lastIssuedGoal = null;
    }

    @Override
    public String displayName0() {
        return "Following " + cache;
    }

    @Override
    public void follow(Predicate<Entity> filter) {
        this.filter = filter;
        this.into = false;
        this.lockedTarget = null;
        this.lastIssuedGoal = null;
    }

    @Override
    public void pickup(Predicate<ItemStack> filter) {
        this.filter = e -> e instanceof ItemEntity && filter.test(((ItemEntity) e).getItem());
        this.into = true;
        this.lockedTarget = null;
        this.lastIssuedGoal = null;
    }

    @Override
    public List<Entity> following() {
        return cache;
    }

    @Override
    public Predicate<Entity> currentFilter() {
        return filter;
    }
}
