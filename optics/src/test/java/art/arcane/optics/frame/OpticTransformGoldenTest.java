package art.arcane.optics.frame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import art.arcane.optics.math.CellKeys;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.math.BlockBox;
import art.arcane.optics.state.TrackShape;

final class OpticTransformGoldenTest {
    static final String RESOURCE = "optic-transform-golden.txt";
    static final int ORIGINS_PER_PAIR = 1_000;
    static final int WIRE_SAMPLES = 200;
    private static final double WORLD = 30_000_000.0D;
    private static final long FRAME_SWEEP = 0x76F329783C2F3E9CL;
    private static final long MIRROR_SWEEP = 0x76CD7C351930D9ADL;
    private static final long WIRE_SWEEP = 0x5B6D37269BCD5A99L;

    @Test
    void framePairPermutationsMatchTheRecordedTable() throws IOException {
        List<String[]> rows = rows("P");
        assertEquals(576, rows.size());
        for (String[] row : rows) {
            Frame from = frame(row[1]);
            Frame to = frame(row[2]);
            AxisPermutation permutation = AxisPermutation.between(from, to);
            assertEquals(row[3], images(permutation), "pair " + row[1] + "->" + row[2]);
            assertEquals(Boolean.parseBoolean(row[4]), permutation.flipsWorldUp(), "flip " + row[1] + "->" + row[2]);
        }
    }

    @Test
    void mirrorPermutationsMatchTheRecordedTable() throws IOException {
        List<String[]> rows = rows("M");
        assertEquals(96, rows.size());
        for (String[] row : rows) {
            Frame plane = frame(row[1]);
            QuarterTurn turns = QuarterTurn.fromDegrees(Integer.parseInt(row[2]));
            AxisPermutation permutation = AxisPermutation.mirror(plane, turns);
            assertEquals(row[3], images(permutation), "mirror " + row[1] + " " + row[2]);
            assertEquals(Boolean.parseBoolean(row[4]), permutation.flipsWorldUp(), "mirror flip " + row[1] + " " + row[2]);
        }
    }

    @Test
    void permutationOrientationStateMatchesTheRecordedTable() throws IOException {
        List<String[]> rows = rows("R");
        assertEquals(48, rows.size());
        for (String[] row : rows) {
            AxisPermutation permutation = AxisPermutation.of(face(row[1].charAt(0)), face(row[1].charAt(1)), face(row[1].charAt(2)));
            OpticTransform transform = OpticTransform.of(permutation, 0.0D, 0.0D, 0.0D);
            String context = "permutation " + row[1];
            assertEquals(row[2], faces(permutation), context);
            assertEquals(Integer.parseInt(row[3]), permutation.quarterTurnsClockwise(), context);
            assertEquals(Boolean.parseBoolean(row[4]), permutation.reflectsHorizontally(), context);
            StringBuilder rotations = new StringBuilder(16);
            for (int rotation = 0; rotation < 16; rotation++) {
                rotations.append(Integer.toHexString(transform.rotation16(rotation)));
            }
            assertEquals(row[5], rotations.toString(), context);
            StringBuilder tracks = new StringBuilder(32);
            for (TrackShape shape : TrackShape.values()) {
                TrackShape mapped = transform.trackShape(shape);
                tracks.append(tracks.isEmpty() ? "" : ",").append(mapped == null ? "-" : Integer.toString(mapped.ordinal()));
            }
            assertEquals(row[6], tracks.toString(), context);
        }
    }

    @Test
    void framePairSweepMatchesTheRecordedDigest() {
        assertEquals(Long.toHexString(FRAME_SWEEP), Long.toHexString(frameSweep(OpticTransformGoldenTest::frameSample)));
    }

    @Test
    void mirrorSweepMatchesTheRecordedDigest() {
        assertEquals(Long.toHexString(MIRROR_SWEEP), Long.toHexString(mirrorSweep(OpticTransformGoldenTest::mirrorSample)));
    }

    @Test
    void wireSweepMatchesTheRecordedClientCells() {
        assertEquals(Long.toHexString(WIRE_SWEEP), Long.toHexString(wireSweep(OpticTransformGoldenTest::wireSample)));
    }

