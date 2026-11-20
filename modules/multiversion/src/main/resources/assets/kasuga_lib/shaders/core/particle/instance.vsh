#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:projection.glsl>

layout(std140) uniform ParticleView {
    mat4 ViewRotation;
};
uniform samplerBuffer ParticleInstances;
layout(location = 0) in vec3 Position;
layout(location = 0) out vec4 instanceColor;

void main() {
    int offset = gl_InstanceIndex * 5;
    mat4 model = mat4(texelFetch(ParticleInstances, offset), texelFetch(ParticleInstances, offset + 1),
                      texelFetch(ParticleInstances, offset + 2), texelFetch(ParticleInstances, offset + 3));
    instanceColor = texelFetch(ParticleInstances, offset + 4);
    gl_Position = ProjMat * ViewRotation * model * vec4(Position, 1.0);
}
