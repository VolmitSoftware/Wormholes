package art.arcane.optics.frame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import art.arcane.optics.math.Axis;
import art.arcane.optics.math.Face;

final class AxisPermutationGroupTest {
    private static final int COUNT = 48;

    @Test
    void indicesEnumerateFortyEightDistinctElementsWithIdentityFirst() {
        Set<String> images = new HashSet<String>();
        for (int index = 0; index < COUNT; index++) {
            AxisPermutation permutation = AxisPermutation.ofIndex(index);
            assertEquals(index, permutation.index());
            assertSame(permutation, AxisPermutation.of(permutation.x(), permutation.y(), permutation.z()));
            images.add(permutation.x() + "/" + permutation.y() + "/" + permutation.z());
        }
        assertEquals(COUNT, images.size());
        assertSame(AxisPermutation.IDENTITY, AxisPermutation.ofIndex(0));
        assertEquals(Face.E, AxisPermutation.IDENTITY.x());
        assertEquals(Face.U, AxisPermutation.IDENTITY.y());
        assertEquals(Face.S, AxisPermutation.IDENTITY.z());
        assertThrows(IllegalArgumentException.class, () -> AxisPermutation.ofIndex(-1));
        assertThrows(IllegalArgumentException.class, () -> AxisPermutation.ofIndex(COUNT));
    }

    @Test
    void ofRejectsTriplesThatAreNotPerpendicular() {
        assertThrows(IllegalArgumentException.class, () -> AxisPermutation.of(Face.E, Face.E, Face.S));
        assertThrows(IllegalArgumentException.class, () -> AxisPermutation.of(Face.E, Face.W, Face.S));
        assertThrows(IllegalArgumentException.class, () -> AxisPermutation.of(Face.U, Face.U, Face.U));
        assertThrows(IllegalArgumentException.class, () -> AxisPermutation.of(Face.N, Face.U, Face.S));
        assertThrows(NullPointerException.class, () -> AxisPermutation.of(null, Face.U, Face.S));
    }

    @Test
    void compositionIsClosedAssociativeAndMatchesFaceImages() {
        for (int outer = 0; outer < COUNT; outer++) {
            for (int inner = 0; inner < COUNT; inner++) {
                AxisPermutation a = AxisPermutation.ofIndex(outer);
                AxisPermutation b = AxisPermutation.ofIndex(inner);
                AxisPermutation composed = a.compose(b);
                for (Face face : Face.values()) {
                    assertEquals(a.face(b.face(face)), composed.face(face), a + " after " + b + " on " + face);
                }
                assertEquals(a.reflects() != b.reflects(), composed.reflects());
            }
        }
        Random random = new Random(48L);
        for (int sample = 0; sample < 2_000; sample++) {
            AxisPermutation a = AxisPermutation.ofIndex(random.nextInt(COUNT));
            AxisPermutation b = AxisPermutation.ofIndex(random.nextInt(COUNT));
            AxisPermutation c = AxisPermutation.ofIndex(random.nextInt(COUNT));
            assertSame(a.compose(b).compose(c), a.compose(b.compose(c)));
        }
    }

    @Test
    void everyElementHasAnInverseAndIdentityIsNeutral() {
        for (int index = 0; index < COUNT; index++) {
            AxisPermutation permutation = AxisPermutation.ofIndex(index);
            assertSame(AxisPermutation.IDENTITY, permutation.compose(permutation.inverse()));
            assertSame(AxisPermutation.IDENTITY, permutation.inverse().compose(permutation));
            assertSame(permutation, permutation.inverse().inverse());
            assertSame(permutation, AxisPermutation.IDENTITY.compose(permutation));
            assertSame(permutation, permutation.compose(AxisPermutation.IDENTITY));
        }
    }

    @Test
    void twentyFourRotationsAndTwentyFourReflections() {
        int rotations = 0;
        int reflections = 0;
        for (int index = 0; index < COUNT; index++) {
            AxisPermutation permutation = AxisPermutation.ofIndex(index);
            int determinant = determinant(permutation);
            assertEquals(determinant < 0, permutation.reflects(), permutation.toString());
            if (determinant > 0) {
                rotations++;
            } else {
                reflections++;
            }
        }
        assertEquals(24, rotations);
        assertEquals(24, reflections);
    }

