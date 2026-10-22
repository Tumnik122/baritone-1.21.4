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
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.CoreShaders;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.awt.Color;
import java.nio.FloatBuffer;

/**
 * Direct-LWJGL world overlay FX (ribbons, hologram boxes, orbs). Independent of
 * Sodium/Iris; compiles GLSL from Java strings like {@link baritone.hud.HudShaders}.
 */
public final class WorldFxShaders {

    public static final int MODE_RIBBON = 0;
    public static final int MODE_HOLO = 1;
    public static final int MODE_ORB = 2;
    public static final int MODE_NODE = 3;
    public static final int MODE_VENTURA_WAVE = 4;
    public static final int MODE_BLOCK_OVERLAY = 5;

    private static final long START = System.nanoTime();
    private static final int MAX_VERTS = 4096;
    private static final int STRIDE_FLOATS = 10; // pos3 + uv2 + color4 + along1

    private static int program = -1;
    private static int vao = -1;
    private static int vbo = -1;
    private static boolean broken = false;
    private static boolean initialized = false;

    private static int uMvp, uTime, uMode, uCamRight, uCamUp;

    private static float[] verts;
    private static int vertCount;
    private static int currentMode = MODE_RIBBON;
    private static boolean drawing;

    private static int prevProgram, prevVao, prevArrayBuf;
    private static boolean depthWas, cullWas, blendWas, depthMaskWas;
    private static int prevBlendSrc, prevBlendDst, prevDepthFunc;
    private static boolean ignoreDepthRestore;

    private static final Vector3f CAM_RIGHT = new Vector3f();
    private static final Vector3f CAM_UP = new Vector3f();
    private static final Vector3f TMP_DIR = new Vector3f();
    private static final Vector3f TMP_SIDE = new Vector3f();
    private static final Vector3f TMP_LOOK = new Vector3f();

    private static final String VERT_SRC = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            layout (location = 1) in vec2 aUV;
            layout (location = 2) in vec4 aColor;
            layout (location = 3) in float aAlong;
            uniform mat4 uMvp;
            out vec2 vUV;
            out vec4 vColor;
            out float vAlong;
            void main() {
                vUV = aUV;
                vColor = aColor;
                vAlong = aAlong;
                gl_Position = uMvp * vec4(aPos, 1.0);
            }
            """;

    private static final String FRAG_SRC = """
            #version 330 core
            in vec2 vUV;
            in vec4 vColor;
            in float vAlong;
            uniform float uTime;
            uniform int uMode;
            out vec4 fragColor;

