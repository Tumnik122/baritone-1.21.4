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
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * ASTRA CLIENT 1.21.4 - Cyber Holographic Tree ESP & Tactical HUD.
 *
 * Architektura:
 * - Zero-GC w pętli renderowania (Direct ByteBuffer + cache FormattedCharSequence / String).
 * - Komunikacja C++20 AVX2 przez JNI Direct ByteBuffer (bez kopiowania pamięci).
 * - Izolacja stanu OpenGL przez AstraGlStateGuard(false, false, true) dla Iris/Complementary/BSL.
 * - Double Camera Translation Fix: zerowanie translacji (m30, m31, m32) macierzy widoku.
 */
public final class SpruceTreeVisuals {

    // =========================================================================
    // PALETA KOLORÓW CYBER HOLOGRAPHIC ESP (0xAARRGGBB)
    // =========================================================================
    private static final int COL_CYBER_CYAN    = 0xFF00F0FF; // #00F0FF
    private static final int COL_HOLO_WHITE    = 0xFFE6FCFF; // Biało-błękitny hologram
    private static final int COL_CYBER_RED     = 0xFFFF3300; // #FF3300
    private static final int COL_CYBER_ORANGE  = 0xFFFF5500; // #FF5500
    private static final int COL_GOLD          = 0xFFFFD700; // #FFD700
    private static final int COL_EMERALD       = 0xFF00FF88; // #00FF88
    private static final int COL_OBSIDIAN_BG   = 0xDC090C12; // Obsidian Glass
    private static final int COL_PANEL_INNER   = 0xB8111620;
    private static final int COL_BORDER_DARK   = 0xFF2B3240;
    private static final int COL_TEXT_PRIMARY  = 0xFFF0F4FA;
    private static final int COL_TEXT_MUTED    = 0xFF8D99AE;

    // Pre-alokowane sekwencje tekstowe 3D (Zero-GC dla font.drawInBatch8xOutline)
    private static final FormattedCharSequence LABEL_LEAVES_3D =
            Component.literal("LIŚCIE / LEAVES").getVisualOrderText();
    private static final FormattedCharSequence LABEL_LOGS_3D =
            Component.literal("DREWNO / LOGS (2x2)").getVisualOrderText();

    // =========================================================================
    // UKŁAD PAMIĘCI DIRECT BYTEBUFFER (JNI <-> C++20 AVX2)
    // =========================================================================
    private static final int VERTEX_STRIDE = 16; // float x, y, z (12B) + uint32_t color (4B)
    public static final int MAX_CANOPY_TIERS = 16;

    private static final int MAX_SOLID_QUAD_VERTS = 1024;
    private static final int MAX_BLOOM_QUAD_VERTS = 1536;
    private static final int MAX_BLOOM_LINE_VERTS = 3072;

    // Offsety w strukturze AstraCyberEspInput (#pragma pack(1) = 440 bajtów)
    private static final int OFF_CAM_X        = 0;
    private static final int OFF_CAM_Y        = 8;
    private static final int OFF_CAM_Z        = 16;
    private static final int OFF_HAS_TRUNK    = 24;
    private static final int OFF_TRUNK_MIN_X  = 28;
    private static final int OFF_TRUNK_MIN_Z  = 32;
    private static final int OFF_BASE_SOIL_Y  = 36;
    private static final int OFF_TOP_LOG_Y    = 40;
    private static final int OFF_HAS_LEAVES   = 44;
    private static final int OFF_TIER_COUNT   = 48;
    private static final int OFF_TIERS_START  = 52; // 16 * 24B = 384B
    private static final int OFF_TIME_SECONDS = 436;
    private static final int INPUT_BYTES      = 440;

