package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import art.arcane.wormholes.util.Direction;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.junit.Test;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;

public class PortalFeatureWindingTest {
    @Test
    public void reflectedFeatureMeshesReverseEveryFaceWithoutChangingVertexAttributes() {
        StagedVertexBuffer buffer = mock(StagedVertexBuffer.class);
        try (PortalFeatureRenderer.WindingScope scope = new PortalFeatureRenderer.WindingScope(buffer, reflection())) {
            assertOrdering(buffer, PrimitiveTopology.QUADS, new int[] {0, 3, 2, 1, 4, 7, 6, 5});
            assertOrdering(buffer, PrimitiveTopology.TRIANGLES, new int[] {0, 2, 1, 3, 5, 4});
            assertOrdering(buffer, PrimitiveTopology.TRIANGLE_FAN, new int[] {0, 4, 3, 2, 1});
            assertOrdering(buffer, PrimitiveTopology.DEBUG_LINES, new int[] {0, 1, 2, 3});
            assertOrdering(buffer, PrimitiveTopology.TRIANGLE_STRIP, new int[] {0, 1, 2, 3});
        }
    }

    @Test
    public void ordinaryBuffersAndPositiveRotationsKeepTheirWinding() {
        StagedVertexBuffer portal = mock(StagedVertexBuffer.class);
        StagedVertexBuffer ordinary = mock(StagedVertexBuffer.class);
        assertOrdering(portal, PrimitiveTopology.QUADS, new int[] {0, 1, 2, 3});
        try (PortalFeatureRenderer.WindingScope scope = new PortalFeatureRenderer.WindingScope(portal, reflection())) {
            assertOrdering(ordinary, PrimitiveTopology.QUADS, new int[] {0, 1, 2, 3});
            ClientViewEnvironment.Transform rotation = new ClientViewEnvironment.Transform(Direction.U, Direction.W, Direction.S,
                new GeometryVector(0, 0, 0));
            try (PortalFeatureRenderer.WindingScope rotated = new PortalFeatureRenderer.WindingScope(portal, rotation)) {
                assertOrdering(portal, PrimitiveTopology.QUADS, new int[] {0, 1, 2, 3});
            }
            assertOrdering(portal, PrimitiveTopology.QUADS, new int[] {0, 3, 2, 1});
        }
        assertOrdering(portal, PrimitiveTopology.QUADS, new int[] {0, 1, 2, 3});
    }

    @Test
    public void failedNestedPreparationRestoresThePreviousBufferAndClearsOnExit() {
        StagedVertexBuffer outer = mock(StagedVertexBuffer.class);
        StagedVertexBuffer inner = mock(StagedVertexBuffer.class);
        try (PortalFeatureRenderer.WindingScope scope = new PortalFeatureRenderer.WindingScope(outer, reflection())) {
            assertThrows(IllegalStateException.class, () -> {
                try (PortalFeatureRenderer.WindingScope nested = new PortalFeatureRenderer.WindingScope(inner, reflection())) {
                    assertOrdering(inner, PrimitiveTopology.QUADS, new int[] {0, 3, 2, 1});
                    throw new IllegalStateException("Feature preparation failed");
                }
            });
            assertOrdering(outer, PrimitiveTopology.QUADS, new int[] {0, 3, 2, 1});
            assertOrdering(inner, PrimitiveTopology.QUADS, new int[] {0, 1, 2, 3});
        }
        assertOrdering(outer, PrimitiveTopology.QUADS, new int[] {0, 1, 2, 3});
    }

    private static ClientViewEnvironment.Transform reflection() {
        return new ClientViewEnvironment.Transform(Direction.E, Direction.U, Direction.N, new GeometryVector(0, 0, 0));
    }

    private static void assertOrdering(StagedVertexBuffer buffer, PrimitiveTopology topology, int[] order) {
        try (ByteBufferBuilder allocation = new ByteBufferBuilder(512)) {
            BufferBuilder builder = new BufferBuilder(allocation, topology, DefaultVertexFormat.ENTITY);
            for (int index = 0; index < order.length; index++) {
                builder.addVertex(index, index + 0.25F, index + 0.5F).setColor(20 + index, 40 + index, 60 + index, 255)
                    .setUv(index / 8F, index / 16F).setUv1(index, index + 1).setUv2(80 + index, 160 + index).setNormal(0, 0, 1);
            }
            try (MeshData mesh = builder.buildOrThrow()) {
                ByteBuffer vertices = mesh.vertexBuffer();
                byte[] original = new byte[vertices.remaining()];
                vertices.duplicate().get(original);
                PortalFeatureRenderer.correctWinding(buffer, mesh);
                int stride = mesh.drawState().format().getVertexSize();
                assertEquals(order.length, mesh.drawState().vertexCount());
                for (int vertex = 0; vertex < order.length; vertex++) {
                    for (int offset = 0; offset < stride; offset++) {
                        assertEquals(original[order[vertex] * stride + offset], vertices.get(vertex * stride + offset));
                    }
                }
            }
        }
    }
}
