package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.modded.client.ClientMeshWorld;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One streamed section meshed on the mesher thread: per chunk render type, the block-format vertex bytes copied out of
 * the thread's reusable builders, ready for {@link #upload()} on the render thread.
 */
final class PortalSectionMesh implements AutoCloseable {
    private static final ThreadLocal<Map<RenderType, BufferBuilder>> BUILDERS = ThreadLocal.withInitial(IdentityHashMap::new);
    private static final VertexFormat FORMAT = DefaultVertexFormat.BLOCK;
    private final Map<RenderType, Layer> layers = new LinkedHashMap<>();

    private PortalSectionMesh() {
    }

    static PortalSectionMesh compile(long sectionKey, BlockAndTintGetter world, BlockRenderDispatcher blocks) {
        PortalSectionMesh result = new PortalSectionMesh();
        Map<RenderType, BufferBuilder> builders = BUILDERS.get();
        ClientMeshWorld source = world instanceof ClientMeshWorld snapshot ? snapshot : null;
        BlockAndTintGetter destination = source == null ? world : source.destination();
        PortalVertexTransform vertices = source == null ? null : new PortalVertexTransform(source.transform());
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        PoseStack pose = new PoseStack();
        RandomSource random = RandomSource.create();
        int baseX = SectionPos.x(sectionKey) << 4;
        int baseY = SectionPos.y(sectionKey) << 4;
        int baseZ = SectionPos.z(sectionKey) << 4;
        try {
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
                            VertexConsumer consumer = builder(builders, ItemBlockRenderTypes.getRenderLayer(fluid));
                            if (vertices != null) {
                                vertices.inputOrigin(position.getX() & 15, position.getY() & 15, position.getZ() & 15);
                                consumer = vertices.target(consumer);
                            }
                            blocks.renderLiquid(position, destination, consumer, state, fluid);
                        }
                        if (state.getRenderShape() == RenderShape.MODEL) {
                            VertexConsumer consumer = builder(builders, ItemBlockRenderTypes.getChunkRenderType(state));
                            pose.pushPose();
                            if (vertices != null) {
                                vertices.inputOrigin(0, 0, 0);
                                consumer = vertices.target(consumer);
                            } else {
                                pose.translate(x, y, z);
                            }
                            blocks.renderBatched(state, position, destination, pose, consumer, true, random);
                            pose.popPose();
                        }
                    }
                }
            }
            for (Map.Entry<RenderType, BufferBuilder> entry : builders.entrySet()) {
                BufferBuilder builder = entry.getValue();
                if (!builder.building()) {
                    continue;
                }
                BufferBuilder.RenderedBuffer rendered = builder.endOrDiscardIfEmpty();
                if (rendered == null) {
                    continue;
                }
                try {
                    int count = rendered.drawState().vertexCount();
                    VertexFormat format = rendered.drawState().format();
                    ByteBuffer copy = MemoryUtil.memAlloc(rendered.vertexBuffer().remaining());
                    copy.put(rendered.vertexBuffer().duplicate()).flip();
                    if (vertices != null) {
                        vertices.winding(copy, count, format.getVertexSize());
                    }
                    RenderType type = entry.getKey();
                    result.layers.put(type, new Layer(copy, format, count, type == RenderType.translucent() || type == RenderType.tripwire()
                        ? centers(copy, count, format.getVertexSize()) : null));
                } finally {
                    rendered.release();
                }
            }
            return result;
        } catch (RuntimeException | Error failure) {
            result.close();
            throw failure;
        } finally {
            for (BufferBuilder builder : builders.values()) {
                if (builder.building()) {
                    builder.discard();
                }
            }
        }
    }

    private static BufferBuilder builder(Map<RenderType, BufferBuilder> builders, RenderType type) {
        BufferBuilder builder = builders.computeIfAbsent(type, key -> new BufferBuilder(key.bufferSize()));
        if (!builder.building()) {
            builder.begin(VertexFormat.Mode.QUADS, FORMAT);
        }
        return builder;
    }

    private static float[] centers(ByteBuffer vertices, int vertexCount, int stride) {
        float[] centers = new float[vertexCount / 4 * 3];
        for (int quad = 0; quad < vertexCount / 4; quad++) {
            float x = 0.0F;
            float y = 0.0F;
            float z = 0.0F;
            for (int corner = 0; corner < 4; corner++) {
                int offset = (quad * 4 + corner) * stride;
                x += vertices.getFloat(offset);
                y += vertices.getFloat(offset + 4);
                z += vertices.getFloat(offset + 8);
            }
            centers[quad * 3] = x * 0.25F;
            centers[quad * 3 + 1] = y * 0.25F;
            centers[quad * 3 + 2] = z * 0.25F;
        }
        return centers;
    }

    /** Uploads every layer. Render thread only; the CPU copies stay owned by this mesh until {@link #close()}. */
    Map<RenderType, PortalGpuMesh> upload() {
        Map<RenderType, PortalGpuMesh> uploaded = new LinkedHashMap<>();
        try {
            for (Map.Entry<RenderType, Layer> entry : layers.entrySet()) {
                Layer layer = entry.getValue();
                uploaded.put(entry.getKey(), PortalGpuMesh.quads(layer.vertices(), layer.format(), layer.vertexCount(), layer.centers()));
            }
            return uploaded;
        } catch (RuntimeException | Error failure) {
            for (PortalGpuMesh mesh : uploaded.values()) {
                mesh.close();
            }
            throw failure;
        }
    }

    @Override
    public void close() {
        for (Layer layer : layers.values()) {
            MemoryUtil.memFree(layer.vertices());
        }
        layers.clear();
    }

    /** Vertices in the format the builder actually produced: Iris widens {@code BLOCK} to its terrain format under a shader pack. */
    private record Layer(ByteBuffer vertices, VertexFormat format, int vertexCount, float[] centers) {
    }
}
