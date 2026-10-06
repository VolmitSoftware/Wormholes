package art.arcane.optics.frame;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;

final class ViewWindowTest {
    @Test
    void inverseCameraMappingMatchesTerrainForEveryOrientationAndSide() {
        for (Face local : Face.values()) {
            for (Face remote : Face.values()) {
                for (boolean front : new boolean[] {false, true}) {
                    verify(ViewWindow.between(new Vec3d(-31.5D, 68.0D, -2.5D), Frame.canonical(local), new Vec3d(124.5D, -16.0D, 241.5D),
                        Frame.canonical(remote), front, 160));
                }
            }
        }
    }

    @Test
    void mirrorRotationAndNegativeOriginsPreserveDestinationEye() {
        for (Face normal : Face.values()) {
            for (QuarterTurn turns : QuarterTurn.values()) {
                verify(ViewWindow.mirror(new Vec3d(-31.5D, 68.0D, -2.5D), Frame.canonical(normal), turns, true, 160));
            }
        }
    }

    @Test
    void encodedTransformsMapTheSameDestinationEye() {
        ViewWindow window = ViewWindow.between(new Vec3d(-31.5D, 68.0D, -2.5D), Frame.canonical(Face.E), new Vec3d(124.5D, -16.0D, 241.5D),
            Frame.canonical(Face.N), true, 160);
        OpticTransform decoded = OpticTransform.decode(window.transform().encode());
        double[] display = new double[3];
        double[] destination = new double[3];
        window.transform().pointInto(138.125D, 92.25D, -134.5D, display);
        decoded.inverse().pointInto(display[0], display[1], display[2], destination);
        assertEquals(138.125D, destination[0], 0.00001D);
        assertEquals(92.25D, destination[1], 0.00001D);
        assertEquals(-134.5D, destination[2], 0.00001D);
    }

    private static void verify(ViewWindow window) {
        double[] display = new double[3];
        double[] result = new double[3];
        window.transform().pointInto(138.125D, 92.25D, -134.5D, display);
        window.transform().inverse().pointInto(display[0], display[1], display[2], result);
        assertEquals(138.125D, result[0], 0.00001D);
        assertEquals(92.25D, result[1], 0.00001D);
        assertEquals(-134.5D, result[2], 0.00001D);
    }
}
