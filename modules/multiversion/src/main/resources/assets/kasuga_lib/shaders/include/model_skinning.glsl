uniform samplerBuffer KasugaBonePalette;

layout(location = 6) in int BoneBindingType;
layout(location = 7) in ivec4 BoneIndices;
layout(location = 8) in vec4 BoneWeights;
layout(location = 9) in vec3 SdefCenter;
layout(location = 10) in vec3 SdefRadius0;
layout(location = 11) in vec3 SdefRadius1;

int kasugaBoneOffset(int palette, int influence) {
    return palette + BoneIndices[influence] * 9;
}

mat4 kasugaBoneMatrix(int offset) {
    return mat4(texelFetch(KasugaBonePalette, offset),
                texelFetch(KasugaBonePalette, offset + 1),
                texelFetch(KasugaBonePalette, offset + 2),
                texelFetch(KasugaBonePalette, offset + 3));
}

mat3 kasugaBoneNormal(int offset) {
    return mat3(texelFetch(KasugaBonePalette, offset + 4).xyz,
                texelFetch(KasugaBonePalette, offset + 5).xyz,
                texelFetch(KasugaBonePalette, offset + 6).xyz);
}

vec4 kasugaQuaternionProduct(vec4 a, vec4 b) {
    return vec4(a.w * b.xyz + b.w * a.xyz + cross(a.xyz, b.xyz), a.w * b.w - dot(a.xyz, b.xyz));
}

vec3 kasugaRotate(vec4 quaternion, vec3 direction) {
    vec3 axis = quaternion.xyz;
    return direction + 2.0 * cross(axis, cross(axis, direction) + quaternion.w * direction);
}

vec4 kasugaSlerp(vec4 a, vec4 b, float fraction) {
    float cosine = dot(a, b);
    if (cosine < 0.0) {
        cosine = -cosine;
        b = -b;
    }
    if (1.0 - cosine < 0.000001) return normalize(mix(a, b, fraction));
    float angle = acos(min(cosine, 1.0));
    return normalize(a * sin((1.0 - fraction) * angle) + b * sin(fraction * angle));
}

void kasugaLinearSkin(int palette, inout vec3 position, inout vec3 normal) {
    vec3 nextPosition = vec3(0.0);
    vec3 nextNormal = vec3(0.0);
    for (int influence = 0; influence < 4; ++influence) {
        float weight = BoneWeights[influence];
        if (weight == 0.0) continue;
        int offset = kasugaBoneOffset(palette, influence);
        nextPosition += (kasugaBoneMatrix(offset) * vec4(position, 1.0)).xyz * weight;
        nextNormal += (kasugaBoneNormal(offset) * normal) * weight;
    }
    position = nextPosition;
    normal = nextNormal;
}

void kasugaSphericalSkin(int palette, inout vec3 position, inout vec3 normal) {
    int first = kasugaBoneOffset(palette, 0);
    int second = kasugaBoneOffset(palette, 1);
    vec2 weights = BoneWeights.xy / (BoneWeights.x + BoneWeights.y);
    vec4 rotation = kasugaSlerp(texelFetch(KasugaBonePalette, first + 7),
                                texelFetch(KasugaBonePalette, second + 7), weights.y);
    vec3 radius = SdefRadius0 * weights.x + SdefRadius1 * weights.y;
    vec3 firstCenter = (kasugaBoneMatrix(first) * vec4(SdefCenter + SdefRadius0, 1.0)).xyz;
    vec3 secondCenter = (kasugaBoneMatrix(second) * vec4(SdefCenter + SdefRadius1, 1.0)).xyz;
    position = kasugaRotate(rotation, position - SdefCenter - radius)
               + firstCenter * weights.x + secondCenter * weights.y;
    normal = kasugaRotate(rotation, normal);
}

void kasugaDualQuaternionSkin(int palette, inout vec3 position, inout vec3 normal) {
    vec4 real = vec4(0.0);
    vec4 dual = vec4(0.0);
    vec4 reference = vec4(0.0);
    for (int influence = 0; influence < 4; ++influence) {
        float weight = BoneWeights[influence];
        if (weight == 0.0) continue;
        int offset = kasugaBoneOffset(palette, influence);
        vec4 rotation = texelFetch(KasugaBonePalette, offset + 7);
        if (dot(reference, reference) == 0.0) reference = rotation;
        if (dot(reference, rotation) < 0.0) weight = -weight;
        real += rotation * weight;
        dual += texelFetch(KasugaBonePalette, offset + 8) * weight;
    }
    float magnitude = length(real);
    if (magnitude < 0.000001) return;
    real /= magnitude;
    dual /= magnitude;
    dual -= real * dot(real, dual);
    vec3 translation = 2.0 * kasugaQuaternionProduct(dual, vec4(-real.xyz, real.w)).xyz;
    position = kasugaRotate(real, position) + translation;
    normal = kasugaRotate(real, normal);
}

void kasugaSkinModel(int palette, inout vec3 position, inout vec3 normal) {
    switch (BoneBindingType) {
        case 1: kasugaLinearSkin(palette, position, normal); break;
        case 2: kasugaSphericalSkin(palette, position, normal); break;
        case 3: kasugaDualQuaternionSkin(palette, position, normal); break;
    }
}
