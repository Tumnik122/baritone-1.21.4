// ============================================================================
//  BaritoneBypassScreen.java — BARITONE ASTRA // REVOLUTION (v2.4)
//  Fabric 1.21.4 · Oficjalne mapowania Mojmap (Unimined) · 100% natywny render
//
//  Umieść plik w:  src/main/java/com/astra/revolution/gui/BaritoneBypassScreen.java
//  (jeśli używasz innego pakietu — zmień linię `package` i odpowiednio ścieżkę).
//  Zapisz plik w kodowaniu UTF-8 (polskie znaki w opisach modułów).
//
//  Otwieranie GUI (przykład, w twoim kodzie klienckim):
//      if (InputConstants.isKeyPressed(minecraft.getWindow().getWindow(),
//                                     BaritoneBypassScreen.OPEN_KEY)) {
//          minecraft.setScreen(new BaritoneBypassScreen());
//      }
//
//  Stan modułów jest eksportowany przez publiczne pola statyczne tej klasy
//  (np. BaritoneBypassScreen.fastBreakInstamine) — czytaj je w handlerach
//  tick / pakiet. Integracja z Baritone odbywa się przez odbicie (reflection),
//  więc projekt kompiluje się BEZ twardej zależności od Baritone.
// ============================================================================

package com.astra.revolution.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.utils.SettingsUtil;
import baritone.hud.AiActionLogger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import org.lwjgl.glfw.GLFW;

public class BaritoneBypassScreen extends Screen {

    // ========================================================================
    //  SEKCJA 1 · GEOMETRIA (wszystko window-relative, hit-testy = render)
    // ========================================================================
    private static final int WINDOW_W = 560;
    private static final int WINDOW_H = 360;
    private static final int TOPBAR_H = 34;
    private static final int SIDEBAR_W = 140;
    private static final int FOOTER_H = 16;

    private static final int CONTENT_X = SIDEBAR_W + 1;                  // 141
    private static final int CONTENT_Y = TOPBAR_H + 1;                  // 35
    private static final int CONTENT_W = WINDOW_W - CONTENT_X - 1;      // 418
    private static final int CONTENT_H = WINDOW_H - CONTENT_Y - FOOTER_H; // 309

    private static final int CARD_X    = CONTENT_X + 8;                 // 149
    private static final int CARD_W    = CONTENT_W - 16;                // 402
    private static final int CARD_H    = 46;
    private static final int CARD_STEP = 52;                            // karta + odstęp
    private static final int HEADER_H  = 30;                            // nagłówek sekcji

    // Pozycje widgetów względem lewego-górnego narożnika karty.
    private static final int TOGGLE_X = 352, TOGGLE_Y = 14, TOGGLE_W = 38, TOGGLE_H = 18;
    private static final int SLIDER_X = 240, SLIDER_W = 150, SLIDER_TRACK_Y = 25, SLIDER_TRACK_H = 4;
    private static final int CYCLE_X  = 262, CYCLE_Y  = 14, CYCLE_W  = 128, CYCLE_H  = 18;

    // ========================================================================
    //  SEKCJA 2 · PALETA „CLEAN OBSIDIAN / DARK GLASSMORPHISM”
    // ========================================================================
    private static final int C_WINDOW_BG   = 0xE6090C12; // midnight obsidian, 90%
    private static final int C_SIDEBAR_BG  = 0xFF10151F;
    private static final int C_CARD_BG     = 0xFF121722;
    private static final int C_CARD_BG_ALT = 0xFF171E2B;
    private static final int C_BORDER      = 0xFF232D3F;
    private static final int C_BORDER_HOT  = 0xFF33425E;
    private static final int C_CYAN        = 0xFF00E5FF;
    private static final int C_PURPLE      = 0xFF9D4EDD;
    private static final int C_INACTIVE    = 0xFF2A3447;
    private static final int C_TEXT_MAIN   = 0xFFF0F4FC;
    private static final int C_TEXT_DIM    = 0xFF8C9BAE;
    private static final int C_TEXT_FAINT  = 0xFF54637D;
    private static final int C_GREEN       = 0xFF35E08A;
    private static final int C_RED         = 0xFFFF4757;
    private static final int C_AMBER       = 0xFFE0A100;

    // ========================================================================
    //  SEKCJA 3 · IKONY PIXEL-ART 8x8 ('X' = zapalony piksel)
    //  (emoji z SMP nie renderują się w vanilla foncie — stąd własne glify)
    // ========================================================================
    private static final String[] ICON_BOLT = {
            ".....XX.",
            "....XX..",
            "...XX...",
            ".XXXXX..",
            "..XX....",
            ".XX.....",
            "XX......",
            "........"
    };
    private static final String[] ICON_SHIELD = {
            "XXXXXXXX",
            "X......X",
            "X..XX..X",
            "X.XXXX.X",
            "X..XX..X",
            "X......X",
            ".X....X.",
            "..XXXX.."
    };
    private static final String[] ICON_ROBOT = {
            "...X....",
            ".XXXXXX.",
            ".X.XX.X.",
            ".XXXXXX.",
            "..XXXX..",
            "X.XXXX.X",
            "X.XXXX.X",
            "..X..X.."
    };
    private static final String[] ICON_WAVE = {
            "........",
            "........",
            "XX..XX..",
            "..XX..XX",
            "XX..XX..",
            "..XX..XX",
            "........",
            "........"
    };
    private static final String[] ICON_EYE = {
            "........",
            "..XXXX..",
            ".X....X.",
            "X..XX..X",
            ".X....X.",
            "..XXXX..",
            "........",
            "........"
    };

    // ========================================================================
    //  SEKCJA 4 · PUBLICZNY STAN RUNTIME (do odczytu przez resztę moda)
    // ========================================================================
    public static final String VERSION = "v2.4";
    public static int OPEN_KEY = GLFW.GLFW_KEY_RIGHT_SHIFT;

    // ⚡ Mining & Speed
    public static boolean fastBreakInstamine  = false;
    public static boolean autoDetectEfficiency = false;
    public static boolean multiBlockQueue     = false;
    public static float   miningRange         = 4.5f;

    // 🛡️ GrimAC Bypass
    public static boolean raytraceLOS        = true;
    public static boolean smoothRotations    = true;
    public static float   rotationSpeed      = 140.0f;
    public static int     rotationMode       = 2;          // 0=PACKET 1=TICK 2=SMOOTH
    public static boolean strictPlace        = true;
    public static boolean combatLogDetector  = false;

    // 🤖 Automation & Anarchia
    public static boolean anarchiaMode        = false;
    public static boolean autoDropTrash       = false;
    public static boolean autoSortInventory   = false;
    public static float   dropDelay           = 2.0f;
    public static boolean autoEat             = false;
    public static float   autoEatThreshold    = 15.0f;
    public static boolean autoHomeOnFull      = false;
    public static boolean itemLockProtection  = false;

    // 🌊 Movement & Safety
    public static boolean waterAvoid          = true;
    public static boolean waterBuoyancy       = false;
    public static boolean lavaReflex          = false;
    public static float   lavaScanRadius      = 3.0f;
    public static boolean avoidFluidProximity = true;
    public static float   fluidAvoidDistance  = 3.0f;
    public static boolean antiSuffocation     = false;
    public static boolean tunnelFloatGuard    = false;
    public static boolean disconnectOnLowHealth = false;
    public static float   disconnectHealthHearts = 6.0f;
    public static boolean disconnectOnFall    = false;
    public static float   disconnectFallDistance = 5.0f;
    public static boolean autoWaterClutch     = true;

    // 👁️ Visuals & HUD
    public static boolean hudEnabled         = false;
    public static boolean oreEsp             = false;
    public static int     espMode            = 0;          // 0=OUTLINE 1=TRACERS 2=BOTH
    public static boolean pathTrail          = false;
    public static float   hudScale           = 100.0f;
    public static float   guiOpacity         = 0.90f;      // „Dark Mode Intensity”

    /** Ustaw na true z zewnątrz (np. proces górowania), aby dioda pokazała ACTIVE. */
    public static volatile boolean BOT_ACTIVE = false;

    // ========================================================================
    //  SEKCJA 5 · MODEL DANYCH
    // ========================================================================
    private static final float[] SAVED_SCROLL = new float[10];

    private final List<Category> categories;
    private int selectedCategory = 0;

    // ========================================================================
    //  SEKCJA 6 · STAN OKNA / INTERAKCJI / ANIMACJI
    // ========================================================================
    private int windowX = 0;
    private int windowY = 0;
    private boolean positioned = false;

    private boolean draggingWindow = false;
    private double dragOffX = 0.0;
    private double dragOffY = 0.0;
    private SliderModule draggingSlider = null;

    private long openTime = 0L;
    private float openProgress = 0.0f;

    private int fpsFrames = 0;
    private int fpsDisplay = 0;
    private long fpsClock = 0L;

    public BaritoneBypassScreen() {
        super(Component.literal("Baritone Astra // Revolution"));
        syncFromBaritone();
        this.categories = buildCategories();
        long now = System.currentTimeMillis();
        this.openTime = now;
        this.fpsClock = now;
    }

    @Override
    protected void init() {
        if (!this.positioned) {
            this.windowX = (this.width - WINDOW_W) / 2;
            this.windowY = (this.height - WINDOW_H) / 2;
            this.positioned = true;
        }
    }

    public static void playClickSound() {
        try {
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        } catch (Throwable ignored) {
        }
    }

    // ========================================================================
    //  SEKCJA 7 · RENDER GŁÓWNY
    // ========================================================================
    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        updateFps();
        clampWindow();

        // Animacja otwarcia (easeOutCubic) — fade + subtelny wjazd od dołu.
        float t = clamp((System.currentTimeMillis() - this.openTime) / 170.0f, 0.0f, 1.0f);
        this.openProgress = 1.0f - (1.0f - t) * (1.0f - t) * (1.0f - t);
        float p = this.openProgress;

