package art.arcane.wormholes.portal;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.util.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PortalCrossingPointTest {
    @Test
    void cellApertureCentersPreserveExactFloorHeightsInBothDirectionsAndSides() {
        GeometryVector overworld = center(new GeometryVector(1001, 200, 0), new GeometryVector(1001, 205, 0));
        GeometryVector nether = center(new GeometryVector(1103, 80, 0), new GeometryVector(1103, 85, 0));
        for (Direction sourceNormal : new Direction[]{Direction.N, Direction.E, Direction.S, Direction.W}) {
            for (Direction destinationNormal : new Direction[]{Direction.N, Direction.E, Direction.S, Direction.W}) {
                PortalFrame source = PortalFrame.canonical(sourceNormal);
                PortalFrame destination = PortalFrame.canonical(destinationNormal);
                for (boolean front : new boolean[]{false, true}) {
                    PortalCrossing outward = new PortalCrossing(source.view(front), overworld,
                        new GeometryVector(1001.5D, 200.0D, 0.4785775140992615D),
                        new GeometryVector(0, 0, -0.1D), new GeometryVector(0, 0, -1), front);
                    GeometryVector arrival = outward.outPoint(destination, nether);
                    assertEquals(80.0D, arrival.y(), 0.0D);
                    assertEquals(80, arrival.getBlockY());
                    PortalCrossing returning = new PortalCrossing(destination.view(front), nether, arrival,
                        outward.outVelocity(destination), outward.outLook(destination), front);
                    assertEquals(200.0D, returning.outPoint(source, overworld).y(), 0.0D);
                }
            }
        }
    }

    @Test
    void rolledFramesPreserveAnExactStandingCoordinateWhenItBecomesDestinationHeight() {
        GeometryVector origin = center(new GeometryVector(200, 30, 0), new GeometryVector(205, 30, 0));
        GeometryVector arrival = center(new GeometryVector(90, 80, 40), new GeometryVector(90, 85, 40));
        PortalFrame source = PortalFrame.fromNormalUp(Direction.S, Direction.E);
        for (Direction normal : new Direction[]{Direction.N, Direction.E, Direction.S, Direction.W}) {
            PortalFrame destination = PortalFrame.canonical(normal);
            for (boolean front : new boolean[]{false, true}) {
                PortalCrossing crossing = new PortalCrossing(source.view(front), origin,
                    new GeometryVector(200.0D, 30.5D, 0.4785775140992615D),
                    new GeometryVector(0, 0, -0.1D), new GeometryVector(0, 0, -1), front);
                GeometryVector mapped = crossing.outPoint(destination, arrival);
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
        PortalFrame source = PortalFrame.fromNormalUp(Direction.N, Direction.U);
        PortalFrame destination = PortalFrame.fromNormalUp(Direction.U, Direction.N);
        GeometryVector origin = new GeometryVector(11.5D, 200.5D, 20.5D);
        GeometryVector arrival = new GeometryVector(50.5D, 80.5D, -30.5D);
        GeometryVector point = new GeometryVector(12.125D, 200.25D, 19.875D);
        GeometryVector transformed = source.transformPoint(point, origin, arrival, destination);
        assertEquals(point, destination.transformPoint(transformed, arrival, origin, source));
        assertEquals(0.625D, Math.abs(transformed.y() - arrival.y()), 0.0D);
    }

    @Test
    void identicalApertureShapesPreserveExactFloorsAcrossHeightSignsAndLargeCoordinates() {
        int[] floors = {-20000000, -64, -1, 0, 1, 63, 80, 127, 200, 319, 20000000};
        PortalFrame frame = PortalFrame.canonical(Direction.N);
        for (int sourceFloor : floors) {
            GeometryVector source = center(new GeometryVector(-20000000, sourceFloor, 20000000),
                new GeometryVector(-19999994, sourceFloor + 5, 20000000));
            for (int targetFloor : floors) {
                GeometryVector target = center(new GeometryVector(20000000, targetFloor, -20000000),
                    new GeometryVector(20000006, targetFloor + 5, -20000000));
                GeometryVector point = new GeometryVector(-19999998.375D, sourceFloor, 20000000.25D);
                GeometryVector mapped = frame.transformPoint(point, source, target, frame);
                assertEquals(targetFloor, mapped.y(), 0.0D);
                assertEquals(targetFloor, mapped.getBlockY());
                assertEquals(point, frame.transformPoint(mapped, target, source, frame));
            }
        }
    }

    @Test
    void allCardinalRollsRetainFractionalCoordinatesAtLargeNegativeAndPositiveOrigins() {
        GeometryVector source = center(new GeometryVector(-20000000, -64, 20000000),
            new GeometryVector(-19999995, -59, 20000005));
        GeometryVector target = center(new GeometryVector(20000000, 80, -20000000),
            new GeometryVector(20000005, 85, -19999995));
        GeometryVector point = new GeometryVector(-19999999.375D, -63.125D, 20000001.625D);
        for (Direction sourceNormal : Direction.values()) {
            for (Direction sourceUp : Direction.values()) {
                if (sourceNormal.getAxis() == sourceUp.getAxis()) {
                    continue;
                }
                PortalFrame sourceFrame = PortalFrame.fromNormalUp(sourceNormal, sourceUp);
                for (Direction targetNormal : Direction.values()) {
                    for (Direction targetUp : Direction.values()) {
                        if (targetNormal.getAxis() == targetUp.getAxis()) {
                            continue;
                        }
                        PortalFrame targetFrame = PortalFrame.fromNormalUp(targetNormal, targetUp);
                        GeometryVector mapped = sourceFrame.transformPoint(point, source, target, targetFrame);
                        assertEquals(point, targetFrame.transformPoint(mapped, target, source, sourceFrame));
                    }
                }
            }
        }
    }

    @Test
    void approachingAlongNormalKeepsTheSameMappedCrossingAndManifestColumnOnBothSides() {
        GeometryVector origin = new GeometryVector(1001.5D, 200.5D, 0.5D);
        GeometryVector arrival = new GeometryVector(1106.5D, 85.5D, 8.5D);
        for (Direction direction : Direction.values()) {
            PortalFrame source = PortalFrame.canonical(direction);
            for (Direction destinationNormal : Direction.values()) {
                PortalFrame destination = PortalFrame.canonical(destinationNormal);
                GeometryVector lateral = new GeometryVector(source.getRight().x() * 2.5D + source.getUp().x() * 1.25D,
                    source.getRight().y() * 2.5D + source.getUp().y() * 1.25D,
                    source.getRight().z() * 2.5D + source.getUp().z() * 1.25D);
                GeometryVector crossing = origin.add(lateral);
                for (int side : new int[]{-1, 1}) {
                    GeometryVector expected = source.view(side > 0).transformPoint(crossing, origin, arrival, destination);
                    for (double distance : new double[]{96.0D, 32.0D, 13.0D, 1.0D, 0.001D}) {
                        GeometryVector feet = crossing.add(new GeometryVector(direction.x() * side * distance,
                            direction.y() * side * distance, direction.z() * side * distance));
                        GeometryVector actual = source.transformCrossingPoint(feet, origin, arrival, destination);
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
        PortalFrame source = PortalFrame.canonical(Direction.S);
        PortalFrame destination = PortalFrame.canonical(Direction.E);
        GeometryVector origin = new GeometryVector(0.5D, 64.0D, 0.5D);
        GeometryVector arrival = new GeometryVector(100.5D, 80.0D, 100.5D);
        GeometryVector front = source.transformCrossingPoint(new GeometryVector(3.0D, 65.5D, 96.5D), origin, arrival, destination);
        GeometryVector back = source.transformCrossingPoint(new GeometryVector(3.0D, 65.5D, -95.5D), origin, arrival, destination);
        assertNotEquals(front, back);
        assertEquals(81.5D, front.y());
        assertEquals(81.5D, back.y());
        assertNotEquals(front, source.transformCrossingPoint(new GeometryVector(4.0D, 65.5D, 96.5D), origin, arrival, destination));
    }

    private static GeometryVector center(GeometryVector min, GeometryVector max) {
        PortalGeometry geometry = new PortalGeometry();
        geometry.setBlocks(List.of(min, max));
        return geometry.getApertureCenter();
    }
}
