#version 150

#moj_import <minecraft:fog.glsl>

uniform vec4 ColorModulator;
uniform float GameTime;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in float pathPhase;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    float t = GameTime * 24000.0;
    float pulse = 0.5 + 0.5 * sin(t * 6.28318 * 0.35 + pathPhase * 6.5);
    float scan = exp(-abs(fract(pathPhase * 0.35 - t * 0.45) - 0.5) * 10.0);
    vec4 color = vertexColor * ColorModulator;

    vec3 hot = vec3(0.55, 0.95, 1.0);
    vec3 fringe = vec3(1.0, 0.45, 0.95);
    color.rgb = mix(color.rgb, hot, 0.28 * pulse);
    color.rgb += fringe * scan * 0.22;
    color.rgb *= 1.12 + 0.38 * pulse + 0.35 * scan;
    color.a *= 0.78 + 0.22 * pulse;

    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
