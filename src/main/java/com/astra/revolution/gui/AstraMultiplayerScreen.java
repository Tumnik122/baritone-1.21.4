package com.astra.revolution.gui;

import com.astra.revolution.gui.BaritoneBypassScreen.VectorIcon;
import com.astra.revolution.gui.BaritoneBypassScreen.VenturaSdfRenderer;
import com.astra.revolution.compat.IasCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DirectJoinServerScreen;
import net.minecraft.client.gui.screens.EditServerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 🌌 ASTRA // ARGON 4 ULTRA-HIGH MULTIPLAYER SCREEN
 * Modern, ultra-high fidelity server selection UI built with Argon 4 glassmorphic aesthetics.
 * Features:
 * - Obsidian Acrylic background with animated neon cyan & violet aura gradients
 * - Bento-grid server cards with real-time ping telemetry, player count pill, and MOTD
 * - Instant search filtering by server name, host address, or MOTD content
 * - Floating high-tech Argon dock with Direct Connect, Add, Edit, Delete, and Refresh
 * - Keyboard shortcuts (Up/Down/Enter/D/A/E/Del/R/Esc) and graceful ping lifecycle
 */
public class AstraMultiplayerScreen extends Screen {

    // =========================================================================
    // ARGON 4 DESIGN SYSTEM (NEON CYAN & ULTRA-VIOLET GLASS PALETTE)
    // =========================================================================
    public static final int COL_ARGON_BG_TOP        = 0xFF050509;
    public static final int COL_ARGON_BG_BOTTOM     = 0xFF0B0916;
    public static final int COL_ARGON_GLASS         = 0xCC100D1F;
    public static final int COL_ARGON_GLASS_HOVER   = 0xEE18142E;
    public static final int COL_ARGON_BORDER        = 0x1FFFFFFF;
    public static final int COL_ARGON_BORDER_HOVER  = 0x4400F0FF;
    public static final int COL_ARGON_BORDER_ACT    = 0x888B5CF6;
    public static final int COL_ARGON_GLOW_ACT      = 0x488B5CF6;
    public static final int COL_ARGON_CYAN_GLOW     = 0x3800F0FF;

    public static final int COL_ARGON_CYAN          = 0xFF00F0FF;
    public static final int COL_ARGON_VIOLET        = 0xFF8B5CF6;
    public static final int COL_ARGON_LILAC         = 0xFFC4B5FD;
    public static final int COL_TEXT_PRIMARY        = 0xFFF8FAFC;
    public static final int COL_TEXT_SECONDARY      = 0xFF94A3B8;
    public static final int COL_TEXT_MUTED          = 0xFF64748B;

    public static final int COL_STATUS_GREEN        = 0xFF10B981;
    public static final int COL_STATUS_CRIMSON      = 0xFFF43F5E;
    public static final int COL_STATUS_AMBER        = 0xFFF59E0B;
    public static final int COL_BG_FIELD            = 0xC00A0814;
    public static final int COL_BORDER_FIELD        = 0x22FFFFFF;

    private final Screen parent;
    private ServerList serverList;
    private final List<ServerEntry> allServers = new ArrayList<>();
    private final List<ServerEntry> filteredServers = new ArrayList<>();
    private ServerEntry selectedServer = null;

    private final ServerStatusPinger pinger = new ServerStatusPinger();

    private String searchQuery = "";
    private boolean searchFocused = false;
    private float searchFocusAnim = 0.0f;

    private float scrollOffset = 0.0f;
    private float targetScroll = 0.0f;
    private float maxScroll = 0.0f;

    private long lastFrameTimeNs = 0L;
    private float globalTime = 0.0f;
    private float introAnim = 0.0f;

    private final List<BgParticle> particles = new ArrayList<>();

