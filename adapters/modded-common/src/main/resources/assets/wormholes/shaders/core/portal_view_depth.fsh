#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D Sampler0;
layout(std140) uniform Portal { vec4 ClipPlane; vec4 TargetSize; vec4 DepthTransform; };
layout(std140) uniform ViewDepth { mat4 ContentToRoot; };
layout(location = 0) out vec4 fragColor;
layout(location = 0) in float portalClipDistance;
layout(location = 1) in vec2 portalDepth;
void main() {
    if (portalClipDistance > 0.0) discard;
    if ((portalDepth.x / portalDepth.y) * DepthTransform.x + DepthTransform.y < 0.0) discard;
    vec2 uv = (gl_FragCoord.xy - TargetSize.zw) / TargetSize.xy;
    float stored = texture(Sampler0, uv).r;
    fragColor = vec4(0.0);
    if (stored == 0.0) {
        gl_FragDepth = 0.0;
        return;
    }
    vec4 root = ContentToRoot * vec4(uv * 2.0 - 1.0, (stored - DepthTransform.y) / DepthTransform.x, 1.0);
    gl_FragDepth = clamp((root.z / root.w) * DepthTransform.x + DepthTransform.y, 0.0, 1.0);
}
