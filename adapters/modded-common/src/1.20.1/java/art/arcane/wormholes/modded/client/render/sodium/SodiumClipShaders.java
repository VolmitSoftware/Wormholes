/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: the Sodium shader transformation of MixinSodiumShaderLoader and MixinSodiumDefaultShaderInterface
 * for Sodium 0.5 and Embeddium 0.3, writing gl_ClipDistance from a clip-space plane uniform after the chunk shader's main.
 */
package art.arcane.wormholes.modded.client.render.sodium;

import com.mojang.logging.LogUtils;
import me.jellysquid.mods.sodium.client.gl.shader.GlProgram;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat4v;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ShaderBindingContext;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL20C;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SodiumClipShaders {
    public static final String CLIP_PLANE = "WormholesClipPlane";
    public static final ResourceLocation TERRAIN_VERTEX = new ResourceLocation("sodium", "blocks/block_layer_opaque.vsh");
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void)?\\s*\\)");
    private static volatile boolean ready;

    private SodiumClipShaders() {
    }

    /** Whether a linked Sodium chunk program carries the clip plane uniform. */
    public static boolean ready() {
        return ready;
    }

    public static String source(ResourceLocation name, String source) {
        if (!TERRAIN_VERTEX.equals(name)) {
            return source;
        }
        Optional<String> clipped = vertex(source);
        if (clipped.isEmpty()) {
            LOGGER.warn("Portal views render without Sodium terrain clipping: {} has no single main to wrap", name);
            return source;
        }
        return clipped.get();
    }

    public static Optional<String> vertex(String source) {
        if (source.contains("gl_ClipDistance") || source.contains(CLIP_PLANE)) {
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
        return Optional.of(source.substring(0, start) + "void wormholes_main()" + source.substring(end)
            + "\nuniform vec4 " + CLIP_PLANE + ";\n\nvoid main() {\n    wormholes_main();\n"
            + "    gl_ClipDistance[0] = dot(gl_Position, " + CLIP_PLANE + ");\n}\n");
    }

    /** The clip plane uniform of a freshly linked chunk program, or null when the program was not patched (Iris packs). */
    public static @Nullable GlUniformFloat4v bind(ShaderBindingContext context) {
        if (!(context instanceof GlProgram<?> program)) {
            return null;
        }
        int location = GL20C.glGetUniformLocation(program.handle(), CLIP_PLANE);
        if (location < 0) {
            return null;
        }
        ready = true;
        return new GlUniformFloat4v(location);
    }
}
