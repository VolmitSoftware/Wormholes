package art.arcane.optics.occlusion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;

final class ProjectorHoldProofTest {
    private static final double PADDING = 0.75D;
    private static final int SAMPLES_PER_FRAME = 400;
    private static final double BOUNDARY_MARGIN = 1.0E-3D;
    private static final double[] CELL_SAMPLE_OFFSETS = {0.002D, 0.25D, 0.5D, 0.75D, 0.998D};

    @Test
    void holdIsNeverGivenWhenABruteForceRayMarchReachesTheCell() {
        Random random = new Random(7L);
        int holds = 0;
        int samples = 0;
        for (Frame frame : frames()) {
            for (int sample = 0; sample < SAMPLES_PER_FRAME; sample++) {
                Scene scene = Scene.random(random, frame, PADDING, false, true, true);
                ProjectorHoldProof.Verdict verdict = scene.verdict();
                boolean hidden = scene.rayMarchHidden();
                if (verdict == ProjectorHoldProof.Verdict.HOLD) {
                    assertTrue(hidden, scene.describe(verdict));
                    assertEquals(ProjectorHoldProof.Occupancy.OCCLUDING, scene.crossingOccupancy(), scene.describe(verdict));
                    holds++;
                }
                if (!hidden) {
                    assertNotEquals(ProjectorHoldProof.Verdict.HOLD, verdict, scene.describe(verdict));
                }
                samples++;
            }
        }
        assertTrue(holds > samples / 40, "holds=" + holds + " samples=" + samples);
    }

    @Test
    void holdHidesEveryRayToTheCellAtEveryAperturePadding() {
        Random random = new Random(29L);
        for (double padding : new double[] {0.0D, 0.25D, 0.5D, 0.75D, 1.5D}) {
            int holds = 0;
            for (Frame frame : frames()) {
                for (int sample = 0; sample < SAMPLES_PER_FRAME; sample++) {
                    Scene scene = Scene.random(random, frame, padding, sample % 2 == 0, true, true);
                    ProjectorHoldProof.Verdict verdict = scene.verdict();
                    if (verdict == ProjectorHoldProof.Verdict.HOLD) {
                        assertTrue(scene.everyCellRayHidden(), scene.describe(verdict));
                        holds++;
                    }
                }
            }
            assertTrue(holds > 0, "padding=" + padding + " holds=" + holds);
        }
    }

    @Test
    void solidWallHoldsExactlyTheCellsWhoseCrossingLeavesThePaddedWindow() {
        Random random = new Random(11L);
        int holds = 0;
        int inWindow = 0;
        for (Frame frame : frames()) {
            for (int sample = 0; sample < SAMPLES_PER_FRAME; sample++) {
                Scene scene = Scene.random(random, frame, PADDING, true, true, true);
                ProjectorHoldProof.Verdict verdict = scene.verdict();
                if (scene.crossingMarginFromPaddedWindow() < BOUNDARY_MARGIN) {
                    continue;
                }
                if (scene.crossingInsidePaddedWindow()) {
                    assertEquals(ProjectorHoldProof.Verdict.IN_WINDOW, verdict, scene.describe(verdict));
                    inWindow++;
                } else {
                    assertEquals(ProjectorHoldProof.Verdict.HOLD, verdict, scene.describe(verdict));
                    assertTrue(scene.rayMarchHidden(), scene.describe(verdict));
                    holds++;
                }
            }
        }
        assertTrue(holds > 0, "holds=" + holds);
        assertTrue(inWindow > 0, "inWindow=" + inWindow);
    }

