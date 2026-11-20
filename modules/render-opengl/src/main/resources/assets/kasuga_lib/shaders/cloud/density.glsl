uniform sampler3D NoiseVolume;
uniform sampler3D ShapeVolume;
uniform vec3 NoiseOffset;
uniform vec4 Optical; // density, extinction per world unit, erosion, anvil
uniform float Coverage;
uniform vec3 NoiseScale;
uniform vec3 WarpScale;

vec4 sampleCloudNoise(vec3 p, float lod) { return textureLod(NoiseVolume, p, lod); }
vec4 sampleCloudShape(vec3 p, float lod) { return textureLod(ShapeVolume, p, lod); }
// CLOUD_DENSITY_SHARED
