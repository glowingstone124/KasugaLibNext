#version 330
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) in vec2 texCoord;
uniform sampler2D CurrentColor;
uniform sampler2D CurrentMetadata;
uniform sampler2D HistoryColor;
uniform sampler2D HistoryMetadata;
layout(std140) uniform HistoryParameters {
    mat4 InverseViewProjection;
    mat4 PreviousViewProjection;
    vec4 HistoryDelta;
    vec4 HistoryProjection;
    ivec4 HistoryFlags;
};
layout(location = 0) out vec4 fragColor;
layout(location = 1) out vec2 cloudMetadata;
#define CameraAndWindDelta HistoryDelta.xyz
#define HistoryWeight HistoryDelta.w
#define CloudSize HistoryProjection.xy
#define RelativeDepthTolerance HistoryProjection.z
#define HistoryValid HistoryFlags.x
#define DebugHistory HistoryFlags.y

void main() {
    ivec2 pixel = ivec2(texCoord * CloudSize);
    vec2 uv = (vec2(pixel) + .5) / CloudSize;
    vec4 current = texelFetch(CurrentColor, pixel, 0);
    cloudMetadata = texelFetch(CurrentMetadata, pixel, 0).rg;
    fragColor = current;
    bool accepted = false;
    if (HistoryValid != 0 && cloudMetadata.x > 0.0 && current.a > .0001) {
        vec4 n = InverseViewProjection * vec4(uv * 2.0 - 1.0, 1, 1);
        vec4 f = InverseViewProjection * vec4(uv * 2.0 - 1.0, HistoryProjection.w, 1);
        vec3 origin = n.xyz / n.w;
        vec3 point = origin + normalize(f.xyz - origin * f.w) * cloudMetadata.x + CameraAndWindDelta;
        vec4 previous = PreviousViewProjection * vec4(point, 1);
        vec2 oldUV = previous.xy / max(previous.w, .00001) * .5 + .5;
        vec2 margin = .5 / CloudSize;
        if (previous.w > .00001 && all(greaterThanEqual(oldUV, margin)) && all(lessThanEqual(oldUV, 1.0 - margin))) {
            vec2 oldMetadata = texture(HistoryMetadata, oldUV).rg;
            vec4 oldColor = texture(HistoryColor, oldUV);
            float tolerance = max(2.0, length(point) * RelativeDepthTolerance);
            accepted = oldMetadata.x > 0.0 && oldColor.a > .0001
                && abs(oldMetadata.x - length(point)) < tolerance
                && abs(oldMetadata.y - cloudMetadata.y) < max(2.0, cloudMetadata.y * .02);
            if (accepted) {
                vec4 lo = current, hi = current;
                for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) {
                    vec4 value = texelFetch(CurrentColor, clamp(pixel + ivec2(x, y), ivec2(0), ivec2(CloudSize) - 1), 0);
                    lo = min(lo, value); hi = max(hi, value);
                }
                // Restrict history to the current local radiance/opacity range.
                // Empty current rays never resurrect old cloud silhouettes.
                fragColor = mix(current, clamp(oldColor, lo, hi), HistoryWeight);
            }
        }
    }
    if (DebugHistory != 0) fragColor = accepted ? vec4(0, 1, 0, 1) : vec4(1, 0, 0, 1);
}
