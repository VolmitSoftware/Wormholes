package art.arcane.wormholes.modded.client.render.iris;

import art.arcane.wormholes.modded.client.render.PortalTerrainVertices;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.irisshaders.iris.pipeline.IrisPipelines;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

import java.util.EnumMap;
import java.util.Locale;

final class IrisMeshTerrain {
    private static final EnumMap<ChunkSectionLayer, RenderPipeline> NORMAL = new EnumMap<>(ChunkSectionLayer.class);
    private static final EnumMap<ChunkSectionLayer, RenderPipeline> REFLECTED = new EnumMap<>(ChunkSectionLayer.class);

    private IrisMeshTerrain() {
    }

    static CompiledRenderPipeline compiled(ChunkSectionLayer layer, boolean reflected) {
        return RenderSystem.getCompiledPipelineNullable(pipeline(layer, reflected));
    }

    static void warm() {
        for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
            compiled(layer, false);
            compiled(layer, true);
        }
    }

    static WorldRenderingPhase phase(ChunkSectionLayer layer) {
        return switch (layer) {
            case SOLID -> WorldRenderingPhase.TERRAIN_SOLID;
            case CUTOUT -> WorldRenderingPhase.TERRAIN_CUTOUT;
            case TRANSLUCENT -> WorldRenderingPhase.TERRAIN_TRANSLUCENT;
        };
    }

    private static RenderPipeline pipeline(ChunkSectionLayer layer, boolean reflected) {
        return (reflected ? REFLECTED : NORMAL).computeIfAbsent(layer, selected -> create(selected, reflected));
    }

    private static RenderPipeline create(ChunkSectionLayer layer, boolean reflected) {
        RenderPipeline original = switch (layer) {
            case SOLID -> RenderPipelines.SOLID_BLOCK;
            case CUTOUT -> RenderPipelines.CUTOUT_BLOCK;
            case TRANSLUCENT -> RenderPipelines.TRANSLUCENT_BLOCK;
        };
        RenderPipeline pipeline = new TerrainPipeline(original, layer, reflected, PortalTerrainVertices.FORMAT);
        IrisPipelines.assignPipeline(pipeline, switch (layer) {
            case SOLID -> ShaderKey.TERRAIN_SOLID;
            case CUTOUT -> ShaderKey.TERRAIN_CUTOUT;
            case TRANSLUCENT -> ShaderKey.TERRAIN_TRANSLUCENT;
        });
        IrisPipelines.assignPipelineShadow(pipeline, layer == ChunkSectionLayer.TRANSLUCENT ? ShaderKey.SHADOW_TRANSLUCENT : ShaderKey.SHADOW_TERRAIN_CUTOUT);
        return pipeline;
    }

    private static final class TerrainPipeline extends RenderPipeline {
        private TerrainPipeline(RenderPipeline original, ChunkSectionLayer layer, boolean reflected, VertexFormat format) {
            super(original.getLocation().withSuffix("_wormholes_mesh_" + layer.name().toLowerCase(Locale.ROOT) + (reflected ? "_reflected" : "")),
                original.getShaders(), original.getShaderDefines(), original.getBindGroupLayouts(),
                original.getColorTargetStates().toArray(ColorTargetState[]::new), original.getDepthStencilState(), original.getPolygonMode(),
                !reflected && original.isCull(), new VertexFormat[] {format}, original.getPrimitiveTopology(), original.pushConstantSize(),
                original.getSortKey());
        }
    }
}
