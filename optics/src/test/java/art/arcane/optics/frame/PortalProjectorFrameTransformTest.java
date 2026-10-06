package art.arcane.optics.frame;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import art.arcane.optics.math.Face;

public final class PortalProjectorFrameTransformTest {
    private static final Face[] NORMALS = new Face[] {
        Face.N, Face.S, Face.E, Face.W, Face.U, Face.D
    };
    private static final double[] SAMPLE_COORDS = new double[] {
        -37.5D, -8.5D, -0.5D, 0.0D, 0.5D, 12.5D, 63.5D, 128.5D
    };

    private static void referenceApply(Frame from,
                                       Frame to,
                                       double fromOriginX,
                                       double fromOriginY,
                                       double fromOriginZ,
                                       double toOriginX,
                                       double toOriginY,
                                       double toOriginZ,
                                       double x,
                                       double y,
                                       double z,
                                       double[] out3) {
        double offsetX = x - fromOriginX;
        double offsetY = y - fromOriginY;
        double offsetZ = z - fromOriginZ;
        double frameRight = (offsetX * from.getRight().x()) + (offsetY * from.getRight().y()) + (offsetZ * from.getRight().z());
        double frameUp = (offsetX * from.getUp().x()) + (offsetY * from.getUp().y()) + (offsetZ * from.getUp().z());
        double frameNormal = (offsetX * from.getNormal().x()) + (offsetY * from.getNormal().y()) + (offsetZ * from.getNormal().z());
        out3[0] = toOriginX + (frameRight * to.getRight().x()) + (frameUp * to.getUp().x()) + (frameNormal * to.getNormal().x());
        out3[1] = toOriginY + (frameRight * to.getRight().y()) + (frameUp * to.getUp().y()) + (frameNormal * to.getNormal().y());
        out3[2] = toOriginZ + (frameRight * to.getRight().z()) + (frameUp * to.getUp().z()) + (frameNormal * to.getNormal().z());
    }

    private static void referenceMirrorApply(Frame frame,
                                             int quarterTurns,
                                             double originX,
                                             double originY,
                                             double originZ,
                                             double x,
                                             double y,
                                             double z,
                                             double[] out3) {
        double[] scratch = new double[3];
        double offsetX = x - originX;
        double offsetY = y - originY;
        double offsetZ = z - originZ;
        PortalCoordMap.mirrorDisplayToSourceVectorInto(1.0D, 0.0D, 0.0D, frame, quarterTurns, scratch);
        double xx = scratch[0];
        double yx = scratch[1];
        double zx = scratch[2];
        PortalCoordMap.mirrorDisplayToSourceVectorInto(0.0D, 1.0D, 0.0D, frame, quarterTurns, scratch);
        double xy = scratch[0];
        double yy = scratch[1];
        double zy = scratch[2];
        PortalCoordMap.mirrorDisplayToSourceVectorInto(0.0D, 0.0D, 1.0D, frame, quarterTurns, scratch);
        double xz = scratch[0];
        double yz = scratch[1];
        double zz = scratch[2];
        out3[0] = originX + (offsetX * xx) + (offsetY * xy) + (offsetZ * xz);
        out3[1] = originY + (offsetX * yx) + (offsetY * yy) + (offsetZ * yz);
        out3[2] = originZ + (offsetX * zx) + (offsetY * zy) + (offsetZ * zz);
    }

    private static void assertSameBlock(double[] expected, double[] actual, String context) {
        for (int axis = 0; axis < 3; axis++) {
            assertEquals(expected[axis], actual[axis], 0.0D, context + " axis=" + axis);
            assertEquals((int) Math.floor(expected[axis]), (int) Math.floor(actual[axis]),
                context + " floored axis=" + axis);
        }
    }

