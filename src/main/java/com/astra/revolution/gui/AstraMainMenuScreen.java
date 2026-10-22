package com.astra.revolution.gui;

import com.astra.revolution.gui.BaritoneBypassScreen.VectorIcon;
import com.astra.revolution.gui.BaritoneBypassScreen.VenturaSdfRenderer;
import com.astra.revolution.compat.IasCompat;
import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 🌌 ASTRA CLIENT REVOLUTION (v1.21.4) - NEXT-GEN MAIN MENU
 *
 * Styl: Cyber-Luxury Linear Glass & Floating Bento Hub (OpenGL 3.3 Hardware SDF).
 * Cechy wizualne i technologiczne:
 * - Dynamiczny Astra Nexus Core: animowane pierścienie orbitalne, pulsująca aura i pryzmatyczne logo
 * - Specular Light Tracking: krawędzie i poświaty kart płynnie podążają za pozycją kursora myszy
 * - Pływający podwójny dock (Floating Island Dock): lewa wyspa profilu i telemetryki RAM/FPS, prawa wyspa narzędzi
 * - Zaawansowane tło Cyber-Horizon: organiczne mgławice SDF, dot-matrix grid z radialnym wygaszaniem
 * - 5 luksusowych motywów kolorystycznych przełączanych jednym kliknięciem lub klawiszem [T]
 */
public class AstraMainMenuScreen extends Screen {

    // Flaga diagnostyczna umożliwiająca jednorazowe przejście do vanillowego menu
    public static volatile boolean bypassToVanillaOnce = false;

    // =========================================================================
    // PALETA KOLORÓW: OBSIDIAN & CYBER-LUXURY GLASS
    // =========================================================================
    private static final int COL_BG_TOP         = 0xFF050508;
    private static final int COL_BG_BOTTOM      = 0xFF0B0914;
    private static final int COL_CARD_BG        = 0xB80F0E18;
    private static final int COL_CARD_BG_HOVER  = 0xDC181526;
    private static final int COL_CARD_BORDER    = 0x22FFFFFF;
    private static final int COL_TEXT_PRIMARY   = 0xFFF8F7FF;
    private static final int COL_TEXT_SECONDARY = 0xFF9D97B2;
    private static final int COL_TEXT_MUTED     = 0xFF5E5974;
    private static final int COL_STATUS_ONLINE  = 0xFF10B981;
    private static final int COL_STATUS_AMBER   = 0xFFF59E0B;
    private static final int COL_DANGER         = 0xFFF43F5E;

    // 5 luksusowych motywów kolorystycznych (Astra Curated Palettes)
    private static final int[][] ACCENT_THEMES = {
            {0xFFA78BFA, 0xFF7C3AED, 0x447C3AED}, // 0: Amethyst Lilac (Domyślny)
            {0xFF38BDF8, 0xFF0284C7, 0x440284C7}, // 1: Cyber Celestial Cyan
            {0xFF34D399, 0xFF059669, 0x44059669}, // 2: Aurora Emerald
            {0xFFFBBF24, 0xFFD97706, 0x44D97706}, // 3: Solar Gold
            {0xFFFB7185, 0xFFE11D48, 0x44E11D48}  // 4: Crimson Eclipse
    };
    private static final String[] THEME_NAMES = {
            "Amethyst Lilac", "Cyber Cyan", "Aurora Emerald", "Solar Gold", "Crimson Eclipse"
    };
    private static int currentThemeIndex = 0;

    // =========================================================================
    // STAN ANIMACJI I CZĄSTECZEK
    // =========================================================================
    private final List<NavCard> navCards = new ArrayList<>();
    private final List<BottomAction> bottomActions = new ArrayList<>();
    private final List<GlowParticle> particles = new ArrayList<>();
    private final Random random = new Random();

    private long lastFrameTimeNs = 0L;
    private float globalTime = 0.0f;
    private float screenIntroAnim = 0.0f;
    private float smoothMouseX = 0.0f;
    private float smoothMouseY = 0.0f;
    private boolean musicMuted = false;
    private float savedMusicVolume = 1.0f;

    // Obszar interaktywny karty telemetrycznej Hero (przełącznik motywu)
    private float heroCardX, heroCardY, heroCardW, heroCardH;

    public AstraMainMenuScreen() {
        super(Component.literal("Astra Client Revolution"));
    }

    @Override
    protected void init() {
        super.init();
        this.lastFrameTimeNs = System.nanoTime();

        if (this.smoothMouseX == 0.0f && this.smoothMouseY == 0.0f) {
            this.smoothMouseX = this.width / 2.0f;
            this.smoothMouseY = this.height / 2.0f;
        }

        // Cząsteczki w tle skalowane do rozdzielczości ekranu
        int targetParticles = Mth.clamp((this.width * this.height) / 9000, 48, 105);
        if (this.particles.isEmpty() || this.particles.size() != targetParticles) {
            this.particles.clear();
            for (int i = 0; i < targetParticles; i++) {
                this.particles.add(new GlowParticle(this.width, this.height, this.random));
            }
        }

        // Odczyt aktualnego stanu głośności muzyki
        if (this.minecraft != null && this.minecraft.options != null) {
            try {
                float vol = this.minecraft.options.getSoundSourceVolume(SoundSource.MUSIC);
                this.musicMuted = (vol <= 0.001f);
                if (!this.musicMuted) {
                    this.savedMusicVolume = vol;
                }
            } catch (Throwable ignored) {
            }
        }

        rebuildNavigationCards();
        rebuildBottomBarActions();
    }

