package art.arcane.optics.volume;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProjectorPlaneWindowTest {
    @Test
    void blockFootprintProtectsApertureWhenItsCenterRayMisses() {
        PlaneWindow window = window(1.0D, 2.0D, 5.0D);
        assertFalse(window.containsRayIntersection(0, 0, 5, 0.8D, 0, -1, -1));
        assertTrue(window.intersectsBlockSilhouette(0, 0, 5, 0.8D, 0, -1, -1));
        assertFalse(window.intersectsBlockSilhouette(0, 0, 5, 1.2D, 0, -1, -1));
        assertFalse(window.intersectsBlockSilhouette(0, 0, 5, 0, 2, -1, -1));
    }

    @Test
    void footprintSpanningApertureIsProtectedWhenEveryCornerMisses() {
        PlaneWindow window = window(0.2D, 0.2D, 5.0D);
        for (double x : new double[]{-0.5D, 0.5D}) {
            for (double y : new double[]{-0.5D, 0.5D}) {
                for (double z : new double[]{-1.5D, -0.5D}) {
                    assertFalse(window.containsRayIntersection(0, 0, 5, x, y, z, z));
                }
            }
        }
        assertTrue(window.intersectsBlockSilhouette(0, 0, 5, 0, 0, -1, -1));
    }

    @Test
    void obliqueViewsConsiderBothNormalFaces() {
        PlaneWindow window = window(1.0D, 2.0D, 2.0D);
        assertFalse(window.containsRayIntersection(5, 0, 2, -0.7D, 0, -1, -1));
        assertTrue(window.intersectsBlockSilhouette(5, 0, 2, -0.7D, 0, -1, -1));
        assertTrue(window.intersectsBlockSilhouette(5, 0, 2, -3, 0, -1, -1));
        assertFalse(window.intersectsBlockSilhouette(5, 0, 2, -5.5D, 0, -1, -1));
    }

    @Test
    void footprintWorksFromBothSidesAcrossEveryFrameOrientation() {
        for (Face normal : Face.values()) {
            Frame frame = Frame.canonical(normal);
            for (int rotation = 0; rotation < 4; rotation++, frame = frame.rotateClockwise()) {
                Face right = frame.getRight();
                for (double side : new double[]{-1.0D, 1.0D}) {
                    double eyeDistance = 5.0D * side;
                    double cellDistance = -side;
                    PlaneWindow window = PlaneWindow.create(null,
                        new Box(-0.5D, 0.5D, -0.5D, 0.5D, -0.5D, 0.5D),
                        frame, 0, 0, 0, 0, eyeDistance);
                    double eyeX = normal.x() * eyeDistance;
                    double eyeY = normal.y() * eyeDistance;
                    double eyeZ = normal.z() * eyeDistance;
                    double normalX = normal.x() * cellDistance;
                    double normalY = normal.y() * cellDistance;
                    double normalZ = normal.z() * cellDistance;
                    assertTrue(window.intersectsBlockSilhouette(eyeX, eyeY, eyeZ,
                        normalX + right.x() * 0.8D, normalY + right.y() * 0.8D,
                        normalZ + right.z() * 0.8D, cellDistance), normal.name());
                    assertFalse(window.intersectsBlockSilhouette(eyeX, eyeY, eyeZ,
                        normalX + right.x() * 1.2D, normalY + right.y() * 1.2D,
                        normalZ + right.z() * 1.2D, cellDistance), normal.name());
                }
            }
        }
    }

    @Test
    void degenerateEyePlaneAndEyeIntersectingBlockStayProtected() {
        assertTrue(window(1, 2, 0).intersectsBlockSilhouette(0, 0, 0, 100, 0, -1, -1));
        assertTrue(window(1, 2, 1.0E-8D).intersectsBlockSilhouette(0, 0, 1.0E-8D,
            100, 0, -1, -1));
        assertTrue(window(1, 2, 2).intersectsBlockSilhouette(0, 0, 2, 100, 0, 2, 2));
    }

    @Test
    void slabBoundsMatchCornerProjectionAcrossEveryOrientation() {
        Random random = new Random(73L);
        double[] actual = new double[4];
        for (Face normal : Face.values()) {
            Frame frame = Frame.canonical(normal);
            for (int rotation = 0; rotation < 4; rotation++, frame = frame.rotateClockwise()) {
                for (int sample = 0; sample < 64; sample++) {
                    double xa = random.nextDouble(-1000.0D, 1000.0D);
                    double ya = random.nextDouble(-1000.0D, 1000.0D);
                    double za = random.nextDouble(-1000.0D, 1000.0D);
                    Box area = new Box(xa, xa + random.nextDouble(0.001D, 20.0D),
                        ya, ya + random.nextDouble(0.001D, 20.0D), za, za + random.nextDouble(0.001D, 20.0D));
                    double originX = random.nextDouble(-1000.0D, 1000.0D);
                    double originY = random.nextDouble(-1000.0D, 1000.0D);
                    double originZ = random.nextDouble(-1000.0D, 1000.0D);
                    double eyeX = random.nextDouble(-1000.0D, 1000.0D);
                    double eyeY = random.nextDouble(-1000.0D, 1000.0D);
                    double eyeZ = random.nextDouble(-1000.0D, 1000.0D);
                    double padding = random.nextDouble(0.0D, 2.0D);
                    double eyeDistance = (eyeX - originX) * normal.x() + (eyeY - originY) * normal.y()
                        + (eyeZ - originZ) * normal.z();
                    double cellDistance = -eyeDistance * random.nextDouble(0.1D, 20.0D);
                    PlaneWindow window = PlaneWindow.create(null, area, frame,
                        originX, originY, originZ, padding, eyeDistance);
                    assertTrue(window.slabWindow(eyeX, eyeY, eyeZ, cellDistance, actual));
                    double[] expected = cornerSlabBounds(area, frame, originX, originY, originZ,
                        eyeX, eyeY, eyeZ, padding, eyeDistance, cellDistance);
                    assertArrayEquals(expected, actual, 0.0D);
                }
            }
        }
    }

    private static double[] cornerSlabBounds(Box area, Frame frame,
                                             double originX, double originY, double originZ,
                                             double eyeX, double eyeY, double eyeZ,
                                             double padding, double eyeDistance, double cellDistance) {
        double rightMin = Double.POSITIVE_INFINITY;
        double rightMax = Double.NEGATIVE_INFINITY;
        double upMin = Double.POSITIVE_INFINITY;
        double upMax = Double.NEGATIVE_INFINITY;
        Face right = frame.getRight();
        Face up = frame.getUp();
        for (int corner = 0; corner < 8; corner++) {
            double x = ((corner & 1) == 0 ? area.getXa() : area.getXb()) - originX;
            double y = ((corner & 2) == 0 ? area.getYa() : area.getYb()) - originY;
            double z = ((corner & 4) == 0 ? area.getZa() : area.getZb()) - originZ;
            double lateral = x * right.x() + y * right.y() + z * right.z();
            double vertical = x * up.x() + y * up.y() + z * up.z();
            rightMin = Math.min(rightMin, lateral);
            rightMax = Math.max(rightMax, lateral);
            upMin = Math.min(upMin, vertical);
            upMax = Math.max(upMax, vertical);
        }
        double eyeRight = (eyeX - originX) * right.x() + (eyeY - originY) * right.y() + (eyeZ - originZ) * right.z();
        double eyeUp = (eyeX - originX) * up.x() + (eyeY - originY) * up.y() + (eyeZ - originZ) * up.z();
        double t = -eyeDistance / (cellDistance - eyeDistance);
        return new double[] {
            eyeRight + (((rightMin - padding - 1.0E-7D) - eyeRight) / t),
            eyeRight + (((rightMax + padding + 1.0E-7D) - eyeRight) / t),
            eyeUp + (((upMin - padding - 1.0E-7D) - eyeUp) / t),
            eyeUp + (((upMax + padding + 1.0E-7D) - eyeUp) / t)
        };
    }

    private static PlaneWindow window(double width, double height, double eyeDistance) {
        return PlaneWindow.create(null,
            new Box(-width * 0.5D, width * 0.5D, -height * 0.5D, height * 0.5D, -0.5D, 0.5D),
            Frame.canonical(Face.S), 0, 0, 0, 0, eyeDistance);
    }
}
