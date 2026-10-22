package baritone.bypass.modern;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;

public final class SdfShaderManager {
    private static SdfShaderManager instance;

    private final int capsuleProgram;
    private final int iconProgram;
    private final int fontProgram;

    // Uniformy kapsuły
    private final int uCapProj, uCapSize, uCapRealSize, uCapRadius, uCapBorder, uCapShadow;
    // Uniformy ikon
    private final int uIconProj, uIconSize, uIconType;
    // Uniformy fontu / prostokątów
    private final int uFontProj, uFontSampler, uFontUseTex;

    private final int vao;
    private final int vbo;
    private final FloatBuffer vertexBuffer;
    private final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);

    private static final String VERTEX_SHADER = """
            #version 330 core
            layout(location = 0) in vec2 a_Pos;
            layout(location = 1) in vec2 a_UV;
            layout(location = 2) in vec4 a_Color;

            uniform mat4 u_ProjMat;

            out vec2 v_UV;
            out vec4 v_Color;

            void main() {
                v_UV = a_UV;
                v_Color = a_Color;
                gl_Position = u_ProjMat * vec4(a_Pos, 0.0, 1.0);
            }
            """;

    private static final String CAPSULE_FRAGMENT_SHADER = """
            #version 330 core
            in vec2 v_UV;
            in vec4 v_Color;

            uniform vec2 u_Size;
            uniform vec2 u_CapsuleSize;
            uniform float u_Radius;
            uniform float u_BorderWidth;
            uniform float u_ShadowSoft;

            out vec4 FragColor;

            float sdRoundBox(vec2 p, vec2 b, float r) {
                vec2 q = abs(p) - b + r;
                return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
            }

            void main() {
                vec2 p = (v_UV - 0.5) * u_Size;
                vec2 halfBox = u_CapsuleSize * 0.5;
                float r = min(u_Radius, min(halfBox.x, halfBox.y));
                float aa = max(fwidth(p.x), 0.35);

                // Drop shadow
                float dShadow = sdRoundBox(p - vec2(0.0, 1.5), halfBox, r);
                float shadowAlpha = (1.0 - smoothstep(-1.5, u_ShadowSoft, dShadow)) * 0.46;
                vec4 shadowCol = vec4(0.03, 0.02, 0.06, shadowAlpha);

                // Main capsule SDF
                float d = sdRoundBox(p, halfBox, r);
                float fillMask = 1.0 - smoothstep(-aa, aa, d);

                float normY = clamp(p.y / halfBox.y, -1.0, 1.0);
                float gradT = normY * 0.5 + 0.5;

                // Tło: #161226 (góra) -> #120F1F (dół)
                vec3 bgTop = vec3(0.086, 0.071, 0.149);
                vec3 bgBottom = vec3(0.071, 0.059, 0.122);
                vec3 bgColor = mix(bgTop, bgBottom, gradT);
                bgColor += vec3(0.032, 0.024, 0.060) * pow(1.0 - gradT, 2.4);

                // Obramowanie 1px: #63518C (góra) -> #4A3B69 (dół)
                vec3 borderTop = vec3(0.388, 0.318, 0.549);
                vec3 borderBottom = vec3(0.290, 0.231, 0.412);
                vec3 borderColor = mix(borderTop, borderBottom, gradT);

                float innerEdge = d + u_BorderWidth;
                float borderMask = smoothstep(-aa, aa, innerEdge) * fillMask;

                vec3 capsuleRGB = mix(bgColor, borderColor, borderMask);
                float capsuleAlpha = mix(0.94, 1.0, borderMask) * fillMask;

                vec3 finalRGB = mix(shadowCol.rgb, capsuleRGB, fillMask);
                float finalAlpha = max(shadowCol.a * (1.0 - fillMask * 0.85), capsuleAlpha);

                FragColor = vec4(finalRGB, finalAlpha);
            }
            """;

    private static final String ICON_FRAGMENT_SHADER = """
            #version 330 core
            in vec2 v_UV;
            in vec4 v_Color;

            uniform vec2 u_Size;
            uniform int u_IconType;

            out vec4 FragColor;

            float sdRoundBox(vec2 p, vec2 b, float r) {
                vec2 q = abs(p) - b + r;
                return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
            }

            float sdSegment(vec2 p, vec2 a, vec2 b) {
                vec2 pa = p - a, ba = b - a;
                float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
                return length(pa - ba * h);
            }

            float sdTriangleIsosceles(vec2 p, vec2 q) {
                p.x = abs(p.x);
                vec2 a = p - q * clamp(dot(p, q) / dot(q, q), 0.0, 1.0);
                vec2 b = p - q * vec2(clamp(p.x / q.x, 0.0, 1.0), 1.0);
                float s = -sign(q.y);
                vec2 d = min(vec2(dot(a, a), s * (p.x * q.y - p.y * q.x)),
                             vec2(dot(b, b), s * (p.y - q.y)));
                return -sqrt(d.x) * sign(d.y);
            }

            void main() {
                // Znormalizowana przestrzeń -1.0 .. +1.0
                vec2 p = (v_UV - 0.5) * 2.0;
                float aa = max(fwidth(p.x), 0.04);
                vec3 lavender = vec3(0.647, 0.580, 0.976); // #A594F9
                vec3 deepPurple = vec3(0.420, 0.310, 0.780);

                if (u_IconType == 0) {
                    // 1. AVATAR GRACZA: Kosmiczny squircle + świecący gradientowy kryształ/trójkąt
                    float dBox = sdRoundBox(p, vec2(0.88, 0.88), 0.42);
                    float boxMask = 1.0 - smoothstep(-aa, aa, dBox);

                    vec3 bg = mix(vec3(0.22, 0.14, 0.44), vec3(0.11, 0.07, 0.24), p.y * 0.5 + 0.5);
                    float border = smoothstep(-0.14, 0.0, dBox) * boxMask;
                    bg = mix(bg, vec3(0.55, 0.43, 0.88), border * 0.8);

                    // Świecący kryształ / trójkąt w środku
                    vec2 pt = p - vec2(0.0, -0.46);
                    float dTri = sdTriangleIsosceles(pt, vec2(0.46, 0.90)) - 0.04;
                    float glow = exp(-max(dTri, 0.0) * 5.0) * 0.65;
                    float triMask = 1.0 - smoothstep(-aa, aa, dTri);

                    vec3 crystalCol = mix(vec3(0.98, 0.75, 1.0), vec3(0.58, 0.42, 0.98), (p.y + 0.5));
                    vec3 col = bg + vec3(0.65, 0.45, 1.0) * glow;
                    col = mix(col, crystalCol, triMask);

                    FragColor = vec4(col, boxMask);
                } else if (u_IconType == 1) {
                    // 2. SERWER RACK: Dwa zaokrąglone moduły rackowe + diody statusu
                    float rackTop = sdRoundBox(p - vec2(0.0, -0.36), vec2(0.78, 0.25), 0.14);
                    float rackBot = sdRoundBox(p - vec2(0.0, 0.36), vec2(0.78, 0.25), 0.14);
                    float dRack = min(rackTop, rackBot);

                    // Wycięcia diod i szczelin
                    float led1 = length(p - vec2(-0.44, -0.36)) - 0.08;
                    float led2 = length(p - vec2(-0.44, 0.36)) - 0.08;
                    float slot1 = sdSegment(p, vec2(0.05, -0.36), vec2(0.45, -0.36)) - 0.06;
                    float slot2 = sdSegment(p, vec2(0.05, 0.36), vec2(0.45, 0.36)) - 0.06;
                    float cutouts = min(min(led1, led2), min(slot1, slot2));

                    float rackMask = (1.0 - smoothstep(-aa, aa, dRack));
                    float holeMask = 1.0 - smoothstep(-aa, aa, cutouts);

                    vec3 col = mix(lavender, vec3(1.0, 1.0, 1.0), holeMask * 0.9);
                    FragColor = vec4(col, rackMask);
                } else if (u_IconType == 2) {
                    // 3. PING: Fioletowy squircle z białym symbolem fal Wi-Fi
                    float dBox = sdRoundBox(p, vec2(0.86, 0.86), 0.40);
                    float boxMask = 1.0 - smoothstep(-aa, aa, dBox);
                    vec3 sqBg = mix(lavender, deepPurple, p.y * 0.5 + 0.5);

                    vec2 wp = p - vec2(0.0, 0.38);
                    float r = length(wp);
                    float angle = atan(wp.x, -wp.y);
                    float cone = smoothstep(0.78, 0.68, abs(angle));

                    float dotWave = length(wp) - 0.13;
                    float ring1 = abs(r - 0.44) - 0.085;
                    float ring2 = abs(r - 0.76) - 0.085;

                    float wifiMask = (1.0 - smoothstep(-aa, aa, dotWave))
                            + (1.0 - smoothstep(-aa, aa, ring1)) * cone
                            + (1.0 - smoothstep(-aa, aa, ring2)) * cone;
                    wifiMask = clamp(wifiMask, 0.0, 1.0);

                    vec3 col = mix(sqBg, vec3(1.0, 1.0, 1.0), wifiMask);
                    FragColor = vec4(col, boxMask);
                } else if (u_IconType == 3) {
                    // 4. TPS: Procesor / Chip (obudowa + rdzeń + nóżki)
                    float body = abs(sdRoundBox(p, vec2(0.54, 0.54), 0.14)) - 0.09;
                    float core = sdRoundBox(p, vec2(0.24, 0.24), 0.06);

                    // Nóżki procesora (po 2 z każdej strony)
                    vec2 ap = abs(p);
                    float pinsH = sdRoundBox(vec2(ap.x - 0.74, abs(ap.y - 0.25)), vec2(0.12, 0.065), 0.02);
                    float pinsV = sdRoundBox(vec2(abs(ap.x - 0.25), ap.y - 0.74), vec2(0.065, 0.12), 0.02);

                    float dCpu = min(min(body, core), min(pinsH, pinsV));
                    float mask = 1.0 - smoothstep(-aa, aa, dCpu);
                    FragColor = vec4(lavender, mask);
                } else if (u_IconType == 4) {
                    // 5. BPS: Okrągły prędkościomierz ze wskazówką
                    float r = length(p);
                    float ring = abs(r - 0.76) - 0.11;
                    // Wycięcie na dole tarczy
                    float cutBottom = (p.y > 0.48 && abs(p.x) < 0.45) ? 1.0 : 0.0;
                    float arcMask = (1.0 - smoothstep(-aa, aa, ring)) * (1.0 - cutBottom);

                    // Wskazówka wychylona pod kątem + środek
                    float needle = sdSegment(p, vec2(0.0, 0.08), vec2(0.38, -0.34)) - 0.095;
                    float centerDot = length(p - vec2(0.0, 0.08)) - 0.16;
                    float needleMask = 1.0 - smoothstep(-aa, aa, min(needle, centerDot));

                    float totalMask = clamp(arcMask + needleMask, 0.0, 1.0);
                    vec3 col = mix(lavender, vec3(0.95, 0.92, 1.0), needleMask * 0.45);
                    FragColor = vec4(col, totalMask);
                } else {
                    // 6. PSZENICA / FARMING TRACKER: Złoty kłos pszenicy
                    vec3 gold = vec3(1.0, 0.82, 0.12); // #FFD11F
                    float stem = sdSegment(p, vec2(-0.25, 0.65), vec2(0.25, -0.65)) - 0.07;
                    float g1 = length(p - vec2(0.08, -0.35)) - 0.20;
                    float g2 = length(p - vec2(-0.10, -0.15)) - 0.20;
                    float g3 = length(p - vec2(0.12, 0.05)) - 0.20;
                    float g4 = length(p - vec2(-0.08, 0.25)) - 0.20;
                    float dWheat = min(stem, min(min(g1, g2), min(g3, g4)));
                    float wheatMask = 1.0 - smoothstep(-aa, aa, dWheat);
                    FragColor = vec4(gold, wheatMask);
                }
            }
            """;

    private static final String FONT_FRAGMENT_SHADER = """
            #version 330 core
            in vec2 v_UV;
            in vec4 v_Color;

            uniform sampler2D u_Sampler;
            uniform int u_UseTex;

            out vec4 FragColor;

            void main() {
                if (u_UseTex == 1) {
                    vec4 tex = texture(u_Sampler, v_UV);
                    float alpha = tex.a * v_Color.a;
                    if (alpha <= 0.005) {
                        discard;
                    }
                    FragColor = vec4(v_Color.rgb, alpha);
                } else {
                    FragColor = v_Color;
                }
            }
            """;

    private SdfShaderManager() {
        this.capsuleProgram = createProgram(VERTEX_SHADER, CAPSULE_FRAGMENT_SHADER);
        this.iconProgram = createProgram(VERTEX_SHADER, ICON_FRAGMENT_SHADER);
        this.fontProgram = createProgram(VERTEX_SHADER, FONT_FRAGMENT_SHADER);

        this.uCapProj = GL20.glGetUniformLocation(capsuleProgram, "u_ProjMat");
        this.uCapSize = GL20.glGetUniformLocation(capsuleProgram, "u_Size");
        this.uCapRealSize = GL20.glGetUniformLocation(capsuleProgram, "u_CapsuleSize");
        this.uCapRadius = GL20.glGetUniformLocation(capsuleProgram, "u_Radius");
        this.uCapBorder = GL20.glGetUniformLocation(capsuleProgram, "u_BorderWidth");
        this.uCapShadow = GL20.glGetUniformLocation(capsuleProgram, "u_ShadowSoft");

        this.uIconProj = GL20.glGetUniformLocation(iconProgram, "u_ProjMat");
        this.uIconSize = GL20.glGetUniformLocation(iconProgram, "u_Size");
        this.uIconType = GL20.glGetUniformLocation(iconProgram, "u_IconType");

        this.uFontProj = GL20.glGetUniformLocation(fontProgram, "u_ProjMat");
        this.uFontSampler = GL20.glGetUniformLocation(fontProgram, "u_Sampler");
        this.uFontUseTex = GL20.glGetUniformLocation(fontProgram, "u_UseTex");

        this.vao = GL30.glGenVertexArrays();
        this.vbo = GL15.glGenBuffers();
        // Pojemność na 512 quadów (6 wierzchołków * 8 floatów = 48 floatów na quad)
        this.vertexBuffer = BufferUtils.createFloatBuffer(512 * 48);

        GL30.glBindVertexArray(this.vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) this.vertexBuffer.capacity() * Float.BYTES, GL15.GL_DYNAMIC_DRAW);

        int stride = 8 * Float.BYTES;
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, stride, 0L);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, stride, 2L * Float.BYTES);
        GL20.glEnableVertexAttribArray(2);
        GL20.glVertexAttribPointer(2, 4, GL11.GL_FLOAT, false, stride, 4L * Float.BYTES);

        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    public static synchronized SdfShaderManager getInstance() {
        if (instance == null) {
            instance = new SdfShaderManager();
        }
        return instance;
    }

    public void drawCapsule(Matrix4f proj, float x, float y, float width, float height, float radius, float borderWidth) {
        float shadowPad = 8.0f;
        float quadX = x - shadowPad;
        float quadY = y - shadowPad;
        float quadW = width + shadowPad * 2.0f;
        float quadH = height + shadowPad * 2.0f;

        GL20.glUseProgram(this.capsuleProgram);
        uploadMatrix(this.uCapProj, proj);
        GL20.glUniform2f(this.uCapSize, quadW, quadH);
        GL20.glUniform2f(this.uCapRealSize, width, height);
        GL20.glUniform1f(this.uCapRadius, radius);
        GL20.glUniform1f(this.uCapBorder, borderWidth);
        GL20.glUniform1f(this.uCapShadow, 5.5f);

        drawSingleQuad(quadX, quadY, quadW, quadH, 0f, 0f, 1f, 1f, 0xFFFFFFFF);
    }

    public void drawVectorIcon(Matrix4f proj, float x, float y, float size, int iconType) {
        GL20.glUseProgram(this.iconProgram);
        uploadMatrix(this.uIconProj, proj);
        GL20.glUniform2f(this.uIconSize, size, size);
        GL20.glUniform1i(this.uIconType, iconType);

        drawSingleQuad(x, y, size, size, 0f, 0f, 1f, 1f, 0xFFFFFFFF);
    }

    public void drawSeparator(Matrix4f proj, float x, float y, float width, float height, int argbColor) {
        GL20.glUseProgram(this.fontProgram);
        uploadMatrix(this.uFontProj, proj);
        GL20.glUniform1i(this.uFontUseTex, 0);

        drawSingleQuad(x, y, width, height, 0f, 0f, 1f, 1f, argbColor);
    }

    public void beginFontBatch(Matrix4f proj, int textureId) {
        GL20.glUseProgram(this.fontProgram);
        uploadMatrix(this.uFontProj, proj);
        GL20.glUniform1i(this.uFontSampler, 0);
        GL20.glUniform1i(this.uFontUseTex, 1);

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        this.vertexBuffer.clear();
    }

    public void pushQuadToBatch(float x, float y, float w, float h, float u0, float v0, float u1, float v1, int argb) {
        if (this.vertexBuffer.remaining() < 48) {
            flushBatch();
            this.vertexBuffer.clear();
        }
        float a = ((argb >>> 24) & 0xFF) / 255.0f;
        float r = ((argb >>> 16) & 0xFF) / 255.0f;
        float g = ((argb >>> 8) & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;

        float x2 = x + w;
        float y2 = y + h;

        putVertex(x, y, u0, v0, r, g, b, a);
        putVertex(x, y2, u0, v1, r, g, b, a);
        putVertex(x2, y2, u1, v1, r, g, b, a);

        putVertex(x, y, u0, v0, r, g, b, a);
        putVertex(x2, y2, u1, v1, r, g, b, a);
        putVertex(x2, y, u1, v0, r, g, b, a);
    }

    public void flushBatch() {
        int vertexCount = this.vertexBuffer.position() / 8;
        if (vertexCount == 0) {
            return;
        }
        this.vertexBuffer.flip();
        GL30.glBindVertexArray(this.vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.vbo);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, this.vertexBuffer);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, vertexCount);
        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    private void drawSingleQuad(float x, float y, float w, float h, float u0, float v0, float u1, float v1, int argb) {
        this.vertexBuffer.clear();
        pushQuadToBatch(x, y, w, h, u0, v0, u1, v1, argb);
        flushBatch();
    }

    private void putVertex(float x, float y, float u, float v, float r, float g, float b, float a) {
        this.vertexBuffer.put(x).put(y).put(u).put(v).put(r).put(g).put(b).put(a);
    }

    private void uploadMatrix(int location, Matrix4f matrix) {
        this.matrixBuffer.clear();
        matrix.get(this.matrixBuffer);
        GL20.glUniformMatrix4fv(location, false, this.matrixBuffer);
    }

    private static int createProgram(String vshSrc, String fshSrc) {
        int vsh = compileShader(GL20.GL_VERTEX_SHADER, vshSrc);
        int fsh = compileShader(GL20.GL_FRAGMENT_SHADER, fshSrc);
        int prog = GL20.glCreateProgram();
        GL20.glAttachShader(prog, vsh);
        GL20.glAttachShader(prog, fsh);
        GL20.glLinkProgram(prog);

        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(prog);
            throw new IllegalStateException("Błąd linkowania shadera GLSL: " + log);
        }
        GL20.glDeleteShader(vsh);
        GL20.glDeleteShader(fsh);
        return prog;
    }

    private static int compileShader(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            throw new IllegalStateException("Błąd kompilacji shadera GLSL: " + log);
        }
        return shader;
    }
}
