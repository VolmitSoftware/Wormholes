package art.arcane.wormholes.modded.client.render;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
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
import net.minecraft.util.Util;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class PortalPipelines implements AutoCloseable {
    private static final BindGroupLayout PORTAL = BindGroupLayout.builder().withUniform("Portal", UniformType.UNIFORM_BUFFER).build();
    private static final BindGroupLayout FEATHER = BindGroupLayout.builder().withUniform("Feather", UniformType.UNIFORM_BUFFER).build();
    private final EnumMap<ChunkSectionLayer, RenderPipeline> terrain = new EnumMap<>(ChunkSectionLayer.class);
    private final EnumMap<ChunkSectionLayer, RenderPipeline> reflectedTerrain = new EnumMap<>(ChunkSectionLayer.class);
    private final EnumMap<ChunkSectionLayer, RenderPipeline> rgssTerrain = new EnumMap<>(ChunkSectionLayer.class);
    private final EnumMap<ChunkSectionLayer, RenderPipeline> reflectedRgssTerrain = new EnumMap<>(ChunkSectionLayer.class);
    private final RenderPipeline composite;
    private final RenderPipeline compositeShape;
    private final RenderPipeline feather;
    private final RenderPipeline layer;
    private final Sources sources;
    private final GpuDevice device;
    private final List<RenderPipeline> descriptors;
    private final Map<RenderPipeline, CompiledRenderPipeline> compiled = new IdentityHashMap<>(16);
    private ReflectedCache reflectedFeatures;
    private boolean prepared;
    private boolean closed;

    PortalPipelines() {
        createTerrain(terrain, reflectedTerrain, false);
        createTerrain(rgssTerrain, reflectedRgssTerrain, true);
        composite = compositePipeline();
        compositeShape = compositeShapePipeline();
        feather = featherPipeline();
        layer = layerPipeline();
        List<RenderPipeline> all = new ArrayList<>(16);
        all.addAll(terrain.values());
        all.addAll(reflectedTerrain.values());
        all.addAll(rgssTerrain.values());
        all.addAll(reflectedRgssTerrain.values());
        all.addAll(List.of(composite, compositeShape, feather, layer));
        descriptors = List.copyOf(all);
        device = RenderSystem.getDevice();
        sources = new Sources(Minecraft.getInstance().getResourceManager());
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

    static RenderPipeline compositeShapePipeline() {
        return RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("wormholes", "portal_composite_shape"))
            .withVertexShader(Identifier.fromNamespaceAndPath("wormholes", "core/portal_composite")).withFragmentShader(Identifier.fromNamespaceAndPath("wormholes", "core/portal_composite"))
            .withShaderDefine("PORTAL_SHAPE")
            .withBindGroupLayout(BindGroupLayout.builder().withUniform("Projection", UniformType.UNIFORM_BUFFER)
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER).build())
            .withBindGroupLayout(PORTAL).withDepthStencilState(DepthStencilState.DEFAULT)
            .withColorTargetState(ColorTargetState.DEFAULT).withCull(false)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).build();
    }

    static RenderPipeline featherPipeline() {
        return RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("wormholes", "portal_feather"))
            .withVertexShader(Identifier.fromNamespaceAndPath("wormholes", "core/portal_composite")).withFragmentShader(Identifier.fromNamespaceAndPath("wormholes", "core/portal_feather"))
            .withShaderDefine("PORTAL_SHAPE")
            .withBindGroupLayout(BindGroupLayout.builder().withUniform("Projection", UniformType.UNIFORM_BUFFER)
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER).build())
            .withBindGroupLayout(PORTAL).withBindGroupLayout(FEATHER)
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).withCull(false)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).build();
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

    void prepare() {
        RenderSystem.assertOnRenderThread();
        if (closed) {
            throw new IllegalStateException("Portal pipeline bank is closed");
        }
        if (prepared) {
            return;
        }
        try {
            for (RenderPipeline pipeline : descriptors) {
                CompiledRenderPipeline result = device.compilePipeline(pipeline, sources, Util.backgroundExecutor()).join().finishCompile();
                if (result == null) {
                    throw new IllegalStateException("Could not compile portal pipeline " + pipeline.getLocation());
                }
                compiled.put(pipeline, result);
            }
            prepared = true;
        } catch (RuntimeException | Error failure) {
            try {
                close();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    CompiledRenderPipeline layer() {
        return compiled(layer);
    }

    CompiledRenderPipeline terrain(ChunkSectionLayer layer, boolean reflected, boolean rgss) {
        EnumMap<ChunkSectionLayer, RenderPipeline> bank = rgss
            ? reflected ? reflectedRgssTerrain : rgssTerrain
            : reflected ? reflectedTerrain : terrain;
        return compiled(bank.get(layer));
    }

    CompiledRenderPipeline composite() {
        return compiled(composite);
    }

    CompiledRenderPipeline compositeShape() {
        return compiled(compositeShape);
    }

    CompiledRenderPipeline feather() {
        return compiled(feather);
    }

    PipelineCache reflectedFeatures() {
        if (!prepared || closed) {
            throw new IllegalStateException("Portal pipeline bank is not prepared");
        }
        if (reflectedFeatures == null) {
            reflectedFeatures = new ReflectedCache();
        }
        return reflectedFeatures;
    }

    static RenderPipeline terrain(String name, RenderPipeline original, boolean rgss, VertexFormat format) {
        RenderPipeline.Builder builder = RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("wormholes", "portal_" + name + (rgss ? "_rgss" : "")))
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
        if (closed) {
            return;
        }
        closed = true;
        prepared = false;
        RuntimeException failure = null;
        for (CompiledRenderPipeline pipeline : compiled.values()) {
            try {
                pipeline.close();
            } catch (RuntimeException closeFailure) {
                failure = collectFailure(failure, closeFailure);
            }
        }
        compiled.clear();
        if (reflectedFeatures != null) {
            try {
                reflectedFeatures.close();
            } catch (RuntimeException closeFailure) {
                failure = collectFailure(failure, closeFailure);
            }
            reflectedFeatures = null;
        }
        try {
            sources.close();
        } catch (RuntimeException closeFailure) {
            failure = collectFailure(failure, closeFailure);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static void createTerrain(EnumMap<ChunkSectionLayer, RenderPipeline> terrain,
                                      EnumMap<ChunkSectionLayer, RenderPipeline> reflectedTerrain, boolean rgss) {
        terrain.put(ChunkSectionLayer.SOLID, terrain("solid", RenderPipelines.SOLID_BLOCK, rgss, DefaultVertexFormat.BLOCK));
        terrain.put(ChunkSectionLayer.CUTOUT, terrain("cutout", RenderPipelines.CUTOUT_BLOCK, rgss, DefaultVertexFormat.BLOCK));
        terrain.put(ChunkSectionLayer.TRANSLUCENT, terrain("translucent", RenderPipelines.TRANSLUCENT_BLOCK, rgss, DefaultVertexFormat.BLOCK));
        reflectedTerrain.put(ChunkSectionLayer.SOLID, terrain("solid_reflected", RenderPipelines.SOLID_BLOCK, rgss, DefaultVertexFormat.BLOCK));
        reflectedTerrain.put(ChunkSectionLayer.CUTOUT, terrain("cutout_reflected", RenderPipelines.CUTOUT_BLOCK, rgss, DefaultVertexFormat.BLOCK));
        reflectedTerrain.put(ChunkSectionLayer.TRANSLUCENT, terrain("translucent_reflected", RenderPipelines.TRANSLUCENT_BLOCK, rgss, DefaultVertexFormat.BLOCK));
    }

    private CompiledRenderPipeline compiled(RenderPipeline pipeline) {
        if (!prepared || closed) {
            throw new IllegalStateException("Portal pipeline bank is not prepared");
        }
        return Objects.requireNonNull(compiled.get(pipeline));
    }

    private static RuntimeException collectFailure(RuntimeException previous, RuntimeException failure) {
        if (previous == null) {
            return failure;
        }
        if (previous != failure) {
            previous.addSuppressed(failure);
        }
        return previous;
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
            RuntimeException failure = null;
            for (CachedIncludeSource include : includes.values()) {
                try {
                    include.close();
                } catch (RuntimeException closeFailure) {
                    failure = collectFailure(failure, closeFailure);
                }
            }
            includes.clear();
            shaders.clear();
            if (failure != null) {
                throw failure;
            }
        }
    }
}
