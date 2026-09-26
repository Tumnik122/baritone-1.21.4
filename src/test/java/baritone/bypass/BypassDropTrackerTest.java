package baritone.bypass;

import org.junit.Test;

import static baritone.bypass.BypassDropTracker.Result.*;
import static org.junit.Assert.*;

public class BypassDropTrackerTest {
    private BypassDropTracker diamonds() {
        return new BypassDropTracker(BypassDropTracker.itemsForOre("deepslate_diamond_ore"));
    }

    @Test
    public void acceptsDiamondsAndSilkTouchButNotDiscardedTrash() {
        BypassDropTracker tracker = diamonds();
        assertTrue(tracker.accepts("diamond"));
        assertTrue(tracker.accepts("diamond_ore"));
        assertTrue(tracker.accepts("deepslate_diamond_ore"));
        assertFalse(tracker.accepts("cobbled_deepslate"));
        assertFalse(tracker.accepts("cobblestone"));
        assertFalse(tracker.accepts("raw_iron"));
    }

    @Test
    public void mapsEverySupportedOreToItsActualDrop() {
        String[][] ores = {{"diamond", "diamond"}, {"gold", "raw_gold"}, {"iron", "raw_iron"},
                {"redstone", "redstone"}, {"lapis", "lapis_lazuli"}, {"emerald", "emerald"}, {"coal", "coal"}};
        for (String[] ore : ores) {
            assertTrue(BypassDropTracker.itemsForOre(ore[0] + "_ore").contains(ore[1]));
            assertTrue(BypassDropTracker.itemsForOre("deepslate_" + ore[0] + "_ore").contains(ore[1]));
        }
    }

    @Test
    public void delayedSpawnDoesNotImmediatelyFinishCollection() {
        BypassDropTracker tracker = diamonds();
        for (int i = 0; i < 29; i++) assertEquals(CONTINUE, tracker.tick(-1, Double.POSITIVE_INFINITY));
        assertEquals(CONTINUE, tracker.tick(42, 9));
        for (int i = 0; i < 29; i++) assertEquals(CONTINUE, tracker.tick(-1, Double.POSITIVE_INFINITY));
        assertEquals(FINISHED, tracker.tick(-1, Double.POSITIVE_INFINITY));
    }

    @Test
    public void beingCloseIsNotProofOfPickup() {
        BypassDropTracker tracker = diamonds();
        for (int i = 0; i < 100; i++) assertEquals(CONTINUE, tracker.tick(42, 0.01));
        assertEquals(CONTINUE, tracker.tick(-1, Double.POSITIVE_INFINITY));
    }

    @Test
    public void blockedItemTimesOutInsteadOfClaimingSuccess() {
        BypassDropTracker tracker = diamonds();
        for (int i = 0; i < 160; i++) assertEquals(CONTINUE, tracker.tick(42, 9));
        assertEquals(TIMED_OUT, tracker.tick(42, 9));
    }

    @Test
    public void movementAndMergedEntitiesResetNoProgressTimer() {
        BypassDropTracker tracker = diamonds();
        for (int i = 0; i < 150; i++) assertEquals(CONTINUE, tracker.tick(42, 16));
        for (int i = 0; i < 150; i++) assertEquals(CONTINUE, tracker.tick(42, 9));
        for (int i = 0; i < 150; i++) assertEquals(CONTINUE, tracker.tick(43, 9));
    }

    @Test
    public void changingTargetsCannotResetTheOverallDeadline() {
        BypassDropTracker tracker = diamonds();
        for (int i = 0; i < 599; i++) assertEquals(CONTINUE, tracker.tick(i, 9));
        assertEquals(TIMED_OUT, tracker.tick(599, 9));
    }
}
