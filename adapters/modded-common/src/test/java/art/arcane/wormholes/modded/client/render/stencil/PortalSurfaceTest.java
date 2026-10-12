package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.QuarterTurn;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Angles.Look;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.optics.shape.FitMode;
import art.arcane.optics.shape.Polygon;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertSame;

public class PortalSurfaceTest {
    @Test
    public void mirrorUsesTheExactPhysicalPlaneAndOpeningCenterForEveryFacingAndQuarterTurn() {
        for (Face facing : Face.values()) {
            for (int frameTurn = 0; frameTurn < 4; frameTurn++) {
                for (int mirrorTurn = 0; mirrorTurn < 4; mirrorTurn++) {
                    for (double offset : new double[] {-0.75D, 0.0D, 0.375D}) {
                        ApertureDescriptor geometry = mirror(facing, frameTurn, mirrorTurn, offset);
                        PortalSurface surface = PortalSurface.of(geometry);
                        OpticTransform transform = PortalSurface.mirrorTransform(geometry);
                        Vec3d center = mirrorCenter(facing, offset);
                        assertPoint(center, surface.planePoint());
                        assertPoint(center, transform.point(center));
                        Vec3d normal = new Vec3d(facing.x(), facing.y(), facing.z());
                        assertPoint(center.subtract(normal.multiply(3.25D)), transform.point(center.add(normal.multiply(3.25D))));
                        double translation = switch (facing.getAxis()) {
                            case X -> transform.translationX();
                            case Y -> transform.translationY();
                            case Z -> transform.translationZ();
                        };
                        assertEquals(2.0D * geometry.planeCoordinate(), translation, 0.0D);
                        assertEquals(AxisPermutation.mirror(geometry.frame(), QuarterTurn.of(mirrorTurn)), transform.permutation());
                        assertTrue(transform.reflects());
                        assertPoint(center, OpticTransform.decode(transform.encode()).point(center));
                        assertEquals(0.0D, surface.signedDistance(center), 0.0D);
                    }
                }
            }
        }
    }

    @Test
    public void mirrorRotatesTangentialCoordinatesAroundTheExactOpeningCenter() {
        for (Face facing : Face.values()) {
            for (int frameTurn = 0; frameTurn < 4; frameTurn++) {
                for (int mirrorTurn = 0; mirrorTurn < 4; mirrorTurn++) {
                    ApertureDescriptor geometry = mirror(facing, frameTurn, mirrorTurn, 0.0D);
                    Frame frame = geometry.frame();
                    Vec3d right = new Vec3d(frame.getRight().x(), frame.getRight().y(), frame.getRight().z());
                    Vec3d up = new Vec3d(frame.getUp().x(), frame.getUp().y(), frame.getUp().z());
                    Vec3d center = mirrorCenter(facing, 0.0D);
                    Vec3d rotated = switch (QuarterTurn.of(mirrorTurn).coherentFor(frame)) {
                        case DEGREES_0 -> right.multiply(1.25D).add(up.multiply(2.75D));
                        case DEGREES_90 -> right.multiply(2.75D).subtract(up.multiply(1.25D));
                        case DEGREES_180 -> right.multiply(-1.25D).subtract(up.multiply(2.75D));
                        case DEGREES_270 -> right.multiply(-2.75D).add(up.multiply(1.25D));
                    };
                    OpticTransform transform = PortalSurface.mirrorTransform(geometry);
                    Vec3d sample = center.add(right.multiply(1.25D)).add(up.multiply(2.75D));
                    assertPoint(center.add(rotated), transform.point(sample));
                    assertPoint(sample, transform.inverse().point(transform.point(sample)));
                    assertPoint(sample, transform.point(transform.inverse().point(sample)));
                    Look look = new Look(37.5F, -21.25F);
                    Look roundtrip = transform.inverse().look(transform.look(look));
                    assertEquals(look.yaw(), roundtrip.yaw(), 1.0E-4F);
                    assertEquals(look.pitch(), roundtrip.pitch(), 1.0E-4F);
                    if ((QuarterTurn.of(mirrorTurn).coherentFor(frame).getQuarterTurns() & 1) == 0) {
                        assertPoint(sample, transform.point(transform.point(sample)));
                    }
                }
            }
        }
    }

    @Test
    public void mirrorAtTheWirePlaneHasTheExactWireTranslation() {
        OpticTransform transform = PortalSurface.mirrorTransform(mirror(Face.S, 0, 0, 0.0D));
        assertEquals(41.0D, transform.translationZ(), 0.0D);
        assertPoint(new Vec3d(12.0D, 67.0D, 20.5D), transform.point(new Vec3d(12.0D, 67.0D, 20.5D)));
        assertPoint(new Vec3d(11.25D, 65.75D, 14.25D), transform.point(new Vec3d(11.25D, 65.75D, 26.75D)));
    }

