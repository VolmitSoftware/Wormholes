package art.arcane.optics.crossing;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.math.Face;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;

class PortalCrossingPointTest {
    @Test
    void cellApertureCentersPreserveExactFloorHeightsInBothDirectionsAndSides() {
        Vec3d overworld = center(new Vec3d(1001, 200, 0), new Vec3d(1001, 205, 0));
        Vec3d nether = center(new Vec3d(1103, 80, 0), new Vec3d(1103, 85, 0));
        for (Face sourceNormal : new Face[]{Face.N, Face.E, Face.S, Face.W}) {
            for (Face destinationNormal : new Face[]{Face.N, Face.E, Face.S, Face.W}) {
                Frame source = Frame.canonical(sourceNormal);
                Frame destination = Frame.canonical(destinationNormal);
                for (boolean front : new boolean[]{false, true}) {
                    PlaneCrossing outward = new PlaneCrossing(source.view(front), overworld,
                        new Vec3d(1001.5D, 200.0D, 0.4785775140992615D),
                        new Vec3d(0, 0, -0.1D), new Vec3d(0, 0, -1), front);
                    Vec3d arrival = outward.outPoint(destination, nether);
                    assertEquals(80.0D, arrival.y(), 0.0D);
                    assertEquals(80, arrival.getBlockY());
                    PlaneCrossing returning = new PlaneCrossing(destination.view(front), nether, arrival,
                        outward.outVelocity(destination), outward.outLook(destination), front);
                    assertEquals(200.0D, returning.outPoint(source, overworld).y(), 0.0D);
                }
            }
        }
    }

    @Test
    void rolledFramesPreserveAnExactStandingCoordinateWhenItBecomesDestinationHeight() {
        Vec3d origin = center(new Vec3d(200, 30, 0), new Vec3d(205, 30, 0));
        Vec3d arrival = center(new Vec3d(90, 80, 40), new Vec3d(90, 85, 40));
        Frame source = Frame.fromNormalUp(Face.S, Face.E);
        for (Face normal : new Face[]{Face.N, Face.E, Face.S, Face.W}) {
            Frame destination = Frame.canonical(normal);
            for (boolean front : new boolean[]{false, true}) {
                PlaneCrossing crossing = new PlaneCrossing(source.view(front), origin,
                    new Vec3d(200.0D, 30.5D, 0.4785775140992615D),
                    new Vec3d(0, 0, -0.1D), new Vec3d(0, 0, -1), front);
                Vec3d mapped = crossing.outPoint(destination, arrival);
                assertEquals(80.0D, mapped.y(), 0.0D);
                double[] primitive = new double[3];
                OpticTransform.between(source.view(front), origin.x(), origin.y(), origin.z(), destination.view(front), arrival.x(),
                    arrival.y(), arrival.z()).pointInto(crossing.point().x(), crossing.point().y(), crossing.point().z(), primitive);
                assertEquals(mapped.y(), primitive[1], 0.0D);
                assertEquals(200.0D, OpticTransform.between(destination.view(front), arrival, source.view(front), origin).point(mapped).x(), 0.0D);
            }
        }
    }

    @Test
    void translatedAndSignedCardinalTransformsRetainFractionalMotionWithoutFloorSnapping() {
        Frame source = Frame.fromNormalUp(Face.N, Face.U);
        Frame destination = Frame.fromNormalUp(Face.U, Face.N);
        Vec3d origin = new Vec3d(11.5D, 200.5D, 20.5D);
        Vec3d arrival = new Vec3d(50.5D, 80.5D, -30.5D);
        Vec3d point = new Vec3d(12.125D, 200.25D, 19.875D);
        Vec3d transformed = OpticTransform.between(source, origin, destination, arrival).point(point);
        assertEquals(point, OpticTransform.between(destination, arrival, source, origin).point(transformed));
        assertEquals(0.625D, Math.abs(transformed.y() - arrival.y()), 0.0D);
    }

    @Test
    void identicalApertureShapesPreserveExactFloorsAcrossHeightSignsAndLargeCoordinates() {
        int[] floors = {-20000000, -64, -1, 0, 1, 63, 80, 127, 200, 319, 20000000};
        Frame frame = Frame.canonical(Face.N);
        for (int sourceFloor : floors) {
            Vec3d source = center(new Vec3d(-20000000, sourceFloor, 20000000),
                new Vec3d(-19999994, sourceFloor + 5, 20000000));
            for (int targetFloor : floors) {
                Vec3d target = center(new Vec3d(20000000, targetFloor, -20000000),
                    new Vec3d(20000006, targetFloor + 5, -20000000));
                Vec3d point = new Vec3d(-19999998.375D, sourceFloor, 20000000.25D);
                Vec3d mapped = OpticTransform.between(frame, source, frame, target).point(point);
                assertEquals(targetFloor, mapped.y(), 0.0D);
                assertEquals(targetFloor, mapped.getBlockY());
                assertEquals(point, OpticTransform.between(frame, target, frame, source).point(mapped));
            }
        }
    }

