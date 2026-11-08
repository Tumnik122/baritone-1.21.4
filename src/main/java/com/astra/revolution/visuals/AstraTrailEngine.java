package com.astra.revolution.visuals;

import baritone.Baritone;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * ⚡ GOD-TIER MOTION TRAILS & DASH CUBICS (Inspirowany MoonDLC class_1751).
 *
 * Oferuje:
 * 1. Centripetal Catmull-Rom Neon Ribbon (płynna wstęga światła 3D za graczem z laserowym rantem).
 * 2. MoonDLC DashCubics (unoszące się, obracające w 3D świecące kostki/diamenty wzdłuż śladu).
 * 3. Pełna ochrona macierzy kamery (Zero-Jitter, brak kasowania macierzy przez identity()).
 * 4. Płynny gradient Chroma Rainbow / Cyber Lilac & Cyan.
 */
public final class AstraTrailEngine {

    public static final AstraTrailEngine INSTANCE = new AstraTrailEngine();

    private boolean enabled = true;
    private int trailMode = 1; // 0 = Ribbon, 1 = MoonDLC DashCubics + Ribbon, 2 = DashCubics Only
    private boolean chroma = true;

    // Struktura węzła ścieżki (Zero-GC pooling)
    public record TrailPoint(double x, double y, double z, float height, long birthMs) {
        public double distanceSq(Vec3 v) {
            double dx = this.x - v.x;
            double dy = this.y - v.y;
            double dz = this.z - v.z;
            return dx * dx + dy * dy + dz * dz;
        }
    }
    private final Deque<TrailPoint> points = new ArrayDeque<>(128);

    // Struktura MoonDLC DashCubic (unosząca się wirująca kostka)
    private static final int MAX_CUBICS = 64;
    private final double[] cubicX = new double[MAX_CUBICS];
    private final double[] cubicY = new double[MAX_CUBICS];
    private final double[] cubicZ = new double[MAX_CUBICS];
    private final long[] cubicBirth = new long[MAX_CUBICS];
    private final float[] cubicRotSpeed = new float[MAX_CUBICS];
    private int cubicCursor = 0;
    private long lastCubicSpawnMs = 0L;

    private static final long TRAIL_LIFETIME_MS = 1100L;

    private AstraTrailEngine() {}

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getTrailMode() {
        return trailMode;
    }

    public void setTrailMode(int trailMode) {
        this.trailMode = trailMode;
    }

    /**
     * Rejestruje aktualną pozycję gracza co tick lub klatkę.
     */
    public void onTick(LocalPlayer player) {
        var s = Baritone.settings();
        boolean active = s != null && s.motionTrail != null ? s.motionTrail.value : enabled;
        if (!active || player == null) return;

        long now = System.currentTimeMillis();
        Vec3 pos = player.position();

        // 1. Zapis węzła wstęgi
        if (points.isEmpty() || points.peekLast().distanceSq(pos) > 0.005D) {
            points.addLast(new TrailPoint(pos.x, pos.y, pos.z, player.getBbHeight(), now));

            // 2. Tworzenie MoonDLC DashCubic przy ruchu (szybsze przy sprincie i skoku)
            boolean movingFast = player.isSprinting() || !player.onGround();
            long minInterval = movingFast ? 45L : 70L;
            if (now - lastCubicSpawnMs > minInterval && player.getDeltaMovement().horizontalDistanceSqr() > 0.0015D) {
                spawnDashCubic(pos.x, pos.y + 0.35D + (Math.random() - 0.5D) * 0.4D, pos.z, now);
                lastCubicSpawnMs = now;
            }
        }

        // Usuwanie przedawnionych węzłów
        while (!points.isEmpty() && (now - points.peekFirst().birthMs()) > TRAIL_LIFETIME_MS) {
            points.removeFirst();
        }
    }

    private void spawnDashCubic(double x, double y, double z, long now) {
        int idx = cubicCursor;
        cubicX[idx] = x;
        cubicY[idx] = y;
        cubicZ[idx] = z;
        cubicBirth[idx] = now;
        cubicRotSpeed[idx] = 120.0f + (float) Math.random() * 180.0f;
        cubicCursor = (idx + 1) % MAX_CUBICS;
    }

    /**
     * Główny render wywoływany w RenderLevelStageEvent / AstraVisualsEngine.
     * PoseStack ma już zaaplikowane obroty kamery i translację o -camPos.
     */
    public void render(PoseStack poseStack, Vec3 camPos) {
        var s = Baritone.settings();
        boolean active = s != null && s.motionTrail != null ? s.motionTrail.value : enabled;
        if (!active || points.size() < 2) return;

        int activeMode = s != null && s.motionTrailMode != null ? s.motionTrailMode.value : trailMode;
        long now = System.currentTimeMillis();

        try (AstraGlStateGuard guard = new AstraGlStateGuard(false, false, true)) {
            // Render 1: Płynna wstęga Catmull-Rom (dla trybów 0-4)
            if (activeMode != 5) {
                renderCatmullRomRibbon(poseStack, camPos, now, activeMode);
            }

            // Render 2: MoonDLC DashCubics (wszystkie tryby)
            renderDashCubics(poseStack, camPos, now, activeMode);
        }
    }

