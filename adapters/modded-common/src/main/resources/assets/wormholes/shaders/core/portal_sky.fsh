#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:fog.glsl>

// The renderer that shades streamed views replaces this stage; its Metal stage reads the view's Portal block (the clip
// plane, the target, the shading renderer's frame and cloud parameters re-based on the view's camera) and Wormholes'
// copies of its cloud weather map and noise volume. Without such a renderer the pipeline is never drawn.
layout(std140) uniform Portal { vec4 ClipPlane; vec4 TargetSize; vec4 DepthTransform; vec4 Shade; mat4 Frame[16]; mat4 Clouds[7]; };
uniform sampler2D CloudWeather;
uniform sampler2D CloudNoise;

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = vec4(FogColor.rgb, 1.0);
}
