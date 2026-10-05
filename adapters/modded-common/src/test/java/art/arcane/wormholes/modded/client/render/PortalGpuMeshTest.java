package art.arcane.wormholes.modded.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;

public class PortalGpuMeshTest {
    @Test
    public void sharedSortingBufferPreservesCameraOrderingAcrossMeshes() {
        GpuDevice device = mock(GpuDevice.class, RETURNS_DEEP_STUBS);
        GpuBuffer vertices = mock(GpuBuffer.class);
        GpuBuffer indices = mock(GpuBuffer.class);
        List<Integer> firstIndices = new ArrayList<>();
        CommandEncoder encoder = device.createCommandEncoder();
        when(device.createBuffer(any(), anyInt(), any(ByteBuffer.class))).thenReturn(vertices, indices);
        doAnswer(invocation -> {
            ByteBuffer data = invocation.getArgument(1);
            assertEquals(24, data.remaining());
            firstIndices.add(Short.toUnsignedInt(data.getShort(data.position())));
            return null;
        }).when(encoder).writeToBuffer(any(), any(ByteBuffer.class));
        try (MockedStatic<RenderSystem> system = mockStatic(RenderSystem.class);
             ByteBufferBuilder allocation = new ByteBufferBuilder(256);
             ByteBufferBuilder sortingBuffer = new ByteBufferBuilder(1)) {
            system.when(RenderSystem::getDevice).thenReturn(device);
            BufferBuilder builder = new BufferBuilder(allocation, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION);
            for (int x : new int[]{0, 10}) {
                builder.addVertex(x, 0, 0);
                builder.addVertex(x + 1, 0, 0);
                builder.addVertex(x + 1, 1, 0);
                builder.addVertex(x, 1, 0);
            }
            try (MeshData data = builder.buildOrThrow()) {
                MeshData.SortState sorting = data.sortQuads(allocation, VertexSorting.DISTANCE_TO_ORIGIN);
                try (PortalGpuMesh first = new PortalGpuMesh(data, sorting);
                     PortalGpuMesh second = new PortalGpuMesh(data, sorting)) {
                    first.sort(sortingBuffer, 0, 0, 0);
                    first.sort(sortingBuffer, 0, 0, 0);
                    second.sort(sortingBuffer, 20, 0, 0);
                    first.sort(sortingBuffer, 20, 0, 0);
                    second.sort(sortingBuffer, 0, 0, 0);
                    assertEquals(List.of(4, 0, 0, 4), firstIndices);
                }
            }
        }
    }

    @Test
    public void nativeIndexedDrawUsesIndexCountBeforeInstanceCount() {
        GpuDevice device = mock(GpuDevice.class);
        GpuBuffer vertices = mock(GpuBuffer.class);
        GpuBuffer indices = mock(GpuBuffer.class);
        RenderPass pass = mock(RenderPass.class);
        when(device.createBuffer(any(), anyInt(), any(ByteBuffer.class))).thenReturn(vertices, indices);
        try (MockedStatic<RenderSystem> system = mockStatic(RenderSystem.class);
             ByteBufferBuilder allocation = new ByteBufferBuilder(128)) {
            system.when(RenderSystem::getDevice).thenReturn(device);
            BufferBuilder builder = new BufferBuilder(allocation, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION);
            builder.addVertex(0, 0, 0);
            builder.addVertex(1, 0, 0);
            builder.addVertex(1, 1, 0);
            builder.addVertex(0, 1, 0);
            try (MeshData data = builder.buildOrThrow()) {
                MeshData.SortState sorting = data.sortQuads(allocation, VertexSorting.DISTANCE_TO_ORIGIN);
                try (PortalGpuMesh mesh = new PortalGpuMesh(data, sorting)) {
                    mesh.draw(pass);
                    verify(pass).drawIndexed(6, 1, 0, 0, 0);
                }
            }
            verify(vertices).close();
            verify(indices).close();
        }
    }
}
