package art.arcane.optics.volume;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.aperture.Aperture;
import art.arcane.optics.math.Axis;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;

final class FrustumBoxCoverageTest {
    private static final List<Rectangle> RING = List.of(
        new Rectangle(0.0D, 1.0D, 0.0D, 3.0D),
        new Rectangle(2.0D, 3.0D, 0.0D, 3.0D),
        new Rectangle(1.0D, 2.0D, 0.0D, 1.0D),
        new Rectangle(1.0D, 2.0D, 2.0D, 3.0D));
    private static final List<Rectangle> TILES = List.of(
        new Rectangle(0.0D, 1.0D, 0.0D, 1.5D),
        new Rectangle(0.0D, 1.0D, 1.5D, 3.0D),
        new Rectangle(1.0D, 2.0D, 0.0D, 3.0D),
        new Rectangle(2.0D, 3.0D, 0.0D, 3.0D));

    @Test
    void apertureHolesDoNotHideBoxesWithCoveredCorners() {
        for (Face direction : Face.values()) {
            for (double offset : new double[] {-30_000_000.0D, 0.0D, 30_000_000.0D}) {
                BoxAperture aperture = new BoxAperture(direction, offset, RING);
                ViewVolume frustum = frustum(aperture, 16.0D, 16.0D);
                Box box = aperture.box(0.5D, 2.5D, 0.5D, 2.5D, 6.0D, 7.0D);
                assertCornersContained(frustum, box);
                assertFalse(frustum.contains(aperture.point(1.5D, 1.5D, 6.5D)));
                assertFalse(containsBox(frustum, box));
            }
        }
    }

    @Test
    void adjacentTilesCoverBoxesWithoutConstructingPointIndex() throws ReflectiveOperationException {
        Field indexField = ViewVolume.class.getDeclaredField("faceIndex");
        indexField.setAccessible(true);
        for (Face direction : Face.values()) {
            BoxAperture aperture = new BoxAperture(direction, 0.0D, TILES);
            ViewVolume frustum = frustum(aperture, 16.0D, 16.0D);
            assertTrue(containsBox(frustum, aperture.box(0.5D, 2.5D, 0.5D, 2.5D, 6.0D, 7.0D)));
            assertTrue(containsBox(frustum, aperture.box(1.5D, 1.5D, 1.5D, 1.5D, 5.0D, 7.0D)));
            assertNull(indexField.get(frustum));
        }
    }

    @Test
    void uncoveredSeamsAndRangeLimitsKeepBoxesVisible() {
        List<Rectangle> split = List.of(new Rectangle(0.0D, 1.499999D, 0.0D, 3.0D),
            new Rectangle(1.500001D, 3.0D, 0.0D, 3.0D));
        for (Face direction : Face.values()) {
            BoxAperture aperture = new BoxAperture(direction, 0.0D, split);
            assertFalse(containsBox(frustum(aperture, 16.0D, 16.0D),
                aperture.box(0.5D, 2.5D, 0.5D, 2.5D, 6.0D, 7.0D)));
            BoxAperture tiled = new BoxAperture(direction, 0.0D, TILES);
            ViewVolume limited = frustum(tiled, 2.0D, 1.0D);
            assertTrue(containsBox(limited, tiled.box(1.2D, 1.8D, 1.2D, 1.8D, 6.0D, 7.0D)));
            assertFalse(containsBox(limited, tiled.box(1.2D, 1.8D, 1.2D, 1.8D, 6.0D, 7.00001D)));
            assertFalse(containsBox(limited, tiled.box(1.2D, 1.8D, 1.2D, 1.8D, 4.99999D, 6.0D)));
        }
    }

    @Test
    void apertureToleranceDoesNotBridgeGapsInTightConeBounds() {
        List<Rectangle> split = List.of(new Rectangle(0.0D, 1.49999995D, 0.0D, 3.0D),
            new Rectangle(1.50000005D, 3.0D, 0.0D, 3.0D));
        BoxAperture aperture = new BoxAperture(Face.S, 0.0D, split);
        ViewVolume frustum = frustum(aperture, 16.0D, 16.0D);
        assertFalse(frustum.containsPrimitive(1.5D, 1.5D, 5.0D));
        assertFalse(containsBox(frustum, aperture.box(1.0D, 2.0D, 1.0D, 2.0D, 5.0D, 6.0D)));
    }

    @Test
    void tiledProofPreservesIndividualLateralClips() {
        List<Rectangle> split = List.of(new Rectangle(0.0D, 1.0D, 0.0D, 3.0D),
            new Rectangle(1.0D, 3.0D, 0.0D, 3.0D));
        BoxAperture aperture = new BoxAperture(Face.S, 0.0D, split);
        ViewVolume frustum = new ViewVolume(aperture.point(3.0D, 1.5D, 0.0D), aperture,
            new ViewVolume.Options(16.0D, 1.0D, 0.0D, 0.2D, 0.0D));
        Box box = aperture.box(-0.5D, 2.0D, 1.0D, 2.0D, 10.0D, 11.0D);
        assertFalse(frustum.containsPrimitive(-0.5D, 1.5D, 10.0D));
        assertFalse(containsBox(frustum, box));
    }

