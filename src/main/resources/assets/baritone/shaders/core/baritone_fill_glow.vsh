#version 150

#moj_import <minecraft:fog.glsl>

in vec3 Position;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;

out float vertexDistance;
out float fillPhase;
out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexDistance = fog_distance(Position, FogShape);
    fillPhase = dot(Position, vec3(0.09, 0.14, 0.06));
    vertexColor = Color;
}
