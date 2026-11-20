#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D SceneSampler;
uniform sampler2D DepthSampler;
uniform sampler2D HeightSampler;
layout(std140) uniform WaterParameters {
    mat4 InverseViewProjection;
    mat4 ViewProjection;
    vec4 WaterEye;
    vec4 WaterDomain;
    vec4 Absorption;
    vec4 Scattering;
    vec4 WaveParameters;
    vec4 DepthConvention;
};
#define Eye WaterEye.xyz
#define WaterAbsorption Absorption.xyz
#define WaterScattering Scattering.xyz
#define Time WaveParameters.x
#define CellSize WaveParameters.y
#define ShorelineFoam WaveParameters.z

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

#include <kasuga_lib:water/clear_surface.glsl>