    private static final ByteBuffer INPUT_BUF =
            ByteBuffer.allocateDirect(INPUT_BYTES).order(ByteOrder.nativeOrder());
    private static final ByteBuffer SOLID_QUAD_BUF =
            ByteBuffer.allocateDirect(MAX_SOLID_QUAD_VERTS * VERTEX_STRIDE).order(ByteOrder.nativeOrder());
    private static final ByteBuffer BLOOM_QUAD_BUF =
            ByteBuffer.allocateDirect(MAX_BLOOM_QUAD_VERTS * VERTEX_STRIDE).order(ByteOrder.nativeOrder());
    private static final ByteBuffer BLOOM_LINE_BUF =
            ByteBuffer.allocateDirect(MAX_BLOOM_LINE_VERTS * VERTEX_STRIDE).order(ByteOrder.nativeOrder());
    private static final ByteBuffer OUT_COUNTS_BUF =
            ByteBuffer.allocateDirect(12).order(ByteOrder.nativeOrder());

    private static final boolean NATIVE_DLL_LOADED;

    // Pre-alokowane macierze robocze (zakaz new Matrix4f() w pętli renderowania)
    private static final Matrix4f VIEW_ROTATION_MATRIX = new Matrix4f();
    private static final Matrix4f LABEL_MATRIX = new Matrix4f();
    private static final long START_NANOS = System.nanoTime();

