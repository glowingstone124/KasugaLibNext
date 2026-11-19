#version 330
#extension GL_ARB_separate_shader_objects : require

#include <kasuga_lib:lighting/view.glsl>

uniform sampler2D SceneSampler;
uniform sampler2D DepthSampler;
uniform samplerBuffer LightSampler;

layout(location = 0) in vec2 texCoord;
layout(location = 1) flat in mat4 inverseViewProjection;
layout(location = 0) out vec4 fragColor;

vec3 positionAt(vec2 uv, float depth) {
    float clipZ = ZeroToOneDepth != 0 ? depth : depth * 2.0 - 1.0;
    vec4 point = inverseViewProjection * vec4(uv * 2.0 - 1.0, clipZ, 1.0);
    return point.xyz / point.w;
}

bool isSurface(float depth) {
    return depth > 0.0;
}

#include <kasuga_lib:lighting/world_lights.glsl>

void main() {
    fragColor = illuminate(1.0);
}
