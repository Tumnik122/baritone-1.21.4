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
    float pulse = 0.5 + 0.5 * sin(GameTime * 12.566371 + fillPhase * 6.0);
    vec4 color = vertexColor * ColorModulator;
    color.rgb = mix(color.rgb, vec3(0.72, 0.94, 1.0), 0.18 * pulse);
    color.a *= 0.72 + 0.28 * pulse;
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
