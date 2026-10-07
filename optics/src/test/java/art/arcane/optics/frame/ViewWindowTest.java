package art.arcane.optics.frame;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

    @Test
    void towardIsTheSingleLocalToRemoteChoiceForLinkedAndMirroredWindows() {
        Vec3d localOrigin = new Vec3d(-31.5D, 68.0D, -2.5D);
        Vec3d remoteOrigin = new Vec3d(124.5D, -16.0D, 241.5D);
        double[] expected = new double[3];
        double[] actual = new double[3];
        for (Face local : Face.values()) {
            for (Face remote : Face.values()) {
                for (boolean front : new boolean[] {false, true}) {
                    Frame localFrame = Frame.canonical(local);
                    Frame remoteFrame = Frame.canonical(remote);
                    ViewWindow linked = ViewWindow.of(false, QuarterTurn.DEGREES_90, localOrigin, localFrame, remoteOrigin, remoteFrame, front, 32);
                    assertEquals(ViewWindow.between(localOrigin, localFrame, remoteOrigin, remoteFrame, front, 32), linked);
                    OpticTransform explicit = OpticTransform.between(localFrame.view(front), localOrigin, remoteFrame.view(front), remoteOrigin);
                    assertEquals(explicit, linked.toward());
                    explicit.pointInto(5.25D, 70.5D, -9.75D, expected);
                    linked.toward().pointInto(5.25D, 70.5D, -9.75D, actual);
                    assertArrayEquals(expected, actual);
                }
            }
            for (QuarterTurn turns : QuarterTurn.values()) {
                Frame plane = Frame.canonical(local);
                ViewWindow mirror = ViewWindow.of(true, turns, localOrigin, plane, remoteOrigin, Frame.canonical(Face.U), true, 32);
                assertEquals(ViewWindow.mirror(localOrigin, plane, turns, true, 32), mirror);
                assertEquals(OpticTransform.mirror(plane, localOrigin, turns).inverse(), mirror.toward());
            }
        }
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
