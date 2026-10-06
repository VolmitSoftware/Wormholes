package art.arcane.wormholes.render;

import art.arcane.wormholes.util.BukkitGeometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.QuarterTurn;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;

public final class PortalMirrorTransformTest {
    private static final double EPSILON = 1.0E-12D;
    private static final Vec3d ORIGIN = new Vec3d(13.25D, -7.5D, 42.75D);

    @Test
    public void sourceAndDisplayTransformsRoundTripForEveryFrameAndRotation() {
        Vector point = new Vector(18.5D, 4.25D, 31.125D);
        Vector vector = new Vector(2.5D, -3.75D, 7.125D);
        double[] displayed = new double[3];
        double[] restored = new double[3];

        for(Face normal : Face.values()) {
            Frame frame = Frame.canonical(normal);
            for(int roll = 0; roll < 4; roll++) {
                for(QuarterTurn rotation : QuarterTurn.values()) {
                    OpticTransform mirror = OpticTransform.mirror(frame, ORIGIN, rotation);
                    mirror.pointInto(point.getX(), point.getY(), point.getZ(), displayed);
                    mirror.inverse().pointInto(displayed[0], displayed[1], displayed[2], restored);
                    assertVector(point, restored);

                    mirror.vectorInto(vector.getX(), vector.getY(), vector.getZ(), displayed);
                    mirror.inverse().vectorInto(displayed[0], displayed[1], displayed[2], restored);
                    assertVector(vector, restored);
                }
                frame = frame.rotateClockwise();
            }
        }
    }

    @Test
    public void quarterTurnsRotateImageClockwiseAndReflectOnlyNormal() {
        Frame frame = Frame.canonical(Face.U);
        Vector source = compose(frame, 2.0D, 3.0D, 4.0D);
        double[] out = new double[3];

        OpticTransform.mirror(frame, ORIGIN, QuarterTurn.DEGREES_0).vectorInto(source.getX(), source.getY(), source.getZ(), out);
        assertComponents(frame, out, 2.0D, 3.0D, -4.0D);
        OpticTransform.mirror(frame, ORIGIN, QuarterTurn.DEGREES_90).vectorInto(source.getX(), source.getY(), source.getZ(), out);
        assertComponents(frame, out, 3.0D, -2.0D, -4.0D);
        OpticTransform.mirror(frame, ORIGIN, QuarterTurn.DEGREES_180).vectorInto(source.getX(), source.getY(), source.getZ(), out);
        assertComponents(frame, out, -2.0D, -3.0D, -4.0D);
        OpticTransform.mirror(frame, ORIGIN, QuarterTurn.DEGREES_270).vectorInto(source.getX(), source.getY(), source.getZ(), out);
        assertComponents(frame, out, -3.0D, 2.0D, -4.0D);
    }

    @Test
    public void wallQuarterTurnsCollapseOntoTheUprightImage() {
        Frame frame = Frame.canonical(Face.N);
        Vector source = compose(frame, 2.0D, 3.0D, 4.0D);
        double[] out = new double[3];

        OpticTransform.mirror(frame, ORIGIN, QuarterTurn.DEGREES_90).vectorInto(source.getX(), source.getY(), source.getZ(), out);
        assertComponents(frame, out, 2.0D, 3.0D, -4.0D);
        OpticTransform.mirror(frame, ORIGIN, QuarterTurn.DEGREES_270).vectorInto(source.getX(), source.getY(), source.getZ(), out);
        assertComponents(frame, out, -2.0D, -3.0D, -4.0D);
    }

    @Test
    public void unrotatedReflectionIsIndependentOfFrameRoll() {
        Frame frame = Frame.canonical(Face.N);
        double[] expected = new double[3];
        double[] actual = new double[3];
        OpticTransform.mirror(frame, ORIGIN, QuarterTurn.DEGREES_0).vectorInto(2.25D, -4.5D, 8.75D, expected);

        for(int roll = 0; roll < 4; roll++) {
            OpticTransform.mirror(frame, ORIGIN, QuarterTurn.DEGREES_0).vectorInto(2.25D, -4.5D, 8.75D, actual);
            assertVector(new Vector(expected[0], expected[1], expected[2]), actual);
            frame = frame.rotateClockwise();
        }
    }

