#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:projection.glsl>
#include <kasuga_lib:lighting/view.glsl>

layout(location = 0) out vec2 texCoord;
layout(location = 1) flat out mat4 inverseViewProjection;

void main() {
    vec2 uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
    texCoord = uv;
    inverseViewProjection = inverse(ProjMat * ViewRotation);
}
