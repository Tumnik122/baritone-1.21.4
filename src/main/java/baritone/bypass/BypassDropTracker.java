package baritone.bypass;

import java.util.Set;

/** Drop filtering and bounded, progress-aware waiting, independent of rendering/network timing. */
final class BypassDropTracker {
    enum Result { CONTINUE, FINISHED, TIMED_OUT }

    private static final int SPAWN_GRACE_TICKS = 30;
    private static final int NO_PROGRESS_TICKS = 160;
    private static final int MAX_TICKS = 600;
    private final Set<String> acceptedItems;
    private int ticks;
    private int emptyTicks;
    private int idleTicks;
    private int targetId = -1;
    private double bestDistance = Double.POSITIVE_INFINITY;

    BypassDropTracker(Set<String> acceptedItems) {
        this.acceptedItems = Set.copyOf(acceptedItems);
    }

    boolean accepts(String itemName) {
        return acceptedItems.contains(itemName);
    }

    Result tick(int entityId, double distanceSquared) {
        ticks++;
        if (entityId < 0) {
            if (++emptyTicks >= SPAWN_GRACE_TICKS) {
                return Result.FINISHED;
            }
        } else {
            emptyTicks = 0;
            if (targetId != entityId || distanceSquared < bestDistance - 0.04D) {
                targetId = entityId;
                bestDistance = distanceSquared;
                idleTicks = 0;
            } else {
                idleTicks++;
            }
        }
        return ticks >= MAX_TICKS || idleTicks >= NO_PROGRESS_TICKS
                ? Result.TIMED_OUT : Result.CONTINUE;
    }

    static Set<String> itemsForOre(String name) {
        String ore = name.startsWith("deepslate_") ? name.substring("deepslate_".length()) : name;
        String drop = switch (ore) {
            case "diamond_ore" -> "diamond";
            case "gold_ore" -> "raw_gold";
            case "iron_ore" -> "raw_iron";
            case "redstone_ore" -> "redstone";
            case "lapis_ore" -> "lapis_lazuli";
            case "emerald_ore" -> "emerald";
            case "coal_ore" -> "coal";
            default -> ore;
        };
        // Include Silk Touch drops as well as ordinary/Fortune drops.
        return Set.copyOf(java.util.List.of(drop, ore, "deepslate_" + ore));
    }
}