        int wx = this.windowX;
        int wy = this.windowY + Math.round((1.0f - p) * 12.0f);

        // Przyciemnienie świata za oknem.
        g.fill(0, 0, this.width, this.height, ((int) (120.0f * p)) << 24);

        // Miękki cień pod oknem.
        drawRoundedRect(g, wx - 4, wy - 3, wx + WINDOW_W + 4, wy + WINDOW_H + 5, 14,
                ((int) (102.0f * p)) << 24);

        // Korpus okna — Dark Glassmorphism.
        drawBorderedPanel(g, wx, wy, wx + WINDOW_W, wy + WINDOW_H, 10, op(C_WINDOW_BG), C_BORDER);

        renderSidebar(g, wx, wy, mouseX, mouseY);
        // Neonowy separator pod belką (cyjan -> fiolet, poziomy).
        drawGradientRect(g, wx + 2, wy + TOPBAR_H, wx + WINDOW_W - 2, wy + TOPBAR_H + 1,
                C_CYAN, C_PURPLE, 24);
        renderTopBar(g, wx, wy, mouseX, mouseY);
        renderContent(g, wx, wy, mouseX, mouseY);
        renderFooter(g, wx, wy);

        super.render(g, mouseX, mouseY, partialTick);
    }

    // ------------------------------------------------------------------ belka
    private void renderTopBar(GuiGraphics g, int wx, int wy, int mouseX, int mouseY) {
        Font f = this.font;

        String head = "BARITONE ASTRA";
        int headW = f.width(head);
        g.drawString(f, head, wx + 12, wy + 12, C_TEXT_MAIN, false);

        String tail = "// REVOLUTION";
        int tailX = wx + 12 + headW + 5;
        g.drawString(f, tail, tailX, wy + 12, C_PURPLE, false);
        g.drawString(f, VERSION, tailX + f.width(tail) + 8, wy + 12, C_TEXT_FAINT, false);

        // Wskaźnik FPS (własny licznik klatek).
        String fpsText = this.fpsDisplay + " FPS";
        g.drawString(f, fpsText, wx + 450 - f.width(fpsText), wy + 12, C_TEXT_DIM, false);

        // Dioda stanu bota (zielona/czerwona) + etykieta.
        boolean active = BaritoneBridge.isBotActive();
        g.drawString(f, active ? "ACTIVE" : "IDLE", wx + 458, wy + 12, active ? C_GREEN : C_RED, false);
        drawRoundedRect(g, wx + 502, wy + 11, wx + 514, wy + 23, 6, active ? 0x4635E08A : 0x46FF4757);
        drawRoundedRect(g, wx + 504, wy + 13, wx + 512, wy + 21, 4, active ? C_GREEN : C_RED);

        // Przycisk zamknięcia [X].
        int cX = wx + WINDOW_W - 30;
        int cY = wy + 6;
        boolean hot = inRect(mouseX, mouseY, cX, cY, cX + 22, cY + 22);
        if (hot) {
            drawBorderedPanel(g, cX, cY, cX + 22, cY + 22, 6, 0x30FF4757, 0x80FF4757);
        }
        String xLabel = "X";
        g.drawString(f, xLabel, cX + (22 - f.width(xLabel)) / 2, cY + 6,
                hot ? 0xFFFF7B88 : C_TEXT_DIM, false);
    }

    // ------------------------------------------------------------- pasek kategorii
    private void renderSidebar(GuiGraphics g, int wx, int wy, int mouseX, int mouseY) {
        Font f = this.font;

        drawBorderedPanel(g, wx + 1, wy + TOPBAR_H + 1, wx + 1 + SIDEBAR_W,
                wy + WINDOW_H - FOOTER_H, 6, op(C_SIDEBAR_BG), C_BORDER);
        g.fill(wx + 1 + SIDEBAR_W, wy + TOPBAR_H + 1, wx + 2 + SIDEBAR_W,
                wy + WINDOW_H - FOOTER_H, C_BORDER);

        int tabX = wx + 8;
        int tabW = SIDEBAR_W - 16;
        int baseY = wy + TOPBAR_H + 10;
        for (int i = 0; i < this.categories.size(); i++) {
            Category cat = this.categories.get(i);
            int tabY = baseY + i * 36;
            boolean selected = i == this.selectedCategory;
            boolean hot = !selected && inRect(mouseX, mouseY, tabX, tabY, tabX + tabW, tabY + 30);

            if (selected) {
                drawBorderedPanel(g, tabX, tabY, tabX + tabW, tabY + 30, 6,
                        op(C_CARD_BG_ALT), C_BORDER_HOT);
                g.fillGradient(tabX + 1, tabY + 6, tabX + 3, tabY + 24, C_CYAN, C_PURPLE);
            } else if (hot) {
                drawRoundedRect(g, tabX, tabY, tabX + tabW, tabY + 30, 6, 0x18FFFFFF);
            }

            drawIcon(g, cat.icon, tabX + 8, tabY + 11,
                    selected ? C_CYAN : (hot ? C_TEXT_MAIN : C_TEXT_DIM));
            g.drawString(f, trunc(cat.name, tabW - 26), tabX + 22, tabY + 11,
                    selected ? C_TEXT_MAIN : C_TEXT_DIM, false);
        }

        // Sygnatura w stopce sidebara.
        int brandY = wy + WINDOW_H - FOOTER_H - 26;
        drawIcon(g, ICON_BOLT, wx + 10, brandY, C_BORDER_HOT);
        g.drawString(f, "ASTRA CORE", wx + 24, brandY, C_TEXT_FAINT, false);
    }

    // ------------------------------------------------------------- sekcja robocza
    private void renderContent(GuiGraphics g, int wx, int wy, int mouseX, int mouseY) {
        Font f = this.font;
        Category cat = this.categories.get(this.selectedCategory);
        int cx = wx + CONTENT_X;
        int cy = wy + CONTENT_Y;

        // Płynny scroll (lerp) z clampem do realnej wysokości zawartości.
        float maxScroll = maxScroll(cat);
        cat.scrollTarget = clamp(cat.scrollTarget, 0.0f, maxScroll);
        cat.scroll += (cat.scrollTarget - cat.scroll) * 0.38f;
        if (Math.abs(cat.scrollTarget - cat.scroll) < 0.05f) cat.scroll = cat.scrollTarget;
        cat.scroll = clamp(cat.scroll, 0.0f, maxScroll);
        if (this.selectedCategory >= 0 && this.selectedCategory < SAVED_SCROLL.length) {
            SAVED_SCROLL[this.selectedCategory] = cat.scrollTarget;
        }
        int scroll = (int) cat.scroll;

        // Od 1.20.2 enableScissor przyjmuje narożniki (minX, minY, maxX, maxY).
        g.enableScissor(cx, cy, cx + CONTENT_W, cy + CONTENT_H);

        // Nagłówek sekcji — przewija się razem z kartami.
        g.drawString(f, cat.name.toUpperCase(Locale.ROOT), cx + 8, cy + 9 - scroll, C_TEXT_MAIN, false);
        String count = cat.modules.size() + " MODULES";
        g.drawString(f, count, cx + CONTENT_W - 18 - f.width(count), cy + 9 - scroll, C_TEXT_DIM, false);
        drawGradientRect(g, cx + 8, cy + 22 - scroll, cx + 128, cy + 23 - scroll,
                0xC800E5FF, 0x00E5FF, 16);

        // Karty modułów (pomijamy te całkowicie poza viewportem).
        for (int i = 0; i < cat.modules.size(); i++) {
            int cardY = cy + HEADER_H + i * CARD_STEP - scroll;
            if (cardY > cy + CONTENT_H || cardY + CARD_H < cy) continue;
            renderCard(g, cat.modules.get(i), wx + CARD_X, cardY, mouseX, mouseY);
        }
        g.disableScissor();

        // Pasek przewijania (thumb: cyjan -> fiolet).
        if (maxScroll > 0.5f) {
            int sbX = cx + CONTENT_W - 5;
            int trackY = cy + 2;
            int trackH = CONTENT_H - 4;
            g.fill(sbX, trackY, sbX + 2, trackY + trackH, 0x14FFFFFF);
            int thumbH = (int) Math.max(24.0f, (float) trackH * ((float) CONTENT_H / contentHeight(cat)));
            thumbH = Math.min(thumbH, trackH);
            int thumbY = trackY + Math.round((cat.scroll / maxScroll) * (float) (trackH - thumbH));
            g.fillGradient(sbX, thumbY, sbX + 2, thumbY + thumbH, 0xCC00E5FF, 0xCC9D4EDD);
        }

        // Subtelna ramka sekcji — natywny renderOutline.
        g.renderOutline(cx, cy, cx + CONTENT_W, cy + CONTENT_H, 0x22FFFFFF);
    }

    // ------------------------------------------------------------------ karta
    private void renderCard(GuiGraphics g, Module module, int cardX, int cardY, int mouseX, int mouseY) {
        boolean hot = inRect(mouseX, mouseY, cardX, cardY, cardX + CARD_W, cardY + CARD_H);
        if (hot) {
            drawRoundedRect(g, cardX - 2, cardY - 2, cardX + CARD_W + 2, cardY + CARD_H + 2, 10, 0x2A00E5FF);
        }
        drawBorderedPanel(g, cardX, cardY, cardX + CARD_W, cardY + CARD_H, 8,
                op(hot ? C_CARD_BG_ALT : C_CARD_BG), hot ? C_BORDER_HOT : C_BORDER);
        if (hot) {
            drawOutline(g, cardX, cardY, cardX + CARD_W, cardY + CARD_H, 8, 0x5C00E5FF);
        }

        Font f = this.font;
        g.drawString(f, trunc(module.name, module.nameMax()), cardX + 12, cardY + 9, C_TEXT_MAIN, false);
        g.drawString(f, trunc(module.description, module.descMax()), cardX + 12, cardY + 21,
                C_TEXT_DIM, false);
        module.render(g, f, cardX, cardY, mouseX, mouseY);
    }

    // ------------------------------------------------------------------ stopka
    private void renderFooter(GuiGraphics g, int wx, int wy) {
        Font f = this.font;
        g.fill(wx + 1, wy + WINDOW_H - FOOTER_H, wx + WINDOW_W - 1,
                wy + WINDOW_H - FOOTER_H + 1, C_BORDER);

        int textY = wy + WINDOW_H - FOOTER_H + 4;
        boolean linked = BaritoneBridge.linked();
        g.drawString(f, "ASTRA LINK ::", wx + 12, textY, C_TEXT_FAINT, false);
        String status = linked ? "BARITONE ONLINE" : "OFFLINE / LOCAL FLAGS";
        g.drawString(f, status, wx + 12 + f.width("ASTRA LINK ::") + 6, textY,
                linked ? C_GREEN : C_AMBER, false);

        int total = 0;
        for (Category cat : this.categories) total += cat.modules.size();
        String right = total + " MODULES LOADED";
        g.drawString(f, right, wx + WINDOW_W - 12 - f.width(right), textY, C_TEXT_FAINT, false);
    }

    // ========================================================================
    //  SEKCJA 8 · OBSŁUGA MYSZY I KLAWIATURY
    // ========================================================================
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int mx = (int) mouseX;
        int my = (int) mouseY;
        int wx = this.windowX;
        int wy = this.windowY;

        // --- Górna belka: przeciąganie okna / przycisk zamknięcia.
        if (inRect(mx, my, wx + 1, wy + 1, wx + WINDOW_W - 1, wy + TOPBAR_H)) {
            int closeX = wx + WINDOW_W - 30;
            int closeY = wy + 6;
            if (inRect(mx, my, closeX, closeY, closeX + 22, closeY + 22)) {
                playClickSound();
                onClose();
                return true;
            }
            this.draggingWindow = true;
            this.dragOffX = mouseX - (double) wx;
            this.dragOffY = mouseY - (double) wy;
            return true;
        }

        // --- Pasek kategorii: wybór zakładki.
        if (inRect(mx, my, wx + 1, wy + TOPBAR_H + 1, wx + 1 + SIDEBAR_W, wy + WINDOW_H - FOOTER_H)) {
            int tabX = wx + 8;
            int tabW = SIDEBAR_W - 16;
            int baseY = wy + TOPBAR_H + 10;
            for (int i = 0; i < this.categories.size(); i++) {
                int tabY = baseY + i * 36;
                if (inRect(mx, my, tabX, tabY, tabX + tabW, tabY + 30)) {
                    if (this.selectedCategory != i) {
                        this.selectedCategory = i;
                        playClickSound();
                    }
                    return true;
                }
            }
            return true;
        }

        // --- Sekcja robocza: karty / widgety.
        if (inRect(mx, my, wx + CONTENT_X, wy + CONTENT_Y,
                wx + CONTENT_X + CONTENT_W, wy + CONTENT_Y + CONTENT_H)) {
            Category cat = this.categories.get(this.selectedCategory);
            int cardX = wx + CARD_X;
            int baseY = wy + CONTENT_Y + HEADER_H;
            for (int i = 0; i < cat.modules.size(); i++) {
                int cardY = baseY + i * CARD_STEP - (int) cat.scroll;
                if (mx >= cardX && mx <= cardX + CARD_W && my >= cardY && my < cardY + CARD_H) {
                    if (cat.modules.get(i).onClick(this, mouseX, mouseY, button, cardX, cardY)) {
                        return true;
                    }
                }
            }
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.draggingWindow) {
            this.windowX = clamp((int) (mouseX - this.dragOffX),
                    -WINDOW_W + 120, Math.max(-WINDOW_W + 120, this.width - 120));
            this.windowY = clamp((int) (mouseY - this.dragOffY), 2, Math.max(2, this.height - 60));
            return true;
        }
        if (this.draggingSlider != null) {
            this.draggingSlider.setFromMouse(mouseX, this.windowX + CARD_X + SLIDER_X);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean wasInteracting = this.draggingWindow || this.draggingSlider != null;
        if (this.draggingSlider != null) {
            try {
                SettingsUtil.save(Baritone.settings());
            } catch (Throwable ignored) {
            }
        }
        this.draggingWindow = false;
        this.draggingSlider = null;
        if (wasInteracting) return true;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    // Od 1.21.2: cztery argumenty (mouseX, mouseY, scrollX, scrollY).
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int mx = (int) mouseX;
        int my = (int) mouseY;
        int wx = this.windowX;
        int wy = this.windowY;
        if (inRect(mx, my, wx + CONTENT_X, wy + CONTENT_Y,
                wx + CONTENT_X + CONTENT_W, wy + CONTENT_Y + CONTENT_H)) {
            Category cat = this.categories.get(this.selectedCategory);
            cat.scrollTarget = clamp(cat.scrollTarget - (float) scrollY * 34.0f, 0.0f, maxScroll(cat));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == OPEN_KEY) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // 1.21.2+ Mojmap: shouldPause(); starsze mapowania: isPauseScreen().
    // Celowo BEZ @Override — obie deklaracje zawsze kompilują się bez błędu,
    // a aktywna nazwa realnie wyłącza pauzowanie singleplayera.
    public boolean shouldPause() {
        return false;
    }

    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        pushAllToBaritone();
        try {
            SettingsUtil.save(Baritone.settings());
        } catch (Throwable ignored) {}
        super.onClose();
    }

    // ========================================================================
    //  SEKCJA 9 · BUDOWA ZAKŁADEK I MODUŁÓW
    // ========================================================================
    public static void syncFromBaritone() {
        try {
            baritone.api.Settings s = Baritone.settings();
            if (s == null) return;

            // ⚡ Mining & Speed
            fastBreakInstamine = s.fastBreak.value;
            autoDetectEfficiency = s.autoDetectEfficiency10.value;
            multiBlockQueue = s.continuousBreaking.value;
            miningRange = s.blockReachDistance.value;

            // 🛡️ GrimAC Bypass
            raytraceLOS = s.strictRaytraceOnly.value;
            smoothRotations = s.smoothRotation.value;
            rotationSpeed = s.maxRotationSpeedPerTick.value;
            if (s.smoothRotation.value && s.humanizedRotations.value) {
                rotationMode = 2; // SMOOTH
            } else if (s.smoothRotation.value) {
                rotationMode = 1; // TICK
            } else {
                rotationMode = 0; // PACKET
            }
            strictPlace = s.antiCheatCompatibility.value;
            combatLogDetector = s.autoEatPauseInCombat.value;

            // 🤖 Automation & Anarchia
            anarchiaMode = s.anarchiaMode.value;
            autoDropTrash = s.autoDropTrash.value;
            autoSortInventory = s.autoSortInventory.value;
            itemLockProtection = s.autoLockResource.value;
            dropDelay = Math.max(1.0f, Math.min(20.0f, s.autoDropDelayMs.value / 50.0f));
            autoEat = s.autoEat.value;
            autoEatThreshold = (float) s.autoEatThreshold.value;
            autoHomeOnFull = s.autoHomeOnFull.value;

            // 🌊 Movement & Safety
            waterAvoid = s.mineAvoidWater.value;
            waterBuoyancy = !s.sprintInWater.value;
            lavaReflex = s.mineAvoidLava.value;
            lavaScanRadius = s.breakHoldMaxDistance.value != null ? s.breakHoldMaxDistance.value.floatValue() : 3.0f;
            avoidFluidProximity = s.avoidFluidProximity.value;
            fluidAvoidDistance = s.fluidAvoidDistance.value != null ? s.fluidAvoidDistance.value.floatValue() : 3.0f;
            antiSuffocation = s.avoidUpdatingFallingBlocks.value;
            tunnelFloatGuard = !s.assumeWalkOnWater.value;
            disconnectOnLowHealth = s.disconnectOnLowHealth.value;
            disconnectHealthHearts = s.disconnectHealthHearts.value != null ? s.disconnectHealthHearts.value.floatValue() : 6.0f;
            disconnectOnFall = s.disconnectOnFall.value;
            disconnectFallDistance = s.disconnectFallDistance.value != null ? s.disconnectFallDistance.value.floatValue() : 5.0f;
            autoWaterClutch = s.autoWaterClutch.value && s.allowWaterBucketFall.value;

            // 👁️ Visuals & HUD
            hudEnabled = s.showHudOverlay.value;
            pathTrail = s.renderPath.value;
            hudScale = (float) (s.hudScale.value * 100.0);
        } catch (Throwable ignored) {
        }
    }

    public void refreshFromSettings() {
        syncFromBaritone();
        if (this.categories == null) return;
        for (Category cat : this.categories) {
            for (Module m : cat.modules) {
                if (m instanceof ToggleModule tm) {
                    // Mining & Speed
                    if (tm.name.equals("FastBreak Instamine")) tm.enabled = fastBreakInstamine;
                    else if (tm.name.equals("AutoDetect Efficiency 10")) tm.enabled = autoDetectEfficiency;
                    else if (tm.name.equals("Multi-Block Queue")) tm.enabled = multiBlockQueue;
                    // GrimAC Bypass
                    else if (tm.name.equals("Raytrace Line-Of-Sight")) tm.enabled = raytraceLOS;
                    else if (tm.name.equals("Smooth Human Rotations")) tm.enabled = smoothRotations;
                    else if (tm.name.equals("Strict Place Verification")) tm.enabled = strictPlace;
                    else if (tm.name.equals("Combat Log Detector")) tm.enabled = combatLogDetector;
                    // Automation
                    else if (tm.name.equals("Tryb Anarchia")) tm.enabled = anarchiaMode;
                    else if (tm.name.equals("AutoDrop Trash")) tm.enabled = autoDropTrash;
                    else if (tm.name.equals("AutoSort Inventory")) tm.enabled = autoSortInventory;
                    else if (tm.name.equals("Item Lock Protection")) tm.enabled = itemLockProtection;
                    else if (tm.name.equals("AutoEat Smart")) tm.enabled = autoEat;
                    else if (tm.name.equals("AutoHome on Full")) tm.enabled = autoHomeOnFull;
                    // Movement & Safety
                    else if (tm.name.equals("AI Water Avoidance")) tm.enabled = waterAvoid;
                    else if (tm.name.equals("Water Surface Buoyancy")) tm.enabled = waterBuoyancy;
                    else if (tm.name.equals("Real-Time Lava Reflex")) tm.enabled = lavaReflex;
                    else if (tm.name.equals("Fluid Proximity Buffer")) tm.enabled = avoidFluidProximity;
                    else if (tm.name.equals("Anti-Suffocation")) tm.enabled = antiSuffocation;
                    else if (tm.name.equals("Tunnel 1x2 Float Guard")) tm.enabled = tunnelFloatGuard;
                    else if (tm.name.equals("AutoLog Low Health")) tm.enabled = disconnectOnLowHealth;
                    else if (tm.name.equals("AutoLog Fall Protect")) tm.enabled = disconnectOnFall;
                    else if (tm.name.equals("MLG Water Clutch")) tm.enabled = autoWaterClutch;
                    // Visuals & HUD
                    else if (tm.name.equals("Live Diagnostics HUD")) tm.enabled = hudEnabled;
                    else if (tm.name.equals("Ore ESP / Tracers")) tm.enabled = oreEsp;
                    else if (tm.name.equals("Path Trail Renderer")) tm.enabled = pathTrail;
                } else if (m instanceof SliderModule sm) {
                    if (sm.name.equals("Mining Range")) sm.value = miningRange;
                    else if (sm.name.equals("Rotation Speed")) sm.value = rotationSpeed;
                    else if (sm.name.equals("Drop & Sort Delay")) sm.value = dropDelay;
                    else if (sm.name.equals("AutoEat Threshold")) sm.value = autoEatThreshold;
                    else if (sm.name.equals("Lava Scan Radius")) sm.value = lavaScanRadius;
                    else if (sm.name.equals("Fluid Buffer Distance")) sm.value = fluidAvoidDistance;
                    else if (sm.name.equals("AutoLog Hearts")) sm.value = disconnectHealthHearts;
                    else if (sm.name.equals("Fall Protect Distance")) sm.value = disconnectFallDistance;
                    else if (sm.name.equals("HUD Scale")) sm.value = hudScale;
                    else if (sm.name.equals("Dark Mode Intensity")) sm.value = guiOpacity * 100.0f;
                } else if (m instanceof CycleModule cm) {
                    if (cm.name.equals("Rotation Mode")) cm.index = clamp(rotationMode, 0, cm.modes.length - 1);
                    else if (cm.name.equals("ESP Mode")) cm.index = clamp(espMode, 0, cm.modes.length - 1);
                }
            }
        }
    }

    private static List<Category> buildCategories() {
        List<Category> list = new ArrayList<>();
        list.add(new Category("Mining & Speed", ICON_BOLT, buildMiningCategory(), SAVED_SCROLL[0]));
        list.add(new Category("GrimAC Bypass", ICON_SHIELD, buildGrimCategory(), SAVED_SCROLL[1]));
        list.add(new Category("Automation", ICON_ROBOT, buildAutomationCategory(), SAVED_SCROLL[2]));
        list.add(new Category("Movement & Safety", ICON_WAVE, buildMovementCategory(), SAVED_SCROLL[3]));
        list.add(new Category("Visuals & HUD", ICON_EYE, buildVisualsCategory(), SAVED_SCROLL[4]));
        return list;
    }

    private static List<Module> buildMiningCategory() {
        List<Module> modules = new ArrayList<>();
        modules.add(new ToggleModule("FastBreak Instamine",
                "Natychmiastowe łamanie bloków bez opóźnień.",
                fastBreakInstamine,
                v -> {
                    fastBreakInstamine = v;
                    try {
                        Baritone.settings().fastBreak.value = v;
                        Baritone.settings().continuousBreaking.value = v || multiBlockQueue;
                        SettingsUtil.save(Baritone.settings());
                        AiActionLogger.log("GUI", "FastBreak -> " + (v ? "ON" : "OFF"));
                    } catch (Throwable ignored) {}
                }));
        modules.add(new ToggleModule("AutoDetect Efficiency 10",
                "Wykrywa kilofy Wydajność 10+ i znosi 5-tickowe opóźnienie.",
                autoDetectEfficiency,
                v -> {
                    autoDetectEfficiency = v;
                    try {
                        Baritone.settings().autoDetectEfficiency10.value = v;
                        SettingsUtil.save(Baritone.settings());
                        AiActionLogger.log("GUI", "AutoDetect Efficiency 10 -> " + (v ? "ON" : "OFF"));
                    } catch (Throwable ignored) {}
                }));
        modules.add(new ToggleModule("Multi-Block Queue",
                "Kolejkowanie bloków i ciągłe niszczenie bez przerw.",
                multiBlockQueue,
                v -> {
                    multiBlockQueue = v;
                    try {
                        Baritone.settings().continuousBreaking.value = v || fastBreakInstamine;
                        Baritone.settings().breakHoldTicks.value = v ? 15 : 0;
                        SettingsUtil.save(Baritone.settings());
                        AiActionLogger.log("GUI", "Multi-Block Queue -> " + (v ? "ON" : "OFF"));
                    } catch (Throwable ignored) {}
                }));
        modules.add(new SliderModule("Mining Range",
                "Zasięg kopania w blokach.",
                1.0f, 6.0f, 0.1f, miningRange, " blk",
                v -> {
                    miningRange = v;
                    try {
                        Baritone.settings().blockReachDistance.value = v;
                        SettingsUtil.save(Baritone.settings());
                    } catch (Throwable ignored) {}
                }));
        return modules;
    }

    private static List<Module> buildGrimCategory() {
        List<Module> modules = new ArrayList<>();
        modules.add(new ToggleModule("Raytrace Line-Of-Sight",
                "Sprawdza widoczność celu - brak klikania przez ściany.",
                raytraceLOS,
                v -> {
                    raytraceLOS = v;
                    try {
                        Baritone.settings().strictRaytraceOnly.value = v;
                        SettingsUtil.save(Baritone.settings());
                        AiActionLogger.log("GUI", "Raytrace LOS -> " + (v ? "ON" : "OFF"));
                    } catch (Throwable ignored) {}
                }));
        modules.add(new ToggleModule("Smooth Human Rotations",
                "Płynne rotacje głową z ludzką akceleracją.",
                smoothRotations,
                v -> {
                    smoothRotations = v;
                    try {
                        Baritone.settings().smoothRotation.value = v;
                        Baritone.settings().humanizedRotations.value = v;
                        SettingsUtil.save(Baritone.settings());
                        AiActionLogger.log("GUI", "Smooth Human Rotations -> " + (v ? "ON" : "OFF"));
                    } catch (Throwable ignored) {}
                }));
        modules.add(new SliderModule("Rotation Speed",
                "Maksymalna prędkość obrotu głowy.",
                1.0f, 30.0f, 1.0f, rotationSpeed, "°/t",
                v -> {
                    rotationSpeed = v;
                    try {
                        Baritone.settings().maxRotationSpeedPerTick.value = v;
                        SettingsUtil.save(Baritone.settings());
                    } catch (Throwable ignored) {}
                }));
        modules.add(new CycleModule("Rotation Mode",
                "Tryb rotacji wysyłanej do serwera.",
                new String[]{"PACKET", "TICK", "SMOOTH"}, rotationMode,
                i -> {
                    rotationMode = i;
                    try {
                        if (i == 2) {
                            Baritone.settings().smoothRotation.value = true;
                            Baritone.settings().humanizedRotations.value = true;
                        } else if (i == 1) {
                            Baritone.settings().smoothRotation.value = true;
                            Baritone.settings().humanizedRotations.value = false;
                        } else {
                            Baritone.settings().smoothRotation.value = false;
                            Baritone.settings().humanizedRotations.value = false;
                        }
                        SettingsUtil.save(Baritone.settings());
                        AiActionLogger.log("GUI", "Rotation Mode -> " + (i == 2 ? "SMOOTH" : (i == 1 ? "TICK" : "PACKET")));
                    } catch (Throwable ignored) {}
                }));
        modules.add(new ToggleModule("Strict Place Verification",
                "Legalne pakiety kładzenia bloków (Grim Place).",
                strictPlace,
                v -> {
                    strictPlace = v;
                    try {
                        Baritone.settings().antiCheatCompatibility.value = v;
                        Baritone.settings().antiCheatCompat.value = v;
                        Baritone.settings().humanizedInteractDelay.value = v;
                        SettingsUtil.save(Baritone.settings());
                        AiActionLogger.log("GUI", "Strict Place -> " + (v ? "ON" : "OFF"));
                    } catch (Throwable ignored) {}
                }));
        modules.add(new ToggleModule("Combat Log Detector",
                "Natychmiastowy bezpieczny odwrót przy ataku gracza.",
                combatLogDetector,
                v -> {
                    combatLogDetector = v;
                    try {
                        Baritone.settings().autoEatPauseInCombat.value = v;
                        SettingsUtil.save(Baritone.settings());
                        AiActionLogger.log("GUI", "Combat Log Detector -> " + (v ? "ON" : "OFF"));
                    } catch (Throwable ignored) {}
                }));
        return modules;
    }

    private static List<Module> buildAutomationCategory() {
        List<Module> modules = new ArrayList<>();
        modules.add(new ToggleModule("Tryb Anarchia",
                "AutoDrop + AutoSort + Blokuj sloty + AutoLog <6 serc + FallProtect >5 blk.",
                anarchiaMode,
                v -> {
                    anarchiaMode = v;
                    try {
                        Baritone.settings().anarchiaMode.value = v;
                        if (v) {
                            Baritone.settings().autoDropTrash.value = true;
                            Baritone.settings().autoSortInventory.value = true;
                            Baritone.settings().autoLockResource.value = true;
                            Baritone.settings().disconnectOnLowHealth.value = true;
                            Baritone.settings().disconnectHealthHearts.value = 6.0D;
                            Baritone.settings().disconnectOnFall.value = true;
                            Baritone.settings().disconnectFallDistance.value = 5.0D;

                            autoDropTrash = true;
                            autoSortInventory = true;
                            itemLockProtection = true;
                            disconnectOnLowHealth = true;
                            disconnectHealthHearts = 6.0f;
                            disconnectOnFall = true;
                            disconnectFallDistance = 5.0f;

                            AiActionLogger.log("GUI", "TRYB ANARCHIA -> ON [Drop+Sort+Lock+HP<6+Fall>5b]");
                            if (Minecraft.getInstance().gui != null && Minecraft.getInstance().gui.getChat() != null) {
                                Minecraft.getInstance().gui.getChat().addMessage(
                                        Component.literal("§6§l[Anarchia] §aWłączono tryb Anarchia! §7(AutoDrop ON, AutoSort ON, AutoLock ON, AutoLog <6 serc ON, FallProtect >5 kratek ON)")
                                );
                            }
                        } else {
                            Baritone.settings().anarchiaMode.value = false;
                            Baritone.settings().autoDropTrash.value = false;
                            Baritone.settings().autoSortInventory.value = false;
                            Baritone.settings().autoLockResource.value = false;

                            autoDropTrash = false;
                            autoSortInventory = false;
                            itemLockProtection = false;

                            AiActionLogger.log("GUI", "TRYB ANARCHIA -> OFF");
                            if (Minecraft.getInstance().gui != null && Minecraft.getInstance().gui.getChat() != null) {
                                Minecraft.getInstance().gui.getChat().addMessage(
                                        Component.literal("§6§l[Anarchia] §cWyłączono tryb Anarchia (AutoDrop OFF, AutoSort OFF, AutoLock OFF).")
                                );
                            }
                        }
                        SettingsUtil.save(Baritone.settings());
                    } catch (Throwable ignored) {}
                }));
        modules.add(new ToggleModule("AutoDrop Trash",
                "Wyrzuca bruk, andezyt, dioryt, granit i ziemię.",
                autoDropTrash,
                v -> {
                    autoDropTrash = v;
                    try {
                        Baritone.settings().autoDropTrash.value = v;
                        SettingsUtil.save(Baritone.settings());
                        AiActionLogger.log("GUI", "AutoDrop Trash -> " + (v ? "ON" : "OFF"));
                    } catch (Throwable ignored) {}
                }));
        modules.add(new ToggleModule("AutoSort Inventory",
                "Czyści eq i od razu po tym je segreguje (hotbar, stacki, surowce).",
                autoSortInventory,
                v -> {
                    autoSortInventory = v;
                    try {
                        Baritone.settings().autoSortInventory.value = v;
                        SettingsUtil.save(Baritone.settings());
                        AiActionLogger.log("GUI", "AutoSort Inventory -> " + (v ? "ON" : "OFF"));
                    } catch (Throwable ignored) {}
                }));
        modules.add(new SliderModule("Drop & Sort Delay",
                "Opóźnienie między akcjami w ekwipunku (GrimAC-safe).",
                1.0f, 20.0f, 1.0f, dropDelay, " t",
                v -> {
                    dropDelay = v;
                    try {
                        Baritone.settings().autoDropDelayMs.value = Math.max(50, Math.round(v * 50.0f));
                        Baritone.settings().autoSortDelayTicks.value = Math.max(1, Math.round(v));
                        SettingsUtil.save(Baritone.settings());
                    } catch (Throwable ignored) {}
                }));
        modules.add(new ToggleModule("Item Lock Protection",
                "Blokowanie slotów surowcem (np. surowym żelazem).",
                itemLockProtection,
                v -> {
                    itemLockProtection = v;
                    try {
                        Baritone.settings().autoLockResource.value = v;
                        SettingsUtil.save(Baritone.settings());
                        AiActionLogger.log("GUI", "Item Lock Protection -> " + (v ? "ON" : "OFF"));
                    } catch (Throwable ignored) {}
                }));
        modules.add(new ToggleModule("AutoEat Smart",
                "Automatyczne jedzenie w bezpiecznym ukryciu.",
                autoEat,
                v -> {
                    autoEat = v;
                    try {
                        Baritone.settings().autoEat.value = v;
                        SettingsUtil.save(Baritone.settings());
                        AiActionLogger.log("GUI", "AutoEat Smart -> " + (v ? "ON" : "OFF"));
                    } catch (Throwable ignored) {}
                }));
        modules.add(new SliderModule("AutoEat Threshold",
                "Poziom głodu aktywujący jedzenie.",
                6.0f, 20.0f, 1.0f, autoEatThreshold, " / 20",
                v -> {
                    autoEatThreshold = v;
                    try {
                        Baritone.settings().autoEatThreshold.value = Math.round(v);
                        SettingsUtil.save(Baritone.settings());
                    } catch (Throwable ignored) {}
                }));
        modules.add(new ToggleModule("AutoHome on Full",
                "Powrót do bazy po zapełnieniu ekwipunku.",
                autoHomeOnFull,
                v -> {
                    autoHomeOnFull = v;
                    try {
                        Baritone.settings().autoHomeOnFull.value = v;
                        Baritone.settings().allowInventory.value = v;
                        SettingsUtil.save(Baritone.settings());
                        AiActionLogger.log("GUI", "AutoHome on Full -> " + (v ? "ON" : "OFF"));
                    } catch (Throwable ignored) {}
                }));
        return modules;
    }

    private static List<Module> buildMovementCategory() {
        List<Module> modules = new ArrayList<>();
        modules.add(new ToggleModule("AI Water Avoidance",
                "Całkowity zakaz wchodzenia do wody (AI Penalty / Zero Water).",
                waterAvoid,
                v -> {
                    waterAvoid = v;
                    try {
                        Baritone.settings().mineAvoidWater.value = v;
                        SettingsUtil.save(Baritone.settings());
                        AiActionLogger.log("GUI", "AI Water Avoidance -> " + (v ? "ON" : "OFF"));
                    } catch (Throwable ignored) {}
                }));
        modules.add(new ToggleModule("Water Surface Buoyancy",
                "Bezwzględne utrzymywanie na powierzchni wody.",
                waterBuoyancy,
                v -> {
                    waterBuoyancy = v;
                    try {
                        Baritone.settings().sprintInWater.value = !v;
                        Baritone.settings().walkOnWaterOnePenalty.value = v ? 1.0 : 3.0;
                        SettingsUtil.save(Baritone.settings());
                        AiActionLogger.log("GUI", "Water Surface Buoyancy -> " + (v ? "ON" : "OFF"));
                    } catch (Throwable ignored) {}
                }));
        modules.add(new ToggleModule("Real-Time Lava Reflex",
                "Błyskawiczny odskok i anulowanie trasy przy lawie.",
                lavaReflex,
                v -> {
                    lavaReflex = v;
                    try {
                        Baritone.settings().mineAvoidLava.value = v;
                        Baritone.settings().strictLiquidCheck.value = v;
                        SettingsUtil.save(Baritone.settings());
                        AiActionLogger.log("GUI", "Lava Reflex -> " + (v ? "ON" : "OFF"));
                    } catch (Throwable ignored) {}
                }));
        modules.add(new SliderModule("Lava Scan Radius",
                "Promień wykrywania lawy w blokach.",
                1.0f, 6.0f, 0.1f, lavaScanRadius, " blk",
                v -> {
                    lavaScanRadius = v;
                    try {
                        Baritone.settings().breakHoldMaxDistance.value = (double) v;
                        SettingsUtil.save(Baritone.settings());
                    } catch (Throwable ignored) {}
                }));
        modules.add(new ToggleModule("Fluid Proximity Buffer",
                "Aktywnie omija wodę i lawę, wyznaczając trasy w bezpiecznej odległości.",
                avoidFluidProximity,
                v -> {
                    avoidFluidProximity = v;
                    try {
                        Baritone.settings().avoidFluidProximity.value = v;
                        SettingsUtil.save(Baritone.settings());
                        AiActionLogger.log("GUI", "Fluid Proximity Buffer -> " + (v ? "ON" : "OFF"));
                    } catch (Throwable ignored) {}
                }));
        modules.add(new SliderModule("Fluid Buffer Distance",
                "Bezpieczny dystans w blokach od brzegów wody i zbiorników lawy.",
                1.0f, 4.0f, 1.0f, fluidAvoidDistance, " blk",
                v -> {
                    fluidAvoidDistance = v;
                    try {
                        Baritone.settings().fluidAvoidDistance.value = Math.round(v);
                        SettingsUtil.save(Baritone.settings());
                    } catch (Throwable ignored) {}
                }));
        modules.add(new ToggleModule("Anti-Suffocation",
                "Zapobiega wchodzeniu w opadający piasek.",
                antiSuffocation,
                v -> {
                    antiSuffocation = v;
                    try {
                        Baritone.settings().avoidUpdatingFallingBlocks.value = v;
                        Baritone.settings().pauseMiningForFallingBlocks.value = v;
                        SettingsUtil.save(Baritone.settings());
                        AiActionLogger.log("GUI", "Anti-Suffocation -> " + (v ? "ON" : "OFF"));
                    } catch (Throwable ignored) {}
                }));
        modules.add(new ToggleModule("Tunnel 1x2 Float Guard",
                "Bezpieczna wysokość w zalanych korytarzach.",
                tunnelFloatGuard,
                v -> {
                    tunnelFloatGuard = v;
                    try {
                        Baritone.settings().assumeWalkOnWater.value = !v;
                        SettingsUtil.save(Baritone.settings());
                    } catch (Throwable ignored) {}
                    AiActionLogger.log("GUI", "Tunnel Float Guard -> " + (v ? "ON" : "OFF"));
                }));
        modules.add(new ToggleModule("AutoLog Low Health",
                "Rozłącza z serwerem, gdy zdrowie spadnie poniżej progu.",
                disconnectOnLowHealth,
                v -> {
                    disconnectOnLowHealth = v;
                    try {
                        Baritone.settings().disconnectOnLowHealth.value = v;
                        SettingsUtil.save(Baritone.settings());
                        AiActionLogger.log("GUI", "AutoLog Low Health -> " + (v ? "ON" : "OFF"));
                    } catch (Throwable ignored) {}
                }));
        modules.add(new SliderModule("AutoLog Hearts",
                "Próg serc aktywujący natychmiastowe rozłączenie.",
                1.0f, 10.0f, 0.5f, disconnectHealthHearts, " serc",
                v -> {
                    disconnectHealthHearts = v;
                    try {
                        Baritone.settings().disconnectHealthHearts.value = (double) v;
                        SettingsUtil.save(Baritone.settings());
                    } catch (Throwable ignored) {}
                }));
        modules.add(new ToggleModule("AutoLog Fall Protect",
                "Rozłącza z serwerem przy upadku z wysokości (>5 kratek).",
                disconnectOnFall,
                v -> {
                    disconnectOnFall = v;
                    try {
                        Baritone.settings().disconnectOnFall.value = v;
                        SettingsUtil.save(Baritone.settings());
                        AiActionLogger.log("GUI", "AutoLog Fall Protect -> " + (v ? "ON" : "OFF"));
                    } catch (Throwable ignored) {}
                }));
        modules.add(new ToggleModule("MLG Water Clutch",
                "Automatyczne stawianie wody pod sobą przy upadku z wysokości (0 obrażeń).",
                autoWaterClutch,
                v -> {
                    autoWaterClutch = v;
                    try {
                        Baritone.settings().autoWaterClutch.value = v;
                        Baritone.settings().allowWaterBucketFall.value = v;
                        SettingsUtil.save(Baritone.settings());
                        AiActionLogger.log("GUI", "MLG Water Clutch -> " + (v ? "ON" : "OFF"));
                    } catch (Throwable ignored) {}
                }));
        modules.add(new SliderModule("Fall Protect Distance",
                "Maksymalna wysokość upadku przed rozłączeniem.",
                3.0f, 20.0f, 1.0f, disconnectFallDistance, " blk",
                v -> {
                    disconnectFallDistance = v;
                    try {
                        Baritone.settings().disconnectFallDistance.value = (double) v;
                        SettingsUtil.save(Baritone.settings());
                    } catch (Throwable ignored) {}
                }));
        return modules;
    }

    private static List<Module> buildVisualsCategory() {
        List<Module> modules = new ArrayList<>();
        modules.add(new ToggleModule("Live Diagnostics HUD",
                "Panel stanu bota na ekranie głównym.",
                hudEnabled,
                v -> {
                    hudEnabled = v;
                    try {
                        Baritone.settings().showHudOverlay.value = v;
                        SettingsUtil.save(Baritone.settings());
                        AiActionLogger.log("GUI", "Live Diagnostics HUD -> " + (v ? "ON" : "OFF"));
                    } catch (Throwable ignored) {}
                }));
        modules.add(new ToggleModule("Ore ESP / Tracers",
                "Podświetla żelazo, złoto, diamenty i szczątki.",
                oreEsp,
                v -> {
                    oreEsp = v;
                    AiActionLogger.log("GUI", "Ore ESP -> " + (v ? "ON" : "OFF"));
                }));
        modules.add(new CycleModule("ESP Mode",
                "Sposób podświetlania rud.",
                new String[]{"OUTLINE", "TRACERS", "BOTH"}, espMode,
                i -> {
                    espMode = i;
                    AiActionLogger.log("GUI", "ESP Mode -> " + (i == 0 ? "OUTLINE" : (i == 1 ? "TRACERS" : "BOTH")));
                }));
        modules.add(new ToggleModule("Path Trail Renderer",
                "Neonowa linia przewidywanej trasy Baritone.",
                pathTrail,
                v -> {
                    pathTrail = v;
                    try {
                        Baritone.settings().renderPath.value = v;
                        SettingsUtil.save(Baritone.settings());
                        AiActionLogger.log("GUI", "Path Trail Renderer -> " + (v ? "ON" : "OFF"));
                    } catch (Throwable ignored) {}
                }));
        modules.add(new SliderModule("HUD Scale",
                "Skala panelu diagnostycznego HUD.",
                50.0f, 200.0f, 1.0f, hudScale, "%",
                v -> {
                    hudScale = v;
                    try {
                        Baritone.settings().hudScale.value = (double) (v / 100.0f);
                        SettingsUtil.save(Baritone.settings());
                    } catch (Throwable ignored) {}
                }));
        modules.add(new SliderModule("Dark Mode Intensity",
                "Przezroczystość interfejsu GUI.",
                50.0f, 100.0f, 1.0f, guiOpacity * 100.0f, "%",
                v -> {
                    guiOpacity = v / 100.0f;
                }));
        return modules;
    }

    // ========================================================================
    //  SEKCJA 10 · POMOST DO BARITONE
    // ========================================================================
    public static void pushAllToBaritone() {
        applyMiningSettings();
        applyGrimSettings();
        applyAutomationSettings();
        applyMovementSettings();
        applyVisualSettings();
    }

    public static void applyMiningSettings() {
        try {
            baritone.api.Settings s = Baritone.settings();
            if (s == null) return;
            s.allowBreak.value = true;
            s.fastBreak.value = fastBreakInstamine;
            s.autoDetectEfficiency10.value = autoDetectEfficiency;
            s.continuousBreaking.value = fastBreakInstamine || multiBlockQueue;
            s.blockReachDistance.value = miningRange;
            SettingsUtil.save(s);
        } catch (Throwable ignored) {}
    }

    public static void applyGrimSettings() {
        try {
            baritone.api.Settings s = Baritone.settings();
            if (s == null) return;
            s.antiCheatCompatibility.value = strictPlace;
            s.antiCheatCompat.value = strictPlace;
            s.humanizedInteractDelay.value = strictPlace;
            s.strictRaytraceOnly.value = raytraceLOS;
            s.smoothRotation.value = smoothRotations && (rotationMode != 0);
            s.humanizedRotations.value = smoothRotations && (rotationMode == 2);
            s.maxRotationSpeedPerTick.value = rotationSpeed;
            s.autoEatPauseInCombat.value = combatLogDetector;
            SettingsUtil.save(s);
        } catch (Throwable ignored) {}
    }

    public static void applyAutomationSettings() {
        try {
            baritone.api.Settings s = Baritone.settings();
            if (s == null) return;
            s.anarchiaMode.value = anarchiaMode;
            s.autoDropTrash.value = autoDropTrash;
            s.autoSortInventory.value = autoSortInventory;
            s.autoLockResource.value = itemLockProtection;
            s.autoDropDelayMs.value = Math.max(50, Math.round(dropDelay * 50.0f));
            s.autoSortDelayTicks.value = Math.max(1, Math.round(dropDelay));
            s.autoEat.value = autoEat;
            s.autoEatThreshold.value = Math.round(autoEatThreshold);
            s.autoHomeOnFull.value = autoHomeOnFull;
            SettingsUtil.save(s);
        } catch (Throwable ignored) {}
    }

    public static void applyMovementSettings() {
        try {
            baritone.api.Settings s = Baritone.settings();
            if (s == null) return;
            s.mineAvoidWater.value = waterAvoid;
            s.sprintInWater.value = !waterBuoyancy;
            s.walkOnWaterOnePenalty.value = waterBuoyancy ? 1.0 : 3.0;
            s.mineAvoidLava.value = lavaReflex;
            s.strictLiquidCheck.value = lavaReflex;
            s.breakHoldMaxDistance.value = (double) lavaScanRadius;
            s.avoidFluidProximity.value = avoidFluidProximity;
            s.fluidAvoidDistance.value = Math.round(fluidAvoidDistance);
            s.avoidUpdatingFallingBlocks.value = antiSuffocation;
            s.pauseMiningForFallingBlocks.value = antiSuffocation;
            s.assumeWalkOnWater.value = !tunnelFloatGuard;
            s.disconnectOnLowHealth.value = disconnectOnLowHealth;
            s.disconnectHealthHearts.value = (double) disconnectHealthHearts;
            s.disconnectOnFall.value = disconnectOnFall;
            s.disconnectFallDistance.value = (double) disconnectFallDistance;
            s.autoWaterClutch.value = autoWaterClutch;
            s.allowWaterBucketFall.value = autoWaterClutch;
            SettingsUtil.save(s);
        } catch (Throwable ignored) {}
    }

    public static void applyVisualSettings() {
        try {
            baritone.api.Settings s = Baritone.settings();
            if (s == null) return;
            s.showHudOverlay.value = hudEnabled;
            s.renderPath.value = pathTrail;
            s.hudScale.value = (double) (hudScale / 100.0f);
            SettingsUtil.save(s);
        } catch (Throwable ignored) {}
    }

    // ========================================================================
    //  SEKCJA 11 · PRYMITYWY RYSOWANIA (czysty GuiGraphics)
    // ========================================================================

    /** Zaokrąglony prostokąt — narożniki jako schodki ćwiartek okręgu. */
    private static void drawRoundedRect(GuiGraphics g, int x1, int y1, int x2, int y2,
                                        int radius, int color) {
        int w = x2 - x1;
        int h = y2 - y1;
        if (w <= 0 || h <= 0) return;
        if (radius > w / 2) radius = w / 2;
        if (radius > h / 2) radius = h / 2;
        if (radius <= 0) {
            g.fill(x1, y1, x2, y2, color);
            return;
        }

        g.fill(x1 + radius, y1, x2 - radius, y2, color);
        g.fill(x1, y1 + radius, x1 + radius, y2 - radius, color);
        g.fill(x2 - radius, y1 + radius, x2, y2 - radius, color);

        for (int j = 0; j < radius; j++) {
            int dx = cornerDx(radius, j);
            if (dx <= 0) continue;
            int yt = y1 + j;
            int yb = y2 - 1 - j;
            int leftX = x1 + radius - dx;
            int rightX = x2 - radius;
            g.fill(leftX, yt, leftX + dx, yt + 1, color);
            g.fill(rightX, yt, rightX + dx, yt + 1, color);
            g.fill(leftX, yb, leftX + dx, yb + 1, color);
            g.fill(rightX, yb, rightX + dx, yb + 1, color);
        }
    }

    /** Kontur zaokrąglonego prostokąta (1 px), spójny z drawRoundedRect. */
    private static void drawOutline(GuiGraphics g, int x1, int y1, int x2, int y2,
                                    int radius, int color) {
        int w = x2 - x1;
        int h = y2 - y1;
        if (w <= 0 || h <= 0) return;
        if (radius <= 0) {
            g.renderOutline(x1, y1, x2, y2, color);
            return;
        }
        if (radius > w / 2) radius = w / 2;
        if (radius > h / 2) radius = h / 2;

        int edgeDx = cornerDx(radius, 0);
        g.fill(x1 + radius - edgeDx, y1, x2 - radius + edgeDx, y1 + 1, color);
        g.fill(x1 + radius - edgeDx, y2 - 1, x2 - radius + edgeDx, y2, color);
        g.fill(x1, y1 + radius, x1 + 1, y2 - radius, color);
        g.fill(x2 - 1, y1 + radius, x2, y2 - radius, color);

        for (int j = 1; j < radius; j++) {
            int dx = cornerDx(radius, j);
            if (dx <= 0) continue;
            int leftX = x1 + radius - dx;
            int rightX = x2 - radius + dx - 1;
            g.fill(leftX, y1 + j, leftX + 1, y1 + j + 1, color);
            g.fill(rightX, y1 + j, rightX + 1, y1 + j + 1, color);
            g.fill(leftX, y2 - 1 - j, leftX + 1, y2 - j, color);
            g.fill(rightX, y2 - 1 - j, rightX + 1, y2 - j, color);
        }
    }

    /** Poziomy gradient (lewo -> prawo) składany z pionowych pasów fill(). */
    private static void drawGradientRect(GuiGraphics g, int x1, int y1, int x2, int y2,
                                         int colorFrom, int colorTo, int steps) {
        if (x2 <= x1 || y2 <= y1) return;
        int span = x2 - x1;
        int n = clamp(steps, 1, span);
        for (int i = 0; i < n; i++) {
            int stripA = x1 + Math.round(span * (float) i / (float) n);
            int stripB = x1 + Math.round(span * (float) (i + 1) / (float) n);
            if (stripB <= stripA) continue;
            float t = n == 1 ? 0.0f : (float) i / (float) (n - 1);
            g.fill(stripA, y1, stripB, y2, lerpColor(colorFrom, colorTo, t));
        }
    }

    /** Panel: zaokrąglone tło + 1 px obramowanie (efekt warstwowy). */
    private static void drawBorderedPanel(GuiGraphics g, int x1, int y1, int x2, int y2,
                                          int radius, int bg, int border) {
        drawRoundedRect(g, x1, y1, x2, y2, radius, border);
        drawRoundedRect(g, x1 + 1, y1 + 1, x2 - 1, y2 - 1, Math.max(0, radius - 1), bg);
    }

    /** Ikona pixel-art ('X' = zapalony piksel), gwarantowana zgodność z vanilla fontem. */
    private static void drawIcon(GuiGraphics g, String[] pattern, int x, int y, int color) {
        for (int row = 0; row < pattern.length; row++) {
            String line = pattern[row];
            for (int col = 0; col < line.length(); col++) {
                if (line.charAt(col) == 'X') {
                    g.fill(x + col, y + row, x + col + 1, y + row + 1, color);
                }
            }
        }
    }

    /** Szerokość ćwiartki okręgu narożnika dla wiersza `row` (piksle środkowe). */
    private static int cornerDx(int radius, int row) {
        double dy = radius - (row + 0.5);
        double inner = (double) radius * radius - dy * dy;
        if (inner <= 0.0) return 0;
        return clamp((int) Math.round(Math.sqrt(inner)), 0, radius);
    }

    // ========================================================================
    //  SEKCJA 12 · NARZĘDZIA
    // ========================================================================
    private static boolean inRect(int mx, int my, int x1, int y1, int x2, int y2) {
        return mx >= x1 && mx < x2 && my >= y1 && my < y2;
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static int lerpColor(int from, int to, float t) {
        int fa = (from >>> 24) & 0xFF, fr = (from >> 16) & 0xFF, fg = (from >> 8) & 0xFF, fb = from & 0xFF;
        int ta = (to >>> 24) & 0xFF, tr = (to >> 16) & 0xFF, tg = (to >> 8) & 0xFF, tb = to & 0xFF;
        int a = fa + Math.round((ta - fa) * t);
        int r = fr + Math.round((tr - fr) * t);
        int gg = fg + Math.round((tg - fg) * t);
        int b = fb + Math.round((tb - fb) * t);
        return (a << 24) | (r << 16) | (gg << 8) | b;
    }

    private static int scaleAlpha(int color, float factor) {
        int a = clamp((int) (((color >>> 24) & 0xFF) * factor), 0, 255);
        return (a << 24) | (color & 0x00FFFFFF);
    }

    /** Skala alfy wg suwaka „Dark Mode Intensity”. */
    private static int op(int color) {
        return scaleAlpha(color, clamp(guiOpacity, 0.5f, 1.0f));
    }

    private String trunc(String text, int maxWidth) {
        if (maxWidth <= 0) return "";
        if (this.font.width(text) <= maxWidth) return text;
        String suffix = "...";
        int keep = text.length();
        while (keep > 0 && this.font.width(text.substring(0, keep)) + this.font.width(suffix) > maxWidth) {
            keep--;
        }
        return text.substring(0, keep) + suffix;
    }

    private static float contentHeight(Category cat) {
        return HEADER_H + cat.modules.size() * CARD_STEP - 6 + 12;
    }

    private static float maxScroll(Category cat) {
        return Math.max(0.0f, contentHeight(cat) - CONTENT_H);
    }

    private void updateFps() {
        this.fpsFrames++;
        long now = System.currentTimeMillis();
        if (now - this.fpsClock >= 1000L) {
            this.fpsDisplay = this.fpsFrames;
            this.fpsFrames = 0;
            this.fpsClock = now;
        }
    }

    private void clampWindow() {
        int minX = -WINDOW_W + 120;
        this.windowX = clamp(this.windowX, minX, Math.max(minX, this.width - 120));
        this.windowY = clamp(this.windowY, 2, Math.max(2, this.height - 60));
    }

    // ========================================================================
    //  SEKCJA 13 · MODEL WIDGETÓW
    // ========================================================================
    private abstract static class Module {
        final String name;
        final String description;

        Module(String name, String description) {
            this.name = name;
            this.description = description;
        }

        abstract void render(GuiGraphics g, Font font, int cardX, int cardY, int mouseX, int mouseY);

        abstract boolean onClick(BaritoneBypassScreen screen, double mouseX, double mouseY,
                                 int button, int cardX, int cardY);

        abstract int nameMax();

        abstract int descMax();
    }

    /** Nowoczesny przełącznik pigułkowy (slider ON/OFF) z animowanym pokrętłem. */
    private static final class ToggleModule extends Module {
        private final Consumer<Boolean> onToggle;
        boolean enabled;
        float knobAnimation;

        ToggleModule(String name, String description, boolean initial, Consumer<Boolean> onToggle) {
            super(name, description);
            this.enabled = initial;
            this.onToggle = onToggle;
            this.knobAnimation = initial ? 1.0f : 0.0f;
        }

        void set(boolean value) {
            this.enabled = value;
            if (this.onToggle != null) this.onToggle.accept(Boolean.valueOf(value));
        }

        @Override
        void render(GuiGraphics g, Font font, int cardX, int cardY, int mouseX, int mouseY) {
            int px = cardX + TOGGLE_X;
            int py = cardY + TOGGLE_Y;

            String state = this.enabled ? "ON" : "OFF";
            g.drawString(font, state, px - 9 - font.width(state), py + 4,
                    this.enabled ? C_CYAN : C_TEXT_FAINT, false);

            float target = this.enabled ? 1.0f : 0.0f;
            this.knobAnimation += (target - this.knobAnimation) * 0.45f;
            if (Math.abs(target - this.knobAnimation) < 0.02f) this.knobAnimation = target;

            if (this.enabled) {
                drawRoundedRect(g, px - 2, py - 2, px + TOGGLE_W + 2, py + TOGGLE_H + 2, 10, 0x2E00E5FF);
                drawRoundedRect(g, px, py, px + TOGGLE_W, py + TOGGLE_H, 9, C_CYAN);
            } else {
                drawRoundedRect(g, px, py, px + TOGGLE_W, py + TOGGLE_H, 9, C_INACTIVE);
            }

            int travel = TOGGLE_W - 4 - 14;
            int kx = px + 2 + Math.round(this.knobAnimation * travel);
            drawRoundedRect(g, kx, py + 2, kx + 14, py + TOGGLE_H - 2, 7,
                    this.enabled ? 0xFFFFFFFF : 0xFF66758C);
        }

        @Override
        boolean onClick(BaritoneBypassScreen screen, double mouseX, double mouseY,
                        int button, int cardX, int cardY) {
            if (button != 0) return false;
            set(!this.enabled);
            playClickSound();
            if (screen != null) {
                screen.refreshFromSettings();
            }
            return true;
        }

        @Override
        int nameMax() {
            return TOGGLE_X - 42;
        }

        @Override
        int descMax() {
            return TOGGLE_X - 42;
        }
    }

    /** Płynny suwak wartości z krokiem, jednostką i gradientowym wypełnieniem. */
    private static final class SliderModule extends Module {
        private final float min;
        private final float max;
        private final float step;
        private final String unit;
        private final Consumer<Float> onChange;
        float value;

        SliderModule(String name, String description, float min, float max, float step,
                     float initial, String unit, Consumer<Float> onChange) {
            super(name, description);
            this.min = min;
            this.max = max;
            this.step = step > 0.0f ? step : 0.01f;
            this.value = clamp(initial, min, max);
            this.unit = unit == null ? "" : unit;
            this.onChange = onChange;
        }

        String displayValue() {
            String number = this.step >= 1.0f
                    ? String.valueOf(Math.round(this.value))
                    : String.format(Locale.US, "%.1f", this.value);
            return number + this.unit;
        }

        void setFromMouse(double mouseX, int trackX) {
            float t = clamp((float) (mouseX - (double) trackX) / (float) SLIDER_W, 0.0f, 1.0f);
            float raw = this.min + t * (this.max - this.min);
            float snapped = Math.round(raw / this.step) * this.step;
            this.value = clamp(snapped, this.min, this.max);
            if (this.onChange != null) this.onChange.accept(Float.valueOf(this.value));
        }

        @Override
        void render(GuiGraphics g, Font font, int cardX, int cardY, int mouseX, int mouseY) {
            int tx = cardX + SLIDER_X;
            int ty = cardY + SLIDER_TRACK_Y;

            String text = displayValue();
            g.drawString(font, text, cardX + CARD_W - 12 - font.width(text), cardY + 9,
                    C_TEXT_MAIN, false);

            boolean hot = inRect(mouseX, mouseY, tx - 6, cardY + 12, tx + SLIDER_W + 6, cardY + 34);

            drawRoundedRect(g, tx, ty, tx + SLIDER_W, ty + SLIDER_TRACK_H, 2, C_INACTIVE);

            float t = this.max > this.min ? (this.value - this.min) / (this.max - this.min) : 0.0f;
            int fillW = Math.round(clamp(t, 0.0f, 1.0f) * (float) SLIDER_W);
            if (fillW > 0) {
                drawGradientRect(g, tx, ty, tx + fillW, ty + SLIDER_TRACK_H, C_CYAN, C_PURPLE, 12);
            }

            int kx = clamp(tx + fillW - 5, tx - 5, tx + SLIDER_W - 5);
            int ky = ty - 6;
            if (hot) {
                drawRoundedRect(g, kx - 2, ky - 2, kx + 12, ky + 18, 6, 0x3300E5FF);
            }
            drawBorderedPanel(g, kx, ky, kx + 10, ky + 16, 4,
                    hot ? 0xFFFFFFFF : 0xFFE8EEF8, hot ? C_CYAN : C_BORDER);
        }

        @Override
        boolean onClick(BaritoneBypassScreen screen, double mouseX, double mouseY,
                        int button, int cardX, int cardY) {
            if (button != 0) return false;
            int tx = cardX + SLIDER_X;
            if (mouseX >= tx - 12 && mouseX <= tx + SLIDER_W + 12
                    && mouseY >= cardY + 6 && mouseY <= cardY + CARD_H - 6) {
                screen.draggingSlider = this;
                setFromMouse(mouseX, tx);
                playClickSound();
                return true;
            }
            return false;
        }

        @Override
        int nameMax() {
            return CARD_W - 24 - 74;
        }

        @Override
        int descMax() {
            return SLIDER_X - 20;
        }
    }

    /** Cycle-button: LMB = następny tryb, RMB = poprzedni. */
    private static final class CycleModule extends Module {
        private final String[] modes;
        private final Consumer<Integer> onChange;
        int index;

        CycleModule(String name, String description, String[] modes, int initialIndex,
                    Consumer<Integer> onChange) {
            super(name, description);
            this.modes = modes;
            this.index = clamp(initialIndex, 0, modes.length - 1);
            this.onChange = onChange;
        }

        String currentMode() {
            return this.modes[this.index];
        }

        void cycle(int direction) {
            this.index = Math.floorMod(this.index + direction, this.modes.length);
            if (this.onChange != null) this.onChange.accept(Integer.valueOf(this.index));
        }

        @Override
        void render(GuiGraphics g, Font font, int cardX, int cardY, int mouseX, int mouseY) {
            int px = cardX + CYCLE_X;
            int py = cardY + CYCLE_Y;
            boolean hot = inRect(mouseX, mouseY, px, py, px + CYCLE_W, py + CYCLE_H);

            if (hot) {
                drawRoundedRect(g, px - 2, py - 2, px + CYCLE_W + 2, py + CYCLE_H + 2, 10, 0x2400E5FF);
            }
            drawBorderedPanel(g, px, py, px + CYCLE_W, py + CYCLE_H, 9,
                    op(C_CARD_BG_ALT), hot ? C_BORDER_HOT : C_BORDER);

            String mode = currentMode();
            g.drawString(font, mode, px + (CYCLE_W - font.width(mode)) / 2, py + 4,
                    C_TEXT_MAIN, false);
            g.drawString(font, "<", px + 9, py + 4, hot ? C_CYAN : C_TEXT_DIM, false);
            g.drawString(font, ">", px + CYCLE_W - 14, py + 4, hot ? C_CYAN : C_TEXT_DIM, false);
        }

        @Override
        boolean onClick(BaritoneBypassScreen screen, double mouseX, double mouseY,
                        int button, int cardX, int cardY) {
            if (button != 0 && button != 1) return false;
            int px = cardX + CYCLE_X;
            int py = cardY + CYCLE_Y;
            if (mouseX >= px - 10 && mouseX <= px + CYCLE_W + 10
                    && mouseY >= py - 5 && mouseY <= py + CYCLE_H + 5) {
                cycle(button == 1 ? -1 : 1);
                playClickSound();
                if (screen != null) {
                    screen.refreshFromSettings();
                }
                return true;
            }
            return false;
        }

        @Override
        int nameMax() {
            return CYCLE_X - 24;
        }

        @Override
        int descMax() {
            return CYCLE_X - 24;
        }
    }

    /** Zakładka: nazwa, ikona, lista modułów + własny stan scrolla. */
    private static final class Category {
        final String name;
        final String[] icon;
        final List<Module> modules;
        float scroll;
        float scrollTarget;

        Category(String name, String[] icon, List<Module> modules, float initialScroll) {
            this.name = name;
            this.icon = icon;
            this.modules = modules;
            this.scroll = initialScroll;
            this.scrollTarget = initialScroll;
        }
    }

    // ========================================================================
    //  SEKCJA 14 · BEZPIECZNY MOST DO BARITONE (pure reflection)
    //  Równoważne wywołaniom Baritone.settings().allowBreak.value = ...
    //  Wykonywane wyłącznie wtedy, gdy Baritone istnieje na classpath.
    // ========================================================================
    public static final class BaritoneBridge {

        private static long lastBotPoll = 0L;
        private static boolean polledBotActive = false;

        private BaritoneBridge() {
        }

        public static boolean linked() {
            try {
                return Baritone.settings() != null;
            } catch (Throwable t) {
                return false;
            }
        }

        public static void setBoolean(String key, boolean value) {
            set(key, Boolean.valueOf(value));
        }

        public static void setInt(String key, int value) {
            set(key, Integer.valueOf(value));
        }

        /** Próbuje Float, potem Double, potem Integer — dopasowanie do typu Setting<T>. */
        public static void setNumber(String key, float value) {
            if (set(key, Float.valueOf(value))) return;
            if (set(key, Double.valueOf(value))) return;
            set(key, Integer.valueOf(Math.round(value)));
        }

        private static boolean set(String key, Object value) {
            try {
                baritone.api.Settings s = Baritone.settings();
                if (s == null) return false;
                java.lang.reflect.Field f = s.getClass().getField(key);
                Object setting = f.get(s);
                if (setting != null) {
                    setting.getClass().getField("value").set(setting, value);
                    SettingsUtil.save(s);
                    return true;
                }
            } catch (Throwable ignored) {
            }
            return false;
        }

        /** true, gdy bot realnie pracuje (Baritone mine process) lub flaga BOT_ACTIVE. */
        public static boolean isBotActive() {
            if (BOT_ACTIVE) return true;
            long now = System.currentTimeMillis();
            if (now - lastBotPoll > 300L) {
                lastBotPoll = now;
                polledBotActive = pollBotActive();
            }
            return polledBotActive;
        }

        private static boolean pollBotActive() {
            try {
                baritone.api.IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
                if (baritone == null) return false;
                if (baritone.getMineProcess().isActive()) return true;
                if (baritone.getPathingBehavior().hasPath()) return true;
                if (baritone.getFollowProcess().isActive()) return true;
                if (baritone.getBuilderProcess().isActive()) return true;
                if (baritone.getCustomGoalProcess().isActive()) return true;
            } catch (Throwable ignored) {
            }
            return false;
        }
    }
}
