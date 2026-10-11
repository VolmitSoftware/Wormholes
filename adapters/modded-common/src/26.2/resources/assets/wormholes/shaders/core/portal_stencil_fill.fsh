#version 330
#extension GL_ARB_separate_shader_objects : require

#moj_import <minecraft:dynamictransforms.glsl>

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = ColorModulator;
}
