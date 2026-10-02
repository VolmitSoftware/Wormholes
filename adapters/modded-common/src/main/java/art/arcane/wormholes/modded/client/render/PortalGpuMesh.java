package art.arcane.wormholes.modded.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

final class PortalGpuMesh implements AutoCloseable {
    private final GpuBuffer vertices;
    private final GpuBuffer indices;
    private final IndexType indexType;
    private final int indexCount;
    private final MeshData.SortState sorting;
    private float sortX = Float.NaN;
    private float sortY;
    private float sortZ;

    PortalGpuMesh(MeshData mesh, MeshData.SortState sorting) {
        vertices = RenderSystem.getDevice().createBuffer(() -> "Portal vertices", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
        indexCount = mesh.drawState().indexCount();
        this.sorting = sorting;
        indexType = mesh.drawState().indexType();
        try {
            if (mesh.indexBuffer() != null) {
                indices = uploadIndices(mesh.indexBuffer());
            } else {
                ByteBuffer sequential = MemoryUtil.memAlloc(indexCount * indexType.bytes);
                try {
                    for (int vertex = 0; vertex < mesh.drawState().vertexCount(); vertex += 4) {
                        putIndex(sequential, vertex);
                        putIndex(sequential, vertex + 1);
                        putIndex(sequential, vertex + 2);
                        putIndex(sequential, vertex + 2);
                        putIndex(sequential, vertex + 3);
                        putIndex(sequential, vertex);
                    }
                    sequential.flip();
                    indices = uploadIndices(sequential);
                } finally {
                    MemoryUtil.memFree(sequential);
                }
            }
        } catch (RuntimeException | Error failure) {
            vertices.close();
            throw failure;
        }
    }

    private static GpuBuffer uploadIndices(ByteBuffer data) {
        return RenderSystem.getDevice().createBuffer(() -> "Portal indices", GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, data);
    }

    private void putIndex(ByteBuffer data, int index) {
        if (indexType == IndexType.SHORT) {
            data.putShort((short) index);
        } else {
            data.putInt(index);
        }
    }

    long bytes() {
        return vertices.size() + indices.size();
    }

    void sort(float x, float y, float z) {
        if (sorting == null || (x == sortX && y == sortY && z == sortZ)) {
            return;
        }
        try (ByteBufferBuilder builder = new ByteBufferBuilder(indexCount * 4);
             ByteBufferBuilder.Result sorted = sorting.buildSortedIndexBuffer(builder, VertexSorting.byDistance(x, y, z))) {
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(indices.slice(), sorted.byteBuffer());
        }
        sortX = x;
        sortY = y;
        sortZ = z;
    }

    void draw(RenderPass pass) {
        pass.setVertexBuffer(0, vertices.slice());
        pass.setIndexBuffer(indices, indexType);
        pass.drawIndexed(indexCount, 1, 0, 0, 0);
    }

    @Override
    public void close() {
        vertices.close();
        indices.close();
    }
}
