#version 150
uniform sampler2D SceneDepth, WeatherLayout, WeatherDimensions, WeatherLayers;
uniform sampler3D NoiseVolume, ShapeVolume;
uniform mat4 InverseViewProjection;
uniform vec4 Viewport;
uniform vec2 DepthFootprint;
uniform vec3 GridOrigin, SunDirection, SunColor, AmbientColor, NoiseOffset, AtmosphereColor;
uniform float CellSize, MaxHeight, Coverage, MaxDistance;
uniform int GridSize, SampleBudget, LayerCount, StormLayer, LightSteps;
uniform int AdaptiveMarching, DebugView, DisplayColorSpace;
uniform float JitterPhase;
uniform float Extinction, Erosion, DetailFrequency, AmbientStrength, AtmosphereDistance;
uniform vec4 Scattering; // forward g, backward g, backward weight, higher-order energy
// Base relative to camera, thickness, noise scale, profile type.
uniform vec4 Layers[4];
uniform float LayerBias[4], LayerDensity[4];
uniform vec3 LayerNoiseOrigin[4];
uniform vec3 LayerDetailOrigin[4];
out vec4 fragColor;
out vec2 cloudMetadata;

const float DepthScale = 2.0;
const float DepthOffset = -1.0;
const float NearClipDepth = -1.0;
const float FarClipDepth = 1.0;
const float FarDepth = 1.0;
vec2 skyUV() { return (gl_FragCoord.xy - Viewport.xy) / Viewport.zw; }
vec4 sampleCloudNoise(vec3 p, float lod) { return textureLod(NoiseVolume, p, lod); }
vec4 sampleCloudShape(vec3 p, float lod) { return textureLod(ShapeVolume, p, lod); }
float sceneDepth(vec2 uv) {
    return min(texture(SceneDepth, uv).r, min(min(texture(SceneDepth, uv + DepthFootprint).r,
        texture(SceneDepth, uv - DepthFootprint).r), min(texture(SceneDepth, uv + DepthFootprint * vec2(-1, 1)).r,
        texture(SceneDepth, uv + DepthFootprint * vec2(1, -1)).r)));
}
float skyLayerBias(int layer) { return LayerBias[layer]; }
float skyLayerDensity(int layer) { return LayerDensity[layer]; }
vec3 skyLayerNoiseOrigin(int layer) { return LayerNoiseOrigin[layer]; }
vec3 skyLayerDetailOrigin(int layer) { return LayerDetailOrigin[layer]; }
// CLOUD_SKY_SHARED
