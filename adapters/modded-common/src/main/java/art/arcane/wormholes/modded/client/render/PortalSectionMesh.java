package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.modded.client.ClientMeshWorld;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Objects;
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
    private final EnumMap<ChunkSectionLayer, PortalTerrainVertices> terrain = new EnumMap<>(ChunkSectionLayer.class);
    private final PortalTerrainMaterials materials;
    private BlockState materialState;
    private boolean materialFluid;
    private int materialEmission;
    private float centerX;
    private float centerY;
    private float centerZ;
    private MeshData.SortState translucentSort;

    private PortalSectionMesh(PortalTerrainMaterials materials) {
        this.materials = Objects.requireNonNull(materials, "materials");
    }

    static PortalSectionMesh compile(long sectionKey, BlockAndTintGetter world, BlockStateModelSet models,
                                     FluidStateModelSet fluids, BlockColors colors, boolean ambientOcclusion, PortalTerrainMaterials materials) {
        PortalSectionMesh result = new PortalSectionMesh(materials);
        ClientMeshWorld source = world instanceof ClientMeshWorld snapshot ? snapshot : null;
        BlockAndTintGetter destination = source == null ? world : source.destination();
        PortalVertexTransform vertices = source == null ? null : new PortalVertexTransform(source.transform());
        ModelBlockRenderer renderer = new ModelBlockRenderer(ambientOcclusion, true, colors);
        FluidRenderer fluidRenderer = new FluidRenderer(fluids);
        BlockQuadOutput output = vertices == null
            ? (x, y, z, quad, instance) -> result.consumer(quad.materialInfo().layer()).putBlockBakedQuad(x, y, z, quad, instance)
            : (x, y, z, quad, instance) -> vertices.target(result.consumer(quad.materialInfo().layer())).putBlockBakedQuad(x, y, z, quad, instance);
        FluidRenderer.Output fluidOutput = vertices == null ? result::consumer : layer -> vertices.target(result.consumer(layer));
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        int baseX = SectionPos.x(sectionKey) << 4;
        int baseY = SectionPos.y(sectionKey) << 4;
        int baseZ = SectionPos.z(sectionKey) << 4;
        BlockModelLighter.enableCaching();
        try (PortalShaderScope scope = PortalShaderScope.vertices(); PortalTerrainLighting lighting = new PortalTerrainLighting(materials.lighting())) {
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
                        result.centerX = vertices == null ? x + 0.5F : vertices.centerX();
                        result.centerY = vertices == null ? y + 0.5F : vertices.centerY();
                        result.centerZ = vertices == null ? z + 0.5F : vertices.centerZ();
                        result.materialEmission = state.getLightEmission();
                        FluidState fluid = state.getFluidState();
                        if (!fluid.isEmpty()) {
                            result.materialState = fluid.createLegacyBlock();
                            result.materialFluid = true;
                            if (vertices != null) {
                                vertices.inputOrigin(position.getX() & 15, position.getY() & 15, position.getZ() & 15);
                            }
                            fluidRenderer.tesselate(destination, position, fluidOutput, state, fluid);
                        }
                        if (state.getRenderShape() == RenderShape.MODEL) {
                            result.materialState = state;
                            result.materialFluid = false;
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
                    if (materials.enabled()) {
                        mesh = result.extend(entry.getKey(), mesh);
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

    private VertexConsumer consumer(ChunkSectionLayer layer) {
        BufferBuilder builder = builder(layer);
        if (!materials.enabled()) {
            return builder;
        }
        PortalTerrainVertices vertices = terrain.computeIfAbsent(layer, ignored -> new PortalTerrainVertices(builder));
        vertices.block(materials.blockId(materialState), materialFluid, materialEmission, centerX, centerY, centerZ);
        return vertices;
    }

    private MeshData extend(ChunkSectionLayer layer, MeshData source) {
        ByteBufferBuilder allocation = new ByteBufferBuilder(Math.multiplyExact(source.drawState().vertexCount(), materials.format().getVertexSize()));
        try {
            MeshData extended = terrain.get(layer).expand(source, allocation);
            source.close();
            allocations.put(layer, allocation).close();
            return extended;
        } catch (RuntimeException | Error failure) {
            source.close();
            allocation.close();
            throw failure;
        }
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
