package baritone.pathing.calc;

import baritone.Baritone;

/** Drabina retry po CALC_FAILED — zamiast poddawać się po pierwszym niepowodzeniu. */
public final class CalcRetryLadder {

    private int attempt = 0;

    /** Wywołaj przed kalkulacją. null = wyczerpane próby. */
    public synchronized Attempt nextAttempt() {
        attempt++;
        long primary = Baritone.settings().primaryTimeoutMS.value;
        long failure = Baritone.settings().failureTimeoutMS.value;
        return switch (attempt) {
            case 1 -> new Attempt(1, primary, failure, -1.0);   // normalna (settingi)
            case 2 -> new Attempt(2, failure, failure, 1.0);    // dużo czasu, dokładnie
            case 3 -> new Attempt(3, failure * 2, failure * 2, 1.4); // ostatnia: długo i szeroko
            default -> null;
        };
    }

    public synchronized void onSuccess() { attempt = 0; }
    public synchronized void reset()      { attempt = 0; }

    /** @param weight waga A* (-1 = setting astarWeight) */
    public record Attempt(int index, long primaryMs, long failureMs, double weight) {}
}
