package com.astra.revolution.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Astra Client / Baritone Revolution (Minecraft 1.21.4 Mojmap, Java 21)
 * Moduł HUD "Keybinds" — Unified Liquid Lilac & Obsidian Glass Edition.
 *
 * Zachowanie:
 * - Wyświetla wyłącznie aktywne (odpalone) moduły.
 * - Gdy wszystkie moduły są wyłączone, widżet płynnie się zwija i całkowicie znika z ekranu.
 * - Przy otwartym ClickGUI (BaritoneBypassScreen) pokazuje lekki podgląd umożliwiający przeciąganie.
 * - 100% Zero-Allocation w pętli render() oraz onClientTick().
 */
public final class KeybindsHudRenderer {

    // =========================================================================
    // DESIGN SYSTEM: UNIFIED LIQUID LILAC & OBSIDIAN GLASS
    // =========================================================================
    public static final int COL_BG           = 0xD6090912; // Głębokie obsydianowe szkło (~84% alpha)
    public static final int COL_BORDER       = 0x54C4B5FD; // Lawendowa krawędź bańki szkła
    public static final int COL_HEADER_BG    = 0x1AC4B5FD; // Subtelna szklana kopuła nagłówka
    public static final int COL_ACCENT_LILAC = 0xFFD4C8FF; // Główna świetlista pastelowa lawenda
    public static final int COL_TEXT_WHITE   = 0xFFFFFFFF; // Czysta biel aktywnych nazw i klawiszy
    public static final int COL_TEXT_DIM     = 0xFF9F98BD; // Przygaszona lawenda (elementy pomocnicze)
    public static final int COL_ACTIVE_GREEN = 0xFFC4B5FD; // Zmapowane na spójny akcent lawendowy
    public static final int COL_BADGE_BG     = 0xFF161426; // Obsydianowe wnętrze klawisza 3D

    // Monochromatyczna rodzina lawendowa (spójny kolor bez pstrokatych wstawek)
    private static final int COL_LILAC_CORE          = 0xFFD8CEFF; // Jasny rdzeń diody i akcentów
    private static final int COL_LILAC_BRIGHT        = 0xFFC4B5FD; // Podstawowa neonowa lawenda
    private static final int COL_LILAC_MUTED         = 0xFF9E92D4; // Stonowana lawenda dla trybu TGL/HOLD
    private static final int COL_LILAC_GLOW          = 0x46A78BFA; // Miękka poświata neonowa
    private static final int COL_ROW_ACTIVE_BG       = 0x16C4B5FD; // Delikatna szklana poświata wiersza
    private static final int COL_ROW_ACCENT_BAR      = 0xDDC4B5FD; // Pionowy pasek świetlny po lewej wiersza

    // Warstwy optyczne szkła Liquid Glass
    private static final int COL_DROP_SHADOW         = 0x4A020207;
    private static final int COL_INNER_GLASS_RIM     = 0x24FFFFFF; // Wewnętrzny 1px połysk menisku
    private static final int COL_BORDER_HOVER        = 0x90DFD6FF;
    private static final int COL_DIVIDER_HAIRLINE    = 0x2EC4B5FD;
    private static final int COL_COUNTER_PILL_BG     = 0x24C4B5FD;
    private static final int COL_COUNTER_PILL_BORDER = 0x48C4B5FD;
    private static final int COL_LED_INACTIVE        = 0xFF4B4663;

    // Keycapy 3D w jednej lawendowej estetyce
    private static final int COL_KEYCAP_LIP          = 0x96040409;
    private static final int COL_KEYCAP_ACTIVE_BG    = 0xFF1D1933;
    private static final int COL_KEYCAP_ACTIVE_BDR   = 0x7EC4B5FD;
    private static final int COL_KEYCAP_PRESS_BG     = 0xFF2D264D;
    private static final int COL_KEYCAP_PRESS_BORDER = 0xFFE6DFFF;

    // =========================================================================
    // STAŁE UKŁADU I STAN POZYCJI HUD
    // =========================================================================
    public static int hudX = 20;
    public static int hudY = 120;
    public static boolean enabled = true;
    public static boolean onlyShowActive = true; // Domyślnie widoczne TYLKO odpalone moduły!

    private static final int MIN_WIDTH        = 152;
    private static final int HEADER_HEIGHT    = 20;
    private static final int ROW_HEIGHT       = 16;
    private static final int PADDING_X        = 9;
    private static final int BODY_PAD_TOP     = 4;
    private static final int BODY_PAD_BOT     = 5;
    private static final int LED_TO_NAME_GAP  = 10;
    private static final int NAME_TO_KEY_GAP  = 14;
    private static final int KEY_TO_MODE_GAP  = 6;
    private static final float CARD_RADIUS    = 7.5f;
    private static final float KEYCAP_RADIUS  = 3.5f;

    // Stan przeciągania myszką w ClickGUI
    private static boolean dragging = false;
    private static int dragOffsetX = 0;
    private static int dragOffsetY = 0;
    private static int lastWidth = MIN_WIDTH;
    private static int lastHeight = HEADER_HEIGHT + 24;

    // Płynne animacje całej kapsuły HUD (Zero-Alloc)
    private static float widgetAlphaAnim = 0.0f;
    private static float animatedWidth   = MIN_WIDTH;
    private static float headerHoverAnim = 0.0f;
    private static boolean widthInitialized = false;

    // Timer klatki (prymitywy Zero-Alloc)
    private static long lastFrameNanos = 0L;
    private static float elapsedSeconds = 0.0f;

    public static KeybindEntry listeningEntry = null;

    public static boolean isListening() {
        return listeningEntry != null;
    }

