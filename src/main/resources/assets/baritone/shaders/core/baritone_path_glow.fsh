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
    float pulse = 0.5 + 0.5 * sin(GameTime * 18.849556 + pathPhase * 5.0);
    vec4 color = vertexColor * ColorModulator;

    // Keep the source color while adding a restrained emissive highlight.
    color.rgb = mix(color.rgb, vec3(0.92, 0.98, 1.0), 0.22 * pulse);
    color.rgb *= 1.08 + 0.22 * pulse;
    color.a *= 0.82 + 0.18 * pulse;

    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
