package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.util.Vector3d;

import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.util.Direction;

public final class ProjectedItemFrameTransformTest {
    private static final double EPSILON = 1.0E-12D;
    private static final Direction[] DIRECTIONS = Direction.values();

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
        List<PortalFrame> frames = frames();
        double[] expectedNormal = new double[3];
        double[] expectedTop = new double[3];
        double[] sourceTop = new double[3];
        double[] actualTop = new double[3];
        double[] scratch = new double[3];
        for (PortalFrame frame : frames) {
            for (int mirrorTurns = 0; mirrorTurns < 4; mirrorTurns++) {
                for (Direction sourceFacing : DIRECTIONS) {
                    int transform = ProjectedItemFrameTransform.mirror(
                        sourceFacing, frame, mirrorTurns, scratch);
                    PortalCoordMap.mirrorSourceToDisplayVectorInto(
                        sourceFacing.x(), sourceFacing.y(), sourceFacing.z(), frame, mirrorTurns, expectedNormal);
                    assertDirection(expectedNormal, ProjectedItemFrameTransform.targetFacing(transform));
                    for (boolean filledMap : new boolean[] {false, true}) {
                        for (int sourceRotation = 0; sourceRotation < 8; sourceRotation++) {
                            orientedTop(sourceFacing, sourceRotation, filledMap, sourceTop);
                            PortalCoordMap.mirrorSourceToDisplayVectorInto(
                                sourceTop[0], sourceTop[1], sourceTop[2], frame, mirrorTurns, expectedTop);
                            int targetRotation = ProjectedItemFrameTransform.transformRotation(
                                transform, sourceRotation, filledMap);
                            orientedTop(ProjectedItemFrameTransform.targetFacing(transform),
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
        List<PortalFrame> frames = frames();
        double[] scratch = new double[3];
        for (PortalFrame sourceFrame : frames) {
            for (PortalFrame targetFrame : frames) {
                for (Direction sourceFacing : DIRECTIONS) {
                    int linked = ProjectedItemFrameTransform.between(
                        sourceFacing, sourceFrame, targetFrame, scratch);
                    assertFalse(ProjectedItemFrameTransform.isReversed(linked));
                }
            }
            for (int quarterTurns = 0; quarterTurns < 4; quarterTurns++) {
                for (Direction sourceFacing : DIRECTIONS) {
                    int mirrored = ProjectedItemFrameTransform.mirror(
                        sourceFacing, sourceFrame, quarterTurns, scratch);
                    assertTrue(ProjectedItemFrameTransform.isReversed(mirrored));
                }
            }
        }
    }

    @Test
    public void spawnDataUsesTheTransformedMinecraftDirectionId() {
        PortalFrame frame = PortalFrame.canonical(Direction.N);
        double[] scratch = new double[3];
        for (Direction facing : DIRECTIONS) {
            int transform = ProjectedItemFrameTransform.between(
                facing, frame, frame, scratch);
            assertEquals(facing.byteValue(), ProjectedItemFrameTransform.spawnData(transform));
        }
        assertEquals(0, ProjectedItemFrameTransform.spawnData(ProjectedItemFrameTransform.NONE));
    }

    @Test
    public void metadataTransformsDirectionWithoutMutatingTheCapturedList() {
        PortalFrame sourceFrame = PortalFrame.canonical(Direction.N);
        PortalFrame targetFrame = PortalFrame.canonical(Direction.U);
        double[] scratch = new double[3];
        int transform = ProjectedItemFrameTransform.between(
            Direction.N, sourceFrame, targetFrame, scratch);
        EntityData<String> retained = new EntityData<String>(11, null, "retained");
        List<EntityData<?>> source = List.of(
            new EntityData<BlockFace>(8, null, BlockFace.NORTH),
            new EntityData<Integer>(10, null, Integer.valueOf(1)),
            retained);

        List<EntityData<?>> projected = ProjectedItemFrameTransform.transformMetadata(source, transform);

        assertEquals(BlockFace.UP, valueAt(projected, 8));
        assertEquals(Integer.valueOf(5), valueAt(projected, 10));
        assertSame(retained, projected.get(2));
        assertEquals(BlockFace.NORTH, valueAt(source, 8));
        assertEquals(Integer.valueOf(1), valueAt(source, 10));
    }

    @Test
    public void filledMapsUseOneMetadataStepPerQuarterTurn() {
        PortalFrame sourceFrame = PortalFrame.canonical(Direction.N);
        PortalFrame targetFrame = PortalFrame.canonical(Direction.U);
        double[] scratch = new double[3];
        int transform = ProjectedItemFrameTransform.between(
            Direction.N, sourceFrame, targetFrame, scratch);

        assertEquals(3, ProjectedItemFrameTransform.transformRotation(transform, 1, true));
        assertEquals(5, ProjectedItemFrameTransform.transformRotation(transform, 1, false));
    }

    @Test
    public void changedProjectionOrientationInvalidatesRetainedMetadata() {
        EntityRenderSpoofedEntity state = EntityRenderSpoofedEntity.create(false, false, false);

        assertTrue(state.updateMetadataTransform(17));
        assertFalse(state.updateMetadataTransform(17));
        assertTrue(state.updateMetadataTransform(29));
    }

    @Test
    public void linkedAttachmentAnchorsMatchProjectedBlockCells() {
        List<PortalFrame> frames = frames();
        double[] scratch = new double[3];
        double[] expected = new double[3];
        ProjectorFrameTransform cellTransform = new ProjectorFrameTransform();
        double[] anchors = new double[] {-31.96875D, -1.03125D, -0.03125D, 0.03125D, 7.96875D, 64.03125D};
        for (PortalFrame sourceFrame : frames) {
            for (PortalFrame targetFrame : frames) {
                cellTransform.configure(targetFrame, sourceFrame,
                    0.4995D, 22.4995D, -15.5005D,
                    1.9995D, -4.5005D, 8.9995D);
                for (double x : anchors) {
                    for (double y : anchors) {
                        for (double z : anchors) {
                            Vector3d projected = ProjectedItemFrameTransform.betweenAnchor(
                                x, y, z,
                                1.9995D, -4.5005D, 8.9995D,
                                0.4995D, 22.4995D, -15.5005D,
                                sourceFrame, targetFrame, scratch);
                            cellTransform.apply(
                                projected.getX() + 0.5D,
                                projected.getY() + 0.5D,
                                projected.getZ() + 0.5D,
                                expected);
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
        List<PortalFrame> frames = frames();
        double[] scratch = new double[3];
        double[] expected = new double[3];
        ProjectorFrameTransform cellTransform = new ProjectorFrameTransform();
        double[] anchors = new double[] {-31.96875D, -1.03125D, -0.03125D, 0.03125D, 7.96875D, 64.03125D};
        for (PortalFrame frame : frames) {
            for (int quarterTurns = 0; quarterTurns < 4; quarterTurns++) {
                cellTransform.configureMirror(frame, quarterTurns,
                    0.4995D, -2.5005D, 7.4995D, scratch);
                for (double x : anchors) {
                    for (double y : anchors) {
                        for (double z : anchors) {
                            Vector3d projected = ProjectedItemFrameTransform.mirrorAnchor(
                                x, y, z,
                                0.4995D, -2.5005D, 7.4995D,
                                frame, quarterTurns, scratch);
                            cellTransform.apply(
                                projected.getX() + 0.5D,
                                projected.getY() + 0.5D,
                                projected.getZ() + 0.5D,
                                expected);
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
        PortalFrame frame = PortalFrame.canonical(Direction.N);
        double[] scratch = new double[3];

        Vector3d linked = ProjectedItemFrameTransform.betweenAnchor(
            4.03125D, 0.03125D, 0.03125D,
            1.9995D, 0.4995D, 0.4995D,
            0.4995D, 0.4995D, 0.4995D,
            frame, frame, scratch);
        Vector3d mirrored = ProjectedItemFrameTransform.mirrorAnchor(
            0.03125D, 0.03125D, 2.03125D,
            0.4995D, 0.4995D, 0.5D,
            frame, 0, scratch);

        assertEquals(2.0D, linked.getX(), 0.0D);
        assertEquals(-2.0D, mirrored.getZ(), 0.0D);
    }

    private static void assertLinkedOrientations(boolean filledMap) {
        List<PortalFrame> frames = frames();
        double[] expectedNormal = new double[3];
        double[] expectedTop = new double[3];
        double[] sourceTop = new double[3];
        double[] actualTop = new double[3];
        double[] scratch = new double[3];
        for (PortalFrame sourceFrame : frames) {
            for (PortalFrame targetFrame : frames) {
                for (Direction sourceFacing : DIRECTIONS) {
                    int transform = ProjectedItemFrameTransform.between(
                        sourceFacing, sourceFrame, targetFrame, scratch);
                    sourceFrame.transformVectorInto(
                        sourceFacing.x(), sourceFacing.y(), sourceFacing.z(), targetFrame, expectedNormal);
                    assertDirection(expectedNormal, ProjectedItemFrameTransform.targetFacing(transform));
                    for (int sourceRotation = 0; sourceRotation < 8; sourceRotation++) {
                        orientedTop(sourceFacing, sourceRotation, filledMap, sourceTop);
                        sourceFrame.transformVectorInto(
                            sourceTop[0], sourceTop[1], sourceTop[2], targetFrame, expectedTop);
                        int targetRotation = ProjectedItemFrameTransform.transformRotation(
                            transform, sourceRotation, filledMap);
                        orientedTop(ProjectedItemFrameTransform.targetFacing(transform),
                            targetRotation, filledMap, actualTop);
                        assertVector(expectedTop, actualTop,
                            sourceFrame.getNormal() + "->" + targetFrame.getNormal()
                                + " face=" + sourceFacing + " rotation=" + sourceRotation + " map=" + filledMap);
                    }
                }
            }
        }
    }

    private static List<PortalFrame> frames() {
        ArrayList<PortalFrame> frames = new ArrayList<PortalFrame>(24);
        for (Direction normal : DIRECTIONS) {
            PortalFrame frame = PortalFrame.canonical(normal);
            for (int roll = 0; roll < 4; roll++) {
                frames.add(frame);
                frame = frame.rotateClockwise();
            }
        }
        return frames;
    }

    private static Object valueAt(List<EntityData<?>> metadata, int index) {
        for (EntityData<?> data : metadata) {
            if (data.getIndex() == index) {
                return data.getValue();
            }
        }
        return null;
    }

    private static void orientedTop(Direction facing, int rotation, boolean filledMap, double[] out3) {
        Direction top = canonicalTop(facing);
        Direction right = cross(facing, top);
        double angle = filledMap
            ? Math.floorMod(rotation, 4) * (Math.PI / 2.0D)
            : Math.floorMod(rotation, 8) * (Math.PI / 4.0D);
        double sine = Math.sin(angle);
        double cosine = Math.cos(angle);
        out3[0] = (-sine * right.x()) + (cosine * top.x());
        out3[1] = (-sine * right.y()) + (cosine * top.y());
        out3[2] = (-sine * right.z()) + (cosine * top.z());
    }

    private static Direction canonicalTop(Direction facing) {
        return switch (facing) {
            case U -> Direction.N;
            case D -> Direction.S;
            default -> Direction.U;
        };
    }

    private static Direction cross(Direction left, Direction right) {
        return Direction.closest(
            (left.y() * right.z()) - (left.z() * right.y()),
            (left.z() * right.x()) - (left.x() * right.z()),
            (left.x() * right.y()) - (left.y() * right.x()));
    }

    private static void assertDirection(double[] expected, Direction actual) {
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
