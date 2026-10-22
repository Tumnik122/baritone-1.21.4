package baritone.bypass.modern;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * Bezpieczny zapis i przywracanie stanu OpenGL 3.3.
 * Zabezpiecza przed konfliktami z silnikiem Blaze3D w Minecraft 1.21.4 oraz modami graficznymi (Sodium/Iris).
 */
public final class GlStateBackup {
    private int lastProgram;
    private int lastVao;
    private int lastArrayBuffer;
    private int lastTexture2D;
    private int lastActiveTexture;
    private boolean lastBlend;
    private boolean lastDepthTest;
    private boolean lastCullFace;

    public void capture() {
        this.lastProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        this.lastVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        this.lastArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        this.lastActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        this.lastTexture2D = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        this.lastBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        this.lastDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        this.lastCullFace = GL11.glIsEnabled(GL11.GL_CULL_FACE);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        GL20.glBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA
        );
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
    }

    public void restore() {
        GL20.glUseProgram(this.lastProgram);
        GL30.glBindVertexArray(this.lastVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.lastArrayBuffer);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.lastTexture2D);
        GL13.glActiveTexture(this.lastActiveTexture);

        if (this.lastBlend) {
            RenderSystem.enableBlend();
        } else {
            RenderSystem.disableBlend();
        }

        if (this.lastDepthTest) {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
        } else {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
        }

        if (this.lastCullFace) {
            GL11.glEnable(GL11.GL_CULL_FACE);
        } else {
            GL11.glDisable(GL11.GL_CULL_FACE);
        }
    }
}
