package com.astra.revolution.visuals;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

/**
 * 👑 GOD-TIER CHINA HAT (Inspirowany MoonDLC class_1925).
 *
 * Renderowany bezpośrednio jako warstwa (RenderLayer) modelu gracza,
 * dzięki czemu kapelusz jest PERFEKCYJNIE zablokowany do kości głowy (model.head):
 * - Pochyla się razem z głową gracza przy rozglądaniu (Pitch & Yaw).
 * - Śledzi animacje skradania (Sneak), pływania (Swim) i latania na Elytrze.
 * - Wykrywa założony hełm i automatycznie podnosi się o 3cm (0.48F vs 0.45F), aby nie kolidować z pancerzem.
 * - Zawiera 3D wytłaczany rant (Extruded Outer Rim) z fizyczną głębią i obracającym się gradientem Chroma / Lilac-Cyan.
 * - Renderowany przez RenderType.lightning() dla pełnego, nieskazitelnego neonowego blasku (Zero czarnych cieni).
 */
public final class ChinaHatLayer extends RenderLayer<PlayerRenderState, PlayerModel> {

    private static boolean enabled = true;
    private static float radius = 0.65F;
    private static float height = 0.28F;
    private static float rotationSpeed = 1.6F;
    private static boolean chromaEnabled = true;

    // Zero-allocation tablica trygonometryczna dla 72 segmentów stożka
    private static final int SEGMENTS = 72;
    private static final float[] SIN_TABLE = new float[SEGMENTS + 1];
    private static final float[] COS_TABLE = new float[SEGMENTS + 1];

    static {
        for (int i = 0; i <= SEGMENTS; i++) {
            double angle = ((double) i / (double) SEGMENTS) * (Math.PI * 2.0);
            SIN_TABLE[i] = (float) Math.sin(angle);
            COS_TABLE[i] = (float) Math.cos(angle);
        }
    }

