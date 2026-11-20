layout(std140) uniform CloudParameters {
    mat4 InverseViewProjection;
    mat4 WorldToLocal;
    vec4 Optical;
    vec4 NoiseOffsetCoverage;
    vec4 CloudNoiseScale;
    vec4 CloudWarpScale;
    vec4 LightDirectionSteps;
    vec4 LightColorGrid;
    vec4 CloudAmbient;
    vec4 CloudSizeDepthFootprint;
    vec4 DepthConvention;
    vec4 LocalLightTangent;
    vec4 LocalLightBitangent;
};
#define NoiseOffset NoiseOffsetCoverage.xyz
#define Coverage NoiseOffsetCoverage.w
#define NoiseScale CloudNoiseScale.xyz
#define WarpScale CloudWarpScale.xyz
#define DepthFootprint CloudSizeDepthFootprint.zw
#define LightGridSize int(LightColorGrid.w)
