package art.arcane.wormholes.portal;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.ShapeDescriptor;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpeningRestTest {
    private static final double EYE = 1.62D;
    private static final ApertureDescriptor FLOOR = horizontal(64, false, ApertureKind.FRAME, ShapeDescriptor.FULL, 3);
    private static final ApertureDescriptor CEILING = horizontal(70, true, ApertureKind.FRAME, ShapeDescriptor.FULL, 3);
    private static final Predicate<Box> BACKING = box -> box.getYa() < 64.0D;
    private static final Predicate<Box> CEILING_BACKING = box -> box.getYb() > 71.0D;

    @Test
    void standingOnTheBlockUnderAFloorOpeningRestsAndExitsWithTheEyeJustPastThePlane() {
        OpeningRest.Rest rest = OpeningRest.rest(FLOOR, body(1.5D, 64.0D), new Vec3d(1.5D, 64.0D + EYE, 1.5D), BACKING);

        assertNotNull(rest);
        assertEquals(64.5D - (64.0D + EYE) - 1.0E-3D, rest.shift(), 1.0E-9D);
        Vec3d exit = rest.exit(new Vec3d(1.5D, 64.0D, 1.5D));
        assertEquals(64.5D - 1.0E-3D, exit.y() + EYE, 1.0E-9D);
        assertEquals(1.5D, exit.x(), 0.0D);
        assertEquals(1.5D, exit.z(), 0.0D);
    }

    @Test
    void standingTwoBlocksDeepInAnOpeningStillRestsWhileTheEyeIsAboveThePlane() {
        Predicate<Box> deep = box -> box.getYa() < 63.0D;
        OpeningRest.Rest rest = OpeningRest.rest(FLOOR, body(1.5D, 63.0D), new Vec3d(1.5D, 63.0D + EYE, 1.5D), deep);

        assertNotNull(rest);
        assertEquals(64.5D - (63.0D + EYE) - 1.0E-3D, rest.shift(), 1.0E-9D);
    }

    @Test
    void anOpeningOverAirIsLeftToTheEyeCrossing() {
        assertNull(OpeningRest.rest(FLOOR, body(1.5D, 64.0D), new Vec3d(1.5D, 64.0D + EYE, 1.5D), box -> false));
    }

    @Test
    void standingOnSolidSelectedCellsHasNotEnteredTheOpening() {
        assertNull(OpeningRest.rest(FLOOR, body(1.5D, 65.0D), new Vec3d(1.5D, 65.0D + EYE, 1.5D), box -> box.getYa() < 65.0D));
    }

    @Test
    void aBodyPassingThroughTheBlocksBehindTheOpeningIsNotResting() {
        assertNull(OpeningRest.rest(FLOOR, body(1.5D, 63.6D), new Vec3d(1.5D, 63.6D + EYE, 1.5D), BACKING));
    }

    @Test
    void anEyeAlreadyPastThePlaneIsLeftToTheEyeCrossing() {
        assertNull(OpeningRest.rest(FLOOR, body(1.5D, 62.8D), new Vec3d(1.5D, 62.8D + EYE, 1.5D), box -> box.getYa() < 62.8D));
    }

    @Test
    void aBodyBesideTheOpeningDoesNotRest() {
        assertNull(OpeningRest.rest(FLOOR, body(3.5D, 64.0D), new Vec3d(3.5D, 64.0D + EYE, 1.5D), BACKING));
    }

    @Test
    void aHeadPressedIntoTheBlockAboveACeilingOpeningRestsUpward() {
        double eye = 0.4D;
        double feet = 71.0D - 1.8D;
        OpeningRest.Rest rest = OpeningRest.rest(CEILING, new Box(1.2D, 1.8D, feet, 71.0D, 1.2D, 1.8D), new Vec3d(1.5D, feet + eye, 1.5D),
            CEILING_BACKING);

        assertNotNull(rest);
        assertEquals(70.5D - (feet + eye) + 1.0E-3D, rest.shift(), 1.0E-9D);
    }

    @Test
    void aHeadBelowTheCeilingOpeningHasNotEnteredIt() {
        assertNull(OpeningRest.rest(CEILING, new Box(1.2D, 1.8D, 68.0D, 69.8D, 1.2D, 1.8D), new Vec3d(1.5D, 68.4D, 1.5D), box -> box.getYb() > 69.8D));
    }

    @Test
    void onlyHorizontalFramePortalsRest() {
        assertTrue(OpeningRest.applies(FLOOR));
        assertFalse(OpeningRest.applies(horizontal(64, false, ApertureKind.VANILLA_REPLACEMENT, ShapeDescriptor.FULL, 3)));
        assertFalse(OpeningRest.applies(horizontal(64, false, ApertureKind.DOOR, ShapeDescriptor.FULL, 3)));
        assertFalse(OpeningRest.applies(new ApertureDescriptor(0, 64, 0, Face.S.ordinal(), true, 0, false, 3, 3, new long[]{511},
            ShapeDescriptor.FULL, 0, 0, 1, 64, 0, 0, 0, 0, 0, 0, ApertureKind.FRAME, 0.0D, 0, 1, List.of())));
        assertNull(OpeningRest.rest(new ApertureDescriptor(0, 64, 0, Face.S.ordinal(), true, 0, false, 3, 3, new long[]{511},
            ShapeDescriptor.FULL, 0, 0, 1, 64, 0, 0, 0, 0, 0, 0, ApertureKind.FRAME, 0.0D, 0, 1, List.of()), body(1.5D, 64.0D),
            new Vec3d(1.5D, 64.0D + EYE, 0.5D), box -> true));
    }

    @Test
    void aShapedOpeningRestsOnlyInsideItsShape() {
        ApertureDescriptor circle = horizontal(64, false, ApertureKind.FRAME, ShapeDescriptor.parse("circle"), 7);

        assertNotNull(OpeningRest.rest(circle, body(3.5D, 64.0D, 3.5D), new Vec3d(3.5D, 64.0D + EYE, 3.5D), BACKING));
        assertNull(OpeningRest.rest(circle, body(0.3D, 64.0D, 0.3D), new Vec3d(0.3D, 64.0D + EYE, 0.3D), BACKING));
    }

    private static Box body(double x, double feet) {
        return body(x, feet, 1.5D);
    }

    private static Box body(double x, double feet, double z) {
        return new Box(x - 0.3D, x + 0.3D, feet, feet + 1.8D, z - 0.3D, z + 0.3D);
    }

    private static ApertureDescriptor horizontal(int y, boolean front, int kind, ShapeDescriptor shape, int size) {
        boolean[] open = new boolean[size * size];
        Arrays.fill(open, true);
        return new ApertureDescriptor(0, y, 0, Face.D.ordinal(), front, 0, false, size, size, ApertureDescriptor.apertureMask(size, size, open), shape,
            0, 0, 1, 64, 0, 0, 0, 0, 0, 0, kind, 0.0D, 0, 1, List.of());
    }
}
