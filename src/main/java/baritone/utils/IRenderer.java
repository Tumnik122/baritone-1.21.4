/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.utils;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.utils.accessor.IEntityRenderManager;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CoreShaders;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.awt.*;

public interface IRenderer {

    Tesselator tessellator = Tesselator.getInstance();
    IEntityRenderManager renderManager = (IEntityRenderManager) Minecraft.getInstance().getEntityRenderDispatcher();
    TextureManager textureManager = Minecraft.getInstance().getTextureManager();
    Settings settings = BaritoneAPI.getSettings();

    float[] color = new float[]{1.0F, 1.0F, 1.0F, 255.0F};

    static float getTime() {
        return (System.currentTimeMillis() % 100000L) / 1000.0f;
    }

    static void glColor(Color color, float alpha) {
        float[] colorComponents = color.getColorComponents(null);
        IRenderer.color[0] = colorComponents[0];
        IRenderer.color[1] = colorComponents[1];
        IRenderer.color[2] = colorComponents[2];
        IRenderer.color[3] = alpha;
    }

    static Color blendColors(Color c1, Color c2, float ratio) {
        ratio = Math.max(0, Math.min(1, ratio));
        int r = (int)(c1.getRed() * (1 - ratio) + c2.getRed() * ratio);
        int g = (int)(c1.getGreen() * (1 - ratio) + c2.getGreen() * ratio);
        int b = (int)(c1.getBlue() * (1 - ratio) + c2.getBlue() * ratio);
        int a = (int)(c1.getAlpha() * (1 - ratio) + c2.getAlpha() * ratio);
        return new Color(r, g, b, a);
    }

    static Color getRainbowColor(float offset) {
        float hue = (getTime() * 0.15f + offset) % 1.0f;
        return Color.getHSBColor(hue, 0.8f, 1.0f);
    }

    static Color getPulseColor(Color baseColor, float speed) {
        float pulse = 0.6f + 0.4f * Mth.sin(getTime() * speed);
        return new Color(
            (int)(baseColor.getRed() * pulse),
            (int)(baseColor.getGreen() * pulse),
            (int)(baseColor.getBlue() * pulse),
            baseColor.getAlpha()
        );
    }

    static BufferBuilder startLines(Color color, float alpha, float lineWidth, boolean ignoreDepth) {
        return startLines(color, alpha, lineWidth, ignoreDepth, false);
    }

    static BufferBuilder startLines(Color color, float alpha, float lineWidth, boolean ignoreDepth, boolean additiveHalo) {
        RenderSystem.enableBlend();
        if (additiveHalo && settings.renderShaderEffects.value) {
            RenderSystem.blendFuncSeparate(
                    GlStateManager.SourceFactor.SRC_ALPHA,
                    GlStateManager.DestFactor.ONE,
                    GlStateManager.SourceFactor.ONE,
                    GlStateManager.DestFactor.ONE
            );
        } else {
            RenderSystem.blendFuncSeparate(
                    GlStateManager.SourceFactor.SRC_ALPHA,
                    GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                    GlStateManager.SourceFactor.ONE,
                    GlStateManager.DestFactor.ZERO
            );
        }
        glColor(color, alpha);
        RenderSystem.lineWidth(lineWidth);
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();

        if (ignoreDepth) {
            RenderSystem.disableDepthTest();
        }
        if (settings.renderShaderEffects.value) {
            try {
                RenderSystem.setShader(BaritoneShaderPrograms.PATH_GLOW);
            } catch (Throwable t) {
                settings.renderShaderEffects.value = false;
                RenderSystem.setShader(CoreShaders.RENDERTYPE_LINES);
            }
        } else {
            RenderSystem.setShader(CoreShaders.RENDERTYPE_LINES);
        }
        return tessellator.begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
    }

    static BufferBuilder startLines(Color color, float lineWidth, boolean ignoreDepth) {
        return startLines(color, .4f, lineWidth, ignoreDepth);
    }

