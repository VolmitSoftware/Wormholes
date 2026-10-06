package art.arcane.optics.volume;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.aperture.Aperture;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProjectorFrustumRowTest {
    private double aperturePadding = 0.75D;
    private double nearPadding = 2.0D;
    private double cullingRatio = 0.2D;

    @Test
    void unionRowsMatchScalarAcrossOrientationsCoordinatesAndPadding() {
        double previousAperturePadding = aperturePadding;
        double previousNearPadding = nearPadding;
        Random random = new Random(473_816L);
        ProjectorFrustumRow row = new ProjectorFrustumRow();
        int preparedRows = 0;
        int fallbackRows = 0;
        int acceptedCells = 0;
        try {
            for (Face normal : Face.values()) {
                Frame frame = Frame.canonical(normal);
                for (int rotation = 0; rotation < 4; rotation++, frame = frame.rotateClockwise()) {
                    int rowAxis = frame.getUp().getAxis().ordinal();
                    for (double offset : new double[] {-30_000_000.0D, 0.0D, 30_000_000.0D}) {
                        TestStructure structure = irregularStructure(frame, offset);
                        for (int sample = 0; sample < 24; sample++) {
                            aperturePadding = new double[] {0.0D, 1.0E-7D, 0.35D, 1.0D}[sample % 4];
                            nearPadding = new double[] {0.0D, 0.01D, 2.0D}[sample % 3];
                            double distance = new double[] {0.0D, 1.0E-8D, 0.05D, 0.25D, 1.5D, 8.0D}[sample % 6];
                            Vec3d center = structure.getApertureCenter();
                            Vec3d eye = center.add(new Vec3d(normal.x() * distance, normal.y() * distance, normal.z() * distance));
                            if (sample >= 12) {
                                eye = eye.add(new Vec3d(frame.getUp().x() * 7.0D, frame.getUp().y() * 7.0D, frame.getUp().z() * 7.0D));
                            }
                            ViewVolume frustum = new ViewVolume(eye, structure, new ViewVolume.Options(64.0D, 48.0D, nearPadding, cullingRatio, aperturePadding));
                            for (int index = 0; index < 8; index++) {
                                double depth = 0.5D + random.nextInt(64);
                                double lateral = random.nextInt(65) - 32.0D;
                                double[] point = new double[] {
                                    center.getX() - normal.x() * depth + frame.getRight().x() * lateral,
                                    center.getY() - normal.y() * depth + frame.getRight().y() * lateral,
                                    center.getZ() - normal.z() * depth + frame.getRight().z() * lateral
                                };
                                int middle = (int) Math.floor(point[rowAxis]);
                                int start = middle - 64;
                                int end = middle + 64;
                                boolean prepared = row.prepare(frustum, rowAxis, point[0], point[1], point[2],
                                    (index & 1) == 0 ? start : end, (index & 1) == 0 ? end : start);
                                if (prepared) {
                                    preparedRows++;
                                } else {
                                    fallbackRows++;
                                }
                                for (int coordinate = start; coordinate <= end; coordinate++) {
                                    point[rowAxis] = coordinate + 0.5D;
                                    boolean scalar = frustum.containsPrimitive(point[0], point[1], point[2]);
                                    if (prepared) {
                                        assertEquals(scalar, row.contains(coordinate),
                                            normal + " rotation=" + rotation + " sample=" + sample + " coordinate=" + coordinate);
                                        if (scalar) {
                                            acceptedCells++;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            aperturePadding = previousAperturePadding;
            nearPadding = previousNearPadding;
        }
        assertTrue(preparedRows > 5_000, "prepared=" + preparedRows);
        assertTrue(fallbackRows > 0, "fallback=" + fallbackRows);
        assertTrue(acceptedCells > 10_000, "accepted=" + acceptedCells);
    }

    @Test
    void separatedAperturesKeepTheirGapAndReusedRowsClearOldBits() {
        double previousAperturePadding = aperturePadding;
        double previousNearPadding = nearPadding;
        try {
            aperturePadding = 0.0D;
            nearPadding = 0.0D;
            ArrayList<Box> cells = new ArrayList<>();
            for (int x : new int[] {0, 4}) {
                for (int z = -1; z <= 1; z++) {
                    cells.add(new Box(x, x + 0.999D, 0.0D, 0.999D, z, z + 0.999D));
                }
            }
            ViewVolume frustum = new ViewVolume(new Vec3d( 2.5D, 3.0D, 0.5D), new TestStructure(cells), new ViewVolume.Options(64.0D, 48.0D, nearPadding, cullingRatio, aperturePadding));
            ProjectorFrustumRow row = new ProjectorFrustumRow();
            assertTrue(row.prepare(frustum, 0, -20.0D, -2.5D, 0.5D, -20, 20));
            int acceptedRuns = 0;
            boolean previous = false;
            for (int x = -20; x <= 20; x++) {
                boolean accepted = row.contains(x);
                assertEquals(frustum.containsPrimitive(x + 0.5D, -2.5D, 0.5D), accepted);
                if (accepted && !previous) {
                    acceptedRuns++;
                }
                previous = accepted;
            }
            assertEquals(2, acceptedRuns);
            assertFalse(row.contains(2));
            assertTrue(row.prepare(ViewVolume.empty(), 0, 0, 0, 0, -10, 10));
            assertFalse(row.contains(-1));
            assertFalse(row.contains(0));
            assertFalse(row.contains(1));
            assertFalse(row.prepare(frustum, 1, 0.5D, 0.5D, 0.5D, -20, 20));
            assertFalse(row.prepare(frustum, 0, 0, 0, 0, -1_024, 1_024));
            assertFalse(row.prepare(frustum, 0, Double.NaN, 0, 0, -10, 10));
            assertFalse(row.contains(0));
        } finally {
            aperturePadding = previousAperturePadding;
            nearPadding = previousNearPadding;
        }
    }

    @Test
    void coplanarAndTinySignedProjectionFactorsMatchScalarFaces() {
        ProjectorFrustumRow row = new ProjectorFrustumRow();
        for (Face normal : Face.values()) {
            int normalAxis = normal.getAxis().ordinal();
            int rowAxis = (normalAxis + 1) % 3;
            for (double offset : new double[] {-30_000_000.0D, 0.0D, 30_000_000.0D}) {
                double[] origin = new double[] {offset + 0.5D, 64.5D, offset + 0.5D};
                Box face = new Box(
                    origin[0] - (normalAxis == 0 ? 0 : 2), origin[0] + (normalAxis == 0 ? 0 : 2),
                    origin[1] - (normalAxis == 1 ? 0 : 2), origin[1] + (normalAxis == 1 ? 0 : 2),
                    origin[2] - (normalAxis == 2 ? 0 : 2), origin[2] + (normalAxis == 2 ? 0 : 2));
                for (double distance : new double[] {-0.01D, -1.0E-7D, -1.0E-8D, 0.0D, 1.0E-8D, 1.0E-7D, 0.01D}) {
                    Vec3d eye = new Vec3d( origin[0] + normal.x() * distance,
                        origin[1] + normal.y() * distance, origin[2] + normal.z() * distance);
                    Frustum frustum = new Frustum(eye, face, normal, normal.getAxis(), 64.0D, 48.0D, 0.35D);
                    for (int depth = -64; depth <= 64; depth++) {
                        double[] point = origin.clone();
                        point[normalAxis] += depth;
                        int middle = (int) Math.floor(origin[rowAxis]);
                        assertTrue(row.prepare(ViewVolume.empty(), rowAxis, point[0], point[1], point[2], middle - 64, middle + 64));
                        assertTrue(frustum.appendRow(row, rowAxis, point[0], point[1], point[2], middle - 64, middle + 64));
                        for (int coordinate = middle - 64; coordinate <= middle + 64; coordinate++) {
                            point[rowAxis] = coordinate + 0.5D;
                            assertEquals(frustum.containsPrimitive(point[0], point[1], point[2]), row.contains(coordinate),
                                normal + " distance=" + distance + " depth=" + depth + " coordinate=" + coordinate);
                        }
                    }
                }
            }
        }
    }

    private static TestStructure irregularStructure(Frame frame, double offset) {
        ArrayList<Box> cells = new ArrayList<>();
        Face right = frame.getRight();
        Face up = frame.getUp();
        for (int r = -2; r <= 2; r++) {
            for (int u = -4; u <= 4; u++) {
                if ((Math.abs(r) == 2 && Math.abs(u) >= 3) || (r == 0 && u == 1)) {
                    continue;
                }
                double x = offset + right.x() * r + up.x() * u;
                double y = 64.0D + right.y() * r + up.y() * u;
                double z = offset + right.z() * r + up.z() * u;
                cells.add(new Box(x, x + 0.999D, y, y + 0.999D, z, z + 0.999D));
            }
        }
        return new TestStructure(cells);
    }

    private static final class TestStructure implements Aperture {
        private final List<Box> cells;
        private final Box area;

        private TestStructure(List<Box> cells) {
            this.cells = List.copyOf(cells);
            this.area = new Box(cells.getFirst());
            for (int index = 1; index < cells.size(); index++) {
                area.encapsulate(cells.get(index));
            }
        }

        @Override
        public Box getArea() {
            return area;
        }

        @Override
        public Vec3d getApertureCenter() {
            return new Vec3d( (area.getXa() + area.getXb()) * 0.5D,
                (area.getYa() + area.getYb()) * 0.5D, (area.getZa() + area.getZb()) * 0.5D);
        }

        @Override
        public List<Box> getCachedApertureFaces(Face face) {
            ArrayList<Box> faces = new ArrayList<>(cells.size());
            for (Box cell : cells) {
                faces.add(cell.getFace(face));
            }
            return faces;
        }
    }
}
