#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D CoastMap;

layout(location = 0) in vec3 vNormal;
layout(location = 1) in vec2 vUv;
layout(location = 2) in vec4 vColor;
layout(location = 0) out vec4 fragColor;

void main() {
    vec3 albedo = texture(CoastMap, vUv).rgb * vColor.rgb;
    vec3 normal = normalize(vNormal);
    float hemi = 0.82 + 0.18 * max(normal.y, 0.0);
    float sun = max(dot(normal, normalize(vec3(-0.42, 0.82, 0.31))), 0.0);
    fragColor = vec4(albedo * (0.62 * hemi + 0.34 * sun + 0.10), 1.0);
}