    private void rebuildNavigationCards() {
        this.navCards.clear();

        boolean splitLayout = this.width >= 700;
        float cardWidth = splitLayout ? Mth.clamp(this.width * 0.36f, 290.0f, 380.0f) : Mth.clamp(this.width * 0.70f, 280.0f, 360.0f);
        float cardHeight = 50.0f;
        float gap = 10.0f;

        int totalCards = 5;
        float totalStackHeight = totalCards * cardHeight + (totalCards - 1) * gap;

        float startX = splitLayout
                ? (this.width * 0.55f + (this.width * 0.40f - cardWidth) * 0.5f)
                : (this.width - cardWidth) * 0.5f;
        float startY = (this.height - totalStackHeight) * 0.5f - 8.0f;

        // 1. Singleplayer
        this.navCards.add(new NavCard(
                0, startX, startY, cardWidth, cardHeight,
                "Singleplayer", "Lokalne światy i eksploracja offline",
                VectorIcon.PLAY, false,
                "SOLO",
                () -> this.minecraft.setScreen(new AstraSingleplayerScreen(this)),
                null
        ));

        // 2. Multiplayer
        this.navCards.add(new NavCard(
                1, startX, startY + (cardHeight + gap), cardWidth, cardHeight,
                "Multiplayer", "Serwery sieciowe i rywalizacja PvP",
                VectorIcon.SERVER_BOX, false,
                "ONLINE",
                () -> this.minecraft.setScreen(new AstraMultiplayerScreen(this)),
                null
        ));

        // 3. Astra Hub & Schematics (+ bezpośredni skrót do Baritone Bypass)
        this.navCards.add(new NavCard(
                2, startX, startY + (cardHeight + gap) * 2, cardWidth, cardHeight,
                "Astra Hub & Schematics", "Menedżer schematów i automatyzacja",
                VectorIcon.LAYERS, false,
                "BYPASS UI",
                () -> this.minecraft.setScreen(new AstraSchematicScreen()),
                () -> this.minecraft.setScreen(new BaritoneBypassScreen())
        ));

        // 4. Options & Settings
        this.navCards.add(new NavCard(
                3, startX, startY + (cardHeight + gap) * 3, cardWidth, cardHeight,
                "Options & Settings", "Grafika, shadery, sterowanie i audio",
                VectorIcon.SLIDERS, false,
                "CONFIG",
                () -> this.minecraft.setScreen(new OptionsScreen(this, this.minecraft.options)),
                null
        ));

        // 5. Quit Game
        this.navCards.add(new NavCard(
                4, startX, startY + (cardHeight + gap) * 4, cardWidth, cardHeight,
                "Quit Game", "Bezpieczne wyjście z Astra Client",
                VectorIcon.TRASH, true,
                "EXIT",
                () -> this.minecraft.stop(),
                null
        ));
    }

    private void rebuildBottomBarActions() {
        this.bottomActions.clear();

        // 1. Bezpośredni dostęp do GUI Baritone Bypass
        this.bottomActions.add(new BottomAction(
                "Baritone UI",
                VectorIcon.SHIELD,
                () -> this.minecraft.setScreen(new BaritoneBypassScreen())
        ));

        // 2. Przełącznik motywu kolorystycznego w locie
        this.bottomActions.add(new BottomAction(
                "Theme",
                VectorIcon.SPEEDOMETER,
                this::cycleTheme
        ));

        // 3. Szybkie wyciszanie muzyki w menu
        this.bottomActions.add(new BottomAction(
                "Audio",
                VectorIcon.SLIDERS,
                this::toggleMusicMute
        ));

        // 4. Vanillowe menu (opcja diagnostyczna)
        this.bottomActions.add(new BottomAction(
                "Vanilla",
                VectorIcon.BOX,
                () -> {
                    bypassToVanillaOnce = true;
                    this.minecraft.setScreen(new TitleScreen());
                }
        ));

        // 5. In-Game Account Switcher (IAS)
        this.bottomActions.add(new BottomAction(
                "Accounts",
                VectorIcon.USER,
                () -> IasCompat.openIasScreen(this)
        ));

        // 6. Społeczność / Discord
        this.bottomActions.add(new BottomAction(
                "Discord",
                VectorIcon.VENTURA_LOGO,
                () -> Util.getPlatform().openUri("https://discord.gg/")
        ));
    }

    private void cycleTheme() {
        currentThemeIndex = (currentThemeIndex + 1) % ACCENT_THEMES.length;
    }

    private void toggleMusicMute() {
        if (this.minecraft == null || this.minecraft.options == null) return;
        try {
            float currentVol = this.minecraft.options.getSoundSourceVolume(SoundSource.MUSIC);
            if (currentVol > 0.001f) {
                this.savedMusicVolume = currentVol;
                this.minecraft.options.getSoundSourceOptionInstance(SoundSource.MUSIC).set(0.0);
                this.musicMuted = true;
            } else {
                double restore = this.savedMusicVolume > 0.01f ? this.savedMusicVolume : 1.0;
                this.minecraft.options.getSoundSourceOptionInstance(SoundSource.MUSIC).set(restore);
                this.musicMuted = false;
            }
            this.minecraft.options.save();
        } catch (Throwable ignored) {
        }
    }

    // =========================================================================
    // GŁÓWNA PĘTLA RENDEROWANIA (1.21.4 MOJANG MAPPINGS)
    // =========================================================================

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Celowo puste: wyłącza vanillową panoramę, aby zachować 100% czystego szkła SDF
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Obliczenie Delta Time dla absolutnie płynnych animacji niezależnych od FPS
        long now = System.nanoTime();
        float dt = (now - this.lastFrameTimeNs) / 1_000_000_000.0f;
        this.lastFrameTimeNs = now;
        dt = Mth.clamp(dt, 0.0001f, 0.1f);
        this.globalTime += dt;

        this.screenIntroAnim = lerpExp(this.screenIntroAnim, 1.0f, 7.5f, dt);

        this.smoothMouseX = lerpExp(this.smoothMouseX, mouseX, 9.5f, dt);
        this.smoothMouseY = lerpExp(this.smoothMouseY, mouseY, 9.5f, dt);

        float parallaxX = (this.smoothMouseX - this.width * 0.5f) * 0.022f;
        float parallaxY = (this.smoothMouseY - this.height * 0.5f) * 0.022f;

        int accentBright = ACCENT_THEMES[currentThemeIndex][0];
        int accentDeep   = ACCENT_THEMES[currentThemeIndex][1];
        int accentGlow   = ACCENT_THEMES[currentThemeIndex][2];