    public ChinaHatLayer(RenderLayerParent<PlayerRenderState, PlayerModel> parent) {
        super(parent);
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
                       PlayerRenderState state, float netHeadYaw, float headPitch) {
        if (!enabled || state.isSpectator || state.isInvisible) {
            return;
        }
        var s = baritone.Baritone.settings();
        if (s != null && !s.chinaHat.value) {
            return;
        }

        poseStack.pushPose();

        // 1. Zablokowanie transformacji bezpośrednio do kości głowy (head bone MoonDLC standard)
        this.getParentModel().getHead().translateAndRotate(poseStack);

        // 2. Automatyczny offset hełmu (0.52F z hełmem, 0.48F bez hełmu w przestrzeni modelu)
        boolean hasHelmet = state.headEquipment != null && !state.headEquipment.isEmpty();
        float yOffset = hasHelmet ? 0.52F : 0.48F;
        poseStack.translate(0.0F, -yOffset, 0.0F);

        // 3. Wyrównanie orientacji: 180° wokół X odwraca model Y (dół) na local Y (góra), a Z na tył
        poseStack.mulPose(Axis.XP.rotationDegrees(180.0F));

        Matrix4f mat = poseStack.last().pose();
        VertexConsumer buf = bufferSource.getBuffer(RenderType.lightning());

        float r = (s != null && s.chinaHatRadius != null) ? s.chinaHatRadius.value : radius;
        float h = (s != null && s.chinaHatHeight != null) ? s.chinaHatHeight.value : height;
        float speed = (s != null && s.chinaHatRotationSpeed != null) ? s.chinaHatRotationSpeed.value : rotationSpeed;
        boolean chroma = (s != null && s.chinaHatRainbow != null) ? s.chinaHatRainbow.value : chromaEnabled;

        long now = System.currentTimeMillis();
        float timePhase = ((now % 360000L) / 1000.0F) * speed;

        java.awt.Color baseCol = (s != null && s.chinaHatColor != null) ? s.chinaHatColor.value : null;
        java.awt.Color secCol = (s != null && s.chinaHatSecondaryColor != null) ? s.chinaHatSecondaryColor.value : null;

        // Kolor szczytu (Apex) - intensywny neonowy liliowy rdzeń
        int apexColor = 0xE0C77DFF;

        // 4. CZASZA KAPELUSZA (360-stopniowy stożek z płynnym gradientem)
        for (int i = 0; i < SEGMENTS; i++) {
            float f0 = (float) i / SEGMENTS;
            float f1 = (float) (i + 1) / SEGMENTS;

            float x0 = SIN_TABLE[i] * r;
            float z0 = COS_TABLE[i] * r;
            float x1 = SIN_TABLE[i + 1] * r;
            float z1 = COS_TABLE[i + 1] * r;

            int col0 = sampleRimColor(f0, timePhase, chroma, baseCol, secCol);
            int col1 = sampleRimColor(f1, timePhase, chroma, baseCol, secCol);

            // Trójkąt czaszy: baza (x0, 0, z0) -> baza (x1, 0, z1) -> szczyt (0, h, 0)
            buf.addVertex(mat, x0, 0.0F, z0).setColor(col0);
            buf.addVertex(mat, x1, 0.0F, z1).setColor(col1);
            buf.addVertex(mat, 0.0F, h, 0.0F).setColor(apexColor);
            buf.addVertex(mat, 0.0F, h, 0.0F).setColor(apexColor);
        }

        // 5. WYTŁACZANY 3D RANT (Extruded Outer Rim - unikalna technika MoonDLC class_1925)
        float rOuter = r + 0.014F;
        float rInner = r - 0.014F;
        float yRimBot = -0.012F;
        float yRimTop = 0.004F;

        for (int i = 0; i < SEGMENTS; i++) {
            float f0 = (float) i / SEGMENTS;
            float f1 = (float) (i + 1) / SEGMENTS;

            float c0 = COS_TABLE[i];
            float s0 = SIN_TABLE[i];
            float c1 = COS_TABLE[i + 1];
            float s1 = SIN_TABLE[i + 1];

            int glow0 = sampleRimColor(f0, timePhase + 0.1F, chroma, baseCol, secCol);
            int glow1 = sampleRimColor(f1, timePhase + 0.1F, chroma, baseCol, secCol);

            // Zewnętrzna pionowa ścianka rantu
            buf.addVertex(mat, s0 * rOuter, yRimBot, c0 * rOuter).setColor(glow0);
            buf.addVertex(mat, s0 * rOuter, yRimTop, c0 * rOuter).setColor(glow0);
            buf.addVertex(mat, s1 * rOuter, yRimTop, c1 * rOuter).setColor(glow1);
            buf.addVertex(mat, s1 * rOuter, yRimBot, c1 * rOuter).setColor(glow1);

            // Dolna pozioma ścianka rantu
            buf.addVertex(mat, s0 * rInner, yRimBot, c0 * rInner).setColor(glow0);
            buf.addVertex(mat, s0 * rOuter, yRimBot, c0 * rOuter).setColor(glow0);
            buf.addVertex(mat, s1 * rOuter, yRimBot, c1 * rOuter).setColor(glow1);
            buf.addVertex(mat, s1 * rInner, yRimBot, c1 * rInner).setColor(glow1);
        }

        // 6. DIAMENTOWY KRYSZTAŁ NA SZCZYCIE (Apex Diamond Gem)
        float gemR = 0.035F;
        float gemY = h + 0.035F;
        int gemCol = 0xFFFFFFFF;
        for (int i = 0; i < 8; i++) {
            double a0 = (i * Math.PI * 2.0) / 8.0;
            double a1 = ((i + 1) * Math.PI * 2.0) / 8.0;
            float gx0 = (float) Math.cos(a0) * gemR;
            float gz0 = (float) Math.sin(a0) * gemR;
            float gx1 = (float) Math.cos(a1) * gemR;
            float gz1 = (float) Math.sin(a1) * gemR;

            buf.addVertex(mat, 0.0F, gemY + gemR, 0.0F).setColor(gemCol);
            buf.addVertex(mat, gx0, gemY, gz0).setColor(apexColor);
            buf.addVertex(mat, gx1, gemY, gz1).setColor(apexColor);
            buf.addVertex(mat, gx1, gemY, gz1).setColor(apexColor);

            buf.addVertex(mat, 0.0F, gemY - gemR, 0.0F).setColor(gemCol);
            buf.addVertex(mat, gx1, gemY, gz1).setColor(apexColor);
            buf.addVertex(mat, gx0, gemY, gz0).setColor(apexColor);
            buf.addVertex(mat, gx0, gemY, gz0).setColor(apexColor);
        }

        // 7. JEDWABNE WSTĘGI SPŁYWAJĄCE Z TYŁU (Silk Streamers)
        renderBackSilkStreamers(mat, buf, r, timePhase);

        poseStack.popPose();
    }