    public AstraMultiplayerScreen(Screen parent) {
        super(Component.literal("Astra Argon 4 Multiplayer"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        this.lastFrameTimeNs = System.nanoTime();

        this.particles.clear();
        int count = Math.min(35, (this.width * this.height) / 16000);
        for (int i = 0; i < count; i++) {
            this.particles.add(new BgParticle(
                    (float) (Math.random() * this.width),
                    (float) (Math.random() * this.height),
                    (float) ((Math.random() - 0.5) * 14.0),
                    (float) ((Math.random() - 0.5) * 14.0),
                    (float) (1.5 + Math.random() * 2.5),
                    (float) (Math.random() * Math.PI * 2.0)
            ));
        }

        loadServers();
    }

    private void loadServers() {
        this.pinger.removeAll();
        this.serverList = new ServerList(this.minecraft);
        this.serverList.load();

        this.allServers.clear();
        int sz = this.serverList.size();
        for (int i = 0; i < sz; i++) {
            ServerData data = this.serverList.get(i);
            ServerEntry entry = new ServerEntry(data);
            this.allServers.add(entry);
            pingServer(entry);
        }

        applyFilter();
        if (this.selectedServer == null && !this.filteredServers.isEmpty()) {
            this.selectedServer = this.filteredServers.get(0);
        }
    }

    private void pingServer(ServerEntry entry) {
        entry.pinging = true;
        try {
            this.pinger.pingServer(entry.data, () -> {
                entry.pinging = false;
                entry.updateFromData();
            }, () -> {
                entry.pinging = false;
                entry.updateFromData();
            });
        } catch (Throwable t) {
            entry.pinging = false;
        }
    }

    private void applyFilter() {
        this.filteredServers.clear();
        String q = this.searchQuery.trim().toLowerCase();
        for (ServerEntry entry : this.allServers) {
            if (q.isEmpty()
                    || entry.data.name.toLowerCase().contains(q)
                    || entry.data.ip.toLowerCase().contains(q)
                    || (entry.motdText != null && entry.motdText.toLowerCase().contains(q))) {
                this.filteredServers.add(entry);
            }
        }
        if (this.selectedServer != null && !this.filteredServers.contains(this.selectedServer)) {
            this.selectedServer = this.filteredServers.isEmpty() ? null : this.filteredServers.get(0);
        }
        this.targetScroll = 0.0f;
    }

    @Override
    public void tick() {
        super.tick();
        try {
            this.pinger.tick();
        } catch (Throwable ignored) {}
    }

    @Override
    public void removed() {
        super.removed();
        try {
            this.pinger.removeAll();
        } catch (Throwable ignored) {}
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Celowo puste: wyłącza vanillową panoramę, aby zachować 100% czystego szkła Argon 4 SDF
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        long now = System.nanoTime();
        float dt = Math.min(0.05f, (now - this.lastFrameTimeNs) / 1_000_000_000.0f);
        this.lastFrameTimeNs = now;
        this.globalTime += dt;

        this.introAnim = Mth.lerp(1.0f - (float) Math.exp(-8.0f * dt), this.introAnim, 1.0f);
        this.searchFocusAnim = Mth.lerp(1.0f - (float) Math.exp(-12.0f * dt), this.searchFocusAnim, this.searchFocused ? 1.0f : 0.0f);
        this.scrollOffset = Mth.lerp(1.0f - (float) Math.exp(-14.0f * dt), this.scrollOffset, this.targetScroll);

        // 1. Tło i animowana aura Argon 4
        renderBackgroundAura(mouseX, mouseY, dt);

        // 2. Górny pasek nagłówka
        renderHeader(guiGraphics, mouseX, mouseY);

        // 3. Lista serwerów
        renderServerList(guiGraphics, mouseX, mouseY, dt);

        // 4. Dolny dok akcji
        renderBottomDock(guiGraphics, mouseX, mouseY);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.flush();
    }

    private void renderBackgroundAura(int mouseX, int mouseY, float dt) {
        VenturaSdfRenderer.drawRoundedRect(0, 0, this.width, this.height, 0, COL_ARGON_BG_TOP, 0, 0);
        VenturaSdfRenderer.drawDropShadow(-20, this.height * 0.4f, this.width + 40, this.height * 0.65f, 0, 90.0f, 20.0f, COL_ARGON_BG_BOTTOM);

        float pulse = 0.88f + 0.12f * Mth.sin(this.globalTime * 1.6f);
        VenturaSdfRenderer.drawCircle(this.width * 0.18f, this.height * 0.32f, 240.0f * pulse, 0, 0x1A8B5CF6);
        VenturaSdfRenderer.drawCircle(this.width * 0.85f, this.height * 0.68f, 260.0f * pulse, 0, 0x1400F0FF);

        for (BgParticle p : this.particles) {
            p.update(dt, this.width, this.height);
            VenturaSdfRenderer.drawCircle(p.x, p.y, p.radius, 0x3000F0FF, 0x108B5CF6);
        }
    }

    private void renderHeader(GuiGraphics g, int mouseX, int mouseY) {
        float headerY = 16.0f;
        float headerH = 34.0f;

        VenturaSdfRenderer.drawIcon(VectorIcon.VENTURA_LOGO, 32.0f, headerY + 17.0f, 18.0f, COL_ARGON_CYAN);
        g.drawString(this.font, "ASTRA", 48, (int) (headerY + 6.0f), COL_TEXT_PRIMARY, false);
        g.drawString(this.font, "ARGON 4 // MULTIPLAYER", 86, (int) (headerY + 6.0f), COL_ARGON_LILAC, false);

        int count = this.filteredServers.size();
        String stats = count + (count == 1 ? " server listed" : " servers listed");
        VenturaSdfRenderer.drawCircle(52.0f, headerY + 24.0f, 3.2f, COL_STATUS_GREEN, COL_ARGON_CYAN_GLOW);
        g.drawString(this.font, stats, 60, (int) (headerY + 20.0f), COL_TEXT_MUTED, false);

        // Pasek wyszukiwania
        float searchW = Math.min(260.0f, this.width * 0.32f);
        float searchX = this.width - searchW - 28.0f;
        int searchBorder = VenturaSdfRenderer.lerpColor(COL_BORDER_FIELD, COL_ARGON_CYAN, this.searchFocusAnim);

        VenturaSdfRenderer.drawRoundedRect(searchX, headerY + 3.0f, searchW, 28.0f, 7.5f, COL_BG_FIELD, searchBorder, 1.0f);
        VenturaSdfRenderer.drawIcon(VectorIcon.SEARCH, searchX + 14.0f, headerY + 17.0f, 10.0f,
                this.searchFocused ? COL_ARGON_CYAN : COL_TEXT_MUTED);

        String placeholder = this.searchQuery.isEmpty() ? (this.searchFocused ? "" : "Search servers...") : this.searchQuery;
        int textCol = this.searchQuery.isEmpty() ? COL_TEXT_MUTED : COL_TEXT_PRIMARY;
        g.drawString(this.font, placeholder, (int) (searchX + 28.0f), (int) (headerY + 13.0f), textCol, false);

        // Przycisk In-Game Account Switcher (IAS)
        float accBtnW = 106.0f;
        float accBtnH = 28.0f;
        float accBtnX = searchX - accBtnW - 10.0f;
        float accBtnY = headerY + 3.0f;
        boolean accHover = (mouseX >= accBtnX && mouseX <= accBtnX + accBtnW && mouseY >= accBtnY && mouseY <= accBtnY + accBtnH);

        int accBg = accHover ? 0xEE18142E : COL_BG_FIELD;
        int accBorder = accHover ? COL_ARGON_CYAN : COL_BORDER_FIELD;
        VenturaSdfRenderer.drawRoundedRect(accBtnX, accBtnY, accBtnW, accBtnH, 7.5f, accBg, accBorder, 1.0f);
        if (accHover) {
            VenturaSdfRenderer.drawDropShadow(accBtnX, accBtnY, accBtnW, accBtnH, 7.5f, 10.0f, 2.0f, COL_ARGON_CYAN_GLOW);
        }
        VenturaSdfRenderer.drawIcon(VectorIcon.USER, accBtnX + 13.0f, accBtnY + 14.0f, 10.0f, accHover ? COL_ARGON_CYAN : COL_ARGON_LILAC);
        g.drawString(this.font, "Accounts [U]", (int) (accBtnX + 26.0f), (int) (headerY + 13.0f), accHover ? COL_TEXT_PRIMARY : COL_ARGON_LILAC, false);

        if (this.searchFocused && (System.currentTimeMillis() % 1000L < 500L)) {
            int curX = (int) (searchX + 28.0f + this.font.width(this.searchQuery));
            VenturaSdfRenderer.drawLine(curX, headerY + 9.0f, curX, headerY + 24.0f, 1.2f, COL_ARGON_CYAN);
        }

        VenturaSdfRenderer.drawLine(24.0f, headerY + headerH + 8.0f, this.width - 24.0f, headerY + headerH + 8.0f, 1.0f, 0x18FFFFFF);
    }

    private void renderServerList(GuiGraphics g, int mouseX, int mouseY, float dt) {
        float startY = 66.0f;
        float bottomLimit = this.height - 62.0f;
        float viewH = bottomLimit - startY;

        if (this.filteredServers.isEmpty()) {
            float cx = this.width * 0.5f;
            float cy = startY + viewH * 0.5f;
            String msg = this.searchQuery.isEmpty() ? "No servers found. Click [ADD SERVER] to add one!" : "No results for: \"" + this.searchQuery + "\"";
            int tw = this.font.width(msg);
            VenturaSdfRenderer.drawIcon(VectorIcon.SERVER_BOX, cx, cy - 18.0f, 18.0f, COL_TEXT_MUTED);
            g.drawString(this.font, msg, (int) (cx - tw * 0.5f), (int) (cy + 8.0f), COL_TEXT_MUTED, false);
            g.flush();
            return;
        }

        float margin = 28.0f;
        float contentW = this.width - margin * 2.0f;
        boolean twoCols = contentW >= 660.0f;
        int cols = twoCols ? 2 : 1;
        float gap = 10.0f;
        float cardW = twoCols ? (contentW - gap) * 0.5f : contentW;
        float cardH = 72.0f;

        int rows = (this.filteredServers.size() + cols - 1) / cols;
        float totalH = rows * (cardH + gap) - gap;
        this.maxScroll = Math.max(0.0f, totalH - viewH + 16.0f);

        g.flush();
        VenturaSdfRenderer.enableScissor((int) margin, (int) startY, (int) contentW, (int) viewH);

        for (int i = 0; i < this.filteredServers.size(); i++) {
            ServerEntry server = this.filteredServers.get(i);
            int col = i % cols;
            int row = i / cols;

            float cx = margin + col * (cardW + gap);
            float cy = startY + row * (cardH + gap) - this.scrollOffset;

            if (cy + cardH < startY - 10.0f || cy > bottomLimit + 10.0f) {
                continue;
            }

            boolean isHovered = (mouseX >= cx && mouseX <= cx + cardW && mouseY >= cy && mouseY <= cy + cardH);
            boolean isSelected = (this.selectedServer == server);

            server.hoverAnim = Mth.lerp(1.0f - (float) Math.exp(-12.0f * dt), server.hoverAnim, isHovered ? 1.0f : 0.0f);

            int bgCol = VenturaSdfRenderer.lerpColor(COL_ARGON_GLASS, COL_ARGON_GLASS_HOVER, server.hoverAnim);
            int borderCol = isSelected ? COL_ARGON_BORDER_ACT : (isHovered ? COL_ARGON_BORDER_HOVER : COL_ARGON_BORDER);

            if (isSelected) {
                VenturaSdfRenderer.drawDropShadow(cx, cy, cardW, cardH, 9.0f, 14.0f, 3.0f, COL_ARGON_GLOW_ACT);
            }
            VenturaSdfRenderer.drawRoundedRect(cx, cy, cardW, cardH, 9.0f, bgCol, borderCol, isSelected ? 1.5f : 1.0f);

            // Ikona serwera / Box
            float iconBoxSize = 48.0f;
            float iconX = cx + 11.0f;
            float iconY = cy + (cardH - iconBoxSize) * 0.5f;

            VenturaSdfRenderer.drawRoundedRect(iconX, iconY, iconBoxSize, iconBoxSize, 7.5f, COL_BG_FIELD, isSelected ? COL_ARGON_BORDER_ACT : COL_BORDER_FIELD, 1.0f);
            VenturaSdfRenderer.drawIcon(VectorIcon.SERVER_BOX, iconX + iconBoxSize * 0.5f, iconY + iconBoxSize * 0.5f, 14.0f,
                    isSelected ? COL_ARGON_CYAN : COL_TEXT_SECONDARY);

            // Nazwa serwera i adres IP
            float textX = iconX + iconBoxSize + 12.0f;
            float availW = cardW - (textX - cx) - 95.0f;

            String nameTrimmed = this.font.plainSubstrByWidth(server.data.name, (int) availW);
            g.drawString(this.font, nameTrimmed, (int) textX, (int) (cy + 10.0f), COL_TEXT_PRIMARY, false);

            String ipTrimmed = this.font.plainSubstrByWidth(server.data.ip, (int) availW);
            g.drawString(this.font, ipTrimmed, (int) textX, (int) (cy + 24.0f), COL_ARGON_LILAC, false);

            // MOTD
            if (server.motdComp != null) {
                String motdPlain = this.font.plainSubstrByWidth(server.motdComp.getString(), (int) availW);
                g.drawString(this.font, motdPlain, (int) textX, (int) (cy + 38.0f), COL_TEXT_MUTED, false);
            } else if (server.pinging) {
                g.drawString(this.font, "Pinging server...", (int) textX, (int) (cy + 38.0f), COL_TEXT_MUTED, false);
            } else {
                g.drawString(this.font, "Can't connect to server", (int) textX, (int) (cy + 38.0f), COL_STATUS_CRIMSON, false);
            }

            // Prawy panel: Ping & Liczba graczy
            float rightX = cx + cardW - 12.0f;

            // Ping telemetry
            int pingBarsCol;
            String pingText;
            if (server.pinging) {
                pingBarsCol = COL_STATUS_AMBER;
                pingText = "...";
            } else if (server.data.ping < 0L) {
                pingBarsCol = COL_STATUS_CRIMSON;
                pingText = "ERR";
            } else if (server.data.ping < 90L) {
                pingBarsCol = COL_STATUS_GREEN;
                pingText = server.data.ping + "ms";
            } else if (server.data.ping < 180L) {
                pingBarsCol = COL_STATUS_AMBER;
                pingText = server.data.ping + "ms";
            } else {
                pingBarsCol = COL_STATUS_CRIMSON;
                pingText = server.data.ping + "ms";
            }

            // Sygnał kresek (4 kreski)
            float barStartX = rightX - 16.0f;
            float barY = cy + 12.0f;
            for (int b = 0; b < 4; b++) {
                float bh = 4.0f + b * 2.2f;
                int bcol = (server.data.ping >= 0 && (server.data.ping < 90 || (server.data.ping < 180 && b < 3) || b < 2))
                        ? pingBarsCol : 0x22FFFFFF;
                VenturaSdfRenderer.drawRoundedRect(barStartX + b * 4.0f, barY + (11.0f - bh), 2.5f, bh, 1.0f, bcol, 0, 0);
            }

            int ptw = this.font.width(pingText);
            g.drawString(this.font, pingText, (int) (barStartX - ptw - 5.0f), (int) (barY + 2.0f), pingBarsCol, false);

            // Gracze (Pill)
            String playersStr = server.playerCountStr;
            float pillW = this.font.width(playersStr) + 14.0f;
            float pillX = rightX - pillW;
            float pillY = cy + 28.0f;

            VenturaSdfRenderer.drawRoundedRect(pillX, pillY, pillW, 15.0f, 4.5f, COL_BG_FIELD, COL_BORDER_FIELD, 1.0f);
            g.drawString(this.font, playersStr, (int) (pillX + 7.0f), (int) (pillY + 4.0f), COL_TEXT_SECONDARY, false);

            // Przycisk "CONNECT" na karcie przy hoverze/zaznaczeniu
            if (isHovered || isSelected) {
                float playBtnW = 74.0f;
                float playBtnH = 20.0f;
                float playBtnX = rightX - playBtnW;
                float playBtnY = cy + 47.0f;

                boolean playHover = (mouseX >= playBtnX && mouseX <= playBtnX + playBtnW && mouseY >= playBtnY && mouseY <= playBtnY + playBtnH);
                int btnBg = playHover ? COL_ARGON_VIOLET : 0x2A8B5CF6;
                int btnBorder = playHover ? COL_ARGON_CYAN : COL_ARGON_BORDER_ACT;

                VenturaSdfRenderer.drawRoundedRect(playBtnX, playBtnY, playBtnW, playBtnH, 5.5f, btnBg, btnBorder, 1.0f);
                VenturaSdfRenderer.drawIcon(VectorIcon.PLAY, playBtnX + 14.0f, playBtnY + playBtnH * 0.5f, 7.5f, playHover ? COL_TEXT_PRIMARY : COL_ARGON_CYAN);
                g.drawString(this.font, "Connect", (int) (playBtnX + 24.0f), (int) (playBtnY + 6.0f), playHover ? COL_TEXT_PRIMARY : COL_ARGON_LILAC, false);
            }
        }

        g.flush();
        VenturaSdfRenderer.disableScissor();

        // Pasek przewijania (Scrollbar)
        if (this.maxScroll > 1.0f) {
            float barW = 4.0f;
            float barX = this.width - margin + 8.0f;
            float trackH = viewH;
            float thumbH = Math.max(24.0f, trackH * (viewH / totalH));
            float thumbY = startY + (this.scrollOffset / this.maxScroll) * (trackH - thumbH);

            VenturaSdfRenderer.drawRoundedRect(barX, startY, barW, trackH, 2.0f, 0x11FFFFFF, 0, 0);
            VenturaSdfRenderer.drawRoundedRect(barX, thumbY, barW, thumbH, 2.0f, COL_ARGON_CYAN, 0, 0);
        }
    }

    private void renderBottomDock(GuiGraphics g, int mouseX, int mouseY) {
        float dockW = Math.min(740.0f, this.width - 32.0f);
        float dockH = 40.0f;
        float dockX = (this.width - dockW) * 0.5f;
        float dockY = this.height - dockH - 12.0f;

        VenturaSdfRenderer.drawDropShadow(dockX, dockY, dockW, dockH, 10.0f, 18.0f, 4.0f, 0x90000000);
        VenturaSdfRenderer.drawRoundedRect(dockX, dockY, dockW, dockH, 10.0f, 0xEE0B0916, COL_ARGON_BORDER_ACT, 1.0f);

        String[] actions = {"JOIN [ENTER]", "DIRECT [D]", "ADD [A]", "EDIT [E]", "DELETE [DEL]", "REFRESH [R]", "BACK [ESC]"};
        VectorIcon[] icons = {VectorIcon.PLAY, VectorIcon.BOLT, VectorIcon.LAYERS, VectorIcon.SLIDERS, VectorIcon.TRASH, VectorIcon.COMPASS, VectorIcon.CHEVRON_RIGHT};
        float btnW = dockW / actions.length;

        for (int i = 0; i < actions.length; i++) {
            float bx = dockX + i * btnW;
            boolean hovered = (mouseX >= bx && mouseX <= bx + btnW && mouseY >= dockY && mouseY <= dockY + dockH);

            if (hovered) {
                int glowCol = i == 0 ? COL_ARGON_CYAN_GLOW : (i == 4 ? 0x44F43F5E : 0x248B5CF6);
                int borderCol = i == 0 ? COL_ARGON_CYAN : (i == 4 ? COL_STATUS_CRIMSON : COL_ARGON_BORDER_ACT);
                VenturaSdfRenderer.drawRoundedRect(bx + 3.0f, dockY + 4.0f, btnW - 6.0f, dockH - 8.0f, 7.0f, glowCol, borderCol, 1.0f);
            }

            int iconCol = hovered ? (i == 0 ? COL_ARGON_CYAN : (i == 4 ? COL_STATUS_CRIMSON : COL_ARGON_LILAC)) : COL_TEXT_SECONDARY;
            int textCol = hovered ? COL_TEXT_PRIMARY : COL_TEXT_MUTED;

            VenturaSdfRenderer.drawIcon(icons[i], bx + 12.0f, dockY + dockH * 0.5f, 9.0f, iconCol);
            g.drawString(this.font, actions[i], (int) (bx + 23.0f), (int) (dockY + 16.0f), textCol, false);

            if (i < actions.length - 1) {
                VenturaSdfRenderer.drawLine(bx + btnW, dockY + 8.0f, bx + btnW, dockY + dockH - 8.0f, 1.0f, 0x18FFFFFF);
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        this.targetScroll = Mth.clamp(this.targetScroll - (float) scrollY * 36.0f, 0.0f, this.maxScroll);
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        float headerY = 16.0f;
        float searchW = Math.min(260.0f, this.width * 0.32f);
        float searchX = this.width - searchW - 28.0f;

        float accBtnW = 106.0f;
        float accBtnH = 28.0f;
        float accBtnX = searchX - accBtnW - 10.0f;
        float accBtnY = headerY + 3.0f;

        if (mouseX >= accBtnX && mouseX <= accBtnX + accBtnW && mouseY >= accBtnY && mouseY <= accBtnY + accBtnH) {
            IasCompat.openIasScreen(this);
            return true;
        }

        if (mouseX >= searchX && mouseX <= searchX + searchW && mouseY >= headerY && mouseY <= headerY + 34.0f) {
            this.searchFocused = true;
            return true;
        } else {
            this.searchFocused = false;
        }

        float margin = 28.0f;
        float contentW = this.width - margin * 2.0f;
        boolean twoCols = contentW >= 660.0f;
        int cols = twoCols ? 2 : 1;
        float gap = 10.0f;
        float cardW = twoCols ? (contentW - gap) * 0.5f : contentW;
        float cardH = 72.0f;
        float startY = 66.0f;

        for (int i = 0; i < this.filteredServers.size(); i++) {
            ServerEntry server = this.filteredServers.get(i);
            int col = i % cols;
            int row = i / cols;

            float cx = margin + col * (cardW + gap);
            float cy = startY + row * (cardH + gap) - this.scrollOffset;

            if (mouseX >= cx && mouseX <= cx + cardW && mouseY >= cy && mouseY <= cy + cardH) {
                if (this.selectedServer == server) {
                    joinSelectedServer();
                } else {
                    this.selectedServer = server;
                }
                return true;
            }
        }

        float dockW = Math.min(740.0f, this.width - 32.0f);
        float dockH = 40.0f;
        float dockX = (this.width - dockW) * 0.5f;
        float dockY = this.height - dockH - 12.0f;

        if (mouseY >= dockY && mouseY <= dockY + dockH && mouseX >= dockX && mouseX <= dockX + dockW) {
            float btnW = dockW / 7.0f;
            int idx = (int) ((mouseX - dockX) / btnW);
            switch (idx) {
                case 0 -> joinSelectedServer();
                case 1 -> directConnect();
                case 2 -> addServer();
                case 3 -> editSelectedServer();
                case 4 -> deleteSelectedServer();
                case 5 -> loadServers();
                case 6 -> this.minecraft.setScreen(this.parent);
            }
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (this.searchFocused) {
            this.searchQuery += codePoint;
            applyFilter();
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.searchFocused) {
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (!this.searchQuery.isEmpty()) {
                    this.searchQuery = this.searchQuery.substring(0, this.searchQuery.length() - 1);
                    applyFilter();
                }
                return true;
            } else if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_ESCAPE) {
                this.searchFocused = false;
                return true;
            }
        }

        if (keyCode == GLFW.GLFW_KEY_UP) {
            selectPrevious();
            return true;
        } else if (keyCode == GLFW.GLFW_KEY_DOWN) {
            selectNext();
            return true;
        } else if (keyCode == GLFW.GLFW_KEY_ENTER) {
            joinSelectedServer();
            return true;
        } else if (keyCode == GLFW.GLFW_KEY_D) {
            directConnect();
            return true;
        } else if (keyCode == GLFW.GLFW_KEY_A) {
            addServer();
            return true;
        } else if (keyCode == GLFW.GLFW_KEY_E) {
            editSelectedServer();
            return true;
        } else if (keyCode == GLFW.GLFW_KEY_DELETE) {
            deleteSelectedServer();
            return true;
        } else if (keyCode == GLFW.GLFW_KEY_R) {
            loadServers();
            return true;
        } else if (keyCode == GLFW.GLFW_KEY_U) {
            IasCompat.openIasScreen(this);
            return true;
        } else if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.minecraft.setScreen(this.parent);
            return true;
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void selectPrevious() {
        if (this.filteredServers.isEmpty()) return;
        int idx = this.filteredServers.indexOf(this.selectedServer);
        if (idx > 0) {
            this.selectedServer = this.filteredServers.get(idx - 1);
        }
    }

    private void selectNext() {
        if (this.filteredServers.isEmpty()) return;
        int idx = this.filteredServers.indexOf(this.selectedServer);
        if (idx < this.filteredServers.size() - 1) {
            this.selectedServer = this.filteredServers.get(idx + 1);
        }
    }

    private void joinSelectedServer() {
        if (this.selectedServer == null) return;
        ServerData data = this.selectedServer.data;
        ConnectScreen.startConnecting(this, this.minecraft, ServerAddress.parseString(data.ip), data, false, null);
    }

    private void directConnect() {
        ServerData temp = new ServerData(Component.translatable("selectServer.direct").getString(), "", ServerData.Type.OTHER);
        this.minecraft.setScreen(new DirectJoinServerScreen(this, (confirmed) -> {
            if (confirmed) {
                ConnectScreen.startConnecting(this, this.minecraft, ServerAddress.parseString(temp.ip), temp, false, null);
            } else {
                this.minecraft.setScreen(this);
            }
        }, temp));
    }

    private void addServer() {
        ServerData newServer = new ServerData("Minecraft Server", "", ServerData.Type.OTHER);
        this.minecraft.setScreen(new EditServerScreen(this, (confirmed) -> {
            if (confirmed) {
                this.serverList.add(newServer, false);
                this.serverList.save();
                loadServers();
            }
            this.minecraft.setScreen(this);
        }, newServer));
    }

    private void editSelectedServer() {
        if (this.selectedServer == null) return;
        ServerData data = this.selectedServer.data;
        this.minecraft.setScreen(new EditServerScreen(this, (confirmed) -> {
            if (confirmed) {
                this.serverList.save();
                loadServers();
            }
            this.minecraft.setScreen(this);
        }, data));
    }

    private void deleteSelectedServer() {
        if (this.selectedServer == null) return;
        ServerData data = this.selectedServer.data;
        Component msg = Component.translatable("selectServer.deleteQuestion");
        Component warning = Component.translatable("selectServer.deleteWarning", data.name);

        this.minecraft.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                this.serverList.remove(data);
                this.serverList.save();
                loadServers();
            }
            this.minecraft.setScreen(this);
        }, msg, warning, Component.translatable("selectServer.deleteButton"), CommonComponents.GUI_CANCEL));
    }