        // 1. Głębia Obsidianu: tło z organicznymi mgławicami i interaktywną poświatą kursora
        renderObsidianAtmosphere(parallaxX, parallaxY, mouseX, mouseY, accentBright, accentDeep, accentGlow);

        // 2. Dot-Matrix Cyber Grid z radialnym zanikaniem i cząsteczki konstelacji
        renderDotMatrixGrid(parallaxX, parallaxY, accentDeep);
        updateAndRenderParticles(dt, mouseX, mouseY, parallaxX, parallaxY, accentBright, accentDeep);

        // 3. Lewy panel Hero (Astra Nexus Core, typografia i karta telemetryczna)
        if (this.width >= 700) {
            renderLeftHeroShowcase(guiGraphics, mouseX, mouseY, parallaxX, parallaxY, accentBright, accentDeep, accentGlow);
        } else {
            renderCompactHeader(guiGraphics, accentBright, accentDeep, accentGlow);
        }

        // 4. Karty nawigacyjne ze specular light trackingiem
        for (NavCard card : this.navCards) {
            card.updateAndRender(guiGraphics, this.font, mouseX, mouseY, dt, this.globalTime,
                    this.screenIntroAnim, accentBright, accentDeep, accentGlow);
        }

        // 5. Podwójny pływający dok systemowy (Profil + Narzędzia)
        renderFloatingIslandDock(guiGraphics, mouseX, mouseY, dt, accentBright, accentDeep, accentGlow);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    // =========================================================================
    // WARSTWY GRAFICZNE SDF: TŁO, SIATKA DOT-MATRIX, CZĄSTECZKI
    // =========================================================================

    private void renderObsidianAtmosphere(float px, float py, int mx, int my, int accentBright, int accentDeep, int accentGlow) {
        // Główne tło gradientowe
        VenturaSdfRenderer.drawRoundedRect(0, 0, this.width, this.height, 0, COL_BG_TOP, 0x00000000, 0);

        // Dolna miękka winieta
        VenturaSdfRenderer.drawDropShadow(
                -40, this.height * 0.40f, this.width + 80, this.height * 0.70f,
                0, 130.0f, 35.0f, COL_BG_BOTTOM
        );

        // Organiczne pulsujące mgławice w rogach ekranu
        float pulse1 = 0.82f + 0.18f * Mth.sin(this.globalTime * 1.3f);
        float pulse2 = 0.82f + 0.18f * Mth.cos(this.globalTime * 1.0f);

        int nebulaCol1 = withAlpha(accentDeep, (int) (52 * pulse1));
        int nebulaCol2 = withAlpha(accentBright, (int) (38 * pulse2));

        // Lewa górna mgławica (Nexus Bloom)
        VenturaSdfRenderer.drawCircle(
                this.width * 0.24f - px * 1.5f,
                this.height * 0.36f - py * 1.5f,
                230.0f * pulse1,
                0x00000000,
                nebulaCol1
        );

        // Prawa dolna mgławica (Dock Bloom)
        VenturaSdfRenderer.drawCircle(
                this.width * 0.76f - px * 0.9f,
                this.height * 0.65f - py * 0.9f,
                260.0f * pulse2,
                0x00000000,
                nebulaCol2
        );

        // Interaktywny subtelny spotlight podążający za kursorem myszy
        float cursorGlowPulse = 0.75f + 0.25f * Mth.sin(this.globalTime * 2.0f);
        int cursorGlowCol = withAlpha(accentGlow, (int) (40 * cursorGlowPulse));
        VenturaSdfRenderer.drawCircle(this.smoothMouseX, this.smoothMouseY, 110.0f, 0x00000000, cursorGlowCol);
    }

    private void renderDotMatrixGrid(float px, float py, int accentDeep) {
        float spacing = 40.0f;
        float offsetX = ((-px * 0.5f) % spacing + spacing) % spacing;
        float offsetY = ((-py * 0.5f) % spacing + spacing) % spacing;

        float centerX = this.width * 0.5f;
        float centerY = this.height * 0.5f;
        float maxRadius = Math.max(this.width, this.height) * 0.65f;

        // Siatka punktów z płynnym zanikaniem radialnym (High-Tech Matrix)
        for (float x = offsetX; x < this.width; x += spacing) {
            for (float y = offsetY; y < this.height; y += spacing) {
                float dist = (float) Math.hypot(x - centerX, y - centerY);
                float falloff = Math.max(0.0f, 1.0f - dist / maxRadius);
                int alpha = (int) (22.0f * falloff * falloff);

                if (alpha > 2) {
                    VenturaSdfRenderer.drawCircle(x, y, 1.15f, withAlpha(accentDeep, alpha), 0x00000000);
                }
            }
        }
    }

    private void updateAndRenderParticles(float dt, int mouseX, int mouseY, float px, float py, int accentBright, int accentDeep) {
        int size = this.particles.size();

        for (int i = 0; i < size; i++) {
            GlowParticle p = this.particles.get(i);
            p.update(dt, this.width, this.height, mouseX, mouseY);
        }

        float maxDist = 94.0f;
        float maxDistSq = maxDist * maxDist;

        for (int i = 0; i < size; i++) {
            GlowParticle a = this.particles.get(i);
            float ax = a.x - px * a.depth;
            float ay = a.y - py * a.depth;

            // Łączenie węzłów konstelacji
            for (int j = i + 1; j < size; j++) {
                GlowParticle b = this.particles.get(j);
                float bx = b.x - px * b.depth;
                float by = b.y - py * b.depth;

                float dx = ax - bx;
                float dy = ay - by;
                float distSq = dx * dx + dy * dy;

                if (distSq < maxDistSq) {
                    float factor = 1.0f - (float) Math.sqrt(distSq) / maxDist;
                    int lineAlpha = (int) (factor * 44.0f * a.alpha);
                    if (lineAlpha > 3) {
                        int lineCol = withAlpha(accentBright, lineAlpha);
                        VenturaSdfRenderer.drawLine(ax, ay, bx, by, 1.0f, lineCol);
                    }
                }
            }

            // Elastyczne powiązanie z kursorem myszy
            float mdx = ax - mouseX;
            float mdy = ay - mouseY;
            float mDistSq = mdx * mdx + mdy * mdy;
            if (mDistSq < 125.0f * 125.0f) {
                float mFactor = 1.0f - (float) Math.sqrt(mDistSq) / 125.0f;
                int cursorLineCol = withAlpha(accentBright, (int) (mFactor * 78.0f));
                VenturaSdfRenderer.drawLine(ax, ay, mouseX, mouseY, 1.1f, cursorLineCol);
            }

            // Rysowanie świecącego punktu
            float pulse = 0.7f + 0.3f * Mth.sin(this.globalTime * 2.4f + a.phase);
            int coreCol = withAlpha(lerpColor(accentBright, 0xFFFFFFFF, 0.45f), (int) (200 * a.alpha * pulse));
            int glowCol = withAlpha(accentDeep, (int) (90 * a.alpha * pulse));
            VenturaSdfRenderer.drawCircle(ax, ay, a.radius, coreCol, glowCol);
        }
    }

