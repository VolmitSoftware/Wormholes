package art.arcane.optics.entity;

import art.arcane.optics.math.Vec3d;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.QuarterTurn;
import art.arcane.optics.math.Face;
import java.util.concurrent.atomic.AtomicInteger;

public final class ProjectedItemFrameTransformTest {
    private static final AtomicInteger ENTITY_IDS = new AtomicInteger(1);

    private static final double EPSILON = 1.0E-12D;
    private static final Face[] DIRECTIONS = Face.values();
    private static final Vec3d ZERO = new Vec3d(0.0D, 0.0D, 0.0D);

    @Test
    public void linkedFramesPreserveEveryGenericItemOrientation() {
        assertLinkedOrientations(false);
    }

    @Test
    public void linkedFramesPreserveEveryFilledMapOrientation() {
        assertLinkedOrientations(true);
    }

    @Test
    public void mirrorsPreserveEveryRepresentableFaceAndTopOrientation() {
        List<Frame> frames = frames();
        double[] expectedNormal = new double[3];
        double[] expectedTop = new double[3];
        double[] sourceTop = new double[3];
        double[] actualTop = new double[3];
        for (Frame frame : frames) {
            for (int mirrorTurns = 0; mirrorTurns < 4; mirrorTurns++) {
                OpticTransform mirror = OpticTransform.mirror(frame, ZERO, QuarterTurn.of(mirrorTurns));
                for (Face sourceFacing : DIRECTIONS) {
                    int transform = ItemFrameTransform.of(sourceFacing, mirror);
                    mirror.vectorInto(sourceFacing.x(), sourceFacing.y(), sourceFacing.z(), expectedNormal);
                    assertDirection(expectedNormal, ItemFrameTransform.targetFacing(transform));
                    for (boolean filledMap : new boolean[] {false, true}) {
                        for (int sourceRotation = 0; sourceRotation < 8; sourceRotation++) {
                            orientedTop(sourceFacing, sourceRotation, filledMap, sourceTop);
                            mirror.vectorInto(sourceTop[0], sourceTop[1], sourceTop[2], expectedTop);
                            int targetRotation = ItemFrameTransform.transformRotation(
                                transform, sourceRotation, filledMap);
                            orientedTop(ItemFrameTransform.targetFacing(transform),
                                targetRotation, filledMap, actualTop);
                            assertVector(expectedTop, actualTop,
                                "mirror=" + mirrorTurns + " face=" + sourceFacing
                                    + " rotation=" + sourceRotation + " map=" + filledMap);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void handednessDistinguishesLinkedFramesFromMirrorsForEveryFace() {
        List<Frame> frames = frames();
        for (Frame sourceFrame : frames) {
            for (Frame targetFrame : frames) {
                OpticTransform between = OpticTransform.between(sourceFrame, ZERO, targetFrame, ZERO);
                for (Face sourceFacing : DIRECTIONS) {
                    assertFalse(ItemFrameTransform.isReversed(ItemFrameTransform.of(sourceFacing, between)));
                }
            }
            for (int quarterTurns = 0; quarterTurns < 4; quarterTurns++) {
                OpticTransform mirror = OpticTransform.mirror(sourceFrame, ZERO, QuarterTurn.of(quarterTurns));
                for (Face sourceFacing : DIRECTIONS) {
                    assertTrue(ItemFrameTransform.isReversed(ItemFrameTransform.of(sourceFacing, mirror)));
                }
            }
        }
    }

    @Test
    public void spawnDataUsesTheTransformedMinecraftDirectionId() {
        Frame frame = Frame.canonical(Face.N);
        OpticTransform between = OpticTransform.between(frame, ZERO, frame, ZERO);
        for (Face facing : DIRECTIONS) {
            assertEquals(facing.byteValue(), ItemFrameTransform.spawnData(ItemFrameTransform.of(facing, between)));
        }
        assertEquals(0, ItemFrameTransform.spawnData(ItemFrameTransform.NONE));
    }

    @Test
    public void filledMapsUseOneMetadataStepPerQuarterTurn() {
        Frame sourceFrame = Frame.canonical(Face.N);
        Frame targetFrame = Frame.canonical(Face.U);
        int transform = ItemFrameTransform.of(Face.N, OpticTransform.between(sourceFrame, ZERO, targetFrame, ZERO));

        assertEquals(3, ItemFrameTransform.transformRotation(transform, 1, true));
        assertEquals(5, ItemFrameTransform.transformRotation(transform, 1, false));
    }

    @Test
    public void changedProjectionOrientationInvalidatesRetainedMetadata() {
        SpoofedEntity state = SpoofedEntity.create(ENTITY_IDS::getAndIncrement, false, false, false);

        assertTrue(state.updateMetadataTransform(17));
        assertFalse(state.updateMetadataTransform(17));
        assertTrue(state.updateMetadataTransform(29));
    }

    @Test
    public void linkedAttachmentAnchorsMatchProjectedBlockCells() {
        List<Frame> frames = frames();
        double[] projected = new double[3];
        double[] expected = new double[3];
        Vec3d sourceOrigin = new Vec3d(1.9995D, -4.5005D, 8.9995D);
        Vec3d targetOrigin = new Vec3d(0.4995D, 22.4995D, -15.5005D);
        double[] anchors = new double[] {-31.96875D, -1.03125D, -0.03125D, 0.03125D, 7.96875D, 64.03125D};
        for (Frame sourceFrame : frames) {
            for (Frame targetFrame : frames) {
                OpticTransform cellTransform = OpticTransform.between(targetFrame, targetOrigin, sourceFrame, sourceOrigin);
                OpticTransform anchorTransform = OpticTransform.between(sourceFrame, sourceOrigin, targetFrame, targetOrigin);
                for (double x : anchors) {
                    for (double y : anchors) {
                        for (double z : anchors) {
                            ItemFrameTransform.anchorInto(x, y, z, anchorTransform, projected);
                            cellTransform.snappedPointInto(projected[0] + 0.5D, projected[1] + 0.5D, projected[2] + 0.5D, expected);
                            assertEquals(Math.floor(x), Math.floor(expected[0]), 0.0D);
                            assertEquals(Math.floor(y), Math.floor(expected[1]), 0.0D);
                            assertEquals(Math.floor(z), Math.floor(expected[2]), 0.0D);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void mirroredAttachmentAnchorsMatchProjectedBlockCells() {
        List<Frame> frames = frames();
        double[] projected = new double[3];
        double[] expected = new double[3];
        Vec3d origin = new Vec3d(0.4995D, -2.5005D, 7.4995D);
        double[] anchors = new double[] {-31.96875D, -1.03125D, -0.03125D, 0.03125D, 7.96875D, 64.03125D};
        for (Frame frame : frames) {
            for (int quarterTurns = 0; quarterTurns < 4; quarterTurns++) {
                OpticTransform mirror = OpticTransform.mirror(frame, origin, QuarterTurn.of(quarterTurns));
                OpticTransform cellTransform = mirror.inverse();
                for (double x : anchors) {
                    for (double y : anchors) {
                        for (double z : anchors) {
                            ItemFrameTransform.anchorInto(x, y, z, mirror, projected);
                            cellTransform.snappedPointInto(projected[0] + 0.5D, projected[1] + 0.5D, projected[2] + 0.5D, expected);
                            assertEquals(Math.floor(x), Math.floor(expected[0]), 0.0D);
                            assertEquals(Math.floor(y), Math.floor(expected[1]), 0.0D);
                            assertEquals(Math.floor(z), Math.floor(expected[2]), 0.0D);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void fractionalOriginsUseAnchorBlockCenters() {
        Frame frame = Frame.canonical(Face.N);
        double[] linked = new double[3];
        double[] mirrored = new double[3];

        ItemFrameTransform.anchorInto(4.03125D, 0.03125D, 0.03125D,
            OpticTransform.between(frame, new Vec3d(1.9995D, 0.4995D, 0.4995D), frame, new Vec3d(0.4995D, 0.4995D, 0.4995D)), linked);
        ItemFrameTransform.anchorInto(0.03125D, 0.03125D, 2.03125D,
            OpticTransform.mirror(frame, new Vec3d(0.4995D, 0.4995D, 0.5D), QuarterTurn.DEGREES_0), mirrored);

        assertEquals(2.0D, linked[0], 0.0D);
        assertEquals(-2.0D, mirrored[2], 0.0D);
    }

    private static void assertLinkedOrientations(boolean filledMap) {
        List<Frame> frames = frames();
        double[] expectedNormal = new double[3];
        double[] expectedTop = new double[3];
        double[] sourceTop = new double[3];
        double[] actualTop = new double[3];
        for (Frame sourceFrame : frames) {
            for (Frame targetFrame : frames) {
                AxisPermutation permutation = AxisPermutation.between(sourceFrame, targetFrame);
                OpticTransform between = OpticTransform.of(permutation, 0.0D, 0.0D, 0.0D);
                for (Face sourceFacing : DIRECTIONS) {
                    int transform = ItemFrameTransform.of(sourceFacing, between);
                    permutation.vectorInto(sourceFacing.x(), sourceFacing.y(), sourceFacing.z(), expectedNormal);
                    assertDirection(expectedNormal, ItemFrameTransform.targetFacing(transform));
                    for (int sourceRotation = 0; sourceRotation < 8; sourceRotation++) {
                        orientedTop(sourceFacing, sourceRotation, filledMap, sourceTop);
                        permutation.vectorInto(sourceTop[0], sourceTop[1], sourceTop[2], expectedTop);
                        int targetRotation = ItemFrameTransform.transformRotation(
                            transform, sourceRotation, filledMap);
                        orientedTop(ItemFrameTransform.targetFacing(transform),
                            targetRotation, filledMap, actualTop);
                        assertVector(expectedTop, actualTop,
                            sourceFrame.getNormal() + "->" + targetFrame.getNormal()
                                + " face=" + sourceFacing + " rotation=" + sourceRotation + " map=" + filledMap);
                    }
                }
            }
        }
    }

    private static List<Frame> frames() {
        ArrayList<Frame> frames = new ArrayList<Frame>(24);
        for (Face normal : DIRECTIONS) {
            Frame frame = Frame.canonical(normal);
            for (int roll = 0; roll < 4; roll++) {
                frames.add(frame);
                frame = frame.rotateClockwise();
            }
        }
        return frames;
    }

    private static void orientedTop(Face facing, int rotation, boolean filledMap, double[] out3) {
        Face top = canonicalTop(facing);
        Face right = cross(facing, top);
        double angle = filledMap
            ? Math.floorMod(rotation, 4) * (Math.PI / 2.0D)
            : Math.floorMod(rotation, 8) * (Math.PI / 4.0D);
        double sine = Math.sin(angle);
        double cosine = Math.cos(angle);
        out3[0] = (-sine * right.x()) + (cosine * top.x());
        out3[1] = (-sine * right.y()) + (cosine * top.y());
        out3[2] = (-sine * right.z()) + (cosine * top.z());
    }

    private static Face canonicalTop(Face facing) {
        return switch (facing) {
            case U -> Face.N;
            case D -> Face.S;
            default -> Face.U;
        };
    }

    private static Face cross(Face left, Face right) {
        return Face.closest(
            (left.y() * right.z()) - (left.z() * right.y()),
            (left.z() * right.x()) - (left.x() * right.z()),
            (left.x() * right.y()) - (left.y() * right.x()));
    }

    private static void assertDirection(double[] expected, Face actual) {
        assertEquals(expected[0], actual.x(), EPSILON);
        assertEquals(expected[1], actual.y(), EPSILON);
        assertEquals(expected[2], actual.z(), EPSILON);
    }

    private static void assertVector(double[] expected, double[] actual, String context) {
        assertEquals(expected[0], actual[0], EPSILON, context + " x");
        assertEquals(expected[1], actual[1], EPSILON, context + " y");
        assertEquals(expected[2], actual[2], EPSILON, context + " z");
    }
}
