#version 150

in vec3 Position;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec4 ClipPlane;

out float portalClipDistance;
out vec2 portalDepth;

void main() {
    portalClipDistance = dot(vec4(Position, 1.0), ClipPlane);
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    portalDepth = gl_Position.zw;
    gl_Position.z = gl_Position.w;
}
