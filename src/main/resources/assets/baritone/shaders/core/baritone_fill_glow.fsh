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
    float pulse = 0.88 + 0.12 * sin(t * 6.28318 * 0.20);
    vec4 color = vertexColor * ColorModulator;

    // Elegant, soft translucent fill with gentle subtle breathing
    color.rgb *= (0.95 + 0.15 * pulse);
    color.a *= 0.25 * pulse;

    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}

