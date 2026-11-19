#version 150

uniform sampler2D SceneSampler;
uniform sampler2D DepthSampler;
uniform samplerBuffer LightSampler;
uniform mat4 InverseViewProjection;
uniform int LightCount;
in vec2 texCoord;
out vec4 fragColor;

vec3 positionAt(vec2 uv, float depth) {
    vec4 point = InverseViewProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return point.xyz / point.w;
}

bool isSurface(float depth) {
    return depth < 0.999999;
}

#moj_import <kasuga_lib:lighting/world_lights.glsl>

void main() {
    fragColor = illuminate(0.0);
}
