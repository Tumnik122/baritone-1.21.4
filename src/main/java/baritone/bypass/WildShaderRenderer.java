package baritone.bypass;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.*;

import java.nio.ByteBuffer;

/**
 * WILD SHADER RENDERER — Profesjonalny silnik renderowania GLSL SDF (Signed Distance Fields)
 * zaimplementowany 1:1 zgodnie ze specyfikacją Wild Client (BuiltRectangle, BuiltBlur, BuiltBorder):
 *  - Matematycznie gładkie, antyaliasingowane zaokrąglone narożniki (rdist / ralpha)
 *  - Niezależny promień każdego z 4 rogów: (TopLeft, BottomLeft, BottomRight, TopRight)
 *  - Prawdziwy Frosted Glass Blur (80-punktowa splotowa konwolucja kołowa na buforze tła)
 *  - Sub-pikselowe obramowania (SDF border) bez artefaktów schodkowych
 *  - Bezpieczny save/restore stanu OpenGL 3.3
 */
public final class WildShaderRenderer {

    private static boolean initialized = false;
    private static boolean broken = false;

    private static int program = -1;
    private static int vao = -1;
    private static int vbo = -1;
    private static int backdropTex = -1;
    private static int texW = -1, texH = -1;

    // Uniform locations
    private static int uMvpLoc;
    private static int uPosLoc;
    private static int uSizeLoc;
    private static int uRadiusLoc;
    private static int uColorLoc;
    private static int uSmoothnessLoc;
    private static int uModeLoc;
    private static int uBorderColorLoc;
    private static int uBorderThicknessLoc;
    private static int uBackdropLoc;
    private static int uUvRectLoc;
    private static int uBlurRadiusLoc;

    private static final String VERT_SRC = """
        #version 330 core
        layout (location = 0) in vec2 aCorner;
        uniform mat4 uMvp;
        uniform vec2 uPos;
        uniform vec2 uSize;
        out vec2 vUV;
        void main() {
            vUV = aCorner;
            gl_Position = uMvp * vec4(uPos + aCorner * uSize, 0.0, 1.0);
        }
        """;

    private static final String FRAG_SRC = """
        #version 330 core
        in vec2 vUV;
        out vec4 fragColor;

        uniform int   uMode;       // 0 = Rect, 1 = Frosted Blur, 2 = Border
        uniform vec2  uSize;
        uniform vec4  uRadius;     // (x: TopLeft, y: BottomLeft, z: BottomRight, w: TopRight)
        uniform vec4  uColor;
        uniform float uSmoothness;

        uniform vec4  uBorderColor;
        uniform float uBorderThickness;

        uniform sampler2D uBackdrop;
        uniform vec4  uUvRect;     // (u0, v0, u1, v1)
        uniform float uBlurRadius;

        // Wild Client SDF algorytm
        float rdist(vec2 pos, vec2 size, vec4 radius) {
            radius.xy = (pos.x > 0.0) ? radius.xy : radius.wz;
            radius.x  = (pos.y > 0.0) ? radius.x : radius.y;
            vec2 v = abs(pos) - size + radius.x;
            return min(max(v.x, v.y), 0.0) + length(max(v, 0.0)) - radius.x;
        }

        float ralpha(vec2 size, vec2 coord, vec4 radius, float smoothness) {
            vec2 center = size * 0.5;
            float dist = rdist(center - (coord * size), center - 1.0, radius);
            return 1.0 - smoothstep(1.0 - smoothness, 1.0, dist);
        }

        const float DPI = 6.28318530718;
        const float STEP = DPI / 16.0;

        void main() {
            float alpha = ralpha(uSize, vUV, uRadius, uSmoothness);
            if (alpha <= 0.001) {
                discard;
            }

            if (uMode == 0) {
                // Zaokrąglony prostokąt SDF
                vec4 col = vec4(uColor.rgb, uColor.a * alpha);
                if (uBorderThickness > 0.001) {
                    vec2 center = uSize * 0.5;
                    float dist = rdist(center - (vUV * uSize), center - 1.0, uRadius);
                    float borderA = smoothstep(1.0 - uBorderThickness - uSmoothness * 0.5, 1.0 - uBorderThickness, dist);
                    borderA *= 1.0 - smoothstep(1.0 - uSmoothness * 0.5, 1.0, dist);
                    col = mix(col, uBorderColor, borderA * uBorderColor.a);
                }
                fragColor = col;
            } else if (uMode == 1) {
                // Frosted Glass Blur (dokładnie jak w Wild Client blur.fsh)
                vec2 baseUv = mix(uUvRect.xy, uUvRect.zw, vec2(vUV.x, 1.0 - vUV.y));
                vec2 span = uUvRect.zw - uUvRect.xy;
                vec2 multiplier = (uBlurRadius / uSize) * span * 0.08;

                vec3 avg = texture(uBackdrop, baseUv).rgb;
                for (float d = 0.0; d < DPI; d += STEP) {
                    vec2 dir = vec2(cos(d), sin(d)) * multiplier;
                    for (float i = 0.2; i <= 1.0; i += 0.2) {
                        vec2 suv = clamp(baseUv + dir * i, uUvRect.xy, uUvRect.zw);
                        avg += texture(uBackdrop, suv).rgb;
                    }
                }
                avg /= 81.0;

                vec3 finalRgb = mix(avg, uColor.rgb, uColor.a);
                fragColor = vec4(finalRgb, alpha);
            } else {
                // Czysty border
                vec2 center = uSize * 0.5;
                float dist = rdist(center - (vUV * uSize), center - 1.0, uRadius);
                float borderA = smoothstep(1.0 - uBorderThickness - uSmoothness * 0.5, 1.0 - uBorderThickness, dist);
                borderA *= 1.0 - smoothstep(1.0 - uSmoothness * 0.5, 1.0, dist);
                fragColor = vec4(uBorderColor.rgb, uBorderColor.a * borderA * alpha);
            }
        }
        """;