    static {
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
            } catch (Throwable ignored) {}
        }
        try {
            Path tempDir = Path.of(System.getProperty("java.io.tmpdir", "."), "astra_native");
            Files.createDirectories(tempDir);
            Path targetDll = tempDir.resolve("astra-core.dll");
            try (InputStream is = SpruceTreeVisuals.class.getResourceAsStream("/assets/astra/native/astra-core.dll")) {
                if (is != null) {
                    try {
                        Files.copy(is, targetDll, StandardCopyOption.REPLACE_EXISTING);
                    } catch (Throwable ignored) {}
                    if (Files.isRegularFile(targetDll)) {
                        return targetDll.toAbsolutePath();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // Deklaracja natywna JNI
    private static native void nativeBuildCyberEsp(
            ByteBuffer inputBuf,
            ByteBuffer solidQuadBuf, int maxSolidQuadVerts,
            ByteBuffer bloomQuadBuf, int maxBloomQuadVerts,
            ByteBuffer bloomLineBuf, int maxBloomLineVerts,
            ByteBuffer outCountsBuf
    );

    // =========================================================================
    // STAN BOTA I ZERO-GC STRING CACHE DLA HUD 2D
    // =========================================================================
    public enum FarmStatus {
        ANALIZA("ANALIZA", COL_CYBER_CYAN),
        ZNIWA("ŻNIWA", COL_GOLD),
        SADZENIE("SADZENIE", COL_EMERALD);

        public final String displayName;
        public final int color;

        FarmStatus(String displayName, int color) {
            this.displayName = displayName;
            this.color = color;
        }
    }

    private static boolean active = false;
    private static FarmStatus currentStatus = FarmStatus.ANALIZA;
    private static boolean activelyChopping = false;

    private static boolean hasTrunk = false;
    private static int trunkMinX = 0;
    private static int trunkMinZ = 0;
    private static int baseSoilY = 0;
    private static int topLogY = 0;

    private static boolean hasLeaves = false;
    private static int canopyTierCount = 0;
    private static final int[] canopyTiersFlat = new int[MAX_CANOPY_TIERS * 6];
    private static double canopyTopCenterX = 0.0;
    private static double canopyTopCenterY = 0.0;
    private static double canopyTopCenterZ = 0.0;

    // Statystyki farmy
    private static int choppedLogsCount = 0;
    private static int targetLogsGoal = 150;
    private static int choppedLeavesCount = 0;

    // Cache tekstów HUD (aktualizowane tylko przy zmianie wartości liczbowych)
    private static int cachedLogs = -1;
    private static int cachedGoal = -1;
    private static String cachedLogsLine = "- DREWNO (SPRUCE): 0 / 150";

    private static int cachedLeaves = -1;
    private static String cachedLeavesLine = "- LIŚCIE (LEAVES): 0";

    private static int cachedFps = -1;
    private static String cachedFooterLine = "RENDERPASS: GLSL C++ / JAVA BRIDGE | FPS: 144 | SHADER OUTLINES v3.2";

    private SpruceTreeVisuals() {}

    // =========================================================================
    // API AKTUALIZACJI STANU Z SpruceTreeProcess
    // =========================================================================

    /**
     * Aktualizuje geometrię drzewa 2x2 i liczniki z poziomu SpruceTreeProcess#onTick.
     * Brak jakichkolwiek alokacji obiektów.
     */
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
        if (!active) return;

        currentStatus = (status != null) ? status : FarmStatus.ANALIZA;
        activelyChopping = isChoppingNow;

        hasTrunk = trunkFound;
        trunkMinX = tMinX;
        trunkMinZ = tMinZ;
        baseSoilY = soilY;
        topLogY = Math.max(soilY + 1, topY);

        hasLeaves = leavesFound;
        canopyTierCount = Mth.clamp(tierCount, 0, MAX_CANOPY_TIERS);
        if (canopyTierCount > 0 && tiersFlat != null) {
            System.arraycopy(tiersFlat, 0, canopyTiersFlat, 0, canopyTierCount * 6);
        }

        canopyTopCenterX = leavesLabelX;
        canopyTopCenterY = leavesLabelY;
        canopyTopCenterZ = leavesLabelZ;

        choppedLogsCount = logsChopped;
        targetLogsGoal = Math.max(1, logsGoal);
        choppedLeavesCount = leavesChopped;
    }

    // =========================================================================
    // 1. RENDEROWANIE W ŚWIECIE 3D (CYBER ESP + DOUBLE CAMERA TRANSLATION FIX)
    // =========================================================================

    public static void onRenderWorld(PoseStack poseStack, float tickDelta) {
        if (!active || (!hasTrunk && !hasLeaves)) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }

        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 camPos = camera.getPosition();
        final double camX = camPos.x;
        final double camY = camPos.y;
        final double camZ = camPos.z;

        final float timeSec = (System.nanoTime() - START_NANOS) * 1.0e-9f;

        // Zapis danych wejściowych do DirectByteBuffer (Zero-Copy dla C++20 AVX2)
        INPUT_BUF.putDouble(OFF_CAM_X, camX);
        INPUT_BUF.putDouble(OFF_CAM_Y, camY);
        INPUT_BUF.putDouble(OFF_CAM_Z, camZ);

        INPUT_BUF.putInt(OFF_HAS_TRUNK, hasTrunk ? 1 : 0);
        INPUT_BUF.putInt(OFF_TRUNK_MIN_X, trunkMinX);
        INPUT_BUF.putInt(OFF_TRUNK_MIN_Z, trunkMinZ);
        INPUT_BUF.putInt(OFF_BASE_SOIL_Y, baseSoilY);
        INPUT_BUF.putInt(OFF_TOP_LOG_Y, topLogY);

        INPUT_BUF.putInt(OFF_HAS_LEAVES, hasLeaves ? 1 : 0);
        INPUT_BUF.putInt(OFF_TIER_COUNT, canopyTierCount);
        for (int i = 0; i < canopyTierCount * 6; i++) {
            INPUT_BUF.putInt(OFF_TIERS_START + i * 4, canopyTiersFlat[i]);
        }
        INPUT_BUF.putFloat(OFF_TIME_SECONDS, timeSec);

        // Generowanie geometrii w C++20 AVX2 (lub fallback w pamięci DirectByteBuffer)
        if (NATIVE_DLL_LOADED) {
            try {
                nativeBuildCyberEsp(
                        INPUT_BUF,
                        SOLID_QUAD_BUF, MAX_SOLID_QUAD_VERTS,
                        BLOOM_QUAD_BUF, MAX_BLOOM_QUAD_VERTS,
                        BLOOM_LINE_BUF, MAX_BLOOM_LINE_VERTS,
                        OUT_COUNTS_BUF
                );
            } catch (Throwable t) {
                buildFallbackGeometry(camX, camY, camZ, timeSec);
            }
        } else {
            buildFallbackGeometry(camX, camY, camZ, timeSec);
        }

        final int solidQuadVerts = OUT_COUNTS_BUF.getInt(0);
        final int bloomQuadVerts = OUT_COUNTS_BUF.getInt(4);
        final int bloomLineVerts = OUT_COUNTS_BUF.getInt(8);

        // ---------------------------------------------------------------------
        // POPRAWKA PRZESUNIĘCIA KAMERY (DOUBLE CAMERA TRANSLATION FIX)
        // Geometria z bufora jest już w przestrzeni relatywnej do kamery (world - cam).
        // Zerujemy translację w macierzy widoku (m30, m31, m32 = 0), zostawiając
        // wyłącznie rotację kamery, aby obrysy nie uciekały w powietrze.
        // ---------------------------------------------------------------------
        VIEW_ROTATION_MATRIX.set(poseStack.last().pose());
        VIEW_ROTATION_MATRIX.m30(0.0f);
        VIEW_ROTATION_MATRIX.m31(0.0f);
        VIEW_ROTATION_MATRIX.m32(0.0f);

        try (AstraGlStateGuard guard = new AstraGlStateGuard(false, false, true)) {
            RenderSystem.setShader(CoreShaders.POSITION_COLOR);
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            RenderSystem.enableBlend();

            // Przebieg 1: Ściany półprzezroczyste Porter-Duff (Cyan #00F0FF / Red-Orange #FF3300)
            RenderSystem.blendFuncSeparate(
                    GlStateManager.SourceFactor.SRC_ALPHA,
                    GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                    GlStateManager.SourceFactor.ONE,
                    GlStateManager.DestFactor.ZERO
            );
            drawDirectBuffer(SOLID_QUAD_BUF, solidQuadVerts, VertexFormat.Mode.QUADS, VIEW_ROTATION_MATRIX);

            // Przebieg 2: Additive Bloom (wolumetryczne poświaty, fala skanująca UV, Cyber Brackets [ ])
            RenderSystem.blendFuncSeparate(
                    GlStateManager.SourceFactor.SRC_ALPHA,
                    GlStateManager.DestFactor.ONE,
                    GlStateManager.SourceFactor.ONE,
                    GlStateManager.DestFactor.ONE
            );
            drawDirectBuffer(BLOOM_QUAD_BUF, bloomQuadVerts, VertexFormat.Mode.QUADS, VIEW_ROTATION_MATRIX);

            RenderSystem.lineWidth(2.0f);
            drawDirectBuffer(BLOOM_LINE_BUF, bloomLineVerts, VertexFormat.Mode.DEBUG_LINES, VIEW_ROTATION_MATRIX);
            RenderSystem.lineWidth(1.0f);

            // Przebieg 3: Holograficzne etykiety 3D w świecie (font.drawInBatch8xOutline)
            renderWorldHolographicLabels(mc, camera, camX, camY, camZ, timeSec);
        }
    }

    /**
     * Alias backwards-compatible dla wywołań ze starego kodu.
     */
    public static void render3D(PoseStack poseStack, Vec3 camPos, Baritone baritone) {
        onRenderWorld(poseStack, 1.0f);
    }

    private static void drawDirectBuffer(
            ByteBuffer buf, int vertexCount, VertexFormat.Mode mode, Matrix4f matrix
    ) {
        if (vertexCount <= 0) return;

        BufferBuilder builder = Tesselator.getInstance().begin(mode, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < vertexCount; i++) {
            int offset = i * VERTEX_STRIDE;
            float x = buf.getFloat(offset);
            float y = buf.getFloat(offset + 4);
            float z = buf.getFloat(offset + 8);
            int argb = buf.getInt(offset + 12);

            builder.addVertex(matrix, x, y, z).setColor(argb);
        }

        MeshData mesh = builder.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
        }
    }

    /**
     * Renderuje unoszące się etykiety 3D w świecie:
     * - "LIŚCIE / LEAVES" wycentrowane nad koroną (biało-błękitny puls harmoniczny)
     * - "DREWNO / LOGS (2x2)" z boku kolumny pnia (neonowy pomarańcz)
     */
    private static void renderWorldHolographicLabels(
            Minecraft mc, Camera camera, double camX, double camY, double camZ, float timeSec
    ) {
        Font font = mc.font;
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        float harmonic = 0.5f + 0.5f * Mth.sin(timeSec * 3.14159265f);

        // 1. Etykieta nad koroną liści: LIŚCIE / LEAVES
        if (hasLeaves) {
            double lx = (canopyTierCount > 0) ? canopyTopCenterX : (trunkMinX + 1.0);
            double ly = (canopyTierCount > 0) ? (canopyTopCenterY + 0.65) : (topLogY + 3.45);
            double lz = (canopyTierCount > 0) ? canopyTopCenterZ : (trunkMinZ + 1.0);

            int textCol = lerpRgb(COL_CYBER_CYAN, COL_HOLO_WHITE, 0.45f + 0.55f * harmonic, 255);
            int outlineCol = 0xCC031B24;

            drawBillboardText3D(
                    font, bufferSource, camera,
                    LABEL_LEAVES_3D,
                    (float) (lx - camX), (float) (ly - camY), (float) (lz - camZ),
                    textCol, outlineCol, 0.026f
            );
        }

        // 2. Etykieta z boku pnia 2x2: DREWNO / LOGS (2x2)
        if (hasTrunk) {
            double centerX = trunkMinX + 1.0;
            double centerZ = trunkMinZ + 1.0;
            double dirX = camX - centerX;
            double dirZ = camZ - centerZ;
            double len = Math.max(0.001, Math.hypot(dirX, dirZ));

            double sideX = centerX + (dirX / len) * 1.45;
            double sideY = baseSoilY + 1.0 + Math.max(1.5, (topLogY - baseSoilY) * 0.35);
            double sideZ = centerZ + (dirZ / len) * 1.45;

            int textCol = lerpRgb(COL_CYBER_ORANGE, 0xFFFF9933, harmonic, 255);
            int outlineCol = 0xCC240800;

            drawBillboardText3D(
                    font, bufferSource, camera,
                    LABEL_LOGS_3D,
                    (float) (sideX - camX), (float) (sideY - camY), (float) (sideZ - camZ),
                    textCol, outlineCol, 0.024f
            );
        }

        bufferSource.endBatch();
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
    // 2. TACTICAL HUD I NAKŁADKA 2D (KARTA STATUSU + CELOWNIK TAKTYCZNY)
    // =========================================================================

    public static void render2D(GuiGraphics guiGraphics, DeltaTracker deltaTracker, Baritone baritone) {
        onRenderHud(guiGraphics, deltaTracker != null ? deltaTracker.getGameTimeDeltaPartialTick(true) : 1.0f);
    }

    public static void onRenderHud(GuiGraphics gfx, float tickDelta) {
        if (!active) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) return;
        Font font = mc.font;
        if (font == null) return;

        final int screenW = mc.getWindow().getGuiScaledWidth();
        final int screenH = mc.getWindow().getGuiScaledHeight();

        refreshHudCache(mc.getFps());

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        // A. Karta Statusu w Prawym Górnym Rogu ("STATYSTYKI FARMY")
        renderFarmStatsCard(gfx, font, screenW);

        // B. Celownik Taktyczny [ + ] na środku ekranu
        renderTacticalCrosshair(gfx, screenW, screenH);
    }

    private static void renderFarmStatsCard(GuiGraphics gfx, Font font, int screenW) {
        final int panelW = 292;
        final int panelH = 98;
        final int x = screenW - panelW - 14;
        final int y = 14;

        // Tło Obsidian Glass
        gfx.fill(x, y, x + panelW, y + panelH, COL_OBSIDIAN_BG);
        gfx.fill(x + 4, y + 20, x + panelW - 4, y + panelH - 16, COL_PANEL_INNER);

        // Ciemnoszare obramowanie zewnętrzne + złote narożniki i górna belka
        gfx.fill(x, y, x + panelW, y + 1, COL_GOLD);
        gfx.fill(x, y + panelH - 1, x + panelW, y + panelH, COL_BORDER_DARK);
        gfx.fill(x, y, x + 1, y + panelH, COL_BORDER_DARK);
        gfx.fill(x + panelW - 1, y, x + panelW, y + panelH, COL_BORDER_DARK);

        // Złote akcenty narożne na karcie HUD
        gfx.fill(x, y, x + 12, y + 2, COL_GOLD);
        gfx.fill(x + panelW - 12, y, x + panelW, y + 2, COL_GOLD);
        gfx.fill(x, y + panelH - 2, x + 12, y + panelH, COL_GOLD);
        gfx.fill(x + panelW - 12, y + panelH - 2, x + panelW, y + panelH, COL_GOLD);

        // Nagłówek: STATYSTYKI FARMY (złoty #FFD700)
        gfx.drawString(font, "STATYSTYKI FARMY", x + 10, y + 6, COL_GOLD, true);

        // Pasek postępu ścinki pnia (X / 150) w prawym górnym rogu nagłówka
        float goalRatio = Mth.clamp((float) choppedLogsCount / (float) targetLogsGoal, 0.0f, 1.0f);
        int miniBarW = 84;
        int miniBarX = x + panelW - miniBarW - 10;
        int miniBarY = y + 8;
        gfx.fill(miniBarX, miniBarY, miniBarX + miniBarW, miniBarY + 5, 0xFF1A1F2B);
        int fillW = (int) (miniBarW * goalRatio);
        if (fillW > 0) {
            gfx.fillGradient(miniBarX, miniBarY, miniBarX + fillW, miniBarY + 5, COL_CYBER_RED, COL_GOLD);
        }

        // Wiersze danych
        gfx.drawString(font, "ŚCIĘTO:", x + 10, y + 24, COL_TEXT_PRIMARY, true);
        gfx.drawString(font, cachedLogsLine, x + 14, y + 36, COL_CYBER_ORANGE, false);
        gfx.drawString(font, cachedLeavesLine, x + 14, y + 48, COL_CYBER_CYAN, false);

        // Wiersz 4: STATUS: ANALIZA / ŻNIWA / SADZENIE (podświetlony aktywny etap)
        int sx = x + 10;
        int sy = y + 63;
        gfx.drawString(font, "STATUS: ", sx, sy, COL_TEXT_PRIMARY, true);
        sx += font.width("STATUS: ");

        sx = drawStatusToken(gfx, font, "ANALIZA", FarmStatus.ANALIZA, sx, sy);
        gfx.drawString(font, " / ", sx, sy, COL_TEXT_MUTED, false);
        sx += font.width(" / ");
        sx = drawStatusToken(gfx, font, "ŻNIWA", FarmStatus.ZNIWA, sx, sy);
        gfx.drawString(font, " / ", sx, sy, COL_TEXT_MUTED, false);
        sx += font.width(" / ");
        drawStatusToken(gfx, font, "SADZENIE", FarmStatus.SADZENIE, sx, sy);

        // Pasek stopki
        gfx.fill(x + 1, y + panelH - 14, x + panelW - 1, y + panelH - 1, 0xFF06080C);
        gfx.drawString(font, cachedFooterLine, x + 8, y + panelH - 11, COL_TEXT_MUTED, false);
    }

    private static int drawStatusToken(
            GuiGraphics gfx, Font font, String label, FarmStatus tokenStatus, int x, int y
    ) {
        boolean isCurrent = (currentStatus == tokenStatus);
        int col = isCurrent ? tokenStatus.color : COL_TEXT_MUTED;
        gfx.drawString(font, label, x, y, col, isCurrent);
        return x + font.width(label);
    }

    /**
     * Celownik Taktyczny (Tactical Crosshair Brackets [ + ] + linie celownicze)
     */
    private static void renderTacticalCrosshair(GuiGraphics gfx, int screenW, int screenH) {
        final int cx = screenW / 2;
        final int cy = screenH / 2;

        float timeSec = (System.nanoTime() - START_NANOS) * 1.0e-9f;
        int spread = activelyChopping ? (13 + (int) (2.0f * Mth.sin(timeSec * 14.0f))) : 16;

        int bracketCol = activelyChopping ? COL_GOLD : withAlpha(COL_CYBER_CYAN, 210);
        int lineCol = withAlpha(COL_CYBER_CYAN, 95);

        // Zewnętrzne linie celownicze (poziome i pionowe)
        gfx.fill(cx - spread - 14, cy, cx - spread - 4, cy + 1, lineCol);
        gfx.fill(cx + spread + 5, cy, cx + spread + 15, cy + 1, lineCol);
        gfx.fill(cx, cy - spread - 11, cx + 1, cy - spread - 3, lineCol);
        gfx.fill(cx, cy + spread + 4, cx + 1, cy + spread + 12, lineCol);

        // Nawiasy narożne [  ] wokół środka ekranu
        final int arm = 5;
        final int halfH = 9;
        // Lewy nawias [
        gfx.fill(cx - spread, cy - halfH, cx - spread + 1, cy + halfH + 1, bracketCol);
        gfx.fill(cx - spread, cy - halfH, cx - spread + arm, cy - halfH + 1, bracketCol);
        gfx.fill(cx - spread, cy + halfH, cx - spread + arm, cy + halfH + 1, bracketCol);

        // Prawy nawias ]
        gfx.fill(cx + spread, cy - halfH, cx + spread + 1, cy + halfH + 1, bracketCol);
        gfx.fill(cx + spread - arm + 1, cy - halfH, cx + spread + 1, cy - halfH + 1, bracketCol);
        gfx.fill(cx + spread - arm + 1, cy + halfH, cx + spread + 1, cy + halfH + 1, bracketCol);

        // Centralny krzyżyk +
        int coreCol = activelyChopping ? COL_CYBER_RED : COL_HOLO_WHITE;
        gfx.fill(cx - 3, cy, cx + 4, cy + 1, coreCol);
        gfx.fill(cx, cy - 3, cx + 1, cy + 4, coreCol);
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
            cachedFooterLine = "RENDERPASS: GLSL C++ / JAVA BRIDGE | FPS: " + currentFps + " | SHADER OUTLINES v3.2";
        }
    }

    private static int withAlpha(int rgb, int alpha) {
        return (Mth.clamp(alpha, 0, 255) << 24) | (rgb & 0x00FFFFFF);
    }

    private static int lerpRgb(int c1, int c2, float t, int alpha) {
        t = Mth.clamp(t, 0.0f, 1.0f);
        int r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
        int r2 = (c2 >> 16) & 0xFF, g2 = (c2 >> 8) & 0xFF, b2 = c2 & 0xFF;
        int r = (int) (r1 + (r2 - r1) * t);
        int g = (int) (g1 + (g2 - g1) * t);
        int b = (int) (b1 + (b2 - b1) * t);
        return (Mth.clamp(alpha, 0, 255) << 24) | (r << 16) | (g << 8) | b;
    }

    // =========================================================================
    // FALLBACK OFF-HEAP (GDY ASTRA_CORE.DLL NIE ZOSTAŁA JESZCZE ZAŁADOWANA)
    // =========================================================================

    private static void buildFallbackGeometry(double camX, double camY, double camZ, float t) {
        int sIdx = 0, bQIdx = 0, bLIdx = 0;
        float harmonic = 0.5f + 0.5f * Mth.sin(t * 3.14159265f);

        if (hasTrunk) {
            float x0 = (float) (trunkMinX - camX);
            float z0 = (float) (trunkMinZ - camZ);
            float y0 = (float) ((baseSoilY + 1) - camY);
            float y1 = (float) ((topLogY + 1) - camY);

            sIdx = emitBoxQuadsBuf(SOLID_QUAD_BUF, sIdx, x0, y0, z0, x0 + 2f, y1, z0 + 2f,
                    withAlpha(COL_CYBER_RED, 88), withAlpha(COL_CYBER_ORANGE, 88));
            bLIdx = emitBoxLinesBuf(BLOOM_LINE_BUF, bLIdx, x0 - 0.008f, y0, z0 - 0.008f,
                    x0 + 2.008f, y1, z0 + 2.008f, withAlpha(COL_CYBER_RED, 245));

            float scanY = y0 + ((t * 0.55f) % 1.0f) * Math.max(1.0f, y1 - y0);
            bLIdx = emitBoxLinesBuf(BLOOM_LINE_BUF, bLIdx, x0 - 0.03f, scanY, z0 - 0.03f,
                    x0 + 2.03f, scanY + 0.04f, z0 + 2.03f, withAlpha(0xFFBB33, 240));
        }

        if (hasLeaves && hasTrunk) {
            float tMinX = (float) (trunkMinX - camX);
            float tMinZ = (float) (trunkMinZ - camZ);
            float topY = (float) ((topLogY + 1) - camY);
            float startY = topY - 12.0f;
            float[] radii = { 3f, 2f, 1f, 0f };
            for (int tier = 0; tier < 4; tier++) {
                float r = radii[tier];
                float ty0 = startY + tier * 3.5f;
                float ty1 = ty0 + 3.5f;
                sIdx = emitBoxQuadsBuf(SOLID_QUAD_BUF, sIdx, tMinX - r, ty0, tMinZ - r,
                        tMinX + 2f + r, ty1, tMinZ + 2f + r,
                        withAlpha(COL_CYBER_CYAN, 72), withAlpha(COL_CYBER_CYAN, 72));
                bLIdx = emitBoxLinesBuf(BLOOM_LINE_BUF, bLIdx, tMinX - r, ty0, tMinZ - r,
                        tMinX + 2f + r, ty1, tMinZ + 2f + r,
                        withAlpha(COL_CYBER_CYAN, (int) (200 + 55 * harmonic)));
            }
        }

        OUT_COUNTS_BUF.putInt(0, sIdx);
        OUT_COUNTS_BUF.putInt(4, bQIdx);
        OUT_COUNTS_BUF.putInt(8, bLIdx);
    }

    private static int putVert(ByteBuffer b, int idx, float x, float y, float z, int col) {
        int o = idx * VERTEX_STRIDE;
        b.putFloat(o, x);
        b.putFloat(o + 4, y);
        b.putFloat(o + 8, z);
        b.putInt(o + 12, col);
        return idx + 1;
    }

    private static int emitBoxQuadsBuf(
            ByteBuffer b, int idx, float x0, float y0, float z0, float x1, float y1, float z1, int cB, int cT
    ) {
        idx = putVert(b, idx, x0, y0, z0, cB); idx = putVert(b, idx, x0, y1, z0, cT);
        idx = putVert(b, idx, x1, y1, z0, cT); idx = putVert(b, idx, x1, y0, z0, cB);

        idx = putVert(b, idx, x1, y0, z1, cB); idx = putVert(b, idx, x1, y1, z1, cT);
        idx = putVert(b, idx, x0, y1, z1, cT); idx = putVert(b, idx, x0, y0, z1, cB);

        idx = putVert(b, idx, x0, y0, z1, cB); idx = putVert(b, idx, x0, y1, z1, cT);
        idx = putVert(b, idx, x0, y1, z0, cT); idx = putVert(b, idx, x0, y0, z0, cB);

        idx = putVert(b, idx, x1, y0, z0, cB); idx = putVert(b, idx, x1, y1, z0, cT);
        idx = putVert(b, idx, x1, y1, z1, cT); idx = putVert(b, idx, x1, y0, z1, cB);

        idx = putVert(b, idx, x0, y1, z0, cT); idx = putVert(b, idx, x0, y1, z1, cT);
        idx = putVert(b, idx, x1, y1, z1, cT); idx = putVert(b, idx, x1, y1, z0, cT);

        idx = putVert(b, idx, x0, y0, z0, cB); idx = putVert(b, idx, x1, y0, z0, cB);
        idx = putVert(b, idx, x1, y0, z1, cB); idx = putVert(b, idx, x0, y0, z1, cB);
        return idx;
    }

    private static int emitBoxLinesBuf(
            ByteBuffer b, int idx, float x0, float y0, float z0, float x1, float y1, float z1, int c
    ) {
        float[][] segs = {
                {x0,y0,z0, x1,y0,z0}, {x1,y0,z0, x1,y0,z1}, {x1,y0,z1, x0,y0,z1}, {x0,y0,z1, x0,y0,z0},
                {x0,y1,z0, x1,y1,z0}, {x1,y1,z0, x1,y1,z1}, {x1,y1,z1, x0,y1,z1}, {x0,y1,z1, x0,y1,z0},
                {x0,y0,z0, x0,y1,z0}, {x1,y0,z0, x1,y1,z0}, {x1,y0,z1, x1,y1,z1}, {x0,y0,z1, x0,y1,z1}
        };
        for (float[] s : segs) {
            idx = putVert(b, idx, s[0], s[1], s[2], c);
            idx = putVert(b, idx, s[3], s[4], s[5], c);
        }
        return idx;
    }
}
