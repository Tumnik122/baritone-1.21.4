package com.astra.revolution.gui;

import com.astra.revolution.gui.BaritoneBypassScreen.VectorIcon;
import com.astra.revolution.gui.BaritoneBypassScreen.VenturaSdfRenderer;
import com.astra.revolution.compat.IasCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.EditWorldScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;
import org.lwjgl.glfw.GLFW;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 🌌 ASTRA // ARGON 4 ULTRA-HIGH SINGLEPLAYER SCREEN
 * Modern, ultra-high fidelity world selection UI built with Argon 4 glassmorphic aesthetics.
 * Features:
 * - Obsidian Acrylic background with animated neon cyan & violet aura gradients
 * - Bento-grid world cards with real-time smooth hover animations and dual-tone glows
 * - Quick-play action, live search with instant filtering, world deletion with confirmation dialog
 * - High-tech floating Argon dock with primary gradient play button and keyboard shortcuts
 */
public class AstraSingleplayerScreen extends Screen {

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

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("dd.MM.yyyy HH:mm");

    private final Screen parent;
    private final List<WorldEntry> allWorlds = new ArrayList<>();
    private final List<WorldEntry> filteredWorlds = new ArrayList<>();
    private WorldEntry selectedWorld = null;

    private String searchQuery = "";
    private boolean searchFocused = false;
    private float searchFocusAnim = 0.0f;

    private float scrollOffset = 0.0f;
    private float targetScroll = 0.0f;
    private float maxScroll = 0.0f;

    private long lastFrameTimeNs = 0L;
    private float globalTime = 0.0f;
    private float introAnim = 0.0f;
    private boolean loading = true;
    private String statusMessage = "Wczytywanie lokalnych światów...";

    private final List<BgParticle> particles = new ArrayList<>();

    public AstraSingleplayerScreen(Screen parent) {
        super(Component.literal("Astra Argon 4 Singleplayer"));
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

        loadWorldSummaries();
    }

    private void loadWorldSummaries() {
        this.loading = true;
        this.statusMessage = "Skanowanie biblioteki światów...";

        LevelStorageSource levelSource = this.minecraft.getLevelSource();
        try {
            LevelStorageSource.LevelCandidates candidates = levelSource.findLevelCandidates();
            levelSource.loadLevelSummaries(candidates).thenAcceptAsync(summaries -> {
                this.allWorlds.clear();
                for (LevelSummary s : summaries) {
                    this.allWorlds.add(new WorldEntry(s));
                }
                applyFilter();
                this.loading = false;
                if (this.selectedWorld == null && !this.filteredWorlds.isEmpty()) {
                    this.selectedWorld = this.filteredWorlds.get(0);
                }
            }, this.minecraft);
        } catch (Throwable t) {
            this.loading = false;
            this.statusMessage = "Błąd odczytu światów: " + t.getMessage();
        }
    }

