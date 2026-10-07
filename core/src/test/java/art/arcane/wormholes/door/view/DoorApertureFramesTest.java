package art.arcane.wormholes.door.view;

import art.arcane.wormholes.door.DoorHalf;
import art.arcane.wormholes.door.DoorOpenState;
import art.arcane.wormholes.door.DoorPlanePairing;
import art.arcane.wormholes.door.DoorwayPlane;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.ViewWindow;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.claim.BlockClaim;
import art.arcane.optics.aperture.AperturePolygon;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Box;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.wormholes.portal.ApertureKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DoorApertureFramesTest {
    private static final Face[] FACINGS = {Face.N, Face.E, Face.S, Face.W};

    @Test
    void trapdoorPairViewsMatchTraversalForEveryFacingHalfAndObserverSide() {
        for (Face sourceFacing : FACINGS) {
            for (Face targetFacing : FACINGS) {
                for (DoorHalf sourceHalf : DoorHalf.values()) {
                    for (DoorHalf targetHalf : DoorHalf.values()) {
                        DoorwayPlane source = DoorwayPlane.trapdoor(-4, 63, 12, sourceFacing, sourceHalf, DoorOpenState.OPEN);
                        DoorwayPlane target = DoorwayPlane.trapdoor(1928, 191, 8, targetFacing, targetHalf, DoorOpenState.OPEN);
                        for (boolean front : new boolean[]{true, false}) {
                            assertPair(source, target, front);
                        }
                    }
                }
            }
        }
    }

    @Test
    void clientGeometryRetainsEveryHorizontalFrameAndExactPlaneAcrossRenderProfiles() {
        ApertureCells cells = new ApertureCells();
        cells.restore(new Box(-4, -3.001, 63, 63.999, 12, 12.999), List.of(new Vec3d(-4, 63, 12)));
        for (Face facing : FACINGS) {
            for (DoorHalf half : DoorHalf.values()) {
                DoorwayPlane plane = DoorwayPlane.trapdoor(-4, 63, 12, facing, half, DoorOpenState.OPEN);
                Frame frame = DoorApertureFrames.of(plane);
                for (double padding : new double[]{0, 0.125, 0.5}) {
                    for (boolean front : new boolean[]{true, false}) {
                        ApertureDescriptor geometry = ApertureDescriptor.fromPortal(new ApertureDescriptor.Source(cells, frame,
                            front, false, 0, padding, padding, 1, 128, 4, 0, 0, 0, BlockClaim.LightingPolicy.LOCAL,
                            0, ApertureKind.DOOR, DoorwayPlane.planeOffset(frame.getNormal()), 0, 1, List.of())).orElseThrow();
                        assertEquals(frame, geometry.frame());
                        assertEquals(plane.planeY(), geometry.planeCoordinate(), 0.0D);
                        AperturePolygon aperture = AperturePolygon.from(geometry);
                        assertEquals(new Vec3d(-3.5, plane.planeY(), 12.5), aperture.point(0.5, 0.5));
                    }
                }
            }
        }
    }

    @Test
    void openingTheTrapdoorChangesNeitherTheHorizontalFrameNorTheAperturePlane() {
        for (Face facing : FACINGS) {
            for (DoorHalf half : DoorHalf.values()) {
                DoorwayPlane open = DoorwayPlane.trapdoor(3, 48, -5, facing, half, DoorOpenState.OPEN);
                DoorwayPlane closed = DoorwayPlane.trapdoor(3, 48, -5, facing, half, DoorOpenState.CLOSED);
                assertEquals(DoorApertureFrames.of(open), DoorApertureFrames.of(closed));
                assertEquals(open.center(), closed.center());
                assertEquals(half == DoorHalf.TOP ? Face.U : Face.D, DoorApertureFrames.of(open).getNormal());
                assertEquals(half == DoorHalf.TOP ? facing.reverse() : facing, DoorApertureFrames.of(open).getUp());
            }
        }
    }

    private static void assertPair(DoorwayPlane source, DoorwayPlane target, boolean front) {
        Vec3d a = source.center();
        Vec3d b = target.center();
        Frame local = DoorApertureFrames.of(source);
        Frame remote = DoorApertureFrames.destinationFrame(source, target);
        OpticTransform transform = ViewWindow.between(a, local, b, remote, front, 128).transform().normalized();
        assertEquals(Face.U, transform.permutation().y());
        Vec3d center = transform.inverse().point(new Vec3d(a.x(), a.y(), a.z()));
        assertPoint(b, center);
        for (double vertical : new double[]{-2, 2}) {
            Vec3d eye = transform.inverse().point(new Vec3d(a.x() + source.facing().x() * 0.25, a.y() + vertical, a.z() + source.facing().z() * 0.25));
            assertPoint(new Vec3d(b.x() + target.facing().x() * 0.25, b.y() + vertical,
                b.z() + target.facing().z() * 0.25), eye);
        }
        Vec3d crossingPoint = new Vec3d(a.x() + source.facing().x() * 0.2 - source.facing().z() * 0.3,
            a.y(), a.z() + source.facing().z() * 0.2 + source.facing().x() * 0.3);
        PlaneCrossing crossing = source.crossing(new Vec3d(crossingPoint.x(), a.y() + 1, crossingPoint.z()),
            new Vec3d(crossingPoint.x(), a.y() - 1, crossingPoint.z())).orElseThrow();
        assertPoint(DoorPlanePairing.mapAperturePoint(source, target, crossing),
            transform.inverse().point(new Vec3d(crossingPoint.x(), crossingPoint.y(), crossingPoint.z())));
    }

    private static void assertPoint(Vec3d expected, Vec3d actual) {
        assertEquals(expected.x(), actual.x(), 1.0E-10);
        assertEquals(expected.y(), actual.y(), 1.0E-10);
        assertEquals(expected.z(), actual.z(), 1.0E-10);
    }
}