    @Test
    void backSideEyesAndCellsOnTheEyeSideNeverHold() {
        Random random = new Random(23L);
        for (Frame frame : frames()) {
            for (int sample = 0; sample < SAMPLES_PER_FRAME / 4; sample++) {
                Scene eyeBehind = Scene.random(random, frame, PADDING, true, false, true);
                assertFalse(eyeBehind.proof.beginEye(eyeBehind.eyeX, eyeBehind.eyeY, eyeBehind.eyeZ));
                assertEquals(ProjectorHoldProof.Verdict.BACK_SIDE, eyeBehind.verdict(), eyeBehind.describe(null));

                Scene cellInFront = Scene.random(random, frame, PADDING, true, true, false);
                assertTrue(cellInFront.proof.beginEye(cellInFront.eyeX, cellInFront.eyeY, cellInFront.eyeZ));
                assertEquals(ProjectorHoldProof.Verdict.BACK_SIDE, cellInFront.verdict(), cellInFront.describe(null));

                Scene onPlane = Scene.random(random, frame, PADDING, true, true, true);
                double[] eye = {onPlane.eyeX, onPlane.eyeY, onPlane.eyeZ};
                eye[onPlane.normalAxis] = onPlane.planeCoord + 0.5D;
                assertFalse(onPlane.proof.beginEye(eye[0], eye[1], eye[2]));
                eye[onPlane.normalAxis] = onPlane.planeCoord + 0.5D + (onPlane.normalSign * 0.4D);
                assertFalse(onPlane.proof.beginEye(eye[0], eye[1], eye[2]));
                assertEquals(ProjectorHoldProof.Verdict.BACK_SIDE,
                    onPlane.proof.verdict(onPlane.cellX, onPlane.cellY, onPlane.cellZ, onPlane::occupancy));
            }
        }
    }

    @Test
    void openOrUnknownCrossingNeighbourhoodReverts() {
        Frame frame = Frame.canonical(direction(1, 0, 0));
        Box area = new Box(0.0D, 1.0D, 0.0D, 3.0D, 0.0D, 3.0D);
        ProjectorHoldProof proof = ProjectorHoldProof.create(area, frame, 0.5D, 1.5D, 1.5D, PADDING);
        assertTrue(proof.beginEye(4.5D, 1.5D, 1.5D));

        EditableWall wall = new EditableWall();
        assertEquals(ProjectorHoldProof.Verdict.HOLD, proof.verdict(-3, 1, 8, wall));
        assertEquals(ProjectorHoldProof.Verdict.IN_WINDOW, proof.verdict(-3, 1, 4, wall));
        assertEquals(ProjectorHoldProof.Verdict.HOLD, proof.verdict(-3, 1, 5, wall));

        wall.set(0, 1, 7, ProjectorHoldProof.Occupancy.OPEN);
        assertEquals(ProjectorHoldProof.Verdict.HOLD, proof.verdict(-3, 1, 8, wall));

        wall.set(0, 1, 5, ProjectorHoldProof.Occupancy.OPEN);
        assertEquals(ProjectorHoldProof.Verdict.OPEN, proof.verdict(-3, 1, 8, wall));
        wall.set(0, 1, 5, ProjectorHoldProof.Occupancy.UNKNOWN);
        assertEquals(ProjectorHoldProof.Verdict.UNKNOWN, proof.verdict(-3, 1, 8, wall));
        wall.set(0, 1, 5, ProjectorHoldProof.Occupancy.OCCLUDING);

        wall.set(0, 2, 6, ProjectorHoldProof.Occupancy.OPEN);
        assertEquals(ProjectorHoldProof.Verdict.OPEN, proof.verdict(-3, 1, 8, wall));
        wall.set(0, 2, 6, ProjectorHoldProof.Occupancy.OCCLUDING);

        wall.set(0, 0, 4, ProjectorHoldProof.Occupancy.UNKNOWN);
        assertEquals(ProjectorHoldProof.Verdict.UNKNOWN, proof.verdict(-3, 1, 8, wall));
        wall.set(0, 0, 4, ProjectorHoldProof.Occupancy.OCCLUDING);
        assertEquals(ProjectorHoldProof.Verdict.HOLD, proof.verdict(-3, 1, 8, wall));

        ProjectorHoldProof unpadded = ProjectorHoldProof.create(area, frame, 0.5D, 1.5D, 1.5D, 0.0D);
        assertTrue(unpadded.beginEye(4.5D, 1.5D, 1.5D));
        wall.set(0, 2, 6, ProjectorHoldProof.Occupancy.OPEN);
        assertEquals(ProjectorHoldProof.Verdict.HOLD, unpadded.verdict(-3, 1, 8, wall));
        assertEquals(ProjectorHoldProof.Verdict.IN_WINDOW, unpadded.verdict(-3, 1, 4, wall));
    }

