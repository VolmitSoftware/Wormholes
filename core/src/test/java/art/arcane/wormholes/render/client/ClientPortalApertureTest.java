package art.arcane.wormholes.render.client;

import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.door.DoorHalf;
import art.arcane.wormholes.door.DoorOpenState;
import art.arcane.wormholes.door.DoorwayPlane;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.aperture.AperturePolygon;
import art.arcane.wormholes.portal.ApertureKind;

class ClientPortalApertureTest {
    @Test
    void dimensionalDoorAperturesUseThePhysicalThresholdRatherThanTheBlockCenter() {
        for (Face normal : Face.values()) {
            for (boolean front : new boolean[]{true, false}) {
                DoorwayPlane doorway = normal.isVertical()
                    ? DoorwayPlane.trapdoor(-4, 16, -8, Face.E, normal == Face.U ? DoorHalf.TOP : DoorHalf.BOTTOM,
                        DoorOpenState.OPEN)
                    : new DoorwayPlane(-4, 16, -8, normal);
                int height = normal.isVertical() ? 1 : 2;
                ApertureDescriptor geometry = new ApertureDescriptor(-4, 16, -8, normal.ordinal(), front, 0, false, 1, height,
                    new long[]{height == 1 ? 1 : 3}, 0, 0, 1, 64, 0, 0, 0, 0, 0, 0, ApertureKind.DOOR, DoorwayPlane.planeOffset(normal), 0, 0, List.of());
                AperturePolygon aperture = AperturePolygon.from(geometry);
                Vec3d center = doorway.center();
                Vec3d physicalCenter = new Vec3d(center.x(), center.y(), center.z());
                assertEquals(0, aperture.plane().signedDistance(physicalCenter), 1.0E-12);
                assertEquals(0, geometry.signedDistance(center.x(), center.y(), center.z()), 1.0E-12);
                assertEquals(physicalCenter, aperture.point(0.5D, height * 0.5D));
                int sign = front ? 1 : -1;
                Vec3d eye = new Vec3d(center.x() + normal.x() * sign * 0.01,
                    center.y() + normal.y() * sign * 0.01, center.z() + normal.z() * sign * 0.01);
                assertTrue(aperture.servesEye(eye));
                assertEquals(0.01 * sign, geometry.signedDistance(eye.x(), eye.y(), eye.z()), 1.0E-12);
                for (Vec3d vertex : aperture.vertices(aperture.rectangles().getFirst())) {
                    assertEquals(0, aperture.plane().signedDistance(vertex), 1.0E-12);
                }
            }
        }
    }

    @Test
    void fullOpeningMergesIntoOneExactRectangle() {
        AperturePolygon aperture = aperture("111", "111");
        assertEquals(List.of(new AperturePolygon.Rectangle(0, 0, 3, 2)), aperture.rectangles());
        assertEquals(new Vec3d(-4, 16, -7.5), aperture.point(0, 0));
        assertEquals(new Vec3d(-1, 18, -7.5), aperture.point(3, 2));
    }

    @Test
    void mergedRectanglesCoverEveryOpenCellExactlyOnceWithoutFillingHoles() {
        String[] rows = {"11111", "10001", "10101", "10001", "11111", "11000"};
        AperturePolygon aperture = aperture(rows);
        for (int row = 0; row < rows.length; row++) {
            for (int column = 0; column < rows[row].length(); column++) {
                int coverings = 0;
                for (AperturePolygon.Rectangle rectangle : aperture.rectangles()) {
                    if (column >= rectangle.minColumn() && column < rectangle.maxColumn()
                        && row >= rectangle.minRow() && row < rectangle.maxRow()) {
                        coverings++;
                    }
                }
                assertEquals(rows[row].charAt(column) == '1' ? 1 : 0, coverings);
                assertEquals(coverings == 1, aperture.contains(column + 0.5, row + 0.5));
            }
        }
        assertFalse(aperture.contains(-0.001, 0.5));
        assertFalse(aperture.contains(5, 0.5));
        assertFalse(aperture.contains(Double.NaN, 0.5));
    }

    @Test
    void everyFacingAndQuarterTurnUsesCanonicalWorldCellCoordinates() {
        for (Face facing : Face.values()) {
            for (int turns = 0; turns < 4; turns++) {
                for (boolean front : new boolean[]{true, false}) {
                    AperturePolygon aperture = AperturePolygon.from(geometry(facing, front, turns, "11", "11"));
                    Vec3d corner = switch (facing) {
                        case N, S -> new Vec3d(-2, 18, -7.5);
                        case E, W -> new Vec3d(-3.5, 18, -6);
                        case U, D -> new Vec3d(-2, 16.5, -6);
                    };
                    assertEquals(corner, aperture.point(2, 2));
                    assertEquals(0, aperture.plane().signedDistance(corner), 0);
                    int sign = front ? 1 : -1;
                    Vec3d eye = new Vec3d(corner.x() + facing.x() * sign,
                        corner.y() + facing.y() * sign, corner.z() + facing.z() * sign);
                    assertTrue(aperture.servesEye(eye));
                    assertFalse(aperture.servesEye(corner));
                    assertFalse(aperture.servesEye(new Vec3d(corner.x() - facing.x() * sign,
                        corner.y() - facing.y() * sign, corner.z() - facing.z() * sign)));
                    List<Vec3d> vertices = aperture.vertices(aperture.rectangles().getFirst());
                    Vec3d a = vertices.get(0);
                    Vec3d b = vertices.get(1);
                    Vec3d c = vertices.get(2);
                    double normalX = (b.y() - a.y()) * (c.z() - a.z()) - (b.z() - a.z()) * (c.y() - a.y());
                    double normalY = (b.z() - a.z()) * (c.x() - a.x()) - (b.x() - a.x()) * (c.z() - a.z());
                    double normalZ = (b.x() - a.x()) * (c.y() - a.y()) - (b.y() - a.y()) * (c.x() - a.x());
                    assertTrue((normalX * facing.x() + normalY * facing.y() + normalZ * facing.z()) * sign > 0);
                }
            }
        }
    }

