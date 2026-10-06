package art.arcane.optics.frame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import art.arcane.optics.client.ClientViewBlockTransform;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.plate.PlateBox;
import art.arcane.optics.state.TrackShape;
import art.arcane.optics.stream.ProjectionEnvironment;

final class OpticTransformParityTest {
    private static final int ORIGINS_PER_PAIR = 1_000;
    private static final double WORLD = 30_000_000.0D;

    @Test
    void framePairsMatchTheFrameTheHoistedTransformAndTheDirectionMapping() {
        Random random = new Random(0x0B71C5L);
        List<Frame> frames = FrameFixtures.all();
        double[] expected = new double[3];
        double[] actual = new double[3];
        double[] scratch = new double[3];
        ProjectorFrameTransform hoisted = new ProjectorFrameTransform();
        for (Frame from : frames) {
            for (Frame to : frames) {
                DirectionMapping mapping = DirectionMapping.between(from, to, scratch);
                AxisPermutation permutation = AxisPermutation.between(from, to);
                assertDirections(permutation, mapping, from + "->" + to);
                assertEquals(PortalCoordMap.transformFlipsWorldUp(from, to), permutation.flipsWorldUp(), from + "->" + to);
                for (int sample = 0; sample < ORIGINS_PER_PAIR; sample++) {
                    Vec3d fromOrigin = origin(random);
                    Vec3d toOrigin = origin(random);
                    OpticTransform transform = OpticTransform.between(from, fromOrigin, to, toOrigin);
                    String context = from + "->" + to + " " + fromOrigin + " " + toOrigin;
                    assertEquals(PortalCoordMap.transformFlipsWorldUp(from, to), transform.flipsWorldUp(), context);
                    hoisted.configure(from, to, fromOrigin.x(), fromOrigin.y(), fromOrigin.z(), toOrigin.x(), toOrigin.y(), toOrigin.z());
                    assertEquals(ProjectorFrameTransform.coordinateSnapTolerance(fromOrigin.x(), fromOrigin.y(), fromOrigin.z(),
                        toOrigin.x(), toOrigin.y(), toOrigin.z()), transform.snapTolerance(), 0.0D, context);

                    double x = coordinate(random, fromOrigin.x());
                    double y = coordinate(random, fromOrigin.y());
                    double z = coordinate(random, fromOrigin.z());
                    from.transformPointInto(x, y, z, fromOrigin.x(), fromOrigin.y(), fromOrigin.z(),
                        toOrigin.x(), toOrigin.y(), toOrigin.z(), to, expected);
                    transform.pointInto(x, y, z, actual);
                    assertBits(expected, actual, "point " + context);

                    hoisted.apply(x, y, z, expected);
                    transform.snappedPointInto(x, y, z, actual);
                    assertBits(expected, actual, "snapped " + context);

                    double blockX = Math.floor(x) + 0.5D;
                    double blockY = Math.floor(y) + 0.5D;
                    double blockZ = Math.floor(z) + 0.5D;
                    hoisted.apply(blockX, blockY, blockZ, expected);
                    transform.snappedPointInto(blockX, blockY, blockZ, actual);
                    assertBits(expected, actual, "snapped block " + context);

                    from.transformVectorInto(x - fromOrigin.x(), y, z, to, expected);
                    transform.vectorInto(x - fromOrigin.x(), y, z, actual);
                    assertBits(expected, actual, "vector " + context);

                    PlateBox box = box(random, x, y, z);
                    int margin = random.nextInt(4);
                    assertEquals(hoisted.transformBox(box, margin), transform.box(box, margin), "box " + context);
                    assertCell(hoisted, transform, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z), context);
                }
            }
        }
    }

    @Test
    void mirrorsMatchTheCoordMapAndTheHoistedDisplayToSourceTransform() {
        Random random = new Random(0x5EEDL);
        double[] expected = new double[3];
        double[] actual = new double[3];
        double[] scratch = new double[3];
        ProjectorFrameTransform hoisted = new ProjectorFrameTransform();
        for (Frame plane : FrameFixtures.all()) {
            for (QuarterTurn turns : QuarterTurn.values()) {
                int coherent = turns.coherentFor(plane).getQuarterTurns();
                DirectionMapping mapping = DirectionMapping.mirror(plane, coherent, scratch);
                AxisPermutation permutation = AxisPermutation.mirror(plane, turns);
                String planeContext = plane + " " + turns;
                assertDirections(permutation, mapping, planeContext);
                assertEquals(PortalCoordMap.mirrorTransformFlipsWorldUp(plane, coherent), permutation.flipsWorldUp(), planeContext);
                for (int sample = 0; sample < ORIGINS_PER_PAIR; sample++) {
                    Vec3d origin = origin(random);
                    OpticTransform mirror = OpticTransform.mirror(plane, origin, turns);
                    String context = planeContext + " " + origin;
                    double x = coordinate(random, origin.x());
                    double y = coordinate(random, origin.y());
                    double z = coordinate(random, origin.z());

                    PortalCoordMap.mirrorSourceToDisplayVectorInto(x, y, z, plane, coherent, expected);
                    mirror.vectorInto(x, y, z, actual);
                    assertBits(expected, actual, "mirror vector " + context);

                    PortalCoordMap.mirrorSourceToDisplayPointInto(x, y, z, origin.x(), origin.y(), origin.z(), plane, coherent, expected);
                    mirror.pointInto(x, y, z, actual);
                    assertBits(expected, actual, "mirror point " + context);

                    PortalCoordMap.mirrorDisplayToSourcePointInto(x, y, z, origin.x(), origin.y(), origin.z(), plane, coherent, expected);
                    mirror.inverse().pointInto(x, y, z, actual);
                    assertBits(expected, actual, "mirror inverse point " + context);

                    hoisted.configureMirror(plane, coherent, origin.x(), origin.y(), origin.z());
                    OpticTransform displayToSource = mirror.inverse();
                    hoisted.apply(x, y, z, expected);
                    displayToSource.snappedPointInto(x, y, z, actual);
                    assertBits(expected, actual, "mirror snapped " + context);
                    PlateBox box = box(random, x, y, z);
                    int margin = random.nextInt(4);
                    assertEquals(hoisted.transformBox(box, margin), displayToSource.box(box, margin), "mirror box " + context);
                    assertCell(hoisted, displayToSource, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z), context);
                }
            }
        }
    }

    @Test
    void incoherentMirrorTurnsNormalizeWhereTheRawCoordMapDiverges() {
        double[] raw = new double[3];
        double[] normalized = new double[3];
        Frame wall = Frame.canonical(Face.N);
        OpticTransform mirror = OpticTransform.mirror(wall, new Vec3d(0.5D, 64.5D, 0.5D), QuarterTurn.DEGREES_90);
        assertEquals(OpticTransform.mirror(wall, new Vec3d(0.5D, 64.5D, 0.5D), QuarterTurn.DEGREES_0), mirror);
        PortalCoordMap.mirrorSourceToDisplayVectorInto(1.0D, 0.0D, 0.0D, wall, 1, raw);
        mirror.vectorInto(1.0D, 0.0D, 0.0D, normalized);
        assertNotEquals(Face.closest(raw[0], raw[1], raw[2]), Face.closest(normalized[0], normalized[1], normalized[2]));
        assertEquals(Face.E, Face.closest(normalized[0], normalized[1], normalized[2]));
    }

    @Test
    void wireTransformsWithIntegerTranslationsMatchTheClientBlockTransform() {
        Random random = new Random(0xB10CL);
        int[] cell = new int[3];
        for (int index = 0; index < 48; index++) {
            AxisPermutation permutation = AxisPermutation.ofIndex(index);
            for (int sample = 0; sample < 200; sample++) {
                double tx = random.nextInt(200_000) - 100_000;
                double ty = random.nextInt(512) - 256;
                double tz = random.nextInt(200_000) - 100_000;
                ClientViewBlockTransform client = new ClientViewBlockTransform(new ProjectionEnvironment.Transform(
                    permutation.x(), permutation.y(), permutation.z(), new Vec3d(tx, ty, tz)));
                OpticTransform transform = OpticTransform.of(permutation, tx, ty, tz);
                int x = random.nextInt(100_000) - 50_000;
                int y = random.nextInt(512) - 256;
                int z = random.nextInt(100_000) - 50_000;
                transform.cellInto(x, y, z, cell);
                assertEquals(client.displayX(x, y, z), cell[0]);
                assertEquals(client.displayY(x, y, z), cell[1]);
                assertEquals(client.displayZ(x, y, z), cell[2]);
            }
        }
    }

    @Test
    void halfBlockTranslationsSplitTheClientBlockTransformFromTheHoistedBoxOnPositiveAxes() {
        Frame frame = Frame.canonical(Face.N);
        OpticTransform transform = OpticTransform.between(frame, new Vec3d(0.5D, 64.0D, 0.0D), frame, new Vec3d(0.0D, 64.0D, 0.0D));
        assertEquals(-0.5D, transform.translationX(), 0.0D);
        ClientViewBlockTransform client = new ClientViewBlockTransform(new ProjectionEnvironment.Transform(Face.E, Face.U, Face.S,
            new Vec3d(transform.translationX(), transform.translationY(), transform.translationZ())));
        ProjectorFrameTransform hoisted = new ProjectorFrameTransform();
        hoisted.configure(frame, frame, 0.5D, 64.0D, 0.0D, 0.0D, 64.0D, 0.0D);
        int[] cell = new int[3];
        transform.cellInto(10, 70, 3, cell);
        assertEquals(new PlateBox(10, 70, 3, 1, 1, 1), hoisted.transformBox(new PlateBox(10, 70, 3, 1, 1, 1), 0));
        assertEquals(10, cell[0]);
        assertEquals(9, client.displayX(10, 70, 3));
    }

    private static void assertDirections(AxisPermutation permutation, DirectionMapping mapping, String context) {
        for (Face face : Face.values()) {
            assertEquals(mapping.map(face), permutation.face(face), "face " + face + " " + context);
        }
        assertEquals(mapping.quarterTurnsClockwise(), permutation.quarterTurnsClockwise(), "turns " + context);
        assertEquals(mapping.reflects(), permutation.reflectsHorizontally(), "handedness " + context);
        OpticTransform transform = OpticTransform.of(permutation, 0.0D, 0.0D, 0.0D);
        for (int rotation = 0; rotation < 16; rotation++) {
            assertEquals(mapping.mapRotation(rotation), transform.rotation16(rotation), "rotation " + rotation + " " + context);
        }
        for (TrackShape shape : TrackShape.values()) {
            DirectionMapping.RailShape legacy = mapping.mapRailShape(DirectionMapping.RailShape.valueOf(shape.name()));
            TrackShape mapped = transform.trackShape(shape);
            assertEquals(legacy == null ? null : legacy.name(), mapped == null ? null : mapped.name(), "track " + shape + " " + context);
        }
    }

    private static void assertCell(ProjectorFrameTransform hoisted, OpticTransform transform, int x, int y, int z, String context) {
        double[] center = new double[3];
        int[] cell = new int[3];
        hoisted.apply(x + 0.5D, y + 0.5D, z + 0.5D, center);
        transform.cellInto(x, y, z, cell);
        assertEquals((int) Math.floor(center[0]), cell[0], "cell x " + context);
        assertEquals((int) Math.floor(center[1]), cell[1], "cell y " + context);
        assertEquals((int) Math.floor(center[2]), cell[2], "cell z " + context);
    }

    private static void assertBits(double[] expected, double[] actual, String context) {
        for (int axis = 0; axis < 3; axis++) {
            assertEquals(Double.doubleToLongBits(expected[axis] + 0.0D), Double.doubleToLongBits(actual[axis] + 0.0D),
                context + " axis " + axis + " expected " + expected[axis] + " actual " + actual[axis]);
        }
    }

    private static Vec3d origin(Random random) {
        return new Vec3d(originComponent(random, WORLD), originComponent(random, 320.0D), originComponent(random, WORLD));
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

    private static PlateBox box(Random random, double x, double y, double z) {
        return new PlateBox((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z),
            1 + random.nextInt(48), 1 + random.nextInt(48), 1 + random.nextInt(48));
    }
}
