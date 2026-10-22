package com.astra.revolution.gui;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import baritone.api.BaritoneAPI;
import baritone.api.utils.SettingsUtil;
import com.astra.revolution.visuals.AstraVisualsEngine;

/**
 * BaritoneBypassScreen V2 (10/10 Obsidian & Lilac Glass - Zero Glow-Box Edition)
 * Zawiera zunifikowany Design System (Obsidian & Lilac Glass),
 * sprzętowo akcelerowany silnik OpenGL 3.3 SDF (VenturaSdfRenderer)
 * oraz wektorowy system ikon (VectorIcon) współdzielony z AstraSchematicScreen.
 */
public class BaritoneBypassScreen extends Screen {

    // =========================================================================
    // POLA KOMPATYBILNOŚCI WSTECZNEJ (BARITONE / ANARCHIA COMMAND / MIXINS)
    // =========================================================================
    public static int OPEN_KEY = GLFW.GLFW_KEY_RIGHT_SHIFT;
    public static boolean wildPillHud = true;
    public static boolean anarchiaMode = false;
    public static boolean autoDropTrash = true;
    public static boolean autoSortInventory = true;
    public static boolean itemLockProtection = true;
    public static boolean disconnectOnLowHealth = true;
    public static float disconnectHealthHearts = 6.0f;
    public static boolean disconnectOnFall = true;
    public static float disconnectFallDistance = 5.0f;
    public static volatile boolean BOT_ACTIVE = false;

    private static boolean staticSearchFocusedState = false;

    // =========================================================================
    // MOST KOMPATYBILNOŚCI Z HUD (STATUS CAPSULE HUD & BARITONE TELEMETRY)
    // =========================================================================
    public static final class AstraBaritoneBridge {
        private static final AstraBaritoneBridge INSTANCE = new AstraBaritoneBridge();
        public static AstraBaritoneBridge getInstance() { return INSTANCE; }

        private boolean statusCapsuleHud = true;
        public boolean isStatusCapsuleHud() { return statusCapsuleHud; }
        public void setStatusCapsuleHud(boolean val) { this.statusCapsuleHud = val; }

        private static String manualTaskOverride = null;
        private static float cachedTps = 20.0f;
        private static long lastTickMeasureMs = System.currentTimeMillis();

        public static void setActiveTaskOverride(String task) {
            manualTaskOverride = task;
        }

        public static String getActiveTaskName() {
            if (manualTaskOverride != null && !manualTaskOverride.isBlank()) {
                return manualTaskOverride.toUpperCase(Locale.ROOT);
            }
            try {
                if (com.astra.revolution.schematic.AstraSchematicManager.getInstance() != null
                        && com.astra.revolution.schematic.AstraSchematicManager.getInstance().isActive()) {
                    return "SCHEM";
                }
            } catch (Throwable ignored) {
            }
            try {
                Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
                Method getProvider = apiClass.getMethod("getProvider");
                Object provider = getProvider.invoke(null);
                Object primary = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);

                Object pathingBehavior = primary.getClass().getMethod("getPathingBehavior").invoke(primary);
                boolean isPathing = (boolean) pathingBehavior.getClass().getMethod("isPathing").invoke(pathingBehavior);

                Object mineProcess = primary.getClass().getMethod("getMineProcess").invoke(primary);
                if ((boolean) mineProcess.getClass().getMethod("isActive").invoke(mineProcess)) return "MINE";

                Object builderProcess = primary.getClass().getMethod("getBuilderProcess").invoke(primary);
                if ((boolean) builderProcess.getClass().getMethod("isActive").invoke(builderProcess)) return "BUILD";

                Object farmProcess = primary.getClass().getMethod("getFarmProcess").invoke(primary);
                if ((boolean) farmProcess.getClass().getMethod("isActive").invoke(farmProcess)) return "FARM";

                Object followProcess = primary.getClass().getMethod("getFollowProcess").invoke(primary);
                if ((boolean) followProcess.getClass().getMethod("isActive").invoke(followProcess)) return "FOLLOW";

                if (isPathing) return "PATH";
            } catch (Throwable ignored) {
            }
            return "IDLE";
        }

        public static boolean isBaritoneActive() {
            return !"IDLE".equalsIgnoreCase(getActiveTaskName());
        }