    // =========================================================================
    // HERO SHOWCASE: ASTRA NEXUS CORE, TYPOGRAFIA & TELEMETRIA
    // =========================================================================

    private void renderLeftHeroShowcase(GuiGraphics g, int mx, int my, float px, float py, int accentBright, int accentDeep, int accentGlow) {
        float centerX = this.width * 0.28f - px * 0.35f;
        float centerY = this.height * 0.44f - py * 0.35f;

        float pulse = 0.5f + 0.5f * Mth.sin(this.globalTime * 2.2f);
        float emblemCenterY = centerY - 68.0f;

        // 1. Zewnętrzna poświata i cień Nexus Core
        VenturaSdfRenderer.drawDropShadow(
                centerX - 56, emblemCenterY - 48, 112, 96,
                38.0f, 44.0f + pulse * 14.0f, 6.0f,
                withAlpha(accentDeep, (int) (140 + 70 * pulse))
        );

        // 2. Pierścień orbitalny z obracającymi się węzłami
        float ringRadius = 52.0f + pulse * 2.0f;
        for (int i = 0; i < 4; i++) {
            float angle = this.globalTime * 1.1f + i * ((float) Math.PI * 0.5f);
            float ox = centerX + Mth.cos(angle) * ringRadius;
            float oy = emblemCenterY + Mth.sin(angle) * ringRadius;
            VenturaSdfRenderer.drawCircle(ox, oy, 2.5f, accentBright, accentGlow);
        }

        // 3. Szklany medalion o zaokrągleniu squircle
        int medallionFill = lerpColor(0xE0100E19, withAlpha(accentDeep, 210), 0.24f);
        int medallionBorder = lerpColor(accentDeep, accentBright, 0.4f + 0.6f * pulse);
        VenturaSdfRenderer.drawRoundedRect(
                centerX - 42, emblemCenterY - 42, 84, 84,
                24.0f, medallionFill, medallionBorder, 1.8f
        );

        // 4. Wewnętrzny akcent geometrii medalionu
        VenturaSdfRenderer.drawRoundedRect(
                centerX - 35, emblemCenterY - 35, 70, 70,
                18.0f, 0x00000000, withAlpha(accentBright, 65), 1.0f
        );

        // 5. Emblemat wektorowy Ventura / Astra Delta
        VenturaSdfRenderer.drawIcon(
                VectorIcon.VENTURA_LOGO,
                centerX, emblemCenterY,
                40.0f,
                lerpColor(accentBright, 0xFFFFFFFF, 0.40f + 0.30f * pulse)
        );

        // 6. Typografia Brandu: Czyste liternictwo z szerokim światłem
        String brandSub = "A S T R A   R E V O L U T I O N";
        int subW = this.font.width(brandSub);
        g.drawString(this.font, brandSub, (int) (centerX - subW / 2.0f), (int) (centerY + 6.0f), accentBright, false);

        // Subtelna linia separująca pod marką
        float lineW = Math.max(subW + 28, 160.0f);
        float lineX = centerX - lineW / 2.0f;
        float lineY = centerY + 19.0f;
        VenturaSdfRenderer.drawLine(lineX, lineY, lineX + lineW, lineY, 1.0f, withAlpha(accentBright, 80));
        VenturaSdfRenderer.drawCircle(centerX, lineY, 2.2f, accentBright, 0);

        // Etykieta suite
        String tagline = "Next-Gen Utility & Automation Suite";
        int tagW = this.font.width(tagline);
        g.drawString(this.font, tagline, (int) (centerX - tagW / 2.0f), (int) (centerY + 25.0f), COL_TEXT_SECONDARY, false);

        // 7. Kapsuła wersji
        String badgeText = "v1.21.4 REVOLUTION  •  OBSIDIAN SDF";
        int badgeW = this.font.width(badgeText) + 26;
        float badgeX = centerX - badgeW / 2.0f;
        float badgeY = centerY + 41.0f;

        VenturaSdfRenderer.drawRoundedRect(
                badgeX, badgeY, badgeW, 20.0f,
                10.0f,
                withAlpha(accentDeep, 75),
                withAlpha(accentBright, 150),
                1.0f
        );
        // Świecąca zielona kropka statusu w kapsule
        VenturaSdfRenderer.drawCircle(badgeX + 10.0f, badgeY + 10.0f, 3.0f, COL_STATUS_ONLINE, 0x5510B981);
        g.drawString(this.font, badgeText, (int) (badgeX + 18), (int) (badgeY + 6.0f), COL_TEXT_PRIMARY, false);

        // 8. Interaktywna Karta Telemetryczna silnika
        this.heroCardW = 260.0f;
        this.heroCardH = 62.0f;
        this.heroCardX = centerX - this.heroCardW / 2.0f;
        this.heroCardY = badgeY + 30.0f;

        boolean cardHovered = mx >= this.heroCardX && mx <= this.heroCardX + this.heroCardW
                && my >= this.heroCardY && my <= this.heroCardY + this.heroCardH;

        int cardFill = lerpColor(COL_CARD_BG, COL_CARD_BG_HOVER, cardHovered ? 0.8f : 0.0f);
        int cardBorder = lerpColor(COL_CARD_BORDER, accentBright, cardHovered ? 0.75f : 0.25f);

        VenturaSdfRenderer.drawDropShadow(this.heroCardX, this.heroCardY, this.heroCardW, this.heroCardH, 14.0f, 18.0f, 2.0f, 0x77000000);
        VenturaSdfRenderer.drawRoundedRect(this.heroCardX, this.heroCardY, this.heroCardW, this.heroCardH, 14.0f, cardFill, cardBorder, 1.0f);

        // Neonowy pionowy pasek z lewej
        VenturaSdfRenderer.drawRoundedRect4(this.heroCardX + 1.0f, this.heroCardY + 8.0f, 3.2f, this.heroCardH - 16.0f, 0, 2, 2, 0, accentBright, 0, 0);

        // Ikona tarczy i status
        VenturaSdfRenderer.drawIcon(VectorIcon.SHIELD, this.heroCardX + 22.0f, this.heroCardY + 31.0f, 20.0f, accentBright);
        g.drawString(this.font, "Security Engine: ACTIVE", (int) (this.heroCardX + 40), (int) (this.heroCardY + 12), COL_TEXT_PRIMARY, false);
        g.drawString(this.font, "Safe: Vulcan • GrimAC • Polar", (int) (this.heroCardX + 40), (int) (this.heroCardY + 26), COL_STATUS_ONLINE, false);

        String themeLabel = "Motyw: " + THEME_NAMES[currentThemeIndex] + "  [Kliknij / T]";
        g.drawString(this.font, themeLabel, (int) (this.heroCardX + 40), (int) (this.heroCardY + 40), cardHovered ? accentBright : COL_TEXT_MUTED, false);
    }

