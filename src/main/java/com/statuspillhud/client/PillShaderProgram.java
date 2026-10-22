package com.statuspillhud.client;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * GLSL Shader Program dla renderowania kapsuły Status Pill HUD za pomocą Signed Distance Fields (SDF).
 * Zapewnia idealnie gładkie, antyaliasingowane zaokrąglone narożniki, gradient tła, obramowanie i poświatę.
 */
public class PillShaderProgram {

    private static final Logger LOGGER = LoggerFactory.getLogger(PillShaderProgram.class);

    private static final String VERTEX_SHADER = """
        #version 330 core
        layout (location = 0) in vec2 aPosition;
        layout (location = 1) in vec2 aUV;

        uniform mat4 uModelView;
        uniform mat4 uProjection;
        uniform vec2 uPosition;
        uniform vec2 uSize;

        out vec2 vUV;
        out vec2 vLocalPos;

        void main() {
            vUV = aUV;
            vec2 corner = aPosition * 0.5 + 0.5;
            vLocalPos = corner * uSize;
            gl_Position = uProjection * uModelView * vec4(uPosition + corner * uSize, 0.0, 1.0);
        }
        """;

    private static final String FRAGMENT_SHADER = """
        #version 330 core
        in vec2 vUV;
        in vec2 vLocalPos;

        uniform vec2 uSize;
        uniform float uCornerRadius;
        uniform float uTime;
        uniform float uGlowIntensity;
        uniform float uGuiScale;
        uniform vec4 uColorTop;
        uniform vec4 uColorBottom;
        uniform vec4 uAccentColor;
        uniform vec4 uAccentCyan;

        out vec4 fragColor;

        // 2D Signed Distance Field dla zaokrąglonego prostokąta
        float sdRoundBox(vec2 p, vec2 b, float r) {
            vec2 q = abs(p) - b + r;
            return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
        }

        void main() {
            vec2 halfSize = uSize * 0.5;
            vec2 p = vLocalPos - halfSize;
            float r = min(uCornerRadius, min(halfSize.x, halfSize.y));
            float dist = sdRoundBox(p, halfSize, r);

            // Antyaliasing na krawędzi kapsuły (1px smoothstep)
            float aa = 1.0 - smoothstep(-0.8, 0.8, dist);
            if (aa <= 0.001) {
                discard;
            }

            // Główny gradient pionowy (uColorTop -> uColorBottom)
            vec4 bg = mix(uColorTop, uColorBottom, vUV.y);

            // Subtelny efekt szklanego odbłysku na górnej krawędzi
            float topHighlight = smoothstep(2.5, 0.0, vLocalPos.y) * 0.16;
            bg.rgb += vec3(topHighlight);

            // Poziomy gradient akcentu neonowego fioletu do cyjanu z subtelnym pulsem
            float shimmer = 0.5 + 0.5 * sin(vUV.x * 3.14159 + uTime * 1.5);
            vec4 borderCol = mix(uAccentColor, uAccentCyan, vUV.x * 0.7 + shimmer * 0.3);

            // 1-pikselowe obramowanie z wewnętrznym odcięciem
            float border = smoothstep(-1.2, -0.2, dist) * smoothstep(0.8, -0.2, dist);

            // Zewnętrzna miękka poświata (glow)
            float glow = exp(-max(dist, 0.0) * 1.0) * uGlowIntensity;

            vec4 finalCol = bg;
            finalCol = mix(finalCol, vec4(borderCol.rgb, 0.95), border * 0.75);
            finalCol.rgb += borderCol.rgb * glow * 0.35;

            fragColor = vec4(finalCol.rgb, finalCol.a * aa);
        }
        """;

    private int programId = -1;
    private boolean compiled = false;
    private final Map<String, Integer> uniformLocationCache = new HashMap<>();

    public PillShaderProgram() {
        compile();
    }

    private void compile() {
        try {
            int vertShader = compileShader(GL20.GL_VERTEX_SHADER, VERTEX_SHADER);
            if (vertShader == 0) return;

            int fragShader = compileShader(GL20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
            if (fragShader == 0) {
                GL20.glDeleteShader(vertShader);
                return;
            }

            programId = GL20.glCreateProgram();
            GL20.glAttachShader(programId, vertShader);
            GL20.glAttachShader(programId, fragShader);
            GL20.glLinkProgram(programId);

            int linkStatus = GL20.glGetProgrami(programId, GL20.GL_LINK_STATUS);
            if (linkStatus == GL11.GL_FALSE) {
                String log = GL20.glGetProgramInfoLog(programId);
                LOGGER.error("[PillShaderProgram] Błąd linkowania shadera: {}", log);
                GL20.glDeleteProgram(programId);
                programId = -1;
            } else {
                compiled = true;
                LOGGER.info("[PillShaderProgram] Shader SDF skompilowany pomyślnie (Program ID: {})", programId);
            }

            GL20.glDeleteShader(vertShader);
            GL20.glDeleteShader(fragShader);
        } catch (Throwable t) {
            LOGGER.error("[PillShaderProgram] Wyjątek podczas kompilacji shadera: {}", t.getMessage());
            compiled = false;
            programId = -1;
        }
    }

    private int compileShader(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);

        int status = GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS);
        if (status == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            LOGGER.error("[PillShaderProgram] Błąd kompilacji shadera (typ {}): {}", type, log);
            GL20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    public boolean isCompiled() {
        return compiled && programId > 0;
    }

    public int getProgramId() {
        return programId;
    }

    public int getUniformLocation(String name) {
        if (!compiled || programId <= 0) return -1;
        return uniformLocationCache.computeIfAbsent(name, n -> GL20.glGetUniformLocation(programId, n));
    }

    public void delete() {
        if (programId > 0) {
            GL20.glDeleteProgram(programId);
            programId = -1;
            compiled = false;
            uniformLocationCache.clear();
        }
    }
}
