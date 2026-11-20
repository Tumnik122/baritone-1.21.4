package baritone.bypass;

import baritone.bypass.nav.ContinuousBoreExecutor;

/**
 * Rejestr współdzielonego stanu egzekutora wiercenia (ContinuousBoreExecutor)
 * dla nasłuchiwaczy pakietów serwera (MixinClientPlayNetHandler).
 */
public final class AstraBypassRegistry {

    private static volatile ContinuousBoreExecutor activeExecutor;

    private AstraBypassRegistry() {}

    public static void setActiveExecutor(ContinuousBoreExecutor exec) {
        activeExecutor = exec;
    }

    public static ContinuousBoreExecutor getActiveExecutor() {
        return activeExecutor;
    }

    public static void onServerBlockConfirmedAir(long packedPos) {
        ContinuousBoreExecutor exec = activeExecutor;
        if (exec != null) {
            exec.onServerBlockConfirmedAir(packedPos);
        }
    }
}

