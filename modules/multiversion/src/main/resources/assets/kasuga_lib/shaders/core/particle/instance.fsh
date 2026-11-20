#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:oit.glsl>

layout(location = 0) in vec4 instanceColor;
#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

void main() {
    if (instanceColor.a <= 0.0) discard;
#ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, instanceColor.a);
#elif defined(OIT_ACCUMULATE)
    fragColor = sampleColorForAccumulation(instanceColor);
#else
    fragColor = instanceColor;
#endif
}
