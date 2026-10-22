package baritone.render;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.event.events.RenderEvent;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CoreShaders;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.awt.Color;
import java.util.List;

/**
 * 👑 China Hat Visual Cosmetic Renderer (Astra & Argon 4 Edition)
 *
 * Implementuje autentyczny profil azjatyckiego kapelusza (Dǒulì / Kasa)
 * z dwustopniowym wklęsłym załamaniem połaci, subtelnym żebrowaniem bambusa
 * oraz geometrycznym glowing rimem (wstęga 3D na TRIANGLES zamiast niestabilnego GL_LINES).
 *
 * ZERO STATE LEAKS:
 * - Wyeliminowano całkowicie GL_LINE_STRIP i TRIANGLE_FAN, które w Core Profile 1.21.4
 *   generowały artefakty kwadratowych ramek i ciągnących się w dół smug.
 * - Renderuje kapelusz zarówno dla gracza, jak i botów w świecie.
 */
public final class ChinaHatRenderer {

    private static final float[] SIN_TABLE = new float[129];
    private static final float[] COS_TABLE = new float[129];
    private static int cachedSegments = -1;

    private ChinaHatRenderer() {}

    private static void ensureTrigTables(int segments) {
        int count = Mth.clamp(segments, 24, 128);
        if (cachedSegments == count) return;
        cachedSegments = count;
        for (int i = 0; i <= count; i++) {
            double rad = ((double) i / (double) count) * 2.0 * Math.PI;
            SIN_TABLE[i] = (float) Math.sin(rad);
            COS_TABLE[i] = (float) Math.cos(rad);
        }
    }

    public static void render(RenderEvent event, PoseStack stack, Minecraft mc, float delta) {
        Settings s = BaritoneAPI.getSettings();
        if (!s.chinaHat.value) {
            return;
        }

        Player localPlayer = mc.player;
        if (localPlayer == null || mc.level == null) {
            return;
        }

        Camera camera = mc.gameRenderer.getMainCamera();
        if (camera == null) {
            return;
        }

        Vec3 camPos = camera.getPosition();
        float tickDelta = event.getPartialTicks();

        int segs = Mth.clamp(s.chinaHatSegments.value, 32, 96);
        ensureTrigTables(segs);

        float rotSpeed = s.chinaHatRotationSpeed.value;
        float timePhase = (float) (((System.currentTimeMillis() % 600000L) / 1000.0) * rotSpeed);

        boolean firstPerson = mc.options.getCameraType().isFirstPerson();
        boolean allowFirstPerson = s.chinaHatFirstPerson.value;

        // Zbieramy cele: gracz lokalny oraz inne postacie graczy / boty
        List<Player> targetPlayers = new java.util.ArrayList<>();
        for (Player p : mc.level.players()) {
            if (!p.isAlive() || p.isInvisible() || p.isSpectator()) {
                continue;
            }
            if (p == localPlayer && firstPerson && !allowFirstPerson) {
                continue;
            }
            if (p == localPlayer && com.astra.revolution.visuals.AstraVisualsEngine.getActiveDisguise() != com.astra.revolution.visuals.AstraVisualsEngine.DisguiseModel.OFF) {
                continue;
            }
            targetPlayers.add(p);
        }

        if (targetPlayers.isEmpty()) {
            return;
        }

        float radius = Math.max(0.45f, s.chinaHatRadius.value);
        float height = Math.max(0.15f, s.chinaHatHeight.value);
        boolean rainbow = s.chinaHatRainbow.value;
        Color baseCol = s.chinaHatColor.value;
        Color secCol = s.chinaHatSecondaryColor.value;
        float pitchFollow = s.chinaHatTiltFollow.value;

        Tesselator tesselator = Tesselator.getInstance();

        // 1. Ustawienie potoku OpenGL 3.3 Core dla siatki kapelusza przez AstraGlStateGuard
        try (com.astra.revolution.visuals.AstraGlStateGuard guard = new com.astra.revolution.visuals.AstraGlStateGuard(false, false, true)) {
            // =========================================================================
            // PRZEBIEG 1: Czasza kapelusza (Dwustopniowy wklęsły stożek Dǒulì na TRIANGLES)
            // =========================================================================
            BufferBuilder canopy = tesselator.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

            for (Player p : targetPlayers) {
                stack.pushPose();
                applyTransform(stack, p, camPos, tickDelta, pitchFollow);
                Matrix4f mat = stack.last().pose();
                emitCanopyTriangles(canopy, mat, segs, radius, height, rainbow, baseCol, secCol, timePhase);
                emitCelestialApexPearl(canopy, mat, height, rainbow, baseCol, secCol, timePhase);
                stack.popPose();
            }

            com.mojang.blaze3d.vertex.MeshData canopyMesh = canopy.build();
            if (canopyMesh != null) {
                BufferUploader.drawWithShader(canopyMesh);
            }
        }

        // =========================================================================
        // PRZEBIEG 2: Świecący neonowy obrys 3D oraz jedwabne wstęgi Verlet (Additive Bloom)
        // =========================================================================
        try (com.astra.revolution.visuals.AstraGlStateGuard guard = new com.astra.revolution.visuals.AstraGlStateGuard(true, false, true)) {
            BufferBuilder rim = tesselator.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

            for (Player p : targetPlayers) {
                stack.pushPose();
                applyTransform(stack, p, camPos, tickDelta, pitchFollow);
                Matrix4f mat = stack.last().pose();
                emitGlowingRimRibbon(rim, mat, segs, radius, rainbow, baseCol, secCol, timePhase);
                emitVerletSilkRibbons(rim, mat, p, radius, rainbow, baseCol, secCol, timePhase);
                stack.popPose();
            }

            com.mojang.blaze3d.vertex.MeshData rimMesh = rim.build();
            if (rimMesh != null) {
                BufferUploader.drawWithShader(rimMesh);
            }
        }
    }