    private static List<Frame> frames() {
        List<Frame> frames = new ArrayList<Frame>(24);
        for (Face normal : Face.values()) {
            Frame frame = Frame.canonical(normal);
            for (int rotation = 0; rotation < 4; rotation++) {
                frames.add(frame);
                frame = frame.rotateClockwise();
            }
        }
        return frames;
    }

    private static Face direction(int x, int y, int z) {
        for (Face direction : Face.values()) {
            if (direction.x() == x && direction.y() == y && direction.z() == z) {
                return direction;
            }
        }
        throw new IllegalArgumentException(x + "," + y + "," + z);
    }

    private static final class EditableWall implements ProjectorHoldProof.LocalOccupancy {
        private final List<int[]> cells = new ArrayList<int[]>();
        private final List<ProjectorHoldProof.Occupancy> values = new ArrayList<ProjectorHoldProof.Occupancy>();

        private void set(int x, int y, int z, ProjectorHoldProof.Occupancy occupancy) {
            for (int index = 0; index < cells.size(); index++) {
                int[] cell = cells.get(index);
                if (cell[0] == x && cell[1] == y && cell[2] == z) {
                    values.set(index, occupancy);
                    return;
                }
            }
            cells.add(new int[] {x, y, z});
            values.add(occupancy);
        }

        @Override
        public ProjectorHoldProof.Occupancy occupancy(int x, int y, int z) {
            for (int index = 0; index < cells.size(); index++) {
                int[] cell = cells.get(index);
                if (cell[0] == x && cell[1] == y && cell[2] == z) {
                    return values.get(index);
                }
            }
            if (x != 0) {
                return ProjectorHoldProof.Occupancy.OPEN;
            }
            if (y >= 0 && y <= 2 && z >= 0 && z <= 2) {
                return ProjectorHoldProof.Occupancy.OPEN;
            }
            return ProjectorHoldProof.Occupancy.OCCLUDING;
        }
    }

    private static final class Scene {
        private final Frame frame;
        private final int normalAxis;
        private final int normalSign;
        private final int planeCoord;
        private final int firstAxis;
        private final int secondAxis;
        private final int firstMin;
        private final int firstMax;
        private final int secondMin;
        private final int secondMax;
        private final double[] origin;
        private final double padding;
        private final boolean solid;
        private final long wallSeed;
        private final ProjectorHoldProof proof;
        private final double eyeX;
        private final double eyeY;
        private final double eyeZ;
        private final int cellX;
        private final int cellY;
        private final int cellZ;

        private Scene(Frame frame, double padding, int planeCoord, int firstMin, int firstMax, int secondMin, int secondMax,
                      boolean solid, long wallSeed, double[] eye, int[] cell) {
            this.frame = frame;
            this.padding = padding;
            Face normal = frame.getNormal();
            this.normalAxis = normal.x() != 0 ? 0 : normal.y() != 0 ? 1 : 2;
            this.normalSign = normal.x() + normal.y() + normal.z();
            this.planeCoord = planeCoord;
            this.firstAxis = (normalAxis + 1) % 3;
            this.secondAxis = (normalAxis + 2) % 3;
            this.firstMin = firstMin;
            this.firstMax = firstMax;
            this.secondMin = secondMin;
            this.secondMax = secondMax;
            this.solid = solid;
            this.wallSeed = wallSeed;
            double[] min = new double[3];
            double[] max = new double[3];
            min[normalAxis] = planeCoord;
            max[normalAxis] = planeCoord + 1;
            min[firstAxis] = firstMin;
            max[firstAxis] = firstMax + 1;
            min[secondAxis] = secondMin;
            max[secondAxis] = secondMax + 1;
            this.origin = new double[] {(min[0] + max[0]) * 0.5D, (min[1] + max[1]) * 0.5D, (min[2] + max[2]) * 0.5D};
            Box area = new Box(min[0], max[0], min[1], max[1], min[2], max[2]);
            this.proof = ProjectorHoldProof.create(area, frame, origin[0], origin[1], origin[2], padding);
            this.eyeX = eye[0];
            this.eyeY = eye[1];
            this.eyeZ = eye[2];
            this.cellX = cell[0];
            this.cellY = cell[1];
            this.cellZ = cell[2];
        }

