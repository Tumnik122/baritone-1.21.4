package com.astra.revolution.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.options.LanguageSelectScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import com.mojang.blaze3d.vertex.PoseStack;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Constructor;
import java.util.Random;

/**
 * 🌌 ASTRA CLIENT REVOLUTION - CYBER-COSMIC GLASSMORPHISM TITLE SCREEN
 * Pełna implementacja w oficjalnych mapowaniach Mojanga (Fabric 1.21.4, Java 21).
 */
public class CustomStyledTitleScreen extends Screen {

    public static volatile boolean bypassToVanillaOnce = false;

    // =========================================================================
    // PALETA KOLORYSTYCZNA: CYBER-COSMIC GLASSMORPHISM
    // =========================================================================
    private static final int COL_BG_VOID          = 0xFF0B0813;
    private static final int COL_BG_DEEP_VIOLET   = 0xFF1E1035;
    private static final int COL_BG_NEBULA_VIOLET = 0xFF2D1654;

    private static final int COL_CYAN_BRIGHT      = 0xFF00F0FF;
    private static final int COL_CYAN_SOFT        = 0xFF38BDF8;

    private static final int COL_ORANGE_BRIGHT    = 0xFFFF6B00;
    private static final int COL_ORANGE_SOFT      = 0xFFFB923C;

    private static final int COL_VIOLET_NEON      = 0xFF7C3AED;
    private static final int COL_VIOLET_ORCHID    = 0xFFA855F7;

    private static final int COL_GLASS_BASE       = 0xA60B0813;
    private static final int COL_GLASS_ELEVATED   = 0xC4160D29;
    private static final int COL_TEXT_PRIMARY     = 0xFFF8FAFC;
    private static final int COL_TEXT_SECONDARY   = 0xFFCBD5E1;
    private static final int COL_TEXT_MUTED       = 0xFF94A3B8;

    // =========================================================================
    // CACHOWANE KOMPONENTY TEKSTOWE
    // =========================================================================
    private static final Component TXT_EDITION_BADGE   = Component.literal("ASTRA REVOLUTION 1.21.4  •  CYBER-COSMIC UI");
    private static final Component TXT_MAIN_TITLE      = Component.literal("MINECRAFT");
    private static final Component TXT_SUB_TAGLINE     = Component.literal("Wybierz tryb rozgrywki i rozpocznij eksplorację");

    private static final Component TXT_SP_TAG          = Component.literal("LOKALNE ŚWIATY");
    private static final Component TXT_SP_TITLE        = Component.literal("Singleplayer");
    private static final Component TXT_SP_DESC_1       = Component.literal("Odkrywaj lokalne światy");
    private static final Component TXT_SP_DESC_2       = Component.literal("Przetrwanie, kreatywny i własne zapisy");
    private static final Component TXT_SP_ACTION       = Component.literal("GRAJ TERAZ  →");

    private static final Component TXT_MP_TAG          = Component.literal("SIEĆ I SPOŁECZNOŚĆ");
    private static final Component TXT_MP_TITLE        = Component.literal("Multiplayer");
    private static final Component TXT_MP_DESC_1       = Component.literal("Dołącz do serwerów i znajomych");
    private static final Component TXT_MP_DESC_2       = Component.literal("Rywalizacja online, minigry i SMP");
    private static final Component TXT_MP_ACTION       = Component.literal("POŁĄCZ SIĘ  →");

    private static final Component TXT_DOCK_OPTIONS    = Component.literal("Opcje");
    private static final Component TXT_DOCK_MODS       = Component.literal("Mody");
    private static final Component TXT_DOCK_BYPASS     = Component.literal("Baritone");
    private static final Component TXT_DOCK_LANGUAGE   = Component.literal("Język");
    private static final Component TXT_DOCK_QUIT       = Component.literal("Wyjdź");

    private static final Component TXT_FOOTER_VERSION  = Component.literal("Minecraft 1.21.4  |  Astra Client v4");

    private static final int PARTICLE_COUNT = 76;
    private final Particle[] particles = new Particle[PARTICLE_COUNT];
    private final HeroCard[] heroCards = new HeroCard[2];
    private final DockButton[] dockButtons = new DockButton[5];
    private final Random random = new Random(0xC051C1214L);

    private long lastFrameNanos;
    private float elapsedSeconds;
    private float introProgress;

    private double mouseXPos;
    private double mouseYPos;
    private int lastLayoutWidth = -1;
    private int lastLayoutHeight = -1;

    private Component cachedPlayerBadge = Component.literal("Gracz");

    public CustomStyledTitleScreen() {
        super(Component.translatable("narrator.screen.title"));
        initParticles();
        initComponents();
    }

