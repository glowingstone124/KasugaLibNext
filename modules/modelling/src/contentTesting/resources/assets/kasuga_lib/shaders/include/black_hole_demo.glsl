// Radii are measured in Schwarzschild radii. The scene is sampled at the
// outgoing direction after integrating the ray in its orbital plane.
const float InfluenceRadius = 9.0;
const float StepAngle = 0.19634954;
const int MaxSteps = 28;

vec3 viewPosition(vec2 uv, float depth) {
    vec4 point = InverseProjection * vec4(2.0 * uv - 1.0, depth, 1.0);
    return point.xyz / max(point.w, 1e-8);
}

float sceneDepth(vec2 uv) {
    return texture(DepthSampler, uv).r * DepthTransform.x + DepthTransform.y;
}

vec4 diskEmission(vec3 position, vec3 direction, float radius) {
    float edge = smoothstep(2.7, 3.1, radius) * (1.0 - smoothstep(5.4, 7.2, radius));
    if (edge <= 0.0) return vec4(0.0);
    float angle = atan(dot(position, DiskBitangent.xyz), dot(position, DiskTangent.xyz));
    float phase = angle - DiskNormalTime.w * inversesqrt(radius) / radius;
    float filament = 0.62 + 0.21 * sin(8.0 * phase + 17.0 * radius)
        + 0.17 * sin(17.0 * phase - 31.0 * radius);
    vec3 velocity = cross(DiskNormalTime.xyz, position) / radius;
    float beaming = 1.0 / (1.0 + 0.43 * dot(velocity, direction));
    float shift = beaming * sqrt(1.0 - 1.0 / radius);
    vec3 ember = vec3(1.0, 0.18, 0.021);
    vec3 hot = vec3(1.0, 0.88, 0.59);
    float heat = clamp((5.7 - radius) * 0.33 * shift, 0.0, 1.0);
    vec3 emission = mix(ember, hot, heat) * (4.4 * edge * filament * shift * shift * shift / radius);
    return vec4(emission, 0.53 * edge);
}

bool nearerGeometry(vec2 uv, float viewZ) {
    return viewPosition(uv, sceneDepth(uv)).z > viewZ;
}

vec4 blackHoleDemo(vec2 uv) {
    vec3 direction = normalize(viewPosition(uv, DepthTransform.z));
    vec3 observer = -CenterRadius.xyz / CenterRadius.w;
    float axial = -dot(observer, direction);
    vec3 closest = observer + direction * axial;
    float impact2 = dot(closest, closest);
    float chord2 = InfluenceRadius * InfluenceRadius - impact2;
    if (chord2 <= 0.0) discard;
    float chord = sqrt(chord2);
    if (axial + chord <= 0.0 || nearerGeometry(uv, CenterRadius.z)) discard;

    vec3 background = texture(SceneSampler, uv).rgb;
    float fade = 1.0 - smoothstep(7.2 * 7.2, InfluenceRadius * InfluenceRadius, impact2);
    vec3 start = observer + max(0.0, axial - chord) * direction;
    float startRadius = length(start);
    if (startRadius <= 1.0 || impact2 < 0.00001) return vec4(0.0, 0.0, 0.0, 1.0);

    vec3 radial = start / startRadius;
    float radialSpeed = dot(radial, direction);
    vec3 sideways = direction - radialSpeed * radial;
    float transverseSpeed = length(sideways);
    vec3 tangent = sideways / max(transverseSpeed, 0.00001);
    float inverseRadius = 1.0 / startRadius;
    float inverseImpact = sqrt(1.0 - inverseRadius) / (startRadius * transverseSpeed);
    float slope = -radialSpeed * inverseImpact;
    // The geodesic lies in a plane. Its intersections with the disk are half a turn apart.
    // Find their angles once so the integration loop only advances scalar quantities.
    vec2 diskPlane = vec2(dot(radial, DiskNormalTime.xyz), dot(tangent, DiskNormalTime.xyz));
    float pi = 3.14159265;
    float diskAngle = length(diskPlane) > 0.00001 ? mod(atan(-diskPlane.x, diskPlane.y), pi) : 10.0;
    if (diskAngle == 0.0) diskAngle = pi;
    float angle = 0.0;
    vec3 emission = vec3(0.0);
    float transmission = 1.0;
    bool escaped = false;

    for (int step = 0; step < MaxSteps; ++step) {
        // Schwarzschild null geodesic in its orbital plane: u'' = 1.5u² - u.
        float midpoint = inverseRadius + 0.5 * StepAngle * slope;
        float nextInverseRadius = inverseRadius + StepAngle * (slope
            + 0.5 * StepAngle * inverseRadius * (1.5 * inverseRadius - 1.0));
        slope += StepAngle * midpoint * (1.5 * midpoint - 1.0);
        float nextAngle = angle + StepAngle;

        if (diskAngle <= nextAngle) {
            float fraction = (diskAngle - angle) / StepAngle;
            float diskInverseRadius = mix(inverseRadius, nextInverseRadius, fraction);
            if (diskInverseRadius > 1.0 / 7.2 && diskInverseRadius < 1.0 / 2.7) {
                float c = cos(diskAngle);
                float s = sin(diskAngle);
                vec3 diskRadial = c * radial + s * tangent;
                vec3 diskTangent = c * tangent - s * radial;
                float diskRadius = 1.0 / diskInverseRadius;
                vec3 travel = normalize(-slope * diskRadial + diskTangent * diskInverseRadius);
                vec4 disk = diskEmission(diskRadial * diskRadius, travel, diskRadius);
                emission += transmission * disk.rgb;
                transmission *= 1.0 - disk.a;
            }
            diskAngle += pi;
        }
        inverseRadius = nextInverseRadius;
        angle = nextAngle;
        if (inverseRadius >= 1.0) break;
        if (inverseRadius < 1.0 / InfluenceRadius && slope < 0.0) {
            escaped = true;
            break;
        }
    }

    vec3 lensed = vec3(0.0);
    if (escaped) {
        float c = cos(angle);
        float s = sin(angle);
        vec3 outgoing = -slope * (c * radial + s * tangent)
            + max(0.0, inverseRadius) * (c * tangent - s * radial);
        vec4 projected = Projection * vec4(outgoing, 0.0);
        vec2 bent = projected.xy / max(0.0001, projected.w) * 0.5 + 0.5;
        // Ease back to the original ray at the frame boundary. Clamping alone stretches a single pixel.
        float inside = smoothstep(0.0, 0.07, min(min(bent.x, 1.0 - bent.x), min(bent.y, 1.0 - bent.y)));
        vec2 warped = mix(uv, clamp(bent, 0.0, 1.0), inside * fade);
        lensed = nearerGeometry(warped, CenterRadius.z) ? background : texture(SceneSampler, warped).rgb;
    }
    vec3 color = 1.0 - (1.0 - clamp(lensed * transmission, 0.0, 1.0)) * exp(-emission);
    return vec4(mix(background, color, fade), 1.0);
}
