package art.arcane.wormholes.door.view;

import art.arcane.wormholes.door.DoorHalf;
import art.arcane.wormholes.door.DoorOpenState;
import art.arcane.wormholes.door.DoorPlanePairing;
import art.arcane.wormholes.door.DoorVec3;
import art.arcane.wormholes.door.DoorwayCrossing;
import art.arcane.wormholes.door.DoorwayPlane;
import art.arcane.optics.math.Vec3;
import art.arcane.optics.stream.ProjectionEnvironment;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.claim.ProjectedBlockClaim;
import art.arcane.optics.aperture.AperturePolygon;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.client.ClientViewEntityTransform;
import art.arcane.optics.client.ClientViewEnvironmentTransform;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Box;
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
        cells.restore(new Box(-4, -3.001, 63, 63.999, 12, 12.999), List.of(new Vec3(-4, 63, 12)));
        for (Face facing : FACINGS) {
            for (DoorHalf half : DoorHalf.values()) {
                DoorwayPlane plane = DoorwayPlane.trapdoor(-4, 63, 12, facing, half, DoorOpenState.OPEN);
                Frame frame = DoorApertureFrames.of(plane);
                for (double padding : new double[]{0, 0.125, 0.5}) {
                    for (boolean front : new boolean[]{true, false}) {
                        ApertureDescriptor geometry = ApertureDescriptor.fromPortal(new ApertureDescriptor.Source(cells, frame,
                            front, false, 0, padding, padding, 1, 128, 4, 0, 0, 0, ProjectedBlockClaim.LightingPolicy.LOCAL,
                            0, ApertureDescriptor.KIND_DOOR, DoorwayPlane.planeOffset(frame.getNormal()), 0, 1, List.of())).orElseThrow();
                        assertEquals(frame, geometry.frame());
                        assertEquals(plane.planeY(), geometry.planeCoordinate(), 0.0D);
                        AperturePolygon aperture = AperturePolygon.from(geometry);
                        assertEquals(new AperturePolygon.Point(-3.5, plane.planeY(), 12.5), aperture.point(0.5, 0.5));
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
        DoorVec3 a = source.center();
        DoorVec3 b = target.center();
        Frame local = DoorApertureFrames.of(source);
        Frame remote = DoorApertureFrames.destinationFrame(source, target);
        ProjectionEnvironment.Transform transform = ClientViewEnvironmentTransform.of(new ClientViewEntityTransform.EntityFrame(
            a.x(), a.y(), a.z(), local, b.x(), b.y(), b.z(), remote, false, 0, front, 128));
        assertEquals(Face.U, transform.yAxis());
        Vec3 center = transform.destinationPoint(a.x(), a.y(), a.z());
        assertPoint(b, center);
        for (double vertical : new double[]{-2, 2}) {
            Vec3 eye = transform.destinationPoint(a.x() + source.facing().x() * 0.25,
                a.y() + vertical, a.z() + source.facing().z() * 0.25);
            assertPoint(new DoorVec3(b.x() + target.facing().x() * 0.25, b.y() + vertical,
                b.z() + target.facing().z() * 0.25), eye);
        }
        DoorVec3 crossingPoint = new DoorVec3(a.x() + source.facing().x() * 0.2 - source.facing().z() * 0.3,
            a.y(), a.z() + source.facing().z() * 0.2 + source.facing().x() * 0.3);
        DoorwayCrossing crossing = source.crossing(new DoorVec3(crossingPoint.x(), a.y() + 1, crossingPoint.z()),
            new DoorVec3(crossingPoint.x(), a.y() - 1, crossingPoint.z())).orElseThrow();
        assertPoint(DoorPlanePairing.mapAperturePoint(source, target, crossing),
            transform.destinationPoint(crossingPoint.x(), crossingPoint.y(), crossingPoint.z()));
    }

    private static void assertPoint(DoorVec3 expected, Vec3 actual) {
        assertEquals(expected.x(), actual.x(), 1.0E-10);
        assertEquals(expected.y(), actual.y(), 1.0E-10);
        assertEquals(expected.z(), actual.z(), 1.0E-10);
    }
}
