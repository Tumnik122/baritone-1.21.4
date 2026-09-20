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

package baritone.hud;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * Własny pipeline GLSL dla HUD (direct-LWJGL, poza systemem RenderPipeline
 * vanilla — technika stosowana przez mody blur; odporna na refaktory 1.21.x).
 *
 * Tryby:
 *   MODE_PANEL — szklany panel: backdrop blur, chroma-border, glow, grain
 *   MODE_BAR   — pasek postępu: gradient + shimmer + świecąca głowica
 *   MODE_PILL  — zaokrąglony „badge"/żłobek paska
 *
 * Bezpieczeństwo stanu GL: pełny save/restore programu, VAO, tekstury,
 * active texture unit, depth test i cull face. Awaria kompilacji = graceful
 * fallback (overlay rysuje klasyczne fill'e).
 */
public final class HudShaders {

    public static final int MODE_PANEL = 0;
    public static final int MODE_BAR   = 1;
    public static final int MODE_PILL  = 2;
    public static final int MODE_DIVIDER = 3;
    public static final int MODE_VITAL = 4;
    public static final int MODE_RADAR = 5;

    private static final long START = System.nanoTime();

    private static int program = -1;
    private static int vao = -1;
    private static int backdropTex = -1;
    private static int texW = -1, texH = -1;
    private static boolean broken = false;
    private static boolean initialized = false;

    private static int uMvp, uOffset, uSize, uTime, uUvRect, uAccent,
                       uGlow, uMode, uProgress, uRadius, uBackdropLoc,
                       uDotCount, uDots, uGoalDot;

    private static final float[] RADAR_DOTS = new float[32];
    private static int radarDotCount;
    private static float radarGoalX = -1.0F, radarGoalZ = -1.0F;

    // ═════════════════════════ SHADERY ═════════════════════════

    private static final String VERT_SRC = """
            #version 330 core
            layout (location = 0) in vec2 aCorner;
            uniform mat4 uMvp;
            uniform vec2 uOffset;
            uniform vec2 uSize;
            out vec2 vUV;
            void main() {
                vUV = aCorner;
                gl_Position = uMvp * vec4(uOffset + aCorner * uSize, 0.0, 1.0);
            }
            """;

    private static final String FRAG_SRC = """
            #version 330 core
            in vec2 vUV;
            out vec4 fragColor;

            uniform sampler2D uBackdrop;
            uniform vec4  uUvRect;    // (u0, v0, u1, v1) — region snapshotu w teksturze
            uniform vec2  uSize;      // rozmiar prostokąta w px GUI
            uniform float uTime;      // sekundy
            uniform vec3  uAccent;
            uniform float uGlow;      // 0..1 — puls aktywności
            uniform int   uMode;
            uniform float uProgress;
            uniform float uRadius;
            uniform int   uDotCount;
            uniform vec2  uDots[16];
            uniform vec2  uGoalDot;

            vec3 hsl2rgb(vec3 c) {
                vec3 rgb = clamp(abs(mod(c.x * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
                return c.z + c.y * (rgb - 0.5) * (1.0 - abs(2.0 * c.z - 1.0));
            }

            // SDF zaokrąglonego prostokąta — AA za darmo, rogi idealnie gładkie
            float sdRoundBox(vec2 p, vec2 b, float r) {
                vec2 q = abs(p) - b + r;
                return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
            }

            float hash12(vec2 p) {
                vec3 p3 = fract(vec3(p.xyx) * 0.1031);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z);
            }

            float hexGrid(vec2 uv) {
                vec2 p = uv * vec2(14.0, 16.0);
                vec2 g = abs(fract(p) - 0.5);
                return 1.0 - smoothstep(0.02, 0.08, min(g.x, g.y));
            }

            // 13-próbkowy spiralny blur (złoty kąt) — realne frosted glass
            vec3 backdropBlur(vec2 uv) {
                vec3 sum = vec3(0.0);
                float wsum = 0.0;
                vec2 span = uUvRect.zw - uUvRect.xy;
                for (int i = 0; i < 13; i++) {
                    float a  = float(i) * 2.39996323;
                    float rr = sqrt(float(i) / 13.0) * 0.30;
                    vec2 off = vec2(cos(a), sin(a)) * rr * span;
                    vec2 suv = clamp(uv + off, uUvRect.xy, uUvRect.zw);
                    float w  = 1.0 - float(i) / 13.0;
                    sum  += texture(uBackdrop, suv).rgb * w;
                    wsum += w;
                }
                return sum / wsum;
            }

            void main() {
                vec2 p = vUV * uSize;
                float d = sdRoundBox(p - uSize * 0.5, uSize * 0.5, uRadius);
                float aa = 1.0 - smoothstep(-1.0, 1.0, d);
                if (aa <= 0.003) discard;

                if (uMode == 0) {
                    vec2 uv = mix(uUvRect.xy, uUvRect.zw, vec2(vUV.x, 1.0 - vUV.y));
                    vec3 glass = backdropBlur(uv);
                    vec3 col = glass * vec3(0.15, 0.19, 0.26) + vec3(0.006, 0.014, 0.022);
                    col += vec3(0.30, 0.65, 0.85) * 0.06 * smoothstep(0.35, 1.0, 1.0 - vUV.y);
                    col += vec3(0.0, 0.55, 0.75) * hexGrid(vUV) * 0.07;
                    float strip = 1.0 - smoothstep(1.5, 3.4, p.y);
                    vec3 chroma = hsl2rgb(vec3(fract(uTime * 0.07 + vUV.x * 1.15), 0.88, 0.62));
                    col = mix(col, chroma, strip * 0.95);
                    float pulse = 0.55 + 0.45 * sin(uTime * 2.2);
                    float edgeL = exp(-max(p.x - 2.0, 0.0) * 0.5);
                    col += uAccent * edgeL * (0.22 + 0.55 * uGlow * pulse);
                    float innerGlow = 1.0 - smoothstep(0.0, 7.0, abs(d + 2.0));
                    col += uAccent * innerGlow * (0.08 + 0.16 * uGlow);
                    col += vec3(0.015, 0.04, 0.05) * (0.5 + 0.5 * sin(p.y * 1.8 - uTime * 2.5));
                    col += (hash12(p + vec2(fract(uTime * 7.0) * 91.0)) - 0.5) * 0.022;
                    float border = 1.0 - smoothstep(0.4, 1.6, abs(d));
                    col = mix(col, uAccent * 0.65 + 0.35, border * 0.5);
                    fragColor = vec4(col, 0.94 * aa);
                } else if (uMode == 1) {
                    vec3 cyan = vec3(0.0, 0.898, 1.0);
                    vec3 mag  = hsl2rgb(vec3(fract(uTime * 0.08 + 0.78), 0.9, 0.62));
                    vec3 grn  = vec3(0.0, 1.0, 0.53);
                    vec3 col = mix(cyan, mag, smoothstep(0.0, 0.65, vUV.x));
                    col = mix(col, grn, smoothstep(0.80, 1.0, vUV.x) * uProgress);
                    col += vec3(1.0) * 0.28 * exp(-abs(vUV.x - fract(uTime * 0.30)) * 24.0);
                    float headOn = uProgress < 0.995 ? 1.0 : 0.0;
                    col += vec3(1.0, 1.0, 0.95) * 0.85 * exp(-abs(vUV.x - uProgress) * 26.0) * headOn;
                    col *= 0.80 + 0.35 * (1.0 - vUV.y);
                    float mask = 1.0 - smoothstep(uProgress - 0.012, uProgress + 0.012, vUV.x);
                    fragColor = vec4(col, aa * mask);
                } else if (uMode == 2) {
                    vec3 col = uAccent * (0.30 + 0.70 * uGlow);
                    col += vec3(1.0) * 0.12 * exp(-abs(d) * 1.5);
                    fragColor = vec4(col, aa * (0.30 + 0.55 * uGlow));
                } else if (uMode == 3) {
                    float flow = 0.5 + 0.5 * sin(vUV.x * 22.0 - uTime * 6.0);
                    vec3 col = mix(uAccent * 0.25, uAccent, flow);
                    col += vec3(1.0) * 0.18 * exp(-abs(vUV.x - fract(uTime * 0.35)) * 40.0);
                    fragColor = vec4(col, aa * (0.35 + 0.45 * uGlow));
                } else if (uMode == 4) {
                    vec3 lo = vec3(0.95, 0.18, 0.22);
                    vec3 mid = vec3(0.95, 0.78, 0.12);
                    vec3 hi = vec3(0.12, 0.95, 0.48);
                    vec3 col = mix(lo, mid, smoothstep(0.0, 0.55, uProgress));
                    col = mix(col, hi, smoothstep(0.55, 1.0, uProgress));
                    col += vec3(1.0) * 0.22 * exp(-abs(vUV.x - uProgress) * 22.0);
                    col *= 0.78 + 0.35 * (1.0 - vUV.y);
                    float mask = 1.0 - smoothstep(uProgress - 0.015, uProgress + 0.015, vUV.x);
                    float warn = uProgress < 0.35 ? (0.5 + 0.5 * sin(uTime * 8.0)) : 1.0;
                    fragColor = vec4(col, aa * mask * warn);
                } else {
                    vec2 c = vUV * 2.0 - 1.0;
                    float rad = length(c);
                    float ring = 1.0 - smoothstep(0.92, 1.0, rad);
                    if (ring <= 0.01) discard;
                    vec3 col = vec3(0.03, 0.06, 0.09);
                    col += uAccent * (1.0 - smoothstep(0.86, 0.98, rad)) * 0.55;
                    float cross = max(1.0 - smoothstep(0.0, 0.02, abs(c.x)), 1.0 - smoothstep(0.0, 0.02, abs(c.y)));
                    col += uAccent * 0.18 * cross * (1.0 - smoothstep(0.75, 1.0, rad));
                    float sweep = atan(c.y, c.x);
                    float beam = exp(-abs(fract((sweep / 6.28318) - fract(uTime * 0.18)) - 0.5) * 28.0);
                    col += uAccent * beam * 0.25 * ring;
                    for (int i = 0; i < 16; i++) {
                        if (i >= uDotCount) break;
                        float dd = length(c - (uDots[i] * 2.0 - 1.0));
                        col += vec3(0.2, 0.95, 1.0) * exp(-dd * 28.0);
                    }
                    if (uGoalDot.x > -0.01) {
                        float gd = length(c - (uGoalDot * 2.0 - 1.0));
                        col += vec3(1.0, 0.45, 0.95) * exp(-gd * 18.0) * (0.7 + 0.3 * sin(uTime * 7.0));
                    }
                    col += vec3(0.4, 1.0, 0.8) * exp(-length(c) * 14.0) * 0.45;
                    fragColor = vec4(col, aa * ring * 0.88);
                }
            }
            """;

    private HudShaders() {}

    // ═════════════════════════ API ═════════════════════════

    public static boolean isUsable() {
        if (broken) {
            return false;
        }
        if (!initialized) {
            init();
        }
        return !broken && program != -1;
    }

    /**
     * Rysuje zaokrąglony prostokąt shaderem. Współpracuje z bieżącym
     * transformem GuiGraphics (obsługuje guiScale i hudScale — pozycja
     * snapshotu liczona jest przez pełny MVP, więc blur jest zawsze idealnie
     * wyrównany do panelu).
     */
    public static void drawRounded(GuiGraphics gg, float x, float y, float w, float h,
                                   float radius, int accentArgb, float glow,
                                   int mode, float progress) {
        if (!isUsable()) {
            return;
        }

        Matrix4f mvp = new Matrix4f(RenderSystem.getProjectionMatrix())
                .mul(gg.pose().last().pose());

        // ── pozycja panelu w NDC → UV snapshotu (ogólna, odporna na scale) ──
        Vector4f tl = mvp.transform(new Vector4f(x, y, 0.0F, 1.0F));
        Vector4f br = mvp.transform(new Vector4f(x + w, y + h, 0.0F, 1.0F));
        float u0 = 0.5F + 0.5F * Math.min(tl.x, br.x);
        float u1 = 0.5F + 0.5F * Math.max(tl.x, br.x);
        float v0 = 0.5F + 0.5F * Math.min(tl.y, br.y); // dół panelu
        float v1 = 0.5F + 0.5F * Math.max(tl.y, br.y); // góra panelu

        captureBackdrop(u0, v0, u1, v1);

        // ── save stanu GL ──
        int prevProgram  = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevVao      = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevActive   = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        // save texture binding on unit 0 specifically (we will switch to it)
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int prevTex0     = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        // also save the currently active unit's binding if it differs
        int prevTexActive = (prevActive != GL13.GL_TEXTURE0)
                ? restoreActiveAndGetTex(prevActive)
                : prevTex0;
        boolean depthWas  = GL11.glGetBoolean(GL11.GL_DEPTH_TEST);
        boolean cullWas   = GL11.glGetBoolean(GL11.GL_CULL_FACE);
        boolean blendWas  = GL11.glGetBoolean(GL11.GL_BLEND);
        int prevBlendSrc  = GL11.glGetInteger(GL11.GL_BLEND_SRC);
        int prevBlendDst  = GL11.glGetInteger(GL11.GL_BLEND_DST);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, backdropTex);
        GL20.glUseProgram(program);
        GL30.glBindVertexArray(vao);

        GL20.glUniformMatrix4fv(uMvp, false, mvp.get(new float[16]));
        GL20.glUniform2f(uOffset, x, y);
        GL20.glUniform2f(uSize, w, h);
        GL20.glUniform1f(uTime, (System.nanoTime() - START) / 1.0E9F);
        GL20.glUniform4f(uUvRect, u0, v0, u1, v1);
        GL20.glUniform3f(uAccent,
                ((accentArgb >> 16) & 0xFF) / 255.0F,
                ((accentArgb >> 8)  & 0xFF) / 255.0F,
                ( accentArgb        & 0xFF) / 255.0F);
        GL20.glUniform1f(uGlow, glow);
        GL20.glUniform1i(uMode, mode);
        GL20.glUniform1f(uProgress, progress);
            GL20.glUniform1f(uRadius, radius);
        GL20.glUniform1i(uBackdropLoc, 0);
        GL20.glUniform1i(uDotCount, radarDotCount);
        GL20.glUniform2fv(uDots, RADAR_DOTS);
        GL20.glUniform2f(uGoalDot, radarGoalX, radarGoalZ);

        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

        // ── restore stanu GL ──
        GL20.glUseProgram(prevProgram);
        GL30.glBindVertexArray(prevVao);
        // restore texture on unit 0
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex0);
        // restore previously active texture unit and its binding
        if (prevActive != GL13.GL_TEXTURE0) {
            GL13.glActiveTexture(prevActive);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTexActive);
        }
        if (depthWas) GL11.glEnable(GL11.GL_DEPTH_TEST);
        if (cullWas)  GL11.glEnable(GL11.GL_CULL_FACE);
        // restore blend state
        if (!blendWas) GL11.glDisable(GL11.GL_BLEND);
        GL11.glBlendFunc(prevBlendSrc, prevBlendDst);
    }

    /**
     * Mini-radar XZ: {@code dots} to pary (x,z) w przestrzeni 0..1 panelu, max 16.
     */
    public static void drawRadar(GuiGraphics gg, float x, float y, float w, float h,
                                 int accentArgb, float[] dots, int dotCount, float goalX, float goalZ, boolean hasGoal) {
        java.util.Arrays.fill(RADAR_DOTS, 0.5F);
        radarDotCount = Math.max(0, Math.min(16, dotCount));
        if (dots != null) {
            System.arraycopy(dots, 0, RADAR_DOTS, 0, Math.min(RADAR_DOTS.length, dots.length));
        }
        radarGoalX = hasGoal ? goalX : -1.0F;
        radarGoalZ = hasGoal ? goalZ : -1.0F;
        try {
            drawRounded(gg, x, y, w, h, w * 0.5F, accentArgb, 0.7F, MODE_RADAR, 0F);
        } finally {
            radarDotCount = 0;
            radarGoalX = -1.0F;
            radarGoalZ = -1.0F;
        }
    }

    private static int restoreActiveAndGetTex(int active) {
        GL13.glActiveTexture(active);
        return GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    }

    // ═════════════════════════ INTERNALS ═════════════════════════

    /**
     * Snapshot regionu framebuffera do własnej tekstury (glCopyTexSubImage2D).
     * Dzięki temu blur czyta BEZPIECZNIE z kopii — nigdy z celu, na który
     * aktualnie piszemy (czytanie+pisanie tej samej tekstury = UB).
     * Uwaga: tryb „Fabulous" też działa — kopiujemy z aktualnie powiązanego
     * READ framebuffera, czyli dokładnie to, co widać za panelem.
     */
    private static void captureBackdrop(float u0, float v0, float u1, float v1) {
        Minecraft mc = Minecraft.getInstance();
        int fbW = mc.getWindow().getWidth();
        int fbH = mc.getWindow().getHeight();

        if (backdropTex == -1) {
            backdropTex = GL11.glGenTextures();
        }
        // ensure we operate on GL_TEXTURE0 and restore the previous binding when done
        int capPrevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int capPrevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, backdropTex);
        if (texW != fbW || texH != fbH) {
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, fbW, fbH, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
            texW = fbW;
            texH = fbH;
        }

        int sx = clamp((int) (u0 * fbW), 0, fbW);
        int sy = clamp((int) (v0 * fbH), 0, fbH);
        int sw = clamp((int) ((u1 - u0) * fbW) + 1, 0, fbW - sx);
        int sh = clamp((int) ((v1 - v0) * fbH) + 1, 0, fbH - sy);
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, sx, sy, sx, sy, sw, sh);
        // restore GL_TEXTURE0 binding and previously active unit
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, capPrevTex);
        if (capPrevActive != GL13.GL_TEXTURE0) {
            GL13.glActiveTexture(capPrevActive);
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static void init() {
        initialized = true;
        try {
            int vs = compile(GL20.GL_VERTEX_SHADER, VERT_SRC);
            int fs = compile(GL20.GL_FRAGMENT_SHADER, FRAG_SRC);

            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vs);
            GL20.glAttachShader(program, fs);
            GL20.glLinkProgram(program);
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                throw new IllegalStateException("link: " + GL20.glGetProgramInfoLog(program));
            }
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);

            // quad 0..1 jako triangle-strip
            vao = GL30.glGenVertexArrays();
            int vbo = GL15.glGenBuffers();
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER,
                    new float[]{0, 0, 1, 0, 0, 1, 1, 1}, GL15.GL_STATIC_DRAW);
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 0, 0);
            GL30.glBindVertexArray(0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

            uMvp        = GL20.glGetUniformLocation(program, "uMvp");
            uOffset     = GL20.glGetUniformLocation(program, "uOffset");
            uSize       = GL20.glGetUniformLocation(program, "uSize");
            uTime       = GL20.glGetUniformLocation(program, "uTime");
            uUvRect     = GL20.glGetUniformLocation(program, "uUvRect");
            uAccent     = GL20.glGetUniformLocation(program, "uAccent");
            uGlow       = GL20.glGetUniformLocation(program, "uGlow");
            uMode       = GL20.glGetUniformLocation(program, "uMode");
            uProgress   = GL20.glGetUniformLocation(program, "uProgress");
            uRadius     = GL20.glGetUniformLocation(program, "uRadius");
            uBackdropLoc = GL20.glGetUniformLocation(program, "uBackdrop");
            uDotCount   = GL20.glGetUniformLocation(program, "uDotCount");
            uDots       = GL20.glGetUniformLocation(program, "uDots");
            uGoalDot    = GL20.glGetUniformLocation(program, "uGoalDot");
        } catch (Throwable t) {
            System.err.println("[BaritoneHUD] shader init failed — fallback do fill'i: " + t);
            broken = true;
        }
    }

    private static int compile(int type, String src) {
        int id = GL20.glCreateShader(type);
        GL20.glShaderSource(id, src);
        GL20.glCompileShader(id);
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            throw new IllegalStateException("compile: " + GL20.glGetShaderInfoLog(id));
        }
        return id;
    }
}
