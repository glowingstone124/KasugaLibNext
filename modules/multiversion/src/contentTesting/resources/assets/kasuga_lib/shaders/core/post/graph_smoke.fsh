#version 330
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 hdrColor;
layout(location = 1) out vec4 ldrColor;

void main() {
    hdrColor = vec4(0.05, 1.2, 0.1, 1.0);
    ldrColor = texCoord.x < 0.5 ? vec4(0.8, 0.12, 0.51, 1.0) : vec4(0.15, 0.15, 0.8, 1.0);
}
