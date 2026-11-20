layout(std140) uniform SkyParameters {
    mat4 InverseViewProjection;
    vec4 DepthConvention;
    vec4 SkyGridOrigin;
    vec4 SkySunDirection;
    vec4 SkySunColor;
    vec4 SkyAmbientColor;
    vec4 SkyNoiseOffset;
    vec4 SkyAtmosphereColor;
    vec4 SkyGrid;
    vec4 SkyOptics;
    vec4 SkySizes;
    ivec4 SkyMarch;
    ivec4 SkyFlags;
    vec4 Scattering;
    vec4 Layers[4];
    vec4 LayerBiasDensity[4];
    vec4 SkyLayerNoiseOrigins[4];
    vec4 SkyLayerDetailOrigins[4];
};
#define DepthScale DepthConvention.x
#define DepthOffset DepthConvention.y
#define NearClipDepth 1.0
#define FarClipDepth DepthConvention.y
#define FarDepth 0.0
#define GridOrigin SkyGridOrigin.xyz
#define SunDirection SkySunDirection.xyz
#define SunColor SkySunColor.xyz
#define AmbientColor SkyAmbientColor.xyz
#define NoiseOffset SkyNoiseOffset.xyz
#define AtmosphereColor SkyAtmosphereColor.xyz
#define CellSize SkyGrid.x
#define MaxHeight SkyGrid.y
#define Coverage SkyGrid.z
#define MaxDistance SkyGrid.w
#define Extinction SkyOptics.x
#define Erosion SkyOptics.y
#define DetailFrequency SkyOptics.z
#define AmbientStrength SkyOptics.w
#define CloudSize SkySizes.xy
#define DepthFootprint SkySizes.zw
#define AtmosphereDistance SkyGridOrigin.w
#define JitterPhase SkyNoiseOffset.w
#define GridSize SkyMarch.x
#define SampleBudget SkyMarch.y
#define LayerCount SkyMarch.z
#define StormLayer SkyMarch.w
#define LightSteps SkyFlags.x
#define AdaptiveMarching SkyFlags.y
#define DebugView SkyFlags.z
#define DisplayColorSpace SkyFlags.w