    public static void handleListeningKey(int keyCode) {
        if (listeningEntry != null) {
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN) {
                KeybindManager.unregister(listeningEntry.name);
            } else {
                listeningEntry.setKeyCode(keyCode);
                var card = BaritoneBypassScreen.getCardByName(listeningEntry.name);
                if (card != null) {
                    card.keyCode = keyCode;
                    BaritoneBypassScreen.syncCardToSettings(card);
                }
            }
            listeningEntry = null;
        }
    }

    // Pre-buforowane liczniki aktywnych modułów "1", "2", ... dla 0 alokacji w render()
    private static final int MAX_CACHED_COUNT = 64;
    private static final String[] COUNT_CACHE = new String[MAX_CACHED_COUNT + 1];
    private static final String TITLE_TEXT = "KEYBINDS";
    private static final String EMPTY_PREVIEW_TEXT = "Brak aktywnych";

    private static volatile boolean useSdfRenderer = true;

    static {
        for (int i = 0; i <= MAX_CACHED_COUNT; i++) {
            COUNT_CACHE[i] = Integer.toString(i);
        }
        KeybindManager.registerDefaults();
    }

    private KeybindsHudRenderer() {
    }

    // =========================================================================
    // GŁÓWNA METODA RENDEROWANIA HUD (AUTO-HIDE & UNIFIED LILAC GLASS)
    // =========================================================================
    public static void render(GuiGraphics g, float partialTick) {
        if (!enabled || g == null) {
            return;
        }

        try {
            final Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.font == null) {
                return;
            }
            if (mc.getDebugOverlay().showDebugScreen()) {
                return;
            }

            final Font font = mc.font;
            final boolean inClickGui = mc.screen instanceof BaritoneBypassScreen;

            if (dragging && !inClickGui) {
                dragging = false;
            }

            // Odpytywanie fizycznego stanu klawiszy w czasie rzeczywistym
            KeybindManager.onClientTick();

            // Delta czasu w sekundach (Zero-Alloc)
            final long now = System.nanoTime();
            float dt = (lastFrameNanos == 0L) ? 0.008f : (now - lastFrameNanos) * 1.0e-9f;
            if (dt < 0.0f) dt = 0.0f;
            if (dt > 0.1f) dt = 0.1f;
            lastFrameNanos = now;
            elapsedSeconds += dt;
            if (elapsedSeconds > 6283.1853f) {
                elapsedSeconds -= 6283.1853f;
            }

            final float rowLerpSpeed    = Math.min(1.0f, dt * 16.0f);
            final float widgetLerpSpeed = Math.min(1.0f, dt * 14.0f);
            final float pressLerpSpeed  = Math.min(1.0f, dt * 30.0f);

            final List<KeybindEntry> entries = KeybindManager.ENTRIES;
            final int totalEntries = entries.size();

            final int modeColW = Math.max(font.width("HOLD"), font.width("TGL"));

            int activeCount = 0;
            int maxNameW = 54;
            int maxKeycapW = 16;
            float animatedRowsCount = 0.0f;

            // 1. Przebieg pomiarowo-animacyjny
            for (int i = 0; i < totalEntries; i++) {
                final KeybindEntry entry = entries.get(i);
                final boolean isActive = entry.isActive();
                final boolean isPressed = entry.physicallyHeld || KeybindManager.isKeyPhysicallyDown(entry.keyCode);

                if (isActive) {
                    activeCount++;
                }

                // Widoczność danego wiersza (1.0 gdy odpalony lub gdy wyłączono filtrowanie)
                final boolean shouldShowRow = !onlyShowActive || isActive;
                final float targetVis = shouldShowRow ? 1.0f : 0.0f;
                entry.visibilityAnim += (targetVis - entry.visibilityAnim) * rowLerpSpeed;
                if (entry.visibilityAnim < 0.004f) entry.visibilityAnim = 0.0f;
                else if (entry.visibilityAnim > 0.996f) entry.visibilityAnim = 1.0f;

                final float targetAct = isActive ? 1.0f : 0.0f;
                entry.activeAnim += (targetAct - entry.activeAnim) * rowLerpSpeed;
                if (entry.activeAnim < 0.004f) entry.activeAnim = 0.0f;
                else if (entry.activeAnim > 0.996f) entry.activeAnim = 1.0f;

                final float targetPress = isPressed ? 1.0f : 0.0f;
                entry.pressAnim += (targetPress - entry.pressAnim) * pressLerpSpeed;
                if (entry.pressAnim < 0.004f) entry.pressAnim = 0.0f;
                else if (entry.pressAnim > 0.996f) entry.pressAnim = 1.0f;

                if (entry.visibilityAnim > 0.005f) {
                    animatedRowsCount += entry.visibilityAnim;

                    final int nameW = font.width(entry.name);
                    if (nameW > maxNameW) {
                        maxNameW = nameW;
                    }

                    final int keyW = font.width(entry.keyName) + 8;
                    if (keyW > maxKeycapW) {
                        maxKeycapW = keyW;
                    }
                }
            }

            // Czy cały widżet powinien być widoczny?
            // Jeśli onlyShowActive == true i żaden moduł nie jest odpalony -> znika (chyba że otwarte ClickGUI)
            final boolean hasContentToShow = !onlyShowActive ? (totalEntries > 0) : (activeCount > 0);
            final boolean shouldShowWidget = hasContentToShow || inClickGui;

            final float targetWidgetAlpha = shouldShowWidget ? 1.0f : 0.0f;
            widgetAlphaAnim += (targetWidgetAlpha - widgetAlphaAnim) * widgetLerpSpeed;
            if (widgetAlphaAnim < 0.004f) widgetAlphaAnim = 0.0f;
            else if (widgetAlphaAnim > 0.996f) widgetAlphaAnim = 1.0f;

            // Całkowite wyjście z renderowania gdy wszystkie moduły są wyłączone i animacja dobiegła końca
            if (widgetAlphaAnim <= 0.005f && animatedRowsCount <= 0.01f && !inClickGui) {
                widthInitialized = false;
                return;
            }

            // Wyliczenie docelowej szerokości
            final String counterText = getCachedCount(activeCount);
            final int counterTextW = font.width(counterText);
            final int counterPillW = counterTextW + 13;
            final int headerMinW = PADDING_X + 15 + font.width(TITLE_TEXT) + 12 + counterPillW + PADDING_X + (inClickGui ? 10 : 0);

            final int gridRowW = PADDING_X + LED_TO_NAME_GAP + maxNameW + NAME_TO_KEY_GAP + maxKeycapW + KEY_TO_MODE_GAP + modeColW + PADDING_X;
            final float targetBoxW = Math.max(MIN_WIDTH, Math.max(headerMinW, gridRowW));

            if (!widthInitialized) {
                animatedWidth = targetBoxW;
                widthInitialized = true;
            } else {
                animatedWidth += (targetBoxW - animatedWidth) * rowLerpSpeed;
            }

            final int boxWidth = Math.max(MIN_WIDTH, Math.round(animatedWidth));

            // W trybie podglądu w ClickGUI przy 0 aktywnych rezerwujemy 1 wiersz na informację
            final float effectiveRows = (animatedRowsCount < 0.05f && inClickGui) ? 1.0f : animatedRowsCount;
            final int bodyHeight = Math.round(BODY_PAD_TOP + (effectiveRows * ROW_HEIGHT) + BODY_PAD_BOT);
            final int boxHeight = HEADER_HEIGHT + 1 + bodyHeight;

            lastWidth = boxWidth;
            lastHeight = boxHeight;

            // Clamp pozycji do pełnych pikseli wewnątrz okna gry
            final int screenW = mc.getWindow().getGuiScaledWidth();
            final int screenH = mc.getWindow().getGuiScaledHeight();
            if (hudX < 2) hudX = 2;
            if (hudY < 2) hudY = 2;
            if (hudX + boxWidth > screenW - 2) hudX = Math.max(2, screenW - boxWidth - 2);
            if (hudY + boxHeight > screenH - 2) hudY = Math.max(2, screenH - boxHeight - 2);

            final int x = hudX;
            final int y = hudY;
            final float masterAlpha = widgetAlphaAnim;

            // Animacja hovera nagłówka w ClickGUI
            final boolean headerHovered = inClickGui && (dragging || isMouseOverHeaderRaw(mc, x, y, boxWidth));
            final float targetHover = headerHovered ? 1.0f : 0.0f;
            headerHoverAnim += (targetHover - headerHoverAnim) * widgetLerpSpeed;
            if (headerHoverAnim < 0.004f) headerHoverAnim = 0.0f;
            else if (headerHoverAnim > 0.996f) headerHoverAnim = 1.0f;

            // =================================================================
            // 2. JEDNOLITA BRYŁA LIQUID GLASS (MONOCHROME LILAC & OBSIDIAN)
            // =================================================================

            // A. Miękki cień zewnętrzny
            drawRoundedBoxSafe(
                    g,
                    x - 2.0f, y + 1.0f,
                    boxWidth + 4.0f, boxHeight + 3.0f,
                    CARD_RADIUS + 2.0f,
                    mulAlpha(COL_DROP_SHADOW, masterAlpha),
                    0, 0.0f
            );

            // B. Główny obsydianowy korpus + lawendowa ramka SDF
            final int baseBorder = lerpColor(COL_BORDER, COL_BORDER_HOVER, headerHoverAnim);
            drawRoundedBoxSafe(
                    g,
                    x, y,
                    boxWidth, boxHeight,
                    CARD_RADIUS,
                    mulAlpha(COL_BG, masterAlpha),
                    mulAlpha(baseBorder, masterAlpha),
                    1.0f
            );

            // C. Wewnętrzny 1px spekularny pierścień szkła
            drawRoundedBoxSafe(
                    g,
                    x + 1.0f, y + 1.0f,
                    boxWidth - 2.0f, boxHeight - 2.0f,
                    CARD_RADIUS - 1.0f,
                    0,
                    mulAlpha(COL_INNER_GLASS_RIM, masterAlpha),
                    1.0f
            );

            // D. Szklana kopuła nagłówka
            drawRoundedBoxSafe(
                    g,
                    x + 1.0f, y + 1.0f,
                    boxWidth - 2.0f, HEADER_HEIGHT - 1.0f,
                    CARD_RADIUS - 1.0f,
                    mulAlpha(COL_HEADER_BG, masterAlpha),
                    0, 0.0f
            );

            // =================================================================
            // 3. NAGŁÓWEK (SPÓJNA LAWENDA + BIEL)
            // =================================================================
            final int iconX = x + PADDING_X;
            final int iconY = y + (HEADER_HEIGHT - 7) / 2;
            drawKeyboardIcon(g, iconX, iconY, mulAlpha(COL_ACCENT_LILAC, masterAlpha));

            final int headerTextY = y + (HEADER_HEIGHT - 7) / 2;
            final int titleX = iconX + 15;
            final int titleCol = mulAlpha(COL_TEXT_WHITE, masterAlpha);
            if ((titleCol >>> 24) >= 8) {
                g.drawString(font, TITLE_TEXT, titleX, headerTextY, titleCol, false);
            }

            // Uchwyt przeciągania (6 punktów) widoczny po najechaniu w ClickGUI
            int rightHeaderCursor = x + boxWidth - PADDING_X;
            if (headerHoverAnim > 0.02f) {
                final int gripW = 5;
                final int gripX = rightHeaderCursor - gripW;
                final int gripY = y + (HEADER_HEIGHT - 8) / 2;
                final int dotCol = mulAlpha(lerpColor(0x00C4B5FD, 0xB8C4B5FD, headerHoverAnim), masterAlpha);
                drawDragGripDots(g, gripX, gripY, dotCol);
                rightHeaderCursor -= (gripW + 5);
            }

            // Lawendowa pigułka licznika odpalonych modułów
            final int pillH = 13;
            final int pillX = rightHeaderCursor - counterPillW;
            final int pillY = y + (HEADER_HEIGHT - pillH) / 2;
            drawRoundedBoxSafe(
                    g,
                    pillX, pillY,
                    counterPillW, pillH,
                    6.5f,
                    mulAlpha(COL_COUNTER_PILL_BG, masterAlpha),
                    mulAlpha(COL_COUNTER_PILL_BORDER, masterAlpha),
                    0.85f
            );

            final float miniLedSize = 4.0f;
            final float miniLedX = pillX + 4.0f;
            final float miniLedY = pillY + (pillH - miniLedSize) * 0.5f;
            final int miniLedCol = (activeCount > 0) ? COL_LILAC_CORE : COL_LED_INACTIVE;
            if (activeCount > 0) {
                drawRoundedBoxSafe(
                        g,
                        miniLedX - 1.5f, miniLedY - 1.5f,
                        miniLedSize + 3.0f, miniLedSize + 3.0f,
                        3.5f,
                        mulAlpha(COL_LILAC_GLOW, masterAlpha),
                        0, 0.0f
                );
            }
            drawRoundedBoxSafe(
                    g,
                    miniLedX, miniLedY,
                    miniLedSize, miniLedSize,
                    2.0f,
                    mulAlpha(miniLedCol, masterAlpha),
                    0, 0.0f
            );

            final int counterTextY = pillY + (pillH - 7) / 2;
            final int counterTextCol = mulAlpha((activeCount > 0) ? COL_TEXT_WHITE : COL_TEXT_DIM, masterAlpha);
            if ((counterTextCol >>> 24) >= 8) {
                g.drawString(font, counterText, pillX + 10, counterTextY, counterTextCol, false);
            }

            // Cienka lawendowa linia oddzielająca nagłówek od listy
            final int dividerY = y + HEADER_HEIGHT;
            g.fill(x + 2, dividerY, x + boxWidth - 2, dividerY + 1, mulAlpha(COL_DIVIDER_HAIRLINE, masterAlpha));

            // =================================================================
            // 4. WIERSZE AKTYWNYCH MODUŁÓW (Z PRZYCINANIEM SCISSOR PODCZAS ANIMACJI)
            // =================================================================
            final int bodyTopY = dividerY + 1;
            g.enableScissor(x + 1, bodyTopY, x + boxWidth - 1, y + boxHeight - 1);
            try {
                float currentY = bodyTopY + BODY_PAD_TOP;

                if (animatedRowsCount < 0.05f && inClickGui) {
                    final int emptyY = Math.round(currentY + (ROW_HEIGHT - 8) * 0.5f);
                    final int emptyCol = mulAlpha(COL_TEXT_DIM, masterAlpha);
                    if ((emptyCol >>> 24) >= 8) {
                        g.drawString(font, EMPTY_PREVIEW_TEXT, x + PADDING_X, emptyY, emptyCol, false);
                    }
                    return;
                }

                final int modeColX   = x + boxWidth - PADDING_X - modeColW;
                final int keycapColX = modeColX - KEY_TO_MODE_GAP - maxKeycapW;

                for (int i = 0; i < totalEntries; i++) {
                    final KeybindEntry entry = entries.get(i);
                    final float vis = entry.visibilityAnim;
                    if (vis <= 0.01f) {
                        continue;
                    }

                    final float rowAlpha  = masterAlpha * vis;
                    final float actAnim   = entry.activeAnim;
                    final float pressAnim = entry.pressAnim;
                    final float rowH      = ROW_HEIGHT * vis;
                    final int rowTopInt   = Math.round(currentY);

                    // Szklane podświetlenie aktywnego wiersza + lawendowy boczny znacznik
                    if (actAnim > 0.01f) {
                        final int rowBg = mulAlpha(lerpColor(0x00000000, COL_ROW_ACTIVE_BG, actAnim), rowAlpha);
                        drawRoundedBoxSafe(
                                g,
                                x + 4.0f, currentY + 0.5f,
                                boxWidth - 8.0f, Math.max(1.0f, rowH - 1.0f),
                                4.0f,
                                rowBg, 0, 0.0f
                        );

                        final int sideBarCol = mulAlpha(lerpColor(0x00C4B5FD, COL_ROW_ACCENT_BAR, actAnim), rowAlpha);
                        drawRoundedBoxSafe(
                                g,
                                x + 3.0f, currentY + 3.0f * vis,
                                1.5f, Math.max(1.0f, rowH - 6.0f * vis),
                                0.75f,
                                sideBarCol, 0, 0.0f
                        );
                    }

                    // A. Okrągła lawendowa dioda LED SDF z pulsującą poświatą
                    final float ledDiam = 4.0f;
                    final float ledX    = x + PADDING_X + 2.0f;
                    final float ledY    = currentY + (rowH - ledDiam) * 0.5f;

                    if (actAnim > 0.02f) {
                        final float pulse = 0.78f + 0.22f * (float) Math.sin(elapsedSeconds * 4.8 + i * 0.85);
                        final int bloomAlpha = (int) (0x46 * actAnim * pulse * rowAlpha);
                        final int bloomCol = (bloomAlpha << 24) | (COL_LILAC_GLOW & 0x00FFFFFF);
                        final float bloomPad = 2.5f + 0.5f * pulse;

                        drawRoundedBoxSafe(
                                g,
                                ledX - bloomPad,
                                ledY - bloomPad,
                                ledDiam + bloomPad * 2.0f,
                                ledDiam + bloomPad * 2.0f,
                                2.0f + bloomPad,
                                bloomCol, 0, 0.0f
                        );
                    }

                    final int ledCoreCol = mulAlpha(lerpColor(COL_LED_INACTIVE, COL_LILAC_CORE, actAnim), rowAlpha);
                    drawRoundedBoxSafe(g, ledX, ledY, ledDiam, ledDiam, 2.0f, ledCoreCol, 0, 0.0f);

                    // B. Nazwa modułu (czysta biel dla aktywnego)
                    final int nameY = rowTopInt + Math.round((rowH - 8.0f) * 0.5f);
                    final int nameX = x + PADDING_X + 2 + LED_TO_NAME_GAP;
                    final int nameCol = mulAlpha(lerpColor(COL_TEXT_DIM, COL_TEXT_WHITE, actAnim), rowAlpha);
                    if ((nameCol >>> 24) >= 8) {
                        g.drawString(font, entry.name, nameX, nameY, nameCol, true);
                    }

                    // C. Szklany Keycap 3D (w tej samej lawendowej estetyce)
                    final int keycapH    = 13;
                    final int pressPixel = (pressAnim >= 0.45f) ? 1 : 0;
                    final int keycapY    = rowTopInt + Math.round((rowH - (keycapH + 1)) * 0.5f);
                    final int activeCapY = keycapY + pressPixel;

                    if (pressPixel == 0) {
                        drawRoundedBoxSafe(
                                g,
                                keycapColX,
                                keycapY + 1.0f,
                                maxKeycapW,
                                keycapH,
                                KEYCAP_RADIUS,
                                mulAlpha(COL_KEYCAP_LIP, rowAlpha),
                                0, 0.0f
                        );
                    }

                    int capBg  = lerpColor(COL_BADGE_BG, COL_KEYCAP_ACTIVE_BG, actAnim);
                    int capBdr = lerpColor(0x4AC4B5FD, COL_KEYCAP_ACTIVE_BDR, actAnim);
                    if (pressAnim > 0.05f) {
                        capBg  = lerpColor(capBg, COL_KEYCAP_PRESS_BG, pressAnim);
                        capBdr = lerpColor(capBdr, COL_KEYCAP_PRESS_BORDER, pressAnim);
                    }

                    drawRoundedBoxSafe(
                            g,
                            keycapColX,
                            activeCapY,
                            maxKeycapW,
                            keycapH,
                            KEYCAP_RADIUS,
                            mulAlpha(capBg, rowAlpha),
                            mulAlpha(capBdr, rowAlpha),
                            1.0f
                    );

                    final boolean isListeningCap = (entry == listeningEntry);
                    final String displayKey = isListeningCap ? "..." : entry.keyName;
                    final int keyTextW = font.width(displayKey);
                    final int keyTextX = keycapColX + (maxKeycapW - keyTextW) / 2;
                    final int keyTextY = activeCapY + (keycapH - 7) / 2;
                    final int keyTextCol = mulAlpha(
                            isListeningCap
                                    ? COL_ACCENT_LILAC
                                    : ((pressAnim > 0.1f)
                                            ? lerpColor(COL_TEXT_WHITE, COL_ACCENT_LILAC, pressAnim)
                                            : lerpColor(COL_TEXT_DIM, COL_TEXT_WHITE, actAnim)),
                            rowAlpha
                    );
                    if ((keyTextCol >>> 24) >= 8) {
                        g.drawString(font, displayKey, keyTextX, keyTextY, keyTextCol, false);
                    }

                    // D. Spójny lawendowy znacznik trybu TGL / HOLD
                    final String modeLabel = entry.mode.shortLabel;
                    final int modeTextW = font.width(modeLabel);
                    final int modeTextX = modeColX + (modeColW - modeTextW) / 2;
                    final int modeTextY = rowTopInt + Math.round((rowH - 7.0f) * 0.5f);
                    final int modeTextCol = mulAlpha(
                            lerpColor(COL_TEXT_DIM, COL_LILAC_MUTED, actAnim),
                            rowAlpha
                    );
                    if ((modeTextCol >>> 24) >= 8) {
                        g.drawString(font, modeLabel, modeTextX, modeTextY, modeTextCol, false);
                    }

                    currentY += rowH;
                }
            } finally {
                g.disableScissor();
            }
        } catch (Throwable ignored) {
            // Fail-safe: błąd renderowania HUD nigdy nie crashuje gry
        }
    }

    // =========================================================================
    // OBSŁUGA MYSZY (DRAGGING W CLICKGUI)
    // =========================================================================

    public static boolean handleMouseClicked(double mouseX, double mouseY, int button) {
        if (!enabled) {
            return false;
        }
        if (isMouseOverHeader(mouseX, mouseY)) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                dragging = true;
                dragOffsetX = (int) mouseX - hudX;
                dragOffsetY = (int) mouseY - hudY;
                return true;
            } else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                onlyShowActive = !onlyShowActive;
                return true;
            }
        }

        final Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.screen instanceof BaritoneBypassScreen && isMouseOverWidget(mouseX, mouseY)) {
            final List<KeybindEntry> entries = KeybindManager.ENTRIES;
            final int totalEntries = entries.size();
            final Font font = mc.font;
            if (font == null) return false;

            final int modeColW = Math.max(font.width("HOLD"), font.width("TGL"));
            int maxKeycapW = 16;
            for (int i = 0; i < totalEntries; i++) {
                final KeybindEntry entry = entries.get(i);
                if (entry.visibilityAnim > 0.005f) {
                    final int keyW = font.width(entry.keyName) + 8;
                    if (keyW > maxKeycapW) maxKeycapW = keyW;
                }
            }

            final int dividerY = hudY + HEADER_HEIGHT;
            final int bodyTopY = dividerY + 1;
            final int modeColX = hudX + lastWidth - PADDING_X - modeColW;
            final int keycapColX = modeColX - KEY_TO_MODE_GAP - maxKeycapW;

            float currentY = bodyTopY + BODY_PAD_TOP;
            for (int i = 0; i < totalEntries; i++) {
                final KeybindEntry entry = entries.get(i);
                if (entry.visibilityAnim <= 0.01f) continue;
                final float rowH = ROW_HEIGHT * entry.visibilityAnim;
                final int keycapH = 13;
                final int keycapY = Math.round(currentY) + Math.round((rowH - (keycapH + 1)) * 0.5f);

                if (isHovered(mouseX, mouseY, keycapColX, keycapY, maxKeycapW, keycapH)) {
                    if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                        listeningEntry = (listeningEntry == entry) ? null : entry;
                        return true;
                    }
                } else if (isHovered(mouseX, mouseY, modeColX - 2, currentY, modeColW + 4, rowH)) {
                    if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT || button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                        entry.setMode(entry.getMode() == KeybindMode.TOGGLE ? KeybindMode.HOLD : KeybindMode.TOGGLE);
                        var card = BaritoneBypassScreen.getCardByName(entry.name);
                        if (card != null) {
                            card.keyMode = entry.getMode();
                            BaritoneBypassScreen.syncCardToSettings(card);
                        }
                        return true;
                    }
                }
                currentY += rowH;
            }
        }

        return false;
    }

    private static boolean isHovered(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    public static boolean handleMouseDragged(double mouseX, double mouseY, int button) {
        if (!dragging || button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        final Minecraft mc = Minecraft.getInstance();
        final int screenW = (mc != null && mc.getWindow() != null) ? mc.getWindow().getGuiScaledWidth() : 1920;
        final int screenH = (mc != null && mc.getWindow() != null) ? mc.getWindow().getGuiScaledHeight() : 1080;

        int nextX = (int) mouseX - dragOffsetX;
        int nextY = (int) mouseY - dragOffsetY;

        if (nextX < 2) nextX = 2;
        if (nextY < 2) nextY = 2;
        if (nextX + lastWidth > screenW - 2) nextX = Math.max(2, screenW - lastWidth - 2);
        if (nextY + lastHeight > screenH - 2) nextY = Math.max(2, screenH - lastHeight - 2);

        hudX = nextX;
        hudY = nextY;
        return true;
    }

    public static boolean handleMouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return handleMouseDragged(mouseX, mouseY, button);
    }

    public static boolean handleMouseReleased(double mouseX, double mouseY, int button) {
        if (dragging && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            dragging = false;
            return true;
        }
        return false;
    }

    public static boolean isMouseOverHeader(double mouseX, double mouseY) {
        return mouseX >= hudX && mouseX <= (hudX + lastWidth)
                && mouseY >= hudY && mouseY <= (hudY + HEADER_HEIGHT);
    }

    public static boolean isMouseOverWidget(double mouseX, double mouseY) {
        return mouseX >= hudX && mouseX <= (hudX + lastWidth)
                && mouseY >= hudY && mouseY <= (hudY + lastHeight);
    }

    private static boolean isMouseOverHeaderRaw(Minecraft mc, int x, int y, int width) {
        if (mc.mouseHandler == null || mc.getWindow() == null) return false;
        final double scale = mc.getWindow().getGuiScale();
        if (scale <= 0.0) return false;
        final double mx = mc.mouseHandler.xpos() / scale;
        final double my = mc.mouseHandler.ypos() / scale;
        return mx >= x && mx <= (x + width) && my >= y && my <= (y + HEADER_HEIGHT);
    }

    // =========================================================================
    // WARSTWA RENDEROWANIA SDF + WEKTOROWE DETALE (ZERO-ALLOC)
    // =========================================================================

    private static void drawRoundedBoxSafe(GuiGraphics g, float x, float y, float w, float h,
                                           float radius, int fillCol, int borderCol, float borderW) {
        if (w <= 0.0f || h <= 0.0f) return;
        if ((fillCol >>> 24) == 0 && (borderCol >>> 24) == 0) return;

        if (useSdfRenderer) {
            try {
                BaritoneBypassScreen.VenturaSdfRenderer.drawRoundedBox(
                        x, y, w, h, radius, fillCol, borderCol, borderW
                );
                return;
            } catch (Throwable t) {
                useSdfRenderer = false;
            }
        }

        final int ix = (int) x;
        final int iy = (int) y;
        final int iw = (int) w;
        final int ih = (int) h;
        final int cut = (radius >= 3.0f && iw >= 8 && ih >= 8) ? 1 : 0;

        if ((fillCol >>> 24) > 0) {
            if (cut > 0) {
                g.fill(ix + 1, iy, ix + iw - 1, iy + 1, fillCol);
                g.fill(ix, iy + 1, ix + iw, iy + ih - 1, fillCol);
                g.fill(ix + 1, iy + ih - 1, ix + iw - 1, iy + ih, fillCol);
            } else {
                g.fill(ix, iy, ix + iw, iy + ih, fillCol);
            }
        }

        if (borderW > 0.0f && (borderCol >>> 24) > 0) {
            g.fill(ix + cut, iy, ix + iw - cut, iy + 1, borderCol);
            g.fill(ix + cut, iy + ih - 1, ix + iw - cut, iy + ih, borderCol);
            g.fill(ix, iy + cut, ix + 1, iy + ih - cut, borderCol);
            g.fill(ix + iw - 1, iy + cut, ix + iw, iy + ih - cut, borderCol);
        }
    }

    /**
     * Ostra wektorowa ikona klawiatury 11x7 px.
     */
    private static void drawKeyboardIcon(GuiGraphics g, int x, int y, int color) {
        final int alpha = (color >>> 24) & 0xFF;
        if (alpha == 0) return;
        final int keyCapCol = (((alpha * 4) / 5) << 24) | (color & 0x00FFFFFF);

        g.fill(x + 1, y, x + 10, y + 1, color);
        g.fill(x + 1, y + 6, x + 10, y + 7, color);
        g.fill(x, y + 1, x + 1, y + 6, color);
        g.fill(x + 10, y + 1, x + 11, y + 6, color);

        g.fill(x + 2, y + 2, x + 3, y + 3, keyCapCol);
        g.fill(x + 4, y + 2, x + 5, y + 3, keyCapCol);
        g.fill(x + 6, y + 2, x + 7, y + 3, keyCapCol);
        g.fill(x + 8, y + 2, x + 9, y + 3, keyCapCol);

        g.fill(x + 2, y + 4, x + 3, y + 5, keyCapCol);
        g.fill(x + 4, y + 4, x + 7, y + 5, color);
        g.fill(x + 8, y + 4, x + 9, y + 5, keyCapCol);
    }

    /**
     * Uchwyt przeciągania: 6 ostrych punktów siatki.
     */
    private static void drawDragGripDots(GuiGraphics g, int x, int y, int color) {
        if ((color >>> 24) == 0) return;
        for (int row = 0; row < 3; row++) {
            final int dy = y + row * 3;
            g.fill(x, dy, x + 1, dy + 1, color);
            g.fill(x + 3, dy, x + 4, dy + 1, color);
        }
    }

    // =========================================================================
    // NARZĘDZIA ZERO-ALLOC (ARGB LERP, ALPHA SCALE & PRE-BUFOROWANY LICZNIK)
    // =========================================================================

    private static int mulAlpha(int argb, float factor) {
        if (factor >= 0.999f) return argb;
        if (factor <= 0.001f) return (argb & 0x00FFFFFF);
        final int a = (argb >>> 24) & 0xFF;
        final int outA = (int) (a * factor);
        return (outA << 24) | (argb & 0x00FFFFFF);
    }

    private static int lerpColor(int c1, int c2, float t) {
        if (t <= 0.0f) return c1;
        if (t >= 1.0f) return c2;

        final int a1 = (c1 >>> 24) & 0xFF;
        final int r1 = (c1 >>> 16) & 0xFF;
        final int g1 = (c1 >>> 8)  & 0xFF;
        final int b1 = c1 & 0xFF;

        final int a2 = (c2 >>> 24) & 0xFF;
        final int r2 = (c2 >>> 16) & 0xFF;
        final int g2 = (c2 >>> 8)  & 0xFF;
        final int b2 = c2 & 0xFF;

        final int a = a1 + (int) ((a2 - a1) * t);
        final int r = r1 + (int) ((r2 - r1) * t);
        final int g = g1 + (int) ((g2 - g1) * t);
        final int b = b1 + (int) ((b2 - b1) * t);

        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static String getCachedCount(int active) {
        if (active <= 0) return COUNT_CACHE[0];
        if (active >= MAX_CACHED_COUNT) return COUNT_CACHE[MAX_CACHED_COUNT];
        return COUNT_CACHE[active];
    }

    // =========================================================================
    // ARCHITEKTURA REJESTRACJI I OBSŁUGI KLAWISZY (KeybindManager)
    // =========================================================================

    public enum KeybindMode {
        TOGGLE("TGL"),
        HOLD("HOLD");

        public final String shortLabel;

        KeybindMode(String shortLabel) {
            this.shortLabel = shortLabel;
        }
    }

    public static final class KeybindEntry {
        private final String name;
        private int keyCode;
        private String keyName;
        private KeybindMode mode;
        private final BooleanSupplier activeSupplier;
        private final Runnable onToggleAction;
        private final Consumer<Boolean> onHoldAction;

        float visibilityAnim;
        float activeAnim;
        float pressAnim;
        boolean physicallyHeld;
        boolean prevTickDown;

        public KeybindEntry(String name, int keyCode, KeybindMode mode, BooleanSupplier activeSupplier) {
            this(name, keyCode, mode, activeSupplier, null, null);
        }

        public KeybindEntry(String name, int keyCode, KeybindMode mode,
                            BooleanSupplier activeSupplier,
                            Runnable onToggleAction,
                            Consumer<Boolean> onHoldAction) {
            this.name = (name != null) ? name : "Unknown";
            this.mode = (mode != null) ? mode : KeybindMode.TOGGLE;
            this.activeSupplier = (activeSupplier != null) ? activeSupplier : () -> false;
            this.onToggleAction = onToggleAction;
            this.onHoldAction = onHoldAction;
            setKeyCode(keyCode);

            final boolean initActive = isActive();
            this.activeAnim = initActive ? 1.0f : 0.0f;
            this.visibilityAnim = (!onlyShowActive || initActive) ? 1.0f : 0.0f;
            this.pressAnim = 0.0f;
        }

        public String getName() {
            return name;
        }

        public int getKeyCode() {
            return keyCode;
        }

        public String getKeyName() {
            return keyName;
        }

        public KeybindMode getMode() {
            return mode;
        }

        public void setMode(KeybindMode mode) {
            if (mode != null) {
                this.mode = mode;
            }
        }

        public void setKeyCode(int newKeyCode) {
            this.keyCode = newKeyCode;
            this.keyName = KeybindManager.getKeyName(newKeyCode);
            this.physicallyHeld = false;
            this.prevTickDown = false;
        }

        public boolean isActive() {
            try {
                return activeSupplier.getAsBoolean();
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    public static final class KeybindManager {
        private static final List<KeybindEntry> ENTRIES = new CopyOnWriteArrayList<>();

        private KeybindManager() {
        }

        public static KeybindEntry register(String name, int keyCode, KeybindMode mode, BooleanSupplier activeSupplier) {
            return register(new KeybindEntry(name, keyCode, mode, activeSupplier));
        }

        public static KeybindEntry register(String name, int keyCode, KeybindMode mode,
                                            BooleanSupplier activeSupplier,
                                            Runnable onToggleAction,
                                            Consumer<Boolean> onHoldAction) {
            return register(new KeybindEntry(name, keyCode, mode, activeSupplier, onToggleAction, onHoldAction));
        }

        public static KeybindEntry register(KeybindEntry entry) {
            if (entry == null) return null;
            for (int i = 0, n = ENTRIES.size(); i < n; i++) {
                if (ENTRIES.get(i).name.equalsIgnoreCase(entry.name)) {
                    ENTRIES.set(i, entry);
                    return entry;
                }
            }
            ENTRIES.add(entry);
            return entry;
        }

        public static boolean unregister(String name) {
            if (name == null) return false;
            for (int i = 0, n = ENTRIES.size(); i < n; i++) {
                if (ENTRIES.get(i).name.equalsIgnoreCase(name)) {
                    ENTRIES.remove(i);
                    return true;
                }
            }
            return false;
        }

        public static KeybindEntry findByName(String name) {
            if (name == null) return null;
            for (int i = 0, n = ENTRIES.size(); i < n; i++) {
                KeybindEntry entry = ENTRIES.get(i);
                if (entry.name.equalsIgnoreCase(name)) {
                    return entry;
                }
            }
            return null;
        }

        public static void rebind(String moduleName, int newKeyCode) {
            KeybindEntry entry = findByName(moduleName);
            if (entry != null) {
                entry.setKeyCode(newKeyCode);
            }
        }

        public static List<KeybindEntry> getEntries() {
            return ENTRIES;
        }

        public static void onClientTick() {
            final Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getWindow() == null) {
                return;
            }

            final boolean canTrigger = (mc.player != null && mc.screen == null);
            final int count = ENTRIES.size();

            for (int i = 0; i < count; i++) {
                final KeybindEntry entry = ENTRIES.get(i);
                final boolean down = canTrigger && isKeyPhysicallyDown(entry.keyCode);
                entry.physicallyHeld = down;

                if (down && !entry.prevTickDown) {
                    if (entry.mode == KeybindMode.TOGGLE) {
                        if (entry.onToggleAction != null) {
                            try {
                                entry.onToggleAction.run();
                            } catch (Throwable ignored) {
                            }
                        }
                    } else if (entry.mode == KeybindMode.HOLD) {
                        if (entry.onHoldAction != null) {
                            try {
                                entry.onHoldAction.accept(true);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                } else if (!down && entry.prevTickDown) {
                    if (entry.mode == KeybindMode.HOLD && entry.onHoldAction != null) {
                        try {
                            entry.onHoldAction.accept(false);
                        } catch (Throwable ignored) {
                        }
                    }
                }

                entry.prevTickDown = down;
            }
        }

        public static void onKeyEvent(int keyCode, int action) {
            if (keyCode == GLFW.GLFW_KEY_UNKNOWN) return;
            final Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.screen != null) return;

            final int count = ENTRIES.size();
            for (int i = 0; i < count; i++) {
                final KeybindEntry entry = ENTRIES.get(i);
                if (entry.keyCode != keyCode) continue;

                if (action == GLFW.GLFW_PRESS) {
                    entry.physicallyHeld = true;
                    if (!entry.prevTickDown) {
                        entry.prevTickDown = true;
                        if (entry.mode == KeybindMode.TOGGLE && entry.onToggleAction != null) {
                            entry.onToggleAction.run();
                        } else if (entry.mode == KeybindMode.HOLD && entry.onHoldAction != null) {
                            entry.onHoldAction.accept(true);
                        }
                    }
                } else if (action == GLFW.GLFW_RELEASE) {
                    entry.physicallyHeld = false;
                    if (entry.prevTickDown) {
                        entry.prevTickDown = false;
                        if (entry.mode == KeybindMode.HOLD && entry.onHoldAction != null) {
                            entry.onHoldAction.accept(false);
                        }
                    }
                }
            }
        }

        public static void onMouseButtonEvent(int button, int action) {
            onKeyEvent(button, action);
        }

        public static void registerDefaults() {
            ENTRIES.clear();

            register(
                    "GrimAC Bypass",
                    GLFW.GLFW_KEY_R,
                    KeybindMode.TOGGLE,
                    () -> BaritoneBypassScreen.BOT_ACTIVE,
                    () -> BaritoneBypassScreen.BOT_ACTIVE = !BaritoneBypassScreen.BOT_ACTIVE,
                    null
            );

            register(
                    "Anarchia Mode",
                    GLFW.GLFW_KEY_V,
                    KeybindMode.TOGGLE,
                    () -> BaritoneBypassScreen.anarchiaMode,
                    () -> BaritoneBypassScreen.anarchiaMode = !BaritoneBypassScreen.anarchiaMode,
                    null
            );

            register(
                    "Nuker",
                    GLFW.GLFW_KEY_N,
                    KeybindMode.TOGGLE,
                    () -> com.astra.revolution.nuker.VenturaNukerModule.getInstance().isEnabled(),
                    () -> {
                        var nuker = com.astra.revolution.nuker.VenturaNukerModule.getInstance();
                        boolean next = !nuker.isEnabled();
                        nuker.setEnabled(next);
                        var card = BaritoneBypassScreen.getCardById("nuker_main");
                        if (card != null) {
                            card.enabled = next;
                            card.toggleAnim = next ? 1.0f : 0.0f;
                            BaritoneBypassScreen.syncCardToSettings(card);
                        }
                    },
                    null
            );

            register(
                    "FastMine",
                    GLFW.GLFW_KEY_G,
                    KeybindMode.HOLD,
                    () -> isKeyPhysicallyDown(GLFW.GLFW_KEY_G)
            );

            register(
                    "KillAura",
                    GLFW.GLFW_MOUSE_BUTTON_4,
                    KeybindMode.TOGGLE,
                    () -> false
            );
        }

        public static boolean isKeyPhysicallyDown(int keyCode) {
            final Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getWindow() == null || mc.screen != null) {
                return false;
            }
            final long handle = mc.getWindow().getWindow();
            if (keyCode >= GLFW.GLFW_MOUSE_BUTTON_1 && keyCode <= GLFW.GLFW_MOUSE_BUTTON_LAST) {
                return GLFW.glfwGetMouseButton(handle, keyCode) == GLFW.GLFW_PRESS;
            }
            if (keyCode >= GLFW.GLFW_KEY_SPACE && keyCode <= GLFW.GLFW_KEY_LAST) {
                return GLFW.glfwGetKey(handle, keyCode) == GLFW.GLFW_PRESS;
            }
            return false;
        }

        public static String getKeyName(int keyCode) {
            if (keyCode == GLFW.GLFW_KEY_UNKNOWN || keyCode == -1) {
                return "NONE";
            }

            if (keyCode >= GLFW.GLFW_MOUSE_BUTTON_1 && keyCode <= GLFW.GLFW_MOUSE_BUTTON_8) {
                return switch (keyCode) {
                    case GLFW.GLFW_MOUSE_BUTTON_1 -> "M1";
                    case GLFW.GLFW_MOUSE_BUTTON_2 -> "M2";
                    case GLFW.GLFW_MOUSE_BUTTON_3 -> "M3";
                    case GLFW.GLFW_MOUSE_BUTTON_4 -> "M4";
                    case GLFW.GLFW_MOUSE_BUTTON_5 -> "M5";
                    case GLFW.GLFW_MOUSE_BUTTON_6 -> "M6";
                    case GLFW.GLFW_MOUSE_BUTTON_7 -> "M7";
                    case GLFW.GLFW_MOUSE_BUTTON_8 -> "M8";
                    default -> "M" + (keyCode + 1);
                };
            }
            if (keyCode <= -100) {
                int btn = -100 - keyCode;
                return "M" + (btn + 1);
            }
            if (keyCode < -1) {
                int btn = -keyCode - 2;
                return "M" + (btn + 1);
            }

            if (keyCode >= GLFW.GLFW_KEY_A && keyCode <= GLFW.GLFW_KEY_Z) {
                return String.valueOf((char) ('A' + (keyCode - GLFW.GLFW_KEY_A)));
            }

            if (keyCode >= GLFW.GLFW_KEY_0 && keyCode <= GLFW.GLFW_KEY_9) {
                return String.valueOf((char) ('0' + (keyCode - GLFW.GLFW_KEY_0)));
            }

            if (keyCode >= GLFW.GLFW_KEY_F1 && keyCode <= GLFW.GLFW_KEY_F25) {
                return "F" + (keyCode - GLFW.GLFW_KEY_F1 + 1);
            }

            if (keyCode >= GLFW.GLFW_KEY_KP_0 && keyCode <= GLFW.GLFW_KEY_KP_9) {
                return "NUM" + (keyCode - GLFW.GLFW_KEY_KP_0);
            }

            return switch (keyCode) {
                case GLFW.GLFW_KEY_SPACE -> "SPACE";
                case GLFW.GLFW_KEY_TAB -> "TAB";
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> "ENTER";
                case GLFW.GLFW_KEY_BACKSPACE -> "BKSP";
                case GLFW.GLFW_KEY_ESCAPE -> "ESC";
                case GLFW.GLFW_KEY_INSERT -> "INS";
                case GLFW.GLFW_KEY_DELETE -> "DEL";
                case GLFW.GLFW_KEY_HOME -> "HOME";
                case GLFW.GLFW_KEY_END -> "END";
                case GLFW.GLFW_KEY_PAGE_UP -> "PGUP";
                case GLFW.GLFW_KEY_PAGE_DOWN -> "PGDN";
                case GLFW.GLFW_KEY_CAPS_LOCK -> "CAPS";
                case GLFW.GLFW_KEY_SCROLL_LOCK -> "SCRLK";
                case GLFW.GLFW_KEY_NUM_LOCK -> "NUMLK";
                case GLFW.GLFW_KEY_PRINT_SCREEN -> "PRTSC";
                case GLFW.GLFW_KEY_PAUSE -> "PAUSE";

                case GLFW.GLFW_KEY_LEFT_SHIFT -> "LSHIFT";
                case GLFW.GLFW_KEY_RIGHT_SHIFT -> "RSHIFT";
                case GLFW.GLFW_KEY_LEFT_CONTROL -> "LCTRL";
                case GLFW.GLFW_KEY_RIGHT_CONTROL -> "RCTRL";
                case GLFW.GLFW_KEY_LEFT_ALT -> "LALT";
                case GLFW.GLFW_KEY_RIGHT_ALT -> "RALT";
                case GLFW.GLFW_KEY_LEFT_SUPER -> "LWIN";
                case GLFW.GLFW_KEY_RIGHT_SUPER -> "RWIN";

                case GLFW.GLFW_KEY_UP -> "UP";
                case GLFW.GLFW_KEY_DOWN -> "DOWN";
                case GLFW.GLFW_KEY_LEFT -> "LEFT";
                case GLFW.GLFW_KEY_RIGHT -> "RIGHT";

                case GLFW.GLFW_KEY_APOSTROPHE -> "'";
                case GLFW.GLFW_KEY_COMMA -> ",";
                case GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_KP_SUBTRACT -> "-";
                case GLFW.GLFW_KEY_PERIOD, GLFW.GLFW_KEY_KP_DECIMAL -> ".";
                case GLFW.GLFW_KEY_SLASH, GLFW.GLFW_KEY_KP_DIVIDE -> "/";
                case GLFW.GLFW_KEY_SEMICOLON -> ";";
                case GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_KP_EQUAL -> "=";
                case GLFW.GLFW_KEY_LEFT_BRACKET -> "[";
                case GLFW.GLFW_KEY_BACKSLASH -> "\\";
                case GLFW.GLFW_KEY_RIGHT_BRACKET -> "]";
                case GLFW.GLFW_KEY_GRAVE_ACCENT -> "`";
                case GLFW.GLFW_KEY_KP_MULTIPLY -> "*";
                case GLFW.GLFW_KEY_KP_ADD -> "+";

                default -> {
                    String glfwName = GLFW.glfwGetKeyName(keyCode, 0);
                    yield (glfwName != null && !glfwName.isEmpty())
                            ? glfwName.toUpperCase()
                            : "KEY" + keyCode;
                }
            };
        }
    }
}
