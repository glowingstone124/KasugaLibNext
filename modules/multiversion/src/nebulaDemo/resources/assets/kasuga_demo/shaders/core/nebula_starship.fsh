#version 330
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) in vec3 vLocal;
layout(location = 1) in vec3 vWorld;
layout(location = 2) in vec3 vNormal;
layout(location = 3) in vec2 vUv;
layout(location = 4) in vec4 vColor;
layout(location = 5) in vec4 vMaterial;

uniform sampler2D AlbedoMap;
uniform sampler2D NormalMap;
uniform sampler2D MetallicRoughnessMap;
uniform sampler2D EmissiveMap;

layout(location = 0) out vec4 outSurface;
layout(location = 1) out vec4 outNormal;
layout(location = 2) out vec4 outPbr;

#include <kasuga_demo:nebula_starship_uniforms.glsl>
#include <kasuga_demo:nebula_starship_fragment.glsl>
