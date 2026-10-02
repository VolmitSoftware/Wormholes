package art.arcane.wormholes.modded.client.render;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

final class PortalPipelines implements AutoCloseable {
    private static final BindGroupLayout PORTAL = BindGroupLayout.builder().withUniform("Portal", UniformType.UNIFORM_BUFFER).build();
    private final EnumMap<ChunkSectionLayer, RenderPipeline> terrain = new EnumMap<>(ChunkSectionLayer.class);
    private final EnumMap<ChunkSectionLayer, RenderPipeline> reflectedTerrain = new EnumMap<>(ChunkSectionLayer.class);
    private final EnumMap<ChunkSectionLayer, RenderPipeline> extendedTerrain = new EnumMap<>(ChunkSectionLayer.class);
    private final EnumMap<ChunkSectionLayer, RenderPipeline> reflectedExtendedTerrain = new EnumMap<>(ChunkSectionLayer.class);
    private final RenderPipeline composite;
    private final RenderPipeline layer;
    private final Sources sources;
    private final PipelineCache cache;
    private ReflectedCache reflectedFeatures;

    PortalPipelines(boolean rgss) {
        terrain.put(ChunkSectionLayer.SOLID, terrain("solid", RenderPipelines.SOLID_BLOCK, rgss, DefaultVertexFormat.BLOCK));
        terrain.put(ChunkSectionLayer.CUTOUT, terrain("cutout", RenderPipelines.CUTOUT_BLOCK, rgss, DefaultVertexFormat.BLOCK));
        terrain.put(ChunkSectionLayer.TRANSLUCENT, terrain("translucent", RenderPipelines.TRANSLUCENT_BLOCK, rgss, DefaultVertexFormat.BLOCK));
        reflectedTerrain.put(ChunkSectionLayer.SOLID, terrain("solid_reflected", RenderPipelines.SOLID_BLOCK, rgss, DefaultVertexFormat.BLOCK));
        reflectedTerrain.put(ChunkSectionLayer.CUTOUT, terrain("cutout_reflected", RenderPipelines.CUTOUT_BLOCK, rgss, DefaultVertexFormat.BLOCK));
        reflectedTerrain.put(ChunkSectionLayer.TRANSLUCENT, terrain("translucent_reflected", RenderPipelines.TRANSLUCENT_BLOCK, rgss, DefaultVertexFormat.BLOCK));
        extendedTerrain.put(ChunkSectionLayer.SOLID, terrain("solid_warming", RenderPipelines.SOLID_BLOCK, rgss, PortalTerrainVertices.FORMAT));
        extendedTerrain.put(ChunkSectionLayer.CUTOUT, terrain("cutout_warming", RenderPipelines.CUTOUT_BLOCK, rgss, PortalTerrainVertices.FORMAT));
        extendedTerrain.put(ChunkSectionLayer.TRANSLUCENT, terrain("translucent_warming", RenderPipelines.TRANSLUCENT_BLOCK, rgss, PortalTerrainVertices.FORMAT));
        reflectedExtendedTerrain.put(ChunkSectionLayer.SOLID, terrain("solid_warming_reflected", RenderPipelines.SOLID_BLOCK, rgss, PortalTerrainVertices.FORMAT));
        reflectedExtendedTerrain.put(ChunkSectionLayer.CUTOUT, terrain("cutout_warming_reflected", RenderPipelines.CUTOUT_BLOCK, rgss, PortalTerrainVertices.FORMAT));
        reflectedExtendedTerrain.put(ChunkSectionLayer.TRANSLUCENT, terrain("translucent_warming_reflected", RenderPipelines.TRANSLUCENT_BLOCK, rgss, PortalTerrainVertices.FORMAT));
        composite = compositePipeline();
        layer = layerPipeline();
        sources = new Sources(Minecraft.getInstance().getResourceManager());
        cache = new PipelineCache(RenderSystem.getDevice(), sources);
    }

