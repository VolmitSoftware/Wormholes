#version 150

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform vec2 ScreenSize;

out vec4 fragColor;

void main() {
    vec2 uv = gl_FragCoord.xy / ScreenSize;
    float depth = texture(Sampler1, uv).r;
    if (depth >= 1.0) {
        discard;
    }
    gl_FragDepth = depth;
    fragColor = texture(Sampler0, uv);
}
