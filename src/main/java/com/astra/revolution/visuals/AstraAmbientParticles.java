package com.astra.revolution.visuals;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.Random;

/**
 * 3D Ambient World Particles System.
 * Renders floating, drifting atmospheric particles around the player in two distinct modes:
 * Mode 1: GEOMETRIC (Glowing Cyber-Cubes & Stardust Diamonds)
 * Mode 2: MASCOTS (Adorable Mini Tung Tung Sahurki, Mini Capybarki, and Mini Balleriny Capucciny)
 * Mode 3: BOTH (Mega Mix)
 */
public final class AstraAmbientParticles {

    public enum ParticleMode {
        MASCOTS("Mini Maskotki (Sahurki, Capybary, Baleriny)"),
        GEOMETRIC("Cyber Stardust (Kwadraty/Diamenty)"),
        BOTH("Wszystkie Razem (Mega Mix)");

        private final String displayName;

        ParticleMode(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    private static ParticleMode currentMode = ParticleMode.MASCOTS;

    public static ParticleMode getCurrentMode() {
        return currentMode;
    }

    public static void setCurrentMode(ParticleMode mode) {
        if (mode != null) {
            currentMode = mode;
        }
    }

    public static int getModeIndex() {
        return currentMode.ordinal();
    }

    public static void setModeIndex(int index) {
        ParticleMode[] values = ParticleMode.values();
        if (index >= 0 && index < values.length) {
            currentMode = values[index];
        }
    }

    private static final int PARTICLE_COUNT = 72;
    private static AmbientParticle[] particles = null;
    private static final Random RAND = new Random(424242L);

    public static final class AmbientParticle {
        public double relX, relY, relZ;
        public double velX, velY, velZ;
        public float rotX, rotY, rotZ;
        public float rotSpeedX, rotSpeedY, rotSpeedZ;
        public float scale;
        public int type; // 0: Cube, 1: Diamond, 2: Sahur, 3: Capybara, 4: Ballerina
        public int baseColor;
        public float phase;

        public AmbientParticle(Random rand) {
            respawn(rand, true);
        }

        public void respawn(Random rand, boolean initial) {
            double radius = 3.5 + rand.nextDouble() * 12.5;
            double angle = rand.nextDouble() * Math.PI * 2.0;
            this.relX = Math.cos(angle) * radius;
            this.relZ = Math.sin(angle) * radius;
            this.relY = initial ? (rand.nextDouble() * 9.0 - 1.5) : -2.0;
            this.velX = (rand.nextDouble() - 0.5) * 0.012;
            this.velZ = (rand.nextDouble() - 0.5) * 0.012;
            this.velY = 0.007 + rand.nextDouble() * 0.015;
            this.rotX = rand.nextFloat() * 360.0F;
            this.rotY = rand.nextFloat() * 360.0F;
            this.rotZ = rand.nextFloat() * 360.0F;
            this.rotSpeedX = (rand.nextFloat() - 0.5F) * 2.4F;
            this.rotSpeedY = (rand.nextFloat() - 0.5F) * 3.2F;
            this.rotSpeedZ = (rand.nextFloat() - 0.5F) * 2.0F;
            this.scale = 0.22F + rand.nextFloat() * 0.16F;
            this.type = rand.nextInt(5);
            this.phase = rand.nextFloat() * (float) Math.PI * 2.0F;

            int[] palette = {0xFFC77DFF, 0xFF00F0FF, 0xFFFFD700, 0xFFFF69B4, 0xFF00FF88, 0xFFFF4500};
            this.baseColor = palette[rand.nextInt(palette.length)];
        }
    }

    private static void ensureInitialized() {
        if (particles == null) {
            particles = new AmbientParticle[PARTICLE_COUNT];
            for (int i = 0; i < PARTICLE_COUNT; i++) {
                particles[i] = new AmbientParticle(RAND);
            }
        }
    }

    /**
     * Renders ambient 3D particles around the player in the world.
     */
    public static void renderParticles(PoseStack poseStack, Player player, float partialTick) {
        ensureInitialized();
        if (player == null) return;

        Vec3 playerPos = player.position();
        long now = System.currentTimeMillis();
        float time = (now % 100000L) / 1000.0F;

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder cubesBuf = null;
        BufferBuilder diamondsBuf = null;

        for (int i = 0; i < PARTICLE_COUNT; i++) {
            AmbientParticle p = particles[i];

            // Filter by selected mode
            boolean isMascot = p.type >= 2;
            if (currentMode == ParticleMode.GEOMETRIC && isMascot) {
                p.type = i % 2;
                isMascot = false;
            } else if (currentMode == ParticleMode.MASCOTS && !isMascot) {
                p.type = 2 + (i % 3);
                isMascot = true;
            }

            // Simulate drift & floating
            p.relX += p.velX + (Math.cos(p.relY * 0.35D + time) - Math.sin(p.relZ * 0.35D - 0.7D * time)) * 0.005D;
            p.relY += p.velY + (Math.sin(p.relZ * 0.35D + 0.8D * time) - Math.cos(p.relX * 0.35D - time)) * 0.004D;
            p.relZ += p.velZ + (Math.cos(p.relX * 0.35D + 0.6D * time) - Math.sin(p.relY * 0.35D - 0.9D * time)) * 0.005D;

            // Kinetic Repulsion Field: cząstki płynnie rozstępują się przed biegnącym graczem (R = 2.2m)
            double pDistSq = p.relX * p.relX + p.relY * p.relY + p.relZ * p.relZ;
            if (pDistSq < 2.2D * 2.2D && pDistSq > 0.001D) {
                double pDist = Math.sqrt(pDistSq);
                double push = Math.pow(1.0D - (pDist / 2.2D), 2.0D) * 0.038D;
                p.relX += (p.relX / pDist) * push;
                p.relY += (p.relY / pDist) * push * 0.6D;
                p.relZ += (p.relZ / pDist) * push;
            }

            p.rotX += p.rotSpeedX;
            p.rotY += p.rotSpeedY;
            p.rotZ += p.rotSpeedZ;

            // Boundary wrapping
            double distSq = p.relX * p.relX + p.relZ * p.relZ;
            if (p.relY > 9.5D || distSq > 18.0D * 18.0D) {
                p.respawn(RAND, false);
            }

            double worldX = playerPos.x + p.relX;
            double worldY = playerPos.y + p.relY;
            double worldZ = playerPos.z + p.relZ;

            // Distance fade (fade out when closer than 1.6 blocks to prevent camera clipping, and fade out near horizon)
            double camDist = Math.sqrt(distSq + p.relY * p.relY);
            float alpha = 1.0F;
            if (camDist < 1.6D) {
                alpha = (float) Math.max(0.0D, (camDist - 0.5D) / 1.1D);
            } else if (camDist > 12.0D) {
                alpha = (float) Math.max(0.0D, 1.0D - (camDist - 12.0D) / 5.0D);
            }
            if (alpha <= 0.01F) continue;

            poseStack.pushPose();
            poseStack.translate(worldX, worldY, worldZ);

            if (isMascot) {
                // Mascots gently bob and rotate softly
                float bobY = (float) Math.sin(time * 2.2F + p.phase) * 0.08F;
                poseStack.translate(0.0F, bobY, 0.0F);
                poseStack.mulPose(Axis.YP.rotationDegrees(p.rotY + time * 24.0F));
            } else {
                // Geometric cubes/diamonds tumble in 3D
                poseStack.mulPose(Axis.XP.rotationDegrees(p.rotX));
                poseStack.mulPose(Axis.YP.rotationDegrees(p.rotY));
                poseStack.mulPose(Axis.ZP.rotationDegrees(p.rotZ));
            }

            poseStack.scale(p.scale, p.scale, p.scale);

            switch (p.type) {
                case 0 -> {
                    if (cubesBuf == null) {
                        cubesBuf = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
                    }
                    emitMiniCube(cubesBuf, poseStack.last().pose(), 0.35F, p.baseColor, alpha);
                }
                case 1 -> {
                    if (diamondsBuf == null) {
                        diamondsBuf = tesselator.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
                    }
                    emitMiniDiamond(diamondsBuf, poseStack.last().pose(), 0.38F, p.baseColor, alpha);
                }
                case 2 -> drawMiniTungTung(poseStack, time + p.phase, alpha);
                case 3 -> drawMiniCapybara(poseStack, time + p.phase, alpha);
                case 4 -> drawMiniBallerina(poseStack, time + p.phase, alpha);
            }

            poseStack.popPose();
        }

        // Flush batched geometric particles
        if (cubesBuf != null) {
            MeshData mesh = cubesBuf.build();
            if (mesh != null) {
                BufferUploader.drawWithShader(mesh);
                mesh.close();
            }
        }
        if (diamondsBuf != null) {
            MeshData mesh = diamondsBuf.build();
            if (mesh != null) {
                BufferUploader.drawWithShader(mesh);
                mesh.close();
            }
        }
    }

    /**
     * Type 0: 3D Glowing Cyber Cube (Batched Quad Emitter with Directional Diffuse)
     */
    private static void emitMiniCube(BufferBuilder b, Matrix4f m, float s, int colorArgb, float alphaMult) {
        float h = s * 0.5F;
        int a = (int) (((colorArgb >> 24) & 0xFF) * 0.72F * alphaMult);
        int r = (colorArgb >> 16) & 0xFF;
        int g = (colorArgb >> 8) & 0xFF;
        int bCol = colorArgb & 0xFF;

        int rTop = r, gTop = g, bTop = bCol;
        int rBot = (int) (r * 0.55F), gBot = (int) (g * 0.55F), bBot = (int) (bCol * 0.55F);
        int rSide = (int) (r * 0.80F), gSide = (int) (g * 0.80F), bSide = (int) (bCol * 0.80F);

        // Bottom (-Y)
        b.addVertex(m, -h, -h, -h).setColor(rBot, gBot, bBot, a);
        b.addVertex(m,  h, -h, -h).setColor(rBot, gBot, bBot, a);
        b.addVertex(m,  h, -h,  h).setColor(rBot, gBot, bBot, a);
        b.addVertex(m, -h, -h,  h).setColor(rBot, gBot, bBot, a);

        // Top (+Y)
        b.addVertex(m, -h,  h,  h).setColor(rTop, gTop, bTop, a);
        b.addVertex(m,  h,  h,  h).setColor(rTop, gTop, bTop, a);
        b.addVertex(m,  h,  h, -h).setColor(rTop, gTop, bTop, a);
        b.addVertex(m, -h,  h, -h).setColor(rTop, gTop, bTop, a);

        // North (-Z)
        b.addVertex(m, -h,  h, -h).setColor(rSide, gSide, bSide, a);
        b.addVertex(m,  h,  h, -h).setColor(rSide, gSide, bSide, a);
        b.addVertex(m,  h, -h, -h).setColor(rSide, gSide, bSide, a);
        b.addVertex(m, -h, -h, -h).setColor(rSide, gSide, bSide, a);

        // South (+Z)
        b.addVertex(m, -h, -h,  h).setColor(rSide, gSide, bSide, a);
        b.addVertex(m,  h, -h,  h).setColor(rSide, gSide, bSide, a);
        b.addVertex(m,  h,  h,  h).setColor(rSide, gSide, bSide, a);
        b.addVertex(m, -h,  h,  h).setColor(rSide, gSide, bSide, a);

        // West (-X)
        b.addVertex(m, -h, -h, -h).setColor(rSide, gSide, bSide, a);
        b.addVertex(m, -h, -h,  h).setColor(rSide, gSide, bSide, a);
        b.addVertex(m, -h,  h,  h).setColor(rSide, gSide, bSide, a);
        b.addVertex(m, -h,  h, -h).setColor(rSide, gSide, bSide, a);

        // East (+X)
        b.addVertex(m,  h, -h,  h).setColor(rSide, gSide, bSide, a);
        b.addVertex(m,  h, -h, -h).setColor(rSide, gSide, bSide, a);
        b.addVertex(m,  h,  h, -h).setColor(rSide, gSide, bSide, a);
        b.addVertex(m,  h,  h,  h).setColor(rSide, gSide, bSide, a);
    }

    /**
     * Type 1: 3D Stardust Diamond (Batched Octahedron Emitter)
     */
    private static void emitMiniDiamond(BufferBuilder bb, Matrix4f mat, float s, int colorArgb, float alphaMult) {
        float rBase = s * 0.45F;
        float hHalf = s * 0.65F;
        int a = (int) (((colorArgb >> 24) & 0xFF) * 0.80F * alphaMult);
        int r = (colorArgb >> 16) & 0xFF;
        int g = (colorArgb >> 8) & 0xFF;
        int b = colorArgb & 0xFF;
        int col = (a << 24) | (r << 16) | (g << 8) | b;
        int colCenter = (Math.min(255, (int)(a * 1.25F)) << 24) | 0x00FFFFFF;

        float[][] pts = {{-rBase, 0, 0}, {0, 0, rBase}, {rBase, 0, 0}, {0, 0, -rBase}};
        for (int i = 0; i < 4; i++) {
            float[] p1 = pts[i];
            float[] p2 = pts[(i + 1) % 4];
            bb.addVertex(mat, 0,  hHalf, 0).setColor(colCenter);
            bb.addVertex(mat, p1[0], 0, p1[2]).setColor(col);
            bb.addVertex(mat, p2[0], 0, p2[2]).setColor(col);
        }

        for (int i = 0; i < 4; i++) {
            float[] p1 = pts[i];
            float[] p2 = pts[(i + 1) % 4];
            bb.addVertex(mat, 0, -hHalf, 0).setColor(colCenter);
            bb.addVertex(mat, p2[0], 0, p2[2]).setColor(col);
            bb.addVertex(mat, p1[0], 0, p1[2]).setColor(col);
        }
    }

    /**
     * Type 2: Adorable Mini Tung Tung Sahur (Kentongan Drum with Peci Cap)
     */
    private static void drawMiniTungTung(PoseStack ps, float time, float alphaMult) {
        int alpha = (int) (240 * alphaMult);
        int teak = (alpha << 24) | 0x00BA6A28;
        int gold = (alpha << 24) | 0x00FFD700;
        int peci = (alpha << 24) | 0x001A1820;
        int white = (alpha << 24) | 0x00FFFFFF;
        int pupil = (alpha << 24) | 0x00000000;

        // Wooden body
        drawCylinder(ps, 0, -0.32F, 0, 0.22F, 0.22F, 0.64F, 12, teak);
        // Golden hoops
        drawCylinder(ps, 0, 0.22F, 0, 0.23F, 0.23F, 0.05F, 12, gold);
        drawCylinder(ps, 0, -0.27F, 0, 0.23F, 0.23F, 0.05F, 12, gold);
        // Top Peci Cap
        drawCylinder(ps, 0, 0.32F, 0, 0.17F, 0.15F, 0.14F, 12, peci);
        drawDisk(ps, 0.0F, 0.46F, 0.0F, 0.15F, 12, peci);

        // Googly eyes on front (+Z)
        drawBox(ps, -0.11F, 0.06F, 0.225F, -0.03F, 0.14F, 0.235F, white);
        drawBox(ps,  0.03F, 0.06F, 0.225F,  0.11F, 0.14F, 0.235F, white);
        drawBox(ps, -0.08F, 0.07F, 0.236F, -0.04F, 0.11F, 0.245F, pupil);
        drawBox(ps,  0.06F, 0.07F, 0.236F,  0.10F, 0.11F, 0.245F, pupil);
    }

    /**
     * Type 3: Adorable Mini Capybara with Citrus Yuzu/Orange on Head
     */
    private static void drawMiniCapybara(PoseStack ps, float time, float alphaMult) {
        int alpha = (int) (240 * alphaMult);
        int fur = (alpha << 24) | 0x00A06834;
        int muzzle = (alpha << 24) | 0x0078441A;
        int orange = (alpha << 24) | 0x00FF8C00;
        int leaf = (alpha << 24) | 0x0022AA22;
        int black = (alpha << 24) | 0x00111111;

        // Capybara body box
        drawBox(ps, -0.16F, -0.16F, -0.22F, 0.16F, 0.12F, 0.20F, fur);
        // Head in front (+Z)
        drawBox(ps, -0.12F, 0.02F, 0.12F, 0.12F, 0.24F, 0.34F, fur);
        // Snout / muzzle
        drawBox(ps, -0.09F, 0.03F, 0.28F, 0.09F, 0.15F, 0.38F, muzzle);
        // Ears
        drawBox(ps, -0.15F, 0.20F, 0.16F, -0.11F, 0.27F, 0.21F, muzzle);
        drawBox(ps,  0.11F, 0.20F, 0.16F,  0.15F, 0.27F, 0.21F, muzzle);

        // Zen sleepy eyes
        drawBox(ps, -0.13F, 0.16F, 0.24F, -0.115F, 0.18F, 0.29F, black);
        drawBox(ps,  0.115F, 0.16F, 0.24F, 0.13F, 0.18F, 0.29F, black);

        // Słynna mała mandarynka / owoc yuzu na głowie!
        drawCylinder(ps, 0, 0.24F, 0.23F, 0.07F, 0.06F, 0.09F, 8, orange);
        drawDisk(ps, 0.0F, 0.33F, 0.23F, 0.06F, 8, orange);
        // Green leaf
        drawBox(ps, -0.02F, 0.33F, 0.23F, 0.05F, 0.36F, 0.27F, leaf);
    }

    /**
     * Type 4: Adorable Mini Ballerina Capuccina (Coffee Cup, Tutu, Crema, Cherry)
     */
    private static void drawMiniBallerina(PoseStack ps, float time, float alphaMult) {
        int alpha = (int) (240 * alphaMult);
        int cupWhite = (alpha << 24) | 0x00F8F5FF;
        int tutuPink = (alpha << 24) | 0x00FF99CC;
        int crema = (alpha << 24) | 0x006E3B18;
        int cream = (alpha << 24) | 0x00FFF5E6;
        int cherry = (alpha << 24) | 0x00E60039;
        int gold = (alpha << 24) | 0x00FFD700;

        // Porcelain cup
        drawCylinder(ps, 0, -0.05F, 0, 0.14F, 0.22F, 0.30F, 12, cupWhite);
        drawCylinder(ps, 0, 0.23F, 0, 0.225F, 0.225F, 0.03F, 12, gold);
        // Tutu skirt
        drawCylinder(ps, 0, -0.20F, 0, 0.29F, 0.15F, 0.16F, 12, tutuPink);
        // Coffee crema layer
        drawDisk(ps, 0.0F, 0.24F, 0.0F, 0.21F, 12, crema);
        // Whipped cream swirl
        drawCylinder(ps, 0, 0.24F, 0, 0.15F, 0.08F, 0.11F, 10, cream);
        drawDisk(ps, 0.0F, 0.35F, 0.0F, 0.08F, 10, cream);
        // Ruby cocktail cherry on top!
        drawCylinder(ps, 0, 0.35F, 0, 0.05F, 0.03F, 0.07F, 8, cherry);
        drawDisk(ps, 0.0F, 0.42F, 0.0F, 0.04F, 8, cherry);

        // Kawaii eyes on front
        drawBox(ps, -0.09F, 0.08F, 0.18F, -0.04F, 0.13F, 0.20F, (alpha << 24) | 0x00222222);
        drawBox(ps,  0.04F, 0.08F, 0.18F,  0.09F, 0.13F, 0.20F, (alpha << 24) | 0x00222222);
        // Blush cheeks
        drawBox(ps, -0.11F, 0.04F, 0.17F, -0.06F, 0.07F, 0.19F, (alpha << 24) | 0x00FF6699);
        drawBox(ps,  0.06F, 0.04F, 0.17F,  0.11F, 0.07F, 0.19F, (alpha << 24) | 0x00FF6699);
    }

    private static void drawCylinder(PoseStack ps, float x, float y, float z, float rBot, float rTop, float h, int segs, int color) {
        Matrix4f mat = ps.last().pose();
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i <= segs; i++) {
            float a = (float) (i * Math.PI * 2.0D / segs);
            float cos = Mth.cos(a), sin = Mth.sin(a);
            buf.addVertex(mat, x + cos * rTop, y + h, z + sin * rTop).setColor(color);
            buf.addVertex(mat, x + cos * rBot, y, z + sin * rBot).setColor(color);
        }
        MeshData mesh = buf.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
            mesh.close();
        }
    }

