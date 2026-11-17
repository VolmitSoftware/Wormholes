package art.arcane.wormholes.modded.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Wormholes' core shaders on 1.20.1, loaded by the game's shader reload through the loader's registration hook
 * ({@code CoreShaderRegistrationCallback} on Fabric, {@code RegisterShadersEvent} on Forge). The loader entrypoints pass
 * a {@link Registrar} that creates and registers one {@link ShaderInstance} per id.
 */
public final class PortalShaders {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final ResourceLocation BLOCK = id("portal_block");
    public static final ResourceLocation BLOCK_TRANSLUCENT = id("portal_block_translucent");
    public static final ResourceLocation COMPOSITE = id("portal_composite");
    public static final ResourceLocation COMPOSITE_SHAPE = id("portal_composite_shape");
    public static final ResourceLocation FEATHER = id("portal_feather");
    public static final ResourceLocation LAYER = id("portal_layer");
    private static final List<Definition> DEFINITIONS = List.of(
        new Definition(BLOCK, DefaultVertexFormat.BLOCK),
        new Definition(BLOCK_TRANSLUCENT, DefaultVertexFormat.BLOCK),
        new Definition(COMPOSITE, DefaultVertexFormat.POSITION_COLOR),
        new Definition(COMPOSITE_SHAPE, DefaultVertexFormat.POSITION_COLOR_TEX),
        new Definition(FEATHER, DefaultVertexFormat.POSITION_COLOR_TEX),
        new Definition(LAYER, DefaultVertexFormat.POSITION));
    private static final Map<ResourceLocation, ShaderInstance> LOADED = new HashMap<>();

    private PortalShaders() {
    }

    /** Registers every Wormholes core shader for the reload in progress. Called by the loader's shader registration hook. */
    public static void register(Registrar registrar) {
        LOADED.clear();
        for (Definition definition : DEFINITIONS) {
            try {
                registrar.register(definition.id(), definition.format(), shader -> LOADED.put(definition.id(), shader));
            } catch (IOException | RuntimeException failure) {
                LOGGER.error("Could not load Wormholes shader {}", definition.id(), failure);
            }
        }
    }

    /** The loaded shader, or {@code null} before the first reload or after a failed one. */
    @Nullable
    public static ShaderInstance get(ResourceLocation id) {
        return LOADED.get(id);
    }

    /** Whether every shader the streamed view path draws with has loaded. */
    public static boolean streamedReady() {
        return LOADED.size() == DEFINITIONS.size();
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("wormholes", path);
    }

    /** Creates a core shader from {@code assets/<namespace>/shaders/core/<path>.json} and reports it once loaded. */
    @FunctionalInterface
    public interface Registrar {
        void register(ResourceLocation id, VertexFormat format, Consumer<ShaderInstance> loaded) throws IOException;
    }

    private record Definition(ResourceLocation id, VertexFormat format) {
    }
}