        private static Scene random(Random random, Frame frame, double padding, boolean solid, boolean eyeInFront, boolean cellBehind) {
            Face normal = frame.getNormal();
            int normalAxis = normal.x() != 0 ? 0 : normal.y() != 0 ? 1 : 2;
            int normalSign = normal.x() + normal.y() + normal.z();
            int firstAxis = (normalAxis + 1) % 3;
            int secondAxis = (normalAxis + 2) % 3;
            int planeCoord = random.nextInt(81) - 40;
            int firstMin = random.nextInt(21) - 10;
            int firstMax = firstMin + random.nextInt(5);
            int secondMin = random.nextInt(21) - 10;
            int secondMax = secondMin + random.nextInt(5);
            double firstCenter = (firstMin + firstMax + 1) * 0.5D;
            double secondCenter = (secondMin + secondMax + 1) * 0.5D;
            double[] eye = new double[3];
            double eyeOffset = 0.6D + (random.nextDouble() * 8.0D);
            eye[normalAxis] = planeCoord + 0.5D + (eyeInFront ? normalSign * eyeOffset : -normalSign * eyeOffset);
            eye[firstAxis] = firstCenter + (random.nextDouble() * 24.0D) - 12.0D;
            eye[secondAxis] = secondCenter + (random.nextDouble() * 24.0D) - 12.0D;
            int[] cell = new int[3];
            int depth = 1 + random.nextInt(30);
            cell[normalAxis] = cellBehind ? planeCoord - (normalSign * depth) : planeCoord + (normalSign * depth);
            cell[firstAxis] = (int) Math.floor(firstCenter) + random.nextInt(61) - 30;
            cell[secondAxis] = (int) Math.floor(secondCenter) + random.nextInt(61) - 30;
            return new Scene(frame, padding, planeCoord, firstMin, firstMax, secondMin, secondMax, solid, random.nextLong(), eye, cell);
        }

        private ProjectorHoldProof.Verdict verdict() {
            proof.beginEye(eyeX, eyeY, eyeZ);
            return proof.verdict(cellX, cellY, cellZ, this::occupancy);
        }

        private ProjectorHoldProof.Occupancy occupancy(int x, int y, int z) {
            int[] coordinates = {x, y, z};
            if (coordinates[normalAxis] != planeCoord) {
                return ProjectorHoldProof.Occupancy.OPEN;
            }
            int first = coordinates[firstAxis];
            int second = coordinates[secondAxis];
            if (first >= firstMin && first <= firstMax && second >= secondMin && second <= secondMax) {
                return ProjectorHoldProof.Occupancy.OPEN;
            }
            if (solid) {
                return ProjectorHoldProof.Occupancy.OCCLUDING;
            }
            long hash = wallSeed;
            hash ^= first * 0x9E3779B97F4A7C15L;
            hash = Long.rotateLeft(hash, 27) * 0xBF58476D1CE4E5B9L;
            hash ^= second * 0x94D049BB133111EBL;
            hash = Long.rotateLeft(hash, 31) * 0x9E3779B97F4A7C15L;
            hash ^= hash >>> 29;
            long bucket = Math.floorMod(hash, 10L);
            if (bucket < 7L) {
                return ProjectorHoldProof.Occupancy.OCCLUDING;
            }
            return bucket < 9L ? ProjectorHoldProof.Occupancy.OPEN : ProjectorHoldProof.Occupancy.UNKNOWN;
        }

        private double[] crossing() {
            double[] eye = {eyeX, eyeY, eyeZ};
            double[] center = {cellX + 0.5D, cellY + 0.5D, cellZ + 0.5D};
            double plane = planeCoord + 0.5D;
            double t = (plane - eye[normalAxis]) / (center[normalAxis] - eye[normalAxis]);
            return new double[] {
                eye[0] + ((center[0] - eye[0]) * t),
                eye[1] + ((center[1] - eye[1]) * t),
                eye[2] + ((center[2] - eye[2]) * t)
            };
        }

        private boolean crossingInsidePaddedWindow() {
            double[] hit = crossing();
            return hit[firstAxis] >= firstMin - padding && hit[firstAxis] <= firstMax + 1 + padding
                && hit[secondAxis] >= secondMin - padding && hit[secondAxis] <= secondMax + 1 + padding;
        }

