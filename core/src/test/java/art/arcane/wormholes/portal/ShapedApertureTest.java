package art.arcane.wormholes.portal;

import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.FitMode;
import art.arcane.optics.shape.PlaneShape;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.optics.shape.ShapeRaster;
import art.arcane.optics.shape.Shapes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ShapedApertureTest {
    private static final ShapeDescriptor CIRCLE = ShapeDescriptor.parse("circle");

    @Test
    void circleKeepsExactlyTheRasterInsideCellsOfTheBuiltRectangle() {
        ApertureCells built = wall();
        ShapedAperture shaped = ShapedAperture.of(built, Frame.canonical(Face.S), CIRCLE);
        assertNotNull(shaped);
        int expected = ShapeRaster.of(PlaneShape.fit(Shapes.circle(1.0D), FitMode.CONTAIN, 7, 7), 4, 0.5D).insideCount();
        assertEquals(expected, shaped.cells().size());
        assertTrue(expected < 49);
        for (Vec3d cell : shaped.cells()) {
            assertTrue(built.containsBlock(cell.blockX(), cell.blockY(), cell.blockZ()));
        }
        assertEquals(CIRCLE, shaped.shape());
        assertEquals(CIRCLE, shaped.outline().shape());
        assertEquals(7, shaped.outline().apertureWidth());
        assertEquals(7, shaped.outline().apertureHeight());
        assertEquals(49, shaped.outline().openCellCount());
    }

    @Test
    void containmentIsExactInsideTheOpenCells() {
        ShapedAperture shaped = ShapedAperture.of(wall(), Frame.canonical(Face.S), CIRCLE);
        assertNotNull(shaped);
        assertTrue(shaped.contains(3.5D, 67.5D, 0.5D));
        assertFalse(shaped.contains(0.2D, 70.8D, 0.5D));
        int analyticMisses = 0;
        for (Vec3d cell : shaped.cells()) {
            for (int sample = 0; sample < 16; sample++) {
                double column = cell.blockX() + ((sample & 3) + 0.5D) / 4.0D;
                double row = cell.blockY() - 64 + ((sample >> 2) + 0.5D) / 4.0D;
                double u = (column - 3.5D) / 3.5D;
                double v = (row - 3.5D) / 3.5D;
                boolean inside = u * u + v * v <= 1.0D;
                analyticMisses += inside ? 0 : 1;
                assertEquals(inside, shaped.contains(column, row + 64.0D, 0.5D), "column " + column + " row " + row);
            }
        }
        assertTrue(analyticMisses > 0);
    }

    @Test
    void wallCrossingsAreJudgedAtTheTravellersEyeWhileFloorCrossingsUseTheHitPoint() {
        ShapedAperture shaped = ShapedAperture.of(wall(), Frame.canonical(Face.S), CIRCLE);
        assertNotNull(shaped);
        assertFalse(shaped.contains(3.3D, 64.0D, 0.5D));
        assertTrue(shaped.admits(3.3D, 64.0D, 0.5D, 1.62D));
        assertFalse(shaped.admits(0.3D, 64.0D, 0.5D, 1.62D));
        ApertureCells floor = new ApertureCells();
        floor.setArea(new Box(0.0D, 6.999D, 64.0D, 64.999D, 0.0D, 6.999D));
        ShapedAperture flat = ShapedAperture.of(floor, Frame.canonical(Face.U), CIRCLE);
        assertNotNull(flat);
        assertTrue(flat.admits(3.5D, 64.5D, 3.5D, 1.62D));
        assertFalse(flat.admits(0.2D, 64.5D, 0.2D, 1.62D));
    }

    @Test
    void aShapeThatCoversNoCellIsRefused() {
        assertNull(ShapedAperture.of(wall(), Frame.canonical(Face.S), ShapeDescriptor.parse("circle(radius=0.05)")));
    }

    @Test
    void asymmetricShapesFollowTheFrameUp() {
        ShapeDescriptor heart = ShapeDescriptor.parse("heart");
        Frame upright = Frame.canonical(Face.S);
        Frame inverted = upright.rotateClockwise().rotateClockwise();
        ShapedAperture lobesUp = ShapedAperture.of(wall(), upright, heart);
        ShapedAperture lobesDown = ShapedAperture.of(wall(), inverted, heart);
        assertNotNull(lobesUp);
        assertNotNull(lobesDown);
        assertTrue(rowCount(lobesUp, 69) > rowCount(lobesUp, 65));
        assertEquals(rowCount(lobesUp, 69), rowCount(lobesDown, 65));
        assertEquals(rowCount(lobesUp, 65), rowCount(lobesDown, 69));
    }

    private static int rowCount(ShapedAperture shaped, int y) {
        int count = 0;
        for (Vec3d cell : shaped.cells()) {
            count += cell.blockY() == y ? 1 : 0;
        }
        return count;
    }

    private static ApertureCells wall() {
        ApertureCells cells = new ApertureCells();
        cells.setArea(new Box(0.0D, 6.999D, 64.0D, 70.999D, 0.0D, 0.999D));
        return cells;
    }
}
