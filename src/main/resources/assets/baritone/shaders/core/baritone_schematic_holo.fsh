#version 150

#moj_import <minecraft:fog.glsl>

uniform vec4 ColorModulator;
uniform float GameTime;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in vec3 worldPos;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    float t = GameTime * 24000.0;
    vec4 baseColor = vertexColor * ColorModulator;

    // 1. Screen-Space Partial Derivatives Normal Reconstruction (Zero CPU overhead)
    vec3 dX = dFdx(worldPos);
    vec3 dY = dFdy(worldPos);
    vec3 N = normalize(cross(dX, dY));
    vec3 V = normalize(-worldPos); // in camera space, camera is at origin

    // 2. Fresnel Edge Glow (Rim lighting in Obsidian & Lilac Glass aesthetic)
    float NdotV = abs(dot(N, V));
    float fresnel = pow(clamp(1.0 - NdotV, 0.0, 1.0), 2.2);

    // 3. Dual-Harmonic World-Space Scanline Wave
    float macroWave = smoothstep(0.90, 1.0, sin(worldPos.y * 0.85 - t * 2.8) * 0.5 + 0.5);
    float microWave = 0.88 + 0.12 * sin(worldPos.y * 16.0 * 3.14159 + t * 5.0);

    // 4. Status Pulse Modulation (Wrong blocks pulse rapidly 4.5Hz; Missing breathe smoothly 1.2Hz)
    // Check if color is predominantly red (WRONG_BLOCK)
    bool isWrongBlock = (baseColor.r > 0.85 && baseColor.g < 0.45 && baseColor.b < 0.45);
    float pulseSpeed = isWrongBlock ? 9.0 : 2.5;
    float pulse = 0.82 + 0.18 * sin(t * pulseSpeed + dot(worldPos, vec3(0.35)));

    // 5. Final Color Composition
    vec3 lilacHighlight = vec3(0.88, 0.72, 1.0); // #E0B0FF
    vec3 cyanScan = vec3(0.15, 0.90, 1.0);        // #26E6FF

    vec3 rgb = baseColor.rgb * microWave * pulse;
    rgb += lilacHighlight * (fresnel * 0.55);
    rgb += cyanScan * (macroWave * 0.35);

    float alpha = clamp(baseColor.a * (0.85 + 0.45 * fresnel + 0.30 * macroWave), 0.12, 0.95);

    vec4 finalColor = vec4(rgb, alpha);
    fragColor = linear_fog(finalColor, vertexDistance, FogStart, FogEnd, FogColor);
}
