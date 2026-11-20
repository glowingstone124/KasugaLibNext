#version 330
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
layout(location = 1) out vec2 cloudMetadata;
uniform sampler2D WeatherLayout;
uniform sampler2D WeatherDimensions;
uniform sampler2D WeatherLayers;

#include <kasuga_lib:cloud/sky_parameters.glsl>
#include <kasuga_lib:cloud/depth.glsl>
#include <kasuga_lib:cloud/atlas.glsl>

vec2 skyUV() { return texCoord; }
float sceneDepth(vec2 uv) { return nearestSceneDepth(uv); }
float skyLayerBias(int layer) { return LayerBiasDensity[layer].x; }
float skyLayerDensity(int layer) { return LayerBiasDensity[layer].y; }
vec3 skyLayerNoiseOrigin(int layer) { return SkyLayerNoiseOrigins[layer].xyz; }
vec3 skyLayerDetailOrigin(int layer) { return SkyLayerDetailOrigins[layer].xyz; }

#include <kasuga_lib:cloud/sky_march.glsl>
