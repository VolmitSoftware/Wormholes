#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D Sampler0;
layout(std140) uniform Portal { vec4 ClipPlane; vec4 TargetSize; vec4 DepthTransform; };
layout(location = 0) out vec4 fragColor;
layout(location = 0) in float portalClipDistance;
layout(location = 1) in vec2 portalDepth;
void main() {
    if (portalClipDistance > 0.0) discard;
    float depth = (portalDepth.x / portalDepth.y) * DepthTransform.x + DepthTransform.y;
    if (depth < 0.0) discard;
    gl_FragDepth = min(depth, 1.0);
    fragColor = texture(Sampler0, (gl_FragCoord.xy - TargetSize.zw) / TargetSize.xy);
}
