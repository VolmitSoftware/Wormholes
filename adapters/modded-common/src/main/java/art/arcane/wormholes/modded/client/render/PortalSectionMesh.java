package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.modded.client.ClientMeshWorld;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.EnumMap;
import java.util.Map;

final class PortalSectionMesh implements AutoCloseable {
    private final EnumMap<ChunkSectionLayer, ByteBufferBuilder> allocations = new EnumMap<>(ChunkSectionLayer.class);
    private final EnumMap<ChunkSectionLayer, BufferBuilder> builders = new EnumMap<>(ChunkSectionLayer.class);
    private final EnumMap<ChunkSectionLayer, MeshData> meshes = new EnumMap<>(ChunkSectionLayer.class);
    private MeshData.SortState translucentSort;

    static PortalSectionMesh compile(long sectionKey, BlockAndTintGetter world, BlockStateModelSet models,
                                     FluidStateModelSet fluids, BlockColors colors, boolean ambientOcclusion) {
        PortalSectionMesh result = new PortalSectionMesh();
        ClientMeshWorld source = world instanceof ClientMeshWorld snapshot ? snapshot : null;
        BlockAndTintGetter destination = source == null ? world : source.destination();
        PortalVertexTransform vertices = source == null ? null : new PortalVertexTransform(source.transform());
        ModelBlockRenderer renderer = new ModelBlockRenderer(ambientOcclusion, true, colors);
        FluidRenderer fluidRenderer = new FluidRenderer(fluids);
        BlockQuadOutput output = vertices == null
            ? (x, y, z, quad, instance) -> result.builder(quad.materialInfo().layer()).putBlockBakedQuad(x, y, z, quad, instance)
            : (x, y, z, quad, instance) -> vertices.target(result.builder(quad.materialInfo().layer())).putBlockBakedQuad(x, y, z, quad, instance);
        FluidRenderer.Output fluidOutput = vertices == null ? result::builder : layer -> vertices.target(result.builder(layer));
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        int baseX = SectionPos.x(sectionKey) << 4;
        int baseY = SectionPos.y(sectionKey) << 4;
        int baseZ = SectionPos.z(sectionKey) << 4;
        BlockModelLighter.enableCaching();
        try (PortalShaderScope scope = PortalShaderScope.vertices()) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        position.set(baseX + x, baseY + y, baseZ + z);
                        BlockState state = world.getBlockState(position);
                        if (source != null) {
                            source.destinationBlock(baseX + x, baseY + y, baseZ + z, position);
                            vertices.destinationBlock(position, baseX, baseY, baseZ);
                        }
                        if (state.isAir()) {
                            continue;
                        }
                        FluidState fluid = state.getFluidState();
                        if (!fluid.isEmpty()) {
                            if (vertices != null) {
                                vertices.inputOrigin(position.getX() & 15, position.getY() & 15, position.getZ() & 15);
                            }
                            fluidRenderer.tesselate(destination, position, fluidOutput, state, fluid);
                        }
                        if (state.getRenderShape() == RenderShape.MODEL) {
                            if (vertices != null) {
                                vertices.inputOrigin(0, 0, 0);
                            }
                            renderer.tesselateBlock(output, vertices == null ? x : 0, vertices == null ? y : 0,
                                vertices == null ? z : 0, destination, position, state, models.get(state), state.getSeed(position));
                        }
                    }
                }
            }
            for (Map.Entry<ChunkSectionLayer, BufferBuilder> entry : result.builders.entrySet()) {
                MeshData mesh = entry.getValue().build();
                if (mesh != null) {
                    if (vertices != null) {
                        vertices.winding(mesh);
                    }
                    result.meshes.put(entry.getKey(), mesh);
                    if (entry.getKey() == ChunkSectionLayer.TRANSLUCENT) {
                        result.translucentSort = mesh.sortQuads(result.allocations.get(entry.getKey()), VertexSorting.DISTANCE_TO_ORIGIN);
                    }
                }
            }
            return result;
        } catch (RuntimeException | Error failure) {
            result.close();
            throw failure;
        } finally {
            BlockModelLighter.clearCache();
        }
    }

    Map<ChunkSectionLayer, MeshData> meshes() {
        return meshes;
    }

    MeshData.SortState translucentSort() {
        return translucentSort;
    }

    private BufferBuilder builder(ChunkSectionLayer layer) {
        BufferBuilder builder = builders.get(layer);
        if (builder == null) {
            ByteBufferBuilder allocation = new ByteBufferBuilder(32768);
            allocations.put(layer, allocation);
            builder = new BufferBuilder(allocation, PrimitiveTopology.QUADS, DefaultVertexFormat.BLOCK);
            builders.put(layer, builder);
        }
        return builder;
    }

    @Override
    public void close() {
        for (MeshData mesh : meshes.values()) {
            mesh.close();
        }
        for (ByteBufferBuilder allocation : allocations.values()) {
            allocation.close();
        }
        meshes.clear();
        allocations.clear();
    }
}
