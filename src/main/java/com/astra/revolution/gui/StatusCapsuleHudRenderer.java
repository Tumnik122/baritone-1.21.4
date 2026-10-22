package com.astra.revolution.gui;

import com.astra.revolution.gui.BaritoneBypassScreen.AstraBaritoneBridge;
import com.astra.revolution.gui.BaritoneBypassScreen.VectorIcon;
import com.astra.revolution.gui.BaritoneBypassScreen.VenturaSdfRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.util.Mth;

import java.util.Locale;

/**
 * ASTRA REVOLUTION — UNIFIED 7-SEGMENT OBSIDIAN & LILAC GLASS HUD (V2)
 *
 * Kluczowe ulepszenia V2:
 * 1. Zastąpienie glow-boxa kierunkowym Drop-Shadow SDF (blur 8px, offsetY +3px, 0x80000000).
 * 2. 1-pikselowa subtelna matowa ramka 0x22FFFFFF zamiast jaskrawej obwódki.
 * 3. Pełna 7-segmentowa unifikacja: Profil V, Serwer, Ping ms, TPS, Prędkość bps, Baritone Task (IDLE/MINE/PATH) z pulsem, Ekwipunek (5/36).
 * 4. Wszystkie ikony VectorIcon i teksty wycentrowane co do 0.5px na wspólnej osi optycznej cy = y + h * 0.5f.
 */
public final class StatusCapsuleHudRenderer {

    public static final StatusCapsuleHudRenderer INSTANCE = new StatusCapsuleHudRenderer();

    // Paleta Obsidian & Lilac Glass dla HUD-u (subtelne matowe szkło V2)
    private static final int COL_CAPSULE_BG     = 0xD009090E; // Półprzezroczysty głęboki obsydian (~82%)
    private static final int COL_CAPSULE_BORDER = 0x22FFFFFF; // Matowe szkło 13% bieli (Zero Glow-Box)
    private static final int COL_SEPARATOR      = 0x20FFFFFF; // Cienkie separatory pionowe
    private static final int COL_ICON_LILAC     = 0xFFC4B5FD; // Jasny pastelowy fiolet ikon
    private static final int COL_TEXT_WHITE     = 0xFFF5F3FF; // Czysta biel wartości głównych
    private static final int COL_TEXT_LILAC     = 0xFFB8A9FF; // Pastelowy fiolet jednostek (ms, tps, bps, inv)
    private static final int COL_TEXT_GRAY      = 0xFF9494A8; // Przyciemniony tekst dla stanu IDLE

    private long lastFrameNs = System.nanoTime();
    private float smoothedBps = 0.0f;
    private float smoothedTps = 20.0f;
    private long lastWorldGameTime = -1L;
    private long lastWorldTimeRealMs = -1L;
    private float pulsePhase = 0.0f;

    private StatusCapsuleHudRenderer() {}

    public static void register() {
        try {
            Class<?> callbackClass = Class.forName("net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback");
        } catch (Throwable ignored) {
        }
    }

    public void render(GuiGraphics g, DeltaTracker deltaTracker) {
        float partialTick = deltaTracker != null ? deltaTracker.getGameTimeDeltaPartialTick(false) : 0.0f;
        render(g, partialTick);
    }

    public void render(GuiGraphics g, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) return;
        if (!AstraBaritoneBridge.getInstance().isStatusCapsuleHud() && !BaritoneBypassScreen.wildPillHud) return;

        long nowNs = System.nanoTime();
        float dt = Math.min(0.1f, (nowNs - lastFrameNs) / 1_000_000_000.0f);
        lastFrameNs = nowNs;
        pulsePhase = (pulsePhase + dt * 4.0f) % ((float) Math.PI * 2.0f);

        updateTelemetry(mc, dt);

        Font font = mc.font;
        String username = mc.player.getName().getString();
        String serverName = mc.isLocalServer() || mc.getCurrentServer() == null
                ? "Singleplayer"
                : mc.getCurrentServer().ip;

        String pingNum = String.valueOf(getPlayerPing(mc));
        String pingUnit = "ms";

        String tpsNum = String.format(Locale.US, "%.0f", smoothedTps);
        String tpsUnit = "tps";

        String bpsNum = String.format(Locale.US, "%.1f", smoothedBps);
        String bpsUnit = "bps";

        String baritoneTask = AstraBaritoneBridge.getActiveTaskName();
        boolean baritoneActive = AstraBaritoneBridge.isBaritoneActive();

