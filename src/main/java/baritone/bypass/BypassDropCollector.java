package baritone.bypass;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalItemDrop;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Moduł zbierania leżącego dropu diamentów i cennych surowców (Argon 4 & dj).
 */
public final class BypassDropCollector {

    private BypassDropCollector() {}

    public static ItemEntity findNearbyValuableDrop(BypassProcess proc, LocalPlayer player, Level level) {
        if (player == null || level == null) return null;
        AABB searchBox = player.getBoundingBox().inflate(6.0D, 3.5D, 6.0D);
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, searchBox, entity -> {
            if (!entity.isAlive() || entity.isInLava() || proc.getIgnoredDropIds().containsKey(entity.getId()))
                return false;
            Item item = entity.getItem().getItem();
            if (proc.getTargetOresList().isEmpty()) {
                return item == Items.DIAMOND || item == Items.DIAMOND_ORE || item == Items.DEEPSLATE_DIAMOND_ORE;
            }
            for (String ore : proc.getTargetOresList()) {
                String norm = BypassProcess.normalizeOreKey(ore);
                Set<Item> allowed = BypassProcess.ORE_ITEM_MAP.get(norm);
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

    public static PathingCommand handleCollectingDrop(BypassProcess proc, LocalPlayer player, Level level, ItemEntity drop) {
        long now = System.currentTimeMillis();
        proc.getIgnoredDropIds().entrySet().removeIf(entry -> now > entry.getValue());

        if (drop == null || !drop.isAlive() || proc.getIgnoredDropIds().containsKey(drop.getId())
                || DropCollectionHelper.isDropUnsafe(level, drop)) {
            if (drop != null) {
                proc.getIgnoredDropIds().put(drop.getId(), now + 30_000L);
            }
            proc.resetDropCollectionState();
            return null;
        }

        double distSq = player.distanceToSqr(drop);

        if (drop.getId() != proc.getCurrentDropId()) {
            proc.setCurrentDropId(drop.getId());
            proc.setDropCollectTicks(0);
            proc.setDropStuckTicks(0);
            proc.setLastDistToDropSq(distSq);
            proc.setLastDropGoal(null);
        }

        proc.setDropCollectTicks(proc.getDropCollectTicks() + 1);

        if (distSq < proc.getLastDistToDropSq() - 0.03) {
            proc.setLastDistToDropSq(distSq);
            proc.setDropStuckTicks(0);
        } else {
            proc.setDropStuckTicks(proc.getDropStuckTicks() + 1);
        }

        if (proc.getDropCollectTicks() > 100) {
            proc.getIgnoredDropIds().put(drop.getId(), now + 15_000L);
            proc.resetDropCollectionState();
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        if (distSq <= 6.25 && proc.getDropStuckTicks() < 15 && DropCollectionHelper.canWalkDirectlyToDrop(player, level, drop)) {
            proc.setCurrentBreakingBlock(null);
            moveSafelyTowardsDrop(proc, player, drop.position());
            proc.setLastDropGoal(null);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        BlockPos obstruction = DropCollectionHelper.findObstructingBlock(player, level, drop);
        if (obstruction != null && !BypassNavigator.isBedrockOrUnbreakable(level, obstruction)) {
            proc.setDropStuckTicks(0);
            proc.setLastDropGoal(null);
            return proc.mineBlockSafely(player, level, obstruction);
        }

        proc.setCurrentBreakingBlock(null);
        proc.getBaritone().getInputOverrideHandler().clearAllKeys();

        Goal lastGoal = proc.getLastDropGoal();
        if (lastGoal == null || !lastGoal.isInGoal(drop.blockPosition())) {
            lastGoal = new GoalItemDrop(drop);
            proc.setLastDropGoal(lastGoal);
        }

        return new PathingCommand(lastGoal, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    public static void moveSafelyTowardsDrop(BypassProcess proc, LocalPlayer player, Vec3 targetPos) {
        if (player == null || targetPos == null)
            return;
        Rotation fullRot = BypassNavigator.calcRotationFromVec3d(player.getEyePosition(1.0F), targetPos, player.getYRot());
        float yawDiff = Math.abs(Mth.wrapDegrees(fullRot.getYaw() - player.getYRot()));

        if (yawDiff > 3.0f) {
            Rotation walkRot = BypassNavigator.applyGrimGcd(player.getYRot(), player.getXRot(), fullRot.getYaw(), player.getXRot());
            updateRotationGrimSafe(proc, player, walkRot, false);
        }

        proc.getBaritone().getInputOverrideHandler().clearAllKeys();
        proc.getBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
    }

    public static void updateRotationGrimSafe(BypassProcess proc, LocalPlayer player, Rotation targetRot, boolean blockInteract) {
        if (player == null || targetRot == null)
            return;
        float yawDiff = Math.abs(Mth.wrapDegrees(targetRot.getYaw() - player.getYRot()));
        float pitchDiff = Math.abs(targetRot.getPitch() - player.getXRot());

        if (yawDiff < 0.20f && pitchDiff < 0.20f) {
            return;
        }
        proc.getBaritone().getLookBehavior().updateTarget(targetRot, blockInteract);
    }
}