    @Test
    public void nonlinearShapesAndSparseMasksKeepTheirGeometryAcrossMirrorRoundtrips() {
        for (Face facing : Face.values()) {
            for (int frameTurn = 0; frameTurn < 4; frameTurn++) {
                for (ShapeDescriptor shape : List.of(ShapeDescriptor.parse("circle"),
                    ShapeDescriptor.of(new Polygon(new double[] {-1.0D, -1.0D, 1.0D, -1.0D, -1.0D, 1.0D}), FitMode.STRETCH))) {
                    ApertureDescriptor geometry = mirror(facing, frameTurn, 0, 0.0D).withShape(shape);
                    PortalSurface surface = PortalSurface.of(geometry);
                    Similarity reflection = Similarity.of(PortalSurface.mirrorTransform(geometry), 1.0D);
                    PortalSurface reflected = surface.through(reflection);
                    PortalSurface roundtrip = reflected.through(reflection);
                    assertSame(geometry, reflected.geometry());
                    assertSame(surface.aperture(), reflected.aperture());
                    assertSame(surface.aperture(), roundtrip.aperture());
                    assertTrue(surface.sameOpening(roundtrip));
                    assertEquals(geometry.shape(), roundtrip.geometry().shape());
                    assertEquals(geometry.frameOrientation(), roundtrip.geometry().frameOrientation());
                    assertTrue(geometry.apertureOpen(0, 0));
                    assertFalse(geometry.apertureOpen(1, 0));
                    int contained = 0;
                    int excluded = 0;
                    for (double column : new double[] {0.25D, 1.5D, 2.25D, 3.75D}) {
                        for (double row : new double[] {0.25D, 2.5D, 5.75D}) {
                            Vec3d sample = surface.aperture().point(column, row);
                            boolean inside = surface.contains(sample);
                            assertEquals(inside, reflected.contains(reflection.point(sample)));
                            assertEquals(inside, roundtrip.contains(sample));
                            if (inside) {
                                contained++;
                            } else {
                                excluded++;
                            }
                        }
                    }
                    assertTrue(contained > 0);
                    assertTrue(excluded > 0);
                }
            }
        }
    }

    @Test
    public void distanceUsesThePlacedPlaneAcrossScaleRotationAndReflection() {
        for (int orientation = 0; orientation < 48; orientation++) {
            for (double scale : new double[]{0.25D, 1.0D, 4.0D}) {
                Similarity placement = Similarity.of(OpticTransform.of(AxisPermutation.ofIndex(orientation), 11, 70, -40), scale);
                PortalSurface surface = PortalSurface.of(geometry(0.0D)).through(placement);
                Vec3d center = placement.point(new Vec3d(0.5D, 1.5D, 0.5D));
                Vec3d normal = surface.servedNormal();
                assertEquals(0.0D, surface.distance(center), 1.0E-9D);
                assertEquals(2.4D, surface.distance(center.add(normal.multiply(2.4D))), 1.0E-9D);
                assertEquals(1.5D, surface.distance(center.subtract(normal.multiply(1.5D))), 1.0E-9D);
            }
        }
    }

    @Test
    public void distanceUsesAnOffsetPlaneBeyondTheBlockVolume() {
        PortalSurface surface = PortalSurface.of(geometry(0.75D));
        double plane = surface.geometry().planeCoordinate();
        assertEquals(0.0D, surface.distance(new Vec3d(0.5D, 1.5D, plane)), 1.0E-9D);
        assertEquals(0.4D, surface.distance(new Vec3d(0.5D, 1.5D, plane + 0.4D)), 1.0E-9D);
    }

    @Test
    public void distanceRetainsTangentialOpeningEdgeClearance() {
        ApertureDescriptor geometry = geometry(0.0D);
        PortalSurface surface = PortalSurface.of(geometry);
        Vec3d eye = new Vec3d(geometry.apertureArea().getXb() + 3.0D, 1.5D, geometry.planeCoordinate() + 4.0D);
        assertEquals(5.0D, surface.distance(eye), 1.0E-9D);
    }

    @Test
    public void openingMatchesTranslatedPhysicalGeometryAndKeepsDifferentPlacements() {
        PortalSurface original = PortalSurface.of(geometry(0.0D));
        Similarity shift = Similarity.of(OpticTransform.of(AxisPermutation.IDENTITY, 10.0D, 64.0D, -8.0D), 1.0D);
        PortalSurface moved = original.through(shift).through(Similarity.IDENTITY);
        PortalSurface physical = PortalSurface.of(geometry(10, 64, -8, 2, 3, 63L, ShapeDescriptor.FULL, 0.0D));
        assertTrue(moved.sameOpening(physical));
        assertTrue(physical.sameOpening(moved));
        assertFalse(original.sameOpening(moved));
        assertFalse(original.sameOpening(original.through(Similarity.IDENTITY)));
    }