    @Test
    public void mirroredBlockDirectionsFollowImageRotation() {
        Frame wall = Frame.canonical(Face.N);
        Frame floor = Frame.canonical(Face.U);
        AxisPermutation reflection = AxisPermutation.mirror(wall, QuarterTurn.DEGREES_0);
        AxisPermutation halfTurn = AxisPermutation.mirror(wall, QuarterTurn.DEGREES_180);
        AxisPermutation floorQuarterTurn = AxisPermutation.mirror(floor, QuarterTurn.DEGREES_90);

        assertEquals(Face.E, reflection.face(Face.E));
        assertEquals(Face.S, reflection.face(Face.N));
        assertEquals(Face.N, reflection.face(Face.S));
        assertEquals(Face.E, AxisPermutation.mirror(wall, QuarterTurn.DEGREES_90).face(Face.E));
        assertEquals(Face.W, halfTurn.face(Face.E));
        assertEquals(Face.D, halfTurn.face(Face.U));
        assertEquals(Face.S, halfTurn.face(Face.N));
        assertEquals(Face.D, floorQuarterTurn.face(Face.U));
        assertEquals(OpticTransform.mirror(floor, ORIGIN, QuarterTurn.DEGREES_90).face(Face.E), floorQuarterTurn.face(Face.E));
    }

    @Test
    public void imageHalfTurnControlsUpsideDownEntityState() {
        Frame wall = Frame.canonical(Face.N);
        assertFalse(OpticTransform.mirror(wall, ORIGIN, QuarterTurn.DEGREES_0).flipsWorldUp());
        assertTrue(OpticTransform.mirror(wall, ORIGIN, QuarterTurn.DEGREES_180).flipsWorldUp());
        assertTrue(OpticTransform.mirror(Frame.canonical(Face.U), ORIGIN, QuarterTurn.DEGREES_0).flipsWorldUp());
    }

    @Test
    public void everyRotationKeepsWorldUpRepresentableEntityStates() {
        Frame wall = Frame.canonical(Face.N);
        Frame floor = Frame.canonical(Face.U);
        double[] out = new double[3];
        for(QuarterTurn rotation : QuarterTurn.values()) {
            OpticTransform.mirror(wall, ORIGIN, rotation).vectorInto(0.0D, 1.0D, 0.0D, out);
            assertTrue(Math.abs(out[1]) > 0.5D);
            assertEquals(OpticTransform.mirror(wall, ORIGIN, rotation.coherentFor(wall)), OpticTransform.mirror(wall, ORIGIN, rotation));

            OpticTransform.mirror(floor, ORIGIN, rotation).vectorInto(0.0D, 1.0D, 0.0D, out);
            assertTrue(Math.abs(out[1]) > 0.5D);
            assertEquals(rotation, rotation.coherentFor(floor));
        }
    }

    private static Vector compose(Frame frame, double right, double up, double normal) {
        return BukkitGeometry.bukkit(frame.getRight()).multiply(right)
            .add(BukkitGeometry.bukkit(frame.getUp()).multiply(up))
            .add(BukkitGeometry.bukkit(frame.getNormal()).multiply(normal));
    }

    private static void assertComponents(Frame frame, double[] actual, double right, double up, double normal) {
        assertEquals(right, dot(actual, frame.getRight()), EPSILON);
        assertEquals(up, dot(actual, frame.getUp()), EPSILON);
        assertEquals(normal, dot(actual, frame.getNormal()), EPSILON);
    }

    private static double dot(double[] vector, Face direction) {
        return (vector[0] * direction.x()) + (vector[1] * direction.y()) + (vector[2] * direction.z());
    }

    private static void assertVector(Vector expected, double[] actual) {
        assertEquals(expected.getX(), actual[0], EPSILON);
        assertEquals(expected.getY(), actual[1], EPSILON);
        assertEquals(expected.getZ(), actual[2], EPSILON);
    }
}
