/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: ShaderCodeTransformation for 1.20.1 GLSL 150 core shaders, declaring a plain clip plane uniform
 * and writing gl_ClipDistance after each vertex shader's own main.
 */
package art.arcane.wormholes.modded.client.render.stencil;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ClipShaderTransformation {
    public static final String CLIP_PLANE = "WormholesClipPlane";
    public static final String CLIP_PLANE_DEFINE = "WORMHOLES_CLIP_PLANE";
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void)?\\s*\\)");
    private static final Pattern VERSION = Pattern.compile("(?m)^\\s*#version[^\\r\\n]*");
    /** Sky programs: the sky sits at infinity, beyond any portal plane, so it is never clipped. */
    private static final Set<String> UNCLIPPED = Set.of("position", "position_tex");
    private static final String RENAMED_MAIN = "void wormholes_main()";

    private ClipShaderTransformation() {
    }

    /** Whether the named core program keeps every vertex. */
    public static boolean unclipped(String program) {
        return UNCLIPPED.contains(program);
    }

    /**
     * Renames the vertex shader's {@code main} and appends one that writes {@code gl_ClipDistance[0]} as the dot of
     * {@code gl_Position} with the clip-space plane uniform, or a constant 1 for unclipped programs. Empty when the
     * source already writes clip distances or its {@code main} cannot be found exactly once.
     */
    public static Optional<String> vertex(String program, String source) {
        if (source.contains("gl_ClipDistance") || source.contains(CLIP_PLANE)) {
            return Optional.empty();
        }
        Matcher version = VERSION.matcher(source);
        if (!version.find()) {
            return Optional.empty();
        }
        Matcher matcher = MAIN.matcher(source);
        if (!matcher.find()) {
            return Optional.empty();
        }
        int start = matcher.start();
        int end = matcher.end();
        if (matcher.find() || start < version.end()) {
            return Optional.empty();
        }
        boolean clipped = !unclipped(program);
        String uniform = clipped ? "\n#define " + CLIP_PLANE_DEFINE + "\nuniform vec4 " + CLIP_PLANE + ";\n" : "\n";
        String distance = clipped ? "    gl_ClipDistance[0] = dot(gl_Position, " + CLIP_PLANE + ");\n" : "    gl_ClipDistance[0] = 1.0;\n";
        return Optional.of(source.substring(0, version.end()) + uniform + source.substring(version.end(), start) + RENAMED_MAIN
            + source.substring(end) + "\nvoid main() {\n    wormholes_main();\n" + distance + "}\n");
    }
}
