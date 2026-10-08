package art.arcane.wormholes.portal;

import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Axis;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.shape.ShapeDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

final class AmbientOutlineGeometryShapeTest {
    @Test
    void circleOutlineSamplesLieOnTheAnalyticCircle() {
        ApertureCells built = wall();
        ShapedAperture shaped = ShapedAperture.of(built, Frame.canonical(Face.S), ShapeDescriptor.parse("circle"));
        assertNotNull(shaped);
        List<double[]> points = new AmbientOutlineGeometry().points(1L, Axis.Z, built, shaped.outline());
        assertFalse(points.isEmpty());
        for (double[] point : points) {
            assertEquals(3.5D, Math.hypot(point[0] - 3.5D, point[1] - 67.5D), 0.05D);
            assertEquals(0.5D, point[2], 1.0E-9D);
        }
        assertEquals(2.0D * Math.PI * 3.5D * 3.0D, points.size(), 6.0D);
    }

    @Test
    void fullShapesKeepTheCellEdgeOutline() {
        ApertureCells built = wall();
        AmbientOutlineGeometry outline = new AmbientOutlineGeometry();
        List<double[]> points = outline.points(1L, Axis.Z, built, null);
        List<double[]> expected = AmbientOutlineGeometry.build(built.getBlockPositions(), Axis.Z);
        assertEquals(expected.size(), points.size());
        for (int index = 0; index < expected.size(); index++) {
            assertEquals(expected.get(index)[0], points.get(index)[0], 0.0D);
            assertEquals(expected.get(index)[1], points.get(index)[1], 0.0D);
            assertEquals(expected.get(index)[2], points.get(index)[2], 0.0D);
        }
        assertSame(points, outline.points(1L, Axis.Z, built, null));
    }

    @Test
    void changingTheShapeRebuildsTheCachedOutline() {
        ApertureCells built = wall();
        AmbientOutlineGeometry outline = new AmbientOutlineGeometry();
        List<double[]> full = outline.points(1L, Axis.Z, built, null);
        ShapedAperture shaped = ShapedAperture.of(built, Frame.canonical(Face.S), ShapeDescriptor.parse("circle"));
        assertNotNull(shaped);
        List<double[]> circle = outline.points(1L, Axis.Z, built, shaped.outline());
        assertFalse(full.size() == circle.size() && full.equals(circle));
        assertSame(circle, outline.points(1L, Axis.Z, built, shaped.outline()));
    }

    private static ApertureCells wall() {
        ApertureCells cells = new ApertureCells();
        cells.setArea(new Box(0.0D, 6.999D, 64.0D, 70.999D, 0.0D, 0.999D));
        return cells;
    }
}