    @Test
    public void openingMatchesScaledPhysicalExtentsWithoutComparingBlockThickness() {
        PortalSurface scaled = PortalSurface.of(geometry(0.0D)).through(Similarity.of(OpticTransform.IDENTITY, 2.0D))
            .through(Similarity.IDENTITY);
        PortalSurface physical = PortalSurface.of(geometry(0, 0, 0, 4, 6, (1L << 24) - 1L, ShapeDescriptor.FULL, -0.5D));
        assertTrue(scaled.sameOpening(physical));
        assertFalse(scaled.sameOpening(PortalSurface.of(geometry(0, 0, 0, 4, 6, (1L << 24) - 1L, ShapeDescriptor.FULL, 0.0D))));
        assertFalse(scaled.sameOpening(PortalSurface.of(geometry(0, 0, 0, 4, 5, (1L << 20) - 1L, ShapeDescriptor.FULL, -0.5D))));
    }

    @Test
    public void openingComparesPlacedMasksAndEffectiveShapeOrientation() {
        PortalSurface full = PortalSurface.of(geometry(0.0D));
        assertFalse(full.sameOpening(PortalSurface.of(geometry(0, 0, 0, 2, 3, 62L, ShapeDescriptor.FULL, 0.0D))));
        ShapeDescriptor circle = ShapeDescriptor.parse("circle");
        PortalSurface shaped = PortalSurface.of(geometry(0.0D).withShape(circle));
        Similarity shift = Similarity.of(OpticTransform.of(AxisPermutation.IDENTITY, 10.0D, 64.0D, -8.0D), 1.0D);
        PortalSurface moved = shaped.through(shift).through(Similarity.IDENTITY);
        PortalSurface physical = PortalSurface.of(geometry(10, 64, -8, 2, 3, 63L, circle, 0.0D));
        assertTrue(moved.sameOpening(physical));
        assertFalse(moved.sameOpening(PortalSurface.of(geometry(10, 64, -8, 2, 3, 63L, ShapeDescriptor.FULL, 0.0D))));
        Similarity reflection = Similarity.of(OpticTransform.of(AxisPermutation.of(Face.W, Face.U, Face.S), 2.0D, 0.0D, 0.0D), 1.0D);
        ShapeDescriptor triangle = ShapeDescriptor.of(new Polygon(new double[] {-1.0D, -1.0D, 1.0D, -1.0D, -1.0D, 1.0D}), FitMode.STRETCH);
        PortalSurface asymmetric = PortalSurface.of(geometry(0.0D).withShape(triangle));
        assertFalse(asymmetric.sameOpening(asymmetric.through(reflection).through(Similarity.IDENTITY)));
        PortalSurface masked = PortalSurface.of(geometry(0, 0, 0, 2, 3, 21L, ShapeDescriptor.FULL, 0.0D));
        PortalSurface reflected = masked.through(reflection).through(Similarity.IDENTITY);
        assertFalse(masked.sameOpening(reflected));
        assertTrue(reflected.sameOpening(PortalSurface.of(geometry(0, 0, 0, 2, 3, 42L, ShapeDescriptor.FULL, 0.0D))));
    }

    private static ApertureDescriptor mirror(Face facing, int frameTurns, int mirrorTurns, double offset) {
        return new ApertureDescriptor(10, 64, 20, facing.ordinal(), true, ApertureDescriptor.packQuarterTurns(frameTurns, mirrorTurns),
            true, 4, 6, new long[] {0x555555L}, ShapeDescriptor.FULL, 0, 0, 1, 64, 0, 0, 0, 0, 0, 0, 0, offset, 0, 11, List.of());
    }

    private static Vec3d mirrorCenter(Face facing, double offset) {
        return switch (facing) {
            case E -> new Vec3d(10.5D + offset, 67.0D, 22.0D);
            case W -> new Vec3d(10.5D - offset, 67.0D, 22.0D);
            case U -> new Vec3d(12.0D, 64.5D + offset, 23.0D);
            case D -> new Vec3d(12.0D, 64.5D - offset, 23.0D);
            case S -> new Vec3d(12.0D, 67.0D, 20.5D + offset);
            case N -> new Vec3d(12.0D, 67.0D, 20.5D - offset);
        };
    }

    private static void assertPoint(Vec3d expected, Vec3d actual) {
        assertEquals(expected.x(), actual.x(), 1.0E-9D);
        assertEquals(expected.y(), actual.y(), 1.0E-9D);
        assertEquals(expected.z(), actual.z(), 1.0E-9D);
    }

    private static ApertureDescriptor geometry(int x, int y, int z, int width, int height, long mask, ShapeDescriptor shape, double offset) {
        return new ApertureDescriptor(x, y, z, Face.N.ordinal(), true, 0, false, width, height, new long[] {mask}, shape,
            0, 0, 1, 64, 0, 0, 0, 0, 0, 0, 0, offset, 0, 11, List.of());
    }

    private static ApertureDescriptor geometry(double offset) {
        return new ApertureDescriptor(0, 0, 0, Face.N.ordinal(), true, 0, false, 2, 3, new long[]{63}, ShapeDescriptor.FULL,
            0, 0, 1, 64, 0, 0, 0, 0, 0, 0, 0, offset, 0, 11, List.of());
    }
}