    private static void applyTransform(PoseStack stack, Player player, Vec3 camPos, float tickDelta, float pitchFollow) {
        double px = Mth.lerp(tickDelta, player.xo, player.getX()) - camPos.x;
        double py = Mth.lerp(tickDelta, player.yo, player.getY()) - camPos.y;
        double pz = Mth.lerp(tickDelta, player.zo, player.getZ()) - camPos.z;

        float sneakOffset = player.isCrouching() ? 0.22f : 0.0f;
        // Elegancka wysokość: tuż nad głową gracza, z marginesem na warstwę włosów/kapelusza
        float headTopY = player.getBbHeight() + 0.16f - sneakOffset;

        // Subtelne falowanie wysokości (Hover Wave)
        float hoverWave = (float) Math.sin((System.currentTimeMillis() % 10000L) / 1000.0 * 2.5) * 0.012f;

        float headYaw = Mth.lerp(tickDelta, player.yHeadRotO, player.getYHeadRot());
        float headPitch = Mth.lerp(tickDelta, player.xRotO, player.getXRot());

        stack.translate(px, py + headTopY + hoverWave, pz);
        stack.mulPose(Axis.YP.rotationDegrees(-headYaw));
        if (pitchFollow > 0.001f) {
            stack.mulPose(Axis.XP.rotationDegrees(headPitch * pitchFollow));
        }
    }

    private static void emitCanopyTriangles(BufferBuilder buf, Matrix4f mat, int segs,
                                           float rOuter, float yApex, boolean rainbow,
                                           Color baseCol, Color secCol, float timePhase) {
        float rMid = rOuter * 0.46f;
        float yMid = yApex * 0.42f; // Tradycyjny wklęsły łuk bambusowego kapelusza Dǒulì

        int apexColor = sampleColor(0.0f, timePhase, rainbow, baseCol, secCol, 175, 1.0f);

        for (int i = 0; i < segs; i++) {
            float f0 = (float) i / (float) segs;
            float f1 = (float) (i + 1) / (float) segs;

            float c0 = COS_TABLE[i];
            float s0 = SIN_TABLE[i];
            float c1 = COS_TABLE[i + 1];
            float s1 = SIN_TABLE[i + 1];

            // Subtelne żebrowanie bambusowego stelaża (16 symetrycznych żeber)
            float rib0 = 0.92f + 0.08f * (float) Math.sin(f0 * 16.0 * Math.PI);
            float rib1 = 0.92f + 0.08f * (float) Math.sin(f1 * 16.0 * Math.PI);

            int colMid0 = sampleColor(f0, timePhase, rainbow, baseCol, secCol, 125, rib0);
            int colMid1 = sampleColor(f1, timePhase, rainbow, baseCol, secCol, 125, rib1);
            int colRim0 = sampleColor(f0, timePhase, rainbow, baseCol, secCol, 65, rib0);
            int colRim1 = sampleColor(f1, timePhase, rainbow, baseCol, secCol, 65, rib1);

            // Pierścień 1: Szczyt (Apex) -> Pierścień środkowy (Mid Ring)
            buf.addVertex(mat, 0.0f, yApex, 0.0f).setColor(apexColor);
            buf.addVertex(mat, c0 * rMid, yMid, s0 * rMid).setColor(colMid0);
            buf.addVertex(mat, c1 * rMid, yMid, s1 * rMid).setColor(colMid1);

            // Pierścień 2: Środek -> Zewnętrzny rant (2 trójkąty tworzące pas)
            buf.addVertex(mat, c0 * rMid, yMid, s0 * rMid).setColor(colMid0);
            buf.addVertex(mat, c0 * rOuter, 0.0f, s0 * rOuter).setColor(colRim0);
            buf.addVertex(mat, c1 * rOuter, 0.0f, s1 * rOuter).setColor(colRim1);

            buf.addVertex(mat, c0 * rMid, yMid, s0 * rMid).setColor(colMid0);
            buf.addVertex(mat, c1 * rOuter, 0.0f, s1 * rOuter).setColor(colRim1);
            buf.addVertex(mat, c1 * rMid, yMid, s1 * rMid).setColor(colMid1);
        }
    }