    private void renderCompactHeader(GuiGraphics g, int accentBright, int accentDeep, int accentGlow) {
        float centerX = this.width * 0.5f;
        float topY = 24.0f;
        VenturaSdfRenderer.drawIcon(VectorIcon.VENTURA_LOGO, centerX - 76, topY + 9, 20.0f, accentBright);
        g.drawString(this.font, "ASTRA CLIENT 1.21.4", (int) (centerX - 58), (int) (topY + 5), COL_TEXT_PRIMARY, true);
    }

    // =========================================================================
    // PŁYWAJĄCY PODWÓJNY DOK SYSTEMOWY (FLOATING ISLAND DOCK)
    // =========================================================================

    private void renderFloatingIslandDock(GuiGraphics g, int mouseX, int mouseY, float dt, int accentBright, int accentDeep, int accentGlow) {
        float dockH = 34.0f;
        float dockY = this.height - dockH - 14.0f;

        // ---------------------------------------------------------------------
        // WYSPA 1 (LEWA): PROFIL GRACZA I TELEMETRIA SYSTEMOWA
        // ---------------------------------------------------------------------
        String username = (this.minecraft != null && this.minecraft.getUser() != null)
                ? this.minecraft.getUser().getName()
                : "Player";

        int fps = this.minecraft != null ? this.minecraft.getFps() : 60;
        long totalMem = Runtime.getRuntime().totalMemory() / (1024L * 1024L);
        long freeMem = Runtime.getRuntime().freeMemory() / (1024L * 1024L);
        long usedMem = totalMem - freeMem;
        long maxMem = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
        float ramPct = Mth.clamp((float) usedMem / (float) maxMem, 0.0f, 1.0f);
        String gameVer = SharedConstants.getCurrentVersion().getName();

        String userSection = username;
        String metricsSection = String.format("MC %s  •  %d FPS", gameVer, fps);

        float leftIslandW = this.font.width(userSection) + this.font.width(metricsSection) + 145.0f;
        float leftIslandX = 20.0f;

        // Szklany korpus lewej wyspy
        VenturaSdfRenderer.drawDropShadow(leftIslandX, dockY, leftIslandW, dockH, 12.0f, 16.0f, 2.0f, 0x88000000);
        VenturaSdfRenderer.drawRoundedRect(leftIslandX, dockY, leftIslandW, dockH, 12.0f, 0xD40C0B14, COL_CARD_BORDER, 1.0f);

        // Kropka statusu i nick gracza
        float dotX = leftIslandX + 16.0f;
        float dotY = dockY + dockH * 0.5f;
        VenturaSdfRenderer.drawCircle(dotX, dotY, 4.0f, COL_STATUS_ONLINE, 0x5510B981);
        g.drawString(this.font, userSection, (int) (dotX + 10.0f), (int) (dockY + 13.0f), COL_TEXT_PRIMARY, false);

        // Separator 1
        float sep1X = dotX + 18.0f + this.font.width(userSection);
        VenturaSdfRenderer.drawLine(sep1X, dockY + 8.0f, sep1X, dockY + dockH - 8.0f, 1.0f, 0x22FFFFFF);

        // Metryki FPS i wersji
        g.drawString(this.font, metricsSection, (int) (sep1X + 12.0f), (int) (dockY + 13.0f), COL_TEXT_SECONDARY, false);

        // Separator 2
        float sep2X = sep1X + 18.0f + this.font.width(metricsSection);
        VenturaSdfRenderer.drawLine(sep2X, dockY + 8.0f, sep2X, dockY + dockH - 8.0f, 1.0f, 0x22FFFFFF);

        // Mini-pasek zużycia pamięci RAM
        float ramBarX = sep2X + 12.0f;
        float ramBarW = 38.0f;
        float ramBarH = 6.0f;
        float ramBarY = dockY + (dockH - ramBarH) * 0.5f;

        VenturaSdfRenderer.drawRoundedRect(ramBarX, ramBarY, ramBarW, ramBarH, 3.0f, 0x44222030, 0x33FFFFFF, 0.8f);
        int ramFillCol = lerpColor(COL_STATUS_ONLINE, COL_STATUS_AMBER, ramPct);
        if (ramPct > 0.05f) {
            VenturaSdfRenderer.drawRoundedRect(ramBarX + 0.5f, ramBarY + 0.5f, Math.max(3.0f, (ramBarW - 1.0f) * ramPct), ramBarH - 1.0f, 2.5f, ramFillCol, 0, 0);
        }
        g.drawString(this.font, "RAM", (int) (ramBarX + ramBarW + 6.0f), (int) (dockY + 13.0f), COL_TEXT_MUTED, false);

        // ---------------------------------------------------------------------
        // WYSPA 2 (PRAWA): SZYBKIE PRZYCISKI NARZĘDZIOWE
        // ---------------------------------------------------------------------
        float rightDockCursor = this.width - 20.0f;
        for (int i = this.bottomActions.size() - 1; i >= 0; i--) {
            BottomAction action = this.bottomActions.get(i);
            String displayLabel = action.label;
            if ("Audio".equals(action.label)) {
                displayLabel = this.musicMuted ? "Music: OFF" : "Music: ON";
            } else if ("Theme".equals(action.label)) {
                displayLabel = "Theme";
            }

            float btnW = this.font.width(displayLabel) + 30.0f;
            float btnH = 24.0f;
            float btnX = rightDockCursor - btnW;
            float btnY = dockY + (dockH - btnH) * 0.5f;

            action.updateAndRender(g, this.font, displayLabel, btnX, btnY, btnW, btnH,
                    mouseX, mouseY, dt, accentBright, accentDeep,
                    "Audio".equals(action.label) && this.musicMuted);

            rightDockCursor = btnX - 8.0f;
        }
    }

