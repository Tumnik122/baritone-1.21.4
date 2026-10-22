package baritone.bypass;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.process.FarmProcess;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.Locale;

/**
 * STATUS PILL CAPSULE HUD — Wierne odwzorowanie 1:1 nowoczesnego, luksusowego HUD-u ze zrzutu ekranu:
 *  - Jednolita, idealnie zaokrąglona kapsuła o wysokości 22px (promień końców 11px)
 *  - Ciemnofioletowe szkło indygo z subtelnym lawendowym obramowaniem 1px i cieniem
 *  - Ciemny badge z fioletowym kryształem ◆ i nickiem bota
 *  - Eleganckie, cienkie separatory pionowe │
 *  - Ikony w pastelowej lawendzie (🖥 Serwer, 📶 Ping ms, ⚙ TPS, ⏱ BPS / 🌾 Praca / ⛏ Bypass)
 *  - Czyste, białe wartości i stonowane jednostki
 *  - 100% stabilny rendering odporny na błędy OpenGL i kompatybilny ze wszystkimi klientami
 */
public final class BypassHudOverlay {

    private BypassHudOverlay() {}

    // ═══ Paleta kolorów 1:1 ze zrzutu ekranu ═══
    private static final int COLOR_BG_TOP        = 0xF218152B; // Ciemny fiolet indygo
    private static final int COLOR_BG_BOTTOM     = 0xF2141124; // Głęboki nocny granat
    private static final int COLOR_BORDER_TOP    = 0x559B82FF; // Pastelowa lawendowa krawędź góra
    private static final int COLOR_BORDER_BOTTOM = 0x306A55B8; // Dół obramowania
    private static final int COLOR_SHADOW        = 0x40000000; // Cień kapsuły
    private static final int COLOR_HIGHLIGHT     = 0x1EFFFFFF; // Subtelny górny połysk

    private static final int COLOR_BADGE_BG      = 0x900D0A1A; // Tło kwadratu z logo
    private static final int COLOR_BADGE_BD      = 0x50C084FC; // Ramka badge'a
    private static final int COLOR_BADGE_ICON    = 0xFFC084FC; // Fioletowy kryształ ◆

    private static final int COLOR_SEPARATOR     = 0x30A594F9; // Subtelna kreska podziału │
    private static final int COLOR_ICON          = 0xFFA594F9; // Pastelowa lawenda dla ikon
    private static final int COLOR_WHITE         = 0xFFFFFFFF; // Czysta biel dla nicku i liczb
    private static final int COLOR_UNIT          = 0xFF9E92C7; // Stonowana lawenda dla ms, tps, bps
    private static final int COLOR_GREEN         = 0xFF00FF88; // Zielony akcent tempa
    private static final int COLOR_GOLD          = 0xFFFFCC00; // Złoty kłos pszenicy
    private static final int COLOR_CYAN          = 0xFF00E5FF; // Cyjanowy diament

    private static final int PILL_H = 22;
    private static final int PILL_R = 11;

    private static baritone.bypass.modern.ModernCapsuleHud modernHud;
    private static int modernErrorCount = 0;

    public static void render(GuiGraphics g, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null) return;
        if (!com.astra.revolution.gui.BaritoneBypassScreen.wildPillHud) return;

        try {
            com.astra.revolution.gui.StatusCapsuleHudRenderer.INSTANCE.render(g, deltaTracker);
            return;
        } catch (Throwable t) {
            if (modernErrorCount++ < 5) {
                System.err.println("[BypassHudOverlay] StatusCapsuleHudRenderer błąd: " + t);
                t.printStackTrace();
            }
        }

        Font font = mc.font;
        if (font == null) return;

        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();

        // ════════════════════════════════════════════════════════════════════
        // 1. ZBIERANIE DANYCH 1:1
        // ════════════════════════════════════════════════════════════════════

        // Sekcja 1: Nick bota
        String botName = mc.player.getName().getString();