    private void initParticles() {
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            particles[i] = new Particle();
            resetParticle(particles[i], true);
        }
    }

    private void initComponents() {
        // Karta 0: Singleplayer (Fiolet -> Electric Cyan)
        heroCards[0] = new HeroCard(
                CardType.SINGLEPLAYER,
                TXT_SP_TAG,
                TXT_SP_TITLE,
                TXT_SP_DESC_1,
                TXT_SP_DESC_2,
                TXT_SP_ACTION,
                COL_VIOLET_NEON,
                COL_CYAN_BRIGHT,
                COL_CYAN_SOFT,
                this::openSingleplayer
        );

        // Karta 1: Multiplayer (Fiolet -> Neon Sunset Orange)
        heroCards[1] = new HeroCard(
                CardType.MULTIPLAYER,
                TXT_MP_TAG,
                TXT_MP_TITLE,
                TXT_MP_DESC_1,
                TXT_MP_DESC_2,
                TXT_MP_ACTION,
                COL_VIOLET_NEON,
                COL_ORANGE_BRIGHT,
                COL_ORANGE_SOFT,
                this::openMultiplayer
        );

        // Dolny dock
        dockButtons[0] = new DockButton(DockIcon.OPTIONS, TXT_DOCK_OPTIONS, COL_CYAN_BRIGHT, this::openOptions);
        dockButtons[1] = new DockButton(DockIcon.MODS, TXT_DOCK_MODS, COL_VIOLET_ORCHID, this::openMods);
        dockButtons[2] = new DockButton(DockIcon.BYPASS, TXT_DOCK_BYPASS, COL_CYAN_BRIGHT, this::openBypass);
        dockButtons[3] = new DockButton(DockIcon.LANGUAGE, TXT_DOCK_LANGUAGE, COL_CYAN_SOFT, this::openLanguage);
        dockButtons[4] = new DockButton(DockIcon.QUIT, TXT_DOCK_QUIT, COL_ORANGE_BRIGHT, this::quitGame);
    }

    @Override
    protected void init() {
        super.init();
        this.lastFrameNanos = System.nanoTime();
        this.introProgress = 0.0f;

        if (this.minecraft != null && this.minecraft.getUser() != null) {
            String username = this.minecraft.getUser().getName();
            this.cachedPlayerBadge = Component.literal(username != null && !username.isEmpty() ? username : "Gracz");
        }

        updateResponsiveLayout(this.width, this.height);
    }

    @Override
    public void resize(Minecraft client, int width, int height) {
        super.resize(client, width, height);
        updateResponsiveLayout(width, height);
    }

    private void updateResponsiveLayout(int screenW, int screenH) {
        this.lastLayoutWidth = screenW;
        this.lastLayoutHeight = screenH;

        int centerX = screenW / 2;
        int gap = Mth.clamp((int) (screenW * 0.028f), 14, 28);
        int maxAvailableWidth = screenW - 48;
        int cardWidth = Mth.clamp((int) (screenW * 0.27f), 175, 275);
        if (cardWidth * 2 + gap > maxAvailableWidth) {
            cardWidth = Math.max(145, (maxAvailableWidth - gap) / 2);
        }

        int cardHeight = Mth.clamp((int) (screenH * 0.43f), 160, 235);
        int cardsStartY = Mth.clamp((int) (screenH * 0.30f), 82, screenH - cardHeight - 78);

        int leftCardX = centerX - cardWidth - (gap / 2);
        int rightCardX = centerX + (gap / 2);

        heroCards[0].setBounds(leftCardX, cardsStartY, cardWidth, cardHeight);
        heroCards[1].setBounds(rightCardX, cardsStartY, cardWidth, cardHeight);

        int dockGap = Mth.clamp((int) (screenW * 0.012f), 6, 12);
        int pillHeight = 28;
        int pillWidth = Mth.clamp((int) (screenW * 0.10f), 70, 96);
        int totalDockWidth = dockButtons.length * pillWidth + (dockButtons.length - 1) * dockGap;
        int dockStartX = centerX - (totalDockWidth / 2);
        int dockY = Math.min(screenH - 54, cardsStartY + cardHeight + Mth.clamp((int) (screenH * 0.055f), 18, 34));

        for (int i = 0; i < dockButtons.length; i++) {
            int bx = dockStartX + i * (pillWidth + dockGap);
            dockButtons[i].setBounds(bx, dockY, pillWidth, pillHeight);
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        // Blokuje panoramę vanilla
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        if (this.width != this.lastLayoutWidth || this.height != this.lastLayoutHeight) {
            updateResponsiveLayout(this.width, this.height);
        }

        this.mouseXPos = mouseX;
        this.mouseYPos = mouseY;

        long now = System.nanoTime();
        float dt = (now - this.lastFrameNanos) / 1_000_000_000.0f;
        this.lastFrameNanos = now;
        dt = Mth.clamp(dt, 0.0001f, 0.05f);
        this.elapsedSeconds += dt;

        this.introProgress = Math.min(1.0f, this.introProgress + dt * 2.1f);
        float easedIntro = easeOutCubic(this.introProgress);

        updateParticles(dt);
        updateInteractiveStates(mouseX, mouseY, dt);

        renderCosmicBackground(graphics);
        renderParticles(graphics, easedIntro);
        renderHeader(graphics, easedIntro);

        for (int i = 0; i < heroCards.length; i++) {
            float stagger = Mth.clamp((this.introProgress - i * 0.12f) / 0.88f, 0.0f, 1.0f);
            renderHeroCard(graphics, heroCards[i], easeOutCubic(stagger));
        }

        float dockFade = easeOutCubic(Mth.clamp((this.introProgress - 0.22f) / 0.78f, 0.0f, 1.0f));
        renderDock(graphics, dockFade);
        renderStatusFooter(graphics, dockFade);
    }

    private void updateParticles(float dt) {
        int w = Math.max(1, this.width);
        int h = Math.max(1, this.height);

        for (int i = 0; i < PARTICLE_COUNT; i++) {
            Particle p = particles[i];
            p.age += dt;
            p.y -= p.speedY * dt;
            p.x += (float) (Math.sin(this.elapsedSeconds * p.swayFreq + p.phase) * p.swayAmp * dt);

            if (p.y < -16.0f || p.age >= p.maxAge) {
                resetParticle(p, false);
                p.x = random.nextFloat() * w;
                p.y = h + 8.0f + random.nextFloat() * 24.0f;
            }
        }
    }

    private void resetParticle(Particle p, boolean randomY) {
        int w = Math.max(1, this.width > 0 ? this.width : 854);
        int h = Math.max(1, this.height > 0 ? this.height : 480);

        p.x = random.nextFloat() * w;
        p.y = randomY ? random.nextFloat() * h : h + 10.0f;
        p.speedY = 10.0f + random.nextFloat() * 24.0f;
        p.swayFreq = 0.8f + random.nextFloat() * 1.6f;
        p.swayAmp = 4.0f + random.nextFloat() * 10.0f;
        p.phase = random.nextFloat() * 6.283185f;
        p.size = 1.2f + random.nextFloat() * 2.1f;
        p.maxAlpha = 0.28f + random.nextFloat() * 0.58f;
        p.age = randomY ? random.nextFloat() * 6.0f : 0.0f;
        p.maxAge = 8.0f + random.nextFloat() * 9.0f;

        float roll = random.nextFloat();
        if (roll < 0.48f) {
            p.color = random.nextBoolean() ? COL_CYAN_BRIGHT : COL_CYAN_SOFT;
        } else if (roll < 0.90f) {
            p.color = random.nextBoolean() ? COL_ORANGE_BRIGHT : COL_ORANGE_SOFT;
        } else {
            p.color = COL_VIOLET_ORCHID;
        }
    }

    private void updateInteractiveStates(int mouseX, int mouseY, float dt) {
        float hoverSmoothing = 1.0f - (float) Math.exp(-13.0 * dt);
        float pressSmoothing = 1.0f - (float) Math.exp(-22.0 * dt);

        for (HeroCard card : heroCards) {
            boolean hovered = card.contains(mouseX, mouseY);
            card.hovered = hovered;
            card.hoverAnim = Mth.lerp(hoverSmoothing, card.hoverAnim, hovered ? 1.0f : 0.0f);
            card.pressAnim = Mth.lerp(pressSmoothing, card.pressAnim, card.pressed ? 1.0f : 0.0f);
        }

        for (DockButton btn : dockButtons) {
            boolean hovered = btn.contains(mouseX, mouseY);
            btn.hovered = hovered;
            btn.hoverAnim = Mth.lerp(hoverSmoothing, btn.hoverAnim, hovered ? 1.0f : 0.0f);
            btn.pressAnim = Mth.lerp(pressSmoothing, btn.pressAnim, btn.pressed ? 1.0f : 0.0f);
        }
    }

    private void renderCosmicBackground(GuiGraphics graphics) {
        int w = this.width;
        int h = this.height;

        graphics.fill(0, 0, w, h, COL_BG_VOID);

        float normMouseX = w > 0 ? ((float) this.mouseXPos / w - 0.5f) : 0.0f;
        float normMouseY = h > 0 ? ((float) this.mouseYPos / h - 0.5f) : 0.0f;

        float breathe = 0.5f + 0.5f * (float) Math.sin(this.elapsedSeconds * 0.55f);
        int radialCenterX = (int) (w * 0.5f + Math.sin(this.elapsedSeconds * 0.35f) * (w * 0.045f) - normMouseX * 18.0f);
        int radialCenterY = (int) (h * 0.44f + Math.cos(this.elapsedSeconds * 0.28f) * (h * 0.035f) - normMouseY * 14.0f);

        int maxRadiusX = (int) (w * 0.72f);
        int maxRadiusY = (int) (h * 0.78f);
        int innerColor = lerpColor(breathe, COL_BG_DEEP_VIOLET, COL_BG_NEBULA_VIOLET);

        drawRadialGradient(graphics, radialCenterX, radialCenterY, maxRadiusX, maxRadiusY, innerColor, COL_BG_VOID, 26);

        int cyanGlowX = (int) (w * 0.24f + Math.cos(this.elapsedSeconds * 0.42f) * 22.0f - normMouseX * 24.0f);
        int cyanGlowY = (int) (h * 0.48f + Math.sin(this.elapsedSeconds * 0.38f) * 16.0f);
        drawSoftGlowOrb(graphics, cyanGlowX, cyanGlowY, (int) (w * 0.26f), (int) (h * 0.34f), COL_CYAN_SOFT, 0.09f + 0.03f * breathe);

        int orangeGlowX = (int) (w * 0.76f + Math.sin(this.elapsedSeconds * 0.40f) * 22.0f - normMouseX * 24.0f);
        int orangeGlowY = (int) (h * 0.50f + Math.cos(this.elapsedSeconds * 0.36f) * 16.0f);
        drawSoftGlowOrb(graphics, orangeGlowX, orangeGlowY, (int) (w * 0.26f), (int) (h * 0.34f), COL_ORANGE_SOFT, 0.085f + 0.03f * (1.0f - breathe));

        int gridSpacing = 44;
        int gridColor = withAlpha(COL_VIOLET_ORCHID, 0.035f);
        int offsetX = (int) ((this.elapsedSeconds * 6.0f - normMouseX * 10.0f) % gridSpacing);
        for (int gx = offsetX; gx < w; gx += gridSpacing) {
            graphics.fill(gx, 0, gx + 1, h, gridColor);
        }
        for (int gy = 24; gy < h; gy += gridSpacing) {
            graphics.fill(0, gy, w, gy + 1, gridColor);
        }

        graphics.fillGradient(0, 0, w, (int) (h * 0.16f), 0x8805030A, 0x0005030A);
        graphics.fillGradient(0, (int) (h * 0.84f), w, h, 0x0005030A, 0xA605030A);
    }

    private void renderParticles(GuiGraphics graphics, float masterAlpha) {
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            Particle p = particles[i];
            float lifeRatio = Mth.clamp(p.age / p.maxAge, 0.0f, 1.0f);
            float fadeIn = Mth.clamp(lifeRatio / 0.18f, 0.0f, 1.0f);
            float fadeOut = Mth.clamp((1.0f - lifeRatio) / 0.25f, 0.0f, 1.0f);
            float alpha = p.maxAlpha * fadeIn * fadeOut * masterAlpha;

            if (alpha <= 0.01f) continue;

            int px = Math.round(p.x);
            int py = Math.round(p.y);
            int coreRadius = Math.max(1, Math.round(p.size * 0.65f));
            int haloRadius = Math.max(2, Math.round(p.size * 2.1f));

            int haloColor = withAlpha(p.color, alpha * 0.24f);
            graphics.fill(px - haloRadius, py - haloRadius, px + haloRadius, py + haloRadius, haloColor);

            int coreColor = withAlpha(p.color, alpha);
            graphics.fill(px - coreRadius, py - coreRadius, px + coreRadius, py + coreRadius, coreColor);
        }
    }

    private void renderHeader(GuiGraphics graphics, float intro) {
        int centerX = this.width / 2;
        int slideOffset = Math.round((1.0f - intro) * -14.0f);
        int topY = Math.max(16, (int) (this.height * 0.075f)) + slideOffset;

        int badgeTextW = this.font.width(TXT_EDITION_BADGE);
        int badgeW = badgeTextW + 24;
        int badgeH = 18;
        int badgeX = centerX - badgeW / 2;

        drawRoundedRect(graphics, badgeX, topY, badgeW, badgeH, 9, withAlpha(COL_GLASS_ELEVATED, 0.78f * intro));
        drawRoundedBorder(graphics, badgeX, topY, badgeW, badgeH, 9, 1, withAlpha(COL_CYAN_BRIGHT, 0.55f * intro), withAlpha(COL_VIOLET_ORCHID, 0.45f * intro));
        graphics.drawString(this.font, TXT_EDITION_BADGE, centerX - badgeTextW / 2, topY + 5, withAlpha(COL_CYAN_SOFT, intro), false);

        int titleY = topY + 26;
        float titleScale = this.width < 520 ? 1.85f : 2.35f;
        int rawTitleW = this.font.width(TXT_MAIN_TITLE);

        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(centerX, titleY, 0.0f);
        pose.scale(titleScale, titleScale, 1.0f);

        graphics.drawString(this.font, TXT_MAIN_TITLE, -rawTitleW / 2 + 1, 1, withAlpha(COL_VIOLET_NEON, 0.45f * intro), false);
        graphics.drawString(this.font, TXT_MAIN_TITLE, -rawTitleW / 2, 0, withAlpha(COL_TEXT_PRIMARY, intro), true);
        pose.popPose();

        int lineY = titleY + Math.round(9 * titleScale) + 6;
        int lineHalfW = Math.min(130, (int) (this.width * 0.16f));
        drawHorizontalGradient(graphics, centerX - lineHalfW, lineY, centerX, lineY + 2, withAlpha(COL_CYAN_BRIGHT, 0.85f * intro), withAlpha(COL_VIOLET_ORCHID, 0.90f * intro));
        drawHorizontalGradient(graphics, centerX, lineY, centerX + lineHalfW, lineY + 2, withAlpha(COL_VIOLET_ORCHID, 0.90f * intro), withAlpha(COL_ORANGE_BRIGHT, 0.85f * intro));

        int subW = this.font.width(TXT_SUB_TAGLINE);
        graphics.drawString(this.font, TXT_SUB_TAGLINE, centerX - subW / 2, lineY + 7, withAlpha(COL_TEXT_MUTED, 0.92f * intro), false);
    }

    private void renderHeroCard(GuiGraphics graphics, HeroCard card, float intro) {
        if (intro <= 0.01f) return;

        float hover = card.hoverAnim;
        float press = card.pressAnim;
        float floatOffsetY = -6.0f * hover + (1.0f - intro) * 18.0f;
        float scale = 1.0f + 0.032f * hover - 0.018f * press;

        int x = card.x;
        int y = card.y;
        int w = card.width;
        int h = card.height;
        int radius = 14;

        float centerX = x + w * 0.5f;
        float centerY = y + h * 0.5f + floatOffsetY;

        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(centerX, centerY, 0.0f);
        pose.scale(scale, scale, 1.0f);
        pose.translate(-w * 0.5f, -h * 0.5f, 0.0f);

        if (hover > 0.01f) {
            int bloomSpread = Math.round(4 + 7 * hover);
            drawRoundedRect(graphics, -bloomSpread, -bloomSpread, w + bloomSpread * 2, h + bloomSpread * 2, radius + bloomSpread, withAlpha(card.accentSecondary, 0.07f * hover * intro));
            drawRoundedRect(graphics, -3, -3, w + 6, h + 6, radius + 3, withAlpha(card.accentPrimary, 0.14f * hover * intro));
        }

        int baseGlass = lerpColor(hover, COL_GLASS_BASE, COL_GLASS_ELEVATED);
        drawRoundedRect(graphics, 0, 0, w, h, radius, withAlpha(baseGlass, intro));

        int gradTop = lerpColor(hover, withAlpha(card.baseViolet, 0.34f), withAlpha(card.baseViolet, 0.58f));
        int gradBottom = lerpColor(hover, withAlpha(card.accentSecondary, 0.12f), withAlpha(card.accentPrimary, 0.42f));
        drawRoundedVerticalGradient(graphics, 1, 1, w - 2, h - 2, radius - 1, withAlpha(gradTop, intro), withAlpha(gradBottom, intro));

        int specularColor = lerpColor(hover, withAlpha(0xFFFFFFFF, 0.12f), withAlpha(card.accentPrimary, 0.45f));
        graphics.fill(radius, 1, w - radius, 2, withAlpha(specularColor, intro));

        int borderTop = lerpColor(hover, withAlpha(COL_VIOLET_ORCHID, 0.45f), card.accentPrimary);
        int borderBottom = lerpColor(hover, withAlpha(COL_VIOLET_NEON, 0.25f), card.accentSecondary);
        drawRoundedBorder(graphics, 0, 0, w, h, radius, hover > 0.45f ? 2 : 1, withAlpha(borderTop, intro), withAlpha(borderBottom, intro));

        int iconBoxSize = Math.min(44, Math.max(34, h / 5));
        int iconBoxX = (w - iconBoxSize) / 2;
        int iconBoxY = Math.max(14, (int) (h * 0.12f));

        int iconBg = lerpColor(hover, withAlpha(COL_BG_DEEP_VIOLET, 0.85f), withAlpha(card.accentPrimary, 0.22f));
        drawRoundedRect(graphics, iconBoxX, iconBoxY, iconBoxSize, iconBoxSize, 11, withAlpha(iconBg, intro));
        drawRoundedBorder(graphics, iconBoxX, iconBoxY, iconBoxSize, iconBoxSize, 11, 1, withAlpha(card.accentPrimary, (0.45f + 0.55f * hover) * intro), withAlpha(card.baseViolet, 0.50f * intro));

        renderCardGlyph(graphics, card, iconBoxX + iconBoxSize / 2, iconBoxY + iconBoxSize / 2, intro);

        int tagY = iconBoxY + iconBoxSize + 10;
        int tagW = this.font.width(card.tag);
        int tagColor = lerpColor(hover, card.accentSecondary, card.accentPrimary);
        graphics.drawString(this.font, card.tag, (w - tagW) / 2, tagY, withAlpha(tagColor, intro), false);

        int titleY = tagY + 13;
        float cardTitleScale = w < 185 ? 1.25f : 1.48f;
        int titleRawW = this.font.width(card.title);

        pose.pushPose();
        pose.translate(w * 0.5f, titleY, 0.0f);
        pose.scale(cardTitleScale, cardTitleScale, 1.0f);
        graphics.drawString(this.font, card.title, -titleRawW / 2, 0, withAlpha(COL_TEXT_PRIMARY, intro), true);
        pose.popPose();

        int desc1Y = titleY + Math.round(10 * cardTitleScale) + 8;
        int desc1W = this.font.width(card.descriptionPrimary);
        graphics.drawString(this.font, card.descriptionPrimary, (w - desc1W) / 2, desc1Y, withAlpha(COL_TEXT_SECONDARY, intro), false);

        if (h >= 180) {
            int desc2Y = desc1Y + 12;
            int desc2W = this.font.width(card.descriptionSecondary);
            if (desc2W <= w - 16) {
                graphics.drawString(this.font, card.descriptionSecondary, (w - desc2W) / 2, desc2Y, withAlpha(COL_TEXT_MUTED, 0.88f * intro), false);
            }
        }

        int actionBarH = 22;
        int actionBarW = Math.min(w - 28, 136);
        int actionBarX = (w - actionBarW) / 2;
        int actionBarY = h - actionBarH - 14;

        int pillFill = lerpColor(hover, withAlpha(COL_BG_VOID, 0.55f), withAlpha(card.accentPrimary, 0.28f));
        drawRoundedRect(graphics, actionBarX, actionBarY, actionBarW, actionBarH, 11, withAlpha(pillFill, intro));
        drawRoundedBorder(graphics, actionBarX, actionBarY, actionBarW, actionBarH, 11, 1, withAlpha(card.accentPrimary, (0.30f + 0.70f * hover) * intro), withAlpha(card.accentSecondary, (0.25f + 0.65f * hover) * intro));

        int shiftTextX = Math.round(hover * 2.0f);
        int actionW = this.font.width(card.actionLabel);
        int actionTextColor = lerpColor(hover, COL_TEXT_SECONDARY, COL_TEXT_PRIMARY);
        graphics.drawString(this.font, card.actionLabel, (w - actionW) / 2 + shiftTextX, actionBarY + 7, withAlpha(actionTextColor, intro), false);

        int indicatorMaxW = w - radius * 2;
        int indicatorW = Math.round(indicatorMaxW * (0.22f + 0.78f * hover));
        int indicatorX = (w - indicatorW) / 2;
        int indicatorColor = lerpColor(hover, withAlpha(card.accentSecondary, 0.45f), card.accentPrimary);
        graphics.fill(indicatorX, h - 2, indicatorX + indicatorW, h, withAlpha(indicatorColor, intro));

        pose.popPose();
    }

    private void renderCardGlyph(GuiGraphics graphics, HeroCard card, int cx, int cy, float intro) {
        int primary = withAlpha(card.accentPrimary, intro);
        int secondary = withAlpha(card.accentSecondary, intro);
        int core = withAlpha(COL_TEXT_PRIMARY, intro);

        if (card.type == CardType.SINGLEPLAYER) {
            graphics.fill(cx - 1, cy - 10, cx + 1, cy + 10, primary);
            graphics.fill(cx - 10, cy - 1, cx + 10, cy + 1, primary);

            for (int d = 0; d <= 7; d++) {
                int span = 7 - d;
                graphics.fill(cx - span, cy - d, cx - span + 2, cy - d + 1, secondary);
                graphics.fill(cx + span - 2, cy - d, cx + span, cy - d + 1, secondary);
                graphics.fill(cx - span, cy + d, cx - span + 2, cy + d + 1, secondary);
                graphics.fill(cx + span - 2, cy + d, cx + span, cy + d + 1, secondary);
            }
            graphics.fill(cx - 2, cy - 2, cx + 2, cy + 2, core);
        } else {
            renderOutline(graphics, cx - 9, cy - 9, 18, 18, withAlpha(secondary, 0.65f * intro));
            graphics.fill(cx - 6, cy - 4, cx + 6, cy - 3, primary);
            graphics.fill(cx - 6, cy + 3, cx + 6, cy + 4, primary);
            graphics.fill(cx, cy - 6, cx + 1, cy + 6, secondary);

            graphics.fill(cx - 8, cy - 6, cx - 4, cy - 2, core);
            graphics.fill(cx + 4, cy - 6, cx + 8, cy - 2, core);
            graphics.fill(cx - 2, cy + 2, cx + 2, cy + 6, primary);
        }
    }

    private void renderDock(GuiGraphics graphics, float intro) {
        if (intro <= 0.01f || dockButtons.length == 0) return;

        int paddingX = 10;
        int paddingY = 6;
        int dockLeft = dockButtons[0].x - paddingX;
        int dockRight = dockButtons[dockButtons.length - 1].x + dockButtons[dockButtons.length - 1].width + paddingX;
        int dockTop = dockButtons[0].y - paddingY;
        int dockW = dockRight - dockLeft;
        int dockH = dockButtons[0].height + paddingY * 2;

        drawRoundedRect(graphics, dockLeft, dockTop, dockW, dockH, 16, withAlpha(COL_GLASS_BASE, 0.82f * intro));
        drawRoundedBorder(graphics, dockLeft, dockTop, dockW, dockH, 16, 1, withAlpha(COL_VIOLET_ORCHID, 0.36f * intro), withAlpha(COL_CYAN_SOFT, 0.25f * intro));

        for (DockButton btn : dockButtons) {
            renderDockPill(graphics, btn, intro);
        }
    }

    private void renderDockPill(GuiGraphics graphics, DockButton btn, float intro) {
        float hover = btn.hoverAnim;
        float press = btn.pressAnim;
        float offsetY = -2.0f * hover + 1.0f * press;

        int x = btn.x;
        int y = Math.round(btn.y + offsetY);
        int w = btn.width;
        int h = btn.height;
        int radius = h / 2;

        if (hover > 0.01f) {
            drawRoundedRect(graphics, x - 2, y - 2, w + 4, h + 4, radius + 2, withAlpha(btn.accentColor, 0.16f * hover * intro));
        }

        int pillFill = lerpColor(hover, withAlpha(COL_BG_DEEP_VIOLET, 0.72f), withAlpha(btn.accentColor, 0.24f));
        drawRoundedRect(graphics, x, y, w, h, radius, withAlpha(pillFill, intro));

        int borderCol = lerpColor(hover, withAlpha(COL_VIOLET_NEON, 0.40f), btn.accentColor);
        drawRoundedBorder(graphics, x, y, w, h, radius, 1, withAlpha(borderCol, intro), withAlpha(borderCol, 0.65f * intro));

        int textW = this.font.width(btn.label);
        int iconSize = 10;
        int spacing = 5;
        int contentW = iconSize + spacing + textW;
        int startX = x + (w - contentW) / 2;
        int centerY = y + h / 2;

        int iconColor = lerpColor(hover, withAlpha(btn.accentColor, 0.85f), COL_TEXT_PRIMARY);
        renderDockGlyph(graphics, btn.icon, startX + iconSize / 2, centerY, withAlpha(iconColor, intro));

        int labelColor = lerpColor(hover, COL_TEXT_SECONDARY, COL_TEXT_PRIMARY);
        graphics.drawString(this.font, btn.label, startX + iconSize + spacing, y + (h - 8) / 2, withAlpha(labelColor, intro), false);
    }

    private void renderDockGlyph(GuiGraphics graphics, DockIcon icon, int cx, int cy, int color) {
        switch (icon) {
            case OPTIONS -> {
                graphics.fill(cx - 4, cy - 3, cx + 4, cy - 2, color);
                graphics.fill(cx - 2, cy - 4, cx, cy - 1, color);
                graphics.fill(cx - 4, cy, cx + 4, cy + 1, color);
                graphics.fill(cx + 1, cy - 1, cx + 3, cy + 2, color);
                graphics.fill(cx - 4, cy + 3, cx + 4, cy + 4, color);
                graphics.fill(cx - 3, cy + 2, cx - 1, cy + 5, color);
            }
            case MODS -> {
                graphics.fill(cx - 4, cy - 4, cx - 1, cy - 1, color);
                graphics.fill(cx + 1, cy - 4, cx + 4, cy - 1, color);
                graphics.fill(cx - 4, cy + 1, cx - 1, cy + 4, color);
                renderOutline(graphics, cx + 1, cy + 1, 3, 3, color);
            }
            case BYPASS -> {
                graphics.fill(cx - 4, cy - 4, cx + 4, cy - 3, color);
                graphics.fill(cx - 3, cy - 3, cx + 3, cy + 1, color);
                graphics.fill(cx - 2, cy + 1, cx + 2, cy + 3, color);
                graphics.fill(cx - 1, cy + 3, cx + 1, cy + 5, color);
            }
            case LANGUAGE -> {
                renderOutline(graphics, cx - 4, cy - 4, 8, 8, color);
                graphics.fill(cx - 4, cy, cx + 4, cy + 1, color);
                graphics.fill(cx, cy - 4, cx + 1, cy + 4, color);
            }
            case QUIT -> {
                graphics.fill(cx, cy - 5, cx + 1, cy, color);
                graphics.fill(cx - 4, cy - 2, cx - 3, cy + 3, color);
                graphics.fill(cx + 3, cy - 2, cx + 4, cy + 3, color);
                graphics.fill(cx - 3, cy + 3, cx + 4, cy + 4, color);
            }
        }
    }

    private void renderStatusFooter(GuiGraphics graphics, float intro) {
        if (intro <= 0.01f) return;

        int footerY = this.height - 16;
        graphics.drawString(this.font, TXT_FOOTER_VERSION, 12, footerY, withAlpha(COL_TEXT_MUTED, 0.78f * intro), false);

        int nameW = this.font.width(this.cachedPlayerBadge);
        int rightX = this.width - 12 - nameW;
        float pulse = 0.55f + 0.45f * (float) Math.sin(this.elapsedSeconds * 3.2f);

        graphics.fill(rightX - 11, footerY + 1, rightX - 5, footerY + 7, withAlpha(COL_CYAN_BRIGHT, 0.25f * pulse * intro));
        graphics.fill(rightX - 10, footerY + 2, rightX - 6, footerY + 6, withAlpha(COL_CYAN_BRIGHT, pulse * intro));
        graphics.drawString(this.font, this.cachedPlayerBadge, rightX, footerY, withAlpha(COL_TEXT_SECONDARY, 0.88f * intro), false);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        this.mouseXPos = mouseX;
        this.mouseYPos = mouseY;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            for (HeroCard card : heroCards) {
                if (card.contains(mouseX, mouseY)) {
                    card.pressed = true;
                    playClickSound();
                    card.action.run();
                    return true;
                }
            }

            for (DockButton btn : dockButtons) {
                if (btn.contains(mouseX, mouseY)) {
                    btn.pressed = true;
                    playClickSound();
                    btn.action.run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            for (HeroCard card : heroCards) card.pressed = false;
            for (DockButton btn : dockButtons) btn.pressed = false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    private void playClickSound() {
        if (this.minecraft != null) {
            this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }

    private void openSingleplayer() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(new AstraSingleplayerScreen(this));
        }
    }

    private void openMultiplayer() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(new AstraMultiplayerScreen(this));
        }
    }

    private void openOptions() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(new OptionsScreen(this, this.minecraft.options));
        }
    }

    private void openLanguage() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(new LanguageSelectScreen(this, this.minecraft.options, this.minecraft.getLanguageManager()));
        }
    }

    private void openBypass() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(new BaritoneBypassScreen());
        }
    }

    private void openMods() {
        if (this.minecraft == null) return;
        try {
            Class<?> flClass = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object instance = flClass.getMethod("getInstance").invoke(null);
            boolean isLoaded = (boolean) flClass.getMethod("isModLoaded", String.class).invoke(instance, "modmenu");
            if (isLoaded) {
                Class<?> modsScreenClass = Class.forName("com.terraformersmc.modmenu.gui.ModsScreen");
                Constructor<?> constructor = modsScreenClass.getConstructor(Screen.class);
                Screen modsScreen = (Screen) constructor.newInstance(this);
                this.minecraft.setScreen(modsScreen);
            }
        } catch (Throwable ignored) {}
    }

    private void quitGame() {
        if (this.minecraft != null) {
            System.setProperty("astra.allowStop", "true");
            this.minecraft.stop();
        }
    }

    // =========================================================================
    // PROCEDURALNE HELPERY RYSOWANIA
    // =========================================================================
    private static void renderOutline(GuiGraphics graphics, int x, int y, int w, int h, int color) {
        graphics.fill(x, y, x + w, y + 1, color);
        graphics.fill(x, y + h - 1, x + w, y + h, color);
        graphics.fill(x, y + 1, x + 1, y + h - 1, color);
        graphics.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    private static void drawRadialGradient(GuiGraphics graphics, int centerX, int centerY, int radiusX, int radiusY, int centerColor, int edgeColor, int steps) {
        for (int i = steps; i >= 1; i--) {
            float t = (float) i / (float) steps;
            int color = lerpColor(t * t, centerColor, edgeColor);
            int rx = Math.round(radiusX * t);
            int ry = Math.round(radiusY * t);
            drawRoundedRect(graphics, centerX - rx, centerY - ry, rx * 2, ry * 2, Math.min(rx, ry) / 2, color);
        }
    }

    private static void drawSoftGlowOrb(GuiGraphics graphics, int centerX, int centerY, int radiusX, int radiusY, int baseColor, float peakAlpha) {
        int layers = 10;
        for (int i = layers; i >= 1; i--) {
            float t = (float) i / (float) layers;
            float alpha = peakAlpha * (1.0f - t) * (1.0f - t);
            int rx = Math.round(radiusX * t);
            int ry = Math.round(radiusY * t);
            drawRoundedRect(graphics, centerX - rx, centerY - ry, rx * 2, ry * 2, Math.min(rx, ry) / 2, withAlpha(baseColor, alpha));
        }
    }

    private static void drawHorizontalGradient(GuiGraphics graphics, int startX, int startY, int endX, int endY, int colorLeft, int colorRight) {
        int totalW = endX - startX;
        if (totalW <= 0) return;
        int slices = Math.min(totalW, 24);
        for (int i = 0; i < slices; i++) {
            int x1 = startX + (totalW * i) / slices;
            int x2 = startX + (totalW * (i + 1)) / slices;
            float t = (i + 0.5f) / slices;
            graphics.fill(x1, startY, x2, endY, lerpColor(t, colorLeft, colorRight));
        }
    }

    private static void drawRoundedRect(GuiGraphics graphics, int x, int y, int width, int height, int radius, int color) {
        if (width <= 0 || height <= 0 || ((color >>> 24) & 0xFF) == 0) return;
        int r = Math.max(0, Math.min(radius, Math.min(width / 2, height / 2)));
        if (r == 0) {
            graphics.fill(x, y, x + width, y + height, color);
            return;
        }

        for (int dy = 0; dy < r; dy++) {
            double dist = r - dy - 0.5;
            int inset = (int) Math.round(r - Math.sqrt(Math.max(0.0, (double) r * r - dist * dist)));
            graphics.fill(x + inset, y + dy, x + width - inset, y + dy + 1, color);
            graphics.fill(x + inset, y + height - 1 - dy, x + width - inset, y + height - dy, color);
        }
        graphics.fill(x, y + r, x + width, y + height - r, color);
    }

    private static void drawRoundedVerticalGradient(GuiGraphics graphics, int x, int y, int width, int height, int radius, int colorTop, int colorBottom) {
        if (width <= 0 || height <= 0) return;
        int r = Math.max(0, Math.min(radius, Math.min(width / 2, height / 2)));

        for (int dy = 0; dy < r; dy++) {
            double dist = r - dy - 0.5;
            int inset = (int) Math.round(r - Math.sqrt(Math.max(0.0, (double) r * r - dist * dist)));

            float tTop = (float) dy / Math.max(1, height - 1);
            graphics.fill(x + inset, y + dy, x + width - inset, y + dy + 1, lerpColor(tTop, colorTop, colorBottom));

            int bottomRow = height - 1 - dy;
            float tBot = (float) bottomRow / Math.max(1, height - 1);
            graphics.fill(x + inset, y + bottomRow, x + width - inset, y + bottomRow + 1, lerpColor(tBot, colorTop, colorBottom));
        }

        if (height > r * 2) {
            float tMidStart = (float) r / Math.max(1, height - 1);
            float tMidEnd = (float) (height - r) / Math.max(1, height - 1);
            graphics.fillGradient(x, y + r, x + width, y + height - r, lerpColor(tMidStart, colorTop, colorBottom), lerpColor(tMidEnd, colorTop, colorBottom));
        }
    }

    private static void drawRoundedBorder(GuiGraphics graphics, int x, int y, int width, int height, int radius, int thickness, int colorTop, int colorBottom) {
        if (width <= 0 || height <= 0 || thickness <= 0) return;
        int rOuter = Math.max(0, Math.min(radius, Math.min(width / 2, height / 2)));
        int rInner = Math.max(0, rOuter - thickness);

        for (int row = 0; row < height; row++) {
            float t = (float) row / Math.max(1, height - 1);
            int rowColor = lerpColor(t, colorTop, colorBottom);

            int outerInset = computeCornerInset(row, height, rOuter);
            if (row < thickness || row >= height - thickness) {
                graphics.fill(x + outerInset, y + row, x + width - outerInset, y + row + 1, rowColor);
            } else {
                int innerInset = computeCornerInset(row - thickness, height - thickness * 2, rInner);
                int leftEnd = Math.min(x + width - outerInset, x + thickness + innerInset);
                int rightStart = Math.max(x + outerInset, x + width - thickness - innerInset);

                graphics.fill(x + outerInset, y + row, leftEnd, y + row + 1, rowColor);
                graphics.fill(rightStart, y + row, x + width - outerInset, y + row + 1, rowColor);
            }
        }
    }

    private static int computeCornerInset(int row, int totalHeight, int radius) {
        if (radius <= 0) return 0;
        if (row < radius) {
            double dist = radius - row - 0.5;
            return (int) Math.round(radius - Math.sqrt(Math.max(0.0, (double) radius * radius - dist * dist)));
        }
        int bottomDist = totalHeight - 1 - row;
        if (bottomDist < radius) {
            double dist = radius - bottomDist - 0.5;
            return (int) Math.round(radius - Math.sqrt(Math.max(0.0, (double) radius * radius - dist * dist)));
        }
        return 0;
    }

    private static int lerpColor(float t, int colorA, int colorB) {
        float clamped = Mth.clamp(t, 0.0f, 1.0f);
        int a1 = (colorA >>> 24) & 0xFF;
        int r1 = (colorA >>> 16) & 0xFF;
        int g1 = (colorA >>> 8) & 0xFF;
        int b1 = colorA & 0xFF;

        int a2 = (colorB >>> 24) & 0xFF;
        int r2 = (colorB >>> 16) & 0xFF;
        int g2 = (colorB >>> 8) & 0xFF;
        int b2 = colorB & 0xFF;

        int a = Math.round(a1 + (a2 - a1) * clamped);
        int r = Math.round(r1 + (r2 - r1) * clamped);
        int g = Math.round(g1 + (g2 - g1) * clamped);
        int b = Math.round(b1 + (b2 - b1) * clamped);

        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int withAlpha(int argb, float alphaMultiplier) {
        int baseAlpha = (argb >>> 24) & 0xFF;
        int finalAlpha = Mth.clamp(Math.round(baseAlpha * alphaMultiplier), 0, 255);
        return (finalAlpha << 24) | (argb & 0x00FFFFFF);
    }

    private static float easeOutCubic(float x) {
        float inv = 1.0f - Mth.clamp(x, 0.0f, 1.0f);
        return 1.0f - inv * inv * inv;
    }

    private enum CardType { SINGLEPLAYER, MULTIPLAYER }
    private enum DockIcon { OPTIONS, MODS, BYPASS, LANGUAGE, QUIT }

    private static final class Particle {
        float x, y, speedY, swayFreq, swayAmp, phase, size, maxAlpha, age, maxAge;
        int color;
    }

    private static final class HeroCard {
        final CardType type;
        final Component tag, title, descriptionPrimary, descriptionSecondary, actionLabel;
        final int baseViolet, accentPrimary, accentSecondary;
        final Runnable action;
        int x, y, width, height;
        boolean hovered, pressed;
        float hoverAnim, pressAnim;

        HeroCard(CardType type, Component tag, Component title, Component descriptionPrimary, Component descriptionSecondary, Component actionLabel, int baseViolet, int accentPrimary, int accentSecondary, Runnable action) {
            this.type = type;
            this.tag = tag;
            this.title = title;
            this.descriptionPrimary = descriptionPrimary;
            this.descriptionSecondary = descriptionSecondary;
            this.actionLabel = actionLabel;
            this.baseViolet = baseViolet;
            this.accentPrimary = accentPrimary;
            this.accentSecondary = accentSecondary;
            this.action = action;
        }

        void setBounds(int x, int y, int width, int height) {
            this.x = x; this.y = y; this.width = width; this.height = height;
        }

        boolean contains(double mx, double my) {
            return mx >= this.x && mx <= this.x + this.width && my >= this.y && my <= this.y + this.height;
        }
    }

    private static final class DockButton {
        final DockIcon icon;
        final Component label;
        final int accentColor;
        final Runnable action;
        int x, y, width, height;
        boolean hovered, pressed;
        float hoverAnim, pressAnim;

        DockButton(DockIcon icon, Component label, int accentColor, Runnable action) {
            this.icon = icon;
            this.label = label;
            this.accentColor = accentColor;
            this.action = action;
        }

        void setBounds(int x, int y, int width, int height) {
            this.x = x; this.y = y; this.width = width; this.height = height;
        }

        boolean contains(double mx, double my) {
            return mx >= this.x && mx <= this.x + this.width && my >= this.y && my <= this.y + this.height;
        }
    }
}
