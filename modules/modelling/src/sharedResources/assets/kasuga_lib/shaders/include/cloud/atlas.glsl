uniform sampler2D NoiseVolume;
uniform sampler2D ShapeVolume;

// 64 slices in an 8x8 atlas. A one-texel gutter isolates bilinear filtering in each slice.
vec2 volumeUv(vec2 p, float slice) {
    vec2 tile = vec2(mod(slice, 8.0), floor(slice / 8.0));
    return (tile * 66.0 + 1.0 + p * 64.0) / 528.0;
}
vec4 sampleCloudNoise(vec3 p, float lod) {
    vec3 local = fract(p);
    float z = local.z * 64.0 - .5;
    return mix(texture(NoiseVolume, volumeUv(local.xy, mod(floor(z), 64.0))),
               texture(NoiseVolume, volumeUv(local.xy, mod(floor(z) + 1.0, 64.0))), fract(z));
}
vec4 sampleCloudShape(vec3 p, float lod) {
    vec3 local = clamp(p, 0.0, 1.0);
    float z = clamp(local.z * 64.0 - .5, 0.0, 63.0);
    return mix(texture(ShapeVolume, volumeUv(local.xy, floor(z))),
               texture(ShapeVolume, volumeUv(local.xy, min(floor(z) + 1.0, 63.0))), fract(z));
}