    @Test
    void allCardinalRollsRetainFractionalCoordinatesAtLargeNegativeAndPositiveOrigins() {
        Vec3d source = center(new Vec3d(-20000000, -64, 20000000),
            new Vec3d(-19999995, -59, 20000005));
        Vec3d target = center(new Vec3d(20000000, 80, -20000000),
            new Vec3d(20000005, 85, -19999995));
        Vec3d point = new Vec3d(-19999999.375D, -63.125D, 20000001.625D);
        for (Face sourceNormal : Face.values()) {
            for (Face sourceUp : Face.values()) {
                if (sourceNormal.getAxis() == sourceUp.getAxis()) {
                    continue;
                }
                Frame sourceFrame = Frame.fromNormalUp(sourceNormal, sourceUp);
                for (Face targetNormal : Face.values()) {
                    for (Face targetUp : Face.values()) {
                        if (targetNormal.getAxis() == targetUp.getAxis()) {
                            continue;
                        }
                        Frame targetFrame = Frame.fromNormalUp(targetNormal, targetUp);
                        Vec3d mapped = OpticTransform.between(sourceFrame, source, targetFrame, target).point(point);
                        assertEquals(point, OpticTransform.between(targetFrame, target, sourceFrame, source).point(mapped));
                    }
                }
            }
        }
    }

    @Test
    void approachingAlongNormalKeepsTheSameMappedCrossingAndManifestColumnOnBothSides() {
        Vec3d origin = new Vec3d(1001.5D, 200.5D, 0.5D);
        Vec3d arrival = new Vec3d(1106.5D, 85.5D, 8.5D);
        for (Face direction : Face.values()) {
            Frame source = Frame.canonical(direction);
            for (Face destinationNormal : Face.values()) {
                Frame destination = Frame.canonical(destinationNormal);
                Vec3d lateral = new Vec3d(source.getRight().x() * 2.5D + source.getUp().x() * 1.25D,
                    source.getRight().y() * 2.5D + source.getUp().y() * 1.25D,
                    source.getRight().z() * 2.5D + source.getUp().z() * 1.25D);
                Vec3d crossing = origin.add(lateral);
                for (int side : new int[]{-1, 1}) {
                    Vec3d expected = OpticTransform.between(source.view(side > 0), origin, destination, arrival).point(crossing);
                    for (double distance : new double[]{96.0D, 32.0D, 13.0D, 1.0D, 0.001D}) {
                        Vec3d feet = crossing.add(new Vec3d(direction.x() * side * distance,
                            direction.y() * side * distance, direction.z() * side * distance));
                        Vec3d actual = PlaneCrossing.planePoint(source, origin, feet, destination, arrival);
                        assertEquals(expected, actual);
                        assertEquals(expected.getBlockX() >> 4, actual.getBlockX() >> 4);
                        assertEquals(expected.getBlockZ() >> 4, actual.getBlockZ() >> 4);
                    }
                }
            }
        }
    }

    @Test
    void approachSideIsChosenBeforeNormalProjectionAndLateralMovementStillMovesTheExit() {
        Frame source = Frame.canonical(Face.S);
        Frame destination = Frame.canonical(Face.E);
        Vec3d origin = new Vec3d(0.5D, 64.0D, 0.5D);
        Vec3d arrival = new Vec3d(100.5D, 80.0D, 100.5D);
        Vec3d front = PlaneCrossing.planePoint(source, origin, new Vec3d(3.0D, 65.5D, 96.5D), destination, arrival);
        Vec3d back = PlaneCrossing.planePoint(source, origin, new Vec3d(3.0D, 65.5D, -95.5D), destination, arrival);
        assertNotEquals(front, back);
        assertEquals(81.5D, front.y());
        assertEquals(81.5D, back.y());
        assertNotEquals(front, PlaneCrossing.planePoint(source, origin, new Vec3d(4.0D, 65.5D, 96.5D), destination, arrival));
    }

    private static Vec3d center(Vec3d min, Vec3d max) {
        ApertureCells geometry = new ApertureCells();
        geometry.setBlocks(List.of(min, max));
        return geometry.getApertureCenter();
    }
}
