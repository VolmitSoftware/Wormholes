package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.portal.PortalAperture;
import art.arcane.wormholes.util.Axis;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

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
        for (Direction direction : Direction.values()) {
            for (double offset : new double[] {-30_000_000.0D, 0.0D, 30_000_000.0D}) {
                Aperture aperture = new Aperture(direction, offset, RING);
                Frustum4D frustum = frustum(aperture, 16.0D, 16.0D);
                AxisAlignedBB box = aperture.box(0.5D, 2.5D, 0.5D, 2.5D, 6.0D, 7.0D);
                assertCornersContained(frustum, box);
                assertFalse(frustum.contains(aperture.point(1.5D, 1.5D, 6.5D)));
                assertFalse(containsBox(frustum, box));
            }
        }
    }

    @Test
    void adjacentTilesCoverBoxesWithoutConstructingPointIndex() throws ReflectiveOperationException {
        Field indexField = Frustum4D.class.getDeclaredField("faceIndex");
        indexField.setAccessible(true);
        for (Direction direction : Direction.values()) {
            Aperture aperture = new Aperture(direction, 0.0D, TILES);
            Frustum4D frustum = frustum(aperture, 16.0D, 16.0D);
            assertTrue(containsBox(frustum, aperture.box(0.5D, 2.5D, 0.5D, 2.5D, 6.0D, 7.0D)));
            assertTrue(containsBox(frustum, aperture.box(1.5D, 1.5D, 1.5D, 1.5D, 5.0D, 7.0D)));
            assertNull(indexField.get(frustum));
        }
    }

    @Test
    void uncoveredSeamsAndRangeLimitsKeepBoxesVisible() {
        List<Rectangle> split = List.of(new Rectangle(0.0D, 1.499999D, 0.0D, 3.0D),
            new Rectangle(1.500001D, 3.0D, 0.0D, 3.0D));
        for (Direction direction : Direction.values()) {
            Aperture aperture = new Aperture(direction, 0.0D, split);
            assertFalse(containsBox(frustum(aperture, 16.0D, 16.0D),
                aperture.box(0.5D, 2.5D, 0.5D, 2.5D, 6.0D, 7.0D)));
            Aperture tiled = new Aperture(direction, 0.0D, TILES);
            Frustum4D limited = frustum(tiled, 2.0D, 1.0D);
            assertTrue(containsBox(limited, tiled.box(1.2D, 1.8D, 1.2D, 1.8D, 6.0D, 7.0D)));
            assertFalse(containsBox(limited, tiled.box(1.2D, 1.8D, 1.2D, 1.8D, 6.0D, 7.00001D)));
            assertFalse(containsBox(limited, tiled.box(1.2D, 1.8D, 1.2D, 1.8D, 4.99999D, 6.0D)));
        }
    }

    @Test
    void apertureToleranceDoesNotBridgeGapsInTightConeBounds() {
        List<Rectangle> split = List.of(new Rectangle(0.0D, 1.49999995D, 0.0D, 3.0D),
            new Rectangle(1.50000005D, 3.0D, 0.0D, 3.0D));
        Aperture aperture = new Aperture(Direction.S, 0.0D, split);
        Frustum4D frustum = frustum(aperture, 16.0D, 16.0D);
        assertFalse(frustum.containsPrimitive(1.5D, 1.5D, 5.0D));
        assertFalse(containsBox(frustum, aperture.box(1.0D, 2.0D, 1.0D, 2.0D, 5.0D, 6.0D)));
    }

    @Test
    void tiledProofPreservesIndividualLateralClips() {
        List<Rectangle> split = List.of(new Rectangle(0.0D, 1.0D, 0.0D, 3.0D),
            new Rectangle(1.0D, 3.0D, 0.0D, 3.0D));
        Aperture aperture = new Aperture(Direction.S, 0.0D, split);
        Frustum4D frustum = new Frustum4D(aperture.point(3.0D, 1.5D, 0.0D), aperture,
            new Frustum4D.Options(16.0D, 1.0D, 0.0D, 0.2D, 0.0D));
        AxisAlignedBB box = aperture.box(-0.5D, 2.0D, 1.0D, 2.0D, 10.0D, 11.0D);
        assertFalse(frustum.containsPrimitive(-0.5D, 1.5D, 10.0D));
        assertFalse(containsBox(frustum, box));
    }

    @Test
    void boxProofCannotCrossTheApexPlane() {
        Frustum face = new Frustum(new GeometryVector(1.5D, 1.5D, 5.0D),
            new AxisAlignedBB(0.0D, 3.0D, 0.0D, 3.0D, 5.0D, 5.0D),
            Direction.S, Axis.Z, 3.0D, 3.0D, 0.0D);
        assertTrue(face.containsPrimitive(1.5D, 1.5D, 4.0D));
        assertTrue(face.containsPrimitive(1.5D, 1.5D, 6.0D));
        assertFalse(face.containsBox(1.5D, 1.5D, 4.0D, 1.5D, 1.5D, 6.0D));
        assertFalse(Frustum.containsBoxUnion(new Frustum[] {face}, 1.5D, 1.5D, 4.0D, 1.5D, 1.5D, 6.0D));
    }

    @Test
    void provenBoxesContainEverySampledInteriorPoint() {
        Random random = new Random(627193L);
        int proven = 0;
        for (Direction direction : Direction.values()) {
            for (List<Rectangle> rectangles : List.of(RING, TILES)) {
                Aperture aperture = new Aperture(direction, -30_000_000.0D, rectangles);
                Frustum4D frustum = frustum(aperture, 4.0D, 3.0D);
                for (int index = 0; index < 200; index++) {
                    double first = random.nextDouble(-1.0D, 3.0D);
                    double second = random.nextDouble(-1.0D, 3.0D);
                    double depth = random.nextDouble(4.0D, 9.0D);
                    AxisAlignedBB box = aperture.box(first, first + random.nextDouble(0.1D, 2.0D),
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

    private static Frustum4D frustum(Aperture aperture, double axial, double lateral) {
        return new Frustum4D(aperture.point(1.5D, 1.5D, 0.0D), aperture,
            new Frustum4D.Options(axial, lateral, 0.0D, 0.2D, 0.0D));
    }

    private static boolean containsBox(Frustum4D frustum, AxisAlignedBB box) {
        return frustum.containsBox(box.getXa(), box.getYa(), box.getZa(), box.getXb(), box.getYb(), box.getZb());
    }

    private static void assertCornersContained(Frustum4D frustum, AxisAlignedBB box) {
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

    private record Aperture(Direction direction, double offset, List<Rectangle> rectangles) implements PortalAperture {
        private GeometryVector point(double first, double second, double normal) {
            double depth = offset + normal * (direction.x() + direction.y() + direction.z());
            return switch (direction.getAxis()) {
                case X -> new GeometryVector(depth, offset + first, offset + second);
                case Y -> new GeometryVector(offset + first, depth, offset + second);
                case Z -> new GeometryVector(offset + first, offset + second, depth);
            };
        }

        private AxisAlignedBB box(double firstMin, double firstMax, double secondMin, double secondMax,
                                  double normalMin, double normalMax) {
            GeometryVector first = point(firstMin, secondMin, normalMin);
            GeometryVector second = point(firstMax, secondMax, normalMax);
            return new AxisAlignedBB(Math.min(first.x(), second.x()), Math.max(first.x(), second.x()),
                Math.min(first.y(), second.y()), Math.max(first.y(), second.y()),
                Math.min(first.z(), second.z()), Math.max(first.z(), second.z()));
        }

        @Override
        public AxisAlignedBB getArea() {
            return box(0.0D, 3.0D, 0.0D, 3.0D, 5.0D, 5.0D);
        }

        @Override
        public GeometryVector getApertureCenter() {
            return point(1.5D, 1.5D, 5.0D);
        }

        @Override
        public List<AxisAlignedBB> getCachedApertureFaces(Direction face) {
            return rectangles.stream().map(rectangle -> box(rectangle.firstMin(), rectangle.firstMax(),
                rectangle.secondMin(), rectangle.secondMax(), 5.0D, 5.0D)).toList();
        }
    }
}
