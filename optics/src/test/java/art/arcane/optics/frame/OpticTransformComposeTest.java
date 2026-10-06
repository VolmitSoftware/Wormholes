package art.arcane.optics.frame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Axis;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.state.TrackShape;

final class OpticTransformComposeTest {
    private static final double WORLD = 30_000_000.0D;

    @Test
    void composingWithTheInverseIsIdentityWithinOneUlp() {
        Random random = new Random(11L);
        double[] out = new double[3];
        for (int sample = 0; sample < 20_000; sample++) {
            OpticTransform transform = randomTransform(random);
            double x = (random.nextDouble() * 2.0D - 1.0D) * WORLD;
            double y = (random.nextDouble() * 2.0D - 1.0D) * 320.0D;
            double z = (random.nextDouble() * 2.0D - 1.0D) * WORLD;
            OpticTransform roundTrip = transform.compose(transform.inverse());
            assertTrue(roundTrip.isTranslation());
            assertTrue(roundTrip.isIdentity(), roundTrip.toString());
            roundTrip.pointInto(x, y, z, out);
            assertWithinUlp(x, out[0], transform);
            assertWithinUlp(y, out[1], transform);
            assertWithinUlp(z, out[2], transform);
            transform.inverse().compose(transform).pointInto(x, y, z, out);
            assertWithinUlp(x, out[0], transform);
            assertWithinUlp(y, out[1], transform);
            assertWithinUlp(z, out[2], transform);
            assertEquals(transform, transform.inverse().inverse());
        }
    }

    @Test
    void chainedFramePairsComposeToTheDirectPair() {
        Random random = new Random(12L);
        List<Frame> frames = FrameFixtures.all();
        for (Frame a : frames) {
            for (Frame b : frames) {
                Frame c = frames.get(random.nextInt(frames.size()));
                Vec3d originA = origin(random);
                Vec3d originB = origin(random);
                Vec3d originC = origin(random);
                OpticTransform first = OpticTransform.between(a, originA, b, originB);
                OpticTransform second = OpticTransform.between(b, originB, c, originC);
                OpticTransform direct = OpticTransform.between(a, originA, c, originC);
                assertEquals(direct, second.compose(first), a + "->" + b + "->" + c);
                assertEquals(direct.hashCode(), second.compose(first).hashCode());
            }
        }
    }

    @Test
    void compositionIsAssociativeOnPointsAndIdentityIsNeutral() {
        Random random = new Random(13L);
        double[] left = new double[3];
        double[] right = new double[3];
        double[] direct = new double[3];
        for (int sample = 0; sample < 5_000; sample++) {
            OpticTransform a = randomTransform(random);
            OpticTransform b = randomTransform(random);
            OpticTransform c = randomTransform(random);
            assertSame(a.compose(b).compose(c).permutation(), a.compose(b.compose(c)).permutation());
            double x = (random.nextDouble() * 2.0D - 1.0D) * 1_000.0D;
            double y = (random.nextDouble() * 2.0D - 1.0D) * 320.0D;
            double z = (random.nextDouble() * 2.0D - 1.0D) * 1_000.0D;
            a.compose(b).compose(c).pointInto(x, y, z, left);
            a.compose(b.compose(c)).pointInto(x, y, z, right);
            c.pointInto(x, y, z, direct);
            b.pointInto(direct[0], direct[1], direct[2], direct);
            a.pointInto(direct[0], direct[1], direct[2], direct);
            for (int axis = 0; axis < 3; axis++) {
                assertEquals(direct[axis], left[axis], 1.0E-7D);
                assertEquals(direct[axis], right[axis], 1.0E-7D);
            }
            assertEquals(a, OpticTransform.IDENTITY.compose(a));
        }
    }

