#version 150

#moj_import <fog.glsl>

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform float AlphaCutout;

in float vertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
in float portalClipDistance;

out vec4 fragColor;

void main() {
    if (portalClipDistance > 0.0) {
        discard;
    }
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (color.a < AlphaCutout) {
        discard;
    }
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
