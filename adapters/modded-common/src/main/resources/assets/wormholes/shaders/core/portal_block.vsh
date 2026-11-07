#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV2;

#ifndef OIT_ALPHA_ONLY
uniform sampler2D Sampler2;
#endif

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec2 texCoord0;

// One block per view and frame: the clip plane relative to the view's camera, the target rectangle, the depth transform,
// the shade mode, and the shading renderer's per-frame state and cloud parameters re-based on the view's camera (its
// Metal stages read them).
layout(std140) uniform Portal { vec4 ClipPlane; vec4 TargetSize; vec4 DepthTransform; vec4 Shade; mat4 Frame[16]; mat4 Clouds[7]; };
layout(location = 4) out float portalClipDistance;

void main() {
    vec3 pos = Position + ModelOffset;
    portalClipDistance = dot(vec4(pos, 1.0), ClipPlane);
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    #ifndef OIT_ALPHA_ONLY
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
    #else
    vertexColor = Color;
    #endif
    texCoord0 = UV0;
}
