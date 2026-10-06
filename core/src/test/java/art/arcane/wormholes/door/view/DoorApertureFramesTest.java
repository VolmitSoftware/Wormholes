package art.arcane.wormholes.door.view;

import art.arcane.wormholes.door.DoorHalf;
import art.arcane.wormholes.door.DoorOpenState;
import art.arcane.wormholes.door.DoorPlanePairing;
import art.arcane.wormholes.door.DoorVec3;
import art.arcane.wormholes.door.DoorwayCrossing;
import art.arcane.wormholes.door.DoorwayPlane;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalGeometry;
import art.arcane.wormholes.render.ProjectedBlockClaim;
import art.arcane.wormholes.render.client.ClientPortalAperture;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.render.client.ClientViewEntityTransform;
import art.arcane.wormholes.render.client.ClientViewEnvironmentTransform;
import art.arcane.wormholes.util.Direction;
import art.arcane.wormholes.util.AxisAlignedBB;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DoorApertureFramesTest {
    private static final Direction[] FACINGS = {Direction.N, Direction.E, Direction.S, Direction.W};

    @Test
    void trapdoorPairViewsMatchTraversalForEveryFacingHalfAndObserverSide() {
        for (Direction sourceFacing : FACINGS) {
            for (Direction targetFacing : FACINGS) {
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
        PortalGeometry cells = new PortalGeometry();
        cells.restore(new AxisAlignedBB(-4, -3.001, 63, 63.999, 12, 12.999), List.of(new GeometryVector(-4, 63, 12)));
        for (Direction facing : FACINGS) {
            for (DoorHalf half : DoorHalf.values()) {
                DoorwayPlane plane = DoorwayPlane.trapdoor(-4, 63, 12, facing, half, DoorOpenState.OPEN);
                PortalFrame frame = DoorApertureFrames.of(plane);
                for (double padding : new double[]{0, 0.125, 0.5}) {
                    for (boolean front : new boolean[]{true, false}) {
                        ClientPortalGeometry geometry = ClientPortalGeometry.fromPortal(new ClientPortalGeometry.Source(cells, frame,
                            front, false, 0, padding, padding, 1, 128, 4, 0, 0, 0, ProjectedBlockClaim.LightingPolicy.LOCAL,
                            0, ClientPortalGeometry.KIND_DOOR, DoorwayPlane.planeOffset(frame.getNormal()), 0, 1, List.of())).orElseThrow();
                        assertEquals(frame, geometry.frame());
                        assertEquals(plane.planeY(), geometry.planeCoordinate(), 0.0D);
                        ClientPortalAperture aperture = ClientPortalAperture.from(geometry);
                        assertEquals(new ClientPortalAperture.Point(-3.5, plane.planeY(), 12.5), aperture.point(0.5, 0.5));
                    }
                }
            }
        }
    }

    @Test
    void openingTheTrapdoorChangesNeitherTheHorizontalFrameNorTheAperturePlane() {
        for (Direction facing : FACINGS) {
            for (DoorHalf half : DoorHalf.values()) {
                DoorwayPlane open = DoorwayPlane.trapdoor(3, 48, -5, facing, half, DoorOpenState.OPEN);
                DoorwayPlane closed = DoorwayPlane.trapdoor(3, 48, -5, facing, half, DoorOpenState.CLOSED);
                assertEquals(DoorApertureFrames.of(open), DoorApertureFrames.of(closed));
                assertEquals(open.center(), closed.center());
                assertEquals(half == DoorHalf.TOP ? Direction.U : Direction.D, DoorApertureFrames.of(open).getNormal());
                assertEquals(half == DoorHalf.TOP ? facing.reverse() : facing, DoorApertureFrames.of(open).getUp());
            }
        }
    }

    private static void assertPair(DoorwayPlane source, DoorwayPlane target, boolean front) {
        DoorVec3 a = source.center();
        DoorVec3 b = target.center();
        PortalFrame local = DoorApertureFrames.of(source);
        PortalFrame remote = DoorApertureFrames.destinationFrame(source, target);
        ClientViewEnvironment.Transform transform = ClientViewEnvironmentTransform.of(new ClientViewEntityTransform.Frame(
            a.x(), a.y(), a.z(), local, b.x(), b.y(), b.z(), remote, false, 0, front, 128));
        assertEquals(Direction.U, transform.yAxis());
        GeometryVector center = transform.destinationPoint(a.x(), a.y(), a.z());
        assertPoint(b, center);
        for (double vertical : new double[]{-2, 2}) {
            GeometryVector eye = transform.destinationPoint(a.x() + source.facing().x() * 0.25,
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

    private static void assertPoint(DoorVec3 expected, GeometryVector actual) {
        assertEquals(expected.x(), actual.x(), 1.0E-10);
        assertEquals(expected.y(), actual.y(), 1.0E-10);
        assertEquals(expected.z(), actual.z(), 1.0E-10);
    }
}
