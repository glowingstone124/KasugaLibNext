#version 150
uniform sampler2D SceneSampler;
uniform sampler2D DepthSampler;
uniform sampler2D HeightSampler;
uniform mat4 InverseViewProjection;
uniform mat4 ViewProjection;
uniform vec3 Eye;
uniform vec4 WaterDomain; // centre X/Z, simulated extent, mean surface Y
uniform float Time;
uniform float CellSize;
uniform vec3 WaterAbsorption;
uniform vec3 WaterScattering;
uniform float ShorelineFoam;
in vec2 texCoord;
out vec4 fragColor;

// Scale and offset for NDC depth, followed by the scene depth clear value.
const vec3 DepthConvention = vec3(2.0, -1.0, 1.0);

#moj_import <kasuga_lib:water/clear_surface.glsl>