    @Test
    void vectorsCellsAxesAndFacesFollowTheImages() {
        Random random = new Random(7L);
        double[] vector = new double[3];
        int[] cell = new int[3];
        for (int index = 0; index < COUNT; index++) {
            AxisPermutation permutation = AxisPermutation.ofIndex(index);
            for (int sample = 0; sample < 64; sample++) {
                double x = random.nextDouble() * 200.0D - 100.0D;
                double y = random.nextDouble() * 200.0D - 100.0D;
                double z = random.nextDouble() * 200.0D - 100.0D;
                permutation.vectorInto(x, y, z, vector);
                assertEquals(x * permutation.x().x() + y * permutation.y().x() + z * permutation.z().x(), vector[0], 0.0D);
                assertEquals(x * permutation.x().y() + y * permutation.y().y() + z * permutation.z().y(), vector[1], 0.0D);
                assertEquals(x * permutation.x().z() + y * permutation.y().z() + z * permutation.z().z(), vector[2], 0.0D);
                int cellX = random.nextInt(2_000) - 1_000;
                int cellY = random.nextInt(2_000) - 1_000;
                int cellZ = random.nextInt(2_000) - 1_000;
                permutation.cellInto(cellX, cellY, cellZ, cell);
                permutation.vectorInto(cellX + 0.5D, cellY + 0.5D, cellZ + 0.5D, vector);
                assertEquals((int) Math.floor(vector[0]), cell[0]);
                assertEquals((int) Math.floor(vector[1]), cell[1]);
                assertEquals((int) Math.floor(vector[2]), cell[2]);
            }
            assertEquals(permutation.x().getAxis(), permutation.axis(Axis.X));
            assertEquals(permutation.y().getAxis(), permutation.axis(Axis.Y));
            assertEquals(permutation.z().getAxis(), permutation.axis(Axis.Z));
            for (Face face : Face.values()) {
                assertEquals(permutation.face(face).reverse(), permutation.face(face.reverse()));
            }
            assertEquals(permutation.face(Face.U) == Face.D, permutation.flipsWorldUp());
        }
    }

    @Test
    void horizontalHandednessFollowsTheImagesOfSouthAndEast() {
        for (int index = 0; index < COUNT; index++) {
            AxisPermutation permutation = AxisPermutation.ofIndex(index);
            int turns = permutation.quarterTurnsClockwise();
            boolean reflects = permutation.reflectsHorizontally();
            Face south = permutation.face(Face.S);
            Face east = permutation.face(Face.E);
            if (south.isVertical() || east.isVertical()) {
                assertEquals(0, turns, permutation.toString());
                assertFalse(reflects, permutation.toString());
            } else {
                assertEquals(Math.floorMod(4 * turns, 16), rotationIndex(south), permutation.toString());
                assertEquals(Math.floorMod((reflects ? 4 : 12) + 4 * turns, 16), rotationIndex(east), permutation.toString());
            }
            for (int rotation = -20; rotation < 36; rotation++) {
                assertEquals(Math.floorMod((reflects ? -rotation : rotation) + 4 * turns, 16), permutation.rotation16(rotation),
                    permutation + " rotation " + rotation);
            }
        }
    }

    @Test
    void betweenFramesIsAlwaysARotationMappingTheTriads() {
        List<Frame> frames = FrameFixtures.all();
        for (Frame from : frames) {
            for (Frame to : frames) {
                AxisPermutation permutation = AxisPermutation.between(from, to);
                assertEquals(to.getRight(), permutation.face(from.getRight()));
                assertEquals(to.getUp(), permutation.face(from.getUp()));
                assertEquals(to.getNormal(), permutation.face(from.getNormal()));
                assertFalse(permutation.reflects());
            }
            assertSame(AxisPermutation.IDENTITY, AxisPermutation.between(from, from));
        }
    }

    @Test
    void mirrorsReflectAcrossThePlaneAndNormalizeIncoherentTurns() {
        for (Frame plane : FrameFixtures.all()) {
            for (QuarterTurn turns : QuarterTurn.values()) {
                AxisPermutation mirror = AxisPermutation.mirror(plane, turns);
                assertTrue(mirror.reflects());
                assertEquals(plane.getNormal().reverse(), mirror.face(plane.getNormal()));
                assertSame(AxisPermutation.mirror(plane, turns.coherentFor(plane)), mirror);
            }
            assertEquals(plane.getRight(), AxisPermutation.mirror(plane, QuarterTurn.DEGREES_0).face(plane.getRight()));
            assertEquals(plane.getRight().reverse(), AxisPermutation.mirror(plane, QuarterTurn.DEGREES_180).face(plane.getRight()));
        }
        Frame floor = Frame.canonical(Face.U);
        assertEquals(floor.getUp().reverse(), AxisPermutation.mirror(floor, QuarterTurn.DEGREES_90).face(floor.getRight()));
        assertEquals(floor.getRight(), AxisPermutation.mirror(floor, QuarterTurn.DEGREES_90).face(floor.getUp()));
        Frame wall = Frame.canonical(Face.N);
        assertSame(AxisPermutation.mirror(wall, QuarterTurn.DEGREES_0), AxisPermutation.mirror(wall, QuarterTurn.DEGREES_90));
        assertSame(AxisPermutation.mirror(wall, QuarterTurn.DEGREES_180), AxisPermutation.mirror(wall, QuarterTurn.DEGREES_270));
    }

    private static int determinant(AxisPermutation permutation) {
        Face x = permutation.x();
        Face y = permutation.y();
        Face z = permutation.z();
        return x.x() * (y.y() * z.z() - y.z() * z.y())
            - y.x() * (x.y() * z.z() - x.z() * z.y())
            + z.x() * (x.y() * y.z() - x.z() * y.y());
    }

    private static int rotationIndex(Face face) {
        return switch (face) {
            case S -> 0;
            case W -> 4;
            case N -> 8;
            case E -> 12;
            default -> -1;
        };
    }
}
