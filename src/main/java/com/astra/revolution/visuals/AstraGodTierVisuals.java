package com.astra.revolution.visuals;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.CoreShaders;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

/**
 * Ultra-wydajny silnik efektów God-Tier (Zero-Allocation, Zero-glGet Pipeline Stall).
 *
 * Zawiera:
 * 1. Volumetric Cyber Pillars + Double-Helix DNA Beacon (dla celów Baritone)
 * 2. 3D Combat Impact Waves & Ground Dispersion Rings (przy uderzeniach i lądowaniu)
 * 3. Multi-Shell Additive Bloom Geometry (bez kosztu FBO)
 * 4. 2D Kinetic Speed Warp Lines & Combat Damage Ripple (Screen space)
 */
public final class AstraGodTierVisuals {

    // Zero-Alloc Scratch Buffers dla wątku renderującego
    private static final Vector3f TMP_A = new Vector3f();
    private static final Vector3f TMP_B = new Vector3f();

    // Pula aktywnych fal uderzeniowych (Ring Buffer bez alokacji obiektów podczas walki)
    private static final int MAX_SHOCKWAVES = 16;
    private static final double[] SW_X = new double[MAX_SHOCKWAVES];
    private static final double[] SW_Y = new double[MAX_SHOCKWAVES];
    private static final double[] SW_Z = new double[MAX_SHOCKWAVES];
    private static final long[] SW_BIRTH_MS = new long[MAX_SHOCKWAVES];
    private static final int[] SW_COLOR = new int[MAX_SHOCKWAVES];
    private static final boolean[] SW_IS_COMBAT = new boolean[MAX_SHOCKWAVES];
    private static int swCursor = 0;

    // Płynna interpolacja pędu dla efektu linii prędkości
    private static float smoothedSpeedIntensity = 0.0F;

    /**
     * Rejestruje falę uderzeniową w miejscu trafienia przeciwnika lub twardego lądowania (0 B alokacji).
     */
    public static void triggerShockwave(double x, double y, double z, int rgbColor, boolean combatImpact) {
        int idx = swCursor;
        SW_X[idx] = x;
        SW_Y[idx] = y;
        SW_Z[idx] = z;
        SW_BIRTH_MS[idx] = System.currentTimeMillis();
        SW_COLOR[idx] = rgbColor;
        SW_IS_COMBAT[idx] = combatImpact;
        swCursor = (idx + 1) & (MAX_SHOCKWAVES - 1);
    }

