package art.arcane.wormholes.modded.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.EnumMap;
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
    private final List<RenderPipeline> descriptors;
    private final Map<RenderPipeline, CompiledRenderPipeline> compiled = new IdentityHashMap<>(16);
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
    }

    static RenderPipeline compositePipeline() {
        return RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("wormholes", "portal_composite"))
            .withVertexShader(Identifier.fromNamespaceAndPath("wormholes", "core/portal_composite")).withFragmentShader(Identifier.fromNamespaceAndPath("wormholes", "core/portal_composite"))
            .withBindGroupLayout(BindGroupLayout.builder().withUniform("Projection", UniformType.UNIFORM_BUFFER)
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withSampler("Sampler0").build())
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
                .withSampler("Sampler0").build())
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
            .withBindGroupLayout(BindGroupLayout.builder().withSampler("Sampler0")
                .withSampler("Sampler1").build())
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
            GpuDevice device = RenderSystem.getDevice();
            ShaderManager shaderManager = Minecraft.getInstance().getShaderManager();
            for (RenderPipeline pipeline : descriptors) {
                CompiledRenderPipeline result = device.precompilePipeline(pipeline, shaderManager::getShader);
                if (!result.isValid()) {
                    throw new IllegalStateException("Could not compile portal pipeline " + pipeline.getLocation());
                }
                compiled.put(pipeline, result);
            }
            prepared = true;
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    RenderPipeline layer() {
        return ready(layer);
    }

    RenderPipeline terrain(ChunkSectionLayer layer, boolean reflected, boolean rgss) {
        EnumMap<ChunkSectionLayer, RenderPipeline> bank = rgss
            ? reflected ? reflectedRgssTerrain : rgssTerrain
            : reflected ? reflectedTerrain : terrain;
        return ready(bank.get(layer));
    }

    RenderPipeline composite() {
        return ready(composite);
    }

    RenderPipeline compositeShape() {
        return ready(compositeShape);
    }

    RenderPipeline feather() {
        return ready(feather);
    }

    static RenderPipeline terrain(String name, RenderPipeline original, boolean rgss, VertexFormat format) {
        RenderPipeline.Builder builder = RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("wormholes", "portal_" + name + (rgss ? "_rgss" : "")))
            .withVertexShader(Identifier.fromNamespaceAndPath("wormholes", "core/portal_block")).withFragmentShader(Identifier.fromNamespaceAndPath("wormholes", "core/portal_block"))
            .withDepthStencilState(original.getDepthStencilState()).withCull(!name.endsWith("_reflected") && original.isCull())
            .withVertexBinding(0, format).withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withColorTargetState(original.getColorTargetState()).withBindGroupLayout(PORTAL);
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
        compiled.clear();
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

    private RenderPipeline ready(RenderPipeline pipeline) {
        if (!prepared || closed) {
            throw new IllegalStateException("Portal pipeline bank is not prepared");
        }
        Objects.requireNonNull(compiled.get(pipeline));
        return pipeline;
    }
}
