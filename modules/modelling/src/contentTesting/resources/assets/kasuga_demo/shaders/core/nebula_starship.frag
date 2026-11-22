#version 330 core

in vec3 vLocal;
in vec3 vWorld;
in vec3 vNormal;
in vec2 vUv;
in vec4 vColor;
in vec4 vMaterial;

uniform float Time;
uniform float HullTextureStrength;
uniform float WarpCharge;
uniform float WarpActive;
uniform sampler2D AlbedoMap;
uniform sampler2D NormalMap;
uniform sampler2D MetallicRoughnessMap;
uniform sampler2D EmissiveMap;

layout(location = 0) out vec4 outSurface;
layout(location = 1) out vec4 outNormal;
layout(location = 2) out vec4 outPbr;
