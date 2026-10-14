#version 330
#extension GL_ARB_separate_shader_objects : require

void main() {
    vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    gl_Position = vec4(uv * 2.0 - 1.0, 1.0, 1.0);
    gl_ClipDistance[0] = 1.0;
}
