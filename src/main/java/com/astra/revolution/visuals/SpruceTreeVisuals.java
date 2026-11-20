package com.astra.revolution.visuals;

import baritone.Baritone;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.CoreShaders;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * ASTRA CLIENT 1.21.4 - Cyber Holographic Tree ESP & Tactical HUD (GOD-TIER Edition).
 *
 * Architektura:
 * - Rygorystyczne Zero-GC na wszystkich ścieżkach klatkowych (brak alokacji w onRenderWorld / onRenderHud).
 * - Komunikacja C++20 AVX2 przez JNI DirectByteBuffer (zero-copy, ścisły cykl clear/flip/rewind).
 * - Multi-Shell Additive Bloom gwarantujący 100% czytelności świata bez nieprzezroczystych brył.
 * - Kinetic Break Strain z dynamiczną rampą barw Cyber Cyan -> Gold -> Cyber Red.
 * - Szczelna izolacja stanu OpenGL przez AstraGlStateGuard(false, false, true) dla Iris / Complementary / BSL.
 * - Double Camera Translation Fix (zerowanie translacji m30, m31, m32 macierzy widoku).
 * - Celownik taktyczny [ + ] z fizyką sprężynowo-tłumiącą (Hooke spring-damper) niezależną od FPS.
 */
public final class SpruceTreeVisuals {

    // =========================================================================
    // PALETA KOLORÓW CYBER HOLOGRAPHIC ESP (0xAARRGGBB)
    // =========================================================================
    private static final int COL_CYBER_CYAN     = 0xFF00F0FF; // #00F0FF
    private static final int COL_HOLO_WHITE     = 0xFFE6FCFF; // Biało-błękitny hologram
    private static final int COL_CYBER_RED      = 0xFFFF3300; // #FF3300
    private static final int COL_CYBER_ORANGE   = 0xFFFF5500; // #FF5500
    private static final int COL_GOLD           = 0xFFFFD700; // #FFD700
    private static final int COL_EMERALD        = 0xFF00FF88; // #00FF88
    private static final int COL_OBSIDIAN_BG    = 0xDC090C12; // Obsidian Glass
    private static final int COL_PANEL_INNER    = 0xB8111620;
    private static final int COL_FOOTER_BG      = 0xE805070B;
    private static final int COL_BAR_TRACK      = 0xFF141923;
    private static final int COL_BORDER_DARK    = 0xFF2B3240;
    private static final int COL_BORDER_INNER   = 0x66404B61;
    private static final int COL_SHADOW_SOFT    = 0x55000000;
    private static final int COL_TEXT_PRIMARY   = 0xFFF0F4FA;
    private static final int COL_TEXT_MUTED     = 0xFF8D99AE;
    private static final int COL_OUTLINE_3D     = 0xCC031B24;

    // Limity przezroczystości gwarantujące 100% czytelności otoczenia gracza
    private static final int MAX_HOLO_TINT_ALPHA  = 18;  // ~7% max krycia dla powłok fill
    private static final int MAX_BLOOM_QUAD_ALPHA = 48;  // Miękkie addytywne wstęgi UV / aury
    private static final int MAX_OUTER_LINE_ALPHA = 96;  // Zewnętrzna poświata Multi-Shell

    // Fizyka sprężynowa celownika taktycznego [ + ]
    private static final float CROSSHAIR_IDLE_SPREAD     = 16.5f;
    private static final float CROSSHAIR_CHOP_SPREAD     = 12.0f;
    private static final float CROSSHAIR_KICK_VELOCITY   = 88.0f;
    private static final float CROSSHAIR_SPRING_K        = 190.0f;
    private static final float CROSSHAIR_SPRING_DAMPING  = 19.0f;

    // Pre-alokowane stałe tekstowe 3D i 2D (Zero-GC)
    private static final FormattedCharSequence LABEL_TITLE_3D =
            Component.literal("✦ ASTRA 2x2 SPRUCE FARM ✦").getVisualOrderText();

    private static final String HUD_TITLE         = "STATYSTYKI FARMY";
    private static final String HUD_CHOPPED_HDR   = "ŚCIĘTO:";
    private static final String HUD_STATUS_PREFIX = "STATUS: ";
    private static final String HUD_STATUS_SEP    = " / ";
    private static final String HUD_TOK_ANALIZA   = "ANALIZA";
    private static final String HUD_TOK_ZNIWA     = "ŻNIWA";
    private static final String HUD_TOK_SADZENIE  = "SADZENIE";

    private static final String[] PERCENT_STRINGS = new String[101];

    // =========================================================================
    // UKŁAD PAMIĘCI DIRECT BYTEBUFFER (JNI <-> C++20 AVX2, ZERO-COPY)
    // =========================================================================
    private static final int VERTEX_STRIDE = 16; // float x, y, z (12B) + uint32_t argb (4B)
    public static final int MAX_CANOPY_TIERS = 16;
    private static final int CANOPY_TIER_INTS = MAX_CANOPY_TIERS * 6; // 96 intów = 384B

    // Pojemności buforów z bezpieczną rezerwą na efekty nakładkowe (Kinetic Break Strain)
    private static final int MAX_SOLID_QUAD_VERTS        = 1024;
    private static final int NATIVE_MAX_BLOOM_QUAD_VERTS = 1536;
    private static final int RESERVED_MINING_QUAD_VERTS  = 512;
    private static final int MAX_BLOOM_QUAD_VERTS        = NATIVE_MAX_BLOOM_QUAD_VERTS + RESERVED_MINING_QUAD_VERTS; // 2048

    private static final int NATIVE_MAX_BLOOM_LINE_VERTS = 3072;
    private static final int RESERVED_MINING_LINE_VERTS  = 1024;
    private static final int MAX_BLOOM_LINE_VERTS        = NATIVE_MAX_BLOOM_LINE_VERTS + RESERVED_MINING_LINE_VERTS; // 4096

    // Rozmiar struktury AstraCyberEspInput (#pragma pack(1) = 440 bajtów)
    // Layout: [0..23] camXYZ (3x double), [24..51] 7x int, [52..435] 96x int, [436..439] timeSec (float)
    private static final int INPUT_BYTES = 440;
    private static final int OUT_COUNTS_BYTES = 12;

    private static final ByteBuffer INPUT_BUF =
            ByteBuffer.allocateDirect(INPUT_BYTES).order(ByteOrder.nativeOrder());
    private static final ByteBuffer SOLID_QUAD_BUF =
            ByteBuffer.allocateDirect(MAX_SOLID_QUAD_VERTS * VERTEX_STRIDE).order(ByteOrder.nativeOrder());
    private static final ByteBuffer BLOOM_QUAD_BUF =
            ByteBuffer.allocateDirect(MAX_BLOOM_QUAD_VERTS * VERTEX_STRIDE).order(ByteOrder.nativeOrder());
    private static final ByteBuffer BLOOM_LINE_BUF =
            ByteBuffer.allocateDirect(MAX_BLOOM_LINE_VERTS * VERTEX_STRIDE).order(ByteOrder.nativeOrder());
    private static final ByteBuffer OUT_COUNTS_BUF =
            ByteBuffer.allocateDirect(OUT_COUNTS_BYTES).order(ByteOrder.nativeOrder());

    private static final boolean NATIVE_DLL_LOADED;
    private static final String BRIDGE_LABEL;

    // Pre-alokowane obiekty robocze (Zero-GC w pętli renderowania)
    private static final Matrix4f VIEW_ROTATION_MATRIX = new Matrix4f();
    private static final Matrix4f LABEL_MATRIX = new Matrix4f();
    private static final Vector3f SCRATCH_VEC3 = new Vector3f();
    private static final BlockPos.MutableBlockPos SCRATCH_MINING_POS = new BlockPos.MutableBlockPos();

    private static final long START_NANOS = System.nanoTime();

    static {
        for (int i = 0; i <= 100; i++) {
            PERCENT_STRINGS[i] = i + "%";
        }

        boolean loaded = false;
        try {
            Path dll = resolveDllPath();
            if (dll != null && Files.exists(dll)) {
                try {
                    System.load(dll.toAbsolutePath().toString());
                    loaded = true;
                    System.out.println("[Astra/SpruceTreeVisuals] Successfully loaded native library via JNI: " + dll);
                } catch (Throwable t) {
                    System.err.println("[Astra/SpruceTreeVisuals] System.load failed: " + t.getMessage());
                }
            }
        } catch (Throwable t) {
            loaded = false;
        }
        NATIVE_DLL_LOADED = loaded;
        BRIDGE_LABEL = loaded ? "C++20 AVX2" : "JVM DIRECT";
    }

