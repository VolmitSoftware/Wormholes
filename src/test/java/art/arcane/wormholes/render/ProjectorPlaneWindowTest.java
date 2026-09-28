package art.arcane.wormholes.render;

import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProjectorPlaneWindowTest {
    @Test
    void blockFootprintProtectsApertureWhenItsCenterRayMisses() {
        ProjectorPlaneWindow window = window(1.0D, 2.0D, 5.0D);
        assertFalse(window.containsRayIntersection(0, 0, 5, 0.8D, 0, -1, -1));
        assertTrue(window.intersectsBlockSilhouette(0, 0, 5, 0.8D, 0, -1, -1));
        assertFalse(window.intersectsBlockSilhouette(0, 0, 5, 1.2D, 0, -1, -1));
        assertFalse(window.intersectsBlockSilhouette(0, 0, 5, 0, 2, -1, -1));
    }

    @Test
    void footprintSpanningApertureIsProtectedWhenEveryCornerMisses() {
        ProjectorPlaneWindow window = window(0.2D, 0.2D, 5.0D);
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
        ProjectorPlaneWindow window = window(1.0D, 2.0D, 2.0D);
        assertFalse(window.containsRayIntersection(5, 0, 2, -0.7D, 0, -1, -1));
        assertTrue(window.intersectsBlockSilhouette(5, 0, 2, -0.7D, 0, -1, -1));
        assertTrue(window.intersectsBlockSilhouette(5, 0, 2, -3, 0, -1, -1));
        assertFalse(window.intersectsBlockSilhouette(5, 0, 2, -5.5D, 0, -1, -1));
    }

    @Test
    void footprintWorksFromBothSidesAcrossEveryFrameOrientation() {
        for (Direction normal : Direction.values()) {
            PortalFrame frame = PortalFrame.canonical(normal);
            for (int rotation = 0; rotation < 4; rotation++, frame = frame.rotateClockwise()) {
                Direction right = frame.getRight();
                for (double side : new double[]{-1.0D, 1.0D}) {
                    double eyeDistance = 5.0D * side;
                    double cellDistance = -side;
                    ProjectorPlaneWindow window = ProjectorPlaneWindow.create(null,
                        new AxisAlignedBB(-0.5D, 0.5D, -0.5D, 0.5D, -0.5D, 0.5D),
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

    private static ProjectorPlaneWindow window(double width, double height, double eyeDistance) {
        return ProjectorPlaneWindow.create(null,
            new AxisAlignedBB(-width * 0.5D, width * 0.5D, -height * 0.5D, height * 0.5D, -0.5D, 0.5D),
            PortalFrame.canonical(Direction.S), 0, 0, 0, 0, eyeDistance);
    }
}
