package art.arcane.wormholes.door;

import art.arcane.wormholes.door.view.DoorApertureFrames;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Face;
import org.junit.jupiter.api.Test;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class DoorPreparedArrivalTest {
    @Test
    public void pairedWestDoorKeepsContinuousFeetLookAndMomentumInsteadOfTheOneBlockLandingOffset() {
        DoorwayPlane source = new DoorwayPlane(1000, 200, -2, Face.W);
        DoorwayPlane destination = new DoorwayPlane(1102, 80, 9, Face.W);
        DoorTransit transit = prepared(source, new Vec3d(1001.6941597887681D, 200, -1.5D), true,
            new Vec3d(0.6474939030631504D, 0, 0), new Vec3d(1, 0, 0));
        Vec3d point = DoorArrivals.destinationPoint(destination, transit, 1);
        assertEquals(1102.1458402112319D, point.x(), 1.0E-10D);
        assertEquals(80.0D, point.y(), 0.0D);
        assertEquals(9.5D, point.z(), 0.0D);
        assertEquals(90.0F, DoorArrivals.destinationFacing(destination, transit, 1).yaw(), 0.0F);
        assertVector(new Vec3d(-0.6474939030631504D, 0, 0), DoorArrivals.destinationVelocity(destination, transit, 1));
        assertEquals(1101.5D, DoorArrivals.arrivalPoint(destination, transit, 1).x(), 0.0D);
    }

    @Test
    public void allCardinalPairingsAndBothApproachSidesUseTheAdvertisedFrameForEveryComponent() {
        for (Face sourceFacing : new Face[]{Face.N, Face.S, Face.E, Face.W}) {
            for (Face destinationFacing : new Face[]{Face.N, Face.S, Face.E, Face.W}) {
                DoorwayPlane source = new DoorwayPlane(-100, 64, 9, sourceFacing);
                DoorwayPlane destination = new DoorwayPlane(100, -64, -9, destinationFacing);
                Vec3d center = source.center();
                for (boolean front : new boolean[]{true, false}) {
                    DoorTransit transit = prepared(source, new Vec3d(center.x() + 0.125D, 64, center.z() - 0.25D), front,
                        new Vec3d(0.2D, -0.3D, 0.4D), new Vec3d(0, 0, 1));
                    Frame target = DoorApertureFrames.destinationFrame(source, destination);
                    Vec3d targetCenter = destination.center();
                    Vec3d expected = transit.preparedCrossing().outPoint(target, targetCenter);
                    assertEquals(DoorArrivals.destinationPoint(destination, transit, 1), expected);
                    Vec3d expectedVelocity = transit.preparedCrossing().outVelocity(target);
                    assertEquals(DoorArrivals.destinationVelocity(destination, transit, 1), expectedVelocity);
                }
            }
        }
    }

    @Test
    public void fallingTrapdoorContinuationDoesNotBecomeAGroundedDoorOffset() {
        DoorwayPlane source = DoorwayPlane.trapdoor(4, 64, 7, Face.E, DoorHalf.TOP, DoorOpenState.OPEN);
        DoorwayPlane destination = DoorwayPlane.trapdoor(40, 80, 70, Face.E, DoorHalf.TOP, DoorOpenState.OPEN);
        Vec3d center = source.center();
        DoorTransit transit = prepared(source, new Vec3d(center.x(), center.y() - 0.2D, center.z()), true,
            new Vec3d(0, -0.5D, 0), new Vec3d(0, -1, 0));
        assertEquals(destination.center().y() - 0.2D, DoorArrivals.destinationPoint(destination, transit, -1).y(), 1.0E-10D);
        assertVector(new Vec3d(0, -0.5D, 0), DoorArrivals.destinationVelocity(destination, transit, -1));
    }

    @Test
    public void ordinaryArrivalsKeepTheExistingClearanceFacingAndVelocityPolicy() {
        DoorwayPlane source = new DoorwayPlane(4, 64, 7, Face.N);
        DoorwayPlane destination = new DoorwayPlane(40, 80, 70, Face.W);
        DoorTransit transit = new DoorTransit(source, true, 0, 15,
            0.3D, 1.8D, DoorTravelerClass.LIVING, new Vec3d(0, 0, 0.4D));
        assertEquals(DoorArrivals.arrivalPoint(destination, transit, 1), DoorArrivals.destinationPoint(destination, transit, 1));
        assertEquals(DoorArrivals.arrivalFacing(destination, transit, 1), DoorArrivals.destinationFacing(destination, transit, 1));
        assertEquals(DoorPlanePairing.mapVectorToSide(destination, transit, transit.velocity(), 1),
            DoorArrivals.destinationVelocity(destination, transit, 1));
    }

    @Test
    public void supersedingArrivalTokenCannotBeRolledBackByTheFailedEarlierOpening() {
        DoorAutoCloseBook book = new DoorAutoCloseBook();
        UUID door = UUID.randomUUID();
        long first = book.arm(door);
        assertTrue(book.isCurrent(door, first));
        long successor = book.arm(door);
        assertFalse(book.isCurrent(door, first));
        assertTrue(book.isCurrent(door, successor));
    }

    private static DoorTransit prepared(DoorwayPlane source, Vec3d feet, boolean front, Vec3d velocity, Vec3d look) {
        Vec3d center = source.center();
        PlaneCrossing crossing = new PlaneCrossing(DoorApertureFrames.of(source).view(front), center, feet, velocity, look, front);
        PlaneCrossing gate = source.crossingAt(crossing.point(), velocity, front);
        return new DoorTransit(source, gate, -90, 0, 0.3D, 1.8D, DoorTravelerClass.LIVING, velocity, crossing);
    }

    private static void assertVector(Vec3d expected, Vec3d actual) {
        assertEquals(expected.x(), actual.x(), 0.0D);
        assertEquals(expected.y(), actual.y(), 0.0D);
        assertEquals(expected.z(), actual.z(), 0.0D);
    }
}
