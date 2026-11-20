#version 330
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) in vec2 texCoord;
#include <kasuga_lib:cloud/sky_parameters.glsl>
#include <kasuga_lib:cloud/depth.glsl>
uniform sampler2D CloudColor;
uniform sampler2D SceneColor;
layout(location = 0) out vec4 fragColor;

vec3 decode(vec3 color) {
    return mix(color / 12.92, pow((max(color, vec3(0)) + .055) / 1.055, vec3(2.4)), greaterThan(color, vec3(.04045)));
}
vec3 encode(vec3 color) {
    return mix(color * 12.92, 1.055 * pow(max(color, vec3(0)), vec3(1.0 / 2.4)) - .055, greaterThan(color, vec3(.0031308)));
}
void main() {
    vec2 uv = texCoord;
    float depth = texture(SceneDepth, uv).r;
    float distance = depth <= .000001 ? 0.0 : length(unprojectCloud(uv, depth));
    vec2 cell = uv * CloudSize - .5, base = floor(cell), fraction = fract(cell);
    vec4 cloud = vec4(0);
    float total = 0.0;
    for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) {
        vec2 sampleUV = (clamp(base + vec2(x, y), vec2(0), CloudSize - 1.0) + .5) / CloudSize;
        float sampleDepth = nearestSceneDepth(sampleUV);
        float compatible = depth <= .000001 ? (sampleDepth <= .000001 ? 1.0 : 0.0)
            : (sampleDepth <= .000001 ? 0.0 : exp(-abs(length(unprojectCloud(sampleUV, sampleDepth)) - distance) / max(.1, distance * .01)));
        float weight = (x == 0 ? 1.0 - fraction.x : fraction.x) * (y == 0 ? 1.0 - fraction.y : fraction.y) * compatible;
        cloud += texture(CloudColor, sampleUV) * weight; total += weight;
    }
    cloud = total > .00001 ? cloud / total : vec4(0);
    vec4 scene = texture(SceneColor, uv);
    // Preserve exact host pixels when the volume contributes nothing. This
    // also avoids decode/encode roundoff at silhouettes and empty sky.
    if (cloud.a <= 0.0) { fragColor = scene; return; }
    vec3 background = DisplayColorSpace != 0 ? decode(scene.rgb) : scene.rgb;
    vec3 result = cloud.rgb + background * (1.0 - clamp(cloud.a, 0.0, 1.0));
    fragColor = vec4(DisplayColorSpace != 0 ? encode(result) : result, scene.a);
}
