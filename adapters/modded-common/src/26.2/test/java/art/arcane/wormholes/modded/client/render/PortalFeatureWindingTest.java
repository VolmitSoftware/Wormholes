package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.math.Face;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.AxisPermutation;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.irisshaders.iris.pipeline.IrisPipelines;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.junit.Test;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.mockito.MockedStatic;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

public class PortalFeatureWindingTest {
    @Test
    public void cachedReflectedPipelineReceivesIrisBindingsAfterShadersBecomeActive() {
        StagedVertexBuffer buffer = mock(StagedVertexBuffer.class);
        RenderPipeline source = RenderPipelines.SOLID_BLOCK;
        try (MockedStatic<PortalShaderScope> shaders = mockStatic(PortalShaderScope.class);
             MockedStatic<IrisPipelines> iris = mockStatic(IrisPipelines.class);
             PortalFeatureRenderer.WindingScope winding = new PortalFeatureRenderer.WindingScope(buffer, true)) {
            RenderPipeline reflected = PortalFeatureRenderer.reflectedPipeline(source);
            assertFalse(reflected.isCull());
            iris.verifyNoInteractions();
            shaders.when(PortalShaderScope::shaders).thenReturn(true);
            assertSame(reflected, PortalFeatureRenderer.reflectedPipeline(source));
            iris.verify(() -> IrisPipelines.copyPipeline(source, reflected));
        }
        assertSame(source, PortalFeatureRenderer.reflectedPipeline(source));
    }

    @Test
    public void cameraAndRigidReflectionsCombineWithoutDoubleReversingFaces() {
        StagedVertexBuffer buffer = mock(StagedVertexBuffer.class);
        OpticTransform ordinary = OpticTransform.of(AxisPermutation.of(Face.E, Face.U, Face.S), 0, 0, 0);
        for (boolean cameraReflection : new boolean[] {false, true}) {
            CameraRenderState camera = new CameraRenderState();
            camera.viewRotationMatrix = new Matrix4f().scale(cameraReflection ? -2.0F : 2.0F, 3.0F, 0.5F);
            for (boolean rigidReflection : new boolean[] {false, true}) {
                boolean reflected = cameraReflection != rigidReflection;
                assertEquals(reflected, PortalFeatureRenderer.reflected(camera, rigidReflection ? reflection() : ordinary));
                try (MockedStatic<PortalShaderScope> shaders = mockStatic(PortalShaderScope.class);
                     PortalFeatureRenderer.WindingScope scope = new PortalFeatureRenderer.WindingScope(buffer,
                    PortalFeatureRenderer.reflected(camera, rigidReflection ? reflection() : ordinary))) {
                    assertOrdering(buffer, PrimitiveTopology.QUADS, reflected ? new int[] {0, 3, 2, 1} : new int[] {0, 1, 2, 3});
                    RenderPipeline pipeline = PortalFeatureRenderer.reflectedPipeline(RenderPipelines.SOLID_BLOCK);
                    if (reflected) {
                        assertFalse(pipeline.isCull());
                    } else {
                        assertSame(RenderPipelines.SOLID_BLOCK, pipeline);
                    }
                }
            }
        }
        assertOrdering(buffer, PrimitiveTopology.QUADS, new int[] {0, 1, 2, 3});
    }

    @Test
    public void reflectedFeatureMeshesReverseEveryFaceWithoutChangingVertexAttributes() {
        StagedVertexBuffer buffer = mock(StagedVertexBuffer.class);
        try (PortalFeatureRenderer.WindingScope scope = new PortalFeatureRenderer.WindingScope(buffer, true)) {
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
        try (PortalFeatureRenderer.WindingScope scope = new PortalFeatureRenderer.WindingScope(portal, true)) {
            assertOrdering(ordinary, PrimitiveTopology.QUADS, new int[] {0, 1, 2, 3});
            try (PortalFeatureRenderer.WindingScope rotated = new PortalFeatureRenderer.WindingScope(portal, false)) {
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
        try (PortalFeatureRenderer.WindingScope scope = new PortalFeatureRenderer.WindingScope(outer, true)) {
            assertThrows(IllegalStateException.class, () -> {
                try (PortalFeatureRenderer.WindingScope nested = new PortalFeatureRenderer.WindingScope(inner, true)) {
                    assertOrdering(inner, PrimitiveTopology.QUADS, new int[] {0, 3, 2, 1});
                    throw new IllegalStateException("Feature preparation failed");
                }
            });
            assertOrdering(outer, PrimitiveTopology.QUADS, new int[] {0, 3, 2, 1});
            assertOrdering(inner, PrimitiveTopology.QUADS, new int[] {0, 1, 2, 3});
        }
        assertOrdering(outer, PrimitiveTopology.QUADS, new int[] {0, 1, 2, 3});
    }

    private static OpticTransform reflection() {
        return OpticTransform.of(AxisPermutation.of(Face.E, Face.U, Face.N), 0, 0, 0);
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
