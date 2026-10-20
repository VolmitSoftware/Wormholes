package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.Face;
import art.arcane.optics.shape.FitMode;
import art.arcane.optics.shape.PlaneShape;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.optics.shape.ShapeMesh;
import art.arcane.optics.shape.ShapeRaster;
import art.arcane.optics.shape.Shapes;
import art.arcane.wormholes.portal.ApertureKind;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PortalApertureMaskTest {
    private static final ShapeDescriptor CIRCLE = ShapeDescriptor.of(Shapes.circle(1.0D), FitMode.STRETCH);
    private static final int[][] SIZES = {{33, 4}, {4, 33}};

    @Test
    public void stretchedShapesRetainEdgesBelowTheRasterThreshold() {
        for (int[] size : SIZES) {
            PlaneShape plane = CIRCLE.fitTo(size[0], size[1]);
            ShapeRaster raster = plane.raster(ShapeRaster.DEFAULT_SUBSAMPLES);
            ApertureDescriptor geometry = descriptor(plane, raster.insideMask());
            int column = size[0] > size[1] ? 2 : 0;
            int row = size[1] > size[0] ? 2 : 0;
            int bit = row * size[0] + column;

            assertTrue(plane.contains(column + 0.875D, row + 0.875D));
            assertFalse(raster.inside(column, row));
            long[] mask = PortalApertureMesh.renderMask(geometry, plane);
            assertTrue((mask[bit >>> 6] & (1L << (bit & 63))) != 0L);
            assertEquals(area(plane.mesh(8, null)), area(plane.mesh(8, mask)), 1.0E-6D);
        }
    }

    @Test
    public void stretchedShapesKeepExcludedFrameCellsClosed() {
        for (int[] size : SIZES) {
            PlaneShape plane = CIRCLE.fitTo(size[0], size[1]);
            ShapeRaster raster = plane.raster(ShapeRaster.DEFAULT_SUBSAMPLES);
            int column = size[0] / 2;
            int row = size[1] / 2;
            int bit = row * size[0] + column;
            long[] frame = raster.insideMask();
            frame[bit >>> 6] &= ~(1L << (bit & 63));

            assertTrue(raster.inside(column, row));
            long[] mask = PortalApertureMesh.renderMask(descriptor(plane, frame), plane);
            assertEquals(0L, mask[bit >>> 6] & (1L << (bit & 63)));
            assertEquals(area(plane.mesh(8, null)) - 1.0D, area(plane.mesh(8, mask)), 1.0E-6D);
        }
    }

    private static ApertureDescriptor descriptor(PlaneShape plane, long[] mask) {
        return new ApertureDescriptor(0, 70, 0, Face.S.ordinal(), true, 0, false, plane.columns(), plane.rows(), mask, CIRCLE,
            0.0F, 0.0F, 1.0F, 64, 0, 0, 0, 0, 0, 0, ApertureKind.FRAME, 0.0D, 0, 1L, List.of());
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
}