    // =========================================================================
    // OBSŁUGA INTERAKCJI MYSZĄ I KLAWIATURĄ
    // =========================================================================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            // 1. Sprawdzenie kliknięcia w karty nawigacyjne
            for (NavCard card : this.navCards) {
                if (card.mouseClicked(mouseX, mouseY)) {
                    playClickSound(1.0f);
                    return true;
                }
            }

            // 2. Sprawdzenie kliknięcia w przyciski dolnej wyspy
            for (BottomAction action : this.bottomActions) {
                if (action.mouseClicked(mouseX, mouseY)) {
                    playClickSound(1.15f);
                    return true;
                }
            }

            // 3. Sprawdzenie kliknięcia w kartę telemetryczną Hero (przełączanie motywu)
            if (mouseX >= this.heroCardX && mouseX <= this.heroCardX + this.heroCardW
                    && mouseY >= this.heroCardY && mouseY <= this.heroCardY + this.heroCardH) {
                cycleTheme();
                playClickSound(1.2f);
                return true;
            }

            // 4. Kliknięcie w lewą wyspę profilu gracza -> otwiera In-Game Account Switcher
            float dockH = 34.0f;
            float dockY = this.height - dockH - 14.0f;
            if (mouseX >= 20.0f && mouseX <= 280.0f && mouseY >= dockY && mouseY <= dockY + dockH) {
                playClickSound(1.1f);
                IasCompat.openIasScreen(this);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Skróty klawiszowe
        if (keyCode == GLFW.GLFW_KEY_U) {
            playClickSound(1.1f);
            IasCompat.openIasScreen(this);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_B && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
            playClickSound(1.1f);
            this.minecraft.setScreen(new BaritoneBypassScreen());
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_S && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
            playClickSound(1.1f);
            this.minecraft.setScreen(new AstraSchematicScreen());
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_T) {
            cycleTheme();
            playClickSound(1.15f);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_M) {
            toggleMusicMute();
            playClickSound(1.1f);
            return true;
        }

        // Cyfry 1-5 uruchamiające odpowiednie karty
        if (keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_5) {
            int cardIdx = keyCode - GLFW.GLFW_KEY_1;
            if (cardIdx < this.navCards.size()) {
                playClickSound(1.0f);
                this.navCards.get(cardIdx).trigger();
                return true;
            }
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    private void playClickSound(float pitch) {
        if (this.minecraft != null) {
            try {
                this.minecraft.getSoundManager().play(
                        SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), pitch)
                );
            } catch (Throwable ignored) {
            }
        }
    }

    // =========================================================================
    // KLASA: KARTA NAWIGACYJNA Z OBSŁUGĄ SPECULAR LIGHT TRACKING
    // =========================================================================

    private static class NavCard {
        private final int index;
        private final float baseX, baseY, width, height;
        private final String title;
        private final String subtitle;
        private final VectorIcon icon;
        private final boolean dangerStyle;
        private final String badgeText;
        private final Runnable primaryAction;
        private final Runnable secondaryAction;

        private float hoverAnim = 0.0f;
        private float subHoverAnim = 0.0f;
        private float xOffset = 0.0f;

        private float subBtnX, subBtnY, subBtnW, subBtnH;

        public NavCard(int index, float x, float y, float width, float height,
                       String title, String subtitle, VectorIcon icon,
                       boolean dangerStyle, String badgeText,
                       Runnable primaryAction, Runnable secondaryAction) {
            this.index = index;
            this.baseX = x;
            this.baseY = y;
            this.width = width;
            this.height = height;
            this.title = title;
            this.subtitle = subtitle;
            this.icon = icon;
            this.dangerStyle = dangerStyle;
            this.badgeText = badgeText;
            this.primaryAction = primaryAction;
            this.secondaryAction = secondaryAction;
        }

        public void trigger() {
            if (this.primaryAction != null) {
                this.primaryAction.run();
            }
        }

