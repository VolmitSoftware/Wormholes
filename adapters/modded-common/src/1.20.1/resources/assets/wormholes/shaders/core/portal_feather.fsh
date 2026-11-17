#version 150

uniform vec4 FeatherColor;
uniform vec4 FeatherShape;

in float portalClipDistance;
in vec2 portalDepth;
in float portalEdgeDistance;

out vec4 fragColor;

void main() {
    if (portalClipDistance > 0.0) {
        discard;
    }
    float depth = (portalDepth.x / portalDepth.y) * 0.5 + 0.5;
    if (depth < 0.0) {
        discard;
    }
    gl_FragDepth = min(depth, 1.0);
    float alpha = 1.0 - smoothstep(0.0, FeatherShape.x, -portalEdgeDistance);
    if (alpha <= 0.0) {
        discard;
    }
    fragColor = vec4(FeatherColor.rgb, FeatherColor.a * alpha);
}
