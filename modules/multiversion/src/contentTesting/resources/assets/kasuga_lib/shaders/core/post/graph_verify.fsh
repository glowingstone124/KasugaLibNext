#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D HdrSampler;
uniform sampler2D MainDepthSampler;
uniform sampler2D CopiedDepthSampler;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    float hdr = texture(HdrSampler, texCoord).g;
    float mainDepth = texture(MainDepthSampler, texCoord).r;
    float copiedDepth = texture(CopiedDepthSampler, texCoord).r;
    fragColor = vec4(abs(hdr - 1.2) < 0.002 ? 1.0 : 0.0,
                     abs(mainDepth - copiedDepth) < 0.000001 ? 1.0 : 0.0, 0.0, 1.0);
}
