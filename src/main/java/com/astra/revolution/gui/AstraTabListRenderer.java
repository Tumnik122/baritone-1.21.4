package com.astra.revolution.gui;

import com.astra.revolution.gui.BaritoneBypassScreen.VenturaSdfRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.GameType;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ASTRA TAB-LIST RENDERER — Glassmorphism Animated Player List.
 *
 * Pożyczone z MoonDLC SkyRenderingMixin + MixinPlayerListHud:
 * - Slide-in ease-out animacja (0 → 1 w 220ms) gdy gracz otwiera TAB
 * - Slide-out ease-in (1 → 0 w 180ms) gdy gracz zamyka TAB
 * - Glassmorphism tło z chroma border (obracający się gradient HSV)
 * - Chroma ping dots z kolorowaniem latency (zielony < 80ms, żółty < 200ms, czerwony > 200ms)
 * - Kompaktowy grid 4 kolumny zamiast vanilla blobs
 */
public final class AstraTabListRenderer {

    public static final AstraTabListRenderer INSTANCE = new AstraTabListRenderer();

    // Slide-in/out state
    private float easeProgress = 0.0f;       // 0.0 = schowany, 1.0 = w pełni widoczny
    private long lastFrameNs = System.nanoTime();

    // Stałe animacji (inspirowane MoonDLC: ease = 220ms pokazywanie, 180ms chowanie)
    private static final float SLIDE_IN_SPEED  = 1000.0f / 220.0f;   // progress/sekunda przy pokazywaniu
    private static final float SLIDE_OUT_SPEED = 1000.0f / 180.0f;   // progress/sekunda przy chowaniu

    // Kolory glassmorphism (dopasowane do palety Astra: Obsidian & Lilac)
    private static final int COL_BG           = 0xCC060911;   // ~80% ciemny obsydian
    private static final int COL_BG_HEADER    = 0xDD0B0E1A;   // nagłówek ciemniejszy
    private static final int COL_ROW_ODD      = 0x120F1828;   // naprzemienne wiersze (b. subtelne)
    private static final int COL_BORDER       = 0x30FFFFFF;   // matowe szkło
    private static final int COL_SEPARATOR    = 0x18FFFFFF;
    private static final int COL_TEXT_WHITE   = 0xFFF0EEFF;
    private static final int COL_TEXT_LILAC   = 0xFFB8A9FF;
    private static final int COL_TEXT_GRAY    = 0xFF7A7A96;
    private static final int COL_PING_GREEN   = 0xFF4ADE80;
    private static final int COL_PING_YELLOW  = 0xFFFACC15;
    private static final int COL_PING_RED     = 0xFFF87171;
    private static final int COL_YOU_BADGE    = 0xFF7C3AED;   // Fioletowy badge dla "Ty"

    // Wymiary
    private static final float ROW_H      = 13.0f;
    private static final float PADDING    = 8.0f;
    private static final float COL_NAME_W = 110.0f;
    private static final float COL_PING_W = 36.0f;
    private static final float COL_MODE_W = 38.0f;
    private static final int   MAX_COLS   = 4;                // 4 kolumny graczy obok siebie

    private AstraTabListRenderer() {}

    /** Zwraca true jeśli nasz tab-list jest aktywny (widoczny lub w trakcie animacji). */
    public boolean isActive() {
        return easeProgress > 0.001f;
    }