        public void updateAndRender(GuiGraphics g, Font font,
                                    int mouseX, int mouseY, float dt, float globalTime,
                                    float introAnim, int accentBright, int accentDeep, int accentGlow) {

            float stagger = Mth.clamp((introAnim * 1.35f) - (this.index * 0.07f), 0.0f, 1.0f);
            float entranceSlideX = (1.0f - stagger) * 36.0f;

            boolean hovered = isHovered(mouseX, mouseY);
            boolean subHovered = this.secondaryAction != null && isSubHovered(mouseX, mouseY);

            this.hoverAnim = lerpExp(this.hoverAnim, hovered ? 1.0f : 0.0f, 14.0f, dt);
            this.subHoverAnim = lerpExp(this.subHoverAnim, subHovered ? 1.0f : 0.0f, 16.0f, dt);
            this.xOffset = lerpExp(this.xOffset, hovered ? -8.0f : 0.0f, 14.0f, dt);

            float drawX = this.baseX + this.xOffset + entranceSlideX;
            float drawY = this.baseY;
            float radius = 14.0f;

            int activeAccent = this.dangerStyle ? COL_DANGER : accentBright;
            int activeDeep   = this.dangerStyle ? 0xFF991B1B : accentDeep;

            // Specular Light Tracking: Obliczenie odległości kursora od środka karty
            float cardCenterX = drawX + this.width * 0.5f;
            float cardCenterY = drawY + this.height * 0.5f;
            float mouseDist = (float) Math.hypot(mouseX - cardCenterX, mouseY - cardCenterY);
            float lightSheen = Math.max(0.0f, 1.0f - mouseDist / 220.0f);

            // 1. Cień i rozszerzająca się poświata karty
            int shadowCol = lerpColor(
                    0x66000000,
                    withAlpha(activeDeep, (int) (140 + 70 * lightSheen)),
                    this.hoverAnim
            );
            VenturaSdfRenderer.drawDropShadow(
                    drawX, drawY, this.width, this.height,
                    radius, 14.0f + this.hoverAnim * 12.0f, this.hoverAnim * 2.0f, shadowCol
            );

            // 2. Szklany korpus karty z podświetleniem zależnym od kursora
            int fillCol = lerpColor(COL_CARD_BG, COL_CARD_BG_HOVER, this.hoverAnim);
            int borderCol = lerpColor(
                    withAlpha(0xFFFFFFFF, (int) (24 + 40 * lightSheen)),
                    activeAccent,
                    this.hoverAnim * 0.85f
            );
            float borderW = 1.0f + this.hoverAnim * 0.7f;

            VenturaSdfRenderer.drawRoundedRect(
                    drawX, drawY, this.width, this.height,
                    radius, fillCol, borderCol, borderW
            );

            // 3. Neonowy pasek aktywności na lewej krawędzi karty
            if (this.hoverAnim > 0.01f) {
                float barH = (this.height - 18.0f) * this.hoverAnim;
                float barY = drawY + (this.height - barH) * 0.5f;
                VenturaSdfRenderer.drawRoundedRect4(
                        drawX + 1.0f, barY, 3.5f, barH,
                        0.0f, 2.0f, 2.0f, 0.0f,
                        activeAccent, 0x00000000, 0.0f
                );
            }

            // 4. Kapsuła ikony (Jewel Box)
            float iconBoxSize = 32.0f;
            float iconBoxX = drawX + 12.0f;
            float iconBoxY = drawY + (this.height - iconBoxSize) * 0.5f;

            int iconBoxBg = lerpColor(
                    0x401D1A2C,
                    withAlpha(activeDeep, 185),
                    this.hoverAnim
            );
            int iconBoxBorder = lerpColor(0x22FFFFFF, activeAccent, this.hoverAnim * 0.65f);

            VenturaSdfRenderer.drawRoundedRect(
                    iconBoxX, iconBoxY, iconBoxSize, iconBoxSize,
                    10.0f, iconBoxBg, iconBoxBorder, 1.0f
            );

            int iconColor = lerpColor(COL_TEXT_SECONDARY, 0xFFFFFFFF, this.hoverAnim);
            if (!this.dangerStyle && this.hoverAnim < 0.5f) {
                iconColor = lerpColor(accentBright, 0xFFFFFFFF, this.hoverAnim);
            } else if (this.dangerStyle) {
                iconColor = lerpColor(COL_DANGER, 0xFFFFFFFF, this.hoverAnim);
            }

            VenturaSdfRenderer.drawIcon(
                    this.icon,
                    iconBoxX + iconBoxSize * 0.5f,
                    iconBoxY + iconBoxSize * 0.5f,
                    16.0f + this.hoverAnim * 1.5f,
                    iconColor
            );

            // 5. Tytuł i opis karty
            int textX = (int) (iconBoxX + iconBoxSize + 12.0f);
            int titleY = (int) (drawY + 11.5f);
            int subY = (int) (drawY + 26.5f);

            int titleCol = lerpColor(COL_TEXT_PRIMARY, 0xFFFFFFFF, this.hoverAnim);
            int subCol = lerpColor(COL_TEXT_SECONDARY, 0xFFD8B4FE, this.hoverAnim * 0.65f);
            if (this.dangerStyle && this.hoverAnim > 0.1f) {
                subCol = lerpColor(COL_TEXT_SECONDARY, 0xFFFCA5A5, this.hoverAnim);
            }

            g.drawString(font, this.title, textX, titleY, titleCol, false);
            g.drawString(font, this.subtitle, textX, subY, subCol, false);

            // 6. Sub-przycisk lub Badge z prawej strony
            if (this.badgeText != null) {
                int bw = font.width(this.badgeText) + 16;
                this.subBtnW = bw;
                this.subBtnH = 20.0f;
                this.subBtnX = drawX + this.width - bw - 14.0f;
                this.subBtnY = drawY + (this.height - this.subBtnH) * 0.5f;

                int badgeFill = lerpColor(
                        withAlpha(activeDeep, 75),
                        activeAccent,
                        this.subHoverAnim * 0.75f
                );
                int badgeBorder = lerpColor(
                        withAlpha(activeAccent, 120),
                        0xFFFFFFFF,
                        this.subHoverAnim
                );
                int badgeTextCol = lerpColor(activeAccent, 0xFF08070D, this.subHoverAnim);

                VenturaSdfRenderer.drawRoundedRect(
                        this.subBtnX, this.subBtnY, this.subBtnW, this.subBtnH,
                        7.0f, badgeFill, badgeBorder, 1.0f
                );
                g.drawString(
                        font,
                        this.badgeText,
                        (int) (this.subBtnX + 8.0f),
                        (int) (this.subBtnY + 6.0f),
                        badgeTextCol,
                        false
                );
            }
        }

        public boolean isHovered(double mx, double my) {
            float currentX = this.baseX + this.xOffset;
            return mx >= currentX && mx <= currentX + this.width
                    && my >= this.baseY && my <= this.baseY + this.height;
        }

