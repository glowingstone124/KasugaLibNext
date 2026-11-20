#version 330
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) in vec2 texCoord;
#include <kasuga_lib:cloud/parameters.glsl>
#include <kasuga_lib:cloud/depth.glsl>
uniform sampler2D CloudColor;
layout(location = 0) out vec4 fragColor;
#define CloudSize CloudSizeDepthFootprint.xy

void main() {
    vec2 uv = texCoord;
    float targetDepth = texture(SceneDepth, uv).r;
    float targetDistance = targetDepth <= .000001 ? 0.0 : length(unprojectCloud(uv, targetDepth));
    vec2 cell = uv * CloudSize - .5, base = floor(cell), fraction = fract(cell);
    vec4 color = vec4(0);
    float total = 0.0;
    for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) {
        vec2 sampleUV = (clamp(base + vec2(x, y), vec2(0), CloudSize - 1.0) + .5) / CloudSize;
        float depth = nearestSceneDepth(sampleUV);
        // Do not interpolate a cloud sample across an opaque silhouette.
        float compatible = targetDepth <= .000001 ? (depth <= .000001 ? 1.0 : 0.0)
            : (depth <= .000001 ? 0.0 : exp(-abs(length(unprojectCloud(sampleUV, depth)) - targetDistance) / max(.1, targetDistance * .01)));
        float weight = (x == 0 ? 1.0 - fraction.x : fraction.x) * (y == 0 ? 1.0 - fraction.y : fraction.y) * compatible;
        color += texture(CloudColor, sampleUV) * weight; total += weight;
    }
    fragColor = total > .00001 ? color / total : vec4(0);
}
