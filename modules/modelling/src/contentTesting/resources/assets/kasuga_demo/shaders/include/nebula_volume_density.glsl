const vec3 CORE = vec3(2.0, 3.0, 8.0);
const int MARCH_STEPS = 72;

// 16 KB L1-pinned hardware-bilinear 3D value noise using paired (z, z+1) RG channels.
float noise(vec3 p) {
    vec3 i = mod(floor(p), 64.0);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    vec2 uv = (i.xy + vec2(37.0, 17.0) * i.z + f.xy + .5) * (1.0 / 64.0);
    vec2 rg = textureLod(NoiseSampler, uv, 0.0).rg;
    return mix(rg.x, rg.y, f.z);
}

float fbm(vec3 p) {
    float value = .019, amplitude = .553;
    for (int i = 0; i < 3; i++) {
        value += noise(p) * amplitude;
        p = p.yzx * 2.04 + vec3(7.1, 3.7, 1.9);
        amplitude *= .48;
    }
    return value;
}

float fbmFar(vec3 p) {
    float v0 = noise(p);
    p = p.yzx * 2.04 + vec3(7.1, 3.7, 1.9);
    return .0827 + v0 * .553 + noise(p) * .26544;
}
