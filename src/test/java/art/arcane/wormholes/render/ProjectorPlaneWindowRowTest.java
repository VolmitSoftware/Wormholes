package art.arcane.wormholes.render;

import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProjectorPlaneWindowRowTest {
    @Test
    void preparedRowsMatchScalarRaysAcrossFramesAndApertureHoles() {
        Random random = new Random(79584L);
        for (Direction normal : new Direction[]{Direction.N, Direction.S, Direction.E, Direction.W, Direction.U, Direction.D}) {
            PortalFrame frame = PortalFrame.canonical(normal);
            int normalAxis = axis(normal);
            for (int rotation = 0; rotation < 4; rotation++, frame = frame.rotateClockwise()) {
                for (double offset : new double[]{0.0D, -30_000_000.0D, 30_000_000.0D}) {
                    double[] origin = new double[]{offset + 0.5D, 64.5D, offset + 0.5D};
                    AxisAlignedBB area = new AxisAlignedBB(
                        origin[0] - (normalAxis == 0 ? 0.5D : 4.5D), origin[0] + (normalAxis == 0 ? 0.5D : 4.5D),
                        origin[1] - (normalAxis == 1 ? 0.5D : 4.5D), origin[1] + (normalAxis == 1 ? 0.5D : 4.5D),
                        origin[2] - (normalAxis == 2 ? 0.5D : 4.5D), origin[2] + (normalAxis == 2 ? 0.5D : 4.5D));
                    for (int sample = 0; sample < 40; sample++) {
                        double sign = (sample & 1) == 0 ? 1.0D : -1.0D;
                        double eyeDistance = sign * (sample % 8 == 0 ? 1.0E-8D : 0.05D + random.nextDouble() * 10.0D);
                        double[] eye = origin.clone();
                        eye[normalAxis] += eyeDistance;
                        int variableAxis = (normalAxis + 1 + (sample % 2)) % 3;
                        int fixedAxis = 3 - normalAxis - variableAxis;
                        eye[fixedAxis] += random.nextDouble() * 6.0D - 3.0D;
                        double[] point = origin.clone();
                        point[normalAxis] -= sign * (0.1D + random.nextDouble() * 64.0D);
                        point[fixedAxis] += random.nextDouble() * 80.0D - 40.0D;
                        double facing = normal.x() + normal.y() + normal.z();
                        ProjectorPlaneWindow window = ProjectorPlaneWindow.create(new HoledStructure(), area, frame,
                            origin[0], origin[1], origin[2], new double[]{0.0D, 0.05D, 0.5D, 0.75D, 1.0D}[sample % 5], eyeDistance * facing);
                        double cellDistance = (point[normalAxis] - origin[normalAxis]) * facing;
                        window.prepareRow(variableAxis, eye[0], eye[1], eye[2], point[0], point[1], point[2], cellDistance);
                        int center = (int) Math.floor(origin[variableAxis]);
                        for (int coordinate = center - 96; coordinate <= center + 96; coordinate++) {
                            point[variableAxis] = coordinate + 0.5D;
                            assertEquals(window.containsRayIntersection(eye[0], eye[1], eye[2], point[0], point[1], point[2], cellDistance),
                                window.containsRowCell(coordinate), "normal=" + normal + " rotation=" + rotation + " sample=" + sample + " coordinate=" + coordinate);
                        }
                    }
                }
            }
        }
    }

    @Test
    void repeatedPortalHitCellsReuseMembershipChecks() {
        HoledStructure structure = new HoledStructure();
        ProjectorPlaneWindow window = ProjectorPlaneWindow.create(structure,
            new AxisAlignedBB(-4, 4, 64, 65, -4, 4), PortalFrame.canonical(Direction.D),
            0, 64.5D, 0, 0.75D, -0.1D);
        window.prepareRow(0, 0, 64.6D, 0, 0, 0.5D, 0, 64.0D);
        for (int coordinate = -96; coordinate <= 96; coordinate++) {
            window.containsRowCell(coordinate);
        }
        assertTrue(structure.lookups < 20, "lookups=" + structure.lookups);
    }

    private static int axis(Direction direction) {
        return direction.x() != 0 ? 0 : direction.y() != 0 ? 1 : 2;
    }

    private static final class HoledStructure extends PortalStructure {
        private int lookups;

        @Override
        public boolean isFullCuboid() {
            return false;
        }

        @Override
        public boolean containsBlock(int x, int y, int z) {
            lookups++;
            return Math.floorMod(x * 31L + y * 17L + z * 7L, 5L) <= 1L;
        }
    }
}
