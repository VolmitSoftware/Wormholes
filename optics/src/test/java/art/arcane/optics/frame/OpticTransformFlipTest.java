package art.arcane.optics.frame;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;

final class OpticTransformFlipTest {
    private static final Vec3d ORIGIN = new Vec3d(0.5D, 64.0D, 0.5D);

    @Test
    void floorToCeilingTunnelFlipsWorldUp() {
        Frame floor = Frame.canonical(Face.U);
        Frame ceiling = Frame.canonical(Face.D);
        assertTrue(OpticTransform.between(floor, ORIGIN, ceiling, ORIGIN).flipsWorldUp());
        assertTrue(OpticTransform.between(ceiling, ORIGIN, floor, ORIGIN).flipsWorldUp());
    }

    @Test
    void matchingVerticalTunnelKeepsWorldUp() {
        assertFalse(OpticTransform.between(Frame.canonical(Face.U), ORIGIN, Frame.canonical(Face.U), ORIGIN).flipsWorldUp());
    }

    @Test
    void wallTunnelsKeepWorldUp() {
        for (Face fromNormal : new Face[] {Face.N, Face.S, Face.E, Face.W}) {
            for (Face toNormal : new Face[] {Face.N, Face.S, Face.E, Face.W}) {
                assertFalse(OpticTransform.between(Frame.canonical(fromNormal), ORIGIN, Frame.canonical(toNormal), ORIGIN).flipsWorldUp());
            }
        }
    }

    @Test
    void wallToFloorTunnelDoesNotFlip() {
        Frame wall = Frame.canonical(Face.N);
        Frame floor = Frame.canonical(Face.U);
        assertFalse(OpticTransform.between(wall, ORIGIN, floor, ORIGIN).flipsWorldUp());
        assertFalse(OpticTransform.between(floor, ORIGIN, wall, ORIGIN).flipsWorldUp());
    }

    @Test
    void eyeSideFlipOfVerticalFrameRestoresWorldUp() {
        Frame floorBackside = Frame.canonical(Face.U).flipNormal();
        assertFalse(OpticTransform.between(floorBackside, ORIGIN, Frame.canonical(Face.D), ORIGIN).flipsWorldUp());
    }
}