    @Test
    void boxProofCannotCrossTheApexPlane() {
        Frustum face = new Frustum(new Vec3d(1.5D, 1.5D, 5.0D),
            new Box(0.0D, 3.0D, 0.0D, 3.0D, 5.0D, 5.0D),
            Face.S, Axis.Z, 3.0D, 3.0D, 0.0D);
        assertTrue(face.containsPrimitive(1.5D, 1.5D, 4.0D));
        assertTrue(face.containsPrimitive(1.5D, 1.5D, 6.0D));
        assertFalse(face.containsBox(1.5D, 1.5D, 4.0D, 1.5D, 1.5D, 6.0D));
        assertFalse(Frustum.containsBoxUnion(new Frustum[] {face}, 1.5D, 1.5D, 4.0D, 1.5D, 1.5D, 6.0D));
    }

    @Test
    void provenBoxesContainEverySampledInteriorPoint() {
        Random random = new Random(627193L);
        int proven = 0;
        for (Face direction : Face.values()) {
            for (List<Rectangle> rectangles : List.of(RING, TILES)) {
                BoxAperture aperture = new BoxAperture(direction, -30_000_000.0D, rectangles);
                ViewVolume frustum = frustum(aperture, 4.0D, 3.0D);
                for (int index = 0; index < 200; index++) {
                    double first = random.nextDouble(-1.0D, 3.0D);
                    double second = random.nextDouble(-1.0D, 3.0D);
                    double depth = random.nextDouble(4.0D, 9.0D);
                    Box box = aperture.box(first, first + random.nextDouble(0.1D, 2.0D),
                        second, second + random.nextDouble(0.1D, 2.0D), depth, depth + random.nextDouble(0.1D, 2.0D));
                    if (!containsBox(frustum, box)) {
                        continue;
                    }
                    proven++;
                    for (int x = 0; x <= 4; x++) {
                        for (int y = 0; y <= 4; y++) {
                            for (int z = 0; z <= 4; z++) {
                                assertTrue(frustum.containsPrimitive(box.getXa() + (box.getXb() - box.getXa()) * x / 4.0D,
                                    box.getYa() + (box.getYb() - box.getYa()) * y / 4.0D,
                                    box.getZa() + (box.getZb() - box.getZa()) * z / 4.0D));
                            }
                        }
                    }
                }
            }
        }
        assertTrue(proven > 100);
    }

    private static ViewVolume frustum(BoxAperture aperture, double axial, double lateral) {
        return new ViewVolume(aperture.point(1.5D, 1.5D, 0.0D), aperture,
            new ViewVolume.Options(axial, lateral, 0.0D, 0.2D, 0.0D));
    }

    private static boolean containsBox(ViewVolume frustum, Box box) {
        return frustum.containsBox(box.getXa(), box.getYa(), box.getZa(), box.getXb(), box.getYb(), box.getZb());
    }

    private static void assertCornersContained(ViewVolume frustum, Box box) {
        for (double x : new double[] {box.getXa(), box.getXb()}) {
            for (double y : new double[] {box.getYa(), box.getYb()}) {
                for (double z : new double[] {box.getZa(), box.getZb()}) {
                    assertTrue(frustum.containsPrimitive(x, y, z));
                }
            }
        }
    }

    private record Rectangle(double firstMin, double firstMax, double secondMin, double secondMax) {
    }

    private record BoxAperture(Face direction, double offset, List<Rectangle> rectangles) implements Aperture {
        private Vec3d point(double first, double second, double normal) {
            double depth = offset + normal * (direction.x() + direction.y() + direction.z());
            return switch (direction.getAxis()) {
                case X -> new Vec3d(depth, offset + first, offset + second);
                case Y -> new Vec3d(offset + first, depth, offset + second);
                case Z -> new Vec3d(offset + first, offset + second, depth);
            };
        }

        private Box box(double firstMin, double firstMax, double secondMin, double secondMax,
                                  double normalMin, double normalMax) {
            Vec3d first = point(firstMin, secondMin, normalMin);
            Vec3d second = point(firstMax, secondMax, normalMax);
            return new Box(Math.min(first.x(), second.x()), Math.max(first.x(), second.x()),
                Math.min(first.y(), second.y()), Math.max(first.y(), second.y()),
                Math.min(first.z(), second.z()), Math.max(first.z(), second.z()));
        }

        @Override
        public Box getArea() {
            return box(0.0D, 3.0D, 0.0D, 3.0D, 5.0D, 5.0D);
        }

        @Override
        public Vec3d getApertureCenter() {
            return point(1.5D, 1.5D, 5.0D);
        }

        @Override
        public List<Box> getCachedApertureFaces(Face face) {
            return rectangles.stream().map(rectangle -> box(rectangle.firstMin(), rectangle.firstMax(),
                rectangle.secondMin(), rectangle.secondMax(), 5.0D, 5.0D)).toList();
        }
    }
}