        int usedInvSlots = AstraBaritoneBridge.getOccupiedInventorySlots();
        String invText = usedInvSlots + "/36";
        String invUnit = "inv";

        float x = 8.0f;
        float y = 8.0f;
        float h = 24.0f;
        float cy = y + h * 0.5f;

        float wUser   = 18.0f + font.width(username);
        float wServer = 15.0f + font.width(serverName);
        float wPing   = 15.0f + font.width(pingNum) + font.width(pingUnit);
        float wTps    = 15.0f + font.width(tpsNum) + font.width(tpsUnit);
        float wBps    = 15.0f + font.width(bpsNum) + font.width(bpsUnit);
        float wTask   = 18.0f + font.width(baritoneTask);
        float wInv    = 15.0f + font.width(invText) + font.width(invUnit);

        float sepGap = 13.0f;
        float totalW = 10.0f + wUser + sepGap + wServer + sepGap + wPing + sepGap + wTps + sepGap + wBps
                + sepGap + wTask + sepGap + wInv + 10.0f;

        g.flush();

        VenturaSdfRenderer.beginFrame(g);

        // 1. Kierunkowy Drop-Shadow (blur 8px, offset Y +3px, 0x80000000) - zero artefaktów Glow-Box
        VenturaSdfRenderer.drawDropShadow(x, y, totalW, h, 8.0f, 8.0f, 3.0f, 0x80000000);

        // 2. Kapsuła Obsidian Glass z 1-pikselową subtelną ramką 0x22FFFFFF
        VenturaSdfRenderer.drawRoundedRect(x, y, totalW, h, 8.0f, COL_CAPSULE_BG, COL_CAPSULE_BORDER, 1.0f);

        float curX = x + 10.0f;

        // Sekcja 1: Logo V + Nick gracza
        VenturaSdfRenderer.drawCircle(curX + 5.5f, cy, 6.2f, 0x38A78BFA, 0);
        VenturaSdfRenderer.drawIcon(VectorIcon.VENTURA_LOGO, curX + 5.5f, cy, 7.2f, COL_ICON_LILAC);
        float sep1X = curX + wUser + sepGap * 0.5f;
        VenturaSdfRenderer.drawLine(sep1X, cy - 5.5f, sep1X, cy + 5.5f, 0.8f, COL_SEPARATOR);

        // Sekcja 2: Serwer
        float srvX = curX + wUser + sepGap;
        VenturaSdfRenderer.drawIcon(VectorIcon.SERVER_BOX, srvX + 5.0f, cy, 8.0f, COL_ICON_LILAC);
        float sep2X = srvX + wServer + sepGap * 0.5f;
        VenturaSdfRenderer.drawLine(sep2X, cy - 5.5f, sep2X, cy + 5.5f, 0.8f, COL_SEPARATOR);

        // Sekcja 3: Ping (ms)
        float pngX = srvX + wServer + sepGap;
        VenturaSdfRenderer.drawIcon(VectorIcon.WIFI_SIGNAL, pngX + 5.0f, cy, 8.0f, COL_ICON_LILAC);
        float sep3X = pngX + wPing + sepGap * 0.5f;
        VenturaSdfRenderer.drawLine(sep3X, cy - 5.5f, sep3X, cy + 5.5f, 0.8f, COL_SEPARATOR);

        // Sekcja 4: TPS
        float tpsX = pngX + wPing + sepGap;
        VenturaSdfRenderer.drawIcon(VectorIcon.SLIDERS, tpsX + 5.0f, cy, 8.0f, COL_ICON_LILAC);
        float sep4X = tpsX + wTps + sepGap * 0.5f;
        VenturaSdfRenderer.drawLine(sep4X, cy - 5.5f, sep4X, cy + 5.5f, 0.8f, COL_SEPARATOR);

        // Sekcja 5: BPS (prędkość)
        float bpsX = tpsX + wTps + sepGap;
        VenturaSdfRenderer.drawIcon(VectorIcon.SPEEDOMETER, bpsX + 5.0f, cy, 8.4f, COL_ICON_LILAC);
        float sep5X = bpsX + wBps + sepGap * 0.5f;
        VenturaSdfRenderer.drawLine(sep5X, cy - 5.5f, sep5X, cy + 5.5f, 0.8f, COL_SEPARATOR);