        public boolean isSubHovered(double mx, double my) {
            return mx >= this.subBtnX && mx <= this.subBtnX + this.subBtnW
                    && my >= this.subBtnY && my <= this.subBtnY + this.subBtnH;
        }

        public boolean mouseClicked(double mx, double my) {
            if (!isHovered(mx, my)) return false;
            if (this.secondaryAction != null && isSubHovered(mx, my)) {
                this.secondaryAction.run();
                return true;
            }
            if (this.primaryAction != null) {
                this.primaryAction.run();
                return true;
            }
            return false;
        }
    }

    // =========================================================================
    // KLASA: PRZYCISK PŁYWAJĄCEGO DOKU
    // =========================================================================

    private static class BottomAction {
        private final String label;
        private final VectorIcon icon;
        private final Runnable onClick;
        private float x, y, w, h;
        private float hoverAnim = 0.0f;

        public BottomAction(String label, VectorIcon icon, Runnable onClick) {
            this.label = label;
            this.icon = icon;
            this.onClick = onClick;
        }

        public void updateAndRender(GuiGraphics g, Font font,
                                    String displayLabel, float x, float y, float w, float h,
                                    int mouseX, int mouseY, float dt,
                                    int accentBright, int accentDeep, boolean mutedState) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;

            boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
            this.hoverAnim = lerpExp(this.hoverAnim, hovered ? 1.0f : 0.0f, 15.0f, dt);

            int bgCol = lerpColor(0xD40C0B14, withAlpha(accentDeep, 170), this.hoverAnim);
            int borderCol = lerpColor(COL_CARD_BORDER, accentBright, this.hoverAnim);
            if (mutedState) {
                borderCol = lerpColor(0x66EF4444, COL_DANGER, this.hoverAnim);
            }

            VenturaSdfRenderer.drawDropShadow(x, y, w, h, 10.0f, 12.0f, 1.5f, 0x66000000);
            VenturaSdfRenderer.drawRoundedRect(x, y, w, h, 10.0f, bgCol, borderCol, 1.0f);

            int iconCol = mutedState
                    ? COL_DANGER
                    : lerpColor(accentBright, 0xFFFFFFFF, this.hoverAnim);

            VenturaSdfRenderer.drawIcon(this.icon, x + 11.5f, y + h * 0.5f, 11.0f, iconCol);

            int textCol = lerpColor(COL_TEXT_SECONDARY, COL_TEXT_PRIMARY, this.hoverAnim);
            g.drawString(font, displayLabel, (int) (x + 21.0f), (int) (y + 8.0f), textCol, false);
        }

        public boolean mouseClicked(double mx, double my) {
            if (mx >= this.x && mx <= this.x + this.w && my >= this.y && my <= this.y + this.h) {
                if (this.onClick != null) {
                    this.onClick.run();
                }
                return true;
            }
            return false;
        }
    }

    // =========================================================================
    // KLASA: CZĄSTECZKA KONSTELACJI
    // =========================================================================

    private static class GlowParticle {
        float x, y;
        float vx, vy;
        float radius;
        float alpha;
        float depth;
        float phase;

        public GlowParticle(int screenW, int screenH, Random rng) {
            this.x = rng.nextFloat() * Math.max(100, screenW);
            this.y = rng.nextFloat() * Math.max(100, screenH);
            float speed = 8.0f + rng.nextFloat() * 18.0f;
            float angle = rng.nextFloat() * ((float) Math.PI * 2.0f);
            this.vx = Mth.cos(angle) * speed;
            this.vy = Mth.sin(angle) * speed - 4.0f;
            this.radius = 1.4f + rng.nextFloat() * 2.4f;
            this.alpha = 0.25f + rng.nextFloat() * 0.65f;
            this.depth = 0.3f + rng.nextFloat() * 1.1f;
            this.phase = rng.nextFloat() * 6.28f;
        }

        public void update(float dt, int screenW, int screenH, int mouseX, int mouseY) {
            this.x += this.vx * dt;
            this.y += this.vy * dt;

            float dx = this.x - mouseX;
            float dy = this.y - mouseY;
            float distSq = dx * dx + dy * dy;
            if (distSq > 1.0f && distSq < 90.0f * 90.0f) {
                float dist = (float) Math.sqrt(distSq);
                float force = (1.0f - dist / 90.0f) * 44.0f;
                this.x += (dx / dist) * force * dt;
                this.y += (dy / dist) * force * dt;
            }

            float pad = 24.0f;
            if (this.x < -pad) this.x = screenW + pad;
            if (this.x > screenW + pad) this.x = -pad;
            if (this.y < -pad) this.y = screenH + pad;
            if (this.y > screenH + pad) this.y = -pad;
        }
    }

    // =========================================================================
    // UTILITY: INTERPOLACJA KOLORÓW I ALFA
    // =========================================================================

    public static float lerpExp(float current, float target, float speed, float dt) {
        float factor = 1.0f - (float) Math.exp(-speed * dt);
        return current + (target - current) * Mth.clamp(factor, 0.0f, 1.0f);
    }

    public static int lerpColor(int c1, int c2, float t) {
        t = Mth.clamp(t, 0.0f, 1.0f);
        int a1 = (c1 >> 24) & 0xFF;
        int r1 = (c1 >> 16) & 0xFF;
        int g1 = (c1 >> 8) & 0xFF;
        int b1 = c1 & 0xFF;

        int a2 = (c2 >> 24) & 0xFF;
        int r2 = (c2 >> 16) & 0xFF;
        int g2 = (c2 >> 8) & 0xFF;
        int b2 = c2 & 0xFF;

        int a = (int) (a1 + (a2 - a1) * t);
        int r = (int) (r1 + (r2 - r1) * t);
        int g = (int) (g1 + (g2 - g1) * t);
        int b = (int) (b1 + (b2 - b1) * t);

        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static int withAlpha(int rgb, int alpha) {
        int clampedAlpha = Mth.clamp(alpha, 0, 255);
        return (clampedAlpha << 24) | (rgb & 0x00FFFFFF);
    }
}