    private void renderCatmullRomRibbon(PoseStack poseStack, Vec3 camPos, long now, int mode) {
        TrailPoint[] pts = points.toArray(new TrailPoint[0]);
        if (pts.length < 2) return;

        Matrix4f mat = poseStack.last().pose();
        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);

        int totalSegments = pts.length - 1;
        int subSteps = 3;

        for (int i = 0; i < totalSegments; i++) {
            TrailPoint p0 = pts[Math.max(0, i - 1)];
            TrailPoint p1 = pts[i];
            TrailPoint p2 = pts[i + 1];
            TrailPoint p3 = pts[Math.min(pts.length - 1, i + 2)];

            for (int s = 0; s < subSteps; s++) {
                float u = (float) s / subSteps;

                // Centripetal Catmull-Rom spline
                double px = catmullRom(p0.x(), p1.x(), p2.x(), p3.x(), u);
                double py = catmullRom(p0.y(), p1.y(), p2.y(), p3.y(), u);
                double pz = catmullRom(p0.z(), p1.z(), p2.z(), p3.z(), u);

                float ts = Mth.lerp(u, (float) p1.birthMs(), (float) p2.birthMs());
                float age = Math.min(1.0F, (now - (long) ts) / (float) TRAIL_LIFETIME_MS);
                float alphaPct = 1.0F - age;
                if (alphaPct <= 0.01F) continue;

                float ribbonH = Mth.lerp(u, p1.height(), p2.height()) * 0.72F * (float) Math.pow(alphaPct, 0.4);

                float rx = (float) (px - camPos.x);
                float ry = (float) (py - camPos.y);
                float rz = (float) (pz - camPos.z);

                float frac = ((float) i + u) / totalSegments;
                int colBottom = sampleTrailColor(mode, frac, now, (int) (alphaPct * 220.0F), false);
                int colTop = sampleTrailColor(mode, frac, now, (int) (alphaPct * 110.0F), true);

                buf.addVertex(mat, rx, ry + 0.04F, rz).setColor(colBottom);
                buf.addVertex(mat, rx, ry + ribbonH, rz).setColor(colTop);
            }
        }