    @Test
    void recordedFrameSamplesMatch() throws IOException {
        List<String[]> rows = rows("F");
        assertNotEquals(0, rows.size());
        Digest digest = new Digest();
        for (String[] row : rows) {
            Frame from = frame(row[1]);
            Frame to = frame(row[2]);
            Vec3d fromOrigin = vector(row, 3);
            Vec3d toOrigin = vector(row, 6);
            Vec3d point = vector(row, 9);
            BlockBox box = new BlockBox(Integer.parseInt(row[12]), Integer.parseInt(row[13]), Integer.parseInt(row[14]),
                Integer.parseInt(row[15]), Integer.parseInt(row[16]), Integer.parseInt(row[17]));
            int margin = Integer.parseInt(row[18]);
            digest.reset();
            frameSample(from, fromOrigin, to, toOrigin, point.x(), point.y(), point.z(), box, margin, digest);
            assertEquals(row[19], Long.toHexString(digest.value()), "sample " + String.join(" ", row));
        }
    }

    @Test
    void recordedMirrorSamplesMatch() throws IOException {
        List<String[]> rows = rows("G");
        assertNotEquals(0, rows.size());
        Digest digest = new Digest();
        for (String[] row : rows) {
            Frame plane = frame(row[1]);
            QuarterTurn turns = QuarterTurn.fromDegrees(Integer.parseInt(row[2]));
            Vec3d origin = vector(row, 3);
            Vec3d point = vector(row, 6);
            BlockBox box = new BlockBox(Integer.parseInt(row[9]), Integer.parseInt(row[10]), Integer.parseInt(row[11]),
                Integer.parseInt(row[12]), Integer.parseInt(row[13]), Integer.parseInt(row[14]));
            int margin = Integer.parseInt(row[15]);
            digest.reset();
            mirrorSample(plane, turns, origin, point.x(), point.y(), point.z(), box, margin, digest);
            assertEquals(row[16], Long.toHexString(digest.value()), "sample " + String.join(" ", row));
        }
    }

    @Test
    void recordedWireSamplesMatch() throws IOException {
        List<String[]> rows = rows("W");
        assertNotEquals(0, rows.size());
        int[] cell = new int[3];
        for (String[] row : rows) {
            AxisPermutation permutation = AxisPermutation.ofIndex(Integer.parseInt(row[1]));
            OpticTransform cells = OpticTransform.of(permutation, Double.parseDouble(row[2]), Double.parseDouble(row[3]),
                Double.parseDouble(row[4])).cellAligned();
            int x = Integer.parseInt(row[5]);
            int y = Integer.parseInt(row[6]);
            int z = Integer.parseInt(row[7]);
            cells.cellInto(x, y, z, cell);
            String context = String.join(" ", row);
            assertEquals(Integer.parseInt(row[8]), cell[0], context);
            assertEquals(Integer.parseInt(row[9]), cell[1], context);
            assertEquals(Integer.parseInt(row[10]), cell[2], context);
            cells.inverse().cellInto(x, y, z, cell);
            assertEquals(Integer.parseInt(row[11]), cell[0], context);
            assertEquals(Integer.parseInt(row[12]), cell[1], context);
            assertEquals(Integer.parseInt(row[13]), cell[2], context);
        }
    }

    @Test
    void incoherentWallMirrorTurnsCollapseToTheCoherentTurn() {
        double[] normalized = new double[3];
        Frame wall = Frame.canonical(Face.N);
        OpticTransform mirror = OpticTransform.mirror(wall, new Vec3d(0.5D, 64.5D, 0.5D), QuarterTurn.DEGREES_90);
        assertEquals(OpticTransform.mirror(wall, new Vec3d(0.5D, 64.5D, 0.5D), QuarterTurn.DEGREES_0), mirror);
        mirror.vectorInto(1.0D, 0.0D, 0.0D, normalized);
        assertEquals(Face.E, Face.closest(normalized[0], normalized[1], normalized[2]));
    }

    @Test
    void halfBlockTranslationsSplitTheSampledCellFromTheAlignedCellOnPositiveAxes() {
        Frame frame = Frame.canonical(Face.N);
        OpticTransform transform = OpticTransform.between(frame, new Vec3d(0.5D, 64.0D, 0.0D), frame, new Vec3d(0.0D, 64.0D, 0.0D));
        assertEquals(-0.5D, transform.translationX(), 0.0D);
        int[] cell = new int[3];
        transform.cellInto(10, 70, 3, cell);
        assertEquals(10, cell[0]);
        assertEquals(new BlockBox(10, 70, 3, 1, 1, 1), transform.box(new BlockBox(10, 70, 3, 1, 1, 1), 0));
        transform.cellAligned().cellInto(10, 70, 3, cell);
        assertEquals(9, cell[0]);
        transform.inverse().cellInto(9, 70, 3, cell);
        assertEquals(10, cell[0]);
    }

