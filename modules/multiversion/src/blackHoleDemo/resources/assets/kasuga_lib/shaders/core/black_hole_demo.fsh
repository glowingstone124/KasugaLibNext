#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D SceneSampler;
uniform sampler2D DepthSampler;
layout(std140) uniform LensParameters {
    mat4 Projection;
    mat4 InverseProjection;
    vec4 DepthTransform;
    vec4 CenterRadius;
    vec4 DiskNormalTime;
    vec4 DiskTangent;
    vec4 DiskBitangent;
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

#include <kasuga_lib:black_hole_demo.glsl>

void main() {
    fragColor = blackHoleDemo(texCoord);
}