            void main() {
                vec3 col = vColor.rgb;
                float a = vColor.a;

                if (uMode == 0) {
                    // Mode 0: Cyber Ribbon Energy Rail (Flowing laser beam)
                    float core = 1.0 - abs(vUV.y * 2.0 - 1.0);
                    float edge = smoothstep(0.0, 0.25, core);
                    float beam = pow(core, 2.8);
                    float glow = exp(-abs(vUV.y * 2.0 - 1.0) * 3.2);

                    float dash = 0.65 + 0.35 * sin(vAlong * 20.0 - uTime * 14.0);
                    float pulse = exp(-pow(fract(vAlong * 2.6 - uTime * 1.5) - 0.5, 2.0) * 75.0);

                    vec3 beamCol = mix(col, vec3(1.0), beam * 0.85 + pulse * 0.9);
                    beamCol += vec3(0.55, 0.88, 1.0) * pulse * 0.8;

                    float beamAlpha = a * edge * (beam * 0.85 + glow * 0.45 + pulse * 0.6) * dash;
                    fragColor = vec4(beamCol, clamp(beamAlpha, 0.0, 1.0));
                } else if (uMode == 1) {
                    // Mode 1: Futuristic Sci-Fi Hologram Box (Tactical Lock-On)
                    float fx = min(vUV.x, 1.0 - vUV.x);
                    float fy = min(vUV.y, 1.0 - vUV.y);
                    float edgeDist = min(fx, fy);
                    float border = 1.0 - smoothstep(0.0, 0.05, edgeDist);
                    float corner = (fx < 0.24 && fy < 0.24) ? (1.0 - smoothstep(0.0, 0.065, edgeDist)) : 0.0;

                    // Moving cyber scanlines
                    float scanline = 0.82 + 0.18 * sin(vUV.y * 70.0 - uTime * 10.0);
                    // Traveling vertical laser sweep
                    float sweep = exp(-pow(fract(vUV.y * 0.4 - uTime * 0.75) - 0.5, 2.0) * 110.0);

                    // Micro-lattice grid
                    vec2 gridUv = fract(vUV * 12.0);
                    float grid = (gridUv.x < 0.10 && gridUv.y < 0.10) ? 0.30 : 0.0;

                    vec3 holoCol = col + vec3(0.5, 0.85, 1.0) * (sweep * 0.85 + corner * 0.55);
                    float holoAlpha = a * (0.12 * scanline + 0.55 * border + 0.45 * corner + 0.65 * sweep + grid * 0.22);
                    fragColor = vec4(holoCol, clamp(holoAlpha, 0.0, 0.88));
                } else if (uMode == 2) {
                    // Mode 2: Celestial Radiant Star Orb (Eliminates square borders completely)
                    vec2 p = vUV * 2.0 - 1.0;
                    float d = length(p);
                    if (d >= 0.98) discard;

                    // Smooth circular boundary feathering
                    float circleFeather = smoothstep(0.98, 0.62, d);

                    // Brilliant white-hot center
                    float core = exp(-d * 6.2);
                    // Volumetric soft plasma halo
                    float halo = exp(-d * 2.2) * 0.80;

                    // 4-point rotating celestial diffraction spikes (anamorphic lens flare / star spark)
                    float angle = atan(p.y, p.x) + uTime * 3.2 + vAlong * 1.25;
                    float starSpike = pow(abs(cos(angle * 2.0)), 16.0) * exp(-d * 3.2) * 1.5;
                    float starCross = pow(abs(cos(angle * 2.0 + 0.785398)), 24.0) * exp(-d * 4.2) * 0.75;

                    // Pulsing twinkle
                    float twinkle = 0.82 + 0.18 * sin(uTime * 8.5 + vAlong * 4.5);

                    // Radiant color blending: pure white core -> vibrant chromatic aura
                    vec3 orbCol = mix(col, vec3(1.0, 1.0, 1.0), core * 0.95 + (starSpike + starCross) * 0.72);
                    orbCol += vec3(0.75, 0.92, 1.0) * (starSpike + starCross) * 0.5;

                    float orbAlpha = a * circleFeather * (core * 1.0 + halo * 0.75 + (starSpike + starCross) * 0.85) * twinkle;
                    if (orbAlpha < 0.015) discard;

                    fragColor = vec4(orbCol, clamp(orbAlpha, 0.0, 1.0));
                } else if (uMode == 3) {
                    // Mode 3: Search Node Spark
                    vec2 p = vUV * 2.0 - 1.0;
                    float d = length(p);
                    if (d >= 0.98) discard;
                    float circleFeather = smoothstep(0.98, 0.60, d);
                    float core = exp(-d * 4.5);
                    float pulse = 0.5 + 0.5 * sin(uTime * 14.0 + vAlong * 18.0);
                    vec3 nodeCol = mix(col, vec3(1.0, 0.85, 0.45), 0.45 * pulse + core * 0.65);
                    float nodeAlpha = a * circleFeather * core * (0.65 + 0.35 * pulse);
                    if (nodeAlpha < 0.02) discard;
                    fragColor = vec4(nodeCol, nodeAlpha);
                } else if (uMode == 4) {
                    // Mode 4: Ventura Client Wave
                    float w1 = sin((vUV.x + vUV.y) * 6.28318 - uTime * 3.8);
                    float w2 = sin((vUV.x - vUV.y) * 4.5 + uTime * 2.2);
                    float wave = 0.5 + 0.5 * (w1 * 0.7 + w2 * 0.3);

                    float fx = min(vUV.x, 1.0 - vUV.x);
                    float fy = min(vUV.y, 1.0 - vUV.y);
                    float edgeDist = min(fx, fy);
                    float border = 1.0 - smoothstep(0.0, 0.045, edgeDist);

                    vec3 lilac = vec3(0.659, 0.333, 0.969);
                    vec3 cyan = vec3(0.024, 0.714, 0.831);

                    vec3 waveGrad = mix(lilac, cyan, wave);
                    vec3 colBase = mix(col, waveGrad, 0.65);

                    float crest = exp(-abs(w1) * 4.2);
                    colBase += vec3(0.85, 0.92, 1.0) * crest * 0.55;
                    colBase += mix(cyan, lilac, 0.5 + 0.5 * sin(uTime * 3.0)) * border * 0.95;

                    float faceAlpha = 0.12 + 0.18 * wave;
                    float borderAlpha = 0.70 + 0.20 * sin(uTime * 4.0);
                    float finalAlpha = a * mix(faceAlpha, borderAlpha, border);

                    fragColor = vec4(colBase, clamp(finalAlpha, 0.0, 0.95));
                } else {
                    // Mode 5: Luxury Crystal Glass Block Overlay (Ventura & Linear Elegance)
                    float fx = min(vUV.x, 1.0 - vUV.x);
                    float fy = min(vUV.y, 1.0 - vUV.y);
                    float edgeDist = min(fx, fy);
                    float border = 1.0 - smoothstep(0.0, 0.045, edgeDist);
                    float cornerGlow = 1.0 - smoothstep(0.0, 0.12, edgeDist);

                    // Organic chromatic shimmer
                    float shimmer = 0.5 + 0.5 * sin((vUV.x + vUV.y) * 3.14159 - uTime * 2.2);
                    vec3 glassCol = mix(col, vec3(0.88, 0.82, 1.0), 0.20 + 0.10 * shimmer);
                    glassCol += vec3(0.25, 0.35, 0.55) * border;
                    glassCol += vec3(0.15, 0.10, 0.30) * cornerGlow;

                    float faceAlpha = a * (0.45 + 0.12 * shimmer);
                    float borderAlpha = clamp(a * 1.95, 0.0, 0.90);
                    float finalAlpha = mix(faceAlpha, borderAlpha, border);

                    fragColor = vec4(glassCol, clamp(finalAlpha, 0.0, 0.95));
                }
            }
            """;

    private WorldFxShaders() {}

    public static boolean isUsable() {
        if (broken) {
            return false;
        }
        var s = BaritoneAPI.getSettings();
        boolean allow = s.renderShaderEffects.value || s.clientBlockOverlay.value || s.radialWave.value;
        if (!allow) {
            return false;
        }
        if (!initialized) {
            init();
        }
        return !broken && program != -1;
    }

    public static float time() {
        return (System.nanoTime() - START) / 1.0E9F;
    }

    public static void begin(PoseStack stack, Matrix4f projection, int mode, boolean ignoreDepth, boolean additive) {
        if (!isUsable()) {
            return;
        }
        if (drawing) {
            end();
        }
        drawing = true;
        currentMode = mode;
        vertCount = 0;
        if (verts == null) {
            verts = new float[MAX_VERTS * STRIDE_FLOATS];
        }

        Matrix4f mv = stack.last().pose();
        CAM_RIGHT.set(mv.m00(), mv.m10(), mv.m20()).normalize();
        CAM_UP.set(mv.m01(), mv.m11(), mv.m21()).normalize();
        TMP_LOOK.set(-mv.m02(), -mv.m12(), -mv.m22()).normalize();

        Matrix4f mvp = new Matrix4f(projection).mul(mv);

        prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        prevArrayBuf = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        depthWas = GL11.glGetBoolean(GL11.GL_DEPTH_TEST);
        cullWas = GL11.glGetBoolean(GL11.GL_CULL_FACE);
        blendWas = GL11.glGetBoolean(GL11.GL_BLEND);
        prevBlendSrc = GL11.glGetInteger(GL11.GL_BLEND_SRC);
        prevBlendDst = GL11.glGetInteger(GL11.GL_BLEND_DST);
        prevDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        depthMaskWas = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK); // FIX: save depthMask
        ignoreDepthRestore = ignoreDepth;

        RenderSystem.enableBlend();
        if (additive) {
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        } else {
            RenderSystem.defaultBlendFunc();
        }
        GL11.glDisable(GL11.GL_CULL_FACE);
        if (ignoreDepth) {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
        } else {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
        }
        GL11.glDepthMask(false);

        GL20.glUseProgram(program);
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL20.glUniformMatrix4fv(uMvp, false, mvp.get(new float[16]));
        GL20.glUniform1f(uTime, time());
        GL20.glUniform1i(uMode, mode);
        GL20.glUniform3f(uCamRight, CAM_RIGHT.x, CAM_RIGHT.y, CAM_RIGHT.z);
        GL20.glUniform3f(uCamUp, CAM_UP.x, CAM_UP.y, CAM_UP.z);
    }

    public static void end() {
        if (!drawing) {
            return;
        }
        flush();
        drawing = false;

        GL20.glUseProgram(prevProgram);
        GL30.glBindVertexArray(prevVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevArrayBuf);
        if (cullWas) {
            GL11.glEnable(GL11.GL_CULL_FACE);
        } else {
            GL11.glDisable(GL11.GL_CULL_FACE);
        }
        if (depthWas) {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
        } else {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
        }
        GL11.glDepthFunc(prevDepthFunc);
        GL11.glDepthMask(true);
        RenderSystem.depthMask(true);
        if (!blendWas) {
            GL11.glDisable(GL11.GL_BLEND);
        }
        GL11.glBlendFunc(prevBlendSrc, prevBlendDst);
        if (ignoreDepthRestore && depthWas) {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
        }
        RenderSystem.setShader(CoreShaders.POSITION_COLOR);
    }

    public static void ribbon(float x1, float y1, float z1, float x2, float y2, float z2,
                               float width, Color color, float alpha, float along0, float along1) {
        if (!drawing || currentMode != MODE_RIBBON) {
            return;
        }
        TMP_DIR.set(x2 - x1, y2 - y1, z2 - z1);
        if (TMP_DIR.lengthSquared() < 1.0e-8F) {
            return;
        }
        TMP_DIR.normalize();
        TMP_SIDE.set(TMP_DIR).cross(TMP_LOOK);
        if (TMP_SIDE.lengthSquared() < 1.0e-6F) {
            TMP_SIDE.set(CAM_RIGHT);
        } else {
            TMP_SIDE.normalize();
        }
        TMP_SIDE.mul(width);
        float r = color.getRed() / 255.0F;
        float g = color.getGreen() / 255.0F;
        float b = color.getBlue() / 255.0F;
        quad(
                x1 - TMP_SIDE.x, y1 - TMP_SIDE.y, z1 - TMP_SIDE.z, 0, 0, along0,
                x1 + TMP_SIDE.x, y1 + TMP_SIDE.y, z1 + TMP_SIDE.z, 0, 1, along0,
                x2 + TMP_SIDE.x, y2 + TMP_SIDE.y, z2 + TMP_SIDE.z, 1, 1, along1,
                x2 - TMP_SIDE.x, y2 - TMP_SIDE.y, z2 - TMP_SIDE.z, 1, 0, along1,
                r, g, b, alpha
        );
    }

    public static void holoBox(AABB box, Color color, float alpha) {
        if (!drawing || currentMode != MODE_HOLO) {
            return;
        }
        float r = color.getRed() / 255.0F;
        float g = color.getGreen() / 255.0F;
        float b = color.getBlue() / 255.0F;
        float x0 = (float) box.minX, y0 = (float) box.minY, z0 = (float) box.minZ;
        float x1 = (float) box.maxX, y1 = (float) box.maxY, z1 = (float) box.maxZ;
        face(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r, g, b, alpha); // -Y
        face(x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, r, g, b, alpha); // +Y
        face(x0, y1, z0, x1, y1, z0, x1, y0, z0, x0, y0, z0, r, g, b, alpha); // -Z
        face(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, r, g, b, alpha); // +Z
        face(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, r, g, b, alpha); // -X
        face(x1, y1, z0, x1, y1, z1, x1, y0, z1, x1, y0, z0, r, g, b, alpha); // +X
    }

    public static void waveBox(AABB box, Color color, float alpha) {
        if (!drawing || (currentMode != MODE_VENTURA_WAVE && currentMode != MODE_BLOCK_OVERLAY && currentMode != MODE_HOLO)) {
            return;
        }
        float r = color.getRed() / 255.0F;
        float g = color.getGreen() / 255.0F;
        float b = color.getBlue() / 255.0F;
        float x0 = (float) box.minX, y0 = (float) box.minY, z0 = (float) box.minZ;
        float x1 = (float) box.maxX, y1 = (float) box.maxY, z1 = (float) box.maxZ;
        face(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r, g, b, alpha); // -Y
        face(x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, r, g, b, alpha); // +Y
        face(x0, y1, z0, x1, y1, z0, x1, y0, z0, x0, y0, z0, r, g, b, alpha); // -Z
        face(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, r, g, b, alpha); // +Z
        face(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, r, g, b, alpha); // -X
        face(x1, y1, z0, x1, y1, z1, x1, y0, z1, x1, y0, z0, r, g, b, alpha); // +X
    }

    public static void billboard(float x, float y, float z, float radius, Color color, float alpha, float phase) {
        if (!drawing) {
            return;
        }
        float r = color.getRed() / 255.0F;
        float g = color.getGreen() / 255.0F;
        float b = color.getBlue() / 255.0F;
        float rx = CAM_RIGHT.x * radius, ry = CAM_RIGHT.y * radius, rz = CAM_RIGHT.z * radius;
        float ux = CAM_UP.x * radius, uy = CAM_UP.y * radius, uz = CAM_UP.z * radius;
        quad(
                x - rx - ux, y - ry - uy, z - rz - uz, 0, 0, phase,
                x + rx - ux, y + ry - uy, z + rz - uz, 1, 0, phase,
                x + rx + ux, y + ry + uy, z + rz + uz, 1, 1, phase,
                x - rx + ux, y - ry + uy, z - rz + uz, 0, 1, phase,
                r, g, b, alpha
        );
    }

    private static void face(float x0, float y0, float z0, float x1, float y1, float z1,
                              float x2, float y2, float z2, float x3, float y3, float z3,
                              float r, float g, float b, float a) {
        quad(x0, y0, z0, 0, 0, 0, x1, y1, z1, 1, 0, 0, x2, y2, z2, 1, 1, 0, x3, y3, z3, 0, 1, 0, r, g, b, a);
    }

    private static void quad(float x0, float y0, float z0, float u0, float v0, float s0,
                              float x1, float y1, float z1, float u1, float v1, float s1,
                              float x2, float y2, float z2, float u2, float v2, float s2,
                              float x3, float y3, float z3, float u3, float v3, float s3,
                              float r, float g, float b, float a) {
        vert(x0, y0, z0, u0, v0, r, g, b, a, s0);
        vert(x1, y1, z1, u1, v1, r, g, b, a, s1);
        vert(x2, y2, z2, u2, v2, r, g, b, a, s2);
        vert(x0, y0, z0, u0, v0, r, g, b, a, s0);
        vert(x2, y2, z2, u2, v2, r, g, b, a, s2);
        vert(x3, y3, z3, u3, v3, r, g, b, a, s3);
    }

    private static void vert(float x, float y, float z, float u, float v,
                             float r, float g, float b, float a, float along) {
        if (vertCount + 1 >= MAX_VERTS) {
            flush();
        }
        int i = vertCount * STRIDE_FLOATS;
        verts[i] = x;
        verts[i + 1] = y;
        verts[i + 2] = z;
        verts[i + 3] = u;
        verts[i + 4] = v;
        verts[i + 5] = r;
        verts[i + 6] = g;
        verts[i + 7] = b;
        verts[i + 8] = a;
        verts[i + 9] = along;
        vertCount++;
    }

    private static void flush() {
        if (vertCount == 0) {
            return;
        }
        FloatBuffer buf = org.lwjgl.BufferUtils.createFloatBuffer(vertCount * STRIDE_FLOATS);
        buf.put(verts, 0, vertCount * STRIDE_FLOATS);
        buf.flip();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buf, GL15.GL_DYNAMIC_DRAW);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, vertCount);
        vertCount = 0;
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

            vao = GL30.glGenVertexArrays();
            vbo = GL15.glGenBuffers();
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) MAX_VERTS * STRIDE_FLOATS * Float.BYTES, GL15.GL_DYNAMIC_DRAW);
            int stride = STRIDE_FLOATS * Float.BYTES;
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0);
            GL20.glEnableVertexAttribArray(1);
            GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, stride, 3 * Float.BYTES);
            GL20.glEnableVertexAttribArray(2);
            GL20.glVertexAttribPointer(2, 4, GL11.GL_FLOAT, false, stride, 5 * Float.BYTES);
            GL20.glEnableVertexAttribArray(3);
            GL20.glVertexAttribPointer(3, 1, GL11.GL_FLOAT, false, stride, 9 * Float.BYTES);
            GL30.glBindVertexArray(0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

            uMvp = GL20.glGetUniformLocation(program, "uMvp");
            uTime = GL20.glGetUniformLocation(program, "uTime");
            uMode = GL20.glGetUniformLocation(program, "uMode");
            uCamRight = GL20.glGetUniformLocation(program, "uCamRight");
            uCamUp = GL20.glGetUniformLocation(program, "uCamUp");
        } catch (Throwable t) {
            System.err.println("[BaritoneFX] shader init failed — world FX disabled: " + t);
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
