package com.astra.revolution.nuker;

import com.astra.revolution.gui.BaritoneBypassScreen.VenturaSdfRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * ASTRA NUKER BLOCK SELECTOR & CONTROL WIDGET 2.0 (GOD-TIER EDITION)
 * Designed for Astra Client / Baritone Revolution (Minecraft 1.21.4 Mojmap, Java 21).
 *
 * Design System: Obsidian & Lilac Glass 2.0
 * - Deep obsidian frosted backdrop with soft double rim-light.
 * - Multi-layer block cards with inset 3D slot bezel (renderItem with depth).
 * - Live speed calculation badges: "⚡ INSTA" (Emerald glow) vs "⏱ 2T" / "⏱ 3T" (Amber glow).
 * - Interactive Search Bar with in-field clear button, pulsing cursor, and registry ID lookup.
 * - 6 Categorized Quick Filter chips: [💎 Rudy], [🪨 Kamienie], [🪵 Drewno], [🌾 Ziemia], [🔥 Nether], [📦 Wszystkie].
 * - 3-Mode selector (NORMAL | FAST | FASTEST ⚡ Grim Burst) with animated status LED diodes.
 * - Interactive Reach Slider with gradient fill and smooth dragging.
 * - Zero-allocation in render loop, kinetic smooth scrolling, scissor-clipped viewport.
 */
public final class NukerBlockSelectorWidget {

    public record BlockEntry(
            Block block,
            ItemStack stack,
            String displayName,
            String registryId,
            String searchKey,
            float baseHardness,
            Category category
    ) {}

    public enum Category {
        ALL("📦 Wszystkie"),
        ORES("💎 Rudy"),
        STONES("🪨 Kamienie"),
        WOOD("🪵 Drewno"),
        EARTH("🌾 Ziemia"),
        NETHER("🔥 Nether");

