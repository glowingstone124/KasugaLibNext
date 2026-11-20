uniform sampler2D SceneDepth;

vec3 unprojectCloud(vec2 uv, float depth) {
    vec4 point = InverseViewProjection * vec4(uv * 2.0 - 1.0, depth * DepthConvention.x + DepthConvention.y, 1.0);
    return point.xyz / point.w;
}
float nearestSceneDepth(vec2 uv) {
    return max(texture(SceneDepth, uv).r,
        max(max(texture(SceneDepth, uv + DepthFootprint).r, texture(SceneDepth, uv - DepthFootprint).r),
            max(texture(SceneDepth, uv + DepthFootprint * vec2(-1, 1)).r, texture(SceneDepth, uv + DepthFootprint * vec2(1, -1)).r)));
}