    static RenderPipeline compositePipeline() {
        return RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("wormholes", "portal_composite"))
            .withVertexShader(Identifier.fromNamespaceAndPath("wormholes", "core/portal_composite")).withFragmentShader(Identifier.fromNamespaceAndPath("wormholes", "core/portal_composite"))
            .withBindGroupLayout(BindGroupLayout.builder().withUniform("Projection", UniformType.UNIFORM_BUFFER)
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER).build())
            .withBindGroupLayout(PORTAL).withDepthStencilState(DepthStencilState.DEFAULT)
            .withColorTargetState(ColorTargetState.DEFAULT).withCull(false)
            .withVertexBinding(0, DefaultVertexFormat.POSITION).withPrimitiveTopology(PrimitiveTopology.QUADS).build();
    }

    static RenderPipeline layerPipeline() {
        return RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("wormholes", "portal_layer"))
            .withVertexShader(Identifier.fromNamespaceAndPath("wormholes", "core/portal_layer"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("wormholes", "core/portal_layer"))
            .withBindGroupLayout(BindGroupLayout.builder().withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("Sampler1", UniformType.COMBINED_IMAGE_SAMPLER).build())
            .withDepthStencilState(DepthStencilState.DEFAULT).withColorTargetState(ColorTargetState.DEFAULT).withCull(false)
            .withVertexBinding(0, DefaultVertexFormat.POSITION).withPrimitiveTopology(PrimitiveTopology.QUADS).build();
    }

    CompiledRenderPipeline layer() {
        return cache.get(layer);
    }

    CompiledRenderPipeline terrain(ChunkSectionLayer layer, boolean reflected, boolean extended) {
        EnumMap<ChunkSectionLayer, RenderPipeline> selected = extended
            ? (reflected ? reflectedExtendedTerrain : extendedTerrain) : (reflected ? reflectedTerrain : terrain);
        return cache.get(selected.get(layer));
    }

    CompiledRenderPipeline composite() {
        return cache.get(composite);
    }

    PipelineCache reflectedFeatures() {
        if (reflectedFeatures == null) {
            reflectedFeatures = new ReflectedCache();
        }
        return reflectedFeatures;
    }

    static RenderPipeline terrain(String name, RenderPipeline original, boolean rgss, VertexFormat format) {
        RenderPipeline.Builder builder = RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("wormholes", "portal_" + name))
            .withVertexShader(Identifier.fromNamespaceAndPath("wormholes", "core/portal_block")).withFragmentShader(Identifier.fromNamespaceAndPath("wormholes", "core/portal_block"))
            .withDepthStencilState(original.getDepthStencilState()).withCull(!name.endsWith("_reflected") && original.isCull())
            .withVertexBinding(0, format).withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withColorTargetState(original.getColorTargetStates().getFirst()).withBindGroupLayout(PORTAL);
        for (BindGroupLayout layout : original.getBindGroupLayouts()) {
            builder.withBindGroupLayout(layout);
        }
        if (name.startsWith("cutout")) {
            builder.withShaderDefine("ALPHA_CUTOUT", 0.1f);
        }
        if (rgss) {
            builder.withShaderDefine("USE_RGSS");
        }
        return builder.build();
    }

    @Override
    public void close() {
        cache.close();
        if (reflectedFeatures != null) {
            reflectedFeatures.close();
            reflectedFeatures = null;
        }
    }

    private static final class ReflectedCache extends PipelineCache {
        private final Map<RenderPipeline, RenderPipeline> variants = new HashMap<>();

        private ReflectedCache() {
            super(RenderSystem.getDevice(), new Sources(Minecraft.getInstance().getResourceManager()));
        }

        @Override
        public CompiledRenderPipeline get(RenderPipeline pipeline) {
            return super.get(variants.computeIfAbsent(pipeline, ReflectedPipeline::new));
        }
    }

    private static final class ReflectedPipeline extends RenderPipeline {
        private ReflectedPipeline(RenderPipeline source) {
            super(source.getLocation().withSuffix("_wormholes_reflected"), source.getShaders(), source.getShaderDefines(),
                source.getBindGroupLayouts(), source.getColorTargetStates().toArray(ColorTargetState[]::new),
                source.getDepthStencilState(), source.getPolygonMode(), false, source.getVertexFormatBindings().toArray(VertexFormat[]::new),
                source.getPrimitiveTopology(), source.pushConstantSize(), source.getSortKey());
        }
    }

    private static final class Sources implements ShaderSource {
        private final ResourceManager resources;
        private final Map<Identifier, CachedIncludeSource> includes;
        private final Map<String, String> shaders = new HashMap<>();

        private Sources(ResourceManager resources) {
            this.resources = resources;
            includes = ShaderManager.listAllIncludes(resources);
        }

        @Override
        public String getShader(Identifier id, ShaderType type) {
            String extension = type == ShaderType.VERTEX ? ".vsh" : ".fsh";
            String key = id + extension;
            return shaders.computeIfAbsent(key, ignored -> read(id.withPath("shaders/" + id.getPath() + extension)));
        }

        private String read(Identifier id) {
            try (InputStream input = resources.getResourceOrThrow(id).open()) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException failure) {
                throw new UncheckedIOException("Could not read portal shader " + id, failure);
            }
        }

        @Override
        public CachedIncludeSource getInclude(Identifier id) {
            return includes.get(id);
        }

        @Override
        public void close() {
            for (CachedIncludeSource include : includes.values()) {
                include.close();
            }
            includes.clear();
        }
    }
}