    /**
     * Wywoływane z MixinGui co klatkę. Renderuje własny tab-list jeśli gracz trzyma TAB.
     */
    public boolean render(GuiGraphics g, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return false;

        // Animacja slide
        long nowNs = System.nanoTime();
        float dt = Math.min(0.1f, (nowNs - lastFrameNs) / 1_000_000_000.0f);
        lastFrameNs = nowNs;

        boolean tabDown = mc.options.keyPlayerList.isDown();
        if (tabDown) {
            easeProgress = Math.min(1.0f, easeProgress + dt * SLIDE_IN_SPEED);
        } else {
            easeProgress = Math.max(0.0f, easeProgress - dt * SLIDE_OUT_SPEED);
        }

        if (easeProgress <= 0.001f) return false; // W pełni schowany

        // Ease-out quart: f(t) = 1 - (1-t)^4
        float ease = easeOut(easeProgress);

        // Pobierz listę graczy
        ClientPacketListener conn = mc.getConnection();
        if (conn == null) return false;

        List<PlayerInfo> players = conn.getListedOnlinePlayers()
                .stream()
                .sorted(Comparator.comparing(p -> p.getProfile().getName()))
                .collect(Collectors.toList());

        int total = players.size();
        if (total == 0) return false;

        Font font = mc.font;
        int screenW = g.guiWidth();
        int screenH = g.guiHeight();

        // Siatka: max MAX_COLS kolumn
        int cols = Math.min(MAX_COLS, total);
        int rows = (int) Math.ceil((double) total / cols);

        float colW    = COL_NAME_W + COL_PING_W + COL_MODE_W + 4.0f;
        float panelW  = PADDING + cols * colW + (cols - 1) * 4.0f + PADDING;
        float headerH = ROW_H + 6.0f;
        float panelH  = headerH + rows * ROW_H + PADDING;

        float panelX = (screenW - panelW) / 2.0f;
        // Slide-in od góry (jak MoonDLC — panel wjeżdża z góry ekranu)
        float panelY = -panelH + ease * (8.0f + panelH);

        // --- Chroma border glow (inspirowane MoonDLC + Astra estetyka) ---
        float time = (System.currentTimeMillis() % 8000L) / 8000.0f;
        int chromaA = (int)(ease * 200);
        renderChromaBorder(g, panelX - 1, panelY - 1, panelW + 2, panelH + 2, time, chromaA);

        int bgAlpha = (int)(ease * 0xCC);

        // --- Panel Background ---
        VenturaSdfRenderer.beginFrame(g);
        // Drop shadow
        VenturaSdfRenderer.drawDropShadow(panelX, panelY, panelW, panelH, 12.0f, 12.0f, 4.0f,
                (int)(ease * 0x60) << 24 | 0x000000);

        // Główne tło z zaokrąglonymi rogami
        VenturaSdfRenderer.drawRoundedRect(panelX, panelY, panelW, panelH, 8.0f,
                (bgAlpha << 24) | 0x060911,
                (int)(ease * 0x30) << 24 | 0xFFFFFF, 1.0f);

        // Header strip
        VenturaSdfRenderer.drawRoundedRect(panelX, panelY, panelW, headerH, 8.0f,
                (int)(ease * 0xDD) << 24 | 0x0B0E1A,
                0, 0.0f);
        VenturaSdfRenderer.endFrame();

        // Header tekst — "ASTRA • {serverName} • {total} online"
        String serverName = mc.isLocalServer() || mc.getCurrentServer() == null
                ? "Singleplayer"
                : mc.getCurrentServer().ip;
        int headerTextAlpha = (int)(ease * 0xFF);
        String headerLeft  = "§b§lASTRA";
        String headerRight = total + " online";
        String headerMid   = "§7" + serverName;

        float headerCy = panelY + headerH * 0.5f;
        drawFadedText(g, font, headerLeft,  panelX + PADDING, headerCy, (headerTextAlpha << 24) | 0x7C3AED);
        drawFadedText(g, font, headerMid,   panelX + panelW * 0.5f - font.width(serverName) * 0.5f, headerCy, (headerTextAlpha << 24) | 0x9494A8);
        drawFadedText(g, font, headerRight, panelX + panelW - PADDING - font.width(headerRight), headerCy, (headerTextAlpha << 24) | 0x4ADE80);

        // Separator pod headerem
        g.fill((int)(panelX + 4), (int)(panelY + headerH - 1), (int)(panelX + panelW - 4), (int)(panelY + headerH),
                (int)(ease * 0x30) << 24 | 0xFFFFFF);

        // --- Wiersze graczy ---
        String localName = mc.player.getName().getString();
        for (int i = 0; i < total; i++) {
            PlayerInfo info = players.get(i);
            String name = info.getProfile().getName();
            int ping = info.getLatency();
            GameType mode = info.getGameMode();

            int col  = i % cols;
            int row  = i / cols;

            float cellX = panelX + PADDING + col * (colW + 4.0f);
            float cellY = panelY + headerH + row * ROW_H;
            float cy    = cellY + ROW_H * 0.5f;

            // Naprzemienne tło wiersza (bardzo subtelne)
            if (row % 2 == 0) {
                g.fill((int)cellX, (int)cellY, (int)(cellX + colW), (int)(cellY + ROW_H),
                        (int)(ease * 0x12) << 24 | 0x0F1828);
            }

            // Badge "YOU" dla lokalnego gracza
            boolean isYou = name.equals(localName);
            if (isYou) {
                VenturaSdfRenderer.beginFrame(g);
                VenturaSdfRenderer.drawRoundedRect(cellX + 2, cy - 4.5f, 22.0f, 9.0f, 3.0f,
                        (int)(ease * 0xAA) << 24 | 0x4C1D95,
                        (int)(ease * 0x60) << 24 | 0x7C3AED, 0.8f);
                VenturaSdfRenderer.endFrame();
                drawFadedText(g, font, "YOU", cellX + 4, cy, (int)(ease * 0xFF) << 24 | 0xC4B5FD);
            }

            // Imię gracza
            int nameColor = isYou
                    ? (int)(ease * 0xFF) << 24 | 0xDDD6FE
                    : (int)(ease * 0xFF) << 24 | 0xF0EEFF;
            float nameX = isYou ? cellX + 27.0f : cellX + 4.0f;
            drawFadedText(g, font, name, nameX, cy, nameColor);

            // Ping dot + liczba
            int pingColor;
            if (ping < 0)       pingColor = COL_TEXT_GRAY;
            else if (ping < 80) pingColor = COL_PING_GREEN;
            else if (ping < 200) pingColor = COL_PING_YELLOW;
            else                pingColor = COL_PING_RED;

            String pingStr = ping < 0 ? "???" : ping + "ms";
            float pingX = cellX + COL_NAME_W + 2.0f;
            drawFadedText(g, font, pingStr, pingX, cy, (int)(ease * 0xFF) << 24 | (pingColor & 0x00FFFFFF));

            // Mode badge (S=Survival, C=Creative, A=Adventure, SP=Spectator)
            if (mode != null) {
                String modeStr = switch (mode) {
                    case SURVIVAL  -> "S";
                    case CREATIVE  -> "C";
                    case ADVENTURE -> "A";
                    case SPECTATOR -> "SP";
                };
                int modeCol = switch (mode) {
                    case SURVIVAL  -> 0x4ADE80;
                    case CREATIVE  -> 0x60A5FA;
                    case ADVENTURE -> 0xFBBF24;
                    case SPECTATOR -> 0xA78BFA;
                };
                float modeX = cellX + COL_NAME_W + COL_PING_W + 2.0f;
                drawFadedText(g, font, modeStr, modeX, cy, (int)(ease * 0xCC) << 24 | modeCol);
            }
        }

        return easeProgress > 0.001f; // true = mamy kontrolę (ale nie cancellujemy vanilla całkowicie)
    }

