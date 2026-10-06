package art.arcane.optics.crossing;

import art.arcane.optics.math.Vec3;
import art.arcane.optics.math.Face;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.frame.Frame;

class PortalCrossingPointTest {
    @Test
    void cellApertureCentersPreserveExactFloorHeightsInBothDirectionsAndSides() {
        Vec3 overworld = center(new Vec3(1001, 200, 0), new Vec3(1001, 205, 0));
        Vec3 nether = center(new Vec3(1103, 80, 0), new Vec3(1103, 85, 0));
        for (Face sourceNormal : new Face[]{Face.N, Face.E, Face.S, Face.W}) {
            for (Face destinationNormal : new Face[]{Face.N, Face.E, Face.S, Face.W}) {
                Frame source = Frame.canonical(sourceNormal);
                Frame destination = Frame.canonical(destinationNormal);
                for (boolean front : new boolean[]{false, true}) {
                    PlaneCrossing outward = new PlaneCrossing(source.view(front), overworld,
                        new Vec3(1001.5D, 200.0D, 0.4785775140992615D),
                        new Vec3(0, 0, -0.1D), new Vec3(0, 0, -1), front);
                    Vec3 arrival = outward.outPoint(destination, nether);
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
        Vec3 origin = center(new Vec3(200, 30, 0), new Vec3(205, 30, 0));
        Vec3 arrival = center(new Vec3(90, 80, 40), new Vec3(90, 85, 40));
        Frame source = Frame.fromNormalUp(Face.S, Face.E);
        for (Face normal : new Face[]{Face.N, Face.E, Face.S, Face.W}) {
            Frame destination = Frame.canonical(normal);
            for (boolean front : new boolean[]{false, true}) {
                PlaneCrossing crossing = new PlaneCrossing(source.view(front), origin,
                    new Vec3(200.0D, 30.5D, 0.4785775140992615D),
                    new Vec3(0, 0, -0.1D), new Vec3(0, 0, -1), front);
                Vec3 mapped = crossing.outPoint(destination, arrival);
                assertEquals(80.0D, mapped.y(), 0.0D);
                double[] primitive = new double[3];
                source.view(front).transformPointInto(crossing.point().x(), crossing.point().y(), crossing.point().z(),
                    origin.x(), origin.y(), origin.z(), arrival.x(), arrival.y(), arrival.z(), destination.view(front), primitive);
                assertEquals(mapped.y(), primitive[1], 0.0D);
                assertEquals(200.0D, destination.view(front).transformPoint(mapped, arrival, origin, source.view(front)).x(), 0.0D);
            }
        }
    }

    @Test
    void translatedAndSignedCardinalTransformsRetainFractionalMotionWithoutFloorSnapping() {
        Frame source = Frame.fromNormalUp(Face.N, Face.U);
        Frame destination = Frame.fromNormalUp(Face.U, Face.N);
        Vec3 origin = new Vec3(11.5D, 200.5D, 20.5D);
        Vec3 arrival = new Vec3(50.5D, 80.5D, -30.5D);
        Vec3 point = new Vec3(12.125D, 200.25D, 19.875D);
        Vec3 transformed = source.transformPoint(point, origin, arrival, destination);
        assertEquals(point, destination.transformPoint(transformed, arrival, origin, source));
        assertEquals(0.625D, Math.abs(transformed.y() - arrival.y()), 0.0D);
    }

    @Test
    void identicalApertureShapesPreserveExactFloorsAcrossHeightSignsAndLargeCoordinates() {
        int[] floors = {-20000000, -64, -1, 0, 1, 63, 80, 127, 200, 319, 20000000};
        Frame frame = Frame.canonical(Face.N);
        for (int sourceFloor : floors) {
            Vec3 source = center(new Vec3(-20000000, sourceFloor, 20000000),
                new Vec3(-19999994, sourceFloor + 5, 20000000));
            for (int targetFloor : floors) {
                Vec3 target = center(new Vec3(20000000, targetFloor, -20000000),
                    new Vec3(20000006, targetFloor + 5, -20000000));
                Vec3 point = new Vec3(-19999998.375D, sourceFloor, 20000000.25D);
                Vec3 mapped = frame.transformPoint(point, source, target, frame);
                assertEquals(targetFloor, mapped.y(), 0.0D);
                assertEquals(targetFloor, mapped.getBlockY());
                assertEquals(point, frame.transformPoint(mapped, target, source, frame));
            }
        }
    }

    @Test
    void allCardinalRollsRetainFractionalCoordinatesAtLargeNegativeAndPositiveOrigins() {
        Vec3 source = center(new Vec3(-20000000, -64, 20000000),
            new Vec3(-19999995, -59, 20000005));
        Vec3 target = center(new Vec3(20000000, 80, -20000000),
            new Vec3(20000005, 85, -19999995));
        Vec3 point = new Vec3(-19999999.375D, -63.125D, 20000001.625D);
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
                        Vec3 mapped = sourceFrame.transformPoint(point, source, target, targetFrame);
                        assertEquals(point, targetFrame.transformPoint(mapped, target, source, sourceFrame));
                    }
                }
            }
        }
    }

    @Test
    void approachingAlongNormalKeepsTheSameMappedCrossingAndManifestColumnOnBothSides() {
        Vec3 origin = new Vec3(1001.5D, 200.5D, 0.5D);
        Vec3 arrival = new Vec3(1106.5D, 85.5D, 8.5D);
        for (Face direction : Face.values()) {
            Frame source = Frame.canonical(direction);
            for (Face destinationNormal : Face.values()) {
                Frame destination = Frame.canonical(destinationNormal);
                Vec3 lateral = new Vec3(source.getRight().x() * 2.5D + source.getUp().x() * 1.25D,
                    source.getRight().y() * 2.5D + source.getUp().y() * 1.25D,
                    source.getRight().z() * 2.5D + source.getUp().z() * 1.25D);
                Vec3 crossing = origin.add(lateral);
                for (int side : new int[]{-1, 1}) {
                    Vec3 expected = source.view(side > 0).transformPoint(crossing, origin, arrival, destination);
                    for (double distance : new double[]{96.0D, 32.0D, 13.0D, 1.0D, 0.001D}) {
                        Vec3 feet = crossing.add(new Vec3(direction.x() * side * distance,
                            direction.y() * side * distance, direction.z() * side * distance));
                        Vec3 actual = source.transformCrossingPoint(feet, origin, arrival, destination);
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
        Vec3 origin = new Vec3(0.5D, 64.0D, 0.5D);
        Vec3 arrival = new Vec3(100.5D, 80.0D, 100.5D);
        Vec3 front = source.transformCrossingPoint(new Vec3(3.0D, 65.5D, 96.5D), origin, arrival, destination);
        Vec3 back = source.transformCrossingPoint(new Vec3(3.0D, 65.5D, -95.5D), origin, arrival, destination);
        assertNotEquals(front, back);
        assertEquals(81.5D, front.y());
        assertEquals(81.5D, back.y());
        assertNotEquals(front, source.transformCrossingPoint(new Vec3(4.0D, 65.5D, 96.5D), origin, arrival, destination));
    }

    private static Vec3 center(Vec3 min, Vec3 max) {
        ApertureCells geometry = new ApertureCells();
        geometry.setBlocks(List.of(min, max));
        return geometry.getApertureCenter();
    }
}
