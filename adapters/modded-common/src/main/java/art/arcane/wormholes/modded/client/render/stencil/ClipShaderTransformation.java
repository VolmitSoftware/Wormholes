/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: ShaderCodeTransformation rewritten for 26.x GLSL, carrying the clip plane in the Projection
 * uniform block or Sodium's globals block and writing gl_ClipDistance after each vertex shader's own main.
 */
package art.arcane.wormholes.modded.client.render.stencil;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ClipShaderTransformation {
    public static final String CLIP_PLANE = "WormholesClipPlane";
    public static final String CLIP_PLANE_DEFINE = "WORMHOLES_CLIP_PLANE";
    public static final String PROJECTION_INCLUDE = "#include <minecraft:projection.glsl>";
    public static final String SODIUM_GLOBALS_INCLUDE = "#include <sodium:globals.glsl>";
    private static final Pattern PROJECTION_MATRIX = Pattern.compile("uniform\\s+Projection\\s*\\{[^}]*?mat4\\s+ProjMat\\s*;");
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void)?\\s*\\)");
    private static final Set<String> UNCLIPPED = Set.of("minecraft:core/sky", "minecraft:core/stars", "minecraft:core/panorama");
    private static final String RENAMED_MAIN = "void wormholes_main()";

    private ClipShaderTransformation() {
    }

    public static Optional<String> projection(String source) {
        Matcher matcher = PROJECTION_MATRIX.matcher(source);
        if (!matcher.find() || source.contains(CLIP_PLANE)) {
            return Optional.empty();
        }
        int line = source.lastIndexOf('\n', matcher.start()) + 1;
        return Optional.of(source.substring(0, line) + "#define " + CLIP_PLANE_DEFINE + "\n" + source.substring(line, matcher.end())
            + "\n    vec4 " + CLIP_PLANE + ";" + source.substring(matcher.end()));
    }

    public static Optional<String> vertex(String id, String source) {
        if (source.contains("gl_ClipDistance")) {
            return Optional.empty();
        }
        Matcher matcher = MAIN.matcher(source);
        if (!matcher.find()) {
            return Optional.empty();
        }
        int start = matcher.start();
        int end = matcher.end();
        if (matcher.find()) {
            return Optional.empty();
        }
        String distance = clipped(id, source) ? "#ifdef " + CLIP_PLANE_DEFINE + "\n    gl_ClipDistance[0] = dot(gl_Position, " + CLIP_PLANE
            + ");\n#else\n    gl_ClipDistance[0] = 1.0;\n#endif\n" : "    gl_ClipDistance[0] = 1.0;\n";
        return Optional.of(source.substring(0, start) + RENAMED_MAIN + source.substring(end)
            + "\nvoid main() {\n    wormholes_main();\n" + distance + "}\n");
    }

    private static boolean clipped(String id, String source) {
        return (source.contains(PROJECTION_INCLUDE) || source.contains(SODIUM_GLOBALS_INCLUDE)) && !UNCLIPPED.contains(id);
    }
}
