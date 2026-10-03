package art.arcane.wormholes.modded.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.api.v0.IrisProgram;
import net.irisshaders.iris.pipeline.IrisPipelines;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.samplers.IrisSamplers;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

import java.util.EnumMap;
import java.util.Locale;

final class PortalIrisTerrain {
    private static final EnumMap<ChunkSectionLayer, RenderPipeline> NORMAL = new EnumMap<>(ChunkSectionLayer.class);
    private static final EnumMap<ChunkSectionLayer, RenderPipeline> REFLECTED = new EnumMap<>(ChunkSectionLayer.class);

    private PortalIrisTerrain() {
    }

    static CompiledRenderPipeline get(ChunkSectionLayer layer, boolean reflected) {
        RenderPipeline pipeline = (reflected ? REFLECTED : NORMAL).computeIfAbsent(layer, selected -> create(selected, reflected));
        return RenderSystem.getCompiledPipelineNullable(pipeline);
    }

    static GpuSampler sampler(int anisotropy) {
        return IrisSamplers.getTerrainCache(anisotropy);
    }

    static WorldRenderingPhase phase(ChunkSectionLayer layer) {
        return switch (layer) {
            case SOLID -> WorldRenderingPhase.TERRAIN_SOLID;
            case CUTOUT -> WorldRenderingPhase.TERRAIN_CUTOUT;
            case TRANSLUCENT -> WorldRenderingPhase.TERRAIN_TRANSLUCENT;
        };
    }

    private static RenderPipeline create(ChunkSectionLayer layer, boolean reflected) {
        RenderPipeline original = switch (layer) {
            case SOLID -> RenderPipelines.SOLID_BLOCK;
            case CUTOUT -> RenderPipelines.CUTOUT_BLOCK;
            case TRANSLUCENT -> RenderPipelines.TRANSLUCENT_BLOCK;
        };
        RenderPipeline pipeline = new TerrainPipeline(original, layer, reflected);
        IrisProgram program = switch (layer) {
            case SOLID -> IrisProgram.TERRAIN_SOLID;
            case CUTOUT -> IrisProgram.TERRAIN_CUTOUT;
            case TRANSLUCENT -> IrisProgram.TRANSLUCENT;
        };
        IrisApi.getInstance().assignPipeline(pipeline, program);
        IrisPipelines.assignPipelineShadow(pipeline, layer == ChunkSectionLayer.TRANSLUCENT
            ? ShaderKey.SHADOW_TRANSLUCENT : ShaderKey.SHADOW_TERRAIN_CUTOUT);
        return pipeline;
    }

    private static final class TerrainPipeline extends RenderPipeline {
        private TerrainPipeline(RenderPipeline original, ChunkSectionLayer layer, boolean reflected) {
            super(original.getLocation().withSuffix("_wormholes_shader_" + layer.name().toLowerCase(Locale.ROOT)
                    + (reflected ? "_reflected" : "")), original.getShaders(), original.getShaderDefines(),
                original.getBindGroupLayouts(), original.getColorTargetStates().toArray(ColorTargetState[]::new),
                original.getDepthStencilState(), original.getPolygonMode(), !reflected && original.isCull(),
                new VertexFormat[] {PortalTerrainVertices.FORMAT}, original.getPrimitiveTopology(),
                original.pushConstantSize(), original.getSortKey());
        }
    }
}
