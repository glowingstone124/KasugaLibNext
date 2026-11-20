#version 330
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) in vec2 texCoord;
#include <kasuga_lib:cloud/parameters.glsl>
#include <kasuga_lib:cloud/atlas.glsl>
#include <kasuga_lib:cloud/density.glsl>
layout(location = 0) out float fragColor;
#define LocalSunDirection LightDirectionSteps.xyz
#define LightSteps int(LightDirectionSteps.w)
#define LocalSunTangent LocalLightTangent.xyz
#define LocalSunBitangent LocalLightBitangent.xyz

void main() {
    vec2 coordinate = texCoord * CloudSizeDepthFootprint.xy;
    vec2 tile = floor(coordinate / float(LightGridSize));
    float slice = tile.x + tile.y * 8.0;
    if (slice >= float(LightGridSize)) { fragColor = 0.0; return; }
    vec2 cell = mod(coordinate, float(LightGridSize));
    vec3 p = vec3(cell, slice + .5) / float(LightGridSize);
    p = p * vec3(2, 1, 2) - vec3(1, 0, 1);
    float stepLength = max(0.0, intersectVolume(p, LocalSunDirection).y) / float(LightSteps);
    float opticalDepth = 0.0;
    for (int i = 0; i < 8; i++) {
        if (i >= LightSteps) break;
        float distance = (float(i) + .5) * stepLength;
        float angle = float(i) * 2.39996323;
        vec3 offset = (LocalSunTangent * cos(angle) + LocalSunBitangent * sin(angle)) * distance * .045;
        opticalDepth += densityAt(p + LocalSunDirection * distance + offset, true, 1.0) * stepLength * Optical.y;
    }
    fragColor = opticalDepth;
}