    private static void emitGlowingRimRibbon(BufferBuilder buf, Matrix4f mat, int segs,
                                            float radius, boolean rainbow,
                                            Color baseCol, Color secCol, float timePhase) {
        float ribbonHalf = 0.020f;
        float rInner = radius - ribbonHalf;
        float rOuter = radius + ribbonHalf;

        for (int i = 0; i < segs; i++) {
            float f0 = (float) i / (float) segs;
            float f1 = (float) (i + 1) / (float) segs;

            float c0 = COS_TABLE[i];
            float s0 = SIN_TABLE[i];
            float c1 = COS_TABLE[i + 1];
            float s1 = SIN_TABLE[i + 1];

            int glow0 = sampleColor(f0, timePhase, rainbow, baseCol, secCol, 230, 1.1f);
            int glow1 = sampleColor(f1, timePhase, rainbow, baseCol, secCol, 230, 1.1f);

            // Wstęga 3D na TRIANGLES (płynna, wolna od glitchy sterowników GPU)
            buf.addVertex(mat, c0 * rInner, 0.002f, s0 * rInner).setColor(glow0);
            buf.addVertex(mat, c0 * rOuter, -0.002f, s0 * rOuter).setColor(glow0);
            buf.addVertex(mat, c1 * rOuter, -0.002f, s1 * rOuter).setColor(glow1);

            buf.addVertex(mat, c0 * rInner, 0.002f, s0 * rInner).setColor(glow0);
            buf.addVertex(mat, c0 * rOuter, -0.002f, s0 * rOuter).setColor(glow0);
            buf.addVertex(mat, c1 * rOuter, -0.002f, s1 * rOuter).setColor(glow1);

            buf.addVertex(mat, c0 * rInner, 0.002f, s0 * rInner).setColor(glow0);
            buf.addVertex(mat, c1 * rOuter, -0.002f, s1 * rOuter).setColor(glow1);
            buf.addVertex(mat, c1 * rInner, 0.002f, s1 * rInner).setColor(glow1);
        }

        // Levitating Outer Cyber Halo Ring (unoszący się zewnętrzny pierścień świetlny)
        float rHalo = radius * 1.09f;
        float haloHalf = 0.012f;
        float rHInner = rHalo - haloHalf;
        float rHOuter = rHalo + haloHalf;
        for (int i = 0; i < segs; i++) {
            float f0 = (float) i / (float) segs;
            float f1 = (float) (i + 1) / (float) segs;

            float c0 = COS_TABLE[i];
            float s0 = SIN_TABLE[i];
            float c1 = COS_TABLE[i + 1];
            float s1 = SIN_TABLE[i + 1];

            int hGlow0 = sampleColor(f0, -timePhase * 1.4f, rainbow, secCol, baseCol, 195, 1.25f);
            int hGlow1 = sampleColor(f1, -timePhase * 1.4f, rainbow, secCol, baseCol, 195, 1.25f);

            buf.addVertex(mat, c0 * rHInner, 0.025f, s0 * rHInner).setColor(hGlow0);
            buf.addVertex(mat, c0 * rHOuter, 0.015f, s0 * rHOuter).setColor(hGlow0);
            buf.addVertex(mat, c1 * rHOuter, 0.015f, s1 * rHOuter).setColor(hGlow1);

            buf.addVertex(mat, c0 * rHInner, 0.025f, s0 * rHInner).setColor(hGlow0);
            buf.addVertex(mat, c1 * rHOuter, 0.015f, s1 * rHOuter).setColor(hGlow1);
            buf.addVertex(mat, c1 * rHInner, 0.025f, s1 * rHInner).setColor(hGlow1);
        }
    }

    private static int sampleColor(float fraction, float timePhase, boolean rainbow,
                                  Color base, Color sec, int alpha, float boost) {
        if (rainbow) {
            float hue = (fraction + timePhase * 0.12f) % 1.0f;
            if (hue < 0) hue += 1.0f;
            int rgb = Mth.hsvToRgb(hue, 0.72f, 1.0f);
            int r = Math.min(255, (int) (((rgb >> 16) & 0xFF) * boost));
            int g = Math.min(255, (int) (((rgb >> 8) & 0xFF) * boost));
            int b = Math.min(255, (int) ((rgb & 0xFF) * boost));
            return (alpha << 24) | (r << 16) | (g << 8) | b;
        } else {
            float wave = (float) (Math.sin(fraction * 2.0 * Math.PI + timePhase) * 0.5 + 0.5);
            int r = Math.min(255, (int) (Mth.lerp(wave, base.getRed(), sec.getRed()) * boost));
            int g = Math.min(255, (int) (Mth.lerp(wave, base.getGreen(), sec.getGreen()) * boost));
            int b = Math.min(255, (int) (Mth.lerp(wave, base.getBlue(), sec.getBlue()) * boost));
            return (alpha << 24) | (r << 16) | (g << 8) | b;
        }
    }

