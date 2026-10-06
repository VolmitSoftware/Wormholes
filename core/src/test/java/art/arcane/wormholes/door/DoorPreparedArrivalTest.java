package art.arcane.wormholes.door;

import art.arcane.wormholes.door.view.DoorApertureFrames;
import art.arcane.optics.math.Vec3;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Face;
import org.junit.jupiter.api.Test;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class DoorPreparedArrivalTest {
    @Test
    public void pairedWestDoorKeepsContinuousFeetLookAndMomentumInsteadOfTheOneBlockLandingOffset() {
        DoorwayPlane source = new DoorwayPlane(1000, 200, -2, Face.W);
        DoorwayPlane destination = new DoorwayPlane(1102, 80, 9, Face.W);
        DoorTransit transit = prepared(source, new DoorVec3(1001.6941597887681D, 200, -1.5D), true,
            new DoorVec3(0.6474939030631504D, 0, 0), new DoorVec3(1, 0, 0));
        DoorVec3 point = DoorArrivals.destinationPoint(destination, transit, 1);
        assertEquals(1102.1458402112319D, point.x(), 1.0E-10D);
        assertEquals(80.0D, point.y(), 0.0D);
        assertEquals(9.5D, point.z(), 0.0D);
        assertEquals(90.0F, DoorArrivals.destinationFacing(destination, transit, 1).yaw(), 0.0F);
        assertEquals(new DoorVec3(-0.6474939030631504D, 0, 0), DoorArrivals.destinationVelocity(destination, transit, 1));
        assertEquals(1101.5D, DoorArrivals.arrivalPoint(destination, transit, 1).x(), 0.0D);
    }

    @Test
    public void allCardinalPairingsAndBothApproachSidesUseTheAdvertisedFrameForEveryComponent() {
        for (Face sourceFacing : new Face[]{Face.N, Face.S, Face.E, Face.W}) {
            for (Face destinationFacing : new Face[]{Face.N, Face.S, Face.E, Face.W}) {
                DoorwayPlane source = new DoorwayPlane(-100, 64, 9, sourceFacing);
                DoorwayPlane destination = new DoorwayPlane(100, -64, -9, destinationFacing);
                DoorVec3 center = source.center();
                for (boolean front : new boolean[]{true, false}) {
                    DoorTransit transit = prepared(source, new DoorVec3(center.x() + 0.125D, 64, center.z() - 0.25D), front,
                        new DoorVec3(0.2D, -0.3D, 0.4D), new DoorVec3(0, 0, 1));
                    Frame target = DoorApertureFrames.destinationFrame(source, destination);
                    DoorVec3 targetCenter = destination.center();
                    Vec3 expected = transit.preparedCrossing().outPoint(target, vector(targetCenter));
                    assertEquals(vector(DoorArrivals.destinationPoint(destination, transit, 1)), expected);
                    Vec3 expectedVelocity = transit.preparedCrossing().outVelocity(target);
                    assertEquals(vector(DoorArrivals.destinationVelocity(destination, transit, 1)), expectedVelocity);
                }
            }
        }
    }

    @Test
    public void fallingTrapdoorContinuationDoesNotBecomeAGroundedDoorOffset() {
        DoorwayPlane source = DoorwayPlane.trapdoor(4, 64, 7, Face.E, DoorHalf.TOP, DoorOpenState.OPEN);
        DoorwayPlane destination = DoorwayPlane.trapdoor(40, 80, 70, Face.E, DoorHalf.TOP, DoorOpenState.OPEN);
        DoorVec3 center = source.center();
        DoorTransit transit = prepared(source, new DoorVec3(center.x(), center.y() - 0.2D, center.z()), true,
            new DoorVec3(0, -0.5D, 0), new DoorVec3(0, -1, 0));
        assertEquals(destination.center().y() - 0.2D, DoorArrivals.destinationPoint(destination, transit, -1).y(), 1.0E-10D);
        assertEquals(new DoorVec3(0, -0.5D, 0), DoorArrivals.destinationVelocity(destination, transit, -1));
    }

    @Test
    public void ordinaryArrivalsKeepTheExistingClearanceFacingAndVelocityPolicy() {
        DoorwayPlane source = new DoorwayPlane(4, 64, 7, Face.N);
        DoorwayPlane destination = new DoorwayPlane(40, 80, 70, Face.W);
        DoorTransit transit = new DoorTransit(source, DoorwayCrossing.Direction.FRONT_TO_BACK, 0, 15,
            0.3D, 1.8D, DoorTravelerClass.LIVING, new DoorVec3(0, 0, 0.4D));
        assertEquals(DoorArrivals.arrivalPoint(destination, transit, 1), DoorArrivals.destinationPoint(destination, transit, 1));
        assertEquals(DoorArrivals.arrivalFacing(destination, transit, 1), DoorArrivals.destinationFacing(destination, transit, 1));
        assertEquals(DoorVelocityTransform.mapToSide(destination, transit, transit.velocity(), 1),
            DoorArrivals.destinationVelocity(destination, transit, 1));
    }

    @Test
    public void supersedingArrivalTokenCannotBeRolledBackByTheFailedEarlierOpening() {
        DoorAutoCloseBook book = new DoorAutoCloseBook();
        UUID door = UUID.randomUUID();
        long first = book.arm(door);
        assertEquals(true, book.isCurrent(door, first));
        long successor = book.arm(door);
        assertEquals(false, book.isCurrent(door, first));
        assertEquals(true, book.isCurrent(door, successor));
    }

    private static DoorTransit prepared(DoorwayPlane source, DoorVec3 feet, boolean front, DoorVec3 velocity, DoorVec3 look) {
        DoorVec3 center = source.center();
        PlaneCrossing crossing = new PlaneCrossing(DoorApertureFrames.of(source).view(front), vector(center), vector(feet),
            vector(velocity), vector(look), front);
        DoorwayCrossing gate = new DoorwayCrossing(vectorToDoor(crossing.point()), 1, 0, 0,
            front ? DoorwayCrossing.Direction.FRONT_TO_BACK : DoorwayCrossing.Direction.BACK_TO_FRONT);
        return new DoorTransit(source, gate, -90, 0, 0.3D, 1.8D, DoorTravelerClass.LIVING, velocity, crossing);
    }

    private static Vec3 vector(DoorVec3 point) {
        return new Vec3(point.x(), point.y(), point.z());
    }

    private static DoorVec3 vectorToDoor(Vec3 point) {
        return new DoorVec3(point.x(), point.y(), point.z());
    }
}