    static long frameSweep(FrameProbe probe) {
        Random random = new Random(0x0B71C5L);
        List<Frame> frames = FrameFixtures.all();
        Digest digest = new Digest();
        for (Frame from : frames) {
            for (Frame to : frames) {
                for (int sample = 0; sample < ORIGINS_PER_PAIR; sample++) {
                    Vec3d fromOrigin = origin(random);
                    Vec3d toOrigin = origin(random);
                    double x = coordinate(random, fromOrigin.x());
                    double y = coordinate(random, fromOrigin.y());
                    double z = coordinate(random, fromOrigin.z());
                    BlockBox box = box(random, x, y, z);
                    int margin = random.nextInt(4);
                    probe.sample(from, fromOrigin, to, toOrigin, x, y, z, box, margin, digest);
                }
            }
        }
        return digest.value();
    }

    static long mirrorSweep(MirrorProbe probe) {
        Random random = new Random(0x5EEDL);
        Digest digest = new Digest();
        for (Frame plane : FrameFixtures.all()) {
            for (QuarterTurn turns : QuarterTurn.values()) {
                for (int sample = 0; sample < ORIGINS_PER_PAIR; sample++) {
                    Vec3d origin = origin(random);
                    double x = coordinate(random, origin.x());
                    double y = coordinate(random, origin.y());
                    double z = coordinate(random, origin.z());
                    BlockBox box = box(random, x, y, z);
                    int margin = random.nextInt(4);
                    probe.sample(plane, turns, origin, x, y, z, box, margin, digest);
                }
            }
        }
        return digest.value();
    }

    static long wireSweep(WireProbe probe) {
        Random random = new Random(0xB10CL);
        Digest digest = new Digest();
        for (int index = 0; index < 48; index++) {
            for (int sample = 0; sample < WIRE_SAMPLES; sample++) {
                double tx = translation(random, 100_000);
                double ty = translation(random, 256);
                double tz = translation(random, 100_000);
                int x = random.nextInt(100_000) - 50_000;
                int y = random.nextInt(512) - 256;
                int z = random.nextInt(100_000) - 50_000;
                probe.sample(index, tx, ty, tz, x, y, z, digest);
            }
        }
        return digest.value();
    }

    static Vec3d origin(Random random) {
        return new Vec3d(originComponent(random, WORLD), originComponent(random, 320.0D), originComponent(random, WORLD));
    }

    static String frameName(Frame frame) {
        return frame.getNormal().name() + frame.getUp().name();
    }

    static String images(AxisPermutation permutation) {
        return permutation.x().name() + permutation.y().name() + permutation.z().name();
    }

    static String faces(AxisPermutation permutation) {
        StringBuilder builder = new StringBuilder(6);
        for (Face face : Face.values()) {
            builder.append(permutation.face(face).name());
        }
        return builder.toString();
    }

    private static void frameSample(Frame from, Vec3d fromOrigin, Frame to, Vec3d toOrigin, double x, double y, double z,
                                    BlockBox box, int margin, Digest digest) {
        OpticTransform transform = OpticTransform.between(from, fromOrigin, to, toOrigin);
        double[] out = new double[3];
        int[] cell = new int[3];
        digest.add(Double.doubleToLongBits(transform.snapTolerance()));
        digest.add(transform.flipsWorldUp() ? 1L : 0L);
        transform.pointInto(x, y, z, out);
        digest.add(out);
        transform.snappedPointInto(x, y, z, out);
        digest.add(out);
        transform.snappedPointInto(Math.floor(x) + 0.5D, Math.floor(y) + 0.5D, Math.floor(z) + 0.5D, out);
        digest.add(out);
        transform.vectorInto(x - fromOrigin.x(), y, z, out);
        digest.add(out);
        digest.add(transform.box(box, margin));
        transform.cellInto((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z), cell);
        long key = transform.cell(CellKeys.pack((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)));
        assertEquals(CellKeys.pack(cell[0], cell[1], cell[2]), key);
        digest.add(cell);
    }