        public static int getPingMs() {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.getConnection() == null) return 0;
            PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
            return info != null ? Math.max(0, info.getLatency()) : 0;
        }

        public static float getServerTps() {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return 20.0f;
            long now = System.currentTimeMillis();
            if (now - lastTickMeasureMs > 1000L) {
                lastTickMeasureMs = now;
                float tickRate = mc.level.tickRateManager().tickrate();
                cachedTps = Mth.clamp(tickRate, 0.0f, 20.0f);
            }
            return cachedTps;
        }

        public static double getPlayerSpeedBps() {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return 0.0;
            double dx = mc.player.getX() - mc.player.xo;
            double dz = mc.player.getZ() - mc.player.zo;
            return Math.hypot(dx, dz) * 20.0;
        }

        public static int getOccupiedInventorySlots() {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return 0;
            Inventory inv = mc.player.getInventory();
            int used = 0;
            for (int i = 0; i < 36; i++) {
                ItemStack stack = inv.items.get(i);
                if (!stack.isEmpty()) used++;
            }
            return used;
        }
    }

    public static boolean isTypingInTextField() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof BaritoneBypassScreen bbs) {
            return bbs.searchFocused;
        }
        return staticSearchFocusedState;
    }

    public static void drawCenteredRowTextStatic(GuiGraphics g, Font font, String text, float x, float cy, int color) {
        if (text == null || text.isEmpty()) return;
        Font f = (font != null) ? font : Minecraft.getInstance().font;
        g.drawString(f, text, Math.round(x), (int) (cy - f.lineHeight * 0.5f + 1.0f), color, false);
    }

    public static void drawCenteredRowTextStatic(GuiGraphics g, Font font, String text, float x, float rowY, float rowH, int color) {
        if (text == null || text.isEmpty()) return;
        Font f = (font != null) ? font : Minecraft.getInstance().font;
        int yPos = Math.round(rowY + (rowH - f.lineHeight) * 0.5f) + 1;
        g.drawString(f, text, Math.round(x), yPos, color, false);
    }

    public static void drawCenteredRowTextStatic(GuiGraphics g, String text, float x, float rowY, float rowH, int color) {
        drawCenteredRowTextStatic(g, Minecraft.getInstance().font, text, x, rowY, rowH, color);
    }

    public static void drawCenteredRowTextStatic(GuiGraphics g, Font font, Component text, float x, float rowY, float rowH, int color) {
        if (text == null) return;
        Font f = (font != null) ? font : Minecraft.getInstance().font;
        int yPos = Math.round(rowY + (rowH - f.lineHeight) * 0.5f) + 1;
        g.drawString(f, text, Math.round(x), yPos, color, false);
    }

    // =========================================================================
    // WYMIARY OKNA (WSPÓLNE DLA OBU EKRANÓW)
    // =========================================================================
    public static final float WIN_WIDTH     = 690.0f;
    public static final float WIN_HEIGHT    = 430.0f;
    public static final float SIDEBAR_WIDTH = 156.0f;
    public static final float TOPBAR_HEIGHT = 42.0f;
    public static final float WIN_RADIUS    = 12.0f;
    public static final float CARD_RADIUS   = 8.5f;

    // =========================================================================
    // DESIGN SYSTEM: OBSIDIAN & LILAC GLASS (PALETA V2 10/10)
    // =========================================================================
    // Tła powierzchni szklanych (ARGB) - skorygowany kontrast kart względem tła
    public static final int COL_BG_MAIN        = 0xCC09090E; // Półprzezroczyste czarne szkło okna (~80% krycia)
    public static final int COL_BG_SIDEBAR     = 0xD60C0C13; // Głęboki obsydian panelu bocznego
    public static final int COL_BG_TOPBAR      = 0xD60C0C13; // Głęboki obsydian górnego paska
    public static final int COL_BG_SIDEBAR_ACT = 0x26A78BFA; // Półprzezroczyste lawendowe podświetlenie aktywnego tabu
    public static final int COL_BG_CARD        = 0xBA100F18; // Wyraźnie odcięta karta z ametystową nutą (Poprawka V2)
    public static final int COL_BG_CARD_HOVER  = 0xD4191726; // Karta po najechaniu kursorem (płynny lerp hover)
    public static final int COL_BG_FIELD       = 0xB80A0A10; // Wnętrza pól tekstowych, inputów, tagów i wskaźników
    public static final int COL_BG_POPUP       = 0xEE0D0C14; // Pływające menu popup
    public static final int COL_WINDOW_SHADOW  = 0x90000000; // Głęboki drop-shadow okna głównego (blur 16px, offset +8px)

    // Obramowania i linie podziału (subtelne, matowe szkło, NIE jaskrawy neon!)
    public static final int COL_BORDER_WINDOW   = 0x28A78BFA; // Delikatna ramka okna (1px)
    public static final int COL_BORDER_CARD     = 0x18FFFFFF; // Subtelna szklana ramka karty (~10% bieli)
    public static final int COL_BORDER_CARD_ACT = 0x38A78BFA; // Ramka aktywnej/włączonej karty
    public static final int COL_BORDER_FIELD    = 0x1EFFFFFF; // Ramka pola input / tagu
    public static final int COL_DIVIDER         = 0x16FFFFFF; // Pionowe i poziome linie podziału

    // Akcenty kolorystyczne (Amethyst & Pastel Lilac)
    public static final int COL_ACCENT_LILAC   = 0xFFC4B5FD; // Jasny pastelowy fiolet (wartości, ikony, nagłówki)
    public static final int COL_ACCENT_PURPLE  = 0xFFA78BFA; // Główny ametystowy fiolet (suwaki, aktywne przełączniki)
    public static final int COL_ACCENT_TRANS   = 0xB8A78BFA; // Półprzezroczyste wypełnienie akcentujące
    public static final int COL_ACCENT_GLOW    = 0x38A78BFA; // Miękka poświata
    public static final int COL_TOGGLE_OFF_BG  = 0xB009090E; // Tło wyłączonego przełącznika
    public static final int COL_TOGGLE_OFF_KNOB= 0xFF646478; // Kropka wyłączonego przełącznika

    // Statusy funkcyjne
    public static final int COL_STATUS_GREEN   = 0xFF10B981; // GrimAC Safe / Sukces / Załadowany
    public static final int COL_STATUS_AMBER   = 0xFFF59E0B; // Ostrzeżenie / Ryzyko flagi
    public static final int COL_STATUS_CRIMSON = 0xFFF43F5E; // Stop / Brak bloków / Błąd

    // Typografia
    public static final int COL_TEXT_WHITE     = 0xFFF5F3FF; // Tytuły i główne etykiety
    public static final int COL_TEXT_LILAC     = 0xFFD8B4FE; // Wartości, jednostki, aktywne stany
    public static final int COL_TEXT_GRAY      = 0xFF9494A8; // Opisy pomocnicze, subtelne teksty
    public static final int COL_TEXT_TAG       = 0xFFB8A9FF; // Tekst w pigułkach tagów
    public static final int COL_TEXT_SECTION   = 0xFF68647E; // Nagłówki sekcji ("Main", "Other")

    // =========================================================================
    // WEKTOROWE IKONY SDF (ZASTĘPUJĄ EMOTIKONY UNICODE W OBU GUI)
    // =========================================================================
    public enum VectorIcon {
        VENTURA_LOGO,
        SHIELD,      // GrimAC Bypass
        MOVEMENT,    // Parkour / Sprint / Velocity
        MINING,      // AutoMine / Legit Tunnel
        RENDER,      // ESP / Path Render
        LAYERS,      // Schematics (.litematic / .schem)
        COMPASS,     // 3D D-Pad & Transform
        BOX,         // Material & Shulker Audit
        BOLT,        // Fast-Bridge Eagle
        SEARCH,      // Wyszukiwarka
        SLIDERS,     // Presety i zaawansowane
        CHECK,       // Status OK
        REFRESH,     // Przeładuj / Synchronizuj
        PLAY,        // Start Printer / Baritone
        STOP,        // Stop / Cancel
        ARROW_UP,
        ARROW_DOWN,
        ARROW_LEFT,
        ARROW_RIGHT,
        FOLDER,
        SHULKER_BOX,
        DISK_SAVE,
        ROTATE_CW,
        PICKAXE,
        WHEAT,
        RUNNER,
        EYE,
        STAR,
        CHEVRON_RIGHT,
        SLIDER_MINI,
        GRID_FOUR,
        TOGGLE_MINI,
        HEART,
        TRASH,
        FLAME,
        SERVER_BOX,
        WIFI_SIGNAL,
        SPEEDOMETER,
        USER
    }

    // =========================================================================
    // MODEL USTAWIEŃ BARITONE BYPASS (Category zdefiniowana w Category.java)
    // =========================================================================

    public static final class BypassCard {
        public final String id;
        public final String title;
        public final String description;
        public final String securityTag;
        public final int tagStatusColor;
        public final Category category;

        public boolean enabled;
        public float toggleAnim;
        public float hoverAnim;
        public float searchHighlightAnim;
        public float clickPulseAnim;

        // Opcjonalny suwak i selektor trybu wewnątrz karty
        public final boolean hasSlider;
        public final String sliderLabel;
        public final String sliderUnit;
        public final float minVal;
        public final float maxVal;
        public float currentVal;
        public float renderedVal;

        public final String[] modes;
        public int modeIndex;

        public BypassCard(String id, String title, String description, String securityTag,
                          int tagStatusColor, Category category, boolean defaultEnabled,
                          boolean hasSlider, String sliderLabel, String sliderUnit,
                          float minVal, float maxVal, float defaultVal, String... modes) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.securityTag = securityTag;
            this.tagStatusColor = tagStatusColor;
            this.category = category;
            this.enabled = defaultEnabled;
            this.toggleAnim = defaultEnabled ? 1.0f : 0.0f;
            this.hasSlider = hasSlider;
            this.sliderLabel = sliderLabel;
            this.sliderUnit = sliderUnit;
            this.minVal = minVal;
            this.maxVal = maxVal;
            this.currentVal = defaultVal;
            this.renderedVal = defaultVal;
            this.modes = modes != null ? modes : new String[0];
            this.modeIndex = 0;
        }
    }

    public static final class SavedCardState {
        public boolean enabled;
        public float value;
        public int modeIndex;

        public SavedCardState(boolean enabled, float value, int modeIndex) {
            this.enabled = enabled;
            this.value = value;
            this.modeIndex = modeIndex;
        }
    }

    private static final java.util.Map<String, SavedCardState> PERSISTENT_CARD_STATES = new java.util.concurrent.ConcurrentHashMap<>();
    private static Category lastActiveCategory = Category.BYPASS;

    // =========================================================================
    // STAN EKRANU
    // =========================================================================
    private final Screen parentScreen;
    private final List<BypassCard> cards = new ArrayList<>();
    private Category activeCategory = lastActiveCategory;
    private float openProgress = 0.0f;
    private long lastFrameTimeNs = System.nanoTime();

    // Wyszukiwarka
    private String searchQuery = "";
    private boolean searchFocused = false;
    private float searchFocusAnim = 0.0f;

    // Płynny Scrollbar SDF
    private float scrollTarget = 0.0f;
    private float scrollCurrent = 0.0f;
    private float maxScroll = 0.0f;
    private boolean draggingScrollbar = false;
    private float scrollbarDragStartY = 0.0f;
    private float scrollbarStartTarget = 0.0f;
    private float scrollbarHoverAnim = 0.0f;

    // Interakcja z suwakiem karty
    private BypassCard activeSliderCard = null;

    // Mikro-feedback dla przełączników i presetów
    private String activePresetName = "GrimAC 2.3.65 Strict";
    private float statusPulsePhase = 0.0f;

    public BaritoneBypassScreen() {
        this(null);
    }

    public BaritoneBypassScreen(Screen parentScreen) {
        super(Component.literal("Astra Baritone Bypass"));
        this.parentScreen = parentScreen;
        initCards();
    }

    @Override
    public void onClose() {
        if (this.parentScreen != null && this.minecraft != null) {
            this.minecraft.setScreen(this.parentScreen);
        } else {
            super.onClose();
        }
    }

    private void initCards() {
        cards.clear();

        // Kategoria: BYPASS
        cards.add(new BypassCard(
                "grim_rot_gcd", "Rotation GCD Quantizer",
                "Wymusza skok kątowy zgodny z czułością myszy klienta (naprawia AimModulo360 i AimDuplicateLook).",
                "GRIM SAFE", COL_STATUS_GREEN, Category.BYPASS, true,
                true, "Sensitivity Jitter", "%", 0.0f, 100.0f, 94.0f,
                "Strict Mathematical", "Gaussian Human", "Cinematic Smooth"
        ));
        cards.add(new BypassCard(
                "grim_interact_ray", "Strict Raycast Validation",
                "Blokuje interakcje poza osią wzroku (eliminuje flagi Hitbox / Reach / WallInteract).",
                "GRIM SAFE", COL_STATUS_GREEN, Category.BYPASS, true,
                true, "Max Reach Distance", "m", 3.0f, 4.5f, 4.2f,
                "Vanilla 1.21.4", "Server-Side EyePos"
        ));
        cards.add(new BypassCard(
                "movement_fix_input", "Silent MoveFix (Strafe Correction)",
                "Koryguje wektor ruchu W/A/S/D względem serwerowego Yaw (przechodzi Grim Simulation 0.0001).",
                "SIMULATION OK", COL_STATUS_GREEN, Category.BYPASS, true,
                true, "Input Snap Threshold", "deg", 15.0f, 45.0f, 22.5f,
                "Full 8-Direction", "Keyboard Strict"
        ));
        cards.add(new BypassCard(
                "packet_order_1214", "1.21.4 TickEnd Packet Sequencer",
                "Synchronizuje pakiety UseItemOn z nowym cyklem ClientTickEnd wprowadzonym w 1.21.4.",
                "GRIM SAFE", COL_STATUS_GREEN, Category.BYPASS, true,
                false, "", "", 0, 0, 0,
                "Post-Movement", "Pre-Tick"
        ));

        // Kategoria: MOVEMENT
        cards.add(new BypassCard(
                "legit_parkour", "Humanized Parkour Jump",
                "Opóźnia skok na krawędzi bloku o losowy rozkład Gaussa, unikając detekcji perfekcyjnych skoków.",
                "GRIM SAFE", COL_STATUS_GREEN, Category.MOVEMENT, true,
                true, "Edge Margin", "m", 0.02f, 0.25f, 0.08f,
                "Gaussian Delay", "Early Edge"
        ));
        cards.add(new BypassCard(
                "sprint_omni_guard", "Omni-Sprint Desync Guard",
                "Zatrzymuje pakiet StartSprinting podczas cofania lub obrotu o ponad 90 stopni.",
                "GRIM SAFE", COL_STATUS_GREEN, Category.MOVEMENT, true,
                false, "", "", 0, 0, 0,
                "Strict Forward", "Camera Relative"
        ));
        cards.add(new BypassCard(
                "water_mlg_bucket", "Predictive MLG Bucket",
                "Oblicza trajektorię spadania z uwzględnieniem opóźnienia sieciowego RTT.",
                "MEDIUM RISK", COL_STATUS_AMBER, Category.MOVEMENT, false,
                true, "Pre-Rotate Ticks", "t", 1.0f, 6.0f, 3.0f,
                "Legit Hotbar Swap", "Offhand"
        ));

        // Kategoria: MINING
        cards.add(new BypassCard(
                "mine_abort_cooldown", "Switch Block Cooldown",
                "Wprowadza ludzką przerwę po zniszczeniu rudy przed obrotem na kolejny blok.",
                "GRIM SAFE", COL_STATUS_GREEN, Category.MINING, true,
                true, "Reaction Delay", "ms", 20.0f, 220.0f, 85.0f,
                "Dynamic Ping", "Fixed Range"
        ));
        cards.add(new BypassCard(
                "anti_xray_heuristics", "Anti-Xray Cave Bias",
                "Prowadzi tunel wyłącznie przez odsłonięte jaskinie, omijając ukryte pułapki Orebfuscatora.",
                "UNDETECTED", COL_STATUS_GREEN, Category.MINING, true,
                true, "Air Exposure Weight", "x", 1.0f, 5.0f, 3.2f,
                "Exposed Only", "Hybrid Ray"
        ));

        // Kategoria: RENDER (Visuals)
        var s = BaritoneAPI.getSettings();
        boolean clientOverlayVal = s != null && s.clientBlockOverlay != null ? s.clientBlockOverlay.value : true;
        float blockOverlaySpeedVal = s != null && s.blockOverlaySpeed != null ? s.blockOverlaySpeed.value : 16.0f;
        boolean radialWaveVal = s != null && s.radialWave != null ? s.radialWave.value : false;
        float radialWaveRadiusVal = s != null && s.radialWaveRadius != null ? s.radialWaveRadius.value : 8.0f;
        boolean radialWaveDoubleVal = s != null && s.radialWaveDouble != null ? s.radialWaveDouble.value : false;
        float radialWaveSpeedVal = s != null && s.radialWaveSpeed != null ? s.radialWaveSpeed.value : 6.0f;
        boolean blockOverlayWaveVal = s != null && s.blockOverlayWave != null ? s.blockOverlayWave.value : true;
        float blockOverlayAlphaVal = s != null && s.blockOverlayAlpha != null ? s.blockOverlayAlpha.value * 100.0f : 25.0f;
        boolean miningWaveVal = s != null && s.renderMiningWaveOverlay != null ? s.renderMiningWaveOverlay.value : true;
        float blockOverlayLineWidthVal = s != null && s.blockOverlayLineWidth != null ? s.blockOverlayLineWidth.value : 2.0f;
        boolean selBoxesVal = s != null && s.renderSelectionBoxes != null ? s.renderSelectionBoxes.value : true;
        float pathWidthVal = s != null && s.pathRenderLineWidthPixels != null ? s.pathRenderLineWidthPixels.value : 2.5f;
        boolean filledBoxesVal = s != null && s.renderFilledBoxes != null ? s.renderFilledBoxes.value : true;
        float filledBoxAlphaVal = s != null && s.filledBoxAlpha != null ? s.filledBoxAlpha.value * 100.0f : 50.0f;
        boolean chinaHatVal = s != null && s.chinaHat != null ? s.chinaHat.value : true;
        float chinaHatRadiusVal = s != null && s.chinaHatRadius != null ? s.chinaHatRadius.value : 0.65f;
        boolean handShadersVal = s != null && s.handShaders != null ? s.handShaders.value : true;
        boolean disguiseVal = s != null && s.customPlayerDisguise != null && s.customPlayerDisguise.value != null && !s.customPlayerDisguise.value.equalsIgnoreCase("OFF");
        boolean motionTrailVal = s != null && s.motionTrail != null ? s.motionTrail.value : true;
        boolean targetEspVal = s != null && s.targetEsp != null ? s.targetEsp.value : true;
        boolean jumpCirclesVal = s != null && s.jumpCircles != null ? s.jumpCircles.value : true;
        boolean customFogVal = s != null && s.customFog != null ? s.customFog.value : true;
        boolean customSkyVal = s != null && s.customSky != null ? s.customSky.value : true;
        boolean ambientParticlesVal = s != null && s.ambientParticles != null ? s.ambientParticles.value : true;
        boolean motionBlurVal = s != null && s.motionBlur != null ? s.motionBlur.value : true;
        float motionBlurStrengthVal = s != null && s.motionBlurStrength != null ? s.motionBlurStrength.value.floatValue() : 25.0f;
        int motionBlurModeVal = s != null && s.motionBlurMode != null ? s.motionBlurMode.value : 0;

        cards.add(new BypassCard(
                "client_block_overlay", "Ventura Block Overlay",
                "Szklisty, aksamitny sześcian wokół celowanego bloku (pochodnie, płotki, płyty) z poświatą Lilac & Cyan.",
                "VENTURA FX", COL_ACCENT_LILAC, Category.RENDER, clientOverlayVal,
                true, "Lerp Speed", "spd", 5.0f, 40.0f, blockOverlaySpeedVal,
                "Smooth Lerp", "Instant Snap", "X-Ray Depth"
        ));
        cards.add(new BypassCard(
                "radial_ground_wave", "Radial Wave Visuals",
                "Fala szklanych bloków rozchodząca się po ziemi od stóp gracza wyłącznie przy wyskoku.",
                "VENTURA FX", COL_ACCENT_LILAC, Category.RENDER, radialWaveVal,
                true, "Wave Radius", "m", 3.0f, 16.0f, radialWaveRadiusVal,
                "Tylko wyskok (Jump)", "Ciągła fala (Continuous)"
        ));
        cards.add(new BypassCard(
                "radial_wave_speed", "Wave Pulse Frequency",
                "Prędkość rozchodzenia się fali uderzeniowej po blokach i paleta kolorów.",
                "AESTHETICS", COL_ACCENT_LILAC, Category.RENDER, radialWaveDoubleVal,
                true, "Expansion Speed", "m/s", 2.0f, 12.0f, radialWaveSpeedVal,
                "Ventura Lilac", "Neon Cyan", "Dual Gradient"
        ));
        cards.add(new BypassCard(
                "glass_fill_opacity", "Block Overlay Glass Fill",
                "Przezroczystość aksamitnego, szklanego wypełnienia sześcianu bloku.",
                "AESTHETICS", COL_ACCENT_LILAC, Category.RENDER, blockOverlayWaveVal,
                true, "Glass Alpha", "%", 5.0f, 80.0f, blockOverlayAlphaVal,
                "Lilac Velvet", "Crystal Sheen"
        ));
        cards.add(new BypassCard(
                "mining_target_wave", "Mining Wave Highlight",
                "Pulsujący sześcian energii na aktualnie wydobywanym bloku przez Baritone / Bypass.",
                "MINING FX", COL_STATUS_GREEN, Category.RENDER, miningWaveVal,
                true, "Line Width", "px", 1.0f, 5.0f, blockOverlayLineWidthVal,
                "Cyan Diamond", "Amethyst Gold"
        ));
        cards.add(new BypassCard(
                "sdf_path_bezier", "Bezier Path Spline 3D",
                "Wygładza kanciastą ścieżkę A* Baritone w krzywą Catmull-Rom z poświatą Amethyst.",
                "CLIENT ONLY", COL_ACCENT_LILAC, Category.RENDER, selBoxesVal,
                true, "Line Thickness", "px", 1.0f, 5.0f, pathWidthVal,
                "Amethyst Glow", "Depth Tested"
        ));
        cards.add(new BypassCard(
                "goal_hologram", "Goal Box SDF Hologram",
                "Renderuje półprzezroczysty sześcian celu oraz estymowany czas dotarcia (ETA).",
                "CLIENT ONLY", COL_ACCENT_LILAC, Category.RENDER, filledBoxesVal,
                true, "Hologram Alpha", "%", 10.0f, 100.0f, filledBoxAlphaVal,
                "Pulse Box", "Corner Brackets"
        ));
        cards.add(new BypassCard(
                "china_hat_cosmetic", "China Hat 3D Cosmetic",
                "Holograficzny stożkowy kapelusz nad głową gracza z rotacją, falą i świecącym obrysem (Glowing Rim).",
                "CUSTOM 3D", COL_ACCENT_LILAC, Category.RENDER, chinaHatVal,
                true, "Hat Radius", "m", 0.35f, 1.20f, chinaHatRadiusVal,
                "Lilac Obsidian", "Astra Rainbow", "First Person (F1/F5)"
        ));
        BypassCard handShadersCard = new BypassCard(
                "hand_shaders", "Hand Shaders & Dual-Neon Chams",
                "Podwójna neonowa aura bloom, refrakcyjny kryształ Fresnel, animowane fale caustics i Low-Shield PvP.",
                "GOD-TIER", COL_ACCENT_LILAC, Category.RENDER, handShadersVal,
                true, "Glass Alpha", "%", 20.0f, 95.0f, 68.0f,
                "Cyber Lilac & Cyan", "Chroma Rainbow Flow", "Sunset Neon & Gold", "Emerald Matrix", "Cosmic Amethyst", "Frost Crystal Ice", "Red Inferno & Magma", "Midnight Void", "Holographic Ghost"
        );
        handShadersCard.modeIndex = Math.min(Math.max(0, com.astra.revolution.visuals.AstraHandShader.getModeIndex()), handShadersCard.modes.length - 1);
        cards.add(handShadersCard);
        cards.add(new BypassCard(
                "custom_player_disguise", "Custom Player F5 Disguise",
                "Zastępuje model gracza na F5 (Tung Tung Sahur, Ballerina Capuccina, Cyber Mech, Hologram, Capybara).",
                "F5 MODEL", COL_ACCENT_LILAC, Category.RENDER, disguiseVal,
                false, null, null, 0, 0, 0,
                "Tung Tung Sahur", "Ballerina Capuccina", "Cyber Mech", "Hologram Avatar", "Capybara King", "Wyłączone"
        ));
        BypassCard motionTrailCard = new BypassCard(
                "motion_trail", "Motion Trail 3D Ribbon",
                "Płynna wstęga w przestrzeni 3D z neonową żyłą wzdłuż krawędzi i zerowym jitterem współrzędnych.",
                "3D TRAIL", COL_ACCENT_LILAC, Category.RENDER, motionTrailVal,
                false, null, null, 0, 0, 0,
                "Cyber Lilac & Cyan", "Chroma Rainbow", "Sunset Gold", "Glacial Ice Blue", "Matrix Emerald"
        );
        motionTrailCard.modeIndex = Math.min(Math.max(0, AstraVisualsEngine.getMotionTrailMode()), motionTrailCard.modes.length - 1);
        cards.add(motionTrailCard);

        BypassCard targetEspCard = new BypassCard(
                "target_esp", "Target ESP & Cyber Reticle",
                "Trójwymiarowy, obracający się celownik telemetryczny 3D wokół namierzonego celu (Dual Rings, Health HUD).",
                "TARGET", COL_ACCENT_LILAC, Category.RENDER, targetEspVal,
                false, null, null, 0, 0, 0,
                "Cyber Lilac & Cyan", "Dynamic Health HUD", "Chroma Rainbow Flow", "Golden Radiance", "Matrix Emerald"
        );
        targetEspCard.modeIndex = Math.min(Math.max(0, AstraVisualsEngine.getTargetEspMode()), targetEspCard.modes.length - 1);
        cards.add(targetEspCard);
        BypassCard jumpCirclesCard = new BypassCard(
                "jump_circles", "Jump & Landing Circles (Argon 4.0)",
                "Wielowarstwowe fale uderzeniowe (shockwaves) z podwójnym echem, ground snappingiem do terenu i iskrami energii.",
                "SHOCKWAVE", COL_ACCENT_LILAC, Category.RENDER, jumpCirclesVal,
                false, null, null, 0, 0, 0,
                "Cyber Lilac & Cyan", "Chroma Rainbow", "Celestial Starfire", "Synthwave Sunset", "Magma Blast", "Fluid Water Ripple", "Golden Radiance"
        );
        jumpCirclesCard.modeIndex = Math.min(Math.max(0, AstraVisualsEngine.getJumpCirclesMode()), jumpCirclesCard.modes.length - 1);
        cards.add(jumpCirclesCard);

        BypassCard customFogCard = new BypassCard(
                "custom_fog", "Custom Atmospheric Fog (Argon 4.0)",
                "Kinematyczna mgła przestrzenna: wygładzanie klatkowe, gradient horyzontalny, adaptacja pogody i ochrona płynów.",
                "FOG", COL_ACCENT_LILAC, Category.RENDER, customFogVal,
                false, null, null, 0, 0, 0,
                "Cyber Lilac & Cyan", "Synthwave Sunset", "Deep Galaxy", "Aurora Borealis", "Blood Eclipse", "Ethereal Void", "Chroma Rainbow"
        );
        customFogCard.modeIndex = Math.min(Math.max(0, AstraVisualsEngine.getCustomFogMode()), customFogCard.modes.length - 1);
        cards.add(customFogCard);

        BypassCard customSkyCard = new BypassCard(
                "custom_sky", "Custom Celestial Sky (Argon 4.0)",
                "Niestandardowy kolor kopuły nieba, otchłani i horyzontu: kosmiczny fiolet, neonowy zachód słońca i gwiazdy.",
                "SKY", COL_ACCENT_LILAC, Category.RENDER, customSkyVal,
                false, null, null, 0, 0, 0,
                "Cyber Lilac & Cyan", "Synthwave Sunset", "Deep Galaxy", "Aurora Borealis", "Blood Eclipse", "Ethereal Void", "Chroma Rainbow"
        );
        customSkyCard.modeIndex = Math.min(Math.max(0, AstraVisualsEngine.getCustomSkyMode()), customSkyCard.modes.length - 1);
        cards.add(customSkyCard);
        cards.add(new BypassCard(
                "ambient_particles", "3D Ambient World Particles",
                "Latające po świecie cząsteczki: świecące cyber-kostki lub urocze mini maskotki wokół gracza.",
                "PARTICLES", COL_ACCENT_LILAC, Category.RENDER, ambientParticlesVal,
                false, null, null, 0, 0, 0,
                "Mini Maskotki (Sahurki, Capybary, Baleriny)", "Cyber Stardust (Kwadraty/Diamenty)", "Wszystkie Razem (Mega Mix)"
        ));

        BypassCard motionBlurCard = new BypassCard(
                "motion_blur", "Motion Blur (RTX 4060 Engine)",
                "Kinematyczne wygładzanie klatek (Temporal Accumulation) z adaptacją prędkości kątowej myszy, kompensacją Hz i zerowym ghostingiem tekstu.",
                "RTX ACCEL", COL_ACCENT_LILAC, Category.RENDER, motionBlurVal,
                true, "Blur Strength", "%", 5.0f, 70.0f, motionBlurStrengthVal,
                "Smart Dynamic (Subtle)", "PvP Smooth (12%)", "Cinematic Motion (20%)"
        );
        motionBlurCard.modeIndex = Math.min(Math.max(0, motionBlurModeVal), motionBlurCard.modes.length - 1);
        cards.add(motionBlurCard);

        // Kategoria: PRESETS
        cards.add(new BypassCard(
                "preset_grim_strict", "Profile: GrimAC 2.3.65+ (1.21.4)",
                "Maksymalne bezpieczeństwo symulacji fizyki, cichy MoveFix oraz kwantyzacja GCD.",
                "RECOMMENDED", COL_STATUS_GREEN, Category.PRESETS, true,
                true, "Aggression Factor", "%", 10.0f, 100.0f, 35.0f,
                "Zero-Flag Strict", "Balanced"
        ));
        cards.add(new BypassCard(
                "preset_vulcan_watchdog", "Profile: Vulcan / Matrix Hybrid",
                "Zoptymalizowany pod kątem heurystycznych detekcji kątowych i tempa stawiań bloków.",
                "HYBRID SAFE", COL_STATUS_AMBER, Category.PRESETS, false,
                true, "Max CPS Cap", "cps", 6.0f, 16.0f, 11.5f,
                "Random Burst", "Steady"
        ));

        // 5. Przywracanie zapisanego stanu wszystkich kart (toggles, suwaki, tryby)
        for (BypassCard card : cards) {
            SavedCardState saved = PERSISTENT_CARD_STATES.get(card.id);
            if (saved != null) {
                card.enabled = saved.enabled;
                card.currentVal = saved.value;
                card.renderedVal = saved.value;
                if (card.modes != null && card.modes.length > 0) {
                    card.modeIndex = Math.min(saved.modeIndex, card.modes.length - 1);
                }
                card.toggleAnim = card.enabled ? 1.0f : 0.0f;
            }
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Celowo puste: zapobiega podwójnemu nakładaniu domyślnego rozmycia 1.21.4,
        // nasze okno korzysta z autorskiego szkła Obsidian SDF.
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        long now = System.nanoTime();
        float dt = Math.min(0.05f, (now - lastFrameTimeNs) / 1_000_000_000.0f);
        lastFrameTimeNs = now;

        openProgress = Mth.clamp(openProgress + dt * 5.5f, 0.0f, 1.0f);
        float ease = 1.0f - (float) Math.pow(1.0f - openProgress, 3.0);
        statusPulsePhase = (statusPulsePhase + dt * 3.2f) % ((float) Math.PI * 2.0f);

        searchFocusAnim = Mth.lerp(dt * 12.0f, searchFocusAnim, searchFocused ? 1.0f : 0.0f);
        scrollCurrent = Mth.lerp(dt * 14.0f, scrollCurrent, scrollTarget);

        float winX = (this.width - WIN_WIDTH) * 0.5f;
        float winY = (this.height - WIN_HEIGHT) * 0.5f + (1.0f - ease) * 14.0f;

        // 1. Głęboki, kierunkowy Drop-Shadow 0x90000000 (blur = 16px, offsetY = +8px) BEZ efektu Glow-Box
        VenturaSdfRenderer.drawDropShadow(
                winX, winY, WIN_WIDTH, WIN_HEIGHT,
                WIN_RADIUS, 16.0f, 8.0f, COL_WINDOW_SHADOW
        );

        // 2. Główna tafla okna z wąską 1-pikselową ramką 0x28A78BFA
        VenturaSdfRenderer.drawRoundedRect(
                winX, winY, WIN_WIDTH, WIN_HEIGHT,
                WIN_RADIUS, COL_BG_MAIN, COL_BORDER_WINDOW, 1.0f
        );

        // 2. Panel boczny (Sidebar)
        VenturaSdfRenderer.drawRoundedRect4(
                winX, winY, SIDEBAR_WIDTH, WIN_HEIGHT,
                WIN_RADIUS, 0.0f, 0.0f, WIN_RADIUS,
                COL_BG_SIDEBAR, 0x00000000, 0.0f
        );
        // Pionowa linia podziału Sidebar | Content
        VenturaSdfRenderer.drawRoundedRect(winX + SIDEBAR_WIDTH, winY, 1.0f, WIN_HEIGHT, 0.0f, COL_DIVIDER, 0, 0.0f);

        // 3. Górny pasek (Topbar)
        float contentX = winX + SIDEBAR_WIDTH + 1.0f;
        float contentW = WIN_WIDTH - SIDEBAR_WIDTH - 1.0f;
        VenturaSdfRenderer.drawRoundedRect4(
                contentX, winY, contentW, TOPBAR_HEIGHT,
                0.0f, WIN_RADIUS, 0.0f, 0.0f,
                COL_BG_TOPBAR, 0x00000000, 0.0f
        );
        VenturaSdfRenderer.drawRoundedRect(contentX, winY + TOPBAR_HEIGHT, contentW, 1.0f, 0.0f, COL_DIVIDER, 0, 0.0f);

        // 4. Renderowanie zawartości panelu bocznego
        renderSidebar(g, winX, winY, mouseX, mouseY, dt);

        // 5. Renderowanie górnego paska (Wyszukiwarka + Telemetria + Przełącznik do GUI Litematica)
        renderTopbar(g, contentX, winY, contentW, mouseX, mouseY);

        // 6. Obszar kart ustawień z przycinaniem Scissor i paskiem przewijania SDF
        float viewX = contentX;
        float viewY = winY + TOPBAR_HEIGHT + 1.0f;
        float viewW = contentW;
        float viewH = WIN_HEIGHT - TOPBAR_HEIGHT - 1.0f;

        g.flush();
        VenturaSdfRenderer.enableScissor((int) viewX, (int) viewY, (int) viewW, (int) viewH);
        renderCardsViewport(g, viewX, viewY, viewW, viewH, mouseX, mouseY, dt);
        g.flush();
        VenturaSdfRenderer.disableScissor();

        // 7. Płynny pasek przewijania SDF z podświetleniem pozycji
        renderSdfScrollbar(viewX + viewW - 8.5f, viewY + 8.0f, viewH - 16.0f, viewH, mouseX, mouseY, dt);
    }

    private void renderSidebar(GuiGraphics g, float winX, float winY, int mouseX, int mouseY, float dt) {
        // Kropki macOS Ventura (Zamknij, Minimalizuj, Przełącz na Litematica)
        float dotY = winY + 15.0f;
        VenturaSdfRenderer.drawCircle(winX + 16.0f, dotY, 4.5f, COL_STATUS_CRIMSON, 0);
        VenturaSdfRenderer.drawCircle(winX + 30.0f, dotY, 4.5f, COL_STATUS_AMBER, 0);
        VenturaSdfRenderer.drawCircle(winX + 44.0f, dotY, 4.5f, COL_STATUS_GREEN, 0);

        // Tytuł marki
        g.drawString(this.font, "ASTRA", (int) (winX + 14), (int) (winY + 32), COL_TEXT_WHITE, false);
        g.drawString(this.font, "BARITONE 1.21.4", (int) (winX + 52), (int) (winY + 32), COL_TEXT_LILAC, false);

        float curY = winY + 56.0f;
        String lastSection = "";

        for (Category cat : Category.values()) {
            if (!cat.section.equals(lastSection)) {
                lastSection = cat.section;
                g.drawString(this.font, lastSection.toUpperCase(Locale.ROOT), (int) (winX + 14), (int) curY, COL_TEXT_SECTION, false);
                curY += 14.0f;
            }

            float tabX = winX + 8.0f;
            float tabW = SIDEBAR_WIDTH - 16.0f;
            float tabH = 26.0f;
            boolean hovered = isHovered(mouseX, mouseY, tabX, curY, tabW, tabH);
            boolean active = (cat == activeCategory && searchQuery.isEmpty());

            if (active) {
                VenturaSdfRenderer.drawRoundedRect(tabX, curY, tabW, tabH, 6.5f, COL_BG_SIDEBAR_ACT, COL_BORDER_CARD_ACT, 1.0f);
                // Lewy akcentujący pasek pigułkowy
                VenturaSdfRenderer.drawRoundedRect(tabX + 2.5f, curY + 5.5f, 3.0f, tabH - 11.0f, 1.5f, COL_ACCENT_LILAC, 0, 0.0f);
            } else if (hovered) {
                VenturaSdfRenderer.drawRoundedRect(tabX, curY, tabW, tabH, 6.5f, 0x16FFFFFF, 0, 0.0f);
            }

            int iconCol = active ? COL_ACCENT_LILAC : (hovered ? COL_TEXT_WHITE : COL_TEXT_GRAY);
            int textCol = active ? COL_TEXT_WHITE : (hovered ? COL_TEXT_WHITE : COL_TEXT_GRAY);

            VenturaSdfRenderer.drawIcon(cat.icon, tabX + 16.0f, curY + tabH * 0.5f, 11.0f, iconCol);
            g.drawString(this.font, cat.label, (int) (tabX + 28.0f), (int) (curY + 9.0f), textCol, false);

            curY += 30.0f;
        }

        // Dolny przycisk bezpośredniego przejścia do GUI Litematica & Bridge (AstraSchematicScreen)
        float bridgeBtnX = winX + 8.0f;
        float bridgeBtnY = winY + WIN_HEIGHT - 68.0f;
        float bridgeBtnW = SIDEBAR_WIDTH - 16.0f;
        float bridgeBtnH = 28.0f;
        boolean bridgeHover = isHovered(mouseX, mouseY, bridgeBtnX, bridgeBtnY, bridgeBtnW, bridgeBtnH);

        VenturaSdfRenderer.drawRoundedRect(
                bridgeBtnX, bridgeBtnY, bridgeBtnW, bridgeBtnH, 7.0f,
                bridgeHover ? COL_ACCENT_TRANS : COL_BG_SIDEBAR_ACT,
                COL_BORDER_CARD_ACT, 1.0f
        );
        VenturaSdfRenderer.drawIcon(VectorIcon.LAYERS, bridgeBtnX + 15.0f, bridgeBtnY + 14.0f, 11.0f, COL_ACCENT_LILAC);
        g.drawString(this.font, "Litematica & Bridge", (int) (bridgeBtnX + 27.0f), (int) (bridgeBtnY + 10.0f), COL_TEXT_WHITE, false);

        // Karta statusu GrimAC na samym dole Sidebara
        float statY = winY + WIN_HEIGHT - 34.0f;
        VenturaSdfRenderer.drawRoundedRect(bridgeBtnX, statY, bridgeBtnW, 26.0f, 6.5f, COL_BG_FIELD, COL_BORDER_FIELD, 1.0f);
        float pulseRadius = 3.5f + 0.8f * Mth.sin(statusPulsePhase);
        VenturaSdfRenderer.drawCircle(bridgeBtnX + 12.0f, statY + 13.0f, pulseRadius, COL_STATUS_GREEN, COL_ACCENT_GLOW);
        g.drawString(this.font, "Grim 2.3.65: SAFE", (int) (bridgeBtnX + 22.0f), (int) (statY + 9.0f), COL_TEXT_LILAC, false);
    }

    private void renderTopbar(GuiGraphics g, float contentX, float winY, float contentW, int mouseX, int mouseY) {
        // Pole wyszukiwania z płynnym podświetleniem fokusu i licznikiem wyników
        float searchX = contentX + 14.0f;
        float searchY = winY + 8.0f;
        float searchW = 225.0f;
        float searchH = 26.0f;

        int searchBorder = VenturaSdfRenderer.lerpColor(COL_BORDER_FIELD, COL_ACCENT_PURPLE, searchFocusAnim);
        VenturaSdfRenderer.drawRoundedRect(searchX, searchY, searchW, searchH, 7.0f, COL_BG_FIELD, searchBorder, 1.0f);
        VenturaSdfRenderer.drawIcon(VectorIcon.SEARCH, searchX + 13.0f, searchY + 13.0f, 10.0f,
                searchFocused ? COL_ACCENT_LILAC : COL_TEXT_GRAY);

        String displaySearch = searchQuery.isEmpty() && !searchFocused
                ? "Search modules, tags, Grim..."
                : searchQuery + (searchFocused && (System.currentTimeMillis() / 500) % 2 == 0 ? "|" : "");
        int searchTextCol = searchQuery.isEmpty() && !searchFocused ? COL_TEXT_GRAY : COL_TEXT_WHITE;
        g.drawString(this.font, displaySearch, (int) (searchX + 25.0f), (int) (searchY + 9.0f), searchTextCol, false);

        // Pigułka liczby znalezionych kart podczas wyszukiwania
        if (!searchQuery.isEmpty()) {
            int matchCount = getFilteredCards().size();
            String badge = matchCount + " found";
            int bw = this.font.width(badge) + 10;
            VenturaSdfRenderer.drawRoundedRect(searchX + searchW - bw - 5.0f, searchY + 5.0f, bw, 16.0f, 4.5f,
                    matchCount > 0 ? COL_BG_SIDEBAR_ACT : 0x33F43F5E, COL_BORDER_FIELD, 1.0f);
            g.drawString(this.font, badge, (int) (searchX + searchW - bw), (int) (searchY + 9.0f),
                    matchCount > 0 ? COL_TEXT_LILAC : COL_STATUS_CRIMSON, false);
        }

        // Pigułki szybkich presetów w Topbarze
        float pillX = searchX + searchW + 12.0f;
        String[] quickPresets = {"Grim Strict", "Matrix", "Legit"};
        for (String preset : quickPresets) {
            int pw = this.font.width(preset) + 14;
            boolean active = activePresetName.startsWith(preset);
            boolean hov = isHovered(mouseX, mouseY, pillX, searchY + 2.0f, pw, 22.0f);
            VenturaSdfRenderer.drawRoundedRect(
                    pillX, searchY + 2.0f, pw, 22.0f, 6.0f,
                    active ? COL_BG_SIDEBAR_ACT : (hov ? COL_BG_CARD_HOVER : COL_BG_FIELD),
                    active ? COL_BORDER_CARD_ACT : COL_BORDER_FIELD, 1.0f
            );
            g.drawString(this.font, preset, (int) (pillX + 7.0f), (int) (searchY + 9.0f),
                    active ? COL_TEXT_LILAC : COL_TEXT_GRAY, false);
            pillX += pw + 6.0f;
        }

        // Prawa pigułka telemetrii pakietów
        String pktText = "19.8 TPS | 0 Flags";
        int pktW = this.font.width(pktText) + 16;
        float pktX = contentX + contentW - pktW - 14.0f;
        VenturaSdfRenderer.drawRoundedRect(pktX, searchY + 2.0f, pktW, 22.0f, 6.0f, COL_BG_FIELD, COL_BORDER_FIELD, 1.0f);
        g.drawString(this.font, pktText, (int) (pktX + 8.0f), (int) (searchY + 9.0f), COL_STATUS_GREEN, false);
    }

    private List<BypassCard> getFilteredCards() {
        List<BypassCard> result = new ArrayList<>();
        String q = searchQuery.trim().toLowerCase(Locale.ROOT);
        for (BypassCard c : cards) {
            if (q.isEmpty()) {
                if (c.category == activeCategory) {
                    result.add(c);
                }
            } else {
                if (c.title.toLowerCase(Locale.ROOT).contains(q)
                        || c.description.toLowerCase(Locale.ROOT).contains(q)
                        || c.securityTag.toLowerCase(Locale.ROOT).contains(q)) {
                    result.add(c);
                }
            }
        }
        return result;
    }

    private void renderCardsViewport(GuiGraphics g, float viewX, float viewY, float viewW, float viewH,
                                     int mouseX, int mouseY, float dt) {
        List<BypassCard> visible = getFilteredCards();
        float padX = 14.0f;
        float curY = viewY + 14.0f - scrollCurrent;
        float cardW = viewW - padX * 2.0f - 8.0f;

        // Nagłówek aktualnego widoku
        String headerTitle = searchQuery.isEmpty()
                ? activeCategory.label
                : "Search Results for \"" + searchQuery + "\"";
        g.drawString(this.font, headerTitle, (int) (viewX + padX), (int) curY, COL_TEXT_WHITE, false);
        curY += 18.0f;

        boolean hasSearchHighlight = !searchQuery.trim().isEmpty();

        for (BypassCard card : visible) {
            float cardH = card.hasSlider || card.modes.length > 0 ? 78.0f : 52.0f;
            boolean hovered = isHovered(mouseX, mouseY, viewX + padX, curY, cardW, cardH)
                    && mouseY >= viewY && mouseY <= viewY + viewH;

            card.hoverAnim = Mth.lerp(dt * 12.0f, card.hoverAnim, hovered ? 1.0f : 0.0f);
            card.toggleAnim = Mth.lerp(dt * 14.0f, card.toggleAnim, card.enabled ? 1.0f : 0.0f);
            card.searchHighlightAnim = Mth.lerp(dt * 10.0f, card.searchHighlightAnim, hasSearchHighlight ? 1.0f : 0.0f);
            card.clickPulseAnim = Math.max(0.0f, card.clickPulseAnim - dt * 3.5f);
            card.renderedVal = Mth.lerp(dt * 16.0f, card.renderedVal, card.currentVal);

            // Aktualizacja wartości suwaka podczas przeciągania
            if (activeSliderCard == card && card.hasSlider) {
                float sliderX = viewX + padX + 12.0f;
                float sliderW = 210.0f;
                float pct = Mth.clamp((mouseX - sliderX) / sliderW, 0.0f, 1.0f);
                card.currentVal = card.minVal + pct * (card.maxVal - card.minVal);
                syncCardToSettings(card);
            }

            // Tło i obramowanie karty (z efektem Search Highlight i mikro-feedbackiem kliknięcia)
            int bgCol = VenturaSdfRenderer.lerpColor(COL_BG_CARD, COL_BG_CARD_HOVER, card.hoverAnim);
            int baseBorder = card.enabled ? COL_BORDER_CARD_ACT : COL_BORDER_CARD;
            int borderCol = VenturaSdfRenderer.lerpColor(baseBorder, COL_ACCENT_LILAC, card.searchHighlightAnim * 0.65f);

            // Subtelny cień pod kartą dodający głębi bez efektu Glow-Box
            VenturaSdfRenderer.drawDropShadow(viewX + padX, curY, cardW, cardH, CARD_RADIUS, 6.0f, 2.5f, 0x48000000);

            if (card.searchHighlightAnim > 0.05f || card.clickPulseAnim > 0.01f) {
                VenturaSdfRenderer.drawRoundedRect(
                        viewX + padX - 2.0f, curY - 2.0f, cardW + 4.0f, cardH + 4.0f,
                        CARD_RADIUS + 2.0f, 0x00000000, COL_ACCENT_GLOW, 4.5f
                );
            }

            VenturaSdfRenderer.drawRoundedRect(viewX + padX, curY, cardW, cardH, CARD_RADIUS, bgCol, borderCol, 1.0f);

            // Tytuł karty + Tag bezpieczeństwa
            g.drawString(this.font, card.title, (int) (viewX + padX + 12.0f), (int) (curY + 10.0f), COL_TEXT_WHITE, false);

            int titleW = this.font.width(card.title);
            float tagX = viewX + padX + 18.0f + titleW;
            int tagW = this.font.width(card.securityTag) + 12;
            VenturaSdfRenderer.drawRoundedRect(tagX, curY + 8.0f, tagW, 13.0f, 4.0f, COL_BG_FIELD, COL_BORDER_FIELD, 1.0f);
            g.drawString(this.font, card.securityTag, (int) (tagX + 6.0f), (int) (curY + 10.5f), card.tagStatusColor, false);

            // Opis karty
            g.drawString(this.font, card.description, (int) (viewX + padX + 12.0f), (int) (curY + 25.0f), COL_TEXT_GRAY, false);

            // Przełącznik iOS-style w prawym górnym rogu karty
            float togX = viewX + padX + cardW - 42.0f;
            float togY = curY + 11.0f;
            VenturaSdfRenderer.drawToggle(togX, togY, card.toggleAnim, isHovered(mouseX, mouseY, togX, togY, 30.0f, 16.0f));

            // Dolny rząd karty: Suwak SDF + Selektor trybów
            if (card.hasSlider || card.modes.length > 0) {
                float rowY = curY + 46.0f;

                if (card.hasSlider) {
                    float sliderX = viewX + padX + 12.0f;
                    float sliderW = 210.0f;
                    String valStr = String.format(Locale.ROOT, "%.1f%s", card.currentVal, card.sliderUnit);
                    g.drawString(this.font, card.sliderLabel + ": ", (int) sliderX, (int) rowY, COL_TEXT_GRAY, false);
                    g.drawString(this.font, valStr, (int) (sliderX + this.font.width(card.sliderLabel + ": ")), (int) rowY, COL_TEXT_LILAC, false);

                    float barY = rowY + 13.0f;
                    float pct = (card.renderedVal - card.minVal) / Math.max(0.0001f, card.maxVal - card.minVal);
                    VenturaSdfRenderer.drawRoundedRect(sliderX, barY, sliderW, 5.0f, 2.5f, COL_BG_FIELD, COL_BORDER_FIELD, 1.0f);
                    VenturaSdfRenderer.drawRoundedRect(sliderX, barY, Math.max(5.0f, sliderW * pct), 5.0f, 2.5f, COL_ACCENT_PURPLE, 0, 0.0f);
                    VenturaSdfRenderer.drawCircle(sliderX + sliderW * pct, barY + 2.5f, 4.5f, COL_ACCENT_LILAC, COL_ACCENT_GLOW);
                }

                if (card.modes.length > 0) {
                    String modeText = "Mode: " + card.modes[card.modeIndex];
                    int mw = this.font.width(modeText) + 16;
                    float modeX = viewX + padX + cardW - mw - 12.0f;
                    boolean modeHov = isHovered(mouseX, mouseY, modeX, rowY + 4.0f, mw, 18.0f);
                    VenturaSdfRenderer.drawRoundedRect(
                            modeX, rowY + 4.0f, mw, 18.0f, 5.0f,
                            modeHov ? COL_BG_SIDEBAR_ACT : COL_BG_FIELD,
                            modeHov ? COL_BORDER_CARD_ACT : COL_BORDER_FIELD, 1.0f
                    );
                    g.drawString(this.font, modeText, (int) (modeX + 8.0f), (int) (rowY + 9.0f), COL_TEXT_TAG, false);
                }
            }

            curY += cardH + 9.0f;
        }

        float totalContentH = (curY + scrollCurrent) - viewY + 12.0f;
        maxScroll = Math.max(0.0f, totalContentH - viewH);
        scrollTarget = Mth.clamp(scrollTarget, 0.0f, maxScroll);
    }

    private void renderSdfScrollbar(float trackX, float trackY, float trackH, float viewH,
                                    int mouseX, int mouseY, float dt) {
        if (maxScroll <= 1.0f) return;

        boolean hovered = isHovered(mouseX, mouseY, trackX - 4.0f, trackY, 12.0f, trackH) || draggingScrollbar;
        scrollbarHoverAnim = Mth.lerp(dt * 12.0f, scrollbarHoverAnim, hovered ? 1.0f : 0.0f);

        float barW = Mth.lerp(scrollbarHoverAnim, 3.2f, 5.2f);
        float drawX = trackX + (5.2f - barW) * 0.5f;

        // Tło szyny paska przewijania
        VenturaSdfRenderer.drawRoundedRect(drawX, trackY, barW, trackH, barW * 0.5f, COL_BG_FIELD, 0, 0.0f);

        // Obliczenie proporcjonalnego suwaka (Thumb)
        float totalH = viewH + maxScroll;
        float thumbH = Math.max(26.0f, trackH * (viewH / totalH));
        float scrollRatio = Mth.clamp(scrollCurrent / maxScroll, 0.0f, 1.0f);
        float thumbY = trackY + (trackH - thumbH) * scrollRatio;

        int thumbCol = VenturaSdfRenderer.lerpColor(COL_ACCENT_PURPLE, COL_ACCENT_LILAC, scrollbarHoverAnim);
        if (scrollbarHoverAnim > 0.05f) {
            VenturaSdfRenderer.drawRoundedRect(drawX - 1.5f, thumbY - 1.5f, barW + 3.0f, thumbH + 3.0f,
                    (barW + 3.0f) * 0.5f, 0, COL_ACCENT_GLOW, 3.0f);
        }
        VenturaSdfRenderer.drawRoundedRect(drawX, thumbY, barW, thumbH, barW * 0.5f, thumbCol, 0, 0.0f);
    }

    // =========================================================================
    // OBSŁUGA WEJŚCIA MYSZY I KLAWIATURY
    // =========================================================================
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        float winX = (this.width - WIN_WIDTH) * 0.5f;
        float winY = (this.height - WIN_HEIGHT) * 0.5f;

        // Kropki macOS: Czerwona zamyka, Zielona przełącza na AstraSchematicScreen
        if (isHovered(mouseX, mouseY, winX + 10.0f, winY + 9.0f, 12.0f, 12.0f)) {
            this.onClose();
            return true;
        }
        if (isHovered(mouseX, mouseY, winX + 38.0f, winY + 9.0f, 12.0f, 12.0f)) {
            Minecraft.getInstance().setScreen(new AstraSchematicScreen(this));
            return true;
        }

        // Przycisk "Litematica & Bridge" w Sidebarze
        float bridgeBtnX = winX + 8.0f;
        float bridgeBtnY = winY + WIN_HEIGHT - 68.0f;
        if (isHovered(mouseX, mouseY, bridgeBtnX, bridgeBtnY, SIDEBAR_WIDTH - 16.0f, 28.0f)) {
            Minecraft.getInstance().setScreen(new AstraSchematicScreen(this));
            return true;
        }

        // Kliknięcie zakładek Sidebara
        float curY = winY + 56.0f;
        String lastSection = "";
        for (Category cat : Category.values()) {
            if (!cat.section.equals(lastSection)) {
                lastSection = cat.section;
                curY += 14.0f;
            }
            if (isHovered(mouseX, mouseY, winX + 8.0f, curY, SIDEBAR_WIDTH - 16.0f, 26.0f)) {
                activeCategory = cat;
                lastActiveCategory = cat;
                searchQuery = "";
                scrollTarget = 0.0f;
                return true;
            }
            curY += 30.0f;
        }

        float contentX = winX + SIDEBAR_WIDTH + 1.0f;
        float contentW = WIN_WIDTH - SIDEBAR_WIDTH - 1.0f;

        // Fokus pola wyszukiwania
        searchFocused = isHovered(mouseX, mouseY, contentX + 14.0f, winY + 8.0f, 225.0f, 26.0f);

        // Szybkie presety w Topbarze
        float pillX = contentX + 14.0f + 225.0f + 12.0f;
        String[] quickPresets = {"Grim Strict", "Matrix", "Legit"};
        for (String preset : quickPresets) {
            int pw = this.font.width(preset) + 14;
            if (isHovered(mouseX, mouseY, pillX, winY + 10.0f, pw, 22.0f)) {
                activePresetName = preset;
                return true;
            }
            pillX += pw + 6.0f;
        }

        // Złapanie paska przewijania SDF
        float viewY = winY + TOPBAR_HEIGHT + 1.0f;
        float viewH = WIN_HEIGHT - TOPBAR_HEIGHT - 1.0f;
        float trackX = contentX + contentW - 8.5f;
        if (maxScroll > 1.0f && isHovered(mouseX, mouseY, trackX - 4.0f, viewY + 8.0f, 12.0f, viewH - 16.0f)) {
            draggingScrollbar = true;
            scrollbarDragStartY = (float) mouseY;
            scrollbarStartTarget = scrollTarget;
            return true;
        }

        // Interakcja z kartami wewnątrz Viewportu
        if (isHovered(mouseX, mouseY, contentX, viewY, contentW - 12.0f, viewH)) {
            float padX = 14.0f;
            float cardY = viewY + 32.0f - scrollCurrent;
            float cardW = contentW - padX * 2.0f - 8.0f;

            for (BypassCard card : getFilteredCards()) {
                float cardH = card.hasSlider || card.modes.length > 0 ? 78.0f : 52.0f;
                float togX = contentX + padX + cardW - 42.0f;
                float togY = cardY + 11.0f;

                if (isHovered(mouseX, mouseY, togX - 4.0f, togY - 4.0f, 38.0f, 24.0f)) {
                    card.enabled = !card.enabled;
                    card.clickPulseAnim = 1.0f;
                    syncCardToSettings(card);
                    return true;
                }

                if (card.hasSlider) {
                    float sliderX = contentX + padX + 12.0f;
                    float barY = cardY + 59.0f;
                    if (isHovered(mouseX, mouseY, sliderX - 4.0f, barY - 6.0f, 218.0f, 16.0f)) {
                        activeSliderCard = card;
                        float pct = Mth.clamp(((float) mouseX - sliderX) / 210.0f, 0.0f, 1.0f);
                        card.currentVal = card.minVal + pct * (card.maxVal - card.minVal);
                        syncCardToSettings(card);
                        return true;
                    }
                }

                if (card.modes.length > 0) {
                    String modeText = "Mode: " + card.modes[card.modeIndex];
                    int mw = this.font.width(modeText) + 16;
                    float modeX = contentX + padX + cardW - mw - 12.0f;
                    if (isHovered(mouseX, mouseY, modeX, cardY + 50.0f, mw, 18.0f)) {
                        card.modeIndex = (card.modeIndex + 1) % card.modes.length;
                        card.clickPulseAnim = 0.7f;
                        syncCardToSettings(card);
                        return true;
                    }
                }
                cardY += cardH + 9.0f;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void syncCardToSettings(BypassCard card) {
        if (card != null) {
            PERSISTENT_CARD_STATES.put(card.id, new SavedCardState(card.enabled, card.currentVal, card.modeIndex));
        }
        try {
            var s = BaritoneAPI.getSettings();
            switch (card.id) {
                case "client_block_overlay":
                    s.clientBlockOverlay.value = card.enabled;
                    s.blockOverlaySpeed.value = card.currentVal;
                    if (card.modeIndex == 0) {
                        s.blockOverlaySmooth.value = true;
                        s.blockOverlayIgnoreDepth.value = false;
                    } else if (card.modeIndex == 1) {
                        s.blockOverlaySmooth.value = false;
                        s.blockOverlayIgnoreDepth.value = false;
                    } else if (card.modeIndex == 2) {
                        s.blockOverlaySmooth.value = true;
                        s.blockOverlayIgnoreDepth.value = true;
                    }
                    break;
                case "radial_ground_wave":
                    s.radialWave.value = card.enabled;
                    s.radialWaveRadius.value = card.currentVal;
                    s.radialWaveTrigger.value = card.modeIndex;
                    break;
                case "radial_wave_speed":
                    s.radialWaveDouble.value = card.enabled;
                    s.radialWaveSpeed.value = card.currentVal;
                    s.radialWaveMode.value = card.modeIndex;
                    break;
                case "glass_fill_opacity":
                    s.blockOverlayWave.value = card.enabled;
                    s.blockOverlayAlpha.value = card.currentVal / 100.0f;
                    break;
                case "mining_target_wave":
                    s.renderMiningWaveOverlay.value = card.enabled;
                    s.blockOverlayLineWidth.value = card.currentVal;
                    break;
                case "sdf_path_bezier":
                    s.renderSelectionBoxes.value = card.enabled;
                    s.pathRenderLineWidthPixels.value = card.currentVal;
                    break;
                case "goal_hologram":
                    s.renderFilledBoxes.value = card.enabled;
                    s.filledBoxAlpha.value = card.currentVal / 100.0f;
                    break;
                case "china_hat_cosmetic":
                    s.chinaHat.value = card.enabled;
                    s.chinaHatRadius.value = card.currentVal;
                    if (card.modeIndex == 0) {
                        s.chinaHatRainbow.value = false;
                        s.chinaHatFirstPerson.value = false;
                    } else if (card.modeIndex == 1) {
                        s.chinaHatRainbow.value = true;
                        s.chinaHatFirstPerson.value = false;
                    } else if (card.modeIndex == 2) {
                        s.chinaHatFirstPerson.value = true;
                    }
                    AstraVisualsEngine.setChinaHatEnabled(card.enabled);
                    break;
                case "hand_shaders":
                    s.handShaders.value = card.enabled;
                    s.handShaderMode.value = card.modeIndex;
                    AstraVisualsEngine.setHandShadersEnabled(card.enabled);
                    com.astra.revolution.visuals.AstraHandShader.setGlassAlpha(card.currentVal / 100.0f);
                    com.astra.revolution.visuals.AstraHandShader.setModeIndex(card.modeIndex);
                    break;
                case "custom_player_disguise":
                    if (!card.enabled) {
                        s.customPlayerDisguise.value = "OFF";
                        AstraVisualsEngine.setActiveDisguise(AstraVisualsEngine.DisguiseModel.OFF);
                    } else {
                        switch (card.modeIndex) {
                            case 0 -> {
                                s.customPlayerDisguise.value = "TUNG_TUNG_SAHUR";
                                AstraVisualsEngine.setActiveDisguise(AstraVisualsEngine.DisguiseModel.TUNG_TUNG_SAHUR);
                            }
                            case 1 -> {
                                s.customPlayerDisguise.value = "BALLERINA_CAPUCCINA";
                                AstraVisualsEngine.setActiveDisguise(AstraVisualsEngine.DisguiseModel.BALLERINA_CAPUCCINA);
                            }
                            case 2 -> {
                                s.customPlayerDisguise.value = "CYBER_MECH";
                                AstraVisualsEngine.setActiveDisguise(AstraVisualsEngine.DisguiseModel.CYBER_MECH);
                            }
                            case 3 -> {
                                s.customPlayerDisguise.value = "HOLOGRAM_AVATAR";
                                AstraVisualsEngine.setActiveDisguise(AstraVisualsEngine.DisguiseModel.HOLOGRAM_AVATAR);
                            }
                            case 4 -> {
                                s.customPlayerDisguise.value = "CAPYBARA_KING";
                                AstraVisualsEngine.setActiveDisguise(AstraVisualsEngine.DisguiseModel.CAPYBARA_KING);
                            }
                            default -> {
                                s.customPlayerDisguise.value = "OFF";
                                AstraVisualsEngine.setActiveDisguise(AstraVisualsEngine.DisguiseModel.OFF);
                            }
                        }
                    }
                    break;
                case "motion_trail":
                    s.motionTrail.value = card.enabled;
                    s.motionTrailMode.value = card.modeIndex;
                    AstraVisualsEngine.setMotionTrailEnabled(card.enabled);
                    AstraVisualsEngine.setMotionTrailMode(card.modeIndex);
                    break;
                case "target_esp":
                    s.targetEsp.value = card.enabled;
                    s.targetEspMode.value = card.modeIndex;
                    AstraVisualsEngine.setTargetEspEnabled(card.enabled);
                    AstraVisualsEngine.setTargetEspMode(card.modeIndex);
                    break;
                case "jump_circles":
                    s.jumpCircles.value = card.enabled;
                    s.jumpCirclesMode.value = card.modeIndex;
                    AstraVisualsEngine.setJumpCirclesEnabled(card.enabled);
                    AstraVisualsEngine.setJumpCirclesMode(card.modeIndex);
                    break;
                case "custom_fog":
                    s.customFog.value = card.enabled;
                    AstraVisualsEngine.setCustomFogEnabled(card.enabled);
                    AstraVisualsEngine.setCustomFogMode(card.modeIndex);
                    break;
                case "custom_sky":
                    s.customSky.value = card.enabled;
                    AstraVisualsEngine.setCustomSkyEnabled(card.enabled);
                    AstraVisualsEngine.setCustomSkyMode(card.modeIndex);
                    break;
                case "ambient_particles":
                    s.ambientParticles.value = card.enabled;
                    AstraVisualsEngine.setAmbientParticlesEnabled(card.enabled);
                    com.astra.revolution.visuals.AstraAmbientParticles.setModeIndex(card.modeIndex);
                    break;
                case "motion_blur":
                    s.motionBlur.value = card.enabled;
                    s.motionBlurStrength.value = (double) card.currentVal;
                    s.motionBlurMode.value = card.modeIndex;
                    com.astra.revolution.visuals.AstraMotionBlurEngine.getInstance().setEnabled(card.enabled);
                    com.astra.revolution.visuals.AstraMotionBlurEngine.getInstance().setStrength(card.currentVal);
                    com.astra.revolution.visuals.AstraMotionBlurEngine.getInstance().setMode(card.modeIndex);
                    break;
            }
            if (s != null) {
                SettingsUtil.save(s);
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingScrollbar && maxScroll > 1.0f) {
            float trackH = (WIN_HEIGHT - TOPBAR_HEIGHT - 1.0f) - 16.0f;
            float deltaY = (float) mouseY - scrollbarDragStartY;
            scrollTarget = Mth.clamp(scrollbarStartTarget + (deltaY / trackH) * maxScroll, 0.0f, maxScroll);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        activeSliderCard = null;
        draggingScrollbar = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scrollTarget = Mth.clamp(scrollTarget - (float) scrollY * 28.0f, 0.0f, maxScroll);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (searchFocused) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_ENTER) {
                searchFocused = false;
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE && !searchQuery.isEmpty()) {
                searchQuery = searchQuery.substring(0, searchQuery.length() - 1);
                scrollTarget = 0.0f;
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (searchFocused && (Character.isLetterOrDigit(codePoint) || " _-.".indexOf(codePoint) >= 0)) {
            if (searchQuery.length() < 26) {
                searchQuery += codePoint;
                scrollTarget = 0.0f;
            }
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    public static boolean isHovered(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    // =========================================================================
    // SILNIK OPENGL 3.3 SDF: VENTURA SDF RENDERER (WSPÓŁDZIELONY)
    // =========================================================================
    public static final class VenturaSdfRenderer {
        public static final VenturaSdfRenderer INSTANCE = new VenturaSdfRenderer();

        private static int programId = -1;
        private static int vaoId = -1;
        private static int vboId = -1;

        private static int uScreenSize;
        private static int uRect;
        private static int uRadii;
        private static int uFillColor;
        private static int uBorderColor;
        private static int uParams; // x: borderThickness, y: feather/glow, z: progress/lineThickness, w: mode
        private static int uSegment; // x1, y1, x2, y2 dla linii wektorowych

        private static final FloatBuffer QUAD_BUF = BufferUtils.createFloatBuffer(12);

        private static final String VERTEX_SHADER = """
                #version 330 core
                layout(location = 0) in vec2 a_Pos;
                uniform vec2 u_ScreenSize;
                out vec2 v_FragCoord;
                void main() {
                    v_FragCoord = a_Pos;
                    vec2 ndc = (a_Pos / u_ScreenSize) * 2.0 - 1.0;
                    ndc.y = -ndc.y;
                    gl_Position = vec4(ndc, 0.0, 1.0);
                }
                """;

        private static final String FRAGMENT_SHADER = """
                #version 330 core
                in vec2 v_FragCoord;
                out vec4 FragColor;

                uniform vec4 u_Rect;        // x, y, w, h
                uniform vec4 u_Radii;       // top-right, bottom-right, top-left, bottom-left
                uniform vec4 u_FillColor;   // RGBA
                uniform vec4 u_BorderColor; // RGBA
                uniform vec4 u_Params;      // x: borderW, y: feather/blur, z: ringProgress, w: mode (0=Box, 1=Ring, 2=Line, 3=DropShadow)
                uniform vec4 u_Segment;     // x1, y1, x2, y2

                float sdRoundedBox4(vec2 p, vec2 b, vec4 r) {
                    r.xy = (p.x > 0.0) ? r.xy : r.zw;
                    r.x  = (p.y > 0.0) ? r.x  : r.y;
                    vec2 q = abs(p) - b + r.x;
                    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r.x;
                }

                float sdSegment(vec2 p, vec2 a, vec2 b) {
                    vec2 pa = p - a, ba = b - a;
                    float h = clamp(dot(pa, ba) / max(0.0001, dot(ba, ba)), 0.0, 1.0);
                    return length(pa - ba * h);
                }

                void main() {
                    int mode = int(u_Params.w + 0.5);
                    vec2 center = u_Rect.xy + u_Rect.zw * 0.5;
                    vec2 p = v_FragCoord - center;

                    if (mode == 0) {
                        vec2 halfSize = u_Rect.zw * 0.5;
                        float d = sdRoundedBox4(p, halfSize, u_Radii);
                        float aa = 0.75;
                        float fillAlpha = 1.0 - smoothstep(-aa, aa, d);

                        vec4 col = vec4(u_FillColor.rgb, u_FillColor.a * fillAlpha);

                        if (u_Params.x > 0.01) {
                            float borderMask = (1.0 - smoothstep(-aa, aa, d)) - (1.0 - smoothstep(-aa, aa, d + u_Params.x));
                            borderMask = clamp(borderMask, 0.0, 1.0);
                            col = mix(col, vec4(u_BorderColor.rgb, u_BorderColor.a), borderMask * u_BorderColor.a);
                            col.a = max(col.a, borderMask * u_BorderColor.a);
                        }

                        if (u_Params.y > 0.5 && d > -0.5) {
                            float glowDist = max(0.0, d);
                            float feather = u_Params.y;
                            // Płynne wygaszanie Hermite'a od 1.0 przy d=0 do DOKŁADNIE 0.0 przy d=feather
                            float t = clamp(1.0 - (glowDist / feather), 0.0, 1.0);
                            float glowAlpha = t * t * (3.0 - 2.0 * t) * u_BorderColor.a;
                            col = mix(col, vec4(u_BorderColor.rgb, u_BorderColor.a), glowAlpha * (1.0 - fillAlpha));
                        }
                        FragColor = col;
                    } else if (mode == 1) {
                        float radius = u_Rect.z * 0.5;
                        float thickness = u_Params.x;
                        float distFromCenter = length(p);
                        float ringDist = abs(distFromCenter - (radius - thickness * 0.5)) - thickness * 0.5;
                        float aa = 0.75;
                        float ringMask = 1.0 - smoothstep(-aa, aa, ringDist);

                        float angle = atan(p.x, -p.y);
                        if (angle < 0.0) angle += 6.2831853;
                        float normAngle = angle / 6.2831853;

                        float progMask = smoothstep(normAngle - 0.008, normAngle + 0.008, u_Params.z);
                        vec4 trackCol = vec4(u_FillColor.rgb, u_FillColor.a * ringMask);
                        vec4 arcCol = vec4(u_BorderColor.rgb, u_BorderColor.a * ringMask * progMask);

                        FragColor = mix(trackCol, arcCol, progMask);
                    } else if (mode == 2) {
                        float d = sdSegment(v_FragCoord, u_Segment.xy, u_Segment.zw) - u_Params.x * 0.5;
                        float alpha = 1.0 - smoothstep(-0.75, 0.75, d);
                        FragColor = vec4(u_FillColor.rgb, u_FillColor.a * alpha);
                    } else {
                        // Mode 3: Dedykowany Drop-Shadow ze skończonym nośnikiem Hermite'a (Zero Glow-Box)
                        vec2 halfSize = u_Rect.zw * 0.5;
                        float d = sdRoundedBox4(p, halfSize, u_Radii);
                        float blur = max(1.0, u_Params.y);
                        float shadowDist = max(0.0, d);
                        float t = clamp(1.0 - (shadowDist / blur), 0.0, 1.0);
                        float shadowAlpha = t * t * (3.0 - 2.0 * t) * u_BorderColor.a;
                        FragColor = vec4(u_BorderColor.rgb, shadowAlpha);
                    }
                }
                """;

        public static void ensureInitialized() {
            if (programId != -1) return;

            int vs = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
            GL20.glShaderSource(vs, VERTEX_SHADER);
            GL20.glCompileShader(vs);

            int fs = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
            GL20.glShaderSource(fs, FRAGMENT_SHADER);
            GL20.glCompileShader(fs);

            programId = GL20.glCreateProgram();
            GL20.glAttachShader(programId, vs);
            GL20.glAttachShader(programId, fs);
            GL20.glLinkProgram(programId);

            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);

            uScreenSize  = GL20.glGetUniformLocation(programId, "u_ScreenSize");
            uRect        = GL20.glGetUniformLocation(programId, "u_Rect");
            uRadii       = GL20.glGetUniformLocation(programId, "u_Radii");
            uFillColor   = GL20.glGetUniformLocation(programId, "u_FillColor");
            uBorderColor = GL20.glGetUniformLocation(programId, "u_BorderColor");
            uParams      = GL20.glGetUniformLocation(programId, "u_Params");
            uSegment     = GL20.glGetUniformLocation(programId, "u_Segment");

            vaoId = GL30.glGenVertexArrays();
            vboId = GL15.glGenBuffers();
            GL30.glBindVertexArray(vaoId);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, 12 * Float.BYTES, GL15.GL_DYNAMIC_DRAW);
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 2 * Float.BYTES, 0);
            GL30.glBindVertexArray(0);
        }

        public static void beginFrame(GuiGraphics guiGraphics) {
            ensureInitialized();
        }

        public static void endFrame() {}
        public static void flush() {}

        public static void drawDropShadow(float x, float y, float w, float h,
                                          float radius, float blur, float offsetY, int shadowCol) {
            if (((shadowCol >>> 24) & 0xFF) == 0 || blur <= 0.1f) return;

            float insetX = Math.min(2.5f, blur * 0.2f);
            float sx = x + insetX;
            float sy = y + offsetY;
            float sw = Math.max(1.0f, w - insetX * 2.0f);
            float sh = h;

            float qx = sx - blur;
            float qy = sy - blur;
            float qw = sw + blur * 2.0f;
            float qh = sh + blur * 2.0f;

            dispatchQuad(qx, qy, qw, qh,
                    sx, sy, sw, sh, radius, radius, radius, radius,
                    0x00000000, shadowCol, 0.0f, blur, 0.0f, 3.0f, 0, 0, 0, 0);
        }

        public void drawShadowedRect(float x, float y, float w, float h, float radius, float shadowSpread,
                                     int fillCol, int shadowCol, float borderThickness, int borderCol) {
            if (shadowSpread > 0.0f) {
                drawDropShadow(x, y, w, h, radius, shadowSpread, 3.0f, shadowCol);
            }
            drawRoundedRect(x, y, w, h, radius, fillCol, borderCol, borderThickness);
        }

        public void drawVectorIcon(VectorIcon icon, float cx, float cy, float radius, float stroke, int color) {
            drawIcon(icon, cx, cy, radius * 2.0f, color);
        }

        public static void drawRoundedRect(float x, float y, float w, float h, float radius,
                                           int fillCol, int borderCol, float borderW) {
            drawRoundedRect4(x, y, w, h, radius, radius, radius, radius, fillCol, borderCol, borderW);
        }

        public static void drawRoundedRect4(float x, float y, float w, float h,
                                            float rTL, float rTR, float rBR, float rBL,
                                            int fillCol, int borderCol, float borderW) {
            float feather = borderW > 2.5f ? borderW : 0.0f;
            float actualBorder = borderW > 2.5f ? 0.0f : borderW;
            float qx = x - feather;
            float qy = y - feather;
            float qw = w + feather * 2.0f;
            float qh = h + feather * 2.0f;

            dispatchQuad(qx, qy, qw, qh,
                    x, y, w, h, rTR, rBR, rTL, rBL,
                    fillCol, borderCol, actualBorder, feather, 0.0f, 0.0f, 0, 0, 0, 0);
        }

        public static void drawCircle(float cx, float cy, float radius, int color) {
            drawCircle(cx, cy, radius, color, 0);
        }

        public static void drawCircle(float cx, float cy, float radius, int fillCol, int glowCol) {
            float d = radius * 2.0f;
            drawRoundedRect(cx - radius, cy - radius, d, d, radius, fillCol, glowCol, glowCol != 0 ? 3.5f : 0.0f);
        }

        public static void drawProgressRing(float cx, float cy, float radius, float thickness,
                                            float progress, int trackCol, int arcCol) {
            float d = radius * 2.0f;
            dispatchQuad(cx - radius - 2.0f, cy - radius - 2.0f, d + 4.0f, d + 4.0f,
                    cx - radius, cy - radius, d, d,
                    0, 0, 0, 0,
                    trackCol, arcCol, thickness, 0.0f, Mth.clamp(progress, 0.0f, 1.0f), 1.0f,
                    0, 0, 0, 0);
        }

        public static void drawLine(float x1, float y1, float x2, float y2, float thickness, int color) {
            float minX = Math.min(x1, x2) - thickness - 2.0f;
            float minY = Math.min(y1, y2) - thickness - 2.0f;
            float maxX = Math.max(x1, x2) + thickness + 2.0f;
            float maxY = Math.max(y1, y2) + thickness + 2.0f;
            dispatchQuad(minX, minY, maxX - minX, maxY - minY,
                    minX, minY, maxX - minX, maxY - minY,
                    0, 0, 0, 0,
                    color, 0, thickness, 0.0f, 0.0f, 2.0f,
                    x1, y1, x2, y2);
        }

        public static void drawToggle(float x, float y, float anim, boolean hovered) {
            int bg = lerpColor(COL_TOGGLE_OFF_BG, COL_ACCENT_TRANS, anim);
            int border = lerpColor(COL_BORDER_FIELD, COL_ACCENT_LILAC, anim);
            drawRoundedRect(x, y, 30.0f, 16.0f, 8.0f, bg, border, 1.0f);
            float knobX = x + 8.0f + anim * 14.0f;
            int knobCol = lerpColor(COL_TOGGLE_OFF_KNOB, COL_TEXT_WHITE, anim);
            drawCircle(knobX, y + 8.0f, 5.2f, knobCol, anim > 0.5f || hovered ? COL_ACCENT_GLOW : 0);
        }

        public static void drawIcon(VectorIcon icon, float cx, float cy, float size, int col) {
            float r = size * 0.5f;
            float t = 1.45f;
            switch (icon) {
                case VENTURA_LOGO -> {
                    drawLine(cx - r * 0.75f, cy - r * 0.55f, cx, cy + r * 0.75f, t * 1.15f, col);
                    drawLine(cx + r * 0.75f, cy - r * 0.55f, cx, cy + r * 0.75f, t * 1.15f, col);
                    drawLine(cx - r * 0.45f, cy - r * 0.55f, cx + r * 0.45f, cy - r * 0.55f, t, col);
                }
                case SHIELD -> {
                    drawLine(cx, cy - r, cx + r * 0.85f, cy - r * 0.45f, t, col);
                    drawLine(cx + r * 0.85f, cy - r * 0.45f, cx + r * 0.6f, cy + r * 0.45f, t, col);
                    drawLine(cx + r * 0.6f, cy + r * 0.45f, cx, cy + r, t, col);
                    drawLine(cx, cy + r, cx - r * 0.6f, cy + r * 0.45f, t, col);
                    drawLine(cx - r * 0.6f, cy + r * 0.45f, cx - r * 0.85f, cy - r * 0.45f, t, col);
                    drawLine(cx - r * 0.85f, cy - r * 0.45f, cx, cy - r, t, col);
                }
                case LAYERS -> {
                    drawLine(cx, cy - r * 0.9f, cx + r, cy - r * 0.25f, t, col);
                    drawLine(cx + r, cy - r * 0.25f, cx, cy + r * 0.35f, t, col);
                    drawLine(cx, cy + r * 0.35f, cx - r, cy - r * 0.25f, t, col);
                    drawLine(cx - r, cy - r * 0.25f, cx, cy - r * 0.9f, t, col);
                    drawLine(cx - r * 0.9f, cy + r * 0.25f, cx, cy + r * 0.85f, t, col);
                    drawLine(cx, cy + r * 0.85f, cx + r * 0.9f, cy + r * 0.25f, t, col);
                }
                case COMPASS -> {
                    drawLine(cx, cy, cx, cy - r, t, col);
                    drawLine(cx, cy, cx + r * 0.86f, cy + r * 0.5f, t, col);
                    drawLine(cx, cy, cx - r * 0.86f, cy + r * 0.5f, t, col);
                    drawCircle(cx, cy, 1.8f, col, 0);
                }
                case BOX, SHULKER_BOX -> {
                    drawLine(cx, cy - r, cx + r * 0.86f, cy - r * 0.5f, t, col);
                    drawLine(cx + r * 0.86f, cy - r * 0.5f, cx, cy, t, col);
                    drawLine(cx, cy, cx - r * 0.86f, cy - r * 0.5f, t, col);
                    drawLine(cx - r * 0.86f, cy - r * 0.5f, cx, cy - r, t, col);
                    drawLine(cx - r * 0.86f, cy - r * 0.5f, cx - r * 0.86f, cy + r * 0.5f, t, col);
                    drawLine(cx + r * 0.86f, cy - r * 0.5f, cx + r * 0.86f, cy + r * 0.5f, t, col);
                    drawLine(cx, cy, cx, cy + r, t, col);
                    drawLine(cx - r * 0.86f, cy + r * 0.5f, cx, cy + r, t, col);
                    drawLine(cx + r * 0.86f, cy + r * 0.5f, cx, cy + r, t, col);
                }
                case BOLT -> {
                    drawLine(cx + r * 0.2f, cy - r, cx - r * 0.6f, cy + r * 0.1f, t, col);
                    drawLine(cx - r * 0.6f, cy + r * 0.1f, cx + r * 0.15f, cy + r * 0.1f, t, col);
                    drawLine(cx + r * 0.15f, cy + r * 0.1f, cx - r * 0.2f, cy + r, t, col);
                }
                case SEARCH -> {
                    drawProgressRing(cx - r * 0.15f, cy - r * 0.15f, r * 0.65f, 1.4f, 1.0f, col, col);
                    drawLine(cx + r * 0.3f, cy + r * 0.3f, cx + r * 0.9f, cy + r * 0.9f, t, col);
                }
                case MOVEMENT, RUNNER -> {
                    drawLine(cx - r, cy - r * 0.4f, cx + r * 0.5f, cy - r * 0.4f, t, col);
                    drawLine(cx - r * 0.6f, cy + r * 0.1f, cx + r, cy + r * 0.1f, t, col);
                    drawLine(cx - r * 0.8f, cy + r * 0.6f, cx + r * 0.3f, cy + r * 0.6f, t, col);
                }
                case MINING, PICKAXE -> {
                    drawLine(cx - r * 0.7f, cy + r * 0.7f, cx + r * 0.5f, cy - r * 0.5f, t, col);
                    drawLine(cx, cy - r * 0.8f, cx + r * 0.8f, cy, t, col);
                }
                case RENDER, EYE -> {
                    drawLine(cx - r, cy, cx, cy - r * 0.65f, t, col);
                    drawLine(cx, cy - r * 0.65f, cx + r, cy, t, col);
                    drawLine(cx + r, cy, cx, cy + r * 0.65f, t, col);
                    drawLine(cx, cy + r * 0.65f, cx - r, cy, t, col);
                    drawCircle(cx, cy, r * 0.3f, col, 0);
                }
                case SLIDERS, SLIDER_MINI -> {
                    drawLine(cx - r, cy - r * 0.5f, cx + r, cy - r * 0.5f, t, col);
                    drawCircle(cx - r * 0.3f, cy - r * 0.5f, 2.2f, col, 0);
                    drawLine(cx - r, cy + r * 0.5f, cx + r, cy + r * 0.5f, t, col);
                    drawCircle(cx + r * 0.4f, cy + r * 0.5f, 2.2f, col, 0);
                }
                case CHECK -> {
                    drawLine(cx - r * 0.75f, cy + r * 0.05f, cx - r * 0.15f, cy + r * 0.65f, t, col);
                    drawLine(cx - r * 0.15f, cy + r * 0.65f, cx + r * 0.85f, cy - r * 0.55f, t, col);
                }
                case REFRESH -> {
                    drawProgressRing(cx, cy, r * 0.8f, 1.4f, 0.78f, 0x00000000, col);
                    drawLine(cx + r * 0.4f, cy - r * 0.75f, cx + r * 0.85f, cy - r * 0.35f, t, col);
                }
                case PLAY -> {
                    drawLine(cx - r * 0.5f, cy - r * 0.75f, cx + r * 0.75f, cy, t, col);
                    drawLine(cx + r * 0.75f, cy, cx - r * 0.5f, cy + r * 0.75f, t, col);
                    drawLine(cx - r * 0.5f, cy + r * 0.75f, cx - r * 0.5f, cy - r * 0.75f, t, col);
                }
                case STOP -> {
                    drawRoundedRect(cx - r * 0.6f, cy - r * 0.6f, r * 1.2f, r * 1.2f, 2.0f, col, 0, 0.0f);
                }
                case ARROW_UP -> {
                    drawLine(cx, cy - r * 0.75f, cx, cy + r * 0.75f, t, col);
                    drawLine(cx - r * 0.55f, cy - r * 0.2f, cx, cy - r * 0.75f, t, col);
                    drawLine(cx + r * 0.55f, cy - r * 0.2f, cx, cy - r * 0.75f, t, col);
                }
                case ARROW_DOWN -> {
                    drawLine(cx, cy - r * 0.75f, cx, cy + r * 0.75f, t, col);
                    drawLine(cx - r * 0.55f, cy + r * 0.2f, cx, cy + r * 0.75f, t, col);
                    drawLine(cx + r * 0.55f, cy + r * 0.2f, cx, cy + r * 0.75f, t, col);
                }
                case ARROW_LEFT -> {
                    drawLine(cx - r * 0.75f, cy, cx + r * 0.75f, cy, t, col);
                    drawLine(cx - r * 0.2f, cy - r * 0.55f, cx - r * 0.75f, cy, t, col);
                    drawLine(cx - r * 0.2f, cy + r * 0.55f, cx - r * 0.75f, cy, t, col);
                }
                case ARROW_RIGHT, CHEVRON_RIGHT -> {
                    drawLine(cx - r * 0.75f, cy, cx + r * 0.75f, cy, t, col);
                    drawLine(cx + r * 0.2f, cy - r * 0.55f, cx + r * 0.75f, cy, t, col);
                    drawLine(cx + r * 0.2f, cy + r * 0.55f, cx + r * 0.75f, cy, t, col);
                }
                case FOLDER -> {
                    drawLine(cx - r * 0.8f, cy - r * 0.4f, cx - r * 0.2f, cy - r * 0.4f, t, col);
                    drawLine(cx - r * 0.2f, cy - r * 0.4f, cx, cy - r * 0.1f, t, col);
                    drawLine(cx, cy - r * 0.1f, cx + r * 0.8f, cy - r * 0.1f, t, col);
                    drawLine(cx + r * 0.8f, cy - r * 0.1f, cx + r * 0.8f, cy + r * 0.7f, t, col);
                    drawLine(cx + r * 0.8f, cy + r * 0.7f, cx - r * 0.8f, cy + r * 0.7f, t, col);
                    drawLine(cx - r * 0.8f, cy + r * 0.7f, cx - r * 0.8f, cy - r * 0.4f, t, col);
                }
                case DISK_SAVE -> {
                    drawRoundedRect(cx - r * 0.75f, cy - r * 0.75f, r * 1.5f, r * 1.5f, 2.0f, 0, col, t);
                    drawRoundedRect(cx - r * 0.4f, cy - r * 0.7f, r * 0.8f, r * 0.5f, 1.0f, col, 0, 0.0f);
                }
                case ROTATE_CW -> {
                    drawProgressRing(cx, cy, r * 0.75f, 1.4f, 0.75f, 0x00000000, col);
                    drawLine(cx + r * 0.35f, cy - r * 0.7f, cx + r * 0.75f, cy - r * 0.3f, t, col);
                }
                case SERVER_BOX -> {
                    drawRoundedRect(cx - r * 0.8f, cy - r * 0.7f, r * 1.6f, r * 1.4f, 2.5f, 0, col, t);
                    drawLine(cx - r * 0.5f, cy, cx + r * 0.5f, cy, t * 0.9f, col);
                    drawCircle(cx + r * 0.45f, cy - r * 0.35f, 1.4f, col, 0);
                    drawCircle(cx + r * 0.45f, cy + r * 0.35f, 1.4f, col, 0);
                }
                case WIFI_SIGNAL -> {
                    drawProgressRing(cx, cy + r * 0.5f, r * 0.4f, 1.3f, 0.5f, 0, col);
                    drawProgressRing(cx, cy + r * 0.5f, r * 0.8f, 1.3f, 0.5f, 0, col);
                    drawCircle(cx, cy + r * 0.5f, 1.5f, col, 0);
                }
                case SPEEDOMETER -> {
                    drawProgressRing(cx, cy, r * 0.85f, 1.4f, 0.75f, 0, col);
                    drawLine(cx, cy, cx + r * 0.45f, cy - r * 0.45f, t * 1.1f, col);
                    drawCircle(cx, cy, 1.8f, col, 0);
                }
                case STAR -> {
                    drawCircle(cx, cy, r * 0.7f, col, 0);
                }
                case USER -> {
                    drawCircle(cx, cy - r * 0.36f, r * 0.36f, col, 0);
                    drawLine(cx - r * 0.65f, cy + r * 0.70f, cx - r * 0.38f, cy + r * 0.22f, t, col);
                    drawLine(cx - r * 0.38f, cy + r * 0.22f, cx + r * 0.38f, cy + r * 0.22f, t, col);
                    drawLine(cx + r * 0.38f, cy + r * 0.22f, cx + r * 0.65f, cy + r * 0.70f, t, col);
                }
                default -> {
                    drawCircle(cx, cy, r * 0.6f, col, 0);
                }
            }
        }

        private static void dispatchQuad(float qx, float qy, float qw, float qh,
                                         float rx, float ry, float rw, float rh,
                                         float rTR, float rBR, float rTL, float rBL,
                                         int fillCol, int borderCol,
                                         float pX, float pY, float pZ, float pW,
                                         float sx1, float sy1, float sx2, float sy2) {
            INSTANCE.ensureInitialized();
            Window win = Minecraft.getInstance().getWindow();
            float guiW = (float) win.getGuiScaledWidth();
            float guiH = (float) win.getGuiScaledHeight();

            int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            int prevVbo = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            boolean prevBlendEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
            int prevBlendSrcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
            int prevBlendDstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
            int prevBlendSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
            int prevBlendDstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
            boolean prevDepthEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            boolean prevCullEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);

            if (prevDepthEnabled) {
                GlStateManager._disableDepthTest();
            }
            if (prevCullEnabled) {
                GlStateManager._disableCull();
            }

            GlStateManager._enableBlend();
            GlStateManager._blendFuncSeparate(
                    GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA
            );

            GL20.glUseProgram(programId);
            GL20.glUniform2f(uScreenSize, guiW, guiH);
            GL20.glUniform4f(uRect, rx, ry, rw, rh);
            GL20.glUniform4f(uRadii, rTR, rBR, rTL, rBL);
            setColorUniform(uFillColor, fillCol);
            setColorUniform(uBorderColor, borderCol);
            GL20.glUniform4f(uParams, pX, pY, pZ, pW);
            GL20.glUniform4f(uSegment, sx1, sy1, sx2, sy2);

            QUAD_BUF.clear();
            QUAD_BUF.put(qx).put(qy);
            QUAD_BUF.put(qx).put(qy + qh);
            QUAD_BUF.put(qx + qw).put(qy + qh);
            QUAD_BUF.put(qx).put(qy);
            QUAD_BUF.put(qx + qw).put(qy + qh);
            QUAD_BUF.put(qx + qw).put(qy);
            QUAD_BUF.flip();

            GL30.glBindVertexArray(vaoId);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, QUAD_BUF);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);

            GL30.glBindVertexArray(prevVao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevVbo);
            GL20.glUseProgram(prevProgram);
            if (prevBlendEnabled) {
                GlStateManager._enableBlend();
                GlStateManager._blendFuncSeparate(prevBlendSrcRgb, prevBlendDstRgb, prevBlendSrcAlpha, prevBlendDstAlpha);
            } else {
                GlStateManager._disableBlend();
            }
            if (prevDepthEnabled) {
                GlStateManager._enableDepthTest();
            }
            if (prevCullEnabled) {
                GlStateManager._enableCull();
            }
        }

        private static void setColorUniform(int loc, int argb) {
            float a = ((argb >>> 24) & 0xFF) / 255.0f;
            float r = ((argb >>> 16) & 0xFF) / 255.0f;
            float g = ((argb >>> 8) & 0xFF) / 255.0f;
            float b = (argb & 0xFF) / 255.0f;
            GL20.glUniform4f(loc, r, g, b, a);
        }

        public static int lerpColor(int c1, int c2, float t) {
            t = Mth.clamp(t, 0.0f, 1.0f);
            int a = (int) Mth.lerp(t, (c1 >>> 24) & 0xFF, (c2 >>> 24) & 0xFF);
            int r = (int) Mth.lerp(t, (c1 >>> 16) & 0xFF, (c2 >>> 16) & 0xFF);
            int g = (int) Mth.lerp(t, (c1 >>> 8) & 0xFF, (c2 >>> 8) & 0xFF);
            int b = (int) Mth.lerp(t, c1 & 0xFF, c2 & 0xFF);
            return (a << 24) | (r << 16) | (g << 8) | b;
        }

        public static void enableScissor(int x, int y, int w, int h) {
            Window win = Minecraft.getInstance().getWindow();
            double scale = win.getGuiScale();
            int sx = (int) (x * scale);
            int sy = (int) ((win.getGuiScaledHeight() - (y + h)) * scale);
            int sw = Math.max(0, (int) (w * scale));
            int sh = Math.max(0, (int) (h * scale));
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(sx, sy, sw, sh);
        }

        public static void disableScissor() {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
    }
}