    private static void renderBackSilkStreamers(Matrix4f mat, VertexConsumer buf, float r, float time) {
        float ribbonW = 0.032F;
        float ribbonL = 0.52F;
        int nodes = 6;
        float step = ribbonL / nodes;

        float wind = Mth.sin(time * 3.5F) * 0.04F;

        // Dwa symetryczne punkty zaczepienia z tyłu ronda kapelusza (+Z = backward)
        float angle1 = (float) (Math.PI * 0.5 - 0.45);
        float angle2 = (float) (Math.PI * 0.5 + 0.45);
        float[] angles = {angle1, angle2};

        for (float angle : angles) {
            float startX = (float) Math.cos(angle) * r * 0.95F;
            float startZ = (float) Math.sin(angle) * r * 0.95F;
            float curX = startX;
            float curY = 0.0F;
            float curZ = startZ;

            for (int seg = 0; seg < nodes; seg++) {
                float frac = (float) seg / nodes;
                float nextFrac = (float) (seg + 1) / nodes;

                float nextY = curY - step * 0.9F;
                float nextX = curX + wind * (seg + 1) * 0.15F;
                float nextZ = curZ + (float) Math.pow(nextFrac, 1.3) * 0.16F;

                int a0 = (int) ((1.0F - frac * 0.7F) * 210.0F);
                int a1 = (int) ((1.0F - nextFrac * 0.7F) * 210.0F);
                int col0 = (a0 << 24) | 0x00C77DFF;
                int col1 = (a1 << 24) | 0x0000F0FF;

                float hw = ribbonW * (1.0F - frac * 0.4F);
                buf.addVertex(mat, curX - hw, curY, curZ).setColor(col0);
                buf.addVertex(mat, curX + hw, curY, curZ).setColor(col0);
                buf.addVertex(mat, nextX + hw, nextY, nextZ).setColor(col1);
                buf.addVertex(mat, nextX - hw, nextY, nextZ).setColor(col1);

                curX = nextX;
                curY = nextY;
                curZ = nextZ;
            }
        }
    }

    private static int sampleRimColor(float frac, float timePhase, boolean chroma,
                                      java.awt.Color base, java.awt.Color sec) {
        if (chroma) {
            float hue = (frac + timePhase * 0.14F) % 1.0F;
            int rgb = Mth.hsvToRgb(hue, 0.75F, 1.0F);
            return 0xD8000000 | (rgb & 0x00FFFFFF);
        } else if (base != null && sec != null) {
            float wave = (Mth.sin(frac * (float) (Math.PI * 2.0) + timePhase) + 1.0F) * 0.5F;
            int r = (int) Mth.lerp(wave, (float) base.getRed(), (float) sec.getRed());
            int g = (int) Mth.lerp(wave, (float) base.getGreen(), (float) sec.getGreen());
            int b = (int) Mth.lerp(wave, (float) base.getBlue(), (float) sec.getBlue());
            return 0xD8000000 | (r << 16) | (g << 8) | b;
        } else {
            // Neon Lilac & Cyber Cyan
            float wave = (Mth.sin(frac * (float) (Math.PI * 2.0) + timePhase) + 1.0F) * 0.5F;
            int r = (int) Mth.lerp(wave, 199.0F, 0.0F);
            int g = (int) Mth.lerp(wave, 125.0F, 240.0F);
            int b = 255;
            return 0xD8000000 | (r << 16) | (g << 8) | b;
        }
    }
}
