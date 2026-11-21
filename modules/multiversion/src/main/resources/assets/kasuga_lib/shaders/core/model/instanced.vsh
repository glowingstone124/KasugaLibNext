#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:light.glsl>
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>

struct ModelInstance {
    mat4 placement;
    mat3 normal;
    vec4 light;
};

layout(std140) uniform KasugaModelInstances {
    ModelInstance Instances[64];
};

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV1;
layout(location = 4) in ivec2 UV2;
layout(location = 5) in vec3 Normal;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
#ifdef PER_FACE_LIGHTING
layout(location = 2) out vec4 vertexPerFaceColorBack;
layout(location = 3) out vec4 vertexPerFaceColorFront;
#else
layout(location = 2) out vec4 vertexColor;
#endif
layout(location = 4) out vec4 lightMapColor;
layout(location = 5) out vec4 overlayColor;
layout(location = 6) out vec2 texCoord0;

void main() {
    ModelInstance instance = Instances[gl_InstanceIndex];
    vec3 position = (instance.placement * vec4(Position, 1.0)).xyz;
    vec3 normal = normalize(instance.normal * Normal);
    gl_Position = ProjMat * ModelViewMat * vec4(position, 1.0);
    sphericalVertexDistance = fog_spherical_distance(position);
    cylindricalVertexDistance = fog_cylindrical_distance(position);
#ifdef PER_FACE_LIGHTING
    vec2 light = minecraft_compute_light(Light0_Direction, Light1_Direction, normal);
    vertexPerFaceColorBack = minecraft_mix_light_separate(-light, Color);
    vertexPerFaceColorFront = minecraft_mix_light_separate(light, Color);
#else
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, normal, Color);
#endif
    ivec2 lightCoords = any(notEqual(UV2, ivec2(0))) ? UV2 : ivec2(instance.light.xy);
    lightMapColor = sample_lightmap(Sampler2, lightCoords);
    overlayColor = texelFetch(Sampler1, UV1, 0);
    texCoord0 = UV0;
}
