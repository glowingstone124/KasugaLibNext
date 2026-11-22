#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D NoiseSampler;
uniform sampler2D CellSampler;
uniform samplerBuffer DataSampler;
uniform sampler2D CraftSurfaceSampler;
uniform sampler2D CraftNormalSampler;
uniform sampler2D CraftPbrSampler;
uniform sampler2D CraftDepthSampler;

layout(std140) uniform NebulaFrame {
    mat4 InverseProjection;
    mat4 InverseView;
    mat4 CraftInverse;
    vec4 EyeTime;
    ivec4 Counts;
    vec4 PositionVisible;
    vec4 VelocityScale;
    vec4 ForwardInteraction;
    vec4 RightIndirect;
    vec4 UpSmoke;
    vec4 Bounds[8];
    vec4 Warp;
    vec4 WarpTravel;
    vec4 NacelleParticleSphere;
    ivec4 Controls;
    vec4 Lighting;
};

#define Eye EyeTime.xyz
#define Time EyeTime.w
#define SegmentCount Counts.x
#define LightCount Counts.y
#define StarCount Counts.z
#define DustCount Counts.w
#define CraftPosition PositionVisible.xyz
#define CraftVisible PositionVisible.w
#define CraftVelocity VelocityScale.xyz
#define ShipScale VelocityScale.w
#define CraftForward ForwardInteraction.xyz
#define InteractionStrength ForwardInteraction.w
#define CraftRight RightIndirect.xyz
#define IndirectStrength RightIndirect.w
#define CraftUp UpSmoke.xyz
#define SmokeStrength UpSmoke.w
#define WakeBoundsMin Bounds[0].xyz
#define WakeBoundsMax Bounds[1].xyz
#define WakeSubMin0 Bounds[2].xyz
#define WakeSubMax0 Bounds[3].xyz
#define WakeSubMin1 Bounds[4].xyz
#define WakeSubMax1 Bounds[5].xyz
#define WakeSubMin2 Bounds[6].xyz
#define WakeSubMax2 Bounds[7].xyz
#define WarpCharge Warp.x
#define WarpActive Warp.y
#define WarpStretch Warp.z
#define WarpFlash Warp.w
#define WarpDistance WarpTravel.x
#define WarpTunnelPhase WarpTravel.y
#define WarpShock WarpTravel.zw
#define CraftDebug Controls.x
#define NacelleParticleCount Controls.y
#define CoreStrength Lighting.x
#define DetailStrength Lighting.y
#define GlobalFlash Lighting.z

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

vec4 dataAt(int x, int y) { return texelFetch(DataSampler, y * 64 + x); }
bool craftDepthPresent(float depth) { return depth > .000001; }
float craftClipDepth(float depth) { return 1.0 - 2.0 * depth; }

#include <kasuga_demo:nebula_volume_density.glsl>
#include <kasuga_demo:nebula_warp_medium.glsl>
#include <kasuga_demo:nebula_volume_sampling.glsl>
#include <kasuga_demo:nebula_hull_lighting.glsl>
#include <kasuga_demo:nebula_volume_render.glsl>