    static void endLines(BufferBuilder bufferBuilder, boolean ignoredDepth) {
        MeshData meshData = bufferBuilder.build();
        if (meshData != null) {
            BufferUploader.drawWithShader(meshData);
        }

        if (ignoredDepth) {
            RenderSystem.enableDepthTest();
        }

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    static void emitLine(BufferBuilder bufferBuilder, PoseStack stack, double x1, double y1, double z1, double x2, double y2, double z2) {
        final double dx = x2 - x1;
        final double dy = y2 - y1;
        final double dz = z2 - z1;

        final double invMag = 1.0 / Math.sqrt(dx * dx + dy * dy + dz * dz);
        final float nx = (float) (dx * invMag);
        final float ny = (float) (dy * invMag);
        final float nz = (float) (dz * invMag);

        emitLine(bufferBuilder, stack, x1, y1, z1, x2, y2, z2, nx, ny, nz);
    }

    static void emitLine(BufferBuilder bufferBuilder, PoseStack stack, double x1, double y1, double z1, double x2, double y2, double z2, float nx, float ny, float nz) {
        PoseStack.Pose pose = stack.last();
        bufferBuilder.addVertex(pose, (float) x1, (float) y1, (float) z1).setColor(color[0], color[1], color[2], color[3]).setNormal(pose, nx, ny, nz);
        bufferBuilder.addVertex(pose, (float) x2, (float) y2, (float) z2).setColor(color[0], color[1], color[2], color[3]).setNormal(pose, nx, ny, nz);
    }

    static void emitAABB(BufferBuilder bufferBuilder, PoseStack stack, AABB aabb) {
        emitAABB(bufferBuilder, stack, aabb, .005D);
    }

    static void emitAABB(BufferBuilder bufferBuilder, PoseStack stack, AABB aabb, double expand) {
        AABB toDraw = aabb.inflate(expand).move(-renderManager.renderPosX(), -renderManager.renderPosY(), -renderManager.renderPosZ());

        double minX = toDraw.minX;
        double minY = toDraw.minY;
        double minZ = toDraw.minZ;
        double maxX = toDraw.maxX;
        double maxY = toDraw.maxY;
        double maxZ = toDraw.maxZ;
        emitAABB(bufferBuilder, stack, minX, minY, minZ, maxX, maxY, maxZ);
    }

    static void emitAABB(BufferBuilder bufferBuilder, PoseStack stack, double x1, double y1, double z1, double x2, double y2, double z2) {
        emitLine(bufferBuilder, stack, x1, y1, z1, x2, y1, z1, 0, -1, 0);
        emitLine(bufferBuilder, stack, x2, y1, z1, x2, y1, z2, 1, 0, 0);
        emitLine(bufferBuilder, stack, x2, y1, z2, x1, y1, z2, 0, 0, 1);
        emitLine(bufferBuilder, stack, x1, y1, z2, x1, y1, z1, -1, 0, 0);
        emitLine(bufferBuilder, stack, x1, y1, z1, x1, y2, z1, 0, 0, -1);
        emitLine(bufferBuilder, stack, x2, y1, z1, x2, y2, z1, 0, 0, -1);
        emitLine(bufferBuilder, stack, x2, y1, z2, x2, y2, z2, 0, 0, 1);
        emitLine(bufferBuilder, stack, x1, y1, z2, x1, y2, z2, 0, 0, 1);
        emitLine(bufferBuilder, stack, x1, y2, z1, x2, y2, z1, 0, 1, 0);
        emitLine(bufferBuilder, stack, x2, y2, z1, x2, y2, z2, 1, 0, 0);
        emitLine(bufferBuilder, stack, x2, y2, z2, x1, y2, z2, 0, 0, 1);
        emitLine(bufferBuilder, stack, x1, y2, z2, x1, y2, z1, -1, 0, 0);
    }

    static void emitLine(BufferBuilder bufferBuilder, PoseStack stack, Vec3 start, Vec3 end) {
        double vpX = renderManager.renderPosX();
        double vpY = renderManager.renderPosY();
        double vpZ = renderManager.renderPosZ();
        emitLine(bufferBuilder, stack, start.x - vpX, start.y - vpY, start.z - vpZ, end.x - vpX, end.y - vpY, end.z - vpZ);
    }

    static BufferBuilder startFilled(Color color, float alpha, boolean ignoreDepth, boolean holo) {
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ZERO
        );
        glColor(color, alpha);
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();

        if (ignoreDepth) {
            RenderSystem.disableDepthTest();
        }
        if (settings.renderShaderEffects.value) {
            try {
                RenderSystem.setShader(holo ? BaritoneShaderPrograms.GOAL_HOLO : BaritoneShaderPrograms.FILL_GLOW);
            } catch (Throwable t) {
                settings.renderShaderEffects.value = false;
                RenderSystem.setShader(CoreShaders.POSITION_COLOR);
            }
        } else {
            RenderSystem.setShader(CoreShaders.POSITION_COLOR);
        }
        return tessellator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
    }

