#version 150

uniform sampler2D SceneSampler;
uniform sampler2D DepthSampler;
uniform mat4 Projection;
uniform mat4 InverseProjection;
uniform vec4 DepthTransform;
uniform vec4 CenterRadius;
uniform vec4 DiskNormalTime;
uniform vec4 DiskTangent;
uniform vec4 DiskBitangent;

in vec2 texCoord;
out vec4 fragColor;

#moj_import <kasuga_lib:black_hole_demo.glsl>

void main() {
    fragColor = blackHoleDemo(texCoord);
}
