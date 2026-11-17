#version 150

uniform sampler2D Sampler0;
uniform vec4 TargetSize;

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
    fragColor = texture(Sampler0, (gl_FragCoord.xy - TargetSize.zw) / TargetSize.xy);
}
