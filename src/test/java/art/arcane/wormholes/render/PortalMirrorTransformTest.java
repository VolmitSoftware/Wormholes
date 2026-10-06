package art.arcane.wormholes.render;

import art.arcane.wormholes.util.BukkitGeometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.QuarterTurn;
import art.arcane.optics.math.Face;
import art.arcane.optics.frame.PortalCoordMap;

public final class PortalMirrorTransformTest {
    private static final double EPSILON = 1.0E-12D;

    @Test
    public void sourceAndDisplayTransformsRoundTripForEveryFrameAndRotation() {
        Vector origin = new Vector(13.25D, -7.5D, 42.75D);
        Vector point = new Vector(18.5D, 4.25D, 31.125D);
        Vector vector = new Vector(2.5D, -3.75D, 7.125D);
        double[] displayed = new double[3];
        double[] restored = new double[3];

        for(Face normal : Face.values()) {
            Frame frame = Frame.canonical(normal);
            for(int roll = 0; roll < 4; roll++) {
                for(int rotation = 0; rotation < 4; rotation++) {
                    PortalCoordMap.mirrorSourceToDisplayPointInto(point.getX(), point.getY(), point.getZ(),
                        origin.getX(), origin.getY(), origin.getZ(), frame, rotation, displayed);
                    PortalCoordMap.mirrorDisplayToSourcePointInto(displayed[0], displayed[1], displayed[2],
                        origin.getX(), origin.getY(), origin.getZ(), frame, rotation, restored);
                    assertVector(point, restored);

                    PortalCoordMap.mirrorSourceToDisplayVectorInto(vector.getX(), vector.getY(), vector.getZ(),
                        frame, rotation, displayed);
                    PortalCoordMap.mirrorDisplayToSourceVectorInto(displayed[0], displayed[1], displayed[2],
                        frame, rotation, restored);
                    assertVector(vector, restored);
                }
                frame = frame.rotateClockwise();
            }
        }
    }

    @Test
    public void quarterTurnsRotateImageClockwiseAndReflectOnlyNormal() {
        Frame frame = Frame.canonical(Face.N);
        Vector source = compose(frame, 2.0D, 3.0D, 4.0D);
        double[] out = new double[3];

        PortalCoordMap.mirrorSourceToDisplayVectorInto(source.getX(), source.getY(), source.getZ(), frame, 0, out);
        assertComponents(frame, out, 2.0D, 3.0D, -4.0D);
        PortalCoordMap.mirrorSourceToDisplayVectorInto(source.getX(), source.getY(), source.getZ(), frame, 1, out);
        assertComponents(frame, out, 3.0D, -2.0D, -4.0D);
        PortalCoordMap.mirrorSourceToDisplayVectorInto(source.getX(), source.getY(), source.getZ(), frame, 2, out);
        assertComponents(frame, out, -2.0D, -3.0D, -4.0D);
        PortalCoordMap.mirrorSourceToDisplayVectorInto(source.getX(), source.getY(), source.getZ(), frame, 3, out);
        assertComponents(frame, out, -3.0D, 2.0D, -4.0D);
    }

    @Test
    public void unrotatedReflectionIsIndependentOfFrameRoll() {
        Frame frame = Frame.canonical(Face.N);
        double[] expected = new double[3];
        double[] actual = new double[3];
        PortalCoordMap.mirrorSourceToDisplayVectorInto(2.25D, -4.5D, 8.75D, frame, 0, expected);

        for(int roll = 0; roll < 4; roll++) {
            PortalCoordMap.mirrorSourceToDisplayVectorInto(2.25D, -4.5D, 8.75D, frame, 0, actual);
            assertVector(new Vector(expected[0], expected[1], expected[2]), actual);
            frame = frame.rotateClockwise();
        }
    }

    @Test
    public void mirroredBlockDirectionsFollowImageRotation() {
        Frame frame = Frame.canonical(Face.N);
        double[] scratch = new double[3];

        assertEquals(Face.E, ProjectedBlockDataTransformer.mirrorDirection(Face.E, frame, 0, scratch));
        assertEquals(Face.S, ProjectedBlockDataTransformer.mirrorDirection(Face.N, frame, 0, scratch));
        assertEquals(Face.N, ProjectedBlockDataTransformer.mirrorDirection(Face.S, frame, 0, scratch));
        assertEquals(Face.E, ProjectedBlockDataTransformer.mirrorDirection(Face.U, frame, 1, scratch));
        assertEquals(Face.D, ProjectedBlockDataTransformer.mirrorDirection(Face.E, frame, 1, scratch));
    }

    @Test
    public void imageHalfTurnControlsUpsideDownEntityState() {
        Frame wall = Frame.canonical(Face.N);
        assertFalse(PortalCoordMap.mirrorTransformFlipsWorldUp(wall, 0));
        assertTrue(PortalCoordMap.mirrorTransformFlipsWorldUp(wall, 2));
        assertTrue(PortalCoordMap.mirrorTransformFlipsWorldUp(Frame.canonical(Face.U), 0));
    }

    @Test
    public void coherentRotationPolicyOnlyOffersWorldUpRepresentableEntityStates() {
        Frame wall = Frame.canonical(Face.N);
        Frame floor = Frame.canonical(Face.U);
        double[] out = new double[3];
        for(QuarterTurn rotation : QuarterTurn.values()) {
            PortalCoordMap.mirrorSourceToDisplayVectorInto(0.0D, 1.0D, 0.0D, wall, rotation.getQuarterTurns(), out);
            boolean wallRepresentable = Math.abs(out[1]) > 0.5D;
            assertEquals(wallRepresentable, rotation == rotation.coherentFor(wall));

            PortalCoordMap.mirrorSourceToDisplayVectorInto(0.0D, 1.0D, 0.0D, floor, rotation.getQuarterTurns(), out);
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