        private double crossingMarginFromPaddedWindow() {
            double[] hit = crossing();
            double firstMargin = Math.min(Math.abs(hit[firstAxis] - (firstMin - padding)),
                Math.abs(hit[firstAxis] - (firstMax + 1 + padding)));
            double secondMargin = Math.min(Math.abs(hit[secondAxis] - (secondMin - padding)),
                Math.abs(hit[secondAxis] - (secondMax + 1 + padding)));
            return Math.min(firstMargin, secondMargin);
        }

        private ProjectorHoldProof.Occupancy crossingOccupancy() {
            double[] hit = crossing();
            int[] block = {(int) Math.floor(hit[0]), (int) Math.floor(hit[1]), (int) Math.floor(hit[2])};
            block[normalAxis] = planeCoord;
            return occupancy(block[0], block[1], block[2]);
        }

        private boolean rayMarchHidden() {
            return rayMarchHidden(cellX + 0.5D, cellY + 0.5D, cellZ + 0.5D);
        }

        private boolean everyCellRayHidden() {
            for (double offsetX : CELL_SAMPLE_OFFSETS) {
                for (double offsetY : CELL_SAMPLE_OFFSETS) {
                    for (double offsetZ : CELL_SAMPLE_OFFSETS) {
                        if (!rayMarchHidden(cellX + offsetX, cellY + offsetY, cellZ + offsetZ)) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        private boolean rayMarchHidden(double targetX, double targetY, double targetZ) {
            double startX = eyeX;
            double startY = eyeY;
            double startZ = eyeZ;
            double deltaX = targetX - startX;
            double deltaY = targetY - startY;
            double deltaZ = targetZ - startZ;
            int x = (int) Math.floor(startX);
            int y = (int) Math.floor(startY);
            int z = (int) Math.floor(startZ);
            int stepX = deltaX > 0.0D ? 1 : deltaX < 0.0D ? -1 : 0;
            int stepY = deltaY > 0.0D ? 1 : deltaY < 0.0D ? -1 : 0;
            int stepZ = deltaZ > 0.0D ? 1 : deltaZ < 0.0D ? -1 : 0;
            double tMaxX = stepX == 0 ? Double.POSITIVE_INFINITY : (stepX > 0 ? (x + 1) - startX : startX - x) / Math.abs(deltaX);
            double tMaxY = stepY == 0 ? Double.POSITIVE_INFINITY : (stepY > 0 ? (y + 1) - startY : startY - y) / Math.abs(deltaY);
            double tMaxZ = stepZ == 0 ? Double.POSITIVE_INFINITY : (stepZ > 0 ? (z + 1) - startZ : startZ - z) / Math.abs(deltaZ);
            double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : 1.0D / Math.abs(deltaX);
            double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : 1.0D / Math.abs(deltaY);
            double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 1.0D / Math.abs(deltaZ);
            for (int guard = 0; guard < 4096; guard++) {
                if (x == cellX && y == cellY && z == cellZ) {
                    return false;
                }
                if (occupancy(x, y, z) == ProjectorHoldProof.Occupancy.OCCLUDING) {
                    return true;
                }
                double next = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
                if (next > 1.0D) {
                    return false;
                }
                if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                    x += stepX;
                    tMaxX += tDeltaX;
                } else if (tMaxY <= tMaxZ) {
                    y += stepY;
                    tMaxY += tDeltaY;
                } else {
                    z += stepZ;
                    tMaxZ += tDeltaZ;
                }
            }
            throw new AssertionError("ray march did not terminate " + describe(null));
        }

        private String describe(ProjectorHoldProof.Verdict verdict) {
            double[] hit = crossing();
            return "normal=" + frame.getNormal() + " right=" + frame.getRight() + " up=" + frame.getUp()
                + " plane=" + planeCoord + " aperture=[" + firstMin + ".." + firstMax + "]x[" + secondMin + ".." + secondMax + "]"
                + " origin=(" + origin[0] + "," + origin[1] + "," + origin[2] + ")"
                + " eye=(" + eyeX + "," + eyeY + "," + eyeZ + ")"
                + " cell=(" + cellX + "," + cellY + "," + cellZ + ")"
                + " crossing=(" + hit[0] + "," + hit[1] + "," + hit[2] + ")"
                + " padding=" + padding + " solid=" + solid + " verdict=" + verdict;
        }
    }
}
