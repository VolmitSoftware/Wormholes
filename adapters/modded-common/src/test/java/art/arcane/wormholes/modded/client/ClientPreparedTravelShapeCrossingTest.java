package art.arcane.wormholes.modded.client;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.aperture.AperturePolygon;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.FitMode;
import art.arcane.optics.shape.PlaneShape;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.optics.shape.ShapeRaster;
import art.arcane.optics.shape.Shapes;
import art.arcane.wormholes.portal.ApertureKind;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ClientPreparedTravelShapeCrossingTest {
    private static final int EDGE = 7;
    private static final int SAMPLES = 8;
    private static final ShapeDescriptor CIRCLE = ShapeDescriptor.of(Shapes.circle(1.0D), FitMode.CONTAIN);

    @Test
    public void shapedApertureRejectsTheCornerAndAcceptsTheCenter() {
        for (Face facing : new Face[] {Face.N, Face.S, Face.E, Face.W, Face.U, Face.D}) {
            ApertureDescriptor geometry = shaped(facing);
            assertFalse(facing + " corner", crosses(geometry, 0.5D, 0.5D));
            assertTrue(facing + " center", crosses(geometry, EDGE / 2.0D, EDGE / 2.0D));
        }
    }

    @Test
    public void openCellOutsideTheAnalyticEdgeIsNotACrossing() {
        ApertureDescriptor geometry = shaped(Face.S);
        PlaneShape plane = geometry.planeShape();
        double[] outside = null;
        for (int row = 0; row < EDGE && outside == null; row++) {
            for (int column = 0; column < EDGE && outside == null; column++) {
                if (!geometry.apertureOpen(column, row)) {
                    continue;
                }
                for (int sample = 0; sample < SAMPLES * SAMPLES && outside == null; sample++) {
                    double u = column + (sample % SAMPLES + 0.5D) / SAMPLES;
                    double v = row + (sample / SAMPLES + 0.5D) / SAMPLES;
                    if (!plane.contains(u, v)) {
                        outside = new double[] {u, v};
                    }
                }
            }
        }
        assertNotNull("the circle edge should cut through an open cell", outside);
        assertFalse(crosses(geometry, outside[0], outside[1]));
        assertTrue(crosses(geometry.withShape(ShapeDescriptor.FULL), outside[0], outside[1]));
    }

    @Test
    public void fullApertureKeepsCellCrossing() {
        ApertureDescriptor geometry = descriptor(Face.S, ShapeDescriptor.FULL, fullMask());
        assertTrue(crosses(geometry, 0.5D, 0.5D));
        assertTrue(crosses(geometry, EDGE - 0.01D, EDGE - 0.01D));
    }

    private static boolean crosses(ApertureDescriptor geometry, double column, double row) {
        Vec3d point = AperturePolygon.from(geometry).point(column, row);
        Face normal = geometry.facingDirection();
        double side = geometry.frontSide() ? 1.0D : -1.0D;
        Vec3 front = new Vec3(point.x() + normal.x() * side, point.y() + normal.y() * side, point.z() + normal.z() * side);
        Vec3 back = new Vec3(point.x() - normal.x() * side, point.y() - normal.y() * side, point.z() - normal.z() * side);
        return ClientPreparedTravel.crossed(geometry, front, back);
    }

    private static ApertureDescriptor shaped(Face facing) {
        ApertureDescriptor full = descriptor(facing, CIRCLE, fullMask());
        long[] effective = ShapeRaster.of(full.planeShape(), ShapeRaster.DEFAULT_SUBSAMPLES, ShapeRaster.DEFAULT_THRESHOLD).insideMask();
        return descriptor(facing, CIRCLE, effective);
    }

    private static long[] fullMask() {
        boolean[] open = new boolean[EDGE * EDGE];
        Arrays.fill(open, true);
        return ApertureDescriptor.apertureMask(EDGE, EDGE, open);
    }

    private static ApertureDescriptor descriptor(Face facing, ShapeDescriptor shape, long[] mask) {
        return new ApertureDescriptor(10, 64, -20, facing.ordinal(), true, 0, false, EDGE, EDGE, mask, shape,
            0.0F, 0.0F, 1.0F, 64, 0, 0, 0, 0, 0, 0, ApertureKind.FRAME, 0.0D, 0, 1L, List.of());
    }
}