    /**
     * Bezblokadowe wejście w tryb Additive Glow (Zero wywołań glGetInteger / glIsEnabled).
     */
    public static void beginAdditiveGlowPass(boolean depthTest) {
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
            GlStateManager.SourceFactor.SRC_ALPHA,
            GlStateManager.DestFactor.ONE,
            GlStateManager.SourceFactor.ONE,
            GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA
        );
        if (depthTest) {
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
        } else {
            RenderSystem.disableDepthTest();
        }
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(CoreShaders.POSITION_COLOR);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    public static void endGlowPass() {
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.enableCull();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * Renderuje Wolumetryczny Cyber-Filar + Podwójną Helisę DNA (Double-Helix Beacon) dla celu Pathfindera.
     */
    public static void renderVolumetricCyberBeacon(
            PoseStack poseStack, Vec3 camPos,
            double goalX, double goalY, double goalZ,
            int primaryColor, int secondaryColor, float timeSec) {

        float rx = (float) (goalX - camPos.x);
        float ry = (float) (goalY - camPos.y);
        float rz = (float) (goalZ - camPos.z);

        Matrix4f mat = poseStack.last().pose();
        Tesselator tess = Tesselator.getInstance();

        int r1 = (primaryColor >> 16) & 0xFF, g1 = (primaryColor >> 8) & 0xFF, b1 = primaryColor & 0xFF;
        int r2 = (secondaryColor >> 16) & 0xFF, g2 = (secondaryColor >> 8) & 0xFF, b2 = secondaryColor & 0xFF;

        beginAdditiveGlowPass(true);
        BufferBuilder buf = tess.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        // 1. Trójwarstwowy Wolumetryczny Rdzeń Laserowy (Multi-Shell Pseudo-Kawase Bloom)
        float[] radii = {0.09F, 0.22F, 0.42F};
        int[] baseAlphas = {190, 85, 32};
        float pillarHeight = 18.0F;
        int sides = 12;

        for (int shell = 0; shell < 3; shell++) {
            float rad = radii[shell] * (1.0F + 0.08F * Mth.sin(timeSec * 5.0F + shell));
            int aBot = baseAlphas[shell];
            for (int i = 0; i < sides; i++) {
                float a0 = (float) (i * Math.TAU / sides + timeSec * 0.4F);
                float a1 = (float) ((i + 1) * Math.TAU / sides + timeSec * 0.4F);
                float x0 = rx + Mth.cos(a0) * rad, z0 = rz + Mth.sin(a0) * rad;
                float x1 = rx + Mth.cos(a1) * rad, z1 = rz + Mth.sin(a1) * rad;

                buf.addVertex(mat, x0, ry, z0).setColor(r1, g1, b1, aBot);
                buf.addVertex(mat, x1, ry, z1).setColor(r1, g1, b1, aBot);
                buf.addVertex(mat, x1, ry + pillarHeight, z1).setColor(r2, g2, b2, 0);
                buf.addVertex(mat, x0, ry + pillarHeight, z0).setColor(r2, g2, b2, 0);
            }
        }

        // 2. Podwójna Helisa DNA (2 przeciwbieżne wstęgi energetyczne oplatające cel)
        int helixSegments = 48;
        float helixHeight = 6.5F;
        float helixRadius = 0.58F;
        float ribbonThickness = 0.055F;

        for (int strand = 0; strand < 2; strand++) {
            float phaseOffset = (float) (strand * Math.PI);
            int sr = strand == 0 ? r1 : r2;
            int sg = strand == 0 ? g1 : g2;
            int sb = strand == 0 ? b1 : b2;

            for (int s = 0; s < helixSegments; s++) {
                float t0 = (float) s / helixSegments;
                float t1 = (float) (s + 1) / helixSegments;
                float y0 = ry + t0 * helixHeight;
                float y1 = ry + t1 * helixHeight;

                float ang0 = t0 * 14.0F - timeSec * 3.8F + phaseOffset;
                float ang1 = t1 * 14.0F - timeSec * 3.8F + phaseOffset;

                float rScale0 = helixRadius * (1.0F - 0.35F * t0);
                float rScale1 = helixRadius * (1.0F - 0.35F * t1);

                float hx0 = rx + Mth.cos(ang0) * rScale0, hz0 = rz + Mth.sin(ang0) * rScale0;
                float hx1 = rx + Mth.cos(ang1) * rScale1, hz1 = rz + Mth.sin(ang1) * rScale1;

                int alpha0 = (int) (220 * (1.0F - t0) * (0.7F + 0.3F * Mth.sin(t0 * 20.0F - timeSec * 8.0F)));
                int alpha1 = (int) (220 * (1.0F - t1) * (0.7F + 0.3F * Mth.sin(t1 * 20.0F - timeSec * 8.0F)));

                buf.addVertex(mat, hx0, y0 - ribbonThickness, hz0).setColor(sr, sg, sb, alpha0);
                buf.addVertex(mat, hx1, y1 - ribbonThickness, hz1).setColor(sr, sg, sb, alpha1);
                buf.addVertex(mat, hx1, y1 + ribbonThickness, hz1).setColor(255, 255, 255, alpha1);
                buf.addVertex(mat, hx0, y0 + ribbonThickness, hz0).setColor(255, 255, 255, alpha0);
            }
        }

        BufferUploader.drawWithShader(buf.buildOrThrow());
        endGlowPass();
    }

    /**
     * Renderuje wszystkie aktywne fale uderzeniowe (Dual-Ring Dispersion + 3D Combat Hit Burst) w jednym Draw Callu.
     */
    public static void renderActiveShockwaves(PoseStack poseStack, Vec3 camPos) {
        long now = System.currentTimeMillis();
        Matrix4f mat = poseStack.last().pose();
        Tesselator tess = Tesselator.getInstance();

        BufferBuilder buf = null;

        for (int i = 0; i < MAX_SHOCKWAVES; i++) {
            long ageMs = now - SW_BIRTH_MS[i];
            long duration = SW_IS_COMBAT[i] ? 550L : 850L;
            if (ageMs < 0 || ageMs >= duration) continue;

            if (buf == null) {
                beginAdditiveGlowPass(true);
                buf = tess.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            }

            float progress = (float) ageMs / duration; // [0..1]
            float ease = 1.0F - (float) Math.pow(1.0F - progress, 3.8);
            float maxRad = SW_IS_COMBAT[i] ? 1.85F : 3.2F;
            float radius = 0.2F + ease * maxRad;
            float ringWidth = (SW_IS_COMBAT[i] ? 0.14F : 0.22F) * (1.0F - progress * 0.65F);

            int col = SW_COLOR[i];
            int r = (col >> 16) & 0xFF, g = (col >> 8) & 0xFF, b = col & 0xFF;
            int alpha = (int) (235 * (1.0F - progress) * (1.0F - progress));

            float cx = (float) (SW_X[i] - camPos.x);
            float cy = (float) (SW_Y[i] - camPos.y) + 0.03F;
            float cz = (float) (SW_Z[i] - camPos.z);

            int segs = 36;
            for (int s = 0; s < segs; s++) {
                float a0 = (float) (s * Math.TAU / segs);
                float a1 = (float) ((s + 1) * Math.TAU / segs);
                float c0 = Mth.cos(a0), s0 = Mth.sin(a0);
                float c1 = Mth.cos(a1), s1 = Mth.sin(a1);

                buf.addVertex(mat, cx + c0 * (radius - ringWidth), cy, cz + s0 * (radius - ringWidth)).setColor(r, g, b, 0);
                buf.addVertex(mat, cx + c1 * (radius - ringWidth), cy, cz + s1 * (radius - ringWidth)).setColor(r, g, b, 0);
                buf.addVertex(mat, cx + c1 * radius, cy + 0.04F, cz + s1 * radius).setColor(255, 255, 255, alpha);
                buf.addVertex(mat, cx + c0 * radius, cy + 0.04F, cz + s0 * radius).setColor(255, 255, 255, alpha);
            }
        }

        if (buf != null) {
            BufferUploader.drawWithShader(buf.buildOrThrow());
            endGlowPass();
        }
    }

    /**
     * Renderuje 2D linie prędkości (Kinetic Speed Warp Lines) na obrzeżach ekranu oraz
     * czerwono-różowy impuls uderzeniowy (Combat Damage Vignette).
     */
    public static void renderScreenSpeedLinesAndDamageRipple(GuiGraphics g, int screenWidth, int screenHeight, LocalPlayer player, float partialTick) {
        if (player == null) return;

        // 1. Obliczenie pędu poziomego gracza
        Vec3 motion = player.getDeltaMovement();
        double horizSpeed = Math.hypot(motion.x, motion.z);
        float targetIntensity = (float) Mth.clamp((horizSpeed - 0.22) / 0.16, 0.0, 1.0);
        smoothedSpeedIntensity = Mth.lerp(0.12F, smoothedSpeedIntensity, targetIntensity);

        // 2. Combat Damage Ripple Vignette
        if (player.hurtTime > 0) {
            float hurtFactor = (float) player.hurtTime / 10.0F;
            int hurtAlpha = (int) (65 * hurtFactor);
            // Czerwono-różowa winieta na obrzeżach ekranu
            g.fillGradient(0, 0, screenWidth, 24, (hurtAlpha << 24) | 0xFF2255, 0x00FF2255);
            g.fillGradient(0, screenHeight - 24, screenWidth, screenHeight, 0x00FF2255, (hurtAlpha << 24) | 0xFF2255);
            g.fillGradient(0, 0, 24, screenHeight, (hurtAlpha << 24) | 0xFF2255, 0x00FF2255);
            g.fillGradient(screenWidth - 24, 0, screenWidth, screenHeight, 0x00FF2255, (hurtAlpha << 24) | 0xFF2255);
        }

        // 3. Kinetic Speed Warp Lines
        if (smoothedSpeedIntensity > 0.05F) {
            float time = (System.currentTimeMillis() % 100000L) / 1000.0F;
            int numLines = 14;
            float cx = screenWidth * 0.5F;
            float cy = screenHeight * 0.5F;
            int lineAlpha = (int) (140 * smoothedSpeedIntensity);

            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShader(CoreShaders.POSITION_COLOR);

            Tesselator tess = Tesselator.getInstance();
            BufferBuilder buf = tess.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            Matrix4f mat = g.pose().last().pose();

            for (int i = 0; i < numLines; i++) {
                float angle = (float) (i * Math.TAU / numLines + Mth.sin(time * 3.0F + i) * 0.08F);
                float cos = Mth.cos(angle);
                float sin = Mth.sin(angle);

                float rInner = Math.min(cx, cy) * (0.75F + 0.1F * Mth.sin(time * 12.0F + i * 2.0F));
                float rOuter = Math.max(screenWidth, screenHeight) * 0.9F;

                float x0 = cx + cos * rInner;
                float y0 = cy + sin * rInner;
                float x1 = cx + cos * rOuter;
                float y1 = cy + sin * rOuter;

                float perpX = -sin * 1.5F;
                float perpY = cos * 1.5F;

                // Gradient od przezroczystego do neonowego cyjanu z fioletem
                buf.addVertex(mat, x0 - perpX, y0 - perpY, 0).setColor(0, 240, 255, 0);
                buf.addVertex(mat, x0 + perpX, y0 + perpY, 0).setColor(0, 240, 255, 0);
                buf.addVertex(mat, x1 + perpX * 2.2F, y1 + perpY * 2.2F, 0).setColor(199, 125, 255, lineAlpha);
                buf.addVertex(mat, x1 - perpX * 2.2F, y1 - perpY * 2.2F, 0).setColor(199, 125, 255, lineAlpha);
            }

            BufferUploader.drawWithShader(buf.buildOrThrow());
            RenderSystem.disableBlend();
        }
    }
}
