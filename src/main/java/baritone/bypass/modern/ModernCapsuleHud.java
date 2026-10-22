package baritone.bypass.modern;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.process.FarmProcess;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.joml.Matrix4f;

import java.util.Locale;

/**
 * ModernCapsuleHud — Luksusowy, wektorowy pasek HUD w stylu Status Capsule 1:1.
 * Oparty na shaderach proceduralnych GLSL SDF, gładkim foncie TrueType (bez klocków MC)
 * oraz wektorowych ikonkach (Avatar, Server, Wi-Fi, CPU, Speedometer / Wheat).
 */
public final class ModernCapsuleHud {
    static {
        // Wymagane, aby AWT Font i BufferedImage działały stabilnie na wszystkich launcherach
        try {
            System.setProperty("java.awt.headless", "false");
        } catch (Throwable ignored) {}
    }

    // Dokładna paleta kolorów
    private static final int COLOR_WHITE_TEXT  = 0xFFFFFFFF; // #FFFFFF (Główne wartości)
    private static final int COLOR_LILAC_UNIT  = 0xFF8E82B5; // #8E82B5 (Jednostki: ms, tps, bps)
    private static final int COLOR_GREEN_RATE  = 0xFF00FF88; // #00FF88 (Zielony akcent tempa /h)
    private static final int COLOR_SEPARATOR   = 0xFF2D2545; // #2D2545 (Pionowe separatory │)

    // Wymiary geometryczne kapsuły (w jednostkach GUI)
    private static final float CAPSULE_HEIGHT  = 23.0f;
    private static final float CAPSULE_RADIUS  = CAPSULE_HEIGHT / 2.0f; // 11.5px (idealna kapsuła)
    private static final float PADDING_X       = 8.5f;
    private static final float SECTION_GAP     = 7.5f;
    private static final float ICON_TEXT_GAP   = 4.5f;
    private static final float VALUE_UNIT_GAP  = 2.2f;

    // Rozmiary ikon
    private static final float AVATAR_SIZE     = 14.0f;
    private static final float MODULE_ICON_SZ  = 11.5f;

    private SmoothFontRenderer fontRenderer;
    private boolean fontAttempted = false;
    private final GlStateBackup glBackup = new GlStateBackup();
    private static int renderErrorCount = 0;

    public void render(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) {
            return;
        }

