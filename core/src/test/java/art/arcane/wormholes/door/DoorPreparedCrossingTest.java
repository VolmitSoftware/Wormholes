package art.arcane.wormholes.door;

import art.arcane.wormholes.door.view.DoorApertureFrames;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Face;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class DoorPreparedCrossingTest {
    @Test
    public void allCardinalDoorsPreserveActualFeetAndApproachSide() {
        for (Face facing : new Face[]{Face.N, Face.S, Face.E, Face.W}) {
            DoorwayPlane plane = new DoorwayPlane(4, 64, 7, facing);
            Vec3d center = plane.center();
            Vec3d feet = new Vec3d(center.x(), 64.4D, center.z());
            for (boolean front : new boolean[]{true, false}) {
                PlaneCrossing result = DoorTransitGate.prepared(plane, crossing(plane, feet, front), 0.3D, 1.8D).orElseThrow();
                assertEquals(front ? true : false, result.frontSide());
                assertEquals(feet.x(), result.point().x(), 0.000001D);
                assertEquals(feet.y(), result.point().y(), 0.000001D);
                assertEquals(feet.z(), result.point().z(), 0.000001D);
            }
        }
    }

    @Test
    public void trapdoorFrameOrientationMapsBothApproachSides() {
        for (DoorHalf half : DoorHalf.values()) {
            DoorwayPlane plane = DoorwayPlane.trapdoor(4, 64, 7, Face.E, half, DoorOpenState.OPEN);
            for (boolean front : new boolean[]{true, false}) {
                PlaneCrossing result = DoorTransitGate.prepared(plane, crossing(plane, plane.center(), front), 0.3D, 1.8D).orElseThrow();
                boolean above = half == DoorHalf.TOP ? front : !front;
                assertEquals(above ? true : false, result.frontSide());
                assertEquals(plane.center(), result.point());
            }
        }
    }

    @Test
    public void changedFramePlaneAndOutsideApertureAreRejected() {
        DoorwayPlane plane = new DoorwayPlane(4, 64, 7, Face.N);
        PlaneCrossing valid = crossing(plane, plane.center(), true);
        PlaneCrossing moved = new PlaneCrossing(valid.frame(), valid.origin().add(new Vec3d(0, 0, 1)), valid.point(), valid.velocity(), valid.look(), true);
        assertTrue(DoorTransitGate.prepared(plane, moved, 0.3D, 1.8D).isEmpty());
        assertTrue(DoorTransitGate.prepared(new DoorwayPlane(4, 64, 7, Face.E), valid, 0.3D, 1.8D).isEmpty());
        Vec3d center = plane.center();
        assertTrue(DoorTransitGate.prepared(plane, crossing(plane, new Vec3d(center.x() + 2, center.y(), center.z()), true), 0.3D, 1.8D).isEmpty());
    }

    @Test
    public void closedSurfaceRetainsItsContactAdmission() {
        DoorwayPlane plane = new DoorwayPlane(4, 64, 7, Face.N, DoorForm.DOOR, DoorHalf.BOTTOM, DoorOpenState.CLOSED);
        assertTrue(DoorTransitGate.prepared(plane, crossing(plane, plane.center(), true), 0.3D, 1.8D).isPresent());
    }

    private static PlaneCrossing crossing(DoorwayPlane plane, Vec3d feet, boolean front) {
        Vec3d center = plane.center();
        Frame frame = DoorApertureFrames.of(plane);
        return new PlaneCrossing(frame.view(front), new Vec3d(center.x(), center.y(), center.z()),
            new Vec3d(feet.x(), feet.y(), feet.z()), new Vec3d(0, 0, 0), new Vec3d(0, 0, 1), front);
    }
}