        // Sekcja 2: Serwer / Tryb
        String serverName = "Singleplayer";
        if (mc.getCurrentServer() != null && mc.getCurrentServer().ip != null && !mc.getCurrentServer().ip.isEmpty()) {
            serverName = mc.getCurrentServer().ip;
        }

        // Sekcja 3: Ping (ms)
        int pingMs = 0;
        if (mc.getConnection() != null) {
            var entry = mc.getConnection().getPlayerInfo(mc.player.getUUID());
            if (entry != null) {
                pingMs = Math.max(0, entry.getLatency());
            }
        }
        String pingVal = String.valueOf(pingMs);

        // Sekcja 4: TPS
        int tpsVal = 20;
        if (mc.level != null) {
            tpsVal = Math.round(mc.level.tickRateManager().tickrate());
        }
        String tpsStr = String.valueOf(tpsVal);

        // Sekcja 5: BPS (prędkość) LUB #praca (pszenica) LUB #bypass (diamenty)
        boolean isPraca = FarmProcess.isPracaModeActive() || (baritone != null && baritone.getFarmProcess().isActive());
        BypassProcess bypassProc = (baritone instanceof Baritone bImpl) ? bImpl.getBypassProcess() : null;
        boolean isBypass = bypassProc != null && bypassProc.isActive();

        double dx = mc.player.getX() - mc.player.xo;
        double dz = mc.player.getZ() - mc.player.zo;
        double bps = Math.sqrt(dx * dx + dz * dz) * 20.0;
        String bpsVal = String.format(Locale.ROOT, "%.1f", bps);

        // ════════════════════════════════════════════════════════════════════
        // 2. OBLICZENIE SZEROKOŚCI SEKCJI I KONTENERA
        // ════════════════════════════════════════════════════════════════════
        int badgeSize = 14;
        int sepW = font.width("│");
        int gap = 6;

        // Sekcja 1: [◆] nick
        int sec1W = badgeSize + 4 + font.width(botName);

        // Sekcja 2: 🖥 server
        int sec2W = font.width("🖥 ") + font.width(serverName);

        // Sekcja 3: 📶 Xms
        int sec3W = font.width("📶 ") + font.width(pingVal) + font.width("ms");

        // Sekcja 4: ⚙ Xtps
        int sec4W = font.width("⚙ ") + font.width(tpsStr) + font.width("tps");

        // Sekcja 5: ⏱ Xbps / Farm / Bypass
        int sec5W;
        if (isPraca) {
            long totalWheat = FarmProcess.getTotalWheatHarvested();
            int wph = (int) FarmProcess.getWheatPerHour();
            String wheatStr = String.format(Locale.ROOT, "%,d", totalWheat);
            String wphStr = String.format(Locale.ROOT, "%,d/h", wph);
            sec5W = font.width("🌾 ") + font.width("Pszenica: ") + font.width(wheatStr + " szt. ") + font.width("(" + wphStr + ")");
        } else if (isBypass) {
            int dia = bypassProc.getDiamondCount();
            int gold = bypassProc.getGoldCount();
            String st = bypassProc.getState().name();
            sec5W = font.width("⛏ ") + font.width("Dia: " + dia + " │ Gold: " + gold + " [" + st + "]");
        } else {
            sec5W = font.width("⏱ ") + font.width(bpsVal) + font.width("bps");
        }

        // Całkowita szerokość kapsuły
        int padSide = 10;
        int totalW = padSide + sec1W + gap + sepW + gap
                   + sec2W + gap + sepW + gap
                   + sec3W + gap + sepW + gap
                   + sec4W + gap + sepW + gap
                   + sec5W + padSide;

        int startX = 8;
        int startY = 8;

        // ════════════════════════════════════════════════════════════════════
        // 3. RENDER KAPSUŁY (Geometria 1:1, gładkie zaokrąglenie, bez artefaktów)
        // ════════════════════════════════════════════════════════════════════
        drawLekozCapsule(g, startX, startY, totalW, PILL_H, PILL_R);

