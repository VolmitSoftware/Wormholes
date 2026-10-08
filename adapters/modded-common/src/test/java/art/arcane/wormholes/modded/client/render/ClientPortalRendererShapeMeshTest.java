package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.aperture.AperturePolygon;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.FitMode;
import art.arcane.optics.shape.PlaneShape;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.optics.shape.ShapeMesh;
import art.arcane.optics.shape.ShapeRaster;
import art.arcane.optics.shape.Shapes;
import art.arcane.wormholes.portal.ApertureKind;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.IndexType;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

public class ClientPortalRendererShapeMeshTest {
    private static final int EDGE = 7;
    private static final double POSITION_EPSILON = 1.0E-4D;
    private static final double OUTLINE_TOLERANCE_CELLS = 0.05D;

    @Test
    public void shapedApertureUploadsTheShapeMeshOnThePlaneWithSignedDistances() {
        for (Face facing : Face.values()) {
            ApertureDescriptor geometry = shaped(facing, ShapeDescriptor.of(Shapes.flower(5, 1.0D, 0.6D, 0.0D), FitMode.CONTAIN));
            AperturePolygon aperture = AperturePolygon.from(geometry);
            ShapeMesh shape = aperture.planeShape().mesh(8, PortalApertureMesh.renderMask(geometry, aperture.planeShape()));
            Upload upload = upload(() -> PortalApertureMesh.shaped(shape, aperture, geometry));
            assertTrue(shape.triangleCount() > 0);
            assertEquals(shape.vertexCount() * PortalApertureMesh.SHAPE_VERTEX_BYTES, upload.vertices().remaining());
            PlaneShape plane = aperture.planeShape();
            float[] positions = shape.positions();
            for (int vertex = 0; vertex < shape.vertexCount(); vertex++) {
                int offset = vertex * PortalApertureMesh.SHAPE_VERTEX_BYTES;
                Vec3d expected = aperture.point(positions[vertex * 2], positions[vertex * 2 + 1]);
                double x = upload.vertices().getFloat(offset) + geometry.originX();
                double y = upload.vertices().getFloat(offset + 4) + geometry.originY();
                double z = upload.vertices().getFloat(offset + 8) + geometry.originZ();
                assertEquals(facing + " x", expected.x(), x, POSITION_EPSILON);
                assertEquals(facing + " y", expected.y(), y, POSITION_EPSILON);
                assertEquals(facing + " z", expected.z(), z, POSITION_EPSILON);
                assertEquals(facing + " plane", 0.0D, geometry.signedDistance(x, y, z), POSITION_EPSILON);
                assertEquals(facing + " distance", shape.distances()[vertex], upload.vertices().getFloat(offset + 12), 0.0F);
                assertEquals(0.0F, upload.vertices().getFloat(offset + 16), 0.0F);
                assertTrue(facing + " vertex outside the shape", plane.signedDistance(positions[vertex * 2], positions[vertex * 2 + 1]) <= OUTLINE_TOLERANCE_CELLS);
            }
            int[] indices = new int[upload.indices().remaining() / Integer.BYTES];
            upload.indices().asIntBuffer().get(indices);
            assertTrue(Arrays.equals(shape.indices(), indices));
            RenderPass pass = mock(RenderPass.class);
            upload.mesh().draw(pass);
            verify(pass).setIndexBuffer(any(), eq(IndexType.INT));
            verify(pass).drawIndexed(shape.indices().length, 1, 0, 0, 0);
        }
    }

    @Test
    public void renderMaskKeepsTheEdgeSmoothAcrossCellsBelowTheRasterThreshold() {
        ApertureDescriptor geometry = shaped(Face.S, ShapeDescriptor.of(Shapes.circle(1.0D), FitMode.CONTAIN));
        AperturePolygon aperture = AperturePolygon.from(geometry);
        double circle = Math.PI * (EDGE / 2.0D) * (EDGE / 2.0D);
        double smooth = area(aperture.planeShape().mesh(8, PortalApertureMesh.renderMask(geometry, aperture.planeShape())));
        double stepped = area(aperture.mesh(8));
        assertEquals(circle, smooth, circle * 0.01D);
        assertTrue(stepped < circle * 0.97D);
    }

