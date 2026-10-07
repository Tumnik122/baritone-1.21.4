package baritone.bypass.mine;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalNear;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Zbieranie dropów bez deadlocków.
 *
 * Waniliowe reguły 1.21.4:
 *  - Serwer zbiera przedmiot, gdy AABB przedmiotu przecina AABB gracza powiększony o (1.0, 0.5, 1.0);
 *  - Drop z bloku ma pickup delay 10 ticków (tyle czekamy, zanim uznamy "nie zbiera się" za błąd);
 *  - Przedmioty w zasięgu zbierania nie wymagają chodzenia — wystarczy poczekać (loiter).
 *
 * Każdy przedmiot ma własny stan i watchdog postępu; przedmiot, do którego nie da się dojść
 * (brak postępu, brak miejsca w EQ, w lawie) trafia na czasową czarną listę zamiast blokować bota.
 */
public final class DropVacuum {

    /** target = dokąd iść (null = nic do zrobienia ruchem); loiter = jeszcze nie odchodź; inventoryFull = brak miejsca w EQ. */
    public record Decision(ItemEntity target, boolean loiter, boolean inventoryFull) {
        public Goal goal() {
            return target == null ? null : new GoalNear(target.blockPosition(), 1);
        }
    }

    private enum St { APPROACH, ABANDONED }

    private static final class Track {
        St st = St.APPROACH;
        final long firstSeen;
        long lastProgress;
        double bestDist;
        int inRangeTicks;
        long banUntil;

        Track(long now, double dist) {
            firstSeen = now;
            lastProgress = now;
            bestDist = dist;
        }
    }

    private static final int PICKUP_DELAY = 10;
    private static final int SETTLE = PICKUP_DELAY + 6;  // delay + zapas na opóźnienie sieci i scalanie stosów
    private static final int STALL_TICKS = 60;           // brak postępu >= 3 s => porzuć
    private static final double SEARCH_XZ = 14.0, SEARCH_Y = 6.0;

    private final Map<Integer, Track> tracks = new HashMap<>();
    private int currentId = -1;
    private long lastNow;

    public Decision tick(LocalPlayer p, ClientLevel level, long now, Predicate<ItemStack> wanted) {
        lastNow = now;
        if (p == null || level == null) {
            return new Decision(null, false, false);
        }
        AABB search = p.getBoundingBox().inflate(SEARCH_XZ, SEARCH_Y, SEARCH_XZ);
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, search,
                e -> e != null && e.isAlive() && (wanted == null || wanted.test(e.getItem())));
        AABB pickupBox = p.getBoundingBox().inflate(1.0, 0.5, 1.0);

        Set<Integer> alive = new HashSet<>();
        ItemEntity best = null;
        double bestKey = Double.MAX_VALUE;
        boolean loiter = false, full = false;

        for (ItemEntity it : items) {
            alive.add(it.getId());
            double dist = it.position().distanceTo(p.position());
            Track t = tracks.computeIfAbsent(it.getId(), id -> new Track(now, dist));

            if (t.st == St.ABANDONED) {
                if (now < t.banUntil) continue;
                t.st = St.APPROACH; // ban minął — daj drugą szansę
                t.lastProgress = now;
                t.bestDist = dist;
                t.inRangeTicks = 0;
            }
            if (now - t.firstSeen < SETTLE) loiter = true; // świeży drop: niech opadnie i się scali

            if (pickupBox.intersects(it.getBoundingBox())) {
                loiter = true;
                if (++t.inRangeTicks > SETTLE + 4) {          // w zasięgu, a nie znika => EQ pełny / brak zgody
                    if (!hasRoom(p, it.getItem())) full = true;
                    abandon(t, now, 1200);
                }
                continue;                                      // nie trzeba iść
            }
            t.inRangeTicks = 0;

            if (dist < t.bestDist - 0.3) {
                t.bestDist = dist;
                t.lastProgress = now;
            } else if (it.getId() == currentId && now - t.lastProgress > STALL_TICKS) {
                abandon(t, now, 600);
                continue;
            }
            if (it.isInLava()) continue;                       // stracone — nie ryzykuj życia dla przedmiotu

            // preferuj bliskie, utrzymuj wybór (histereza), pilnuj tych w wodzie (odpływają)
            double key = dist * dist - (it.isInWater() ? 64.0 : 0.0) - (it.getId() == currentId ? 9.0 : 0.0);
            if (key < bestKey) {
                bestKey = key;
                best = it;
            }
        }
        tracks.keySet().retainAll(alive); // zniknęły = zebrane lub zdespawnowane
        currentId = best == null ? -1 : best.getId();
        return new Decision(best, loiter, full);
    }

    /** Wołać, gdy A* zwrócił porażkę dla aktualnego celu — zapobiega pętleniu się bota na nieosiągalnym przedmiocie. */
    public void onPathFailed() {
        Track t = tracks.get(currentId);
        if (t != null) abandon(t, lastNow, 600);
    }

    public void reset() {
        tracks.clear();
        currentId = -1;
    }

    private static void abandon(Track t, long now, int banTicks) {
        t.st = St.ABANDONED;
        t.banUntil = now + banTicks;
    }

    private static boolean hasRoom(LocalPlayer p, ItemStack s) {
        var inv = p.getInventory();
        return inv.getFreeSlot() >= 0 || inv.getSlotWithRemainingSpace(s) >= 0;
    }
}