    private WildShaderRenderer() {}

    public static boolean isUsable() {
        if (broken) return false;
        if (!initialized) init();
        return !broken && program != -1;
    }

    private static void init() {
        initialized = true;
        try {
            int vs = compileShader(GL20.GL_VERTEX_SHADER, VERT_SRC);
            int fs = compileShader(GL20.GL_FRAGMENT_SHADER, FRAG_SRC);

            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vs);
            GL20.glAttachShader(program, fs);
            GL20.glLinkProgram(program);

            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                String log = GL20.glGetProgramInfoLog(program);
                GL20.glDeleteProgram(program);
                program = -1;
                broken = true;
                System.err.println("[WildShader] Błąd linkowania shaderów: " + log);
                return;
            }

            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);

            // VAO / VBO: Quad [0..1]
            vao = GL30.glGenVertexArrays();
            vbo = GL15.glGenBuffers();
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            float[] quadVertices = {
                0.0f, 0.0f,
                1.0f, 0.0f,
                0.0f, 1.0f,
                1.0f, 1.0f
            };
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, quadVertices, GL15.GL_STATIC_DRAW);
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 0, 0L);
            GL30.glBindVertexArray(0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

            // Uniform locations
            uMvpLoc             = GL20.glGetUniformLocation(program, "uMvp");
            uPosLoc             = GL20.glGetUniformLocation(program, "uPos");
            uSizeLoc            = GL20.glGetUniformLocation(program, "uSize");
            uRadiusLoc          = GL20.glGetUniformLocation(program, "uRadius");
            uColorLoc           = GL20.glGetUniformLocation(program, "uColor");
            uSmoothnessLoc      = GL20.glGetUniformLocation(program, "uSmoothness");
            uModeLoc            = GL20.glGetUniformLocation(program, "uMode");
            uBorderColorLoc     = GL20.glGetUniformLocation(program, "uBorderColor");
            uBorderThicknessLoc = GL20.glGetUniformLocation(program, "uBorderThickness");
            uBackdropLoc        = GL20.glGetUniformLocation(program, "uBackdrop");
            uUvRectLoc          = GL20.glGetUniformLocation(program, "uUvRect");
            uBlurRadiusLoc      = GL20.glGetUniformLocation(program, "uBlurRadius");

        } catch (Throwable t) {
            broken = true;
            System.err.println("[WildShader] Wyjątek podczas inicjalizacji shaderów: " + t);
        }
    }

    private static int compileShader(int type, String src) {
        int sh = GL20.glCreateShader(type);
        GL20.glShaderSource(sh, src);
        GL20.glCompileShader(sh);
        if (GL20.glGetShaderi(sh, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(sh);
            GL20.glDeleteShader(sh);
            throw new IllegalStateException("Kompilacja shadera nie powiodła się: " + log);
        }
        return sh;
    }

    private static void captureBackdrop(float u0, float v0, float u1, float v1) {
        Minecraft mc = Minecraft.getInstance();
        int fbW = mc.getWindow().getWidth();
        int fbH = mc.getWindow().getHeight();

        if (backdropTex == -1) {
            backdropTex = GL11.glGenTextures();
        }
        int prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, backdropTex);
        if (texW != fbW || texH != fbH) {
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, fbW, fbH, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            texW = fbW;
            texH = fbH;
        }

        int sx = Math.max(0, Math.min(fbW, (int) (u0 * fbW)));
        int sy = Math.max(0, Math.min(fbH, (int) (v0 * fbH)));
        int sw = Math.max(1, Math.min(fbW - sx, (int) ((u1 - u0) * fbW) + 1));
        int sh = Math.max(1, Math.min(fbH - sy, (int) ((v1 - v0) * fbH) + 1));
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, sx, sy, sx, sy, sw, sh);

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        if (prevActive != GL13.GL_TEXTURE0) {
            GL13.glActiveTexture(prevActive);
        }
    }

    /**
     * Renderuje Frosted Glass Blur tła (dokładnie jak Wild Client BuiltBlur)
     */
    public static void renderBlur(GuiGraphics gg, float x, float y, float w, float h,
                                  float rTL, float rBL, float rBR, float rTR,
                                  float blurRadius, int tintArgb) {
        if (!isUsable()) return;

        Matrix4f mvp = new Matrix4f().setOrtho(0.0f, (float) Math.max(1, gg.guiWidth()), (float) Math.max(1, gg.guiHeight()), 0.0f, -1000.0f, 3000.0f).mul(gg.pose().last().pose());
        Vector4f tl = mvp.transform(new Vector4f(x, y, 0.0F, 1.0F));
        Vector4f br = mvp.transform(new Vector4f(x + w, y + h, 0.0F, 1.0F));
        float u0 = 0.5F + 0.5F * Math.min(tl.x, br.x);
        float u1 = 0.5F + 0.5F * Math.max(tl.x, br.x);
        float v0 = 0.5F + 0.5F * Math.min(tl.y, br.y);
        float v1 = 0.5F + 0.5F * Math.max(tl.y, br.y);

        captureBackdrop(u0, v0, u1, v1);

        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int prevTex0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, backdropTex);
        GL20.glUseProgram(program);
        GL30.glBindVertexArray(vao);

        float[] mvpArr = new float[16];
        mvp.get(mvpArr);
        GL20.glUniformMatrix4fv(uMvpLoc, false, mvpArr);
        GL20.glUniform2f(uPosLoc, x, y);
        GL20.glUniform2f(uSizeLoc, w, h);
        GL20.glUniform4f(uRadiusLoc, rTL, rBL, rBR, rTR);
        GL20.glUniform4f(uColorLoc,
                ((tintArgb >> 16) & 0xFF) / 255.0F,
                ((tintArgb >> 8)  & 0xFF) / 255.0F,
                ( tintArgb        & 0xFF) / 255.0F,
                ((tintArgb >> 24) & 0xFF) / 255.0F);
        GL20.glUniform1f(uSmoothnessLoc, 1.0F);
        GL20.glUniform1i(uModeLoc, 1);
        GL20.glUniform1i(uBackdropLoc, 0);
        GL20.glUniform4f(uUvRectLoc, u0, v0, u1, v1);
        GL20.glUniform1f(uBlurRadiusLoc, blurRadius);

        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

        GL20.glUseProgram(prevProgram);
        GL30.glBindVertexArray(prevVao);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex0);
        if (prevActive != GL13.GL_TEXTURE0) {
            GL13.glActiveTexture(prevActive);
        }
    }

    /**
     * Renderuje zaokrąglony prostokąt SDF z per-corner radius i opcjonalnym obramowaniem
     * (dokładnie jak Wild Client BuiltRectangle & BuiltBorder)
     */
    public static void renderRect(GuiGraphics gg, float x, float y, float w, float h,
                                  float rTL, float rBL, float rBR, float rTR,
                                  int fillArgb, int borderArgb, float borderThickness) {
        if (!isUsable()) return;

        Matrix4f mvp = new Matrix4f().setOrtho(0.0f, (float) Math.max(1, gg.guiWidth()), (float) Math.max(1, gg.guiHeight()), 0.0f, -1000.0f, 3000.0f).mul(gg.pose().last().pose());

        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);

        GL20.glUseProgram(program);
        GL30.glBindVertexArray(vao);

        float[] mvpArr = new float[16];
        mvp.get(mvpArr);
        GL20.glUniformMatrix4fv(uMvpLoc, false, mvpArr);
        GL20.glUniform2f(uPosLoc, x, y);
        GL20.glUniform2f(uSizeLoc, w, h);
        GL20.glUniform4f(uRadiusLoc, rTL, rBL, rBR, rTR);
        GL20.glUniform4f(uColorLoc,
                ((fillArgb >> 16) & 0xFF) / 255.0F,
                ((fillArgb >> 8)  & 0xFF) / 255.0F,
                ( fillArgb        & 0xFF) / 255.0F,
                ((fillArgb >> 24) & 0xFF) / 255.0F);
        GL20.glUniform1f(uSmoothnessLoc, 1.15F);
        GL20.glUniform1i(uModeLoc, 0);

        if (borderThickness > 0.001F) {
            GL20.glUniform4f(uBorderColorLoc,
                    ((borderArgb >> 16) & 0xFF) / 255.0F,
                    ((borderArgb >> 8)  & 0xFF) / 255.0F,
                    ( borderArgb        & 0xFF) / 255.0F,
                    ((borderArgb >> 24) & 0xFF) / 255.0F);
            GL20.glUniform1f(uBorderThicknessLoc, borderThickness);
        } else {
            GL20.glUniform1f(uBorderThicknessLoc, 0.0F);
        }

        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

        GL20.glUseProgram(prevProgram);
        GL30.glBindVertexArray(prevVao);
    }
}