    @Test
    public void hoistedTransformMatchesTheFrameProjectionForEveryCardinalFramePair() {
        double[] expected = new double[3];
        double[] actual = new double[3];
        ProjectorFrameTransform transform = new ProjectorFrameTransform();
        for (Frame from : frames()) {
            for (Frame to : frames()) {
                transform.configure(from, to, 12.5D, 64.5D, -3.5D, -220.5D, 71.5D, 811.5D);
                for (double x : SAMPLE_COORDS) {
                    for (double y : SAMPLE_COORDS) {
                        for (double z : SAMPLE_COORDS) {
                            referenceApply(from, to, 12.5D, 64.5D, -3.5D, -220.5D, 71.5D, 811.5D, x, y, z, expected);
                            transform.apply(x, y, z, actual);
                            assertSameBlock(expected, actual, from + "->" + to + " at " + x + "," + y + "," + z);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void hoistedMirrorTransformMatchesTheMirrorProjectionForEveryRotation() {
        double[] expected = new double[3];
        double[] actual = new double[3];
        ProjectorFrameTransform transform = new ProjectorFrameTransform();
        for (Frame frame : frames()) {
            for (int quarterTurns = -4; quarterTurns < 8; quarterTurns++) {
                transform.configureMirror(frame, quarterTurns, 12.5D, 64.5D, -3.5D);
                for (double x : SAMPLE_COORDS) {
                    for (double y : SAMPLE_COORDS) {
                        for (double z : SAMPLE_COORDS) {
                            referenceMirrorApply(frame, quarterTurns, 12.5D, 64.5D, -3.5D, x, y, z, expected);
                            transform.apply(x, y, z, actual);
                            assertSameBlock(expected, actual, frame + " turns=" + quarterTurns + " at " + x + "," + y + "," + z);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void reconfiguringSwitchesBetweenMirrorAndFrameTransformsCleanly() {
        double[] expected = new double[3];
        double[] actual = new double[3];
        Frame from = Frame.canonical(Face.N);
        Frame to = Frame.canonical(Face.E);
        ProjectorFrameTransform transform = new ProjectorFrameTransform();

        transform.configureMirror(from, 1, 4.5D, 70.5D, 9.5D);
        referenceMirrorApply(from, 1, 4.5D, 70.5D, 9.5D, 11.5D, 74.5D, 2.5D, expected);
        transform.apply(11.5D, 74.5D, 2.5D, actual);
        assertSameBlock(expected, actual, "mirror pass");

        transform.configure(from, to, 4.5D, 70.5D, 9.5D, -60.5D, 12.5D, 300.5D);
        referenceApply(from, to, 4.5D, 70.5D, 9.5D, -60.5D, 12.5D, 300.5D, 11.5D, 74.5D, 2.5D, expected);
        transform.apply(11.5D, 74.5D, 2.5D, actual);
        assertSameBlock(expected, actual, "frame pass after mirror");
    }

    @Test
    public void realPortalCenterOffsetsDoNotFloorAnExactBoundaryIntoThePreviousBlock() {
        Frame frame = Frame.canonical(Face.N);
        ProjectorFrameTransform transform = new ProjectorFrameTransform();
        double[] actual = new double[3];
        transform.configure(frame, frame,
            1.9995D, 66.4995D, 0.9995D,
            0.4995D, 66.4995D, 0.9995D);

        transform.apply(4.5D, 70.5D, -3.5D, actual);

        assertEquals(3.0D, actual[0], 0.0D);
        assertEquals(3, (int) Math.floor(actual[0]));

        transform.configure(frame, frame,
            -1.0005D, 66.4995D, 0.9995D,
            -2.5005D, 66.4995D, 0.9995D);
        transform.apply(1.5D, 70.5D, -3.5D, actual);

        assertEquals(0.0D, actual[0], 0.0D);
        assertEquals(0, (int) Math.floor(actual[0]));

        transform.configure(frame, frame,
            9_349_874.9995D, 64.4995D, 0.4995D,
            -16_777_220.5005D, 64.4995D, 0.4995D);
        transform.apply(9_349_898.5D, 64.5D, 0.5D, actual);

        assertEquals(-16_777_197.0D, actual[0], 0.0D);
        assertEquals(-16_777_197, (int) Math.floor(actual[0]));
    }

    @Test
    public void snappingPreservesCoordinatesOutsideTheBoundaryTolerance() {
        double tolerance = ProjectorFrameTransform.coordinateSnapTolerance(0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D);
        for (double boundary : new double[] {-12.0D, 0.0D, 12.0D}) {
            assertEquals(boundary, ProjectorFrameTransform.snapNearInteger(boundary - tolerance * 0.5D, tolerance), 0.0D);
            assertEquals(boundary, ProjectorFrameTransform.snapNearInteger(boundary + tolerance * 0.5D, tolerance), 0.0D);
            double below = boundary - tolerance * 2.0D;
            double above = boundary + tolerance * 2.0D;
            assertEquals(below, ProjectorFrameTransform.snapNearInteger(below, tolerance), 0.0D);
            assertEquals(above, ProjectorFrameTransform.snapNearInteger(above, tolerance), 0.0D);
        }
    }

    private static List<Frame> frames() {
        List<Frame> frames = new ArrayList<Frame>(24);
        for (Face normal : NORMALS) {
            for (Face up : NORMALS) {
                if (normal.getAxis() != up.getAxis()) {
                    frames.add(Frame.fromNormalUp(normal, up));
                }
            }
        }
        assertEquals(24, frames.size());
        return frames;
    }

}