    @Test
    public void renderMaskNeverOpensCellsTheShapeCoversButTheFrameDoesNot() {
        ApertureDescriptor shaped = shaped(Face.S, ShapeDescriptor.of(Shapes.circle(1.0D), FitMode.CONTAIN));
        long[] holed = shaped.apertureMask();
        int center = (EDGE / 2) * EDGE + EDGE / 2;
        holed[center >>> 6] &= ~(1L << (center & 63));
        ApertureDescriptor geometry = descriptor(Face.S, shaped.shape(), holed);
        AperturePolygon aperture = AperturePolygon.from(geometry);
        long[] mask = PortalApertureMesh.renderMask(geometry, aperture.planeShape());
        assertEquals(0L, mask[center >>> 6] & (1L << (center & 63)));
        ShapeMesh mesh = aperture.planeShape().mesh(8, mask);
        float[] positions = mesh.positions();
        int[] indices = mesh.indices();
        for (int triangle = 0; triangle < mesh.triangleCount(); triangle++) {
            double u = 0.0D;
            double v = 0.0D;
            for (int corner = 0; corner < 3; corner++) {
                u += positions[indices[triangle * 3 + corner] * 2] / 3.0D;
                v += positions[indices[triangle * 3 + corner] * 2 + 1] / 3.0D;
            }
            assertFalse("triangle inside the unbuilt center cell", (int) Math.floor(u) == EDGE / 2 && (int) Math.floor(v) == EDGE / 2);
        }
    }

    @Test
    public void emptyShapeMeshUploadsNothing() {
        ApertureDescriptor geometry = shaped(Face.S, ShapeDescriptor.of(Shapes.circle(1.0D), FitMode.CONTAIN));
        long[] corner = ApertureDescriptor.apertureMask(EDGE, EDGE, cornerOnly());
        ApertureDescriptor cornerOnly = descriptor(Face.S, geometry.shape(), corner);
        AperturePolygon aperture = AperturePolygon.from(cornerOnly);
        ShapeMesh shape = aperture.mesh(8);
        assertTrue(shape.isEmpty());
        Upload upload = upload(() -> PortalApertureMesh.shaped(shape, aperture, cornerOnly));
        assertNull(upload.mesh());
        assertNull(upload.vertices());
    }

    @Test
    public void fullApertureKeepsTheQuadVertexBytes() {
        boolean[] open = new boolean[EDGE * EDGE];
        Arrays.fill(open, true);
        open[0] = false;
        open[EDGE * 3 + 3] = false;
        for (Face facing : Face.values()) {
            for (boolean front : new boolean[] {true, false}) {
                ApertureDescriptor geometry = descriptor(facing, ShapeDescriptor.FULL, ApertureDescriptor.apertureMask(EDGE, EDGE, open), front);
                AperturePolygon aperture = AperturePolygon.from(geometry);
                Upload upload = upload(() -> PortalApertureMesh.quads(aperture, geometry));
                List<Float> expected = new ArrayList<>();
                for (AperturePolygon.Rectangle rectangle : aperture.rectangles()) {
                    for (Vec3d point : aperture.vertices(rectangle)) {
                        expected.add((float) (point.x() - geometry.originX()));
                        expected.add((float) (point.y() - geometry.originY()));
                        expected.add((float) (point.z() - geometry.originZ()));
                    }
                }
                assertEquals(expected.size() * Float.BYTES, upload.vertices().remaining());
                for (int index = 0; index < expected.size(); index++) {
                    assertEquals(Float.floatToRawIntBits(expected.get(index)), Float.floatToRawIntBits(upload.vertices().getFloat(index * Float.BYTES)));
                }
                int quads = aperture.rectangles().size();
                assertEquals(quads * 6 * Short.BYTES, upload.indices().remaining());
                for (int quad = 0; quad < quads; quad++) {
                    int base = quad * 4;
                    int[] pattern = {base, base + 1, base + 2, base + 2, base + 3, base};
                    for (int corner = 0; corner < 6; corner++) {
                        assertEquals(pattern[corner], Short.toUnsignedInt(upload.indices().getShort((quad * 6 + corner) * Short.BYTES)));
                    }
                }
            }
        }
    }

