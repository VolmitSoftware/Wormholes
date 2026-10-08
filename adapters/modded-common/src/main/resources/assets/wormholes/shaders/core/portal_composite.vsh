#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:projection.glsl>
#include <minecraft:dynamictransforms.glsl>
layout(location = 0) in vec3 Position;
#ifdef PORTAL_SHAPE
layout(location = 1) in vec2 UV0;
#endif
layout(std140) uniform Portal { vec4 ClipPlane; vec4 TargetSize; vec4 DepthTransform; };
layout(location = 0) out float portalClipDistance;
layout(location = 1) out vec2 portalDepth;
#ifdef PORTAL_SHAPE
layout(location = 2) out float portalEdgeDistance;
#endif
void main() {
    portalClipDistance = dot(vec4(Position, 1.0), ClipPlane);
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    portalDepth = gl_Position.zw;
    gl_Position.z = gl_Position.w;
#ifdef PORTAL_SHAPE
    portalEdgeDistance = UV0.x;
#endif
}
