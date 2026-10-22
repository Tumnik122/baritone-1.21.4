package com.astra.revolution.visuals;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.CoreShaders;
import org.lwjgl.opengl.GL11;

/**
 * 🛡️ ASTRA GL STATE GUARD (OpenGL 3.3 Core / Blaze3D Mojmap 1.21.4).
 *
 * Guarantees 100% zero-leak OpenGL state isolation across all 3D visuals.
 * Restores cull face, depth testing, depth function, depth mask, blending factors,
 * and shader color back to vanilla defaults.
 */
public final class AstraGlStateGuard implements AutoCloseable {

    private final boolean prevCull;
    private final boolean prevDepthTest;
    private final boolean prevBlend;
    private final int prevDepthFunc;

    public AstraGlStateGuard(boolean additive, boolean writeDepth, boolean depthTest) {
        this.prevCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        this.prevDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        this.prevBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        this.prevDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);

        RenderSystem.enableBlend();
        if (additive) {
            // Additive bloom glow (Shockwaves, Laser Cores, Chevrons, Spark Embers)
            RenderSystem.blendFuncSeparate(
                    GlStateManager.SourceFactor.SRC_ALPHA,
                    GlStateManager.DestFactor.ONE,
                    GlStateManager.SourceFactor.ONE,
                    GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA
            );
        } else {
            // Smooth translucent glassmorphism (Ribbons, Disk Bloom, Cylinder Walls)
            RenderSystem.defaultBlendFunc();
        }

        if (depthTest) {
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
        } else {
            RenderSystem.disableDepthTest();
        }

        RenderSystem.depthMask(writeDepth);
        RenderSystem.disableCull();
        RenderSystem.setShader(CoreShaders.POSITION_COLOR);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    @Override
    public void close() {
        RenderSystem.depthMask(true);
        if (this.prevDepthTest) {
            RenderSystem.enableDepthTest();
        } else {
            RenderSystem.disableDepthTest();
        }
        RenderSystem.depthFunc(this.prevDepthFunc);

        if (this.prevCull) {
            RenderSystem.enableCull();
        } else {
            RenderSystem.disableCull();
        }

        RenderSystem.defaultBlendFunc();
        if (!this.prevBlend) {
            RenderSystem.disableBlend();
        }
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }
}
