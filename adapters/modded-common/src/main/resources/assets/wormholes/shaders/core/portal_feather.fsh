#version 330
#extension GL_ARB_separate_shader_objects : require
layout(std140) uniform Portal { vec4 ClipPlane; vec4 TargetSize; vec4 DepthTransform; };
layout(std140) uniform Feather { vec4 FeatherColor; vec4 FeatherShape; };
layout(location = 0) out vec4 fragColor;
layout(location = 0) in float portalClipDistance;
layout(location = 1) in vec2 portalDepth;
layout(location = 2) in float portalEdgeDistance;
void main() {
    if (portalClipDistance > 0.0) discard;
    float depth = (portalDepth.x / portalDepth.y) * DepthTransform.x + DepthTransform.y;
    if (depth < 0.0) discard;
    gl_FragDepth = min(depth, 1.0);
    float alpha = 1.0 - smoothstep(0.0, FeatherShape.x, -portalEdgeDistance);
    if (alpha <= 0.0) discard;
    fragColor = vec4(FeatherColor.rgb, FeatherColor.a * alpha);
}