    @Test
    void translationFormAndPivotFormAgree() {
        OpticTransform translation = OpticTransform.translation(3.0D, -2.0D, 0.5D);
        assertTrue(translation.isTranslation());
        assertFalse(translation.isIdentity());
        assertEquals(new Vec3d(4.0D, 0.0D, 1.5D), translation.point(new Vec3d(1.0D, 2.0D, 1.0D)));
        assertEquals(new Vec3d(1.0D, 2.0D, 1.0D), translation.vector(new Vec3d(1.0D, 2.0D, 1.0D)));
        assertTrue(OpticTransform.IDENTITY.isIdentity());
        assertSame(AxisPermutation.IDENTITY, OpticTransform.IDENTITY.permutation());

        Frame north = Frame.canonical(Face.N);
        Frame east = Frame.canonical(Face.E);
        OpticTransform between = OpticTransform.between(north, new Vec3d(10.5D, 64.0D, 20.5D), east, new Vec3d(-3.5D, 70.0D, 8.5D));
        OpticTransform flat = OpticTransform.between(north, 10.5D, 64.0D, 20.5D, east, -3.5D, 70.0D, 8.5D);
        assertEquals(between, flat);
        OpticTransform sameMap = OpticTransform.of(between.permutation(), between.translationX(), between.translationY(), between.translationZ());
        assertNotEquals(between, sameMap);
        double[] expected = new double[3];
        double[] actual = new double[3];
        between.pointInto(11.25D, 65.5D, 19.0D, expected);
        sameMap.pointInto(11.25D, 65.5D, 19.0D, actual);
        for (int axis = 0; axis < 3; axis++) {
            assertEquals(expected[axis], actual[axis], 1.0E-12D);
        }
        assertEquals(between.reflects(), between.permutation().reflects());
        assertEquals(between.quarterTurnsClockwise(), between.permutation().quarterTurnsClockwise());
        assertEquals(Face.E, between.face(Face.N));
        assertEquals(Axis.X, between.axis(Axis.Z));
    }

    @Test
    void encodingCarriesThePermutationAndTranslation() {
        Random random = new Random(14L);
        for (int sample = 0; sample < 2_000; sample++) {
            OpticTransform transform = OpticTransform.of(AxisPermutation.ofIndex(random.nextInt(48)),
                (random.nextDouble() * 2.0D - 1.0D) * WORLD, random.nextDouble() * 320.0D, (random.nextDouble() * 2.0D - 1.0D) * WORLD);
            byte[] bytes = transform.encode();
            assertEquals(25, bytes.length);
            assertEquals(transform, OpticTransform.decode(bytes));
            OpticTransform pivot = randomTransform(random);
            OpticTransform decoded = OpticTransform.decode(pivot.encode());
            assertSame(pivot.permutation(), decoded.permutation());
            assertEquals(pivot.translationX(), decoded.translationX(), 0.0D);
            assertEquals(pivot.translationY(), decoded.translationY(), 0.0D);
            assertEquals(pivot.translationZ(), decoded.translationZ(), 0.0D);
        }
        assertThrows(IllegalArgumentException.class, () -> OpticTransform.decode(new byte[24]));
        byte[] badIndex = OpticTransform.IDENTITY.encode();
        badIndex[0] = 48;
        assertThrows(IllegalArgumentException.class, () -> OpticTransform.decode(badIndex));
        byte[] nan = OpticTransform.IDENTITY.encode();
        ByteBuffer.wrap(nan).putDouble(1, Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> OpticTransform.decode(nan));
    }