    @Test
    public void subdivisionsStayWithinTheSubcellBudget() {
        assertEquals(8, PortalApertureMesh.subdivisions(8, EDGE, EDGE));
        assertEquals(16, PortalApertureMesh.subdivisions(16, 64, 64));
        assertEquals(4, PortalApertureMesh.subdivisions(16, 512, 512));
        assertEquals(2, PortalApertureMesh.subdivisions(8, 1024, 1024));
        assertEquals(1, PortalApertureMesh.subdivisions(8, 4096, 2048));
        assertTrue((long) 300 * 300 * PortalApertureMesh.subdivisions(16, 300, 300) * PortalApertureMesh.subdivisions(16, 300, 300)
            <= PortalApertureMesh.MAX_SUBDIVIDED_CELLS);
    }

    private static double area(ShapeMesh mesh) {
        float[] positions = mesh.positions();
        int[] indices = mesh.indices();
        double total = 0.0D;
        for (int triangle = 0; triangle < mesh.triangleCount(); triangle++) {
            int a = indices[triangle * 3] * 2;
            int b = indices[triangle * 3 + 1] * 2;
            int c = indices[triangle * 3 + 2] * 2;
            total += Math.abs((positions[b] - positions[a]) * (positions[c + 1] - positions[a + 1])
                - (positions[c] - positions[a]) * (positions[b + 1] - positions[a + 1])) * 0.5D;
        }
        return total;
    }

    private static Upload upload(MeshFactory factory) {
        GpuDevice device = mock(GpuDevice.class);
        List<ByteBuffer> buffers = new ArrayList<>();
        doAnswer(invocation -> {
            ByteBuffer data = invocation.getArgument(2);
            ByteBuffer copy = ByteBuffer.allocate(data.remaining()).order(ByteOrder.nativeOrder());
            copy.put(data.duplicate()).flip();
            buffers.add(copy);
            return mock(GpuBuffer.class);
        }).when(device).createBuffer(any(), anyInt(), any(ByteBuffer.class));
        try (MockedStatic<RenderSystem> system = mockStatic(RenderSystem.class)) {
            system.when(RenderSystem::getDevice).thenReturn(device);
            PortalGpuMesh mesh = factory.create();
            return new Upload(mesh, buffers.isEmpty() ? null : buffers.get(0), buffers.size() < 2 ? null : buffers.get(1));
        }
    }

    private static boolean[] cornerOnly() {
        boolean[] open = new boolean[EDGE * EDGE];
        open[0] = true;
        return open;
    }

    private static ApertureDescriptor shaped(Face facing, ShapeDescriptor shape) {
        boolean[] open = new boolean[EDGE * EDGE];
        Arrays.fill(open, true);
        ApertureDescriptor full = descriptor(facing, shape, ApertureDescriptor.apertureMask(EDGE, EDGE, open));
        return descriptor(facing, shape, ShapeRaster.of(full.planeShape(), ShapeRaster.DEFAULT_SUBSAMPLES, ShapeRaster.DEFAULT_THRESHOLD).insideMask());
    }

    private static ApertureDescriptor descriptor(Face facing, ShapeDescriptor shape, long[] mask) {
        return descriptor(facing, shape, mask, true);
    }

    private static ApertureDescriptor descriptor(Face facing, ShapeDescriptor shape, long[] mask, boolean front) {
        return new ApertureDescriptor(-12, 70, 33, facing.ordinal(), front, 0, false, EDGE, EDGE, mask, shape,
            0.0F, 0.0F, 1.0F, 64, 0, 0, 0, 0, 0, 0, ApertureKind.FRAME, 0.0D, 0, 1L, List.of());
    }

    private interface MeshFactory {
        PortalGpuMesh create();
    }

    private record Upload(PortalGpuMesh mesh, ByteBuffer vertices, ByteBuffer indices) {
    }
}
