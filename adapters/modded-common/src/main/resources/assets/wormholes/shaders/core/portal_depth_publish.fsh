#version 330
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:globals.glsl>
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
layout(location = 0) out vec4 fragColor;
void main() {
    vec2 uv = gl_FragCoord.xy / ScreenSize;
    float aperture = texture(Sampler0, uv).r;
    if (aperture == 0.0) discard;
    float current = texture(Sampler1, gl_FragCoord.xy / vec2(textureSize(Sampler1, 0))).r;
    if (abs(current - aperture) > 0.000001) discard;
    gl_FragDepth = texture(Sampler2, uv).r;
    fragColor = vec4(0.0);
}