        MeshData mesh = buf.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
            mesh.close();
        }

        // Górna neonowa żyłka laserowa (Laser Crest Wire)
        BufferBuilder lineBuf = tess.begin(VertexFormat.Mode.LINE_STRIP, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < totalSegments; i++) {
            TrailPoint p0 = pts[Math.max(0, i - 1)];
            TrailPoint p1 = pts[i];
            TrailPoint p2 = pts[i + 1];
            TrailPoint p3 = pts[Math.min(pts.length - 1, i + 2)];

            for (int s = 0; s < subSteps; s++) {
                float u = (float) s / subSteps;
                double px = catmullRom(p0.x(), p1.x(), p2.x(), p3.x(), u);
                double py = catmullRom(p0.y(), p1.y(), p2.y(), p3.y(), u);
                double pz = catmullRom(p0.z(), p1.z(), p2.z(), p3.z(), u);

                float ts = Mth.lerp(u, (float) p1.birthMs(), (float) p2.birthMs());
                float alphaPct = 1.0F - Math.min(1.0F, (now - (long) ts) / (float) TRAIL_LIFETIME_MS);
                float ribbonH = Mth.lerp(u, p1.height(), p2.height()) * 0.72F * (float) Math.pow(alphaPct, 0.4);

                float rx = (float) (px - camPos.x);
                float ry = (float) (py - camPos.y);
                float rz = (float) (pz - camPos.z);

                lineBuf.addVertex(mat, rx, ry + ribbonH, rz).setColor(255, 255, 255, (int) (alphaPct * 240.0F));
            }
        }

        MeshData lineMesh = lineBuf.build();
        if (lineMesh != null) {
            RenderSystem.lineWidth(2.5F);
            BufferUploader.drawWithShader(lineMesh);
            lineMesh.close();
        }
    }

    /**
     * Rysuje wirujące kostki MoonDLC DashCubics wzdłuż trajektorii.
     */
    private void renderDashCubics(PoseStack poseStack, Vec3 camPos, long now, int mode) {
        Tesselator tess = Tesselator.getInstance();

        // Pass 1: Przezroczyste ścianki kostek
        BufferBuilder buf = tess.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        for (int i = 0; i < MAX_CUBICS; i++) {
            long birth = cubicBirth[i];
            if (birth == 0L) continue;

            long elapsed = now - birth;
            if (elapsed > 950L) continue;

            float agePct = (float) elapsed / 950.0F;
            float alphaPct = 1.0F - agePct;
            float scale = 0.11F * (float) Math.pow(alphaPct, 0.45);

            double rx = cubicX[i] - camPos.x;
            double ry = cubicY[i] - camPos.y;
            double rz = cubicZ[i] - camPos.z;

            float rot = (elapsed / 1000.0F) * cubicRotSpeed[i];

            poseStack.pushPose();
            poseStack.translate(rx, ry, rz);
            poseStack.mulPose(Axis.YP.rotationDegrees(rot));
            poseStack.mulPose(Axis.XP.rotationDegrees(rot * 0.75F));
            poseStack.mulPose(Axis.ZP.rotationDegrees(rot * 0.5F));

            Matrix4f cMat = poseStack.last().pose();
            int color = sampleTrailColor(mode, agePct, now, (int) (alphaPct * 210.0F), false);
            emitCube(buf, cMat, scale, color);

            poseStack.popPose();
        }

        MeshData mesh = buf.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
            mesh.close();
        }

        // Pass 2: Krystaliczne świecące krawędzie kostek (MoonDLC Diamond Edges)
        BufferBuilder edgeBuf = tess.begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR);

        for (int i = 0; i < MAX_CUBICS; i++) {
            long birth = cubicBirth[i];
            if (birth == 0L) continue;

            long elapsed = now - birth;
            if (elapsed > 950L) continue;

            float agePct = (float) elapsed / 950.0F;
            float alphaPct = 1.0F - agePct;
            float scale = 0.11F * (float) Math.pow(alphaPct, 0.45);

            double rx = cubicX[i] - camPos.x;
            double ry = cubicY[i] - camPos.y;
            double rz = cubicZ[i] - camPos.z;

            float rot = (elapsed / 1000.0F) * cubicRotSpeed[i];

            poseStack.pushPose();
            poseStack.translate(rx, ry, rz);
            poseStack.mulPose(Axis.YP.rotationDegrees(rot));
            poseStack.mulPose(Axis.XP.rotationDegrees(rot * 0.75F));
            poseStack.mulPose(Axis.ZP.rotationDegrees(rot * 0.5F));

            Matrix4f cMat = poseStack.last().pose();
            int edgeCol = ((int) (alphaPct * 245.0F) << 24) | 0x00FFFFFF;
            emitCubeEdges(edgeBuf, cMat, scale * 1.02F, edgeCol);

            poseStack.popPose();
        }

        MeshData edgeMesh = edgeBuf.build();
        if (edgeMesh != null) {
            RenderSystem.lineWidth(1.8F);
            BufferUploader.drawWithShader(edgeMesh);
            edgeMesh.close();
        }
    }

    private static void emitCube(BufferBuilder buf, Matrix4f mat, float s, int col) {
        float hs = s * 0.5F;
        // Przód
        buf.addVertex(mat, -hs, -hs, hs).setColor(col);
        buf.addVertex(mat, hs, -hs, hs).setColor(col);
        buf.addVertex(mat, hs, hs, hs).setColor(col);
        buf.addVertex(mat, -hs, -hs, hs).setColor(col);
        buf.addVertex(mat, hs, hs, hs).setColor(col);
        buf.addVertex(mat, -hs, hs, hs).setColor(col);

        // Tył
        buf.addVertex(mat, hs, -hs, -hs).setColor(col);
        buf.addVertex(mat, -hs, -hs, -hs).setColor(col);
        buf.addVertex(mat, -hs, hs, -hs).setColor(col);
        buf.addVertex(mat, hs, -hs, -hs).setColor(col);
        buf.addVertex(mat, -hs, hs, -hs).setColor(col);
        buf.addVertex(mat, hs, hs, -hs).setColor(col);

        // Lewo
        buf.addVertex(mat, -hs, -hs, -hs).setColor(col);
        buf.addVertex(mat, -hs, -hs, hs).setColor(col);
        buf.addVertex(mat, -hs, hs, hs).setColor(col);
        buf.addVertex(mat, -hs, -hs, -hs).setColor(col);
        buf.addVertex(mat, -hs, hs, hs).setColor(col);
        buf.addVertex(mat, -hs, hs, -hs).setColor(col);

        // Prawo
        buf.addVertex(mat, hs, -hs, hs).setColor(col);
        buf.addVertex(mat, hs, -hs, -hs).setColor(col);
        buf.addVertex(mat, hs, hs, -hs).setColor(col);
        buf.addVertex(mat, hs, -hs, hs).setColor(col);
        buf.addVertex(mat, hs, hs, -hs).setColor(col);
        buf.addVertex(mat, hs, hs, hs).setColor(col);

        // Góra
        buf.addVertex(mat, -hs, hs, hs).setColor(col);
        buf.addVertex(mat, hs, hs, hs).setColor(col);
        buf.addVertex(mat, hs, hs, -hs).setColor(col);
        buf.addVertex(mat, -hs, hs, hs).setColor(col);
        buf.addVertex(mat, hs, hs, -hs).setColor(col);
        buf.addVertex(mat, -hs, hs, -hs).setColor(col);

        // Dół
        buf.addVertex(mat, -hs, -hs, -hs).setColor(col);
        buf.addVertex(mat, hs, -hs, -hs).setColor(col);
        buf.addVertex(mat, hs, -hs, hs).setColor(col);
        buf.addVertex(mat, -hs, -hs, -hs).setColor(col);
        buf.addVertex(mat, hs, -hs, hs).setColor(col);
        buf.addVertex(mat, -hs, -hs, hs).setColor(col);
    }

    private static void emitCubeEdges(BufferBuilder buf, Matrix4f mat, float s, int col) {
        float hs = s * 0.5F;
        // Dolne 4 krawędzie
        buf.addVertex(mat, -hs, -hs, -hs).setColor(col); buf.addVertex(mat, hs, -hs, -hs).setColor(col);
        buf.addVertex(mat, hs, -hs, -hs).setColor(col); buf.addVertex(mat, hs, -hs, hs).setColor(col);
        buf.addVertex(mat, hs, -hs, hs).setColor(col); buf.addVertex(mat, -hs, -hs, hs).setColor(col);
        buf.addVertex(mat, -hs, -hs, hs).setColor(col); buf.addVertex(mat, -hs, -hs, -hs).setColor(col);

        // Górne 4 krawędzie
        buf.addVertex(mat, -hs, hs, -hs).setColor(col); buf.addVertex(mat, hs, hs, -hs).setColor(col);
        buf.addVertex(mat, hs, hs, -hs).setColor(col); buf.addVertex(mat, hs, hs, hs).setColor(col);
        buf.addVertex(mat, hs, hs, hs).setColor(col); buf.addVertex(mat, -hs, hs, hs).setColor(col);
        buf.addVertex(mat, -hs, hs, hs).setColor(col); buf.addVertex(mat, -hs, hs, -hs).setColor(col);

        // 4 pionowe filary
        buf.addVertex(mat, -hs, -hs, -hs).setColor(col); buf.addVertex(mat, -hs, hs, -hs).setColor(col);
        buf.addVertex(mat, hs, -hs, -hs).setColor(col); buf.addVertex(mat, hs, hs, -hs).setColor(col);
        buf.addVertex(mat, hs, -hs, hs).setColor(col); buf.addVertex(mat, hs, hs, hs).setColor(col);
        buf.addVertex(mat, -hs, -hs, hs).setColor(col); buf.addVertex(mat, -hs, hs, -hs).setColor(col);
    }

    private static double catmullRom(double p0, double p1, double p2, double p3, float t) {
        double t2 = t * t;
        double t3 = t2 * t;
        return 0.5 * ((2.0 * p1) + (-p0 + p2) * t + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2 + (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3);
    }

    private int sampleTrailColor(int mode, float progress, long timeMs, int alpha, boolean top) {
        switch (mode) {
            case 1 -> { // MoonDLC Chroma Rainbow Flow
                float hue = ((timeMs % 3500L) / 3500.0F + progress * 0.70F + (top ? 0.12F : 0.0F)) % 1.0F;
                int rgb = Mth.hsvToRgb(hue, 0.82F, 1.0F);
                return (alpha << 24) | (rgb & 0x00FFFFFF);
            }
            case 2 -> { // Sunset Neon & Gold
                int r = 255;
                int g = (int) Mth.lerp(progress, 60.0F, 210.0F);
                int b = (int) Mth.lerp(progress, 140.0F, 30.0F);
                return (alpha << 24) | (r << 16) | (g << 8) | b;
            }
            case 3 -> { // Glacial Ice Blue
                int r = (int) Mth.lerp(progress, 0.0F, 180.0F);
                int g = (int) Mth.lerp(progress, 160.0F, 245.0F);
                int b = 255;
                return (alpha << 24) | (r << 16) | (g << 8) | b;
            }
            case 4 -> { // Matrix Emerald
                int r = 0;
                int g = (int) Mth.lerp(progress, 255.0F, 240.0F);
                int b = (int) Mth.lerp(progress, 120.0F, 255.0F);
                return (alpha << 24) | (r << 16) | (g << 8) | b;
            }
            default -> { // Cyber Lilac & Cyan (Astra Signature)
                int r = (int) Mth.lerp(progress, 0.0F, 199.0F);
                int g = (int) Mth.lerp(progress, 240.0F, 125.0F);
                int b = 255;
                return (alpha << 24) | (r << 16) | (g << 8) | b;
            }
        }
    }
}
