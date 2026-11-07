#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:dynamictransforms.glsl>

// A full-screen quad in clip space. The renderer that shades streamed views replaces this stage: on Metal Shaders
// ModelViewMat carries the portal camera's inverse view-projection and TextureMat the destination's rotation, and the
// Metal variant traces its sky along that ray. Without such a renderer the pipeline is never drawn.
layout(location = 0) in vec3 Position;

void main() {
    gl_Position = vec4(Position.xy, 0.0, 1.0);
}
