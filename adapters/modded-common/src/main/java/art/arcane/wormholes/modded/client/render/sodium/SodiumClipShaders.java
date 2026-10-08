/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: the Sodium shader transformation of MixinSodiumShaderLoader and MixinSodiumDefaultShaderInterface,
 * carrying the clip plane at the end of Sodium's std140 globals block instead of a separate uniform.
 */
package art.arcane.wormholes.modded.client.render.sodium;

import art.arcane.wormholes.modded.client.render.stencil.ClipShaderTransformation;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SodiumClipShaders {
    public static final Identifier GLOBALS = Identifier.fromNamespaceAndPath("sodium", "shaders/include/globals.glsl");
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Pattern BLOCK = Pattern.compile("uniform\\s+u_Globals\\s*\\{([^}]*)}\\s*;");
    private static final Pattern COMMENT = Pattern.compile("//[^\\n]*");
    private static final Pattern MEMBER = Pattern.compile("(?:(?:highp|mediump|lowp)\\s+)?([A-Za-z0-9_]+)\\s+[A-Za-z_][A-Za-z0-9_]*");
    private static final int VEC4_ALIGNMENT = 16;
    private static final Map<String, Layout> LAYOUTS = Map.ofEntries(
        Map.entry("float", new Layout(4, 4)), Map.entry("int", new Layout(4, 4)), Map.entry("uint", new Layout(4, 4)),
        Map.entry("bool", new Layout(4, 4)), Map.entry("vec2", new Layout(8, 8)), Map.entry("ivec2", new Layout(8, 8)),
        Map.entry("uvec2", new Layout(8, 8)), Map.entry("vec3", new Layout(16, 12)), Map.entry("ivec3", new Layout(16, 12)),
        Map.entry("uvec3", new Layout(16, 12)), Map.entry("vec4", new Layout(16, 16)), Map.entry("ivec4", new Layout(16, 16)),
        Map.entry("uvec4", new Layout(16, 16)), Map.entry("mat3", new Layout(16, 48)), Map.entry("mat4", new Layout(16, 64)));
    private static volatile int planeOffset = -1;

    private SodiumClipShaders() {
    }

    public static void reset() {
        planeOffset = -1;
    }

    public static boolean ready() {
        return planeOffset >= 0;
    }

    public static int planeOffset() {
        return planeOffset;
    }

    public static String include(String contents) {
        Optional<Globals> globals = transform(contents);
        if (globals.isEmpty()) {
            LOGGER.warn("Portal views render without Sodium terrain clipping: {} has no u_Globals block to extend", GLOBALS);
            return contents;
        }
        planeOffset = globals.get().planeOffset();
        return globals.get().source();
    }

    public static Optional<Globals> transform(String source) {
        if (source.contains(ClipShaderTransformation.CLIP_PLANE)) {
            return Optional.empty();
        }
        Matcher block = BLOCK.matcher(source);
        if (!block.find()) {
            return Optional.empty();
        }
        int end = end(block.group(1));
        if (end < 0) {
            return Optional.empty();
        }
        int line = source.lastIndexOf('\n', block.start()) + 1;
        int close = block.end(1);
        String transformed = source.substring(0, line) + "#define " + ClipShaderTransformation.CLIP_PLANE_DEFINE + "\n"
            + source.substring(line, close) + "    vec4 " + ClipShaderTransformation.CLIP_PLANE + ";\n" + source.substring(close);
        return Optional.of(new Globals(transformed, align(end, VEC4_ALIGNMENT)));
    }

    private static int end(String body) {
        int offset = 0;
        for (String declaration : COMMENT.matcher(body).replaceAll("").split(";")) {
            String member = declaration.strip();
            if (member.isEmpty()) {
                continue;
            }
            Matcher matcher = MEMBER.matcher(member);
            Layout layout = matcher.matches() ? LAYOUTS.get(matcher.group(1)) : null;
            if (layout == null) {
                return -1;
            }
            offset = align(offset, layout.alignment()) + layout.size();
        }
        return offset;
    }

    private static int align(int offset, int alignment) {
        return (offset + alignment - 1) / alignment * alignment;
    }

    public record Globals(String source, int planeOffset) {
    }

    private record Layout(int alignment, int size) {
    }
}
