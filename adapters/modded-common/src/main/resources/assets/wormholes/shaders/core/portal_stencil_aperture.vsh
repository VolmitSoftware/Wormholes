#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;

layout(location = 0) out vec2 portalDepth;

void main() {
    vec4 position = ProjMat * ModelViewMat * vec4(Position, 1.0);
#ifdef WORMHOLES_CLIP_PLANE
    gl_ClipDistance[0] = dot(position, WormholesClipPlane);
#else
    gl_ClipDistance[0] = 1.0;
#endif
    portalDepth = position.zw;
    gl_Position = vec4(position.xy, position.w, position.w);
}
