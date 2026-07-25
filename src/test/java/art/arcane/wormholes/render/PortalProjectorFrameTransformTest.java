package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.util.Direction;

public final class PortalProjectorFrameTransformTest {
    private static final Direction[] NORMALS = new Direction[] {
        Direction.N, Direction.S, Direction.E, Direction.W, Direction.U, Direction.D
    };
    private static final double[] SAMPLE_COORDS = new double[] {
        -37.5D, -8.5D, -0.5D, 0.0D, 0.5D, 12.5D, 63.5D, 128.5D
    };

    private static void referenceApply(PortalFrame from,
                                       PortalFrame to,
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

    private static void referenceMirrorApply(PortalFrame frame,
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
        for (Direction fromNormal : NORMALS) {
            PortalFrame from = PortalFrame.canonical(fromNormal);
            for (Direction toNormal : NORMALS) {
                PortalFrame to = PortalFrame.canonical(toNormal);
                transform.configure(from, to, 12.5D, 64.5D, -3.5D, -220.5D, 71.5D, 811.5D);
                assertTrue(transform.signedPermutation,
                    "cardinal frames must take the signed permutation path: " + fromNormal + " -> " + toNormal);
                for (double x : SAMPLE_COORDS) {
                    for (double y : SAMPLE_COORDS) {
                        for (double z : SAMPLE_COORDS) {
                            referenceApply(from, to, 12.5D, 64.5D, -3.5D, -220.5D, 71.5D, 811.5D, x, y, z, expected);
                            transform.apply(x, y, z, actual);
                            assertSameBlock(expected, actual, fromNormal + "->" + toNormal + " at " + x + "," + y + "," + z);
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
        double[] scratch = new double[3];
        ProjectorFrameTransform transform = new ProjectorFrameTransform();
        for (Direction normal : NORMALS) {
            PortalFrame frame = PortalFrame.canonical(normal);
            for (int quarterTurns = 0; quarterTurns < 4; quarterTurns++) {
                transform.configureMirror(frame, quarterTurns, 12.5D, 64.5D, -3.5D, scratch);
                assertTrue(transform.signedPermutation,
                    "mirror transforms must take the signed permutation path: " + normal + " turns=" + quarterTurns);
                for (double x : SAMPLE_COORDS) {
                    for (double y : SAMPLE_COORDS) {
                        for (double z : SAMPLE_COORDS) {
                            referenceMirrorApply(frame, quarterTurns, 12.5D, 64.5D, -3.5D, x, y, z, expected);
                            transform.apply(x, y, z, actual);
                            assertSameBlock(expected, actual, normal + " turns=" + quarterTurns + " at " + x + "," + y + "," + z);
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
        double[] scratch = new double[3];
        PortalFrame from = PortalFrame.canonical(Direction.N);
        PortalFrame to = PortalFrame.canonical(Direction.E);
        ProjectorFrameTransform transform = new ProjectorFrameTransform();

        transform.configureMirror(from, 1, 4.5D, 70.5D, 9.5D, scratch);
        referenceMirrorApply(from, 1, 4.5D, 70.5D, 9.5D, 11.5D, 74.5D, 2.5D, expected);
        transform.apply(11.5D, 74.5D, 2.5D, actual);
        assertSameBlock(expected, actual, "mirror pass");

        transform.configure(from, to, 4.5D, 70.5D, 9.5D, -60.5D, 12.5D, 300.5D);
        referenceApply(from, to, 4.5D, 70.5D, 9.5D, -60.5D, 12.5D, 300.5D, 11.5D, 74.5D, 2.5D, expected);
        transform.apply(11.5D, 74.5D, 2.5D, actual);
        assertSameBlock(expected, actual, "frame pass after mirror");
    }
}
