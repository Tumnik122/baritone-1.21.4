package com.astra.revolution.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.options.SkinCustomizationScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import com.astra.revolution.nativebridge.AstraNativeTitleRenderer;
import org.lwjgl.glfw.GLFW;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 🌌 Apple iOS / macOS Liquid Glass TitleScreen for Astra Client 1.21.4.
 * Designed for 100% stability, zero heap allocations in the render loop,
 * and high-fidelity aesthetics matching the iOS lockscreen / liquid glass reference design.
 */
public class ModernAestheticTitleScreen extends Screen {

    public static volatile boolean bypassToVanillaOnce = false;

    private static final ResourceLocation BG_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("astra", "textures/gui/title_bg.png");
    private static final ResourceLocation ANARCHIA_ICON =
            ResourceLocation.fromNamespaceAndPath("astra", "textures/gui/anarchia_icon.png");

    private static final String COPYRIGHT_TEXT = "BK-Client is not affiliated with Mojang Studios or Microsoft";
    private static final String VERSION_TEXT = "3.0.8 • Minecraft 1.21.4";
    private static final String ONLINE_STATUS_TEXT = "Online";

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH);
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

    private static final String[] TRACK_TITLES = {
            "The First War",
            "Blizzard Over Lake Astra",
            "Obsidian & Moonlight"
    };
    private static final String[] TRACK_ARTISTS = {
            "Unstable SMP",
            "Astra Soundscapes",
            "C418 • Ambient Mix"
    };
    private static final int[] TRACK_DURATIONS_SEC = {
            239 * 60 + 3,
            4 * 60 + 18,
            6 * 60 + 42
    };

    private static final String[] DOCK_TOOLTIPS = {
            "Options",
            "Customization",
            "Baritone",
            "Quit Game"
    };
    private final int[] dockTooltipWidths = new int[DOCK_TOOLTIPS.length];

    // Color Constants (Liquid Glass & Dark Neumorphism)
    private static final int COL_GLASS_BASE       = 0xB80B1220;
    private static final int COL_GLASS_HOVER      = 0xD0131E33;
    private static final int COL_BORDER_NORMAL    = 0x33CBD5E1;
    private static final int COL_BORDER_HOVER     = 0x66E2E8F0;
    private static final int COL_SPECULAR_RIM     = 0x4DFFFFFF;
    private static final int COL_GLOW_AMBER       = 0xFFF59E0B;
    private static final int COL_GLOW_EMERALD     = 0xFF10B981;

    // Layout coordinates (cached in init, zero-allocation during render)
    private float centerX;
    private float dateY;
    private float clockY;
    private float clockScale;

    private float spPillX, spPillY;
    private float mpPillX, mpPillY;
    private float modePillW, modePillH;

    private float continueX, continueY;
    private float cardW, continueH;
    private float playCircleX, playCircleY, playCircleR;

    private float musicX, musicY, musicW, musicH;
    private float coverX, coverY, coverSize;
    private float sliderX, sliderY, sliderW, sliderH;
    private float playPauseBtnX, playPauseBtnY;
    private float prevBtnX, prevBtnY;
    private float nextBtnX, nextBtnY;
    private float mediaBtnR;

    private float profilePillX, profilePillY, profilePillW, profilePillH;
    private float profileDotsX, profileDotsW;
    private float profileDropdownX, profileDropdownY, profileDropdownW, profileDropdownH;

    private float dockX, dockY, dockW, dockH;
    private final float[] dockIconCenterX = new float[4];

    // Responsive widths
    private int cachedPlayerNameWidth = 40;
    private int cachedOnlineWidth = 24;
    private int copyrightWidth = 140;
    private int versionWidth = 70;
    private int lastServerInitialsWidth = 14;

    // Time & Strings cache
    private long lastCheckedEpochSecond = -1L;
    private int lastCheckedMinute = -1;
    private int lastCheckedDay = -1;
    private String cachedTimeText = "20:43";
    private int cachedTimeWidth = 48;
    private String cachedDateText = "Friday, 13 November";
    private int cachedDateWidth = 96;

    // Music Player State (Dynamic Spotify Integration)
    private boolean spotifyActive = false;
    private String spotifyArtist = "";
    private String spotifyTitle = "";
    private float spotifyPollTimer = 0.0f;

    private int currentTrackIndex = 0;
    private float trackElapsedSeconds = 1.0f;
    private boolean musicPlaying = true;
    private boolean draggingSlider = false;
    private final StringBuilder timeFormatBuilder = new StringBuilder(16);
    private String cachedElapsedText = "0:01";
    private String cachedTotalText = "239:03";
    private int cachedTotalTextWidth = 32;
    private int lastRenderedMusicSecond = -1;

    // Profile & Quick-join state
    private String cachedPlayerName = "Gamer6844";
    private PlayerSkin playerSkin = null;
    private String lastServerName = "anarchia.gg";
    private String lastServerAddress = "anarchia.gg";
    private String lastServerInitials = "A";
    private String continueSubtitle = "Continue • Survival + Anarchy";
    private boolean profileMenuOpen = false;
    private boolean copiedUsernameFeedback = false;
    private float copiedFeedbackTimer = 0.0f;

    // Animations (smoothed delta-time lerps)
    private long lastFrameNanos = 0L;
    private float deltaTime = 0.016f;
    private float totalElapsedTime = 0.0f;

    private float normMouseX = 0.0f;
    private float normMouseY = 0.0f;

    private float spHoverAnim = 0.0f;
    private float mpHoverAnim = 0.0f;
    private float continueHoverAnim = 0.0f;
    private float continuePlayHoverAnim = 0.0f;

    private float sliderHoverAnim = 0.0f;
    private float prevHoverAnim = 0.0f;
    private float playPauseHoverAnim = 0.0f;
    private float nextHoverAnim = 0.0f;

    private float profileHoverAnim = 0.0f;
    private float profileDotsHoverAnim = 0.0f;
    private float profileMenuAnim = 0.0f;

    private final float[] dockHoverAnim = new float[4];
    private int hoveredDockIndex = -1;

    // Atmospheric Firefly Particles (Zero allocation, primitive arrays)
    private static final int FIREFLY_COUNT = 36;
    private final float[] fireflyX = new float[FIREFLY_COUNT];
    private final float[] fireflyY = new float[FIREFLY_COUNT];
    private final float[] fireflySpeedY = new float[FIREFLY_COUNT];
    private final float[] fireflyPhase = new float[FIREFLY_COUNT];
    private final float[] fireflySize = new float[FIREFLY_COUNT];
    private final float[] fireflyAlpha = new float[FIREFLY_COUNT];
    private boolean firefliesInitialized = false;

    public ModernAestheticTitleScreen() {
        super(Component.literal("Title Screen"));
    }

    @Override
    protected void init() {
        super.init();

        try {
            this.lastFrameNanos = System.nanoTime();
            Minecraft mc = this.minecraft != null ? this.minecraft : Minecraft.getInstance();

            this.cachedPlayerName = (mc.getUser() != null) ? mc.getUser().getName() : "Gamer6844";
            try {
                this.playerSkin = (mc.getSkinManager() != null && mc.getGameProfile() != null)
                        ? mc.getSkinManager().getInsecureSkin(mc.getGameProfile())
                        : null;
            } catch (Throwable t) {
                this.playerSkin = null;
            }

            if (this.font != null) {
                this.cachedPlayerNameWidth = this.font.width(this.cachedPlayerName);
                this.cachedOnlineWidth = this.font.width(ONLINE_STATUS_TEXT);
                this.copyrightWidth = this.font.width(COPYRIGHT_TEXT);
                this.versionWidth = this.font.width(VERSION_TEXT);
                this.lastServerInitialsWidth = this.font.width(this.lastServerInitials);

                for (int i = 0; i < DOCK_TOOLTIPS.length; i++) {
                    this.dockTooltipWidths[i] = this.font.width(DOCK_TOOLTIPS[i]);
                }
            } else {
                this.cachedPlayerNameWidth = 40;
                this.cachedOnlineWidth = 24;
                this.copyrightWidth = 140;
                this.versionWidth = 70;
                this.lastServerInitialsWidth = 14;
                for (int i = 0; i < DOCK_TOOLTIPS.length; i++) {
                    this.dockTooltipWidths[i] = 32;
                }
            }

            this.lastCheckedEpochSecond = -1L;
            this.lastRenderedMusicSecond = -1;
            updateClockStringsIfNeeded();
            syncTrackMetadataCache();

            computeResponsiveLayout();
            initFirefliesIfNeeded();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private void initFirefliesIfNeeded() {
        if (this.firefliesInitialized) return;
        int w = Math.max(100, this.width);
        int h = Math.max(100, this.height);
        java.util.Random rnd = new java.util.Random(1337L);
        for (int i = 0; i < FIREFLY_COUNT; i++) {
            this.fireflyX[i] = rnd.nextFloat() * w;
            this.fireflyY[i] = rnd.nextFloat() * h;
            this.fireflySpeedY[i] = 8.0f + rnd.nextFloat() * 14.0f;
            this.fireflyPhase[i] = rnd.nextFloat() * 6.28f;
            this.fireflySize[i] = 1.0f + rnd.nextFloat() * 2.0f;
            this.fireflyAlpha[i] = 0.35f + rnd.nextFloat() * 0.55f;
        }
        this.firefliesInitialized = true;
    }

    private void computeResponsiveLayout() {
        this.centerX = this.width * 0.5f;

        boolean compact = this.height < 520;
        this.dateY = compact ? 22.0f : Math.max(28.0f, this.height * 0.075f);
        this.clockY = this.dateY + 13.0f;
        this.clockScale = compact ? 2.8f : Math.max(3.2f, Math.min(3.8f, this.height * 0.0075f));

        float contentTop = this.clockY + (10.0f * this.clockScale) + (compact ? 12.0f : 18.0f);
        this.cardW = Math.min(320.0f, Math.max(260.0f, this.width * 0.34f));
        this.modePillH = compact ? 28.0f : 32.0f;
        this.continueH = compact ? 44.0f : 48.0f;
        this.musicW = this.spotifyActive ? this.cardW : 0.0f;
        this.musicH = this.spotifyActive ? (compact ? 68.0f : 76.0f) : 0.0f;

        float pillGap = 8.0f;
        this.modePillW = (this.cardW - pillGap) * 0.5f;

        this.spPillX = this.centerX - (this.cardW * 0.5f);
        this.spPillY = contentTop;
        this.mpPillX = this.spPillX + this.modePillW + pillGap;
        this.mpPillY = contentTop;

        float stackGap = compact ? 6.0f : 8.0f;

        this.continueX = this.spPillX;
        this.continueY = this.spPillY + this.modePillH + stackGap;
        this.playCircleR = 12.0f;
        this.playCircleX = this.continueX + this.cardW - 22.0f;
        this.playCircleY = this.continueY + (this.continueH * 0.5f);

        this.musicX = this.spPillX;
        this.musicY = this.continueY + this.continueH + stackGap;
        this.coverSize = compact ? 38.0f : 44.0f;
        this.coverX = this.musicX + 10.0f;
        this.coverY = this.musicY + 9.0f;

        this.sliderX = this.coverX + this.coverSize + 10.0f;
        this.sliderW = (this.musicX + this.musicW - 12.0f) - this.sliderX;
        this.sliderY = this.coverY + this.coverSize - 10.0f;
        this.sliderH = 3.0f;

        float controlsCenterX = this.sliderX + (this.sliderW * 0.5f);
        float controlsCenterY = this.musicY + this.musicH - 12.0f;
        this.mediaBtnR = 9.0f;
        this.playPauseBtnX = controlsCenterX;
        this.playPauseBtnY = controlsCenterY;
        this.prevBtnX = controlsCenterX - 28.0f;
        this.prevBtnY = controlsCenterY;
        this.nextBtnX = controlsCenterX + 28.0f;
        this.nextBtnY = controlsCenterY;

        float avatarSize = 18.0f;
        float dotSectionW = 10.0f + this.cachedOnlineWidth;
        this.profileDotsW = 20.0f;
        this.profilePillW = 6.0f + avatarSize + 6.0f + this.cachedPlayerNameWidth + 8.0f + dotSectionW + 6.0f + this.profileDotsW;
        this.profilePillH = 28.0f;
        this.profilePillX = this.width - 12.0f - this.profilePillW;
        this.profilePillY = 12.0f;
        this.profileDotsX = this.profilePillX + this.profilePillW - this.profileDotsW;

        this.profileDropdownW = Math.max(150.0f, this.profilePillW);
        this.profileDropdownH = 70.0f;
        this.profileDropdownX = this.width - 12.0f - this.profileDropdownW;
        this.profileDropdownY = this.profilePillY + this.profilePillH + 4.0f;

        this.dockW = 168.0f;
        this.dockH = 36.0f;
        this.dockX = this.centerX - (this.dockW * 0.5f);
        this.dockY = this.height - 18.0f - this.dockH;

        float iconSpacing = this.dockW / 4.0f;
        for (int i = 0; i < 4; i++) {
            this.dockIconCenterX[i] = this.dockX + iconSpacing * (i + 0.5f);
        }
    }

    private void updateClockStringsIfNeeded() {
        long nowMillis = System.currentTimeMillis();
        long epochSecond = nowMillis / 1000L;
        if (epochSecond == this.lastCheckedEpochSecond) {
            return;
        }
        this.lastCheckedEpochSecond = epochSecond;

        LocalDateTime now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), ZoneId.systemDefault());
        int minute = now.getMinute();
        int day = now.getDayOfYear();

        if (minute != this.lastCheckedMinute) {
            this.lastCheckedMinute = minute;
            this.cachedTimeText = TIME_FORMATTER.format(now);
            this.cachedTimeWidth = (this.font != null) ? this.font.width(this.cachedTimeText) : 48;
        }
        if (day != this.lastCheckedDay) {
            this.lastCheckedDay = day;
            this.cachedDateText = DATE_FORMATTER.format(now);
            this.cachedDateWidth = (this.font != null) ? this.font.width(this.cachedDateText) : 96;
        }
    }

    private void syncTrackMetadataCache() {
        int totalSec = TRACK_DURATIONS_SEC[this.currentTrackIndex];
        formatSecondsIntoBuilder(totalSec);
        this.cachedTotalText = this.timeFormatBuilder.toString();
        this.cachedTotalTextWidth = (this.font != null) ? this.font.width(this.cachedTotalText) : 32;
        this.lastRenderedMusicSecond = -1;
        updateElapsedMusicTextIfNeeded();
    }

    private void updateElapsedMusicTextIfNeeded() {
        int wholeSec = (int) this.trackElapsedSeconds;
        if (wholeSec == this.lastRenderedMusicSecond) {
            return;
        }
        this.lastRenderedMusicSecond = wholeSec;
        formatSecondsIntoBuilder(wholeSec);
        this.cachedElapsedText = this.timeFormatBuilder.toString();
    }

    private void formatSecondsIntoBuilder(int totalSeconds) {
        int clamped = Math.max(0, totalSeconds);
        int mins = clamped / 60;
        int secs = clamped % 60;
        this.timeFormatBuilder.setLength(0);
        this.timeFormatBuilder.append(mins).append(':');
        if (secs < 10) {
            this.timeFormatBuilder.append('0');
        }
        this.timeFormatBuilder.append(secs);
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Suppress vanilla rotating panorama
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        try {
            long nowNanos = System.nanoTime();
            this.deltaTime = Mth.clamp((nowNanos - this.lastFrameNanos) * 1.0e-9f, 0.0001f, 0.05f);
            this.lastFrameNanos = nowNanos;
            this.totalElapsedTime += this.deltaTime;

            updateClockStringsIfNeeded();
            if (this.musicPlaying && !this.draggingSlider) {
                this.trackElapsedSeconds += this.deltaTime;
                int maxSec = TRACK_DURATIONS_SEC[this.currentTrackIndex];
                if (this.trackElapsedSeconds >= maxSec) {
                    this.trackElapsedSeconds = 0.0f;
                    this.currentTrackIndex = (this.currentTrackIndex + 1) % TRACK_TITLES.length;
                    syncTrackMetadataCache();
                }
            }
            updateElapsedMusicTextIfNeeded();

            if (this.copiedUsernameFeedback) {
                this.copiedFeedbackTimer -= this.deltaTime;
                if (this.copiedFeedbackTimer <= 0.0f) {
                    this.copiedUsernameFeedback = false;
                }
            }

            updateAnimations(mouseX, mouseY);

            AstraNativeTitleRenderer nativeRenderer = AstraNativeTitleRenderer.getInstance();
            boolean nativeActive = nativeRenderer.ensureInitialized();

            // Dynamic Real-time Spotify Polling (0.35s period, zero heap allocation)
            this.spotifyPollTimer -= this.deltaTime;
            if (this.spotifyPollTimer <= 0.0f) {
                this.spotifyPollTimer = 0.35f;
                try {
                    String[] spot = nativeRenderer.pollSpotifyTrack();
                    boolean wasActive = this.spotifyActive;
                    if (spot != null && spot.length >= 2 && spot[0] != null && !spot[0].isEmpty() && spot[1] != null && !spot[1].isEmpty()) {
                        this.spotifyActive = true;
                        this.spotifyArtist = spot[0];
                        this.spotifyTitle = spot[1];
                    } else {
                        this.spotifyActive = false;
                    }
                    if (wasActive != this.spotifyActive) {
                        computeResponsiveLayout();
                    }
                } catch (Throwable ignored) {
                    this.spotifyActive = false;
                }
            }

            // 1. Render Cinematic Castle Wallpaper & Atmosphere
            renderAtmosphericBackground(guiGraphics);

            if (nativeActive) {
                guiGraphics.flush();

                float spLift = -3.0f * this.spHoverAnim;
                float spScale = 1.0f + 0.02f * this.spHoverAnim;
                float spW = this.modePillW * spScale;
                float spH = this.modePillH * spScale;
                float spX = this.spPillX - (spW - this.modePillW) * 0.5f;
                float spY = this.spPillY + spLift - (spH - this.modePillH) * 0.5f;

                float mpLift = -3.0f * this.mpHoverAnim;
                float mpScale = 1.0f + 0.02f * this.mpHoverAnim;
                float mpW = this.modePillW * mpScale;
                float mpH = this.modePillH * mpScale;
                float mpX = this.mpPillX - (mpW - this.modePillW) * 0.5f;
                float mpY = this.mpPillY + mpLift - (mpH - this.modePillH) * 0.5f;

                float contY = this.continueY - 1.5f * this.continueHoverAnim;
                float dropY = this.profileDropdownY - (1.0f - this.profileMenuAnim) * 6.0f;

                float tipX = -200.0f, tipY = -200.0f, tipW = 64.0f, tipH = 18.0f;
                if (this.hoveredDockIndex >= 0) {
                    int idx = this.hoveredDockIndex;
                    float hover = this.dockHoverAnim[idx];
                    tipW = this.dockTooltipWidths[idx] * 0.85f + 16.0f;
                    tipX = this.dockIconCenterX[idx] - tipW * 0.5f;
                    tipY = this.dockY - tipH - 6.0f + (1.0f - hover) * 4.0f;
                }

                float avatarX = this.profilePillX + 6.0f;
                float nameX = avatarX + 20.0f + 8.0f;
                float statusDotX = nameX + this.cachedPlayerNameWidth + 10.0f;
                float dotsCenterX = this.profileDotsX + this.profileDotsW * 0.5f - 2.0f;

                float nativeMusicW = this.spotifyActive ? this.musicW : 0.0f;
                float nativeMusicH = this.spotifyActive ? this.musicH : 0.0f;

                nativeRenderer.syncResponsiveLayout(
                        spX, spY, spW, spH,
                        mpX, mpY, mpW, mpH,
                        this.continueX, contY, this.cardW, this.continueH,
                        this.musicX, this.musicY, nativeMusicW, nativeMusicH,
                        this.profilePillX, this.profilePillY, this.profilePillW, this.profilePillH,
                        this.profileDropdownX, dropY, this.profileDropdownW, this.profileDropdownH,
                        this.dockX, this.dockY, this.dockW, this.dockH,
                        tipX, tipY, tipW, tipH,
                        this.dockIconCenterX[0], this.dockIconCenterX[1], this.dockIconCenterX[2], this.dockIconCenterX[3],
                        statusDotX, dotsCenterX, this.profileDotsHoverAnim, this.profileMenuAnim
                );

                float musicProgress = Mth.clamp(
                        this.trackElapsedSeconds / (float) TRACK_DURATIONS_SEC[this.currentTrackIndex],
                        0.0f, 1.0f
                );

                int bgTexId = 0; // Czyste renderowanie bez niebezpiecznego odczytu glownego FBO w trakcie zapisu

                nativeRenderer.renderFrame(
                        (float) this.width, (float) this.height,
                        (float) mouseX, (float) mouseY,
                        this.totalElapsedTime, this.deltaTime,
                        this.spHoverAnim, this.mpHoverAnim,
                        this.continueHoverAnim, this.continuePlayHoverAnim,
                        this.sliderHoverAnim,
                        this.prevHoverAnim, this.playPauseHoverAnim, this.nextHoverAnim,
                        this.profileHoverAnim,
                        this.dockHoverAnim[0], this.dockHoverAnim[1], this.dockHoverAnim[2], this.dockHoverAnim[3],
                        musicProgress, this.musicPlaying, this.currentTrackIndex,
                        this.hoveredDockIndex, this.profileMenuOpen,
                        bgTexId
                );

                // Przywrocenie stanu Blaze3D dla tekstu i nakladek Java GUI
                com.mojang.blaze3d.systems.RenderSystem.enableBlend();
                com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
                com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
            }

            // 2. Render Header (Date & Big Apple Lockscreen Clock)
            renderHeader(guiGraphics);

            // 3. Render Mode Pills (Singleplayer & Multiplayer)
            renderModePills(guiGraphics, nativeActive);

            // 4. Render Continue Quick-Join Card
            renderContinueCard(guiGraphics, nativeActive);

            // 5. Render iOS Now Playing Music Card
            renderMusicCard(guiGraphics, nativeActive);

            // 6. Render Floating Bottom Dock
            renderFloatingDock(guiGraphics, nativeActive);

            // 7. Render Top-Right User Profile Pill
            renderProfilePill(guiGraphics, nativeActive);

            // 8. Render Footer Texts
            renderFooters(guiGraphics);
        } catch (Throwable t) {
            // Unconditional safety net to guarantee LoadingOverlay never crashes
            guiGraphics.fillGradient(0, 0, this.width, this.height, 0xFF0E1322, 0xFF050811);
        }
    }

    private void updateAnimations(int mouseX, int mouseY) {
        float targetNormX = this.width > 0 ? ((mouseX / (float) this.width) - 0.5f) * 2.0f : 0.0f;
        float targetNormY = this.height > 0 ? ((mouseY / (float) this.height) - 0.5f) * 2.0f : 0.0f;
        float camLerp = smoothFactor(5.0f, this.deltaTime);
        this.normMouseX = Mth.lerp(camLerp, this.normMouseX, targetNormX);
        this.normMouseY = Mth.lerp(camLerp, this.normMouseY, targetNormY);

        float uiLerp = smoothFactor(12.0f, this.deltaTime);

        boolean hoverSp = isPointInRect(mouseX, mouseY, this.spPillX, this.spPillY, this.modePillW, this.modePillH);
        boolean hoverMp = isPointInRect(mouseX, mouseY, this.mpPillX, this.mpPillY, this.modePillW, this.modePillH);
        this.spHoverAnim = Mth.lerp(uiLerp, this.spHoverAnim, hoverSp ? 1.0f : 0.0f);
        this.mpHoverAnim = Mth.lerp(uiLerp, this.mpHoverAnim, hoverMp ? 1.0f : 0.0f);

        boolean hoverContinue = isPointInRect(mouseX, mouseY, this.continueX, this.continueY, this.cardW, this.continueH);
        boolean hoverContinuePlay = isPointInCircle(mouseX, mouseY, this.playCircleX, this.playCircleY, this.playCircleR + 4.0f);
        this.continueHoverAnim = Mth.lerp(uiLerp, this.continueHoverAnim, hoverContinue ? 1.0f : 0.0f);
        this.continuePlayHoverAnim = Mth.lerp(uiLerp, this.continuePlayHoverAnim, hoverContinuePlay ? 1.0f : 0.0f);

        boolean hoverSlider = this.draggingSlider || isPointInRect(mouseX, mouseY, this.sliderX - 4.0f, this.sliderY - 6.0f, this.sliderW + 8.0f, 15.0f);
        boolean hoverPrev = isPointInCircle(mouseX, mouseY, this.prevBtnX, this.prevBtnY, this.mediaBtnR);
        boolean hoverPlayPause = isPointInCircle(mouseX, mouseY, this.playPauseBtnX, this.playPauseBtnY, this.mediaBtnR + 2.0f);
        boolean hoverNext = isPointInCircle(mouseX, mouseY, this.nextBtnX, this.nextBtnY, this.mediaBtnR);

        this.sliderHoverAnim = Mth.lerp(uiLerp, this.sliderHoverAnim, hoverSlider ? 1.0f : 0.0f);
        this.prevHoverAnim = Mth.lerp(uiLerp, this.prevHoverAnim, hoverPrev ? 1.0f : 0.0f);
        this.playPauseHoverAnim = Mth.lerp(uiLerp, this.playPauseHoverAnim, hoverPlayPause ? 1.0f : 0.0f);
        this.nextHoverAnim = Mth.lerp(uiLerp, this.nextHoverAnim, hoverNext ? 1.0f : 0.0f);

        boolean hoverProfile = isPointInRect(mouseX, mouseY, this.profilePillX, this.profilePillY, this.profilePillW, this.profilePillH);
        boolean hoverDots = isPointInRect(mouseX, mouseY, this.profileDotsX, this.profilePillY, this.profileDotsW, this.profilePillH);
        this.profileHoverAnim = Mth.lerp(uiLerp, this.profileHoverAnim, hoverProfile ? 1.0f : 0.0f);
        this.profileDotsHoverAnim = Mth.lerp(uiLerp, this.profileDotsHoverAnim, hoverDots ? 1.0f : 0.0f);
        this.profileMenuAnim = Mth.lerp(uiLerp, this.profileMenuAnim, this.profileMenuOpen ? 1.0f : 0.0f);

        this.hoveredDockIndex = -1;
        for (int i = 0; i < 4; i++) {
            boolean hoverDockIcon = isPointInCircle(mouseX, mouseY, this.dockIconCenterX[i], this.dockY + this.dockH * 0.5f, 16.0f);
            if (hoverDockIcon) {
                this.hoveredDockIndex = i;
            }
            this.dockHoverAnim[i] = Mth.lerp(uiLerp, this.dockHoverAnim[i], hoverDockIcon ? 1.0f : 0.0f);
        }

        // Update atmospheric fireflies
        int w = Math.max(100, this.width);
        int h = Math.max(100, this.height);
        for (int i = 0; i < FIREFLY_COUNT; i++) {
            this.fireflyY[i] -= this.fireflySpeedY[i] * this.deltaTime;
            this.fireflyX[i] += (float) Math.sin(this.totalElapsedTime * 1.5f + this.fireflyPhase[i]) * 0.45f;
            if (this.fireflyY[i] < -10.0f) {
                this.fireflyY[i] = h + 10.0f;
                this.fireflyX[i] = (float) (Math.random() * w);
            }
        }
    }

    private static float smoothFactor(float speed, float dt) {
        return 1.0f - (float) Math.exp(-speed * dt);
    }

    // =========================================================================
    // RENDERING PASSES (ROCK-SOLID GUIGRAPHICS)
    // =========================================================================

    private void renderAtmosphericBackground(GuiGraphics graphics) {
        int w = this.width;
        int h = this.height;

        // Base twilight background fill
        graphics.fill(0, 0, w, h, 0xFF080D1A);

        // Blit high-res nocturnal castle wallpaper with subtle parallax
        PoseStack pose = graphics.pose();
        pose.pushPose();
        float parallaxX = -this.normMouseX * 10.0f;
        float parallaxY = -this.normMouseY * 6.0f;
        pose.translate(parallaxX - 6.0f, parallaxY - 4.0f, 0.0f);

        int renderW = w + 12;
        int renderH = h + 8;
        try {
            graphics.blit(RenderType::guiTextured, BG_TEXTURE, 0, 0, 0.0f, 0.0f, renderW, renderH, renderW, renderH);
        } catch (Throwable ignored) {
            // Safe fallback if texture fails
            graphics.fillGradient(0, 0, renderW, renderH, 0xFF0F172A, 0xFF020617);
        }
        pose.popPose();

        // Dark cinematic vignette overlays
        graphics.fillGradient(0, 0, w, (int) (h * 0.35f), 0xB0020617, 0x00020617);
        graphics.fillGradient(0, (int) (h * 0.65f), w, h, 0x00020617, 0xD9020617);
        graphics.fillGradient(0, 0, (int) (w * 0.25f), h, 0x73020617, 0x00020617);
        graphics.fillGradient((int) (w * 0.75f), 0, w, h, 0x00020617, 0x73020617);

        // Render floating glowing firefly particles
        for (int i = 0; i < FIREFLY_COUNT; i++) {
            float pulse = 0.5f + 0.5f * (float) Math.sin(this.totalElapsedTime * 3.0f + this.fireflyPhase[i]);
            int alpha = (int) (this.fireflyAlpha[i] * pulse * 255.0f);
            if (alpha > 0) {
                int glowCol = (alpha << 24) | 0x00FDE047;
                int px = (int) this.fireflyX[i];
                int py = (int) this.fireflyY[i];
                int size = (int) this.fireflySize[i];
                graphics.fill(px, py, px + size, py + size, glowCol);
            }
        }
    }

    private void renderHeader(GuiGraphics graphics) {
        if (this.font == null) return;
        PoseStack pose = graphics.pose();

        // 1. Current Date (e.g. "Friday, 13 November")
        int dateX = (int) (this.centerX - (this.cachedDateWidth * 0.5f));
        graphics.drawString(this.font, this.cachedDateText, dateX, (int) this.dateY, 0xCCE2E8F0, true);

        // 2. Large Apple Lockscreen Clock
        float scaledClockW = this.cachedTimeWidth * this.clockScale;
        float clockDrawX = this.centerX - (scaledClockW * 0.5f);

        pose.pushPose();
        pose.translate(clockDrawX, this.clockY + 2.0f, 0.0f);
        pose.scale(this.clockScale, this.clockScale, 1.0f);
        graphics.drawString(this.font, this.cachedTimeText, 0, 0, 0x66020617, false);
        pose.popPose();

        pose.pushPose();
        pose.translate(clockDrawX, this.clockY, 0.0f);
        pose.scale(this.clockScale, this.clockScale, 1.0f);
        graphics.drawString(this.font, this.cachedTimeText, 0, 0, 0xFFFFFFFF, false);
        pose.popPose();
    }

    private void renderModePills(GuiGraphics graphics, boolean nativeActive) {
        // --- Singleplayer Pill ---
        float spLift = -2.5f * this.spHoverAnim;
        int spRadius = (int) (this.modePillH * 0.5f);

        if (!nativeActive) {
            int spBg = lerpColor(this.spHoverAnim, COL_GLASS_BASE, COL_GLASS_HOVER);
            int spBorder = lerpColor(this.spHoverAnim, COL_BORDER_NORMAL, COL_BORDER_HOVER);
            drawRoundedRect(graphics, (int) this.spPillX, (int) (this.spPillY + spLift),
                    (int) this.modePillW, (int) this.modePillH, spRadius, spBg);
            drawRoundedBorder(graphics, (int) this.spPillX, (int) (this.spPillY + spLift),
                    (int) this.modePillW, (int) this.modePillH, spRadius, 1, spBorder, spBorder);
            graphics.fill((int) (this.spPillX + spRadius), (int) (this.spPillY + spLift + 1),
                    (int) (this.spPillX + this.modePillW - spRadius), (int) (this.spPillY + spLift + 2), COL_SPECULAR_RIM);
            renderUserVectorIcon(graphics, (int) (this.spPillX + 24.0f), (int) (this.spPillY + spLift + this.modePillH * 0.5f));
        }

        // User label
        float spLabelW = this.font.width("Singleplayer");
        int spTextX = (int) (this.spPillX + (this.modePillW - spLabelW) * 0.5f + 8.0f);
        int spTextY = (int) (this.spPillY + spLift + (this.modePillH - this.font.lineHeight) * 0.5f + 0.5f);
        graphics.drawString(this.font, "Singleplayer", spTextX, spTextY,
                lerpColor(this.spHoverAnim, 0xFFE2E8F0, 0xFFFFFFFF), false);

        // --- Multiplayer Pill ---
        float mpLift = -2.5f * this.mpHoverAnim;
        int mpRadius = (int) (this.modePillH * 0.5f);

        if (!nativeActive) {
            int mpBg = lerpColor(this.mpHoverAnim, COL_GLASS_BASE, COL_GLASS_HOVER);
            int mpBorder = lerpColor(this.mpHoverAnim, COL_BORDER_NORMAL, COL_BORDER_HOVER);
            drawRoundedRect(graphics, (int) this.mpPillX, (int) (this.mpPillY + mpLift),
                    (int) this.modePillW, (int) this.modePillH, mpRadius, mpBg);
            drawRoundedBorder(graphics, (int) this.mpPillX, (int) (this.mpPillY + mpLift),
                    (int) this.modePillW, (int) this.modePillH, mpRadius, 1, mpBorder, mpBorder);
            graphics.fill((int) (this.mpPillX + mpRadius), (int) (this.mpPillY + mpLift + 1),
                    (int) (this.mpPillX + this.modePillW - mpRadius), (int) (this.mpPillY + mpLift + 2), COL_SPECULAR_RIM);
            renderMultiUserVectorIcon(graphics, (int) (this.mpPillX + 24.0f), (int) (this.mpPillY + mpLift + this.modePillH * 0.5f));
        }

        // Multi-user label
        float mpLabelW = this.font.width("Multiplayer");
        int mpTextX = (int) (this.mpPillX + (this.modePillW - mpLabelW) * 0.5f + 9.0f);
        int mpTextY = (int) (this.mpPillY + mpLift + (this.modePillH - this.font.lineHeight) * 0.5f + 0.5f);
        graphics.drawString(this.font, "Multiplayer", mpTextX, mpTextY,
                lerpColor(this.mpHoverAnim, 0xFFE2E8F0, 0xFFFFFFFF), false);
    }

    private void renderContinueCard(GuiGraphics graphics, boolean nativeActive) {
        float contLift = -2.0f * this.continueHoverAnim;
        int radius = 14;

        int cx = (int) this.continueX;
        int cy = (int) (this.continueY + contLift);
        int cw = (int) this.cardW;
        int ch = (int) this.continueH;

        if (!nativeActive) {
            int contBg = lerpColor(this.continueHoverAnim, COL_GLASS_BASE, COL_GLASS_HOVER);
            int contBorder = lerpColor(this.continueHoverAnim, COL_BORDER_NORMAL, COL_BORDER_HOVER);
            drawRoundedRect(graphics, cx, cy, cw, ch, radius, contBg);
            drawRoundedBorder(graphics, cx, cy, cw, ch, radius, 1, contBorder, contBorder);
            graphics.fill(cx + radius, cy + 1, cx + cw - radius, cy + 2, COL_SPECULAR_RIM);
        }

        // Anarchia.gg Server Icon Badge
        float badgeSize = ch - 16.0f;
        int bx = cx + 8;
        int by = cy + 8;
        drawRoundedRect(graphics, bx, by, (int) badgeSize, (int) badgeSize, 8, 0xFF1E293B);
        drawRoundedBorder(graphics, bx, by, (int) badgeSize, (int) badgeSize, 8, 1, 0xFF475569, 0xFF334155);

        try {
            graphics.blit(RenderType::guiTextured, ANARCHIA_ICON, bx + 2, by + 2, 0.0f, 0.0f, (int) badgeSize - 4, (int) badgeSize - 4, (int) badgeSize - 4, (int) badgeSize - 4);
        } catch (Throwable t) {
            int initX = (int) (bx + (badgeSize - this.lastServerInitialsWidth) * 0.5f);
            int initY = (int) (by + (badgeSize - this.font.lineHeight) * 0.5f + 1.0f);
            graphics.drawString(this.font, this.lastServerInitials, initX, initY, 0xFFFFFFFF, true);
        }

        // Continue Subtitle & Server Name
        PoseStack pose = graphics.pose();
        int textX = (int) (bx + badgeSize + 10.0f);

        pose.pushPose();
        pose.translate(textX, cy + 9.0f, 0.0f);
        pose.scale(0.82f, 0.82f, 1.0f);
        graphics.drawString(this.font, this.continueSubtitle, 0, 0, 0xFF94A3B8, false);
        pose.popPose();

        graphics.drawString(this.font, this.lastServerName, textX, (int) (cy + 22.0f), 0xFFF8FAFC, false);

        // Circular Play Button
        if (!nativeActive) {
            int playX = (int) this.playCircleX;
            int playY = (int) (this.playCircleY + contLift);
            int playR = (int) this.playCircleR;
            float playHover = Math.max(this.continueHoverAnim * 0.5f, this.continuePlayHoverAnim);
            int playBg = lerpColor(playHover, 0x33FFFFFF, 0x66FFFFFF);

            drawCircle(graphics, playX, playY, playR, playBg);
            drawCircleOutline(graphics, playX, playY, playR, 0x80FFFFFF);
            renderPlayTriangleIcon(graphics, playX, playY, 5.0f, 0xFFFFFFFF);
        }
    }

    private void renderMusicCard(GuiGraphics graphics, boolean nativeActive) {
        if (!this.spotifyActive) {
            return;
        }

        int mx = (int) this.musicX;
        int my = (int) this.musicY;
        int mw = (int) this.musicW;
        int mh = (int) this.musicH;
        int radius = 14;

        int cvx = (int) this.coverX;
        int cvy = (int) this.coverY;
        int cvSize = (int) this.coverSize;

        if (!nativeActive) {
            drawRoundedRect(graphics, mx, my, mw, mh, radius, COL_GLASS_BASE);
            drawRoundedBorder(graphics, mx, my, mw, mh, radius, 1, COL_BORDER_NORMAL, COL_BORDER_NORMAL);
            graphics.fill(mx + radius, my + 1, mx + mw - radius, my + 2, COL_SPECULAR_RIM);

            // Album Artwork Box (Stylized Night Gradient)
            drawRoundedRect(graphics, cvx, cvy, cvSize, cvSize, 8, 0xFF1E1B4B);
            drawRoundedVerticalGradient(graphics, cvx, cvy, cvSize, cvSize, 8, 0xFF4338CA, 0xFF1E1B4B);
            drawRoundedBorder(graphics, cvx, cvy, cvSize, cvSize, 8, 1, 0x55818CF8, 0x334338CA);

            // Artwork Inner Symbol (Vinyl / Orb)
            drawCircle(graphics, cvx + cvSize / 2, cvy + cvSize / 2, cvSize / 4, 0x66FDE047);
            drawCircle(graphics, cvx + cvSize / 2, cvy + cvSize / 2, cvSize / 8, 0xFFF59E0B);
        }

        // Track Title & Artist (dynamically from Spotify!)
        int titleX = (int) this.sliderX;
        int titleY = cvy + 1;
        String displayTitle = (this.spotifyTitle != null && !this.spotifyTitle.isEmpty()) ? this.spotifyTitle : TRACK_TITLES[this.currentTrackIndex];
        String displayArtist = (this.spotifyArtist != null && !this.spotifyArtist.isEmpty()) ? this.spotifyArtist : TRACK_ARTISTS[this.currentTrackIndex];

        // Truncate title if too long to fit cleanly inside card
        int maxTextW = (int) (this.sliderW);
        if (this.font != null && this.font.width(displayTitle) > maxTextW && maxTextW > 20) {
            displayTitle = this.font.plainSubstrByWidth(displayTitle, maxTextW - 8) + "…";
        }

        graphics.drawString(this.font, displayTitle, titleX, titleY, 0xFFF8FAFC, false);

        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(titleX, titleY + 11.0f, 0.0f);
        pose.scale(0.82f, 0.82f, 1.0f);
        graphics.drawString(this.font, displayArtist, 0, 0, 0xFF94A3B8, false);
        pose.popPose();

        // Music Progress Bar
        int sx = (int) this.sliderX;
        int sy = (int) this.sliderY;
        int sw = (int) this.sliderW;
        int sh = (int) this.sliderH;

        if (!nativeActive) {
            float progress = Mth.clamp(this.trackElapsedSeconds / (float) TRACK_DURATIONS_SEC[this.currentTrackIndex], 0.0f, 1.0f);
            drawRoundedRect(graphics, sx, sy, sw, sh, 1, 0x4DFFFFFF);

            int fillW = Math.max(sh, (int) (sw * progress));
            drawRoundedRect(graphics, sx, sy, fillW, sh, 1, 0xFFF8FAFC);

            if (this.sliderHoverAnim > 0.05f) {
                int thumbX = sx + fillW;
                int thumbR = (int) (3.5f * this.sliderHoverAnim);
                drawCircle(graphics, thumbX, sy + sh / 2, thumbR, 0xFFFFFFFF);
            }
        }

        // Timestamps (Elapsed on left, Total on right)
        float labelY = sy + 5.0f;
        pose.pushPose();
        pose.translate(sx, labelY, 0.0f);
        pose.scale(0.74f, 0.74f, 1.0f);
        graphics.drawString(this.font, this.cachedElapsedText, 0, 0, 0xFF94A3B8, false);
        pose.popPose();

        float totalX = sx + sw - (this.cachedTotalTextWidth * 0.74f);
        pose.pushPose();
        pose.translate(totalX, labelY, 0.0f);
        pose.scale(0.74f, 0.74f, 1.0f);
        graphics.drawString(this.font, this.cachedTotalText, 0, 0, 0xFF94A3B8, false);
        pose.popPose();

        // Media Controls (|◀, ▶/⏸, ▶|)
        if (!nativeActive) {
            renderPrevTrackIcon(graphics, (int) this.prevBtnX, (int) this.prevBtnY, this.prevHoverAnim);
            renderPlayPauseIcon(graphics, (int) this.playPauseBtnX, (int) this.playPauseBtnY, this.musicPlaying, this.playPauseHoverAnim);
            renderNextTrackIcon(graphics, (int) this.nextBtnX, (int) this.nextBtnY, this.nextHoverAnim);
        }
    }

    private void renderFloatingDock(GuiGraphics graphics, boolean nativeActive) {
        int dx = (int) this.dockX;
        int dy = (int) this.dockY;
        int dw = (int) this.dockW;
        int dh = (int) this.dockH;
        int radius = (int) (dh * 0.5f);

        if (!nativeActive) {
            drawRoundedRect(graphics, dx, dy, dw, dh, radius, COL_GLASS_BASE);
            drawRoundedBorder(graphics, dx, dy, dw, dh, radius, 1, COL_BORDER_NORMAL, COL_BORDER_NORMAL);
            graphics.fill(dx + radius, dy + 1, dx + dw - radius, dy + 2, COL_SPECULAR_RIM);
        }

        int midY = (int) (dy + dh * 0.5f);

        for (int i = 0; i < 4; i++) {
            float hover = this.dockHoverAnim[i];
            int cx = (int) this.dockIconCenterX[i];
            int cy = (int) (midY - hover * 2.0f);

            if (!nativeActive) {
                if (hover > 0.05f) {
                    int glowCol = (i == 3) ? 0x4DF87171 : 0x4D38BDF8;
                    drawCircle(graphics, cx, cy, 14, glowCol);
                }
                int iconCol = (i == 3) ? lerpColor(hover, 0xFFE2E8F0, 0xFFF87171) : lerpColor(hover, 0xFFCBD5E1, 0xFFFFFFFF);
                renderDockVectorIcon(graphics, i, cx, cy, iconCol);
            }

            // Floating Tooltip above icon
            if (this.hoveredDockIndex == i && hover > 0.15f) {
                renderDockTooltip(graphics, i, cx, dy, hover);
            }
        }
    }

    private void renderDockTooltip(GuiGraphics graphics, int index, int iconCenterX, int dockY, float hover) {
        String tip = DOCK_TOOLTIPS[index];
        int textW = this.dockTooltipWidths[index];
        int tipW = (int) (textW * 0.85f + 16.0f);
        int tipH = 17;
        int tipX = iconCenterX - tipW / 2;
        int tipY = (int) (dockY - tipH - 6.0f + (1.0f - hover) * 4.0f);

        drawRoundedRect(graphics, tipX, tipY, tipW, tipH, 6, 0xE60B1220);
        drawRoundedBorder(graphics, tipX, tipY, tipW, tipH, 6, 1, 0x4DE2E8F0, 0x4DE2E8F0);

        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(tipX + 8.0f, tipY + 4.5f, 0.0f);
        pose.scale(0.85f, 0.85f, 1.0f);
        graphics.drawString(this.font, tip, 0, 0, 0xFFF8FAFC, false);
        pose.popPose();
    }

    private void renderProfilePill(GuiGraphics graphics, boolean nativeActive) {
        int px = (int) this.profilePillX;
        int py = (int) this.profilePillY;
        int pw = (int) this.profilePillW;
        int ph = (int) this.profilePillH;
        int radius = (int) (ph * 0.5f);

        if (!nativeActive) {
            int bg = lerpColor(this.profileHoverAnim, COL_GLASS_BASE, COL_GLASS_HOVER);
            int border = lerpColor(this.profileHoverAnim, COL_BORDER_NORMAL, COL_BORDER_HOVER);
            drawRoundedRect(graphics, px, py, pw, ph, radius, bg);
            drawRoundedBorder(graphics, px, py, pw, ph, radius, 1, border, border);
            graphics.fill(px + radius, py + 1, px + pw - radius, py + 2, COL_SPECULAR_RIM);
        }

        // Player Head
        int headSize = 18;
        int headX = px + 5;
        int headY = (int) (py + (ph - headSize) * 0.5f);
        if (this.playerSkin != null) {
            PlayerFaceRenderer.draw(graphics, this.playerSkin, headX, headY, headSize);
        } else {
            drawRoundedRect(graphics, headX, headY, headSize, headSize, 4, 0xFF334155);
        }

        // Username
        int nameX = headX + headSize + 6;
        int textY = (int) (py + (ph - this.font.lineHeight) * 0.5f + 0.5f);
        graphics.drawString(this.font, this.cachedPlayerName, nameX, textY, 0xFFF8FAFC, false);

        // Pulsing Emerald Dot + "Online"
        int dotX = nameX + this.cachedPlayerNameWidth + 8;
        int dotY = (int) (py + ph * 0.5f);
        float pulse = 0.65f + 0.35f * (float) Math.sin(this.totalElapsedTime * 3.5f);
        int dotAlpha = (int) (pulse * 255.0f);
        drawCircle(graphics, dotX, dotY, 3, (dotAlpha << 24) | 0x0022C55E);

        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(dotX + 6.0f, textY + 1.0f, 0.0f);
        pose.scale(0.85f, 0.85f, 1.0f);
        graphics.drawString(this.font, ONLINE_STATUS_TEXT, 0, 0, 0xFF86EFAC, false);
        pose.popPose();

        // Triple Dots "•••"
        int dotsX = (int) (this.profileDotsX + this.profileDotsW * 0.5f);
        int dotsY = (int) (py + ph * 0.5f);
        int dotsCol = lerpColor(this.profileDotsHoverAnim, 0xFF94A3B8, 0xFFFFFFFF);
        graphics.fill(dotsX - 4, dotsY - 1, dotsX - 2, dotsY + 1, dotsCol);
        graphics.fill(dotsX - 1, dotsY - 1, dotsX + 1, dotsY + 1, dotsCol);
        graphics.fill(dotsX + 2, dotsY - 1, dotsX + 4, dotsY + 1, dotsCol);

        // Profile Popover Dropdown
        if (this.profileMenuAnim > 0.1f) {
            int ddx = (int) this.profileDropdownX;
            int ddy = (int) (this.profileDropdownY - (1.0f - this.profileMenuAnim) * 4.0f);
            int ddw = (int) this.profileDropdownW;
            int ddh = (int) this.profileDropdownH;

            drawRoundedRect(graphics, ddx, ddy, ddw, ddh, 10, 0xF00B1220);
            drawRoundedBorder(graphics, ddx, ddy, ddw, ddh, 10, 1, 0x66CBD5E1, 0x4DCBD5E1);

            int itemX = ddx + 10;
            graphics.drawString(this.font, "Skin Customization", itemX, ddy + 10, 0xFFF1F5F9, false);
            String copyLabel = this.copiedUsernameFeedback ? "Copied Username!" : "Copy Username";
            int copyColor = this.copiedUsernameFeedback ? 0xFF86EFAC : 0xFFF1F5F9;
            graphics.drawString(this.font, copyLabel, itemX, ddy + 30, copyColor, false);
            graphics.drawString(this.font, "Baritone Settings", itemX, ddy + 50, 0xFF94A3B8, false);
        }
    }

    private void renderFooters(GuiGraphics graphics) {
        if (this.font == null) return;
        PoseStack pose = graphics.pose();
        float footerScale = 0.78f;
        int footerY = this.height - 12;

        // Bottom Left
        pose.pushPose();
        pose.translate(10.0f, footerY, 0.0f);
        pose.scale(footerScale, footerScale, 1.0f);
        graphics.drawString(this.font, COPYRIGHT_TEXT, 0, 0, 0x8894A3B8, false);
        pose.popPose();

        // Bottom Right
        float rightX = this.width - 10.0f - (this.versionWidth * footerScale);
        pose.pushPose();
        pose.translate(rightX, footerY, 0.0f);
        pose.scale(footerScale, footerScale, 1.0f);
        graphics.drawString(this.font, VERSION_TEXT, 0, 0, 0x8894A3B8, false);
        pose.popPose();
    }

    // =========================================================================
    // VECTOR ICON RENDERERS (CLEAN PIXEL-ALIGNED PROCEDURAL GEOMETRY)
    // =========================================================================

    private static void renderUserVectorIcon(GuiGraphics graphics, int cx, int cy) {
        drawCircle(graphics, cx, cy - 3, 2, 0xFFFFFFFF);
        drawRoundedRect(graphics, cx - 4, cy + 1, 8, 4, 2, 0xFFFFFFFF);
    }

    private static void renderMultiUserVectorIcon(GuiGraphics graphics, int cx, int cy) {
        drawCircle(graphics, cx + 3, cy - 3, 2, 0x99FFFFFF);
        drawRoundedRect(graphics, cx - 1, cy + 1, 7, 3, 1, 0x99FFFFFF);
        drawCircle(graphics, cx - 2, cy - 2, 2, 0xFFFFFFFF);
        drawRoundedRect(graphics, cx - 5, cy + 2, 7, 4, 2, 0xFFFFFFFF);
    }

    private static void renderPlayTriangleIcon(GuiGraphics graphics, int cx, int cy, float size, int color) {
        int s = (int) size;
        for (int i = 0; i <= s; i++) {
            int halfH = (int) ((float) i / s * (s * 0.85f));
            graphics.fill(cx - s / 2 + i, cy - halfH, cx - s / 2 + i + 1, cy + halfH + 1, color);
        }
    }

    private static void renderPlayPauseIcon(GuiGraphics graphics, int cx, int cy, boolean playing, float hover) {
        int col = lerpColor(hover, 0xFFCBD5E1, 0xFFFFFFFF);
        if (playing) {
            graphics.fill(cx - 3, cy - 4, cx - 1, cy + 4, col);
            graphics.fill(cx + 1, cy - 4, cx + 3, cy + 4, col);
        } else {
            renderPlayTriangleIcon(graphics, cx + 1, cy, 4.5f, col);
        }
    }

    private static void renderPrevTrackIcon(GuiGraphics graphics, int cx, int cy, float hover) {
        int col = lerpColor(hover, 0xFF94A3B8, 0xFFFFFFFF);
        graphics.fill(cx - 4, cy - 4, cx - 3, cy + 4, col);
        for (int i = 0; i <= 4; i++) {
            int h = (int) ((float) i / 4.0f * 3.5f);
            graphics.fill(cx + i - 1, cy - h, cx + i, cy + h + 1, col);
        }
    }

    private static void renderNextTrackIcon(GuiGraphics graphics, int cx, int cy, float hover) {
        int col = lerpColor(hover, 0xFF94A3B8, 0xFFFFFFFF);
        graphics.fill(cx + 3, cy - 4, cx + 4, cy + 4, col);
        for (int i = 0; i <= 4; i++) {
            int h = (int) ((float) (4 - i) / 4.0f * 3.5f);
            graphics.fill(cx - 1 + i, cy - h, cx + i, cy + h + 1, col);
        }
    }

    private static void renderDockVectorIcon(GuiGraphics graphics, int index, int cx, int cy, int color) {
        switch (index) {
            case 0 -> { // ⚙ Settings / Sliders
                graphics.fill(cx - 5, cy - 3, cx + 5, cy - 2, color);
                graphics.fill(cx - 5, cy + 2, cx + 5, cy + 3, color);
                drawCircle(graphics, cx - 2, cy - 3, 2, color);
                drawCircle(graphics, cx + 2, cy + 2, 2, color);
            }
            case 1 -> { // ✏ Customization / Edit Pencil
                for (int i = -4; i <= 4; i++) {
                    graphics.fill(cx + i, cy + i - 1, cx + i + 1, cy + i + 1, color);
                }
            }
            case 2 -> { // 👤 Baritone / User
                drawCircle(graphics, cx, cy - 2, 2, color);
                drawRoundedRect(graphics, cx - 4, cy + 2, 8, 4, 2, color);
            }
            case 3 -> { // ✕ Quit / Exit
                for (int i = -3; i <= 3; i++) {
                    graphics.fill(cx + i, cy + i, cx + i + 1, cy + i + 1, color);
                    graphics.fill(cx - i, cy + i, cx - i + 1, cy + i + 1, color);
                }
            }
        }
    }

    // =========================================================================
    // GEOMETRIC HELPER METHODS (ROUNDED RECTANGLES, BORDERS, CIRCLES)
    // =========================================================================

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
            graphics.fillGradient(x, y + r, x + width, y + height - r,
                    lerpColor(tMidStart, colorTop, colorBottom), lerpColor(tMidEnd, colorTop, colorBottom));
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

    private static void drawCircle(GuiGraphics graphics, int cx, int cy, int radius, int color) {
        if (radius <= 0 || ((color >>> 24) & 0xFF) == 0) return;
        for (int dy = -radius; dy <= radius; dy++) {
            int dx = (int) Math.round(Math.sqrt(Math.max(0.0, (double) radius * radius - dy * dy)));
            graphics.fill(cx - dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
        }
    }

    private static void drawCircleOutline(GuiGraphics graphics, int cx, int cy, int radius, int color) {
        if (radius <= 0 || ((color >>> 24) & 0xFF) == 0) return;
        for (int dy = -radius; dy <= radius; dy++) {
            int dx = (int) Math.round(Math.sqrt(Math.max(0.0, (double) radius * radius - dy * dy)));
            graphics.fill(cx - dx, cy + dy, cx - dx + 1, cy + dy + 1, color);
            graphics.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
        }
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

        int a = (int) (a1 + (a2 - a1) * clamped);
        int r = (int) (r1 + (r2 - r1) * clamped);
        int g = (int) (g1 + (g2 - g1) * clamped);
        int b = (int) (b1 + (b2 - b1) * clamped);

        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // =========================================================================
    // MOUSE & KEYBOARD INTERACTION
    // =========================================================================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        if (this.profileMenuOpen) {
            if (isPointInRect(mouseX, mouseY, this.profileDropdownX, this.profileDropdownY, this.profileDropdownW, this.profileDropdownH)) {
                playUiClick();
                int row = (int) ((mouseY - this.profileDropdownY - 5.0) / 20.0);
                if (row == 0 && this.minecraft != null) {
                    this.profileMenuOpen = false;
                    this.minecraft.setScreen(new SkinCustomizationScreen(this, this.minecraft.options));
                } else if (row == 1 && this.minecraft != null) {
                    this.minecraft.keyboardHandler.setClipboard(this.cachedPlayerName);
                    this.copiedUsernameFeedback = true;
                    this.copiedFeedbackTimer = 2.0f;
                } else if (row == 2) {
                    this.profileMenuOpen = false;
                    openAccountSwitcherFallback();
                }
                return true;
            }
            if (!isPointInRect(mouseX, mouseY, this.profilePillX, this.profilePillY, this.profilePillW, this.profilePillH)) {
                this.profileMenuOpen = false;
            }
        }

        if (isPointInRect(mouseX, mouseY, this.profilePillX, this.profilePillY, this.profilePillW, this.profilePillH)) {
            playUiClick();
            this.profileMenuOpen = !this.profileMenuOpen;
            return true;
        }

        if (isPointInRect(mouseX, mouseY, this.spPillX, this.spPillY, this.modePillW, this.modePillH)) {
            playUiClick();
            if (this.minecraft != null) {
                this.minecraft.setScreen(new AstraSingleplayerScreen(this));
            }
            return true;
        }

        if (isPointInRect(mouseX, mouseY, this.mpPillX, this.mpPillY, this.modePillW, this.modePillH)) {
            playUiClick();
            if (this.minecraft != null) {
                this.minecraft.setScreen(new AstraMultiplayerScreen(this));
            }
            return true;
        }

        if (isPointInRect(mouseX, mouseY, this.continueX, this.continueY, this.cardW, this.continueH)) {
            playUiClick();
            quickJoinLastSession();
            return true;
        }

        if (this.spotifyActive) {
            if (isPointInRect(mouseX, mouseY, this.sliderX - 4.0f, this.sliderY - 7.0f, this.sliderW + 8.0f, 17.0f)) {
                this.draggingSlider = true;
                scrubMusicToMouse(mouseX);
                return true;
            }

            if (isPointInCircle(mouseX, mouseY, this.playPauseBtnX, this.playPauseBtnY, this.mediaBtnR + 3.0f)) {
                playUiClick();
                this.musicPlaying = !this.musicPlaying;
                return true;
            }
            if (isPointInCircle(mouseX, mouseY, this.prevBtnX, this.prevBtnY, this.mediaBtnR + 2.0f)) {
                playUiClick();
                if (this.trackElapsedSeconds > 4.0f) {
                    this.trackElapsedSeconds = 0.0f;
                } else {
                    this.currentTrackIndex = (this.currentTrackIndex - 1 + TRACK_TITLES.length) % TRACK_TITLES.length;
                    this.trackElapsedSeconds = 0.0f;
                    syncTrackMetadataCache();
                }
                return true;
            }
            if (isPointInCircle(mouseX, mouseY, this.nextBtnX, this.nextBtnY, this.mediaBtnR + 2.0f)) {
                playUiClick();
                this.currentTrackIndex = (this.currentTrackIndex + 1) % TRACK_TITLES.length;
                this.trackElapsedSeconds = 0.0f;
                syncTrackMetadataCache();
                return true;
            }
        }

        for (int i = 0; i < 4; i++) {
            if (isPointInCircle(mouseX, mouseY, this.dockIconCenterX[i], this.dockY + this.dockH * 0.5f, 16.0f)) {
                playUiClick();
                handleDockAction(i);
                return true;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.draggingSlider && button == 0) {
            scrubMusicToMouse(mouseX);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && this.draggingSlider) {
            this.draggingSlider = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void scrubMusicToMouse(double mouseX) {
        float ratio = Mth.clamp((float) ((mouseX - this.sliderX) / this.sliderW), 0.0f, 1.0f);
        this.trackElapsedSeconds = ratio * TRACK_DURATIONS_SEC[this.currentTrackIndex];
        this.lastRenderedMusicSecond = -1;
        updateElapsedMusicTextIfNeeded();
    }

    private void quickJoinLastSession() {
        if (this.minecraft == null) return;
        try {
            ServerAddress address = ServerAddress.parseString(this.lastServerAddress);
            ServerData data = new ServerData(this.lastServerName, this.lastServerAddress, ServerData.Type.OTHER);
            ConnectScreen.startConnecting(this, this.minecraft, address, data, false, null);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private void handleDockAction(int index) {
        if (this.minecraft == null) return;
        switch (index) {
            case 0 -> this.minecraft.setScreen(new OptionsScreen(this, this.minecraft.options));
            case 1 -> this.minecraft.setScreen(new SkinCustomizationScreen(this, this.minecraft.options));
            case 2 -> this.minecraft.setScreen(new BaritoneBypassScreen());
            case 3 -> {
                System.setProperty("astra.allowStop", "true");
                this.minecraft.stop();
            }
        }
    }

    private void openAccountSwitcherFallback() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(new BaritoneBypassScreen());
        }
    }

    private void playUiClick() {
        if (this.minecraft != null) {
            this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.15f));
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static boolean isPointInRect(double px, double py, float x, float y, float w, float h) {
        return px >= x && px <= (x + w) && py >= y && py <= (y + h);
    }

    private static boolean isPointInCircle(double px, double py, float cx, float cy, float radius) {
        double dx = px - cx;
        double dy = py - cy;
        return (dx * dx + dy * dy) <= (radius * radius);
    }
}