    private static void emitCelestialApexPearl(BufferBuilder buf, Matrix4f mat, float yApex,
                                              boolean rainbow, Color baseCol, Color secCol, float timePhase) {
        float pearlRadius = 0.038f;
        float pearlY = yApex + 0.045f;
        int color = sampleColor(0.5f, timePhase * 1.5f, rainbow, secCol, baseCol, 240, 1.3f);
        int segs = 12;

        for (int i = 0; i < segs; i++) {
            double a0 = (i * Math.PI * 2.0) / segs;
            double a1 = ((i + 1) * Math.PI * 2.0) / segs;
            float x0 = (float) Math.cos(a0) * pearlRadius;
            float z0 = (float) Math.sin(a0) * pearlRadius;
            float x1 = (float) Math.cos(a1) * pearlRadius;
            float z1 = (float) Math.sin(a1) * pearlRadius;

            // Diamentowy oktaedr
            buf.addVertex(mat, 0.0f, pearlY + pearlRadius * 1.2f, 0.0f).setColor(color);
            buf.addVertex(mat, x0, pearlY, z0).setColor(color);
            buf.addVertex(mat, x1, pearlY, z1).setColor(color);

            buf.addVertex(mat, 0.0f, pearlY - pearlRadius * 1.2f, 0.0f).setColor(color);
            buf.addVertex(mat, x1, pearlY, z1).setColor(color);
            buf.addVertex(mat, x0, pearlY, z0).setColor(color);
        }
    }

    private static void emitVerletSilkRibbons(BufferBuilder buf, Matrix4f mat, Player player,
                                             float rOuter, boolean rainbow, Color baseCol, Color secCol, float timePhase) {
        // Dwie wstęgi jedwabne spływające z tylnej krawędzi kapelusza
        float ribbonWidth = 0.035f;
        float ribbonLength = 0.62f;
        int nodes = 7;
        float segLen = ribbonLength / nodes;

        float swing = player.walkAnimation.position() * 0.45f;
        float windX = (float) Math.sin(timePhase * 3.0f + swing) * 0.045f;
        float windZ = (float) Math.cos(timePhase * 2.2f) * 0.030f;

        // Kąty zaczepienia z tyłu stożka (-Z w układzie lokalnym po obrocie)
        float[] angles = {-2.45f, -0.69f};

        for (int side = 0; side < 2; side++) {
            float baseAngle = angles[side];
            float startX = (float) Math.cos(baseAngle) * rOuter * 0.95f;
            float startZ = (float) Math.sin(baseAngle) * rOuter * 0.95f;
            float startY = 0.0f;

            float curX = startX;
            float curY = startY;
            float curZ = startZ;

            for (int seg = 0; seg < nodes; seg++) {
                float frac = (float) seg / (float) nodes;
                float nextFrac = (float) (seg + 1) / (float) nodes;

                float nextY = curY - segLen * 0.85f;
                float trailDrift = (float) Math.pow(nextFrac, 1.4) * 0.22f;
                float nextX = curX + windX * (seg + 1) * 0.18f;
                float nextZ = curZ - trailDrift + windZ * (seg + 1) * 0.15f;

                int col0 = sampleColor(frac, timePhase, rainbow, baseCol, secCol, (int) ((1.0f - frac * 0.75f) * 220), 1.1f);
                int col1 = sampleColor(nextFrac, timePhase, rainbow, baseCol, secCol, (int) ((1.0f - nextFrac * 0.75f) * 220), 1.1f);

                float hw = ribbonWidth * (1.0f - frac * 0.45f);

                buf.addVertex(mat, curX - hw, curY, curZ).setColor(col0);
                buf.addVertex(mat, curX + hw, curY, curZ).setColor(col0);
                buf.addVertex(mat, nextX + hw, nextY, nextZ).setColor(col1);

                buf.addVertex(mat, curX - hw, curY, curZ).setColor(col0);
                buf.addVertex(mat, nextX + hw, nextY, nextZ).setColor(col1);
                buf.addVertex(mat, nextX - hw, nextY, nextZ).setColor(col1);

                curX = nextX;
                curY = nextY;
                curZ = nextZ;
            }
        }
    }
}