    private static void drawDisk(PoseStack ps, float x, float y, float z, float radius, int segs, int color) {
        Matrix4f mat = ps.last().pose();
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        buf.addVertex(mat, x, y, z).setColor(color);
        for (int i = 0; i <= segs; i++) {
            float a = (float) (i * Math.PI * 2.0D / segs);
            buf.addVertex(mat, x + Mth.cos(a) * radius, y, z + Mth.sin(a) * radius).setColor(color);
        }
        MeshData mesh = buf.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
            mesh.close();
        }
    }

    private static void drawBox(PoseStack ps, float x1, float y1, float z1, float x2, float y2, float z2, int color) {
        Matrix4f m = ps.last().pose();
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        b.addVertex(m, x1, y1, z2).setColor(color); b.addVertex(m, x2, y1, z2).setColor(color);
        b.addVertex(m, x2, y2, z2).setColor(color); b.addVertex(m, x1, y2, z2).setColor(color);
        b.addVertex(m, x2, y1, z1).setColor(color); b.addVertex(m, x1, y1, z1).setColor(color);
        b.addVertex(m, x1, y2, z1).setColor(color); b.addVertex(m, x2, y2, z1).setColor(color);
        b.addVertex(m, x1, y1, z1).setColor(color); b.addVertex(m, x1, y1, z2).setColor(color);
        b.addVertex(m, x1, y2, z2).setColor(color); b.addVertex(m, x1, y2, z1).setColor(color);
        b.addVertex(m, x2, y1, z2).setColor(color); b.addVertex(m, x2, y1, z1).setColor(color);
        b.addVertex(m, x2, y2, z1).setColor(color); b.addVertex(m, x2, y2, z2).setColor(color);
        b.addVertex(m, x1, y2, z2).setColor(color); b.addVertex(m, x2, y2, z2).setColor(color);
        b.addVertex(m, x2, y2, z1).setColor(color); b.addVertex(m, x1, y2, z1).setColor(color);
        b.addVertex(m, x1, y1, z1).setColor(color); b.addVertex(m, x2, y1, z1).setColor(color);
        b.addVertex(m, x2, y1, z2).setColor(color); b.addVertex(m, x1, y1, z2).setColor(color);
        MeshData mesh = b.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
            mesh.close();
        }
    }
}