        try {
            renderInternal(guiGraphics, deltaTracker, mc);
        } catch (Throwable t) {
            if (renderErrorCount++ < 5) {
                System.err.println("[ModernCapsuleHUD] Błąd w render: " + t);
                t.printStackTrace();
            }
            throw t;
        }
    }

    private void renderInternal(GuiGraphics guiGraphics, DeltaTracker deltaTracker, Minecraft mc) {
        // Aktualizacja telemetryczna BPS i TPS
        TpsTracker.updateOnClientTick(mc);

        if (this.fontRenderer == null && !fontAttempted) {
            fontAttempted = true;
            try {
                this.fontRenderer = new SmoothFontRenderer(8.5f);
            } catch (Throwable t) {
                System.err.println("[ModernCapsuleHUD] Nie udało się zainicjalizować SmoothFontRenderer, używam fontu MC: " + t);
            }
        }

        boolean useSmooth = this.fontRenderer != null && this.fontRenderer.isAvailable();

        // 1. Pobranie danych telemetrycznych
        String playerName = mc.player.getName().getString();
        String serverLabel = resolveServerName(mc);
        String pingVal = String.valueOf(resolvePing(mc));
        String tpsVal = String.valueOf(TpsTracker.getRoundedTps());

        // Sprawdzenie czy aktywny jest proces Astra Continuous Farm / Sell (#farm, #sell) lub tryb #praca
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        com.astra.revolution.process.FarmProcess astraFarm = com.astra.revolution.process.FarmProcess.getInstance();
        boolean isAstraActive = astraFarm != null && (astraFarm.isActive() || astraFarm.getSellProcess().isActive());
        boolean isPraca = FarmProcess.isPracaModeActive() || (baritone != null && baritone.getFarmProcess().isActive());

        String mod5Label = null;
        String mod5Val;
        String mod5Unit;
        int mod5Icon = 4; // domyślnie prędkościomierz
        int mod5UnitColor = COLOR_LILAC_UNIT;

        if (isAstraActive) {
            com.astra.revolution.process.FarmProcess.HudTelemetrySnapshot snap = astraFarm.getTelemetrySnapshot();
            mod5Icon = 5; // kłos pszenicy
            mod5Label = snap.processStateLabel() + ": ";
            if (snap.earningsPerHour() > 0.0) {
                mod5Val = String.format(Locale.ROOT, "%,.0f/h", snap.cropsPerHour());
                mod5Unit = " ($" + String.format(Locale.ROOT, "%.2f/h", snap.earningsPerHour()) + ")";
            } else {
                mod5Val = String.format(Locale.ROOT, "%,.0f/h", snap.cropsPerHour());
                mod5Unit = " [" + snap.topologyMode() + "]";
            }
            mod5UnitColor = COLOR_GREEN_RATE;
        } else if (isPraca) {
            long totalWheat = FarmProcess.getTotalWheatHarvested();
            int wph = (int) FarmProcess.getWheatPerHour();
            mod5Icon = 5; // kłos pszenicy
            mod5Label = "Pszenica: ";
            mod5Val = String.format(Locale.ROOT, "%,d", totalWheat) + " szt.";
            mod5Unit = " (" + String.format(Locale.ROOT, "%,d/h", wph) + ")";
            mod5UnitColor = COLOR_GREEN_RATE;
        } else {
            mod5Val = String.format(Locale.ROOT, "%.1f", TpsTracker.getSmoothedBps());
            mod5Unit = "bps";
        }

        // 2. Obliczenie szerokości każdego z 5 modułów
        float wMod1 = AVATAR_SIZE + ICON_TEXT_GAP + strWidth(playerName, mc, useSmooth);
        float wMod2 = MODULE_ICON_SZ + ICON_TEXT_GAP + strWidth(serverLabel, mc, useSmooth);
        float wMod3 = MODULE_ICON_SZ + ICON_TEXT_GAP + strWidth(pingVal, mc, useSmooth) + VALUE_UNIT_GAP + strWidth("ms", mc, useSmooth);
        float wMod4 = MODULE_ICON_SZ + ICON_TEXT_GAP + strWidth(tpsVal, mc, useSmooth) + VALUE_UNIT_GAP + strWidth("tps", mc, useSmooth);

        float wMod5 = MODULE_ICON_SZ + ICON_TEXT_GAP
                + (mod5Label != null ? strWidth(mod5Label, mc, useSmooth) : 0f)
                + strWidth(mod5Val, mc, useSmooth)
                + VALUE_UNIT_GAP
                + strWidth(mod5Unit, mc, useSmooth);

        // 4 separatory między 5 modułami (każdy ma odstęp SECTION_GAP z lewej i prawej + 1px grubości)
        float totalSeparatorsWidth = 4 * (SECTION_GAP * 2.0f + 1.0f);
        float capsuleWidth = PADDING_X * 2.0f + wMod1 + wMod2 + wMod3 + wMod4 + wMod5 + totalSeparatorsWidth;

        // Pozycja kapsuły na ekranie (lewy górny róg)
        float startX = 12.0f;
        float startY = 12.0f;

        // Macierz projekcji ortograficznej w pełni zsynchronizowana z pikselami interfejsu GuiGraphics (0..guiWidth, 0..guiHeight)
        float guiW = (float) Math.max(1, guiGraphics.guiWidth());
        float guiH = (float) Math.max(1, guiGraphics.guiHeight());
        Matrix4f orthoProj = new Matrix4f().setOrtho(0.0f, guiW, guiH, 0.0f, -1000.0f, 3000.0f)
                .mul(guiGraphics.pose().last().pose());
        float guiScale = (float) mc.getWindow().getGuiScale();
        float onePhysicalPixelInGui = 1.0f / Math.max(1.0f, guiScale);

        this.glBackup.capture();
        try {
            SdfShaderManager sdf = SdfShaderManager.getInstance();

            // 1. Renderowanie tła kapsuły SDF + obrysu 1px + cienia
            sdf.drawCapsule(orthoProj, startX, startY, capsuleWidth, CAPSULE_HEIGHT, CAPSULE_RADIUS, Math.max(0.65f, onePhysicalPixelInGui));

            float cursorX = startX + PADDING_X;
            float centerY = startY + CAPSULE_HEIGHT * 0.5f;
            float textH = useSmooth ? this.fontRenderer.getFontHeight() : mc.font.lineHeight;
            float textY = centerY - textH * 0.5f + (useSmooth ? 0.5f : 1.0f);

            // --- MODUŁ 1: Avatar Gracza (Squircle + Kryształ) + Nick ---
            sdf.drawVectorIcon(orthoProj, cursorX, centerY - AVATAR_SIZE * 0.5f, AVATAR_SIZE, 0);
            cursorX += AVATAR_SIZE + ICON_TEXT_GAP;
            drawText(guiGraphics, orthoProj, playerName, cursorX, textY, COLOR_WHITE_TEXT, mc, useSmooth);
            cursorX += strWidth(playerName, mc, useSmooth);

            cursorX = drawSeparatorBar(sdf, orthoProj, cursorX, centerY, onePhysicalPixelInGui);

            // --- MODUŁ 2: Serwer Rack + Nazwa Serwera / Singleplayer ---
            sdf.drawVectorIcon(orthoProj, cursorX, centerY - MODULE_ICON_SZ * 0.5f, MODULE_ICON_SZ, 1);
            cursorX += MODULE_ICON_SZ + ICON_TEXT_GAP;
            drawText(guiGraphics, orthoProj, serverLabel, cursorX, textY, COLOR_WHITE_TEXT, mc, useSmooth);
            cursorX += strWidth(serverLabel, mc, useSmooth);

            cursorX = drawSeparatorBar(sdf, orthoProj, cursorX, centerY, onePhysicalPixelInGui);

            // --- MODUŁ 3: Ping (Squircle Wi-Fi + wartość + "ms") ---
            sdf.drawVectorIcon(orthoProj, cursorX, centerY - MODULE_ICON_SZ * 0.5f, MODULE_ICON_SZ, 2);
            cursorX += MODULE_ICON_SZ + ICON_TEXT_GAP;
            cursorX = drawValueWithUnit(guiGraphics, orthoProj, cursorX, textY, pingVal, "ms", COLOR_LILAC_UNIT, mc, useSmooth);

            cursorX = drawSeparatorBar(sdf, orthoProj, cursorX, centerY, onePhysicalPixelInGui);

            // --- MODUŁ 4: TPS (Chip/Procesor + wartość + "tps") ---
            sdf.drawVectorIcon(orthoProj, cursorX, centerY - MODULE_ICON_SZ * 0.5f, MODULE_ICON_SZ, 3);
            cursorX += MODULE_ICON_SZ + ICON_TEXT_GAP;
            cursorX = drawValueWithUnit(guiGraphics, orthoProj, cursorX, textY, tpsVal, "tps", COLOR_LILAC_UNIT, mc, useSmooth);

            cursorX = drawSeparatorBar(sdf, orthoProj, cursorX, centerY, onePhysicalPixelInGui);

            // --- MODUŁ 5: Prędkość BPS (Speedometer) LUB Pszenica (Wheat Tracker) ---
            sdf.drawVectorIcon(orthoProj, cursorX, centerY - MODULE_ICON_SZ * 0.5f, MODULE_ICON_SZ, mod5Icon);
            cursorX += MODULE_ICON_SZ + ICON_TEXT_GAP;
            if (mod5Label != null) {
                drawText(guiGraphics, orthoProj, mod5Label, cursorX, textY, COLOR_WHITE_TEXT, mc, useSmooth);
                cursorX += strWidth(mod5Label, mc, useSmooth);
            }
            drawValueWithUnit(guiGraphics, orthoProj, cursorX, textY, mod5Val, mod5Unit, mod5UnitColor, mc, useSmooth);

        } finally {
            this.glBackup.restore();
        }
    }

    private float drawValueWithUnit(GuiGraphics g, Matrix4f proj, float x, float textY, String value, String unit, int unitColor, Minecraft mc, boolean useSmooth) {
        drawText(g, proj, value, x, textY, COLOR_WHITE_TEXT, mc, useSmooth);
        float valW = strWidth(value, mc, useSmooth);
        float unitX = x + valW + VALUE_UNIT_GAP;
        drawText(g, proj, unit, unitX, textY, unitColor, mc, useSmooth);
        return unitX + strWidth(unit, mc, useSmooth);
    }

    private void drawText(GuiGraphics g, Matrix4f proj, String text, float x, float y, int argb, Minecraft mc, boolean useSmooth) {
        if (text == null || text.isEmpty()) return;
        if (useSmooth && this.fontRenderer != null) {
            this.fontRenderer.drawString(proj, text, x, y, argb);
        } else {
            g.drawString(mc.font, text, (int) Math.round(x), (int) Math.round(y), argb, false);
        }
    }

    private float strWidth(String text, Minecraft mc, boolean useSmooth) {
        if (text == null || text.isEmpty()) return 0f;
        if (useSmooth && this.fontRenderer != null) {
            return this.fontRenderer.getStringWidth(text);
        } else {
            return (float) mc.font.width(text);
        }
    }

    private float drawSeparatorBar(SdfShaderManager sdf, Matrix4f proj, float cursorX, float centerY, float onePx) {
        float sepX = cursorX + SECTION_GAP;
        float sepHeight = 10.5f;
        float sepWidth = Math.max(0.75f, onePx);
        sdf.drawSeparator(proj, sepX, centerY - sepHeight * 0.5f, sepWidth, sepHeight, COLOR_SEPARATOR);
        return sepX + sepWidth + SECTION_GAP;
    }

    private static String resolveServerName(Minecraft mc) {
        if (mc.isLocalServer() || mc.getCurrentServer() == null) {
            return "Singleplayer";
        }
        return mc.getCurrentServer().ip;
    }

    private static int resolvePing(Minecraft mc) {
        if (mc.isLocalServer() || mc.player == null) {
            return 0;
        }
        ClientPacketListener conn = mc.getConnection();
        if (conn == null) {
            return 0;
        }
        PlayerInfo info = conn.getPlayerInfo(mc.player.getUUID());
        return info != null ? Math.max(0, info.getLatency()) : 0;
    }
}
