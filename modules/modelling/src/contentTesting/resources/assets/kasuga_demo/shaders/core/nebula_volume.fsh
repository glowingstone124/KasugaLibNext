#version 150

uniform sampler2D NoiseSampler;
uniform sampler2D CellSampler;
uniform samplerBuffer DataSampler;

vec4 dataAt(int x, int y) { return texelFetch(DataSampler, y * 64 + x); }
uniform sampler2D CraftSurfaceSampler;
uniform sampler2D CraftNormalSampler;
uniform sampler2D CraftPbrSampler;
uniform sampler2D CraftDepthSampler;

uniform mat4 InverseProjection;
uniform mat4 InverseView;
uniform vec3 Eye;
uniform float Time;
uniform int SegmentCount;
uniform int LightCount;
uniform int StarCount;
uniform int DustCount;
uniform vec3 CraftPosition;
uniform mat4 CraftInverse;
uniform vec3 WakeBoundsMin;
uniform vec3 WakeBoundsMax;
uniform vec3 WakeSubMin0;
uniform vec3 WakeSubMax0;
uniform vec3 WakeSubMin1;
uniform vec3 WakeSubMax1;
uniform vec3 WakeSubMin2;
uniform vec3 WakeSubMax2;
uniform float InteractionStrength;
uniform float CraftVisible;
uniform float CoreStrength;
uniform float DetailStrength;
uniform float GlobalFlash;
uniform float ShipScale;
uniform int CraftDebug;
uniform float IndirectStrength;
uniform float SmokeStrength;
uniform vec3 CraftVelocity;

uniform vec3 CraftForward;
uniform vec3 CraftRight;
uniform vec3 CraftUp;
uniform float WarpCharge;
uniform float WarpActive;
uniform float WarpStretch;
uniform float WarpFlash;
uniform float WarpDistance;
uniform float WarpTunnelPhase;
uniform vec2 WarpShock;
uniform vec4 NacelleParticleSphere;
uniform int NacelleParticleCount;

in vec2 texCoord;
out vec4 fragColor;

bool craftDepthPresent(float depth) { return depth < .999999; }
float craftClipDepth(float depth) { return depth * 2.0 - 1.0; }

#moj_import <kasuga_demo:nebula_volume_density.glsl>
#moj_import <kasuga_demo:nebula_warp_medium.glsl>
#moj_import <kasuga_demo:nebula_volume_sampling.glsl>
#moj_import <kasuga_demo:nebula_hull_lighting.glsl>
#moj_import <kasuga_demo:nebula_volume_render.glsl>