    private static Path resolveDllPath() {
        Path[] candidates = new Path[]{
                Path.of("native-core", "astra-core.dll"),
                Path.of("native-core", "astra_core.dll"),
                Path.of("..", "native-core", "astra-core.dll"),
                Path.of("..", "native-core", "astra_core.dll"),
                Path.of("astra-core.dll"),
                Path.of("astra_core.dll")
        };
        for (Path p : candidates) {
            try {
                Path normalized = p.toAbsolutePath().normalize();
                if (Files.isRegularFile(normalized)) {
                    return normalized;
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            Path tempDir = Path.of(System.getProperty("java.io.tmpdir", "."), "astra_native");
            Files.createDirectories(tempDir);
            Path targetDll = tempDir.resolve("astra-core.dll");
            try (InputStream is = SpruceTreeVisuals.class.getResourceAsStream("/assets/astra/native/astra-core.dll")) {
                if (is != null) {
                    try {
                        Files.copy(is, targetDll, StandardCopyOption.REPLACE_EXISTING);
                    } catch (Throwable ignored) {
                    }
                    if (Files.isRegularFile(targetDll)) {
                        return targetDll.toAbsolutePath();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // Deklaracja natywna JNI (zachowany w 100% zgodny kontrakt ABI z astra-core.dll)
    private static native void nativeBuildCyberEsp(
            ByteBuffer inputBuf,
            ByteBuffer solidQuadBuf, int maxSolidQuadVerts,
            ByteBuffer bloomQuadBuf, int maxBloomQuadVerts,
            ByteBuffer bloomLineBuf, int maxBloomLineVerts,
            ByteBuffer outCountsBuf
    );

    // =========================================================================
    // STAN BOTA, KINETIC BREAK STRAIN I ZERO-GC CACHE TEKSTÓW
    // =========================================================================
    public enum FarmStatus {
        ANALIZA(HUD_TOK_ANALIZA, COL_CYBER_CYAN),
        ZNIWA(HUD_TOK_ZNIWA, COL_GOLD),
        SADZENIE(HUD_TOK_SADZENIE, COL_EMERALD);

        public final String displayName;
        public final int color;

        FarmStatus(String displayName, int color) {
            this.displayName = displayName;
            this.color = color;
        }
    }

    private static volatile boolean active = false;
    private static FarmStatus currentStatus = FarmStatus.ANALIZA;
    private static volatile boolean activelyChopping = false;

    private static volatile boolean hasTrunk = false;
    private static int trunkMinX = 0;
    private static int trunkMinZ = 0;
    private static int baseSoilY = 0;
    private static int topLogY = 0;

    private static volatile boolean hasLeaves = false;
    private static int canopyTierCount = 0;
    private static final int[] canopyTiersFlat = new int[CANOPY_TIER_INTS];
    private static double canopyTopCenterX = 0.0;
    private static double canopyTopCenterY = 0.0;
    private static double canopyTopCenterZ = 0.0;

    // Prymitywny zapis aktywnego celu kopania (Zero-GC nawet przy MutableBlockPos)
    private static volatile boolean hasActiveMiningPos = false;
    private static int miningBlockX = 0;
    private static int miningBlockY = 0;
    private static int miningBlockZ = 0;
    private static long miningTargetStartNanos = 0L;
    private static float externalMiningProgress = -1.0f;
    private static float smoothedBreakStrain = 0.0f;
    private static long lastWorldNanos = 0L;

    // Statystyki farmy
    private static int choppedLogsCount = 0;
    private static int targetLogsGoal = 150;
    private static int choppedLeavesCount = 0;

    // Zero-GC Cache tekstów HUD 2D (odświeżane wyłącznie przy zmianie wartości)
    private static int cachedLogs = -1;
    private static int cachedGoal = -1;
    private static String cachedLogsLine = "- DREWNO (SPRUCE): 0 / 150";
    private static int cachedLeaves = -1;
    private static String cachedLeavesLine = "- LIŚCIE (LEAVES): 0";
    private static int cachedFps = -1;
    private static String cachedFooterLine = "BRIDGE: " + BRIDGE_LABEL + " | FPS: 240 | HOLO v3.2";

    // Zero-GC Cache etykiety 3D w przestrzeni świata
    private static int cachedLabel3DHeight = -1;
    private static FarmStatus cachedLabel3DStatus = null;
    private static int cachedLabel3DLogs = -1;
    private static int cachedLabel3DGoal = -1;
    private static FormattedCharSequence cachedSubLabel3D =
            Component.literal("§fH: §a1m §8| §fStatus: §eANALIZA §8| §60§7/§e150").getVisualOrderText();

    // Stan animacji HUD 2D i sprężyny celownika (niezależny od liczby klatek)
    private static float displayedGoalRatio = 0.0f;
    private static float crosshairSpread = CROSSHAIR_IDLE_SPREAD;
    private static float crosshairSpreadVel = 0.0f;
    private static float crosshairActiveBlend = 0.0f;
    private static boolean prevActivelyChopping = false;
    private static long lastHudNanos = 0L;

    private SpruceTreeVisuals() {
    }

    // =========================================================================
    // API AKTUALIZACJI STANU Z SpruceTreeProcess
    // =========================================================================
    public static void setActiveMiningTarget(BlockPos pos) {
        if (pos == null) {
            hasActiveMiningPos = false;
            externalMiningProgress = -1.0f;
            return;
        }
        int nx = pos.getX();
        int ny = pos.getY();
        int nz = pos.getZ();
        if (!hasActiveMiningPos || nx != miningBlockX || ny != miningBlockY || nz != miningBlockZ) {
            miningBlockX = nx;
            miningBlockY = ny;
            miningBlockZ = nz;
            hasActiveMiningPos = true;
            miningTargetStartNanos = System.nanoTime();
            smoothedBreakStrain = 0.0f;
            externalMiningProgress = -1.0f;
        }
    }

    /**
     * Opcjonalny hook pozwalający przekazać dokładny postęp niszczenia bloku [0.0..1.0].
     * Wartość ujemna włącza automatyczną estymację z BlockState#getDestroyProgress.
     */
    public static void setActiveMiningProgress(float progress) {
        externalMiningProgress = (progress < 0.0f) ? -1.0f : Mth.clamp(progress, 0.0f, 1.0f);
    }

    public static void updateState(
            boolean isBotActive,
            FarmStatus status,
            boolean isChoppingNow,
            boolean trunkFound, int tMinX, int tMinZ, int soilY, int topY,
            boolean leavesFound, int tierCount, int[] tiersFlat,
            double leavesLabelX, double leavesLabelY, double leavesLabelZ,
            int logsChopped, int logsGoal, int leavesChopped
    ) {
        active = isBotActive;
        if (!active) {
            activelyChopping = false;
            hasActiveMiningPos = false;
            return;
        }
        currentStatus = (status != null) ? status : FarmStatus.ANALIZA;
        activelyChopping = isChoppingNow;
        hasTrunk = trunkFound;
        trunkMinX = tMinX;
        trunkMinZ = tMinZ;
        baseSoilY = soilY;
        topLogY = Math.max(soilY + 1, topY);
        hasLeaves = leavesFound;

        int clampedTiers = Mth.clamp(tierCount, 0, MAX_CANOPY_TIERS);
        if (clampedTiers > 0 && tiersFlat != null) {
            int maxCopy = Math.min(clampedTiers * 6, tiersFlat.length);
            canopyTierCount = maxCopy / 6;
            System.arraycopy(tiersFlat, 0, canopyTiersFlat, 0, canopyTierCount * 6);
        } else {
            canopyTierCount = 0;
        }

        canopyTopCenterX = leavesLabelX;
        canopyTopCenterY = leavesLabelY;
        canopyTopCenterZ = leavesLabelZ;
        choppedLogsCount = Math.max(0, logsChopped);
        targetLogsGoal = Math.max(1, logsGoal);
        choppedLeavesCount = Math.max(0, leavesChopped);
    }

    // =========================================================================
    // 1. RENDEROWANIE W ŚWIECIE 3D (MULTI-SHELL ADDITIVE BLOOM + IRIS GUARD)
    // =========================================================================
    public static void onRenderWorld(PoseStack poseStack, float tickDelta) {
        final boolean showMiningOverlay = hasActiveMiningPos && activelyChopping;
        if (!active || (!hasTrunk && !hasLeaves && !showMiningOverlay)) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }

        final long nowNanos = System.nanoTime();
        final float dt = computeClampedDt(nowNanos, lastWorldNanos);
        lastWorldNanos = nowNanos;
        final float timeSec = (nowNanos - START_NANOS) * 1.0e-9f;

        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 camPos = camera.getPosition();
        final double camX = camPos.x;
        final double camY = camPos.y;
        final double camZ = camPos.z;

        // Aktualizacja fizycznego naprężenia niszczonego bloku (Kinetic Break Strain)
        updateKineticBreakStrain(mc, nowNanos, dt, showMiningOverlay);

        // 1) Pakowanie danych wejściowych do INPUT_BUF (Zero-Copy, sekwencyjny clear -> put -> flip)
        packInputBuffer(camX, camY, camZ, timeSec);

        // 2) Zerowanie pozycji buforów wyjściowych przed generowaniem geometrii
        SOLID_QUAD_BUF.clear();
        BLOOM_QUAD_BUF.clear();
        BLOOM_LINE_BUF.clear();
        OUT_COUNTS_BUF.clear();

        int solidQuadVerts;
        int bloomQuadVerts;
        int bloomLineVerts;

        if (NATIVE_DLL_LOADED) {
            try {
                nativeBuildCyberEsp(
                        INPUT_BUF,
                        SOLID_QUAD_BUF, MAX_SOLID_QUAD_VERTS,
                        BLOOM_QUAD_BUF, NATIVE_MAX_BLOOM_QUAD_VERTS,
                        BLOOM_LINE_BUF, NATIVE_MAX_BLOOM_LINE_VERTS,
                        OUT_COUNTS_BUF
                );
                OUT_COUNTS_BUF.position(0).limit(OUT_COUNTS_BYTES);
                solidQuadVerts = clampQuadVerts(OUT_COUNTS_BUF.getInt(0), MAX_SOLID_QUAD_VERTS);
                bloomQuadVerts = clampQuadVerts(OUT_COUNTS_BUF.getInt(4), NATIVE_MAX_BLOOM_QUAD_VERTS);
                bloomLineVerts = clampLineVerts(OUT_COUNTS_BUF.getInt(8), NATIVE_MAX_BLOOM_LINE_VERTS);
            } catch (Throwable t) {
                buildFallbackGeometry(camX, camY, camZ, timeSec);
                OUT_COUNTS_BUF.position(0).limit(OUT_COUNTS_BYTES);
                solidQuadVerts = clampQuadVerts(OUT_COUNTS_BUF.getInt(0), MAX_SOLID_QUAD_VERTS);
                bloomQuadVerts = clampQuadVerts(OUT_COUNTS_BUF.getInt(4), NATIVE_MAX_BLOOM_QUAD_VERTS);
                bloomLineVerts = clampLineVerts(OUT_COUNTS_BUF.getInt(8), NATIVE_MAX_BLOOM_LINE_VERTS);
            }
        } else {
            buildFallbackGeometry(camX, camY, camZ, timeSec);
            OUT_COUNTS_BUF.position(0).limit(OUT_COUNTS_BYTES);
            solidQuadVerts = clampQuadVerts(OUT_COUNTS_BUF.getInt(0), MAX_SOLID_QUAD_VERTS);
            bloomQuadVerts = clampQuadVerts(OUT_COUNTS_BUF.getInt(4), NATIVE_MAX_BLOOM_QUAD_VERTS);
            bloomLineVerts = clampLineVerts(OUT_COUNTS_BUF.getInt(8), NATIVE_MAX_BLOOM_LINE_VERTS);
        }

        // 3) Dopisanie efektu Kinetic Break Strain w zarezerwowanej strefie buforów (działa zarówno z DLL jak i JVM)
        if (showMiningOverlay) {
            bloomQuadVerts = appendKineticBreakStrainQuads(
                    BLOOM_QUAD_BUF, bloomQuadVerts, MAX_BLOOM_QUAD_VERTS, camX, camY, camZ, timeSec
            );
            bloomLineVerts = appendKineticBreakStrainLines(
                    BLOOM_LINE_BUF, bloomLineVerts, MAX_BLOOM_LINE_VERTS, camX, camY, camZ, timeSec
            );
            bloomQuadVerts = clampQuadVerts(bloomQuadVerts, MAX_BLOOM_QUAD_VERTS);
            bloomLineVerts = clampLineVerts(bloomLineVerts, MAX_BLOOM_LINE_VERTS);
        }

        // Bezpieczne ustawienie granic DirectByteBuffer (position = 0, limit = zapisane bajty)
        SOLID_QUAD_BUF.position(0).limit(solidQuadVerts * VERTEX_STRIDE);
        BLOOM_QUAD_BUF.position(0).limit(bloomQuadVerts * VERTEX_STRIDE);
        BLOOM_LINE_BUF.position(0).limit(bloomLineVerts * VERTEX_STRIDE);

        // POPRAWKA PRZESUNIĘCIA KAMERY (DOUBLE CAMERA TRANSLATION FIX)
        // Wierzchołki są już w układzie relatywnym do kamery (world - cam), więc zerujemy translację.
        VIEW_ROTATION_MATRIX.set(poseStack.last().pose());
        VIEW_ROTATION_MATRIX.m30(0.0f);
        VIEW_ROTATION_MATRIX.m31(0.0f);
        VIEW_ROTATION_MATRIX.m32(0.0f);

        // 4) Szczelny blok renderowania 3D kompatybilny z Iris / BSL / Complementary
        try (AstraGlStateGuard guard = new AstraGlStateGuard(false, false, true)) {
            try {
                RenderSystem.setShader(CoreShaders.POSITION_COLOR);
                RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
                RenderSystem.enableBlend();
                RenderSystem.disableCull();
                RenderSystem.depthMask(false);

                // Przebieg 1: Ultra-subtelna, półprzezroczysta mgiełka holograficzna (alpha <= 18/255)
                if (solidQuadVerts >= 4) {
                    RenderSystem.defaultBlendFunc();
                    drawDirectBuffer(
                            SOLID_QUAD_BUF, solidQuadVerts, VertexFormat.Mode.QUADS,
                            VIEW_ROTATION_MATRIX, 1.0f, MAX_HOLO_TINT_ALPHA
                    );
                }

                // Przebieg 2: Addytywne wstęgi UV sweep-lasera, aureoli korony i aury naprężenia
                if (bloomQuadVerts >= 4) {
                    RenderSystem.blendFuncSeparate(
                            GlStateManager.SourceFactor.SRC_ALPHA,
                            GlStateManager.DestFactor.ONE,
                            GlStateManager.SourceFactor.ONE,
                            GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA
                    );
                    drawDirectBuffer(
                            BLOOM_QUAD_BUF, bloomQuadVerts, VertexFormat.Mode.QUADS,
                            VIEW_ROTATION_MATRIX, 1.0f, MAX_BLOOM_QUAD_ALPHA
                    );
                }

                // Przebieg 3: Multi-Shell Additive Bloom dla laserowych krawędzi, bracketów i pierścieni
                if (bloomLineVerts >= 2) {
                    RenderSystem.blendFuncSeparate(
                            GlStateManager.SourceFactor.SRC_ALPHA,
                            GlStateManager.DestFactor.ONE,
                            GlStateManager.SourceFactor.ONE,
                            GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA
                    );

                    // Shell A: Miękka, szeroka poświata zewnętrzna (Additive Halo)
                    RenderSystem.lineWidth(3.0f);
                    drawDirectBuffer(
                            BLOOM_LINE_BUF, bloomLineVerts, VertexFormat.Mode.DEBUG_LINES,
                            VIEW_ROTATION_MATRIX, 0.28f, MAX_OUTER_LINE_ALPHA
                    );

                    // Shell B: Ostry, wysokokontrastowy rdzeń neonowy (Razor Core)
                    RenderSystem.lineWidth(1.6f);
                    drawDirectBuffer(
                            BLOOM_LINE_BUF, bloomLineVerts, VertexFormat.Mode.DEBUG_LINES,
                            VIEW_ROTATION_MATRIX, 1.0f, 255
                    );
                }

                // Przywrócenie bazowego stanu przed potokiem czcionek 3D
                RenderSystem.lineWidth(1.0f);
                RenderSystem.defaultBlendFunc();
                RenderSystem.enableCull();
                RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

                // Przebieg 4: Holograficzne etykiety 3D w świecie (Zero-GC)
                renderWorldHolographicLabels(mc, camera, camX, camY, camZ, timeSec);
            } finally {
                // Deterministyczne przywrócenie stanu Blaze3D (ochrona przed czarnym ekranem i artefaktami GUI)
                RenderSystem.lineWidth(1.0f);
                RenderSystem.depthMask(true);
                RenderSystem.enableCull();
                RenderSystem.defaultBlendFunc();
                RenderSystem.disableBlend();
                RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            }
        }
    }

    public static void render3D(PoseStack poseStack, Vec3 camPos, Baritone baritone) {
        onRenderWorld(poseStack, 1.0f);
    }

    private static void packInputBuffer(double camX, double camY, double camZ, float timeSec) {
        INPUT_BUF.clear();
        INPUT_BUF.putDouble(camX);              // offset 0
        INPUT_BUF.putDouble(camY);              // offset 8
        INPUT_BUF.putDouble(camZ);              // offset 16
        INPUT_BUF.putInt(hasTrunk ? 1 : 0);     // offset 24
        INPUT_BUF.putInt(trunkMinX);            // offset 28
        INPUT_BUF.putInt(trunkMinZ);            // offset 32
        INPUT_BUF.putInt(baseSoilY);            // offset 36
        INPUT_BUF.putInt(topLogY);              // offset 40
        INPUT_BUF.putInt(hasLeaves ? 1 : 0);    // offset 44
        INPUT_BUF.putInt(canopyTierCount);      // offset 48

        final int activeInts = canopyTierCount * 6;
        for (int i = 0; i < CANOPY_TIER_INTS; i++) {
            INPUT_BUF.putInt(i < activeInts ? canopyTiersFlat[i] : 0); // offset 52..435
        }
        INPUT_BUF.putFloat(timeSec);            // offset 436..439
        INPUT_BUF.flip();                       // position = 0, limit = 440
    }

    private static void drawDirectBuffer(
            ByteBuffer buf,
            int vertexCount,
            VertexFormat.Mode mode,
            Matrix4f matrix,
            float alphaScale,
            int maxAlpha
    ) {
        if (vertexCount <= 0) {
            return;
        }
        final int requiredBytes = vertexCount * VERTEX_STRIDE;
        if (requiredBytes > buf.capacity()) {
            return;
        }

        buf.rewind();
        if (buf.limit() < requiredBytes) {
            buf.limit(requiredBytes);
        }

        BufferBuilder builder = Tesselator.getInstance().begin(mode, DefaultVertexFormat.POSITION_COLOR);
        final boolean scaleAlpha = alphaScale < 0.999f;
        for (int i = 0; i < vertexCount; i++) {
            float x = buf.getFloat();
            float y = buf.getFloat();
            float z = buf.getFloat();
            int argb = buf.getInt();

            int a = (argb >>> 24) & 0xFF;
            if (scaleAlpha) {
                a = (int) (a * alphaScale);
            }
            if (a > maxAlpha) {
                a = maxAlpha;
            }
            argb = (a << 24) | (argb & 0x00FFFFFF);

            builder.addVertex(matrix, x, y, z).setColor(argb);
        }

        MeshData mesh = builder.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
        }
    }

    private static void updateKineticBreakStrain(Minecraft mc, long nowNanos, float dt, boolean showMiningOverlay) {
        if (!showMiningOverlay) {
            float decay = 1.0f - (float) Math.exp(-14.0f * dt);
            smoothedBreakStrain += (0.0f - smoothedBreakStrain) * decay;
            if (smoothedBreakStrain < 0.002f) {
                smoothedBreakStrain = 0.0f;
            }
            return;
        }

        float targetStrain;
        if (externalMiningProgress >= 0.0f) {
            targetStrain = externalMiningProgress;
        } else {
            SCRATCH_MINING_POS.set(miningBlockX, miningBlockY, miningBlockZ);
            BlockState state = mc.level.getBlockState(SCRATCH_MINING_POS);
            if (state.isAir()) {
                targetStrain = 0.0f;
                miningTargetStartNanos = nowNanos;
            } else {
                float progressPerTick = state.getDestroyProgress(mc.player, mc.level, SCRATCH_MINING_POS);
                float elapsedSec = Math.max(0.0f, (nowNanos - miningTargetStartNanos) * 1.0e-9f);
                if (progressPerTick > 0.0001f) {
                    targetStrain = Mth.clamp(elapsedSec * 20.0f * progressPerTick, 0.0f, 1.0f);
                } else {
                    targetStrain = Mth.clamp(elapsedSec / 0.75f, 0.0f, 1.0f);
                }
            }
        }

        float follow = 1.0f - (float) Math.exp(-22.0f * dt);
        smoothedBreakStrain += (targetStrain - smoothedBreakStrain) * follow;
        smoothedBreakStrain = Mth.clamp(smoothedBreakStrain, 0.0f, 1.0f);
    }

    private static void renderWorldHolographicLabels(
            Minecraft mc, Camera camera, double camX, double camY, double camZ, float timeSec
    ) {
        if (!hasTrunk && !hasLeaves) {
            return;
        }

        Font font = mc.font;
        if (font == null) {
            return;
        }

        double centerX = hasTrunk ? (trunkMinX + 1.0) : canopyTopCenterX;
        double centerZ = hasTrunk ? (trunkMinZ + 1.0) : canopyTopCenterZ;
        double topY = hasTrunk ? (topLogY + 3.2) : (canopyTopCenterY + 2.4);

        double dx = centerX - camX;
        double dy = topY - camY;
        double dz = centerZ - camZ;
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq <= 10.24D || distSq >= 4096.0D) { // 3.2m .. 64.0m
            return;
        }

        double dist = Math.sqrt(distSq);
        float scaleTitle = Mth.clamp(0.024f + (float) dist * 0.00038f, 0.024f, 0.044f);
        float scaleSub = scaleTitle * 0.76f;
        float harmonic = 0.5f + 0.5f * Mth.sin(timeSec * 3.14159265f);

        refreshWorldLabelCache();

        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        int titleCol = lerpRgb(COL_CYBER_CYAN, COL_HOLO_WHITE, 0.45f + 0.55f * harmonic, 255);

        SCRATCH_VEC3.set((float) dx, (float) dy, (float) dz);
        drawBillboardText3D(
                font, bufferSource, camera,
                LABEL_TITLE_3D,
                SCRATCH_VEC3.x, SCRATCH_VEC3.y, SCRATCH_VEC3.z,
                titleCol, COL_OUTLINE_3D, scaleTitle
        );

        drawBillboardText3D(
                font, bufferSource, camera,
                cachedSubLabel3D,
                SCRATCH_VEC3.x, SCRATCH_VEC3.y - (scaleTitle * 14.2f), SCRATCH_VEC3.z,
                COL_TEXT_PRIMARY, COL_OUTLINE_3D, scaleSub
        );

        bufferSource.endBatch();
    }

    private static void refreshWorldLabelCache() {
        int height = hasTrunk ? Math.max(1, topLogY - baseSoilY + 1) : 0;
        if (height != cachedLabel3DHeight
                || currentStatus != cachedLabel3DStatus
                || choppedLogsCount != cachedLabel3DLogs
                || targetLogsGoal != cachedLabel3DGoal) {
            cachedLabel3DHeight = height;
            cachedLabel3DStatus = currentStatus;
            cachedLabel3DLogs = choppedLogsCount;
            cachedLabel3DGoal = targetLogsGoal;
            cachedSubLabel3D = Component.literal(
                    "§fH: §a" + height + "m §8| §fStatus: §e" + currentStatus.displayName
                            + " §8| §6" + choppedLogsCount + "§7/§e" + targetLogsGoal
            ).getVisualOrderText();
        }
    }

    private static void drawBillboardText3D(
            Font font,
            MultiBufferSource bufferSource,
            Camera camera,
            FormattedCharSequence text,
            float relX, float relY, float relZ,
            int textColor, int outlineColor, float scale
    ) {
        LABEL_MATRIX.set(VIEW_ROTATION_MATRIX);
        LABEL_MATRIX.translate(relX, relY, relZ);
        LABEL_MATRIX.rotate(camera.rotation());
        LABEL_MATRIX.scale(scale, -scale, scale);

        float halfWidth = -font.width(text) * 0.5f;
        font.drawInBatch8xOutline(
                text,
                halfWidth,
                0.0f,
                textColor,
                outlineColor,
                LABEL_MATRIX,
                bufferSource,
                LightTexture.FULL_BRIGHT
        );
    }

    // =========================================================================
    // 2. TACTICAL HUD 2D (OBSIDIAN GLASS CARD + DYNAMICZNY CELOWNIK [ + ])
    // =========================================================================
    public static void render2D(GuiGraphics guiGraphics, DeltaTracker deltaTracker, Baritone baritone) {
        onRenderHud(guiGraphics, deltaTracker != null ? deltaTracker.getGameTimeDeltaPartialTick(true) : 1.0f);
    }

    public static void onRenderHud(GuiGraphics gfx, float tickDelta) {
        if (!active) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) {
            return;
        }
        Font font = mc.font;
        if (font == null) {
            return;
        }

        final long nowNanos = System.nanoTime();
        final float dt = computeClampedDt(nowNanos, lastHudNanos);
        lastHudNanos = nowNanos;
        final float timeSec = (nowNanos - START_NANOS) * 1.0e-9f;

        final int screenW = mc.getWindow().getGuiScaledWidth();
        final int screenH = mc.getWindow().getGuiScaledHeight();

        refreshHudCache(mc.getFps());
        stepHudPhysics(dt, timeSec);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        // A. Karta Statusu w Prawym Górnym Rogu ("STATYSTYKI FARMY" - Obsidian Glass)
        renderFarmStatsCard(gfx, font, screenW, screenH, timeSec);

        // B. Taktyczny Celownik [ + ] z fizyką sprężynową na środku ekranu
        renderTacticalCrosshair(gfx, screenW, screenH, timeSec);

        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private static void stepHudPhysics(float dt, float timeSec) {
        // Płynne doganianie paska postępu ścinki
        float targetRatio = Mth.clamp((float) choppedLogsCount / (float) targetLogsGoal, 0.0f, 1.0f);
        float barFollow = 1.0f - (float) Math.exp(-10.0f * dt);
        displayedGoalRatio += (targetRatio - displayedGoalRatio) * barFollow;
        if (Math.abs(targetRatio - displayedGoalRatio) < 0.001f) {
            displayedGoalRatio = targetRatio;
        }

        // Impuls rozchylenia celownika przy rozpoczęciu ścinki (kinetic kick + powrót sprężynowy)
        final boolean choppingNow = activelyChopping;
        if (choppingNow && !prevActivelyChopping) {
            crosshairSpreadVel += CROSSHAIR_KICK_VELOCITY;
        }
        prevActivelyChopping = choppingNow;

        float targetSpread = choppingNow
                ? (CROSSHAIR_CHOP_SPREAD + 1.35f * Mth.sin(timeSec * 12.5f) + 2.2f * smoothedBreakStrain)
                : (CROSSHAIR_IDLE_SPREAD + 0.55f * Mth.sin(timeSec * 2.6f));

        // Całkowanie sprężynowo-tłumiące Hooke'a: F = k*(target - x) - c*v
        float accel = (targetSpread - crosshairSpread) * CROSSHAIR_SPRING_K
                - crosshairSpreadVel * CROSSHAIR_SPRING_DAMPING;
        crosshairSpreadVel += accel * dt;
        crosshairSpread += crosshairSpreadVel * dt;
        crosshairSpread = Mth.clamp(crosshairSpread, 8.0f, 30.0f);

        float blendTarget = choppingNow ? 1.0f : 0.0f;
        float blendFollow = 1.0f - (float) Math.exp(-12.0f * dt);
        crosshairActiveBlend += (blendTarget - crosshairActiveBlend) * blendFollow;
    }

    private static void renderFarmStatsCard(GuiGraphics gfx, Font font, int screenW, int screenH, float timeSec) {
        final int minWidthForFooter = font.width(cachedFooterLine) + 18;
        final int panelW = Math.min(Math.max(256, minWidthForFooter), Math.max(180, screenW - 8));
        final int panelH = 104;
        final int margin = Mth.clamp(screenW / 60, 6, 14);
        final int x = Math.max(4, screenW - panelW - margin);
        final int y = Math.min(margin, Math.max(4, screenH - panelH - 4));

        // Miękki cień zewnętrzny Obsidian Glass
        gfx.fill(x - 1, y - 1, x + panelW + 1, y + panelH + 1, COL_SHADOW_SOFT);

        // Główna tafla Obsidian Glass (0xDC090C12) + zagłębiona komora telemetrii
        gfx.fill(x, y, x + panelW, y + panelH, COL_OBSIDIAN_BG);
        gfx.fill(x + 5, y + 21, x + panelW - 5, y + panelH - 17, COL_PANEL_INNER);

        // Podwójne obramowanie: zewnętrzna rama stalowa + wewnętrzna linia szkła
        gfx.fill(x, y, x + panelW, y + 1, COL_GOLD);
        gfx.fill(x, y + panelH - 1, x + panelW, y + panelH, COL_BORDER_DARK);
        gfx.fill(x, y, x + 1, y + panelH, COL_BORDER_DARK);
        gfx.fill(x + panelW - 1, y, x + panelW, y + panelH, COL_BORDER_DARK);

        gfx.fill(x + 2, y + 2, x + panelW - 2, y + 3, COL_BORDER_INNER);
        gfx.fill(x + 2, y + panelH - 3, x + panelW - 2, y + panelH - 2, COL_BORDER_INNER);
        gfx.fill(x + 2, y + 2, x + 3, y + panelH - 2, COL_BORDER_INNER);
        gfx.fill(x + panelW - 3, y + 2, x + panelW - 2, y + panelH - 2, COL_BORDER_INNER);

        // Złote narożniki taktyczne [L] (poziome + pionowe ramiona na wszystkich 4 rogach)
        final int cornerW = 12;
        final int cornerH = 9;
        // Lewy górny
        gfx.fill(x, y, x + cornerW, y + 2, COL_GOLD);
        gfx.fill(x, y, x + 2, y + cornerH, COL_GOLD);
        // Prawy górny
        gfx.fill(x + panelW - cornerW, y, x + panelW, y + 2, COL_GOLD);
        gfx.fill(x + panelW - 2, y, x + panelW, y + cornerH, COL_GOLD);
        // Lewy dolny
        gfx.fill(x, y + panelH - 2, x + cornerW, y + panelH, COL_GOLD);
        gfx.fill(x, y + panelH - cornerH, x + 2, y + panelH, COL_GOLD);
        // Prawy dolny
        gfx.fill(x + panelW - cornerW, y + panelH - 2, x + panelW, y + panelH, COL_GOLD);
        gfx.fill(x + panelW - 2, y + panelH - cornerH, x + panelW, y + panelH, COL_GOLD);

        // Nagłówek karty + pulsujący znacznik aktywności
        int pulseDotCol = withAlpha(
                activelyChopping ? COL_GOLD : currentStatus.color,
                (int) (180 + 75 * (0.5f + 0.5f * Mth.sin(timeSec * 5.0f)))
        );
        gfx.fill(x + 9, y + 8, x + 13, y + 12, pulseDotCol);
        gfx.drawString(font, HUD_TITLE, x + 17, y + 6, COL_GOLD, true);

        // Procent postępu (Zero-GC tablica PERCENT_STRINGS) + poziomy pasek gradientowy (Cyber Red -> Gold)
        int pctIndex = Mth.clamp(Math.round(displayedGoalRatio * 100.0f), 0, 100);
        String pctStr = PERCENT_STRINGS[pctIndex];
        int pctW = font.width(pctStr);
        int pctX = x + panelW - pctW - 9;
        gfx.drawString(font, pctStr, pctX, y + 6, COL_HOLO_WHITE, true);

        int barW = Math.min(78, Math.max(40, panelW - 165));
        int barX = pctX - barW - 6;
        int barY = y + 8;
        drawHorizontalGradientBar(gfx, barX, barY, barW, 5, displayedGoalRatio, timeSec);

        // Statystyki ścinki (Zero-GC cache)
        gfx.drawString(font, HUD_CHOPPED_HDR, x + 10, y + 26, COL_TEXT_PRIMARY, true);
        gfx.drawString(font, cachedLogsLine, x + 14, y + 38, COL_CYBER_ORANGE, false);
        gfx.drawString(font, cachedLeavesLine, x + 14, y + 50, COL_CYBER_CYAN, false);

        // Pasek faz: ANALIZA / ŻNIWA / SADZENIE z aktywnym podkreśleniem neonowym
        int sx = x + 10;
        int sy = y + 67;
        gfx.drawString(font, HUD_STATUS_PREFIX, sx, sy, COL_TEXT_PRIMARY, true);
        sx += font.width(HUD_STATUS_PREFIX);

        sx = drawStatusToken(gfx, font, HUD_TOK_ANALIZA, FarmStatus.ANALIZA, sx, sy);
        gfx.drawString(font, HUD_STATUS_SEP, sx, sy, COL_TEXT_MUTED, false);
        sx += font.width(HUD_STATUS_SEP);

        sx = drawStatusToken(gfx, font, HUD_TOK_ZNIWA, FarmStatus.ZNIWA, sx, sy);
        gfx.drawString(font, HUD_STATUS_SEP, sx, sy, COL_TEXT_MUTED, false);
        sx += font.width(HUD_STATUS_SEP);

        drawStatusToken(gfx, font, HUD_TOK_SADZENIE, FarmStatus.SADZENIE, sx, sy);

        // Stopka diagnostyczna (FPS + aktywny mostek AVX2/JVM)
        gfx.fill(x + 2, y + panelH - 15, x + panelW - 2, y + panelH - 14, COL_BORDER_INNER);
        gfx.fill(x + 2, y + panelH - 14, x + panelW - 2, y + panelH - 2, COL_FOOTER_BG);
        gfx.drawString(font, cachedFooterLine, x + 9, y + panelH - 11, COL_TEXT_MUTED, false);
    }

    private static void drawHorizontalGradientBar(
            GuiGraphics gfx, int x, int y, int width, int height, float ratio, float timeSec
    ) {
        // Szyna tła + górna krawędź cienia
        gfx.fill(x, y, x + width, y + height, COL_BAR_TRACK);
        gfx.fill(x, y, x + width, y + 1, 0x66000000);

        int fillW = Math.round(width * Mth.clamp(ratio, 0.0f, 1.0f));
        if (fillW <= 0) {
            return;
        }

        // Gładki, poziomy gradient (Cyber Red -> Cyber Orange -> Cyber Gold) w jednej partii GuiGraphics
        float invMax = 1.0f / Math.max(1, width - 1);
        for (int i = 0; i < fillW; i++) {
            float t = i * invMax;
            int col = (t < 0.5f)
                    ? lerpRgb(COL_CYBER_RED, COL_CYBER_ORANGE, t * 2.0f, 255)
                    : lerpRgb(COL_CYBER_ORANGE, COL_GOLD, (t - 0.5f) * 2.0f, 255);
            gfx.fill(x + i, y, x + i + 1, y + height, col);
        }

        // Górny refleks świetlny (specular sheen)
        gfx.fill(x, y, x + fillW, y + 1, 0x44FFFFFF);

        // Pulsujący wierzchołek energii na końcu paska
        int tipAlpha = (int) (185 + 70 * (0.5f + 0.5f * Mth.sin(timeSec * 8.0f)));
        int tipStart = Math.max(x, x + fillW - 2);
        gfx.fill(tipStart, y, x + fillW, y + height, withAlpha(COL_HOLO_WHITE, tipAlpha));
    }

    private static int drawStatusToken(
            GuiGraphics gfx, Font font, String label, FarmStatus tokenStatus, int x, int y
    ) {
        boolean isCurrent = (currentStatus == tokenStatus);
        int col = isCurrent ? tokenStatus.color : COL_TEXT_MUTED;
        int w = font.width(label);
        gfx.drawString(font, label, x, y, col, isCurrent);
        if (isCurrent) {
            gfx.fill(x, y + 9, x + w, y + 10, withAlpha(col, 210));
        }
        return x + w;
    }

    private static void renderTacticalCrosshair(GuiGraphics gfx, int screenW, int screenH, float timeSec) {
        final int cx = screenW / 2;
        final int cy = screenH / 2;
        final int spread = Math.round(crosshairSpread);

        final int strainCol = evaluateStrainColor(smoothedBreakStrain, 255);
        final int bracketCol = lerpRgb(
                COL_CYBER_CYAN, strainCol, crosshairActiveBlend,
                (int) (205 + 50 * crosshairActiveBlend)
        );
        final int tickCol = lerpRgb(
                COL_CYBER_CYAN, strainCol, crosshairActiveBlend * 0.7f,
                (int) (95 + 55 * crosshairActiveBlend)
        );

        // Zewnętrzne linie celownicze (skalują się ze spreadem sprężyny)
        gfx.fill(cx - spread - 13, cy, cx - spread - 4, cy + 1, tickCol);
        gfx.fill(cx + spread + 5, cy, cx + spread + 14, cy + 1, tickCol);
        gfx.fill(cx, cy - spread - 10, cx + 1, cy - spread - 3, tickCol);
        gfx.fill(cx, cy + spread + 4, cx + 1, cy + spread + 11, tickCol);

        // Nawiasy taktyczne [  ] z subtelnym cieniem kontrastowym
        final int arm = 5;
        final int halfH = 9;
        // Lewy nawias [
        gfx.fill(cx - spread - 1, cy - halfH, cx - spread, cy + halfH + 1, COL_SHADOW_SOFT);
        gfx.fill(cx - spread, cy - halfH, cx - spread + 1, cy + halfH + 1, bracketCol);
        gfx.fill(cx - spread, cy - halfH, cx - spread + arm, cy - halfH + 1, bracketCol);
        gfx.fill(cx - spread, cy + halfH, cx - spread + arm, cy + halfH + 1, bracketCol);

        // Prawy nawias ]
        gfx.fill(cx + spread + 1, cy - halfH, cx + spread + 2, cy + halfH + 1, COL_SHADOW_SOFT);
        gfx.fill(cx + spread, cy - halfH, cx + spread + 1, cy + halfH + 1, bracketCol);
        gfx.fill(cx + spread - arm + 1, cy - halfH, cx + spread + 1, cy - halfH + 1, bracketCol);
        gfx.fill(cx + spread - arm + 1, cy + halfH, cx + spread + 1, cy + halfH + 1, bracketCol);

        // Pulsujący celownik środkowy [ + ]
        float corePulse = 0.5f + 0.5f * Mth.sin(timeSec * (activelyChopping ? 14.0f : 4.2f));
        int coreArm = (activelyChopping && corePulse > 0.65f) ? 4 : 3;
        int coreCol = lerpRgb(
                COL_HOLO_WHITE,
                lerpRgb(COL_GOLD, COL_CYBER_RED, smoothedBreakStrain, 255),
                crosshairActiveBlend,
                (int) (200 + 55 * corePulse)
        );
        gfx.fill(cx - coreArm, cy, cx + coreArm + 1, cy + 1, coreCol);
        gfx.fill(cx, cy - coreArm, cx + 1, cy + coreArm + 1, coreCol);
        gfx.fill(cx, cy, cx + 1, cy + 1, COL_HOLO_WHITE);

        // Mikro-wskaźnik postępu Kinetic Break Strain pod celownikiem podczas ścinki
        if (smoothedBreakStrain > 0.02f) {
            final int microW = 18;
            final int mx = cx - (microW / 2);
            final int my = cy + halfH + 4;
            gfx.fill(mx, my, mx + microW, my + 2, 0x88090C12);
            int filled = Math.max(1, Math.round(microW * smoothedBreakStrain));
            gfx.fill(mx, my, mx + filled, my + 2, strainCol);
        }
    }

    private static void refreshHudCache(int currentFps) {
        if (choppedLogsCount != cachedLogs || targetLogsGoal != cachedGoal) {
            cachedLogs = choppedLogsCount;
            cachedGoal = targetLogsGoal;
            cachedLogsLine = "- DREWNO (SPRUCE): " + choppedLogsCount + " / " + targetLogsGoal;
        }
        if (choppedLeavesCount != cachedLeaves) {
            cachedLeaves = choppedLeavesCount;
            cachedLeavesLine = "- LIŚCIE (LEAVES): " + choppedLeavesCount;
        }
        if (currentFps != cachedFps) {
            cachedFps = currentFps;
            cachedFooterLine = "BRIDGE: " + BRIDGE_LABEL + " | FPS: " + currentFps + " | HOLO v3.2";
        }
    }

    // =========================================================================
    // 3. KINETIC BREAK STRAIN (MULTI-SHELL NA AKTYWNIE NISZCZONYM BLOKU)
    // =========================================================================
    private static int appendKineticBreakStrainQuads(
            ByteBuffer buf, int idx, int maxVerts,
            double camX, double camY, double camZ, float timeSec
    ) {
        float strain = smoothedBreakStrain;
        float mx0 = (float) (miningBlockX - camX);
        float my0 = (float) (miningBlockY - camY);
        float mz0 = (float) (miningBlockZ - camZ);

        float pulseFreq = 8.0f + 18.0f * strain;
        float pulse = 0.5f + 0.5f * Mth.sin(timeSec * pulseFreq);

        // Subtelna, addytywna aura bloku (nigdy nie zasłania tekstury pęknięć)
        int auraAlpha = (int) (8 + 24 * strain + 6 * pulse);
        int auraCol = evaluateStrainColor(strain, auraAlpha);
        float pad = 0.008f + 0.006f * pulse;
        idx = emitBoxQuadsBuf(
                buf, idx, maxVerts,
                mx0 - pad, my0 - pad, mz0 - pad,
                mx0 + 1.0f + pad, my0 + 1.0f + pad, mz0 + 1.0f + pad,
                auraCol, auraCol
        );

        // Równikowy pas naprężenia kinetycznego (rośnie wraz z postępem niszczenia)
        float bandHalf = 0.04f + 0.14f * strain;
        int bandEdgeCol = evaluateStrainColor(strain, 0);
        int bandCoreCol = evaluateStrainColor(strain, (int) (18 + 26 * strain));
        float midY = my0 + 0.5f;
        float rPad = 0.014f;
        idx = emitPerimeterRibbonQuads(
                buf, idx, maxVerts,
                mx0 - rPad, midY - bandHalf, mz0 - rPad,
                mx0 + 1.0f + rPad, midY, mz0 + 1.0f + rPad,
                bandEdgeCol, bandCoreCol
        );
        idx = emitPerimeterRibbonQuads(
                buf, idx, maxVerts,
                mx0 - rPad, midY, mz0 - rPad,
                mx0 + 1.0f + rPad, midY + bandHalf, mz0 + 1.0f + rPad,
                bandCoreCol, bandEdgeCol
        );
        return idx;
    }

    private static int appendKineticBreakStrainLines(
            ByteBuffer buf, int idx, int maxVerts,
            double camX, double camY, double camZ, float timeSec
    ) {
        float strain = smoothedBreakStrain;
        float mx0 = (float) (miningBlockX - camX);
        float my0 = (float) (miningBlockY - camY);
        float mz0 = (float) (miningBlockZ - camZ);

        float pulseFreq = 8.0f + 18.0f * strain;
        float pulse = 0.5f + 0.5f * Mth.sin(timeSec * pulseFreq);
        float jitter = 0.007f * strain * Mth.sin(timeSec * 42.0f);

        // 1) Zewnętrzne Cyber-Brackets namierzania (Cyber Cyan -> Gold -> Cyber Red)
        float outerPad = 0.012f + 0.018f * pulse * (1.0f - strain * 0.35f) + jitter;
        int outerCol = evaluateStrainColor(strain, (int) (210 + 45 * pulse));
        idx = emitCyberBrackets(
                buf, idx, maxVerts,
                mx0 - outerPad, my0 - outerPad, mz0 - outerPad,
                mx0 + 1.0f + outerPad, my0 + 1.0f + outerPad, mz0 + 1.0f + outerPad,
                outerCol
        );

        // 2) Wewnętrzna kurcząca się klatka naprężeń (Kinetic Compression Cage)
        float inset = 0.03f + 0.16f * strain;
        int innerCol = lerpRgb(
                evaluateStrainColor(strain, 255), COL_HOLO_WHITE,
                0.35f + 0.35f * pulse,
                (int) (140 + 115 * strain)
        );
        idx = emitCyberBrackets(
                buf, idx, maxVerts,
                mx0 + inset, my0 + inset, mz0 + inset,
                mx0 + 1.0f - inset, my0 + 1.0f - inset, mz0 + 1.0f - inset,
                innerCol
        );

        // 3) Laserowy pierścień równikowy sygnalizujący linię pęknięcia
        float ringY = my0 + 0.5f + 0.08f * Mth.sin(timeSec * 10.0f) * (1.0f - strain);
        int ringCol = lerpRgb(evaluateStrainColor(strain, 255), COL_HOLO_WHITE, 0.25f, 235);
        idx = emitPerimeterRingLines(
                buf, idx, maxVerts,
                mx0 - outerPad - 0.008f, ringY, mz0 - outerPad - 0.008f,
                mx0 + 1.0f + outerPad + 0.008f, mz0 + 1.0f + outerPad + 0.008f,
                ringCol
        );
        return idx;
    }

    // =========================================================================
    // 4. FALLBACK OFF-HEAP (ZERO-GC, MULTI-SHELL BLOOM + HARMONIC CROWN HALO)
    // =========================================================================
    private static void buildFallbackGeometry(double camX, double camY, double camZ, float t) {
        int sIdx = 0;
        int bQIdx = 0;
        int bLIdx = 0;

        float harmonic = 0.5f + 0.5f * Mth.sin(t * 2.6f);
        float harmonicQuad = 0.5f + 0.5f * Mth.sin(t * 2.6f + 1.5707963f);

        // A. Pień drzewa 2x2: krawędzie laserowe, cyber-brackets [ ] oraz dwukierunkowy UV sweep ring
        if (hasTrunk) {
            float x0 = (float) (trunkMinX - camX);
            float z0 = (float) (trunkMinZ - camZ);
            float y0 = (float) ((baseSoilY + 1) - camY);
            float y1 = (float) ((topLogY + 1) - camY);
            float x1 = x0 + 2.0f;
            float z1 = z0 + 2.0f;
            float trunkHeight = Math.max(1.0f, y1 - y0);

            // Whisper-thin holograficzna mgiełka pnia (alpha 9..15 -> 100% widoczności otoczenia)
            int wallBotCol = withAlpha(COL_CYBER_CYAN, (int) (9 + 4 * harmonic));
            int wallTopCol = withAlpha(COL_GOLD, (int) (11 + 5 * harmonic));
            sIdx = emitBoxQuadsBuf(SOLID_QUAD_BUF, sIdx, MAX_SOLID_QUAD_VERTS, x0, y0, z0, x1, y1, z1, wallBotCol, wallTopCol);

            // Multi-Shell pionowe filary laserowe (rdzeń + zewnętrzna powłoka przestrzenna)
            int pillarCoreCol = withAlpha(COL_CYBER_CYAN, 245);
            int pillarHaloCol = withAlpha(COL_HOLO_WHITE, (int) (85 + 35 * harmonic));
            bLIdx = emitVerticalLaserPillars(BLOOM_LINE_BUF, bLIdx, NATIVE_MAX_BLOOM_LINE_VERTS, x0, y0, z0, x1, y1, z1, pillarCoreCol);
            bLIdx = emitVerticalLaserPillars(
                    BLOOM_LINE_BUF, bLIdx, NATIVE_MAX_BLOOM_LINE_VERTS,
                    x0 - 0.016f, y0, z0 - 0.016f, x1 + 0.016f, y1, z1 + 0.016f, pillarHaloCol
            );

            // Cyber-Brackets [ ] na wszystkich 8 narożnikach pnia 2x2 (rdzeń + zewnętrzna powłoka)
            int bracketCoreCol = withAlpha(COL_HOLO_WHITE, 250);
            int bracketOuterCol = withAlpha(COL_CYBER_CYAN, (int) (125 + 45 * harmonic));
            bLIdx = emitCyberBrackets(
                    BLOOM_LINE_BUF, bLIdx, NATIVE_MAX_BLOOM_LINE_VERTS,
                    x0 - 0.010f, y0, z0 - 0.010f, x1 + 0.010f, y1, z1 + 0.010f, bracketCoreCol
            );
            bLIdx = emitCyberBrackets(
                    BLOOM_LINE_BUF, bLIdx, NATIVE_MAX_BLOOM_LINE_VERTS,
                    x0 - 0.028f, y0 - 0.012f, z0 - 0.028f, x1 + 0.028f, y1 + 0.012f, z1 + 0.028f, bracketOuterCol
            );

            // Subtelny obrys podstawy 2x2 na ziemi (ułatwia ocenę strefy sadzonek w fazie SADZENIE)
            int baseCol = withAlpha(
                    currentStatus == FarmStatus.SADZENIE ? COL_EMERALD : COL_CYBER_CYAN,
                    (int) (145 + 55 * harmonic)
            );
            bLIdx = emitPerimeterRingLines(
                    BLOOM_LINE_BUF, bLIdx, NATIVE_MAX_BLOOM_LINE_VERTS,
                    x0 - 0.02f, y0 + 0.02f, z0 - 0.02f, x1 + 0.02f, z1 + 0.02f, baseCol
            );

            // Dwukierunkowy Sweep-Laser UV Ring (płynnie skanuje pień w górę i w dół)
            float sweepAngle = t * 1.45f;
            float scanNorm = 0.5f - 0.5f * Mth.cos(sweepAngle);
            float scanDir = (Mth.sin(sweepAngle) >= 0.0f) ? 1.0f : -1.0f;
            float scanY = y0 + scanNorm * trunkHeight;

            int ringPrimaryCol = lerpRgb(COL_GOLD, COL_CYBER_CYAN, 0.35f + 0.35f * harmonic, 245);
            bLIdx = emitPerimeterRingLines(
                    BLOOM_LINE_BUF, bLIdx, NATIVE_MAX_BLOOM_LINE_VERTS,
                    x0 - 0.036f, scanY, z0 - 0.036f, x1 + 0.036f, z1 + 0.036f, ringPrimaryCol
            );

            // Echo laserowe podążające za kierunkiem skanowania
            float echoY = Mth.clamp(scanY - scanDir * 0.26f, y0, y1);
            int ringEchoCol = withAlpha(COL_CYBER_CYAN, 130);
            bLIdx = emitPerimeterRingLines(
                    BLOOM_LINE_BUF, bLIdx, NATIVE_MAX_BLOOM_LINE_VERTS,
                    x0 - 0.026f, echoY, z0 - 0.026f, x1 + 0.026f, z1 + 0.026f, ringEchoCol
            );

            // Półprzezroczysta, addytywna wstęga UV (fade 0 -> core -> 0) w BLOOM_QUAD_BUF
            float bandLowY = Math.max(y0, scanY - 0.16f);
            float bandHighY = Math.min(y1, scanY + 0.16f);
            int uvZeroCol = withAlpha(COL_CYBER_CYAN, 0);
            int uvPeakCol = lerpRgb(COL_CYBER_CYAN, COL_GOLD, 0.45f, 34);
            bQIdx = emitPerimeterRibbonQuads(
                    BLOOM_QUAD_BUF, bQIdx, NATIVE_MAX_BLOOM_QUAD_VERTS,
                    x0 - 0.032f, bandLowY, z0 - 0.032f, x1 + 0.032f, scanY, z1 + 0.032f,
                    uvZeroCol, uvPeakCol
            );
            bQIdx = emitPerimeterRibbonQuads(
                    BLOOM_QUAD_BUF, bQIdx, NATIVE_MAX_BLOOM_QUAD_VERTS,
                    x0 - 0.032f, scanY, z0 - 0.032f, x1 + 0.032f, bandHighY, z1 + 0.032f,
                    uvPeakCol, uvZeroCol
            );
        }

        // B. Korona drzewa (Crown Halo) — płynna pulsacja sinusoidalna (smooth harmonic)
        if (hasLeaves) {
            float haloCenterX = hasTrunk ? (float) (trunkMinX + 1.0 - camX) : (float) (canopyTopCenterX - camX);
            float haloCenterZ = hasTrunk ? (float) (trunkMinZ + 1.0 - camZ) : (float) (canopyTopCenterZ - camZ);
            float haloBaseY = hasTrunk ? (float) ((topLogY + 1) - camY) : (float) (canopyTopCenterY - camY - 1.2);

            float breathe = 0.055f * harmonic;
            float halfExtent = 2.5f + breathe;
            float haloX0 = haloCenterX - halfExtent;
            float haloX1 = haloCenterX + halfExtent;
            float haloZ0 = haloCenterZ - halfExtent;
            float haloZ1 = haloCenterZ + halfExtent;
            float haloY0 = haloBaseY + 0.12f;
            float haloY1 = haloBaseY + 2.35f + 0.10f * harmonicQuad;

            // Delikatna, prawie przezroczysta poświata korony (alpha 7..13)
            int haloWallCol = withAlpha(COL_CYBER_CYAN, (int) (7 + 5 * harmonic));
            sIdx = emitBoxQuadsBuf(
                    SOLID_QUAD_BUF, sIdx, MAX_SOLID_QUAD_VERTS,
                    haloX0, haloY0, haloZ0, haloX1, haloY1, haloZ1, haloWallCol, haloWallCol
            );

            // Harmoniczne narożniki Crown Halo (Multi-Shell: wewnętrzne + zewnętrzne)
            int haloPrimaryCol = lerpRgb(COL_CYBER_CYAN, COL_HOLO_WHITE, 0.25f + 0.45f * harmonic, (int) (185 + 60 * harmonic));
            int haloShellCol = withAlpha(COL_CYBER_CYAN, (int) (80 + 45 * harmonicQuad));
            bLIdx = emitCyberBrackets(
                    BLOOM_LINE_BUF, bLIdx, NATIVE_MAX_BLOOM_LINE_VERTS,
                    haloX0, haloY0, haloZ0, haloX1, haloY1, haloZ1, haloPrimaryCol
            );
            bLIdx = emitCyberBrackets(
                    BLOOM_LINE_BUF, bLIdx, NATIVE_MAX_BLOOM_LINE_VERTS,
                    haloX0 - 0.04f, haloY0 - 0.02f, haloZ0 - 0.04f,
                    haloX1 + 0.04f, haloY1 + 0.02f, haloZ1 + 0.04f, haloShellCol
            );

            // Dwa przeciwbieżne pierścienie harmoniczne pulsujące w koronie
            float beaconY1 = haloY0 + 0.45f + 0.42f * harmonic;
            float beaconY2 = haloY1 - 0.45f - 0.32f * harmonicQuad;
            int beaconCol1 = withAlpha(COL_HOLO_WHITE, (int) (175 + 65 * harmonic));
            int beaconCol2 = withAlpha(COL_CYBER_CYAN, (int) (145 + 65 * harmonicQuad));

            bLIdx = emitPerimeterRingLines(
                    BLOOM_LINE_BUF, bLIdx, NATIVE_MAX_BLOOM_LINE_VERTS,
                    haloX0 - 0.04f, beaconY1, haloZ0 - 0.04f, haloX1 + 0.04f, haloZ1 + 0.04f, beaconCol1
            );
            bLIdx = emitPerimeterRingLines(
                    BLOOM_LINE_BUF, bLIdx, NATIVE_MAX_BLOOM_LINE_VERTS,
                    haloX0 + 0.08f, beaconY2, haloZ0 + 0.08f, haloX1 - 0.08f, haloZ1 - 0.08f, beaconCol2
            );

            // Addytywna wstęga świetlna Crown Halo (fade 0 -> glow -> 0)
            int crownEdgeZero = withAlpha(COL_CYBER_CYAN, 0);
            int crownRimGlow = withAlpha(COL_CYBER_CYAN, (int) (12 + 12 * harmonic));
            bQIdx = emitPerimeterRibbonQuads(
                    BLOOM_QUAD_BUF, bQIdx, NATIVE_MAX_BLOOM_QUAD_VERTS,
                    haloX0 - 0.03f, beaconY1 - 0.14f, haloZ0 - 0.03f,
                    haloX1 + 0.03f, beaconY1, haloZ1 + 0.03f,
                    crownEdgeZero, crownRimGlow
            );
            bQIdx = emitPerimeterRibbonQuads(
                    BLOOM_QUAD_BUF, bQIdx, NATIVE_MAX_BLOOM_QUAD_VERTS,
                    haloX0 - 0.03f, beaconY1, haloZ0 - 0.03f,
                    haloX1 + 0.03f, beaconY1 + 0.14f, haloZ1 + 0.03f,
                    crownRimGlow, crownEdgeZero
            );
        }

        OUT_COUNTS_BUF.clear();
        OUT_COUNTS_BUF.putInt(sIdx);
        OUT_COUNTS_BUF.putInt(bQIdx);
        OUT_COUNTS_BUF.putInt(bLIdx);
        OUT_COUNTS_BUF.flip();
    }

    // =========================================================================
    // 5. ZERO-GC PRYMITYWY GEOMETRYCZNE DIRECT BYTEBUFFER
    // =========================================================================
    private static int putVert(ByteBuffer b, int idx, int maxVerts, float x, float y, float z, int col) {
        if (idx >= maxVerts) {
            return idx;
        }
        int o = idx * VERTEX_STRIDE;
        b.putFloat(o, x);
        b.putFloat(o + 4, y);
        b.putFloat(o + 8, z);
        b.putInt(o + 12, col);
        return idx + 1;
    }

    private static int putLine(
            ByteBuffer b, int idx, int maxVerts,
            float x0, float y0, float z0, float x1, float y1, float z1, int col
    ) {
        if (idx + 2 > maxVerts) {
            return idx;
        }
        idx = putVert(b, idx, maxVerts, x0, y0, z0, col);
        idx = putVert(b, idx, maxVerts, x1, y1, z1, col);
        return idx;
    }

    private static int emitCornerBracket(
            ByteBuffer b, int idx, int maxVerts,
            float cx, float cy, float cz,
            float armX, float armY, float armZ, int col
    ) {
        idx = putLine(b, idx, maxVerts, cx, cy, cz, cx + armX, cy, cz, col);
        idx = putLine(b, idx, maxVerts, cx, cy, cz, cx, cy + armY, cz, col);
        idx = putLine(b, idx, maxVerts, cx, cy, cz, cx, cy, cz + armZ, col);
        return idx;
    }

    private static int emitCyberBrackets(
            ByteBuffer b, int idx, int maxVerts,
            float x0, float y0, float z0, float x1, float y1, float z1, int col
    ) {
        float dx = Math.min(0.42f, (x1 - x0) * 0.24f);
        float dy = Math.min(0.42f, (y1 - y0) * 0.26f);
        float dz = Math.min(0.42f, (z1 - z0) * 0.24f);

        // Dolne 4 narożniki (ZERO alokacji tablic)
        idx = emitCornerBracket(b, idx, maxVerts, x0, y0, z0,  dx,  dy,  dz, col);
        idx = emitCornerBracket(b, idx, maxVerts, x1, y0, z0, -dx,  dy,  dz, col);
        idx = emitCornerBracket(b, idx, maxVerts, x1, y0, z1, -dx,  dy, -dz, col);
        idx = emitCornerBracket(b, idx, maxVerts, x0, y0, z1,  dx,  dy, -dz, col);

        // Górne 4 narożniki
        idx = emitCornerBracket(b, idx, maxVerts, x0, y1, z0,  dx, -dy,  dz, col);
        idx = emitCornerBracket(b, idx, maxVerts, x1, y1, z0, -dx, -dy,  dz, col);
        idx = emitCornerBracket(b, idx, maxVerts, x1, y1, z1, -dx, -dy, -dz, col);
        idx = emitCornerBracket(b, idx, maxVerts, x0, y1, z1,  dx, -dy, -dz, col);
        return idx;
    }

    private static int emitVerticalLaserPillars(
            ByteBuffer b, int idx, int maxVerts,
            float x0, float y0, float z0, float x1, float y1, float z1, int col
    ) {
        idx = putLine(b, idx, maxVerts, x0, y0, z0, x0, y1, z0, col);
        idx = putLine(b, idx, maxVerts, x1, y0, z0, x1, y1, z0, col);
        idx = putLine(b, idx, maxVerts, x1, y0, z1, x1, y1, z1, col);
        idx = putLine(b, idx, maxVerts, x0, y0, z1, x0, y1, z1, col);
        return idx;
    }

    private static int emitPerimeterRingLines(
            ByteBuffer b, int idx, int maxVerts,
            float x0, float y, float z0, float x1, float z1, int col
    ) {
        idx = putLine(b, idx, maxVerts, x0, y, z0, x1, y, z0, col);
        idx = putLine(b, idx, maxVerts, x1, y, z0, x1, y, z1, col);
        idx = putLine(b, idx, maxVerts, x1, y, z1, x0, y, z1, col);
        idx = putLine(b, idx, maxVerts, x0, y, z1, x0, y, z0, col);
        return idx;
    }

    private static int emitPerimeterRibbonQuads(
            ByteBuffer b, int idx, int maxVerts,
            float x0, float y0, float z0, float x1, float y1, float z1, int cBot, int cTop
    ) {
        if (idx + 16 > maxVerts || y1 <= y0) {
            return idx;
        }
        // Północ (z0)
        idx = putVert(b, idx, maxVerts, x0, y0, z0, cBot);
        idx = putVert(b, idx, maxVerts, x0, y1, z0, cTop);
        idx = putVert(b, idx, maxVerts, x1, y1, z0, cTop);
        idx = putVert(b, idx, maxVerts, x1, y0, z0, cBot);
        // Południe (z1)
        idx = putVert(b, idx, maxVerts, x1, y0, z1, cBot);
        idx = putVert(b, idx, maxVerts, x1, y1, z1, cTop);
        idx = putVert(b, idx, maxVerts, x0, y1, z1, cTop);
        idx = putVert(b, idx, maxVerts, x0, y0, z1, cBot);
        // Zachód (x0)
        idx = putVert(b, idx, maxVerts, x0, y0, z1, cBot);
        idx = putVert(b, idx, maxVerts, x0, y1, z1, cTop);
        idx = putVert(b, idx, maxVerts, x0, y1, z0, cTop);
        idx = putVert(b, idx, maxVerts, x0, y0, z0, cBot);
        // Wschód (x1)
        idx = putVert(b, idx, maxVerts, x1, y0, z0, cBot);
        idx = putVert(b, idx, maxVerts, x1, y1, z0, cTop);
        idx = putVert(b, idx, maxVerts, x1, y1, z1, cTop);
        idx = putVert(b, idx, maxVerts, x1, y0, z1, cBot);
        return idx;
    }

    private static int emitBoxQuadsBuf(
            ByteBuffer b, int idx, int maxVerts,
            float x0, float y0, float z0, float x1, float y1, float z1, int cB, int cT
    ) {
        if (idx + 24 > maxVerts) {
            return idx;
        }
        // 4 ściany boczne
        idx = emitPerimeterRibbonQuads(b, idx, maxVerts, x0, y0, z0, x1, y1, z1, cB, cT);
        // Góra
        idx = putVert(b, idx, maxVerts, x0, y1, z0, cT);
        idx = putVert(b, idx, maxVerts, x0, y1, z1, cT);
        idx = putVert(b, idx, maxVerts, x1, y1, z1, cT);
        idx = putVert(b, idx, maxVerts, x1, y1, z0, cT);
        // Dół
        idx = putVert(b, idx, maxVerts, x0, y0, z0, cB);
        idx = putVert(b, idx, maxVerts, x1, y0, z0, cB);
        idx = putVert(b, idx, maxVerts, x1, y0, z1, cB);
        idx = putVert(b, idx, maxVerts, x0, y0, z1, cB);
        return idx;
    }

    // =========================================================================
    // 6. NARZĘDZIA MATEMATYCZNE I KOLORYSTYCZNE (ZERO-GC)
    // =========================================================================
    private static int evaluateStrainColor(float strain, int alpha) {
        float s = Mth.clamp(strain, 0.0f, 1.0f);
        if (s < 0.5f) {
            return lerpRgb(COL_CYBER_CYAN, COL_GOLD, s * 2.0f, alpha);
        }
        return lerpRgb(COL_GOLD, COL_CYBER_RED, (s - 0.5f) * 2.0f, alpha);
    }

    private static float computeClampedDt(long nowNanos, long prevNanos) {
        if (prevNanos == 0L) {
            return 1.0f / 60.0f;
        }
        float raw = (nowNanos - prevNanos) * 1.0e-9f;
        return Mth.clamp(raw, 0.0005f, 0.05f);
    }

    private static int clampQuadVerts(int count, int maxVerts) {
        if (count <= 0) {
            return 0;
        }
        return Math.min(count, maxVerts) & ~3;
    }

    private static int clampLineVerts(int count, int maxVerts) {
        if (count <= 0) {
            return 0;
        }
        return Math.min(count, maxVerts) & ~1;
    }

    private static int withAlpha(int rgb, int alpha) {
        return (Mth.clamp(alpha, 0, 255) << 24) | (rgb & 0x00FFFFFF);
    }

    private static int lerpRgb(int c1, int c2, float t, int alpha) {
        t = Mth.clamp(t, 0.0f, 1.0f);
        int r1 = (c1 >> 16) & 0xFF;
        int g1 = (c1 >> 8) & 0xFF;
        int b1 = c1 & 0xFF;
        int r2 = (c2 >> 16) & 0xFF;
        int g2 = (c2 >> 8) & 0xFF;
        int b2 = c2 & 0xFF;
        int r = (int) (r1 + (r2 - r1) * t);
        int g = (int) (g1 + (g2 - g1) * t);
        int b = (int) (b1 + (b2 - b1) * t);
        return (Mth.clamp(alpha, 0, 255) << 24) | (r << 16) | (g << 8) | b;
    }
}
