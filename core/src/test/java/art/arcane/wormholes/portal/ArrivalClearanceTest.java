package art.arcane.wormholes.portal;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArrivalClearanceTest {
    private static final Frame HORIZONTAL = Frame.canonical(Face.D);
    private static final Vec3d FLOOR = new Vec3d(1.5D, 64.5D, 1.5D);
    private static final Vec3d CEILING = new Vec3d(11.5D, 80.5D, 11.5D);

    @Test
    void aDownwardArrivalWithItsHeadInTheCeilingCellsMovesBelowThem() {
        Similarity toward = Similarity.between(HORIZONTAL.view(false), FLOOR, HORIZONTAL.view(false), CEILING, 1.0D);
        double feet = 80.5D - 1.621D;

        double lift = ArrivalClearance.lift(toward, FLOOR, HORIZONTAL.view(false).getNormal(), body(feet));

        assertEquals(80.0D - 1.0E-3D - (feet + 1.8D), lift, 1.0E-9D);
    }

    @Test
    void anUpwardArrivalInsideTheBlocksUnderAFloorOpeningMovesAboveTheOpening() {
        Vec3d ceiling = new Vec3d(1.5D, 70.5D, 1.5D);
        Vec3d floor = new Vec3d(11.5D, 64.5D, 11.5D);
        Similarity toward = Similarity.between(HORIZONTAL.view(true), ceiling, HORIZONTAL.view(true), floor, 1.0D);
        double feet = 64.5D + 1.0E-3D - 1.62D;

        double lift = ArrivalClearance.lift(toward, ceiling, HORIZONTAL.view(true).getNormal(), body(feet));

        assertEquals(65.0D + 1.0E-3D - feet, lift, 1.0E-9D);
    }

    @Test
    void anArrivalAlreadyBeyondTheOpeningIsNotMoved() {
        Vec3d ceiling = new Vec3d(1.5D, 70.5D, 1.5D);
        Vec3d floor = new Vec3d(11.5D, 64.5D, 11.5D);
        Similarity toward = Similarity.between(HORIZONTAL.view(true), ceiling, HORIZONTAL.view(true), floor, 1.0D);

        assertEquals(0.0D, ArrivalClearance.lift(toward, ceiling, HORIZONTAL.view(true).getNormal(), body(65.5D)), 0.0D);
    }

    @Test
    void anArrivalThroughAWallIsNotMoved() {
        Frame wall = Frame.canonical(Face.S);
        Similarity toward = Similarity.between(HORIZONTAL.view(false), FLOOR, wall, new Vec3d(20.5D, 65.5D, 20.5D), 1.0D);

        assertEquals(0.0D, ArrivalClearance.lift(toward, FLOOR, HORIZONTAL.view(false).getNormal(), body(64.0D)), 0.0D);
    }

    @Test
    void aScaledPairClearsTheScaledOpening() {
        Similarity toward = Similarity.between(HORIZONTAL.view(false), FLOOR, HORIZONTAL.view(false), CEILING, 2.0D);
        double feet = 79.0D;

        double lift = ArrivalClearance.lift(toward, FLOOR, HORIZONTAL.view(false).getNormal(), body(feet));

        assertEquals(79.5D - 1.0E-3D - (feet + 1.8D), lift, 1.0E-9D);
    }

    private static Box body(double feet) {
        return new Box(11.2D, 11.8D, feet, feet + 1.8D, 11.2D, 11.8D);
    }
}
