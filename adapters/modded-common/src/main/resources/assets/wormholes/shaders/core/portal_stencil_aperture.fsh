#version 330
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) in vec2 portalDepth;

layout(location = 0) out vec4 fragColor;

void main() {
    float depth = portalDepth.x / portalDepth.y;
#ifndef RENDERPEARL_DEPTH_IS_ZERO_TO_ONE
    depth = depth * 0.5 + 0.5;
#endif
    gl_FragDepth = clamp(depth, 0.0, 1.0);
    fragColor = vec4(0.0);
}