    @Test
    void apertureCloserThanNearPlaneStillCoversItsProjectedFootprint() {
        AperturePolygon aperture = aperture("11", "11");
        for (AperturePolygon.ClipDepth depth : AperturePolygon.ClipDepth.values()) {
            List<AperturePolygon.ClipVertex> polygon = aperture.project(aperture.rectangles().getFirst(), perspective(-7.4, depth), depth);
            assertEquals(4, polygon.size());
            assertEquals(4, projectedArea(polygon), 1.0E-10);
            for (AperturePolygon.ClipVertex vertex : polygon) {
                assertEquals(vertex.w(), vertex.z(), 1.0E-10);
                assertTrue(vertex.w() > 0);
            }
        }
    }

    @Test
    void normalDistancePreservesDepthAndFarOrBehindCameraPolygonsDisappear() {
        AperturePolygon aperture = aperture("11", "11");
        for (AperturePolygon.ClipDepth depth : AperturePolygon.ClipDepth.values()) {
            AperturePolygon.Rectangle rectangle = aperture.rectangles().getFirst();
            List<AperturePolygon.ClipVertex> polygon = aperture.project(rectangle, perspective(-5.5, depth), depth);
            assertEquals(4, polygon.size());
            assertEquals(1, projectedArea(polygon), 1.0E-10);
            assertEquals(depth == AperturePolygon.ClipDepth.ZERO_TO_ONE ? 8.0 / 9.0 : -2.0 / 9.0, polygon.getFirst().z(), 1.0E-10);
            assertTrue(aperture.project(rectangle, perspective(4, depth), depth).isEmpty());
            assertTrue(aperture.project(rectangle, perspective(-7.6, depth), depth).isEmpty());
            assertTrue(aperture.project(rectangle, perspective(-7.5, depth), depth).isEmpty());
        }
    }

    @Test
    void cameraPlaneCrossingClipsToFiniteScreenCoordinates() {
        AperturePolygon aperture = aperture("11", "11");
        double[] matrix = {0.25, 0, 0, 1, 0, 0.25, 0, 0, 0, 0, 0, 0, 1, -4.25, 0, 3.5};
        List<AperturePolygon.ClipVertex> polygon = aperture.project(aperture.rectangles().getFirst(), matrix,
            AperturePolygon.ClipDepth.ZERO_TO_ONE);
        assertTrue(polygon.size() >= 3);
        assertTrue(projectedArea(polygon) > 0);
        for (AperturePolygon.ClipVertex vertex : polygon) {
            assertTrue(vertex.w() > 0);
            assertTrue(Math.abs(vertex.x() / vertex.w()) <= 1.0 + 1.0E-10);
            assertTrue(Math.abs(vertex.y() / vertex.w()) <= 1.0 + 1.0E-10);
            assertTrue(Double.isFinite(vertex.z()));
        }
    }

    @Test
    void screenClippingPreservesIrregularOpeningHoles() {
        AperturePolygon aperture = aperture("111", "101", "111");
        double[] matrix = {0.5, 0, 0, 0, 0, 0.5, 0, 0, 0, 0, 0, 0, 1.25, -8.75, 0.5, 1};
        double area = 0;
        for (AperturePolygon.Rectangle rectangle : aperture.rectangles()) {
            area += projectedArea(aperture.project(rectangle, matrix, AperturePolygon.ClipDepth.ZERO_TO_ONE));
        }
        assertEquals(2.0, area, 1.0E-10);
    }

    private static double projectedArea(List<AperturePolygon.ClipVertex> polygon) {
        double sum = 0;
        for (int index = 0; index < polygon.size(); index++) {
            AperturePolygon.ClipVertex a = polygon.get(index);
            AperturePolygon.ClipVertex b = polygon.get((index + 1) % polygon.size());
            sum += (a.x() / a.w()) * (b.y() / b.w()) - (b.x() / b.w()) * (a.y() / a.w());
        }
        return Math.abs(sum) * 0.5;
    }

    private static double[] perspective(double eyeZ, AperturePolygon.ClipDepth depth) {
        double z = depth == AperturePolygon.ClipDepth.ZERO_TO_ONE ? 1.0 / 9.0 : 11.0 / 9.0;
        double offset = depth == AperturePolygon.ClipDepth.ZERO_TO_ONE ? 10.0 / 9.0 : 20.0 / 9.0;
        return new double[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, z, -1, 3, -17, offset - z * eyeZ, eyeZ};
    }

    private static AperturePolygon aperture(String... rows) {
        return AperturePolygon.from(geometry(Face.S, true, 0, rows));
    }

    private static ApertureDescriptor geometry(Face facing, boolean front, int turns, String... rows) {
        boolean[] open = new boolean[rows.length * rows[0].length()];
        for (int row = 0; row < rows.length; row++) {
            for (int column = 0; column < rows[row].length(); column++) {
                open[row * rows[0].length() + column] = rows[row].charAt(column) == '1';
            }
        }
        return new ApertureDescriptor(-4, 16, -8, facing.ordinal(), front, turns, false, rows[0].length(), rows.length,
            ApertureDescriptor.apertureMask(rows[0].length(), rows.length, open), 0, 0, 1, 64, 0, 0, 0, 0, 0, 0, 0, 0.0D, 0, 0, List.of());
    }
}