        // Sekcja 6: Proces Baritone (Dioda statusu + Task)
        float taskX = bpsX + wBps + sepGap;
        int dotCol = baritoneActive ? BaritoneBypassScreen.COL_STATUS_GREEN : 0xFF646478;
        float dotRadius = baritoneActive ? 3.0f + 0.6f * Mth.sin(pulsePhase) : 2.8f;
        int glowCol = baritoneActive ? BaritoneBypassScreen.COL_ACCENT_GLOW : 0;
        VenturaSdfRenderer.drawCircle(taskX + 5.0f, cy, dotRadius, dotCol, glowCol);
        float sep6X = taskX + wTask + sepGap * 0.5f;
        VenturaSdfRenderer.drawLine(sep6X, cy - 5.5f, sep6X, cy + 5.5f, 0.8f, COL_SEPARATOR);

        // Sekcja 7: Ekwipunek (Shulker / Box)
        float invX = taskX + wTask + sepGap;
        VenturaSdfRenderer.drawIcon(VectorIcon.SHULKER_BOX, invX + 5.0f, cy, 8.0f, COL_ICON_LILAC);

        VenturaSdfRenderer.endFrame();

        // Renderowanie napisów z wyrównaniem do wspólnej osi optycznej cy
        BaritoneBypassScreen.drawCenteredRowTextStatic(g, font, username, curX + 15.5f, cy, COL_TEXT_LILAC);
        BaritoneBypassScreen.drawCenteredRowTextStatic(g, font, serverName, srvX + 13.5f, cy, COL_TEXT_WHITE);

        drawValueAndLilacUnit(g, font, pingNum, pingUnit, pngX + 13.5f, cy);
        drawValueAndLilacUnit(g, font, tpsNum, tpsUnit, tpsX + 13.5f, cy);
        drawValueAndLilacUnit(g, font, bpsNum, bpsUnit, bpsX + 13.5f, cy);

        int taskTextCol = baritoneActive ? COL_TEXT_WHITE : COL_TEXT_GRAY;
        BaritoneBypassScreen.drawCenteredRowTextStatic(g, font, baritoneTask, taskX + 13.5f, cy, taskTextCol);

        drawValueAndLilacUnit(g, font, invText, invUnit, invX + 13.5f, cy);

        // God-Tier Screen Space Visuals: Kinetic Speed Warp Lines & Damage Ripple
        com.astra.revolution.visuals.AstraGodTierVisuals.renderScreenSpeedLinesAndDamageRipple(g, g.guiWidth(), g.guiHeight(), mc.player, partialTick);
    }

    private void drawValueAndLilacUnit(GuiGraphics g, Font font, String num, String unit, float x, float cy) {
        BaritoneBypassScreen.drawCenteredRowTextStatic(g, font, num, x, cy, COL_TEXT_WHITE);
        BaritoneBypassScreen.drawCenteredRowTextStatic(g, font, unit, x + font.width(num) + 1.0f, cy, COL_TEXT_LILAC);
    }

    private void updateTelemetry(Minecraft mc, float dt) {
        double dx = mc.player.getX() - mc.player.xo;
        double dz = mc.player.getZ() - mc.player.zo;
        float instantBps = (float) (Math.hypot(dx, dz) * 20.0);
        smoothedBps = Mth.lerp(Math.min(1.0f, dt * 10.0f), smoothedBps, instantBps);
        if (smoothedBps < 0.05f) smoothedBps = 0.0f;

        long worldTime = mc.level.getGameTime();
        long nowMs = System.currentTimeMillis();
        if (lastWorldGameTime != -1L && worldTime > lastWorldGameTime) {
            long ticks = worldTime - lastWorldGameTime;
            long ms = Math.max(1L, nowMs - lastWorldTimeRealMs);
            float instantTps = Mth.clamp((ticks * 1000.0f) / ms, 0.0f, 20.0f);
            smoothedTps = Mth.lerp(0.2f, smoothedTps, instantTps);
            lastWorldGameTime = worldTime;
            lastWorldTimeRealMs = nowMs;
        } else if (lastWorldGameTime == -1L || worldTime < lastWorldGameTime) {
            lastWorldGameTime = worldTime;
            lastWorldTimeRealMs = nowMs;
        }
    }

    private int getPlayerPing(Minecraft mc) {
        ClientPacketListener conn = mc.getConnection();
        if (conn == null || mc.player == null) return 0;
        PlayerInfo info = conn.getPlayerInfo(mc.player.getUUID());
        return info != null ? Math.max(0, info.getLatency()) : 0;
    }
}