    @Test
    void boxesFramesCellsAndAnglesFollowThePermutation() {
        Frame north = Frame.canonical(Face.N);
        Frame up = Frame.canonical(Face.U);
        OpticTransform tilt = OpticTransform.between(north, new Vec3d(0.5D, 64.5D, 0.5D), up, new Vec3d(100.5D, 70.5D, -40.5D));
        Box mapped = tilt.box(new Box(0.0D, 2.0D, 64.0D, 67.0D, 0.0D, 1.0D));
        double[] corner = new double[3];
        double[] min = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        double[] max = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (int index = 0; index < 8; index++) {
            tilt.pointInto((index & 1) == 0 ? 0.0D : 2.0D, (index & 2) == 0 ? 64.0D : 67.0D, (index & 4) == 0 ? 0.0D : 1.0D, corner);
            for (int axis = 0; axis < 3; axis++) {
                min[axis] = Math.min(min[axis], corner[axis]);
                max[axis] = Math.max(max[axis], corner[axis]);
            }
        }
        assertEquals(min[0], mapped.getXa(), 0.0D);
        assertEquals(min[1], mapped.getYa(), 0.0D);
        assertEquals(min[2], mapped.getZa(), 0.0D);
        assertEquals(max[0], mapped.getXb(), 0.0D);
        assertEquals(max[1], mapped.getYb(), 0.0D);
        assertEquals(max[2], mapped.getZb(), 0.0D);

        Frame side = Frame.fromNormalUp(Face.E, Face.U);
        Frame image = tilt.frame(side);
        assertEquals(tilt.face(side.getNormal()), image.getNormal());
        assertEquals(tilt.face(side.getUp()), image.getUp());
        assertEquals(tilt.face(side.getRight()), image.getRight());
        OpticTransform mirror = OpticTransform.mirror(north, new Vec3d(0.5D, 64.5D, 0.5D), QuarterTurn.DEGREES_0);
        Frame mirrored = mirror.frame(side);
        assertEquals(mirror.face(side.getNormal()), mirrored.getNormal());
        assertEquals(mirror.face(side.getUp()), mirrored.getUp());

        int[] cell = new int[3];
        tilt.cellInto(3, 66, -7, cell);
        long key = tilt.cell(CellKeys.pack(3, 66, -7));
        assertEquals(cell[0], CellKeys.unpackX(key));
        assertEquals(cell[1], CellKeys.unpackY(key));
        assertEquals(cell[2], CellKeys.unpackZ(key));

        OpticTransform quarter = OpticTransform.between(north, new Vec3d(0.0D, 0.0D, 0.0D), Frame.canonical(Face.E), new Vec3d(0.0D, 0.0D, 0.0D));
        assertEquals(0, Angles.unwrap(quarter.yaw(180.0F), 270.0F) - 270.0F, 1.0E-4F);
        Angles.Look look = quarter.look(new Angles.Look(180.0F, -30.0F));
        assertEquals(0, Angles.unwrap(look.yaw(), 270.0F) - 270.0F, 1.0E-4F);
        assertEquals(-30.0F, look.pitch(), 1.0E-4F);
        Vec3d direction = Angles.direction(12.0F, 40.0F);
        Vec3d mappedDirection = tilt.vector(direction);
        Angles.Look tilted = tilt.look(new Angles.Look(12.0F, 40.0F));
        Angles.Look expected = Angles.look(mappedDirection.x(), mappedDirection.y(), mappedDirection.z());
        assertEquals(expected, tilted);
        assertEquals(TrackShape.EAST_WEST, quarter.trackShape(TrackShape.NORTH_SOUTH));
        assertNull(tilt.trackShape(TrackShape.NORTH_SOUTH));
    }

    private static void assertWithinUlp(double expected, double actual, OpticTransform transform) {
        assertEquals(expected, actual, Math.ulp(Math.max(Math.abs(expected), WORLD)), transform.toString());
    }

    private static OpticTransform randomTransform(Random random) {
        AxisPermutation permutation = AxisPermutation.ofIndex(random.nextInt(48));
        return switch (random.nextInt(3)) {
            case 0 -> OpticTransform.of(permutation, (random.nextDouble() * 2.0D - 1.0D) * WORLD, random.nextDouble() * 320.0D,
                (random.nextDouble() * 2.0D - 1.0D) * WORLD);
            case 1 -> OpticTransform.between(FrameFixtures.all().get(random.nextInt(24)), origin(random),
                FrameFixtures.all().get(random.nextInt(24)), origin(random));
            default -> OpticTransform.mirror(FrameFixtures.all().get(random.nextInt(24)), origin(random),
                QuarterTurn.values()[random.nextInt(4)]);
        };
    }

    private static Vec3d origin(Random random) {
        return new Vec3d(Math.floor((random.nextDouble() * 2.0D - 1.0D) * WORLD) + 0.5D, Math.floor(random.nextDouble() * 320.0D),
            Math.floor((random.nextDouble() * 2.0D - 1.0D) * WORLD) + 0.5D);
    }
}
