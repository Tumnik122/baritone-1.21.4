package baritone.bypass.modern;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

public final class TpsTracker {
    private static long lastWorldTime = -1L;
    private static long lastPacketNano = -1L;
    private static float currentTps = 20.0f;
    private static double smoothedBps = 0.0;

    private TpsTracker() {}

    public static void updateOnClientTick(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            currentTps = 20.0f;
            smoothedBps = 0.0;
            lastWorldTime = -1L;
            return;
        }

        // 1. Obliczanie prędkości poziomej gracza w blokach na sekundę (BPS)
        double dx = mc.player.getX() - mc.player.xo;
        double dz = mc.player.getZ() - mc.player.zo;
        double instantBps = Math.hypot(dx, dz) * 20.0;
        smoothedBps = smoothedBps + (instantBps - smoothedBps) * 0.35;
        if (smoothedBps < 0.03) {
            smoothedBps = 0.0;
        }

        // 2. Obliczanie TPS
        MinecraftServer integrated = mc.getSingleplayerServer();
        if (integrated != null) {
            float mspt = integrated.getCurrentSmoothedTickTime();
            float targetTps = integrated.tickRateManager().tickrate();
            currentTps = mspt <= 0.0f ? targetTps : Math.min(targetTps, 1000.0f / mspt);
            return;
        }

        long gameTime = mc.level.getGameTime();
        long now = System.nanoTime();
        if (lastWorldTime != -1L && gameTime > lastWorldTime) {
            long ticksElapsed = gameTime - lastWorldTime;
            double secondsElapsed = (now - lastPacketNano) / 1_000_000_000.0;
            if (secondsElapsed > 0.05) {
                float rawTps = (float) Math.max(0.0, Math.min(20.0, (double) ticksElapsed / secondsElapsed));
                currentTps = currentTps * 0.75f + rawTps * 0.25f;
                lastWorldTime = gameTime;
                lastPacketNano = now;
            }
        } else if (lastWorldTime == -1L) {
            lastWorldTime = gameTime;
            lastPacketNano = now;
        }
    }

    public static int getRoundedTps() {
        return Math.round(Math.max(0.0f, Math.min(20.0f, currentTps)));
    }

    public static double getSmoothedBps() {
        return smoothedBps;
    }
}
