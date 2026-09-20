#version 150

#moj_import <minecraft:fog.glsl>

uniform vec4 ColorModulator;
uniform float GameTime;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in float fillPhase;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    float t = GameTime * 24000.0;
    float pulse = 0.85 + 0.15 * sin(t * 6.28318 * 0.15);
    vec4 color = vertexColor * ColorModulator;

    // Smooth, modern luminous edge glow (no harsh grid or scanlines)
    float edge = 0.5 + 0.5 * abs(sin(fillPhase * 3.14159));
    color.rgb *= (0.95 + 0.25 * pulse) * (0.85 + 0.35 * edge);
    color.a *= 0.22 + 0.10 * pulse;

    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}

