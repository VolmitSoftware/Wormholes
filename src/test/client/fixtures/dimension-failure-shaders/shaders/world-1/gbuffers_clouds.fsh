#version 120
void main() { gl_FragData[0] = vec4(nightMiddleSkyColor + dayMiddleSkyColor, 1.0); }