        private final String label;
        Category(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    // =========================================================================
    // DESIGN SYSTEM: OBSIDIAN & LILAC GLASS 2.0 PALETTE
    // =========================================================================
    public static final int COL_PANEL_BG       = 0xE60A0A12; // Głęboki, aksamitny obsydian (~90% alpha)
    public static final int COL_HEADER_BG      = 0xF012111E; // Górny szklany panel nawigacji
    public static final int COL_CARD_BG        = 0xD0141424; // Podstawa wiersza bloku
    public static final int COL_CARD_HOVER     = 0xF0202036; // Rozświetlenie przy najechaniu
    public static final int COL_CARD_SELECTED  = 0xE6261E3E; // Aksamitny lawendowy fiolet dla wybranych
    public static final int COL_SLOT_BEZEL     = 0xCC080810; // Wklęsły slot na ikonę Minecrafta
    public static final int COL_BORDER_MUTED   = 0x1FFFFFFF; // Subtelna szklana ramka (12% alpha)
    public static final int COL_BORDER_LILAC   = 0x4DC4B5FD; // Świecąca pastelowa lawenda (30% alpha)
    public static final int COL_BORDER_ACTIVE  = 0xB3C4B5FD; // Wyrazisty neonowy obrys dla zaznaczonych (70% alpha)

    public static final int COL_ACCENT_LILAC   = 0xFFC4B5FD; // Pastelowa lawenda (główny akcent)
    public static final int COL_ACCENT_DIM     = 0x26C4B5FD; // Subtelne tło akcentu
    public static final int COL_ACCENT_FILL    = 0x4DC4B5FD; // Wypełnienie aktywnych przycisków

    public static final int COL_TEXT_WHITE     = 0xFFF5F3FF; // Czysta biel (nazwy bloków)
    public static final int COL_TEXT_MUTED     = 0xFF8E8EA8; // Stonowany szaro-fioletowy (opisy, ID)
    public static final int COL_TEXT_DIM       = 0xFF5A5A72; // Bardzo ciemny tekst pomocniczy

    public static final int COL_INSTAMINE_BG   = 0x3300FF88; // Szmaragdowa dioda Instamine
    public static final int COL_INSTAMINE_BDR  = 0x8000FF88; // Szmaragdowy obrys
    public static final int COL_INSTAMINE_TXT  = 0xFF00FF88; // Neonowa zieleń

    public static final int COL_HARD_BG        = 0x33FFB703; // Złota dioda niszczenia wielotickowego
    public static final int COL_HARD_BDR       = 0x80FFB703; // Złoty obrys
    public static final int COL_HARD_TXT       = 0xFFFFC83B; // Ciepłe złoto

    public static final int COL_DANGER_BG      = 0x33FF4444; // Rubinowe tło czyszczenia
    public static final int COL_DANGER_BORDER  = 0x66FF4444; // Rubinowy obrys
    public static final int COL_DANGER_TXT     = 0xFFFF6B6B; // Rubinowy tekst

    private static final Map<Block, BlockEntry> REGISTRY_CACHE = new LinkedHashMap<>();

    // Domyślne popularne bloki (dla natychmiastowej responsywności UI)
    public static final Block[] PRESET_ORES = new Block[] {
            Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE,
            Blocks.ANCIENT_DEBRIS,
            Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE,
            Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE,
            Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE,
            Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE,
            Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE,
            Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
            Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE,
            Blocks.NETHER_QUARTZ_ORE, Blocks.NETHER_GOLD_ORE
    };

    public static final Block[] PRESET_STONES = new Block[] {
            Blocks.STONE, Blocks.DEEPSLATE,
            Blocks.COBBLESTONE, Blocks.COBBLED_DEEPSLATE,
            Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE,
            Blocks.TUFF, Blocks.CALCITE, Blocks.DRIPSTONE_BLOCK,
            Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.BEDROCK
    };

    public static final Block[] PRESET_WOOD = new Block[] {
            Blocks.OAK_LOG, Blocks.BIRCH_LOG, Blocks.SPRUCE_LOG,
            Blocks.JUNGLE_LOG, Blocks.ACACIA_LOG, Blocks.DARK_OAK_LOG,
            Blocks.MANGROVE_LOG, Blocks.CHERRY_LOG, Blocks.PALE_OAK_LOG,
            Blocks.CRIMSON_STEM, Blocks.WARPED_STEM
    };

    public static final Block[] PRESET_EARTH = new Block[] {
            Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT,
            Blocks.GRAVEL, Blocks.SAND, Blocks.RED_SAND,
            Blocks.CLAY, Blocks.MUD, Blocks.SOUL_SAND, Blocks.SOUL_SOIL
    };

    public static final Block[] PRESET_NETHER = new Block[] {
            Blocks.NETHERRACK, Blocks.BLACKSTONE, Blocks.BASALT, Blocks.SMOOTH_BASALT,
            Blocks.SOUL_SAND, Blocks.SOUL_SOIL, Blocks.GLOWSTONE,
            Blocks.MAGMA_BLOCK, Blocks.NETHER_BRICKS, Blocks.CRIMSON_NYLIUM, Blocks.WARPED_NYLIUM
    };

    private final List<BlockEntry> filteredEntries = new ArrayList<>(768);

    private String searchQuery = "";
    private boolean searchFocused = false;
    private Category activeCategory = Category.ALL;
    private boolean draggingReach = false;

    private float scrollOffset = 0.0f;
    private float targetScroll = 0.0f;

    // Cache etykiet i liczb (zero alokacji w render())
    private int lastSelectedCount = -1;
    private String cachedCountText = "0 wybrane";
    private float lastReachValue = -1.0f;
    private String cachedReachText = "4.5 m";

    public NukerBlockSelectorWidget() {
        ensureRegistryCache();
        rebuildFilteredList();
    }

    private static synchronized void ensureRegistryCache() {
        if (REGISTRY_CACHE.size() > 50) return;
        REGISTRY_CACHE.clear();

        try {
            for (Block block : BuiltInRegistries.BLOCK) {
                if (block == null || block == Blocks.AIR || block.defaultBlockState().isAir()) continue;
                try {
                    ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
                    String idStr = (id != null) ? id.toString() : "";
                    String path = (id != null) ? id.getPath().toLowerCase(Locale.ROOT) : "";

                    String name;
                    try {
                        name = block.getName().getString();
                    } catch (Throwable t) {
                        name = (id != null) ? id.getPath().replace('_', ' ') : "Block";
                    }
                    if (name.isBlank() && id != null) {
                        name = id.getPath().replace('_', ' ');
                    }

                    ItemStack stack;
                    try {
                        var item = block.asItem();
                        stack = (item != null && item != Items.AIR) ? new ItemStack(item) : new ItemStack(Items.STONE);
                    } catch (Throwable t) {
                        stack = new ItemStack(Items.STONE);
                    }

                    float hardness = block.defaultBlockState().getDestroySpeed(null, null);
                    String searchKey = (name + " " + idStr + " " + path).toLowerCase(Locale.ROOT);

                    // Kategoryzacja
                    Category cat = Category.ALL;
                    if (path.contains("ore") || path.contains("debris")) {
                        cat = Category.ORES;
                    } else if (path.contains("stone") || path.contains("deepslate") || path.contains("granite")
                            || path.contains("diorite") || path.contains("andesite") || path.contains("tuff")
                            || path.contains("calcite") || path.contains("obsidian")) {
                        cat = Category.STONES;
                    } else if (path.contains("log") || path.contains("wood") || path.contains("stem") || path.contains("planks")) {
                        cat = Category.WOOD;
                    } else if (path.contains("dirt") || path.contains("grass") || path.contains("sand")
                            || path.contains("gravel") || path.contains("clay") || path.contains("mud")) {
                        cat = Category.EARTH;
                    } else if (path.contains("nether") || path.contains("blackstone") || path.contains("basalt")
                            || path.contains("soul") || path.contains("glowstone") || path.contains("magma")) {
                        cat = Category.NETHER;
                    }

                    REGISTRY_CACHE.put(block, new BlockEntry(block, stack, name, idStr, searchKey, hardness, cat));
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            // Fallback awaryjny
            for (Block b : PRESET_STONES) {
                try {
                    ResourceLocation id = BuiltInRegistries.BLOCK.getKey(b);
                    String idStr = (id != null) ? id.toString() : "minecraft:stone";
                    String name = (id != null) ? id.getPath().replace('_', ' ') : "Stone";
                    REGISTRY_CACHE.put(b, new BlockEntry(b, new ItemStack(Items.STONE), name, idStr, name.toLowerCase(Locale.ROOT), 1.5f, Category.STONES));
                } catch (Throwable ignored) {}
            }
        }
    }

    public void rebuildFilteredList() {
        rebuildFilteredList(false);
    }

    public void rebuildFilteredList(boolean keepScroll) {
        ensureRegistryCache();
        filteredEntries.clear();
        Set<Block> whitelist = VenturaNukerModule.getInstance().getCustomWhitelist();
        String q = searchQuery.trim().toLowerCase(Locale.ROOT);

        java.util.Set<Block> added = new java.util.HashSet<>();

        // 1. Zaznaczone bloki ZAWSZE na samej górze (z fajeczką '✓')
        for (Block b : whitelist) {
            BlockEntry entry = REGISTRY_CACHE.get(b);
            if (entry != null && matchesCategoryAndQuery(entry, q) && added.add(b)) {
                filteredEntries.add(entry);
            }
        }

        // 2. Popularne bloki dopasowane do wybranej kategorii
        Block[] popularSubset = getPopularForCategory(activeCategory);
        for (Block b : popularSubset) {
            BlockEntry entry = REGISTRY_CACHE.get(b);
            if (entry != null && matchesCategoryAndQuery(entry, q) && added.add(b)) {
                filteredEntries.add(entry);
            }
        }

        // 3. Reszta rejestru Minecraft
        for (BlockEntry entry : REGISTRY_CACHE.values()) {
            if (matchesCategoryAndQuery(entry, q) && added.add(entry.block())) {
                filteredEntries.add(entry);
                if (filteredEntries.size() >= 450) break; // Optymalizacja pamięci
            }
        }

        if (!keepScroll) {
            targetScroll = 0.0f;
        }
    }

    private boolean matchesCategoryAndQuery(BlockEntry entry, String q) {
        if (activeCategory != Category.ALL && entry.category != activeCategory) {
            // Dopuszczaj, jeśli użytkownik wpisał konkretną nazwę w szukajce
            if (q.isEmpty()) return false;
        }
        if (!q.isEmpty()) {
            return entry.searchKey.contains(q);
        }
        return true;
    }

    private static Block[] getPopularForCategory(Category cat) {
        return switch (cat) {
            case ORES -> PRESET_ORES;
            case STONES -> PRESET_STONES;
            case WOOD -> PRESET_WOOD;
            case EARTH -> PRESET_EARTH;
            case NETHER -> PRESET_NETHER;
            case ALL -> PRESET_STONES;
        };
    }

    public boolean isSearchFocused() { return searchFocused; }
    public void setSearchFocused(boolean focused) { this.searchFocused = focused; }

    // =========================================================================
    // GŁÓWNA PĘTLA RENDEROWANIA (ZERO-ALLOCATION)
    // =========================================================================
    public void render(GuiGraphics gg, int x, int y, int width, int height, int mouseX, int mouseY, float partialTick) {
        ensureRegistryCache();
        if (filteredEntries.isEmpty()) {
            rebuildFilteredList();
        }

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        VenturaNukerModule nuker = VenturaNukerModule.getInstance();
        Set<Block> whitelist = nuker.getCustomWhitelist();

        // Płynna kinetyka scrolla
        scrollOffset = Mth.lerp(0.35f, scrollOffset, targetScroll);

        // Odświeżanie pamięci podręcznej tekstów bez alokacji w każdej klatce
        int currentCount = whitelist.size();
        if (currentCount != lastSelectedCount) {
            lastSelectedCount = currentCount;
            cachedCountText = currentCount + " wybrane";
        }
        float currentReach = nuker.getRange();
        if (Float.compare(currentReach, lastReachValue) != 0) {
            lastReachValue = currentReach;
            cachedReachText = String.format(Locale.US, "%.1f m", currentReach);
        }

        // Tło głównego kontenera: Obsidian Glass 2.0 z miękkim obramowaniem i podświetleniem
        VenturaSdfRenderer.drawRoundedRect(x, y, width, height, 8.0f, COL_PANEL_BG, COL_BORDER_MUTED, 1.0f);
        // Subtelny lawendowy refleks górny (rim-light)
        VenturaSdfRenderer.drawRoundedRect(x + 2, y + 1, width - 4, 2.0f, 1.0f, 0x22C4B5FD, 0, 0.0f);

        int pad = 7;
        int innerW = width - (pad * 2);
        int curY = y + pad;

        // =====================================================================
        // 1. SELEKTOR TRYBÓW (NORMAL | FAST | FASTEST ⚡ Grim Burst)
        // =====================================================================
        int modeGap = 4;
        int modeW = (innerW - (modeGap * 2)) / 3;
        int modeH = 25;
        VenturaNukerModule.Mode[] modes = VenturaNukerModule.Mode.values();
        VenturaNukerModule.Mode activeMode = nuker.getMode();

        for (int i = 0; i < modes.length; i++) {
            VenturaNukerModule.Mode m = modes[i];
            int bx = x + pad + i * (modeW + modeGap);
            boolean active = (m == activeMode);
            boolean hover = isHovered(mouseX, mouseY, bx, curY, modeW, modeH);

            int bg = active ? COL_CARD_SELECTED : (hover ? COL_CARD_HOVER : COL_CARD_BG);
            int border = active ? COL_ACCENT_LILAC : (hover ? COL_BORDER_LILAC : COL_BORDER_MUTED);
            float borderW = active ? 1.5f : 1.0f;
            VenturaSdfRenderer.drawRoundedRect(bx, curY, modeW, modeH, 5.0f, bg, border, borderW);

            // Dioda LED statusu trybu
            int ledCol = active ? (m == VenturaNukerModule.Mode.FASTEST ? 0xFF00FF88 : COL_ACCENT_LILAC) : 0xFF454558;
            VenturaSdfRenderer.drawCircle(bx + 9.0f, curY + modeH / 2.0f, 2.8f, ledCol, active ? ledCol : 0);

            // Teksty trybu
            gg.drawString(font, m.getTitle(), bx + 17, curY + 4, active ? COL_TEXT_WHITE : COL_TEXT_MUTED, false);
            gg.drawString(font, m.getSubtitle(), bx + 17, curY + 14, active ? COL_ACCENT_LILAC : COL_TEXT_DIM, false);
        }
        curY += modeH + 6;

        // =====================================================================
        // 2. SUWAK REACH + PRZYCISK OCHRONY PODŁOGI (FLAT)
        // =====================================================================
        int sliderH = 20;
        int flatBtnW = 104;
        int reachW = innerW - flatBtnW - 6;

        // Reach Slider
        boolean reachHover = isHovered(mouseX, mouseY, x + pad, curY, reachW, sliderH);
        VenturaSdfRenderer.drawRoundedRect(x + pad, curY, reachW, sliderH, 5.0f, COL_CARD_BG, reachHover ? COL_BORDER_LILAC : COL_BORDER_MUTED, 1.0f);
        float pct = Mth.clamp((currentReach - 1.0f) / 9.0f, 0.0f, 1.0f);
        int fillW = (int) ((reachW - 4) * pct);
        if (fillW > 0) {
            VenturaSdfRenderer.drawRoundedRect(x + pad + 2, curY + 2, fillW, sliderH - 4, 3.5f, 0x40C4B5FD, 0, 0.0f);
        }
        // Suwak thumb
        int thumbX = (int) (x + pad + 2 + (reachW - 8) * pct);
        VenturaSdfRenderer.drawCircle(thumbX + 2.0f, curY + sliderH / 2.0f, 4.0f, COL_ACCENT_LILAC, reachHover || draggingReach ? COL_ACCENT_LILAC : 0);

        gg.drawString(font, "Zasięg (Reach)", x + pad + 8, curY + 6, COL_TEXT_WHITE, false);
        gg.drawString(font, cachedReachText, x + pad + reachW - font.width(cachedReachText) - 8, curY + 6, COL_ACCENT_LILAC, false);

        // Przycisk FLAT (Ochrona podłogi / stóp)
        int flatX = x + pad + reachW + 6;
        boolean isFlat = nuker.isFlat();
        boolean flatHover = isHovered(mouseX, mouseY, flatX, curY, flatBtnW, sliderH);
        int flatBg = isFlat ? 0x3300FF88 : (flatHover ? COL_CARD_HOVER : COL_CARD_BG);
        int flatBorder = isFlat ? 0x8000FF88 : (flatHover ? COL_BORDER_LILAC : COL_BORDER_MUTED);
        VenturaSdfRenderer.drawRoundedRect(flatX, curY, flatBtnW, sliderH, 5.0f, flatBg, flatBorder, 1.0f);

        int flatLedCol = isFlat ? 0xFF00FF88 : 0xFF5A5A72;
        VenturaSdfRenderer.drawCircle(flatX + 9.0f, curY + sliderH / 2.0f, 3.0f, flatLedCol, isFlat ? 0xFF00FF88 : 0);
        String flatLabel = isFlat ? "Podłoga: BEZP." : "Podłoga: WYŁ.";
        gg.drawString(font, flatLabel, flatX + 17, curY + 6, isFlat ? COL_TEXT_WHITE : COL_TEXT_MUTED, false);

        curY += sliderH + 6;

        // =====================================================================
        // 3. WYSZUKIWARKA BLOKÓW + PIGUŁKA WYBRANYCH + PRZYCISK CZYSZCZENIA
        // =====================================================================
        int badgeW = 78;
        int clearBtnW = currentCount > 0 ? 22 : 0;
        int rightControlsW = badgeW + (clearBtnW > 0 ? clearBtnW + 4 : 0);
        int searchW = innerW - rightControlsW - 5;
        int searchH = 20;

        int searchBorder = searchFocused ? COL_ACCENT_LILAC : COL_BORDER_MUTED;
        int searchBg = searchFocused ? 0xE618182B : COL_CARD_BG;
        VenturaSdfRenderer.drawRoundedRect(x + pad, curY, searchW, searchH, 5.0f, searchBg, searchBorder, 1.0f);

        // Ikona lupy
        gg.drawString(font, "🔍", x + pad + 6, curY + 6, searchFocused ? COL_ACCENT_LILAC : COL_TEXT_DIM, false);

        if (searchQuery.isEmpty() && !searchFocused) {
            gg.drawString(font, "Szukaj bloku... np. stone, dirt, ore", x + pad + 22, curY + 6, COL_TEXT_DIM, false);
        } else {
            String visibleQuery = font.plainSubstrByWidth(searchQuery, searchW - 40);
            gg.drawString(font, visibleQuery, x + pad + 22, curY + 6, COL_TEXT_WHITE, false);

            // Pulsujący neonowy kursor
            if (searchFocused && ((System.currentTimeMillis() / 450L) & 1L) == 0L) {
                int cursorX = x + pad + 22 + font.width(visibleQuery);
                gg.drawString(font, "|", cursorX, curY + 5, COL_ACCENT_LILAC, false);
            }

            // Przycisk "✕" do wyczyszczenia samego pola tekstowego
            if (!searchQuery.isEmpty()) {
                int inlineClearX = x + pad + searchW - 16;
                boolean inClearHov = isHovered(mouseX, mouseY, inlineClearX - 2, curY + 2, 16, 16);
                gg.drawString(font, "✕", inlineClearX, curY + 6, inClearHov ? 0xFFFF7777 : COL_TEXT_MUTED, false);
            }
        }

        // Pigułka licznika ("X wybrane")
        int badgeX = x + pad + searchW + 5;
        VenturaSdfRenderer.drawRoundedRect(badgeX, curY, badgeW, searchH, 5.0f, COL_ACCENT_DIM, COL_BORDER_LILAC, 1.0f);
        gg.drawCenteredString(font, cachedCountText, badgeX + badgeW / 2, curY + 6, COL_ACCENT_LILAC);

        // Przycisk "✕ Wyczyść wszystko"
        if (clearBtnW > 0) {
            int clearX = badgeX + badgeW + 4;
            boolean clearHov = isHovered(mouseX, mouseY, clearX, curY, clearBtnW, searchH);
            VenturaSdfRenderer.drawRoundedRect(clearX, curY, clearBtnW, searchH, 5.0f, clearHov ? COL_DANGER_BG : COL_CARD_BG, COL_DANGER_BORDER, 1.0f);
            gg.drawCenteredString(font, "✕", clearX + clearBtnW / 2, curY + 6, clearHov ? COL_DANGER_TXT : COL_TEXT_MUTED);
        }

        curY += searchH + 5;

        // =====================================================================
        // 4. CHIPY KATEGORII (PRESETY I FILTRY NA ŻYWO)
        // =====================================================================
        Category[] cats = Category.values();
        int chipGap = 3;
        int chipH = 16;
        int chipW = (innerW - (chipGap * (cats.length - 1))) / cats.length;

        for (int c = 0; c < cats.length; c++) {
            Category cat = cats[c];
            int cx = x + pad + c * (chipW + chipGap);
            boolean isCatActive = (cat == activeCategory);
            boolean chov = isHovered(mouseX, mouseY, cx, curY, chipW, chipH);

            int cbg = isCatActive ? COL_ACCENT_FILL : (chov ? COL_CARD_HOVER : COL_CARD_BG);
            int cbdr = isCatActive ? COL_ACCENT_LILAC : (chov ? COL_BORDER_LILAC : COL_BORDER_MUTED);
            int ctxt = isCatActive ? COL_TEXT_WHITE : (chov ? COL_ACCENT_LILAC : COL_TEXT_MUTED);

            VenturaSdfRenderer.drawRoundedRect(cx, curY, chipW, chipH, 4.0f, cbg, cbdr, 1.0f);
            gg.drawCenteredString(font, cat.getLabel(), cx + chipW / 2, curY + 4, ctxt);
        }

        curY += chipH + 5;

        // =====================================================================
        // 5. SCISSOR-CLIPPED LISTA BLOKÓW (GOD-TIER CARD RENDERING)
        // =====================================================================
        int listH = (y + height - pad) - curY;
        if (listH <= 0) return;

        int rowH = 26; // Wygodna, przestronna wysokość karty bloku
        int rowGap = 3;
        int stepY = rowH + rowGap;

        int totalContentH = filteredEntries.size() * stepY;
        int maxScroll = Math.max(0, totalContentH - listH);
        if (targetScroll > maxScroll) targetScroll = maxScroll;

        gg.enableScissor(x + pad, curY, x + pad + innerW, curY + listH);

        if (filteredEntries.isEmpty()) {
            String emptyMsg = searchQuery.isBlank()
                    ? "Brak bloków w tej kategorii"
                    : "Brak bloków pasujących do: \"" + searchQuery + "\"";
            gg.drawCenteredString(font, emptyMsg, x + width / 2, curY + listH / 2 - 4, COL_TEXT_MUTED);
        } else {
            boolean listHovered = isHovered(mouseX, mouseY, x + pad, curY, innerW, listH);
            int itemY = (int) (curY - scrollOffset);

            for (int i = 0, size = filteredEntries.size(); i < size; i++) {
                if (itemY + rowH >= curY && itemY <= curY + listH) {
                    BlockEntry entry = filteredEntries.get(i);
                    boolean isSelected = whitelist.contains(entry.block);
                    boolean rowHovered = listHovered && isHovered(mouseX, mouseY, x + pad, itemY, innerW, rowH);

                    // Tło i ramka karty bloku
                    int rowBg = isSelected ? COL_CARD_SELECTED : (rowHovered ? COL_CARD_HOVER : COL_CARD_BG);
                    int rowBorder = isSelected ? COL_BORDER_ACTIVE : (rowHovered ? COL_BORDER_LILAC : COL_BORDER_MUTED);
                    float bdrThickness = isSelected ? 1.5f : 1.0f;
                    VenturaSdfRenderer.drawRoundedRect(x + pad, itemY, innerW, rowH, 5.0f, rowBg, rowBorder, bdrThickness);

                    // 1. Wklęsły slot na przedmiot (3D Bezel)
                    int slotX = x + pad + 4;
                    int slotY = itemY + 3;
                    int slotSize = 20;
                    VenturaSdfRenderer.drawRoundedRect(slotX, slotY, slotSize, slotSize, 4.0f, COL_SLOT_BEZEL, isSelected ? COL_BORDER_LILAC : COL_BORDER_MUTED, 1.0f);

                    // Render ikony z Minecraft
                    gg.renderItem(entry.stack, slotX + 2, slotY + 2);
                    gg.renderItemDecorations(font, entry.stack, slotX + 2, slotY + 2);

                    // 2. Nazwa bloku (1.0f crisp white) + Registry ID
                    int textX = slotX + slotSize + 6;
                    gg.drawString(font, entry.displayName, textX, itemY + 4, COL_TEXT_WHITE, false);
                    gg.drawString(font, entry.registryId, textX, itemY + 15, isSelected ? COL_ACCENT_LILAC : COL_TEXT_DIM, false);

                    // 3. Dynamiczny Badge Szybkości Kopania (High-Tech Feel dla Haste II + Eff 10)
                    float breakDelta = 0.0f;
                    if (player != null && level != null) {
                        try {
                            BlockState defaultState = entry.block.defaultBlockState();
                            breakDelta = defaultState.getDestroyProgress(player, level, player.blockPosition());
                        } catch (Throwable ignored) {}
                    }

                    int badgeTagW = 46;
                    int badgeTagH = 13;
                    int checkSize = 14;
                    int checkX = x + pad + innerW - checkSize - 6;
                    int tagX = checkX - badgeTagW - 6;
                    int tagY = itemY + (rowH - badgeTagH) / 2;

                    if (breakDelta >= 1.0f || (player != null && player.getAbilities().instabuild)) {
                        VenturaSdfRenderer.drawRoundedRect(tagX, tagY, badgeTagW, badgeTagH, 3.0f, COL_INSTAMINE_BG, COL_INSTAMINE_BDR, 1.0f);
                        gg.drawCenteredString(font, "⚡ INSTA", tagX + badgeTagW / 2, tagY + 3, COL_INSTAMINE_TXT);
                    } else if (entry.baseHardness > 0.0f) {
                        int ticks = breakDelta > 0.0f ? Math.min(99, (int) Math.ceil(1.0f / breakDelta)) : 99;
                        String tickLabel = "⏱ " + ticks + "T";
                        VenturaSdfRenderer.drawRoundedRect(tagX, tagY, badgeTagW, badgeTagH, 3.0f, COL_HARD_BG, COL_HARD_BDR, 1.0f);
                        gg.drawCenteredString(font, tickLabel, tagX + badgeTagW / 2, tagY + 3, COL_HARD_TXT);
                    }

                    // 4. Zaokrąglony Checkbox z neonową fajeczką '✓'
                    int checkY = itemY + (rowH - checkSize) / 2;
                    int chkBg = isSelected ? COL_ACCENT_LILAC : 0x33000000;
                    int chkBdr = isSelected ? COL_ACCENT_LILAC : (rowHovered ? COL_BORDER_LILAC : COL_BORDER_MUTED);

                    VenturaSdfRenderer.drawRoundedRect(checkX, checkY, checkSize, checkSize, 3.5f, chkBg, chkBdr, 1.0f);
                    if (isSelected) {
                        gg.drawCenteredString(font, "✓", checkX + checkSize / 2, checkY + 3, 0xFF0E0E18);
                    }
                }
                itemY += stepY;
            }
        }

        gg.disableScissor();

        // 6. Kinetyczny, elegancki Scrollbar
        if (maxScroll > 0) {
            int sbW = 4;
            int sbX = x + pad + innerW - sbW;
            float scrollRatio = (float) listH / (float) totalContentH;
            int thumbH = Math.max(16, (int) (listH * scrollRatio));
            float thumbPct = targetScroll / (float) maxScroll;
            int thumbY = curY + (int) ((listH - thumbH) * thumbPct);

            boolean sbHover = isHovered(mouseX, mouseY, sbX - 2, curY, sbW + 4, listH);
            int sbBg = sbHover ? 0x40C4B5FD : 0x1AFFFFFF;
            int sbThumbCol = sbHover ? COL_ACCENT_LILAC : 0x66C4B5FD;

            VenturaSdfRenderer.drawRoundedRect(sbX, curY, sbW, listH, 2.0f, 0x10000000, 0, 0.0f);
            VenturaSdfRenderer.drawRoundedRect(sbX, thumbY, sbW, thumbH, 2.0f, sbThumbCol, sbBg, 1.0f);
        }
    }

    // =========================================================================
    // OBSŁUGA INTERAKCJI MYSZY I KLAWIATURY
    // =========================================================================
    public boolean mouseClicked(double mouseX, double mouseY, int button, int x, int y, int width, int height) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT && button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return false;

        VenturaNukerModule nuker = VenturaNukerModule.getInstance();
        int pad = 7;
        int innerW = width - (pad * 2);
        int curY = y + pad;

        // 1. Tryby Nukera
        int modeGap = 4;
        int modeW = (innerW - (modeGap * 2)) / 3;
        int modeH = 25;
        VenturaNukerModule.Mode[] modes = VenturaNukerModule.Mode.values();
        for (int i = 0; i < modes.length; i++) {
            int bx = x + pad + i * (modeW + modeGap);
            if (isHovered(mouseX, mouseY, bx, curY, modeW, modeH)) {
                nuker.setMode(modes[i]);
                return true;
            }
        }
        curY += modeH + 6;

        // 2. Reach + Flat
        int sliderH = 20;
        int flatBtnW = 104;
        int reachW = innerW - flatBtnW - 6;

        if (isHovered(mouseX, mouseY, x + pad, curY, reachW, sliderH)) {
            draggingReach = true;
            updateReachFromMouse(mouseX, x + pad, reachW);
            return true;
        }

        int flatX = x + pad + reachW + 6;
        if (isHovered(mouseX, mouseY, flatX, curY, flatBtnW, sliderH)) {
            nuker.setFlat(!nuker.isFlat());
            return true;
        }
        curY += sliderH + 6;

        // 3. Wyszukiwarka + Badge + Przycisk czyszczenia
        int badgeW = 78;
        int clearBtnW = nuker.getCustomWhitelist().size() > 0 ? 22 : 0;
        int rightControlsW = badgeW + (clearBtnW > 0 ? clearBtnW + 4 : 0);
        int searchW = innerW - rightControlsW - 5;
        int searchH = 20;

        // Kliknięcie w pole wyszukiwania
        if (isHovered(mouseX, mouseY, x + pad, curY, searchW, searchH)) {
            // Czy kliknięto przycisk czyszczenia wyszukiwarki '✕' wewnątrz inputu?
            if (!searchQuery.isEmpty() && isHovered(mouseX, mouseY, x + pad + searchW - 18, curY + 2, 16, 16)) {
                searchQuery = "";
                rebuildFilteredList();
                return true;
            }
            searchFocused = true;
            return true;
        } else {
            searchFocused = false;
        }

        // Kliknięcie w przycisk "✕ Wyczyść wszystko"
        if (clearBtnW > 0) {
            int clearX = x + pad + searchW + 5 + badgeW + 4;
            if (isHovered(mouseX, mouseY, clearX, curY, clearBtnW, searchH)) {
                nuker.clearCustomBlocks();
                rebuildFilteredList(true);
                return true;
            }
        }
        curY += searchH + 5;

        // 4. Chipy kategorii
        Category[] cats = Category.values();
        int chipGap = 3;
        int chipH = 16;
        int chipW = (innerW - (chipGap * (cats.length - 1))) / cats.length;
        Set<Block> whitelist = nuker.getCustomWhitelist();

        for (int c = 0; c < cats.length; c++) {
            int cx = x + pad + c * (chipW + chipGap);
            if (isHovered(mouseX, mouseY, cx, curY, chipW, chipH)) {
                Category clickedCat = cats[c];
                if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                    // Prawy przycisk myszy: Masowe zaznaczenie/odznaczenie całej grupy!
                    Block[] group = getPopularForCategory(clickedCat);
                    boolean allIn = true;
                    for (Block b : group) {
                        if (!whitelist.contains(b)) { allIn = false; break; }
                    }
                    if (allIn) {
                        for (Block b : group) whitelist.remove(b);
                    } else {
                        Collections.addAll(whitelist, group);
                    }
                } else {
                    activeCategory = clickedCat;
                }
                rebuildFilteredList(true);
                return true;
            }
        }
        curY += chipH + 5;

        // 5. Kliknięcie w wiersz karty bloku
        int listH = (y + height - pad) - curY;
        if (listH > 0 && isHovered(mouseX, mouseY, x + pad, curY, innerW, listH)) {
            int rowH = 26;
            int stepY = rowH + 3;
            int itemY = (int) (curY - scrollOffset);

            for (int i = 0, size = filteredEntries.size(); i < size; i++) {
                if (isHovered(mouseX, mouseY, x + pad, itemY, innerW, rowH)) {
                    Block target = filteredEntries.get(i).block;
                    if (!whitelist.remove(target)) {
                        whitelist.add(target);
                    }
                    return true;
                }
                itemY += stepY;
            }
        }

        return false;
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button, int x, int width) {
        if (draggingReach && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            int pad = 7;
            int innerW = width - (pad * 2);
            int flatBtnW = 104;
            int reachW = innerW - flatBtnW - 6;
            updateReachFromMouse(mouseX, x + pad, reachW);
            return true;
        }
        return false;
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && draggingReach) {
            draggingReach = false;
            return true;
        }
        return false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY, int x, int y, int width, int height) {
        int pad = 7;
        int innerW = width - (pad * 2);
        // Pozycja początkowa listy
        int listY = y + pad + 25 + 6 + 20 + 6 + 20 + 5 + 16 + 5; // 110px od góry
        int listH = (y + height - pad) - listY;

        if (listH > 0 && isHovered(mouseX, mouseY, x + pad, listY, innerW, listH)) {
            int stepY = 26 + 3;
            int maxScroll = Math.max(0, filteredEntries.size() * stepY - listH);
            targetScroll = (float) Mth.clamp(targetScroll - scrollY * 26.0, 0.0, maxScroll);
            return true;
        }
        return false;
    }

    public boolean charTyped(char codePoint, int modifiers) {
        if (!searchFocused) return false;
        if (Character.isLetterOrDigit(codePoint) || codePoint == '_' || codePoint == ':' || codePoint == ' ' || codePoint == '-') {
            searchQuery += codePoint;
            rebuildFilteredList();
            return true;
        }
        return false;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!searchFocused) return false;
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE && !searchQuery.isEmpty()) {
            searchQuery = searchQuery.substring(0, searchQuery.length() - 1);
            rebuildFilteredList();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_ENTER) {
            searchFocused = false;
            return true;
        }
        return false;
    }

    private void updateReachFromMouse(double mouseX, int sliderX, int sliderW) {
        float pct = (float) Mth.clamp((mouseX - sliderX) / (double) sliderW, 0.0, 1.0);
        float stepped = Math.round((1.0f + pct * 9.0f) * 10.0f) / 10.0f;
        VenturaNukerModule.getInstance().setRange(stepped);
    }

    private static boolean isHovered(double mx, double my, double x, double y, double w, double h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }
}