    static BufferBuilder startFilled(Color color, float alpha, boolean ignoreDepth) {
        return startFilled(color, alpha, ignoreDepth, false);
    }

    static BufferBuilder startFilledHolo(Color color, float alpha, boolean ignoreDepth) {
        return startFilled(color, alpha, ignoreDepth, true);
    }

    static BufferBuilder startFilled(Color color, boolean ignoreDepth) {
        return startFilled(color, settings.filledBoxAlpha.value, ignoreDepth);
    }

    static void endFilled(BufferBuilder bufferBuilder, boolean ignoredDepth) {
        MeshData meshData = bufferBuilder.build();
        if (meshData != null) {
            BufferUploader.drawWithShader(meshData);
        }

        if (ignoredDepth) {
            RenderSystem.enableDepthTest();
        }

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    static void emitFilledAABB(BufferBuilder bufferBuilder, PoseStack stack, AABB aabb) {
        AABB toDraw = aabb.move(-renderManager.renderPosX(), -renderManager.renderPosY(), -renderManager.renderPosZ());
        PoseStack.Pose pose = stack.last();
        float r = color[0], g = color[1], b = color[2], a = color[3];
        float minX = (float) toDraw.minX, minY = (float) toDraw.minY, minZ = (float) toDraw.minZ;
        float maxX = (float) toDraw.maxX, maxY = (float) toDraw.maxY, maxZ = (float) toDraw.maxZ;

        // Bottom (-Y)
        bufferBuilder.addVertex(pose, minX, minY, minZ).setColor(r, g, b, a);
        bufferBuilder.addVertex(pose, maxX, minY, minZ).setColor(r, g, b, a);
        bufferBuilder.addVertex(pose, maxX, minY, maxZ).setColor(r, g, b, a);
        bufferBuilder.addVertex(pose, minX, minY, maxZ).setColor(r, g, b, a);

        // Top (+Y)
        bufferBuilder.addVertex(pose, minX, maxY, maxZ).setColor(r, g, b, a);
        bufferBuilder.addVertex(pose, maxX, maxY, maxZ).setColor(r, g, b, a);
        bufferBuilder.addVertex(pose, maxX, maxY, minZ).setColor(r, g, b, a);
        bufferBuilder.addVertex(pose, minX, maxY, minZ).setColor(r, g, b, a);

        // North (-Z)
        bufferBuilder.addVertex(pose, minX, maxY, minZ).setColor(r, g, b, a);
        bufferBuilder.addVertex(pose, maxX, maxY, minZ).setColor(r, g, b, a);
        bufferBuilder.addVertex(pose, maxX, minY, minZ).setColor(r, g, b, a);
        bufferBuilder.addVertex(pose, minX, minY, minZ).setColor(r, g, b, a);

        // South (+Z)
        bufferBuilder.addVertex(pose, minX, minY, maxZ).setColor(r, g, b, a);
        bufferBuilder.addVertex(pose, maxX, minY, maxZ).setColor(r, g, b, a);
        bufferBuilder.addVertex(pose, maxX, maxY, maxZ).setColor(r, g, b, a);
        bufferBuilder.addVertex(pose, minX, maxY, maxZ).setColor(r, g, b, a);

        // West (-X)
        bufferBuilder.addVertex(pose, minX, minY, minZ).setColor(r, g, b, a);
        bufferBuilder.addVertex(pose, minX, minY, maxZ).setColor(r, g, b, a);
        bufferBuilder.addVertex(pose, minX, maxY, maxZ).setColor(r, g, b, a);
        bufferBuilder.addVertex(pose, minX, maxY, minZ).setColor(r, g, b, a);

        // East (+X)
        bufferBuilder.addVertex(pose, maxX, maxY, minZ).setColor(r, g, b, a);
        bufferBuilder.addVertex(pose, maxX, maxY, maxZ).setColor(r, g, b, a);
        bufferBuilder.addVertex(pose, maxX, minY, maxZ).setColor(r, g, b, a);
        bufferBuilder.addVertex(pose, maxX, minY, minZ).setColor(r, g, b, a);
    }
}