    private void applyFilter() {
        this.filteredWorlds.clear();
        String q = this.searchQuery.trim().toLowerCase();
        for (WorldEntry entry : this.allWorlds) {
            if (q.isEmpty()
                    || entry.displayName.toLowerCase().contains(q)
                    || entry.levelId.toLowerCase().contains(q)
                    || entry.modeLabel.toLowerCase().contains(q)) {
                this.filteredWorlds.add(entry);
            }
        }
        if (this.selectedWorld != null && !this.filteredWorlds.contains(this.selectedWorld)) {
            this.selectedWorld = this.filteredWorlds.isEmpty() ? null : this.filteredWorlds.get(0);
        }
        this.targetScroll = 0.0f;
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

        // 3. Siatka światów
        renderWorldList(guiGraphics, mouseX, mouseY, dt);

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
        g.drawString(this.font, "ARGON 4 // SINGLEPLAYER", 86, (int) (headerY + 6.0f), COL_ARGON_LILAC, false);

        int count = this.filteredWorlds.size();
        String stats = count + (count == 1 ? " world available" : " worlds available");
        VenturaSdfRenderer.drawCircle(52.0f, headerY + 24.0f, 3.2f, COL_STATUS_GREEN, COL_ARGON_CYAN_GLOW);
        g.drawString(this.font, stats, 60, (int) (headerY + 20.0f), COL_TEXT_MUTED, false);

        // Pasek wyszukiwania
        float searchW = Math.min(260.0f, this.width * 0.32f);
        float searchX = this.width - searchW - 28.0f;
        int searchBorder = VenturaSdfRenderer.lerpColor(COL_BORDER_FIELD, COL_ARGON_CYAN, this.searchFocusAnim);

        VenturaSdfRenderer.drawRoundedRect(searchX, headerY + 3.0f, searchW, 28.0f, 7.5f, COL_BG_FIELD, searchBorder, 1.0f);
        VenturaSdfRenderer.drawIcon(VectorIcon.SEARCH, searchX + 14.0f, headerY + 17.0f, 10.0f,
                this.searchFocused ? COL_ARGON_CYAN : COL_TEXT_MUTED);

        String placeholder = this.searchQuery.isEmpty() ? (this.searchFocused ? "" : "Search worlds...") : this.searchQuery;
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

    private void renderWorldList(GuiGraphics g, int mouseX, int mouseY, float dt) {
        float startY = 66.0f;
        float bottomLimit = this.height - 62.0f;
        float viewH = bottomLimit - startY;

        if (this.loading) {
            float cx = this.width * 0.5f;
            float cy = startY + viewH * 0.5f;
            VenturaSdfRenderer.drawProgressRing(cx, cy, 32.0f, 2.5f, (this.globalTime * 0.8f) % 1.0f, 0x22FFFFFF, COL_ARGON_CYAN);
            int tw = this.font.width(this.statusMessage);
            g.drawString(this.font, this.statusMessage, (int) (cx - tw * 0.5f), (int) (cy + 26.0f), COL_TEXT_MUTED, false);
            g.flush();
            return;
        }

        if (this.filteredWorlds.isEmpty()) {
            float cx = this.width * 0.5f;
            float cy = startY + viewH * 0.5f;
            String msg = this.searchQuery.isEmpty() ? "No worlds found. Click [CREATE WORLD] to begin!" : "No results for: \"" + this.searchQuery + "\"";
            int tw = this.font.width(msg);
            VenturaSdfRenderer.drawIcon(VectorIcon.COMPASS, cx, cy - 18.0f, 18.0f, COL_TEXT_MUTED);
            g.drawString(this.font, msg, (int) (cx - tw * 0.5f), (int) (cy + 8.0f), COL_TEXT_MUTED, false);
            g.flush();
            return;
        }

        float margin = 28.0f;
        float contentW = this.width - margin * 2.0f;
        boolean twoCols = contentW >= 620.0f;
        int cols = twoCols ? 2 : 1;
        float gap = 10.0f;
        float cardW = twoCols ? (contentW - gap) * 0.5f : contentW;
        float cardH = 66.0f;

        int rows = (this.filteredWorlds.size() + cols - 1) / cols;
        float totalH = rows * (cardH + gap) - gap;
        this.maxScroll = Math.max(0.0f, totalH - viewH + 16.0f);

        VenturaSdfRenderer.enableScissor((int) margin, (int) startY, (int) contentW, (int) viewH);

        for (int i = 0; i < this.filteredWorlds.size(); i++) {
            WorldEntry world = this.filteredWorlds.get(i);
            int col = i % cols;
            int row = i / cols;

            float cx = margin + col * (cardW + gap);
            float cy = startY + row * (cardH + gap) - this.scrollOffset;

            if (cy + cardH < startY - 10.0f || cy > bottomLimit + 10.0f) {
                continue;
            }

            boolean isHovered = (mouseX >= cx && mouseX <= cx + cardW && mouseY >= cy && mouseY <= cy + cardH);
            boolean isSelected = (this.selectedWorld == world);

            world.hoverAnim = Mth.lerp(1.0f - (float) Math.exp(-12.0f * dt), world.hoverAnim, isHovered ? 1.0f : 0.0f);

            int bgCol = VenturaSdfRenderer.lerpColor(COL_ARGON_GLASS, COL_ARGON_GLASS_HOVER, world.hoverAnim);
            int borderCol = isSelected ? COL_ARGON_BORDER_ACT : (isHovered ? COL_ARGON_BORDER_HOVER : COL_ARGON_BORDER);

            if (isSelected) {
                VenturaSdfRenderer.drawDropShadow(cx, cy, cardW, cardH, 9.0f, 14.0f, 3.0f, COL_ARGON_GLOW_ACT);
            }
            VenturaSdfRenderer.drawRoundedRect(cx, cy, cardW, cardH, 9.0f, bgCol, borderCol, isSelected ? 1.5f : 1.0f);

            // Ikona świata
            float iconBoxSize = 44.0f;
            float iconX = cx + 11.0f;
            float iconY = cy + (cardH - iconBoxSize) * 0.5f;

            VenturaSdfRenderer.drawRoundedRect(iconX, iconY, iconBoxSize, iconBoxSize, 7.0f, COL_BG_FIELD, isSelected ? COL_ARGON_BORDER_ACT : COL_BORDER_FIELD, 1.0f);
            VenturaSdfRenderer.drawIcon(isSelected ? VectorIcon.CHECK : VectorIcon.LAYERS,
                    iconX + iconBoxSize * 0.5f, iconY + iconBoxSize * 0.5f, 13.0f,
                    isSelected ? COL_ARGON_CYAN : COL_TEXT_SECONDARY);

            // Nazwa świata i metadane
            float textX = iconX + iconBoxSize + 12.0f;
            g.drawString(this.font, world.displayName, (int) textX, (int) (cy + 12.0f), COL_TEXT_PRIMARY, false);

            String subInfo = world.levelId + "  •  " + world.lastPlayedFormatted;
            g.drawString(this.font, subInfo, (int) textX, (int) (cy + 27.0f), COL_TEXT_MUTED, false);

            // Pigułki trybu gry i wersji
            float tagY = cy + 42.0f;
            float tagW = this.font.width(world.modeLabel) + 14.0f;
            VenturaSdfRenderer.drawRoundedRect(textX, tagY, tagW, 14.0f, 4.0f, COL_BG_FIELD, world.modeColor, 1.0f);
            g.drawString(this.font, world.modeLabel, (int) (textX + 7.0f), (int) (tagY + 3.0f), world.modeColor, false);

            String verStr = world.versionName;
            if (!verStr.isEmpty()) {
                float verX = textX + tagW + 6.0f;
                float verW = this.font.width(verStr) + 12.0f;
                VenturaSdfRenderer.drawRoundedRect(verX, tagY, verW, 14.0f, 4.0f, COL_BG_FIELD, COL_BORDER_FIELD, 1.0f);
                g.drawString(this.font, verStr, (int) (verX + 6.0f), (int) (tagY + 3.0f), COL_TEXT_MUTED, false);
            }

            // Przycisk "GRAJ" bezpośrednio na karcie
            if (isHovered || isSelected) {
                float playBtnW = 62.0f;
                float playBtnH = 26.0f;
                float playBtnX = cx + cardW - playBtnW - 12.0f;
                float playBtnY = cy + (cardH - playBtnH) * 0.5f;

                boolean playHover = (mouseX >= playBtnX && mouseX <= playBtnX + playBtnW && mouseY >= playBtnY && mouseY <= playBtnY + playBtnH);
                int btnBg = playHover ? COL_ARGON_VIOLET : 0x2A8B5CF6;
                int btnBorder = playHover ? COL_ARGON_CYAN : COL_ARGON_BORDER_ACT;

                VenturaSdfRenderer.drawRoundedRect(playBtnX, playBtnY, playBtnW, playBtnH, 6.5f, btnBg, btnBorder, 1.0f);
                VenturaSdfRenderer.drawIcon(VectorIcon.PLAY, playBtnX + 16.0f, playBtnY + playBtnH * 0.5f, 8.5f, playHover ? COL_TEXT_PRIMARY : COL_ARGON_CYAN);
                g.drawString(this.font, "Play", (int) (playBtnX + 28.0f), (int) (playBtnY + 9.0f), playHover ? COL_TEXT_PRIMARY : COL_ARGON_LILAC, false);
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
        float dockW = Math.min(640.0f, this.width - 40.0f);
        float dockH = 40.0f;
        float dockX = (this.width - dockW) * 0.5f;
        float dockY = this.height - dockH - 12.0f;

        VenturaSdfRenderer.drawDropShadow(dockX, dockY, dockW, dockH, 10.0f, 18.0f, 4.0f, 0x90000000);
        VenturaSdfRenderer.drawRoundedRect(dockX, dockY, dockW, dockH, 10.0f, 0xEE0B0916, COL_ARGON_BORDER_ACT, 1.0f);

        String[] actions = {"PLAY [ENTER]", "NEW WORLD [N]", "EDIT [E]", "DELETE [DEL]", "BACK [ESC]"};
        VectorIcon[] icons = {VectorIcon.PLAY, VectorIcon.BOLT, VectorIcon.SLIDERS, VectorIcon.TRASH, VectorIcon.CHEVRON_RIGHT};
        float btnW = dockW / actions.length;

        for (int i = 0; i < actions.length; i++) {
            float bx = dockX + i * btnW;
            boolean hovered = (mouseX >= bx && mouseX <= bx + btnW && mouseY >= dockY && mouseY <= dockY + dockH);

            if (hovered) {
                int glowCol = i == 0 ? COL_ARGON_CYAN_GLOW : (i == 3 ? 0x44F43F5E : 0x248B5CF6);
                int borderCol = i == 0 ? COL_ARGON_CYAN : (i == 3 ? COL_STATUS_CRIMSON : COL_ARGON_BORDER_ACT);
                VenturaSdfRenderer.drawRoundedRect(bx + 4.0f, dockY + 4.0f, btnW - 8.0f, dockH - 8.0f, 7.0f, glowCol, borderCol, 1.0f);
            }

            int iconCol = hovered ? (i == 0 ? COL_ARGON_CYAN : (i == 3 ? COL_STATUS_CRIMSON : COL_ARGON_LILAC)) : COL_TEXT_SECONDARY;
            int textCol = hovered ? COL_TEXT_PRIMARY : COL_TEXT_MUTED;

            VenturaSdfRenderer.drawIcon(icons[i], bx + 16.0f, dockY + dockH * 0.5f, 9.5f, iconCol);
            g.drawString(this.font, actions[i], (int) (bx + 26.0f), (int) (dockY + 16.0f), textCol, false);

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
        boolean twoCols = contentW >= 620.0f;
        int cols = twoCols ? 2 : 1;
        float gap = 10.0f;
        float cardW = twoCols ? (contentW - gap) * 0.5f : contentW;
        float cardH = 66.0f;
        float startY = 66.0f;

        for (int i = 0; i < this.filteredWorlds.size(); i++) {
            WorldEntry world = this.filteredWorlds.get(i);
            int col = i % cols;
            int row = i / cols;

            float cx = margin + col * (cardW + gap);
            float cy = startY + row * (cardH + gap) - this.scrollOffset;

            if (mouseX >= cx && mouseX <= cx + cardW && mouseY >= cy && mouseY <= cy + cardH) {
                if (this.selectedWorld == world) {
                    joinSelectedWorld();
                } else {
                    this.selectedWorld = world;
                }
                return true;
            }
        }

        float dockW = Math.min(640.0f, this.width - 40.0f);
        float dockH = 40.0f;
        float dockX = (this.width - dockW) * 0.5f;
        float dockY = this.height - dockH - 12.0f;

        if (mouseY >= dockY && mouseY <= dockY + dockH && mouseX >= dockX && mouseX <= dockX + dockW) {
            float btnW = dockW / 5.0f;
            int idx = (int) ((mouseX - dockX) / btnW);
            switch (idx) {
                case 0 -> joinSelectedWorld();
                case 1 -> CreateWorldScreen.openFresh(this.minecraft, this);
                case 2 -> editSelectedWorld();
                case 3 -> deleteSelectedWorld();
                case 4 -> this.minecraft.setScreen(this.parent);
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
            joinSelectedWorld();
            return true;
        } else if (keyCode == GLFW.GLFW_KEY_N) {
            CreateWorldScreen.openFresh(this.minecraft, this);
            return true;
        } else if (keyCode == GLFW.GLFW_KEY_E) {
            editSelectedWorld();
            return true;
        } else if (keyCode == GLFW.GLFW_KEY_DELETE) {
            deleteSelectedWorld();
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
        if (this.filteredWorlds.isEmpty()) return;
        int idx = this.filteredWorlds.indexOf(this.selectedWorld);
        if (idx > 0) {
            this.selectedWorld = this.filteredWorlds.get(idx - 1);
        }
    }

    private void selectNext() {
        if (this.filteredWorlds.isEmpty()) return;
        int idx = this.filteredWorlds.indexOf(this.selectedWorld);
        if (idx < this.filteredWorlds.size() - 1) {
            this.selectedWorld = this.filteredWorlds.get(idx + 1);
        }
    }

    private void joinSelectedWorld() {
        if (this.selectedWorld == null) return;
        String levelId = this.selectedWorld.levelId;
        this.minecraft.createWorldOpenFlows().openWorld(levelId, () -> this.minecraft.setScreen(this));
    }

    private void editSelectedWorld() {
        if (this.selectedWorld == null) return;
        try {
            LevelStorageSource levelSource = this.minecraft.getLevelSource();
            LevelStorageSource.LevelStorageAccess access = levelSource.validateAndCreateAccess(this.selectedWorld.levelId);
            this.minecraft.setScreen(EditWorldScreen.create(
                    this.minecraft,
                    access,
                    (confirmed) -> {
                        try {
                            access.close();
                        } catch (Throwable ignored) {}
                        if (confirmed) {
                            loadWorldSummaries();
                        }
                        this.minecraft.setScreen(this);
                    }
            ));
        } catch (Throwable ignored) {}
    }

    private void deleteSelectedWorld() {
        if (this.selectedWorld == null) return;
        this.minecraft.setScreen(new ConfirmScreen(
                confirmed -> {
                    if (confirmed) {
                        try (LevelStorageSource.LevelStorageAccess access = this.minecraft.getLevelSource().createAccess(this.selectedWorld.levelId)) {
                            access.deleteLevel();
                            loadWorldSummaries();
                        } catch (Throwable ignored) {}
                    }
                    this.minecraft.setScreen(this);
                },
                Component.literal("Delete World"),
                Component.literal("Are you sure you want to delete '" + this.selectedWorld.displayName + "'? This action cannot be undone."),
                Component.literal("Delete"),
                CommonComponents.GUI_CANCEL
        ));
    }

    // =========================================================================
    // MODELE DANYCH POMOCNICZYCH
    // =========================================================================

    private static class WorldEntry {
        final LevelSummary summary;
        final String levelId;
        final String displayName;
        final String modeLabel;
        final int modeColor;
        final String versionName;
        final String lastPlayedFormatted;
        float hoverAnim = 0.0f;

        WorldEntry(LevelSummary s) {
            this.summary = s;
            this.levelId = s.getLevelId();
            this.displayName = s.getLevelName().isBlank() ? s.getLevelId() : s.getLevelName();

            if (s.isHardcore()) {
                this.modeLabel = "HARDCORE";
                this.modeColor = COL_STATUS_CRIMSON;
            } else if (s.hasCommands()) {
                this.modeLabel = s.getGameMode().getName().toUpperCase() + " + CHEATS";
                this.modeColor = COL_STATUS_AMBER;
            } else {
                this.modeLabel = s.getGameMode().getName().toUpperCase();
                this.modeColor = COL_STATUS_GREEN;
            }

            this.versionName = s.getWorldVersionName() != null ? s.getWorldVersionName().getString() : "";
            long time = s.getLastPlayed();
            this.lastPlayedFormatted = time > 0 ? DATE_FORMAT.format(new Date(time)) : "Never";
        }
    }

    private static class BgParticle {
        float x, y, vx, vy, radius, phase;

        BgParticle(float x, float y, float vx, float vy, float radius, float phase) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.radius = radius;
            this.phase = phase;
        }

        void update(float dt, int width, int height) {
            this.x += this.vx * dt;
            this.y += this.vy * dt;
            if (this.x < 0) this.x = width;
            if (this.x > width) this.x = 0;
            if (this.y < 0) this.y = height;
            if (this.y > height) this.y = 0;
        }
    }
}