    // --- Chroma obracający się gradient border (inspirowany MoonDLC SkyRenderingMixin star color redirect) ---
    private static void renderChromaBorder(GuiGraphics g, float x, float y, float w, float h, float hueOffset, int alpha) {
        if (alpha <= 0) return;
        int segments = 32;
        // Górna krawędź: lewo → prawo
        for (int i = 0; i < segments; i++) {
            float t0 = (float) i / segments;
            float t1 = (float)(i + 1) / segments;
            float hue0 = (hueOffset + t0 * 0.35f) % 1.0f;
            float hue1 = (hueOffset + t1 * 0.35f) % 1.0f;
            int c0 = (alpha << 24) | (Mth.hsvToRgb(hue0, 0.70f, 1.0f) & 0x00FFFFFF);
            int c1 = (alpha << 24) | (Mth.hsvToRgb(hue1, 0.70f, 1.0f) & 0x00FFFFFF);
            g.fill((int)(x + t0 * w), (int)y, (int)(x + t1 * w), (int)(y + 1.5f), c0);
        }
        // Dolna krawędź: prawo → lewo
        for (int i = 0; i < segments; i++) {
            float t0 = (float) i / segments;
            float t1 = (float)(i + 1) / segments;
            float hue0 = (hueOffset + 0.5f + t0 * 0.35f) % 1.0f;
            float hue1 = (hueOffset + 0.5f + t1 * 0.35f) % 1.0f;
            int c0 = (alpha << 24) | (Mth.hsvToRgb(hue0, 0.70f, 1.0f) & 0x00FFFFFF);
            int c1 = (alpha << 24) | (Mth.hsvToRgb(hue1, 0.70f, 1.0f) & 0x00FFFFFF);
            g.fill((int)(x + (1.0f - t1) * w), (int)(y + h - 1.5f), (int)(x + (1.0f - t0) * w), (int)(y + h), c1);
        }
        // Lewa krawędź
        for (int i = 0; i < segments / 2; i++) {
            float t = (float) i / (segments / 2);
            float hue = (hueOffset + 0.25f + t * 0.15f) % 1.0f;
            int c = (alpha << 24) | (Mth.hsvToRgb(hue, 0.70f, 1.0f) & 0x00FFFFFF);
            g.fill((int)x, (int)(y + t * h), (int)(x + 1.5f), (int)(y + (t + 2.0f / segments) * h), c);
        }
        // Prawa krawędź
        for (int i = 0; i < segments / 2; i++) {
            float t = (float) i / (segments / 2);
            float hue = (hueOffset + 0.75f + t * 0.15f) % 1.0f;
            int c = (alpha << 24) | (Mth.hsvToRgb(hue, 0.70f, 1.0f) & 0x00FFFFFF);
            g.fill((int)(x + w - 1.5f), (int)(y + t * h), (int)(x + w), (int)(y + (t + 2.0f / segments) * h), c);
        }
    }

    private static void drawFadedText(GuiGraphics g, Font font, String text, float x, float cy, int argb) {
        g.drawString(font, text, (int)x, (int)(cy - font.lineHeight * 0.5f), argb, false);
    }

    /** Ease-out quart: f(t) = 1 - (1-t)^4 */
    private static float easeOut(float t) {
        float inv = 1.0f - t;
        return 1.0f - inv * inv * inv * inv;
    }
}
