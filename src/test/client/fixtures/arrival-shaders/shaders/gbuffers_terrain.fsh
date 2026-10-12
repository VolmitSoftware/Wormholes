#version 120

uniform sampler2D gtexture;
varying vec2 surfaceUv;
varying vec4 surfaceColor;

void main() {
    gl_FragData[0] = texture2D(gtexture, surfaceUv) * surfaceColor;
}