        // ════════════════════════════════════════════════════════════════════
        // 4. RENDER ELEMENTÓW I TYPOGRAFII WEWNĄTRZ KAPSUŁY
        // ════════════════════════════════════════════════════════════════════
        int curX = startX + padSide;
        int textY = startY + (PILL_H - font.lineHeight) / 2 + 1;

        // Sekcja 1: Badge [◆] + Nick
        int badgeY = startY + (PILL_H - badgeSize) / 2;
        drawBadgeSquare(g, curX, badgeY, badgeSize);
        // Fioletowy kryształek w badge'u
        int iconX = curX + (badgeSize - font.width("◆")) / 2;
        g.drawString(font, "◆", iconX, badgeY + 3, COLOR_BADGE_ICON, false);
        curX += badgeSize + 4;
        g.drawString(font, botName, curX, textY, COLOR_WHITE, false);
        curX += font.width(botName) + gap;

        // Separator 1
        g.drawString(font, "│", curX, textY, COLOR_SEPARATOR, false);
        curX += sepW + gap;

        // Sekcja 2: 🖥 Server
        g.drawString(font, "🖥", curX, textY, COLOR_ICON, false);
        curX += font.width("🖥 ");
        g.drawString(font, serverName, curX, textY, COLOR_WHITE, false);
        curX += font.width(serverName) + gap;

        // Separator 2
        g.drawString(font, "│", curX, textY, COLOR_SEPARATOR, false);
        curX += sepW + gap;

        // Sekcja 3: 📶 Ping ms
        g.drawString(font, "📶", curX, textY, COLOR_ICON, false);
        curX += font.width("📶 ");
        g.drawString(font, pingVal, curX, textY, COLOR_WHITE, false);
        curX += font.width(pingVal);
        g.drawString(font, "ms", curX, textY, COLOR_UNIT, false);
        curX += font.width("ms") + gap;

        // Separator 3
        g.drawString(font, "│", curX, textY, COLOR_SEPARATOR, false);
        curX += sepW + gap;

        // Sekcja 4: ⚙ TPS
        g.drawString(font, "⚙", curX, textY, COLOR_ICON, false);
        curX += font.width("⚙ ");
        g.drawString(font, tpsStr, curX, textY, COLOR_WHITE, false);
        curX += font.width(tpsStr);
        g.drawString(font, "tps", curX, textY, COLOR_UNIT, false);
        curX += font.width("tps") + gap;

        // Separator 4
        g.drawString(font, "│", curX, textY, COLOR_SEPARATOR, false);
        curX += sepW + gap;