    // =========================================================================
    // INNER CLASSES (ENTRY & PARTICLES)
    // =========================================================================

    public static class ServerEntry {
        public final ServerData data;
        public boolean pinging = false;
        public Component motdComp;
        public String motdText = "";
        public String playerCountStr = "0/?";
        public float hoverAnim = 0.0f;

        public ServerEntry(ServerData data) {
            this.data = data;
            updateFromData();
        }

        public void updateFromData() {
            this.motdComp = this.data.motd;
            this.motdText = this.motdComp != null ? this.motdComp.getString() : "";
            if (this.data.players != null) {
                this.playerCountStr = this.data.players.online() + "/" + this.data.players.max();
            } else {
                this.playerCountStr = (this.data.ping >= 0L) ? "Online" : "Offline";
            }
        }
    }

    public static class BgParticle {
        public float x, y, vx, vy, radius, phase;

        public BgParticle(float x, float y, float vx, float vy, float radius, float phase) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.radius = radius;
            this.phase = phase;
        }

        public void update(float dt, float w, float h) {
            this.x += this.vx * dt;
            this.y += this.vy * dt;
            this.phase += dt;
            if (this.x < -10.0f) this.x = w + 10.0f;
            if (this.x > w + 10.0f) this.x = -10.0f;
            if (this.y < -10.0f) this.y = h + 10.0f;
            if (this.y > h + 10.0f) this.y = -10.0f;
        }
    }
}
