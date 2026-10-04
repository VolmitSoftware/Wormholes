package art.arcane.wormholes.door;

import art.arcane.wormholes.door.view.DoorApertureFrames;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.portal.PortalCrossing;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.util.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class DoorPreparedCrossingTest {
    @Test
    public void allCardinalDoorsPreserveActualFeetAndApproachSide() {
        for (Direction facing : new Direction[]{Direction.N, Direction.S, Direction.E, Direction.W}) {
            DoorwayPlane plane = new DoorwayPlane(4, 64, 7, facing);
            DoorVec3 center = plane.center();
            DoorVec3 feet = new DoorVec3(center.x(), 64.4D, center.z());
            for (boolean front : new boolean[]{true, false}) {
                DoorwayCrossing result = DoorTransitGate.prepared(plane, crossing(plane, feet, front), 0.3D, 1.8D).orElseThrow();
                assertEquals(front ? DoorwayCrossing.Direction.FRONT_TO_BACK : DoorwayCrossing.Direction.BACK_TO_FRONT, result.direction());
                assertEquals(feet.x(), result.point().x(), 0.000001D);
                assertEquals(feet.y(), result.point().y(), 0.000001D);
                assertEquals(feet.z(), result.point().z(), 0.000001D);
            }
        }
    }

    @Test
    public void trapdoorFrameOrientationMapsBothApproachSides() {
        for (DoorHalf half : DoorHalf.values()) {
            DoorwayPlane plane = DoorwayPlane.trapdoor(4, 64, 7, Direction.E, half, DoorOpenState.OPEN);
            for (boolean front : new boolean[]{true, false}) {
                DoorwayCrossing result = DoorTransitGate.prepared(plane, crossing(plane, plane.center(), front), 0.3D, 1.8D).orElseThrow();
                boolean above = half == DoorHalf.TOP ? front : !front;
                assertEquals(above ? DoorwayCrossing.Direction.FRONT_TO_BACK : DoorwayCrossing.Direction.BACK_TO_FRONT, result.direction());
                assertEquals(plane.center(), result.point());
            }
        }
    }

    @Test
    public void changedFramePlaneAndOutsideApertureAreRejected() {
        DoorwayPlane plane = new DoorwayPlane(4, 64, 7, Direction.N);
        PortalCrossing valid = crossing(plane, plane.center(), true);
        PortalCrossing moved = new PortalCrossing(valid.frame(), valid.origin().add(new GeometryVector(0, 0, 1)), valid.point(), valid.velocity(), valid.look(), true);
        assertTrue(DoorTransitGate.prepared(plane, moved, 0.3D, 1.8D).isEmpty());
        assertTrue(DoorTransitGate.prepared(new DoorwayPlane(4, 64, 7, Direction.E), valid, 0.3D, 1.8D).isEmpty());
        DoorVec3 center = plane.center();
        assertTrue(DoorTransitGate.prepared(plane, crossing(plane, new DoorVec3(center.x() + 2, center.y(), center.z()), true), 0.3D, 1.8D).isEmpty());
    }

    @Test
    public void closedSurfaceRetainsItsContactAdmission() {
        DoorwayPlane plane = new DoorwayPlane(4, 64, 7, Direction.N, DoorForm.DOOR, DoorHalf.BOTTOM, DoorOpenState.CLOSED);
        assertTrue(DoorTransitGate.prepared(plane, crossing(plane, plane.center(), true), 0.3D, 1.8D).isPresent());
    }

    private static PortalCrossing crossing(DoorwayPlane plane, DoorVec3 feet, boolean front) {
        DoorVec3 center = plane.center();
        PortalFrame frame = DoorApertureFrames.of(plane);
        return new PortalCrossing(frame.view(front), new GeometryVector(center.x(), center.y(), center.z()),
            new GeometryVector(feet.x(), feet.y(), feet.z()), new GeometryVector(0, 0, 0), new GeometryVector(0, 0, 1), front);
    }
}