        // Sekcja 5: BPS / Farm / Bypass
        if (isPraca) {
            long totalWheat = FarmProcess.getTotalWheatHarvested();
            int wph = (int) FarmProcess.getWheatPerHour();
            String wheatStr = String.format(Locale.ROOT, "%,d", totalWheat);
            String wphStr = String.format(Locale.ROOT, "%,d/h", wph);

            g.drawString(font, "🌾", curX, textY, COLOR_GOLD, false);
            curX += font.width("🌾 ");
            g.drawString(font, "Pszenica: ", curX, textY, COLOR_WHITE, false);
            curX += font.width("Pszenica: ");
            g.drawString(font, wheatStr + " szt. ", curX, textY, COLOR_WHITE, false);
            curX += font.width(wheatStr + " szt. ");
            g.drawString(font, "(" + wphStr + ")", curX, textY, COLOR_GREEN, false);
        } else if (isBypass) {
            int dia = bypassProc.getDiamondCount();
            int gold = bypassProc.getGoldCount();
            String st = bypassProc.getState().name();

            g.drawString(font, "⛏", curX, textY, COLOR_CYAN, false);
            curX += font.width("⛏ ");
            g.drawString(font, "Dia: " + dia + " │ Gold: " + gold + " [" + st + "]", curX, textY, COLOR_WHITE, false);
        } else {
            g.drawString(font, "⏱", curX, textY, COLOR_ICON, false);
            curX += font.width("⏱ ");
            g.drawString(font, bpsVal, curX, textY, COLOR_WHITE, false);
            curX += font.width(bpsVal);
            g.drawString(font, "bps", curX, textY, COLOR_UNIT, false);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // MATEMATYCZNY SILNIK RYSOWANIA KAPSUŁY (SCANLINE S-CURVE)
    // ════════════════════════════════════════════════════════════════════

    private static void drawLekozCapsule(GuiGraphics g, int x, int y, int w, int h, int r) {
        // 1. Cień za kapsułą (Drop shadow przesunięty o 1px w dół)
        renderCapsuleSlices(g, x, y + 1, w, h, r, COLOR_SHADOW, COLOR_SHADOW);

        // 2. Zewnętrzne obramowanie (Border)
        renderCapsuleSlices(g, x, y, w, h, r, COLOR_BORDER_TOP, COLOR_BORDER_BOTTOM);

        // 3. Wypełnienie kapsuły z gradientem pionowym (1:1 z obrazka)
        renderCapsuleSlices(g, x + 1, y + 1, w - 2, h - 2, r - 1, COLOR_BG_TOP, COLOR_BG_BOTTOM);

        // 4. Górny połysk (1px highlight)
        g.fill(x + r + 2, y + 1, x + w - r - 2, y + 2, COLOR_HIGHLIGHT);
    }

    /**
     * Rysuje idealnie gładką kapsułę z zaokrąglonymi końcami o promieniu r
     * za pomocą matematycznych wycinków poziomych (zero schodków i zniekształceń).
     */
    private static void renderCapsuleSlices(GuiGraphics g, int x, int y, int w, int h, int r, int colTop, int colBottom) {
        float rSq = (float) (r * r);
        float centerY = r - 0.5f;

        for (int dy = 0; dy < h; dy++) {
            float offsetY = dy - centerY;
            float span = rSq - offsetY * offsetY;
            float halfW = span > 0.0f ? (float) Math.sqrt(span) : 0.0f;

            int xStart = (int) Math.round((x + r) - halfW);
            int xEnd   = (int) Math.round((x + w - r) + halfW);

            if (xEnd > xStart) {
                // Interpolacja koloru między górą a dołem
                float t = (float) dy / (float) (h - 1);
                int scanCol = lerpColor(colTop, colBottom, t);
                g.fill(xStart, y + dy, xEnd, y + dy + 1, scanCol);
            }
        }
    }

    private static void drawBadgeSquare(GuiGraphics g, int x, int y, int size) {
        // Kwadrat z zaokrąglonymi narożnikami dla badge'a logo
        g.fill(x + 1, y, x + size - 1, y + size, COLOR_BADGE_BG);
        g.fill(x, y + 1, x + 1, y + size - 1, COLOR_BADGE_BG);
        g.fill(x + size - 1, y + 1, x + size, y + size - 1, COLOR_BADGE_BG);

        // Ramka badge'a
        g.fill(x + 1, y, x + size - 1, y + 1, COLOR_BADGE_BD);
        g.fill(x + 1, y + size - 1, x + size - 1, y + size, COLOR_BADGE_BD);
        g.fill(x, y + 1, x + 1, y + size - 1, COLOR_BADGE_BD);
        g.fill(x + size - 1, y + 1, x + size, y + size - 1, COLOR_BADGE_BD);
    }

    private static int lerpColor(int c1, int c2, float t) {
        int a1 = (c1 >> 24) & 0xFF, r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
        int a2 = (c2 >> 24) & 0xFF, r2 = (c2 >> 16) & 0xFF, g2 = (c2 >> 8) & 0xFF, b2 = c2 & 0xFF;

        int a = (int) (a1 + (a2 - a1) * t);
        int r = (int) (r1 + (r2 - r1) * t);
        int g = (int) (g1 + (g2 - g1) * t);
        int b = (int) (b1 + (b2 - b1) * t);

        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
