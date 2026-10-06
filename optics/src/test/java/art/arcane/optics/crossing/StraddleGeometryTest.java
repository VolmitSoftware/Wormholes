package art.arcane.optics.crossing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;

final class StraddleGeometryTest {
    private static final double EPSILON = 1.0E-9D;

    @Test
    void exclusionSlabExtrudesTheApertureFootprintBehindThePlane() {
        ApertureCells aperture = northAperture();
        Frame frame = Frame.canonical(Face.N);
        Vec3d origin = aperture.getApertureCenter();
        Box front = StraddleGeometry.exclusionSlab(aperture, frame, origin, true, 10.0D);
        assertBox(new Box(10.0D, 12.0D, 64.0D, 67.0D, origin.z(), origin.z() + 10.0D), front);
        Box back = StraddleGeometry.exclusionSlab(aperture, frame, origin, false, 10.0D);
        assertBox(new Box(10.0D, 12.0D, 64.0D, 67.0D, origin.z() - 10.0D, origin.z()), back);

        ApertureCells floor = new ApertureCells();
        floor.setBlocks(List.of(new Vec3d(4, 70, 4), new Vec3d(5, 70, 4), new Vec3d(4, 70, 5), new Vec3d(5, 70, 5)));
        Vec3d floorOrigin = floor.getApertureCenter();
        Box below = StraddleGeometry.exclusionSlab(floor, Frame.canonical(Face.U), floorOrigin, true, 3.0D);
        assertBox(new Box(4.0D, 6.0D, floorOrigin.y() - 3.0D, floorOrigin.y(), 4.0D, 6.0D), below);
    }

    @Test
    void straddlingNeedsTheBoxOnBothSidesOfThePlane() {
        Frame frame = Frame.canonical(Face.N);
        Vec3d origin = new Vec3d(11.0D, 65.5D, 20.5D);
        assertTrue(StraddleGeometry.straddles(new Box(10.7D, 11.3D, 64.0D, 65.8D, 20.2D, 20.8D), frame, origin, true));
        assertTrue(StraddleGeometry.straddles(new Box(10.7D, 11.3D, 64.0D, 65.8D, 20.2D, 20.8D), frame, origin, false));
        assertFalse(StraddleGeometry.straddles(new Box(10.7D, 11.3D, 64.0D, 65.8D, 19.2D, 19.8D), frame, origin, true));
        assertFalse(StraddleGeometry.straddles(new Box(10.7D, 11.3D, 64.0D, 65.8D, 21.2D, 21.8D), frame, origin, true));
        assertFalse(StraddleGeometry.straddles(new Box(10.7D, 11.3D, 64.0D, 65.8D, 19.9D, 20.5D), frame, origin, true));
    }

    @Test
    void clipToFrontKeepsOnlyTheEntrySideOfTheBox() {
        Frame frame = Frame.canonical(Face.N);
        Vec3d origin = new Vec3d(11.0D, 65.5D, 20.5D);
        Box box = new Box(10.7D, 11.3D, 64.0D, 65.8D, 20.2D, 20.8D);
        assertBox(new Box(10.7D, 11.3D, 64.0D, 65.8D, 20.2D, 20.5D), StraddleGeometry.clipToFront(box, frame, origin, true));
        assertBox(new Box(10.7D, 11.3D, 64.0D, 65.8D, 20.5D, 20.8D), StraddleGeometry.clipToFront(box, frame, origin, false));
        Box inFront = new Box(10.7D, 11.3D, 64.0D, 65.8D, 19.2D, 19.8D);
        assertBox(inFront, StraddleGeometry.clipToFront(inFront, frame, origin, true));
        Box behind = new Box(10.7D, 11.3D, 64.0D, 65.8D, 21.2D, 21.8D);
        assertBox(new Box(10.7D, 11.3D, 64.0D, 65.8D, 20.5D, 20.5D), StraddleGeometry.clipToFront(behind, frame, origin, true));
        Box up = StraddleGeometry.clipToFront(new Box(0.0D, 1.0D, 69.5D, 71.5D, 0.0D, 1.0D), Frame.canonical(Face.U), new Vec3d(0.5D, 70.5D, 0.5D), true);
        assertBox(new Box(0.0D, 1.0D, 70.5D, 71.5D, 0.0D, 1.0D), up);
    }

    @Test
    void mappedBoxesAndMovesRoundTripThroughTheCrossingTransform() {
        Random random = new Random(0x57AD);
        Frame source = Frame.canonical(Face.N);
        for (Face exit : Face.values()) {
            for (boolean front : new boolean[] {true, false}) {
                PlaneCrossing crossing = new PlaneCrossing(source.view(front), new Vec3d(11.0D, 65.5D, 20.5D),
                    new Vec3d(11.0D, 65.5D, 20.4D), new Vec3d(0, 0, -0.3D), new Vec3d(0, 0, -1), front);
                OpticTransform toward = crossing.toward(Frame.canonical(exit), new Vec3d(-300.5D, 90.0D, 41.5D));
                for (int sample = 0; sample < 200; sample++) {
                    Vec3d move = new Vec3d(random.nextDouble() - 0.5D, random.nextDouble() - 0.5D, random.nextDouble() - 0.5D);
                    Vec3d mapped = StraddleGeometry.mappedMove(move, toward);
                    assertEquals(toward.vector(move), mapped);
                    assertEquals(move, StraddleGeometry.unmappedMove(mapped, toward));
                    double x = 10.0D + random.nextDouble() * 2.0D;
                    double y = 64.0D + random.nextDouble() * 2.0D;
                    double z = 19.5D + random.nextDouble() * 2.0D;
                    Box box = new Box(x - 0.3D, x + 0.3D, y, y + 1.8D, z - 0.3D, z + 0.3D);
                    Box mappedBox = StraddleGeometry.mappedBox(box, toward);
                    assertBox(toward.box(box), mappedBox);
                    assertEquals(box.sizeX() * box.sizeY() * box.sizeZ(), mappedBox.volume(), 1.0E-9D);
                    assertBox(box, StraddleGeometry.mappedBox(mappedBox, toward.inverse()));
                }
            }
        }
    }

    private static ApertureCells northAperture() {
        ApertureCells aperture = new ApertureCells();
        aperture.setBlocks(List.of(new Vec3d(10, 64, 20), new Vec3d(11, 64, 20), new Vec3d(10, 65, 20), new Vec3d(11, 65, 20),
            new Vec3d(10, 66, 20), new Vec3d(11, 66, 20)));
        return aperture;
    }

    private static void assertBox(Box expected, Box actual) {
        assertEquals(expected.getXa(), actual.getXa(), EPSILON, "xa");
        assertEquals(expected.getXb(), actual.getXb(), EPSILON, "xb");
        assertEquals(expected.getYa(), actual.getYa(), EPSILON, "ya");
        assertEquals(expected.getYb(), actual.getYb(), EPSILON, "yb");
        assertEquals(expected.getZa(), actual.getZa(), EPSILON, "za");
        assertEquals(expected.getZb(), actual.getZb(), EPSILON, "zb");
    }
}
