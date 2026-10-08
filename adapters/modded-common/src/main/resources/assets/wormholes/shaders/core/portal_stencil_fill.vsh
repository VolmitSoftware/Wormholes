#version 330
#extension GL_ARB_separate_shader_objects : require

void main() {
    vec2 uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
#ifdef RENDERPEARL_DEPTH_IS_ZERO_TO_ONE
    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
#else
    gl_Position = vec4(uv * 2.0 - 1.0, -1.0, 1.0);
#endif
    gl_ClipDistance[0] = 1.0;
}
