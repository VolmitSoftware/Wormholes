#version 120

varying vec2 surfaceUv;
varying vec4 surfaceColor;

void main() {
    gl_Position = ftransform();
    surfaceUv = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    surfaceColor = gl_Color;
}
