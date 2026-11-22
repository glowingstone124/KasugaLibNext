#version 330
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) in vec3 Position;
layout(location = 1) in vec3 Normal;
layout(location = 2) in vec2 UV;
layout(location = 3) in vec4 Color;
layout(location = 4) in vec4 Material;

layout(location = 0) out vec3 vLocal;
layout(location = 1) out vec3 vWorld;
layout(location = 2) out vec3 vNormal;
layout(location = 3) out vec2 vUv;
layout(location = 4) out vec4 vColor;
layout(location = 5) out vec4 vMaterial;

#include <kasuga_demo:nebula_starship_uniforms.glsl>
#include <kasuga_demo:nebula_starship_vertex.glsl>