    private static void mirrorSample(Frame plane, QuarterTurn turns, Vec3d origin, double x, double y, double z,
                                     BlockBox box, int margin, Digest digest) {
        OpticTransform mirror = OpticTransform.mirror(plane, origin, turns);
        OpticTransform displayToSource = mirror.inverse();
        double[] out = new double[3];
        int[] cell = new int[3];
        digest.add(mirror.flipsWorldUp() ? 1L : 0L);
        mirror.vectorInto(x, y, z, out);
        digest.add(out);
        mirror.pointInto(x, y, z, out);
        digest.add(out);
        displayToSource.pointInto(x, y, z, out);
        digest.add(out);
        displayToSource.snappedPointInto(x, y, z, out);
        digest.add(out);
        digest.add(displayToSource.box(box, margin));
        displayToSource.cellInto((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z), cell);
        digest.add(cell);
    }

    private static void wireSample(int index, double tx, double ty, double tz, int x, int y, int z, Digest digest) {
        OpticTransform cells = OpticTransform.of(AxisPermutation.ofIndex(index), tx, ty, tz).cellAligned();
        int[] cell = new int[3];
        cells.cellInto(x, y, z, cell);
        digest.add(cell);
        cells.inverse().cellInto(x, y, z, cell);
        digest.add(cell);
    }

    private static double translation(Random random, int range) {
        double whole = random.nextInt(range * 2) - range;
        return switch (random.nextInt(3)) {
            case 0 -> whole;
            case 1 -> whole + 0.5D;
            default -> whole + random.nextDouble();
        };
    }

    private static double originComponent(Random random, double range) {
        double whole = Math.floor((random.nextDouble() * 2.0D - 1.0D) * range);
        return switch (random.nextInt(4)) {
            case 0 -> whole;
            case 1 -> whole + 0.5D;
            case 2 -> whole + 0.4995D;
            default -> whole + random.nextDouble();
        };
    }

    private static double coordinate(Random random, double origin) {
        double offset = (random.nextDouble() * 2.0D - 1.0D) * 96.0D;
        return switch (random.nextInt(3)) {
            case 0 -> origin + offset;
            case 1 -> Math.floor(origin + offset) + 0.5D;
            default -> Math.floor(origin + offset);
        };
    }

    private static BlockBox box(Random random, double x, double y, double z) {
        return new BlockBox((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z),
            1 + random.nextInt(48), 1 + random.nextInt(48), 1 + random.nextInt(48));
    }

    private static List<String[]> rows(String kind) throws IOException {
        List<String[]> rows = new ArrayList<String[]>();
        try (InputStream stream = OpticTransformGoldenTest.class.getResourceAsStream(RESOURCE)) {
            assertNotNull(stream, RESOURCE);
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] row = line.trim().split(" ");
                if (row[0].equals(kind)) {
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private static Frame frame(String name) {
        return Frame.fromNormalUp(face(name.charAt(0)), face(name.charAt(1)));
    }

    private static Face face(char name) {
        return Face.valueOf(String.valueOf(name));
    }

    private static Vec3d vector(String[] row, int start) {
        return new Vec3d(Double.parseDouble(row[start]), Double.parseDouble(row[start + 1]), Double.parseDouble(row[start + 2]));
    }

    interface FrameProbe {
        void sample(Frame from, Vec3d fromOrigin, Frame to, Vec3d toOrigin, double x, double y, double z, BlockBox box, int margin,
                    Digest digest);
    }

    interface MirrorProbe {
        void sample(Frame plane, QuarterTurn turns, Vec3d origin, double x, double y, double z, BlockBox box, int margin, Digest digest);
    }

    interface WireProbe {
        void sample(int index, double tx, double ty, double tz, int x, int y, int z, Digest digest);
    }

    static final class Digest {
        private long value = 0xCBF29CE484222325L;

        void reset() {
            value = 0xCBF29CE484222325L;
        }

        void add(long bits) {
            value = (value ^ bits) * 0x100000001B3L;
        }

        void add(double[] point) {
            add(Double.doubleToLongBits(point[0] + 0.0D));
            add(Double.doubleToLongBits(point[1] + 0.0D));
            add(Double.doubleToLongBits(point[2] + 0.0D));
        }

        void add(int[] cell) {
            add(cell[0]);
            add(cell[1]);
            add(cell[2]);
        }

        void add(BlockBox box) {
            add(box.minX());
            add(box.minY());
            add(box.minZ());
            add(box.sizeX());
            add(box.sizeY());
            add(box.sizeZ());
        }

        long value() {
            return value;
        }
    }
}
