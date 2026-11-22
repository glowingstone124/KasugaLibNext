#version 330
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) in vec3 Position;
layout(location = 1) in vec3 Normal;
layout(location = 2) in vec2 UV;
layout(location = 3) in vec4 Color;
layout(location = 4) in vec4 Material;

layout(std140) uniform CoastFrame {
    mat4 ViewProjection;
};

layout(location = 0) out vec3 vNormal;
layout(location = 1) out vec2 vUv;
layout(location = 2) out vec4 vColor;

void main() {
    vNormal = normalize(Normal);
    vUv = vec2(UV.x, 1.0 - UV.y);
    vColor = Color;
    gl_Position = ViewProjection * vec4(Position, 1.0);
}
