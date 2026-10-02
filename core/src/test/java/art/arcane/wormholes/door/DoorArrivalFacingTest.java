package art.arcane.wormholes.door;

import art.arcane.wormholes.util.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoorArrivalFacingTest {
    @Test
    void forwardLookAndMomentumLeaveTheChosenSideForEveryVerticalDoorFrameAndApproach() {
        for (Direction sourceFacing : new Direction[] {Direction.N, Direction.S, Direction.E, Direction.W}) {
            for (Direction targetFacing : new Direction[] {Direction.N, Direction.S, Direction.E, Direction.W}) {
                DoorwayPlane source = new DoorwayPlane(0, 64, 0, sourceFacing);
                DoorwayPlane target = new DoorwayPlane(10, 64, 10, targetFacing);
                for (DoorwayCrossing.Direction direction : DoorwayCrossing.Direction.values()) {
                    for (int side : new int[] {-1, 1}) {
                        for (float offset : new float[] {-30, 0, 30}) {
                            float yaw = (float) Math.toDegrees(Math.atan2(-source.normalX() * direction.exitSideSign(),
                                source.normalZ() * direction.exitSideSign())) + offset;
                            DoorTransit transit = new DoorTransit(source, direction, yaw, 17);
                            float arrival = DoorArrivals.arrivalFacing(target, transit, side).yaw();
                            DoorVec3 forward = heading(arrival);
                            DoorVec3 velocity = DoorVelocityTransform.rotateYaw(heading(yaw), arrival - yaw);
                            assertTrue((forward.x() * target.normalX() + forward.z() * target.normalZ()) * side > 0.8);
                            assertEquals(forward.x(), velocity.x(), 1.0E-6);
                            assertEquals(forward.z(), velocity.z(), 1.0E-6);
                            assertEquals(offset, angleFromOutward(forward, target, side), 1.0E-5);
                        }
                    }
                }
            }
        }
    }

    @Test
    void trapdoorAndMixedFramesKeepLookAlignedWithTheExitTrajectory() {
        for (Direction sourceFacing : new Direction[] {Direction.N, Direction.S, Direction.E, Direction.W}) {
            for (Direction targetFacing : new Direction[] {Direction.N, Direction.S, Direction.E, Direction.W}) {
                for (DoorForm sourceForm : DoorForm.values()) {
                    for (DoorForm targetForm : DoorForm.values()) {
                        DoorwayPlane source = new DoorwayPlane(0, 64, 0, sourceFacing, sourceForm, DoorHalf.BOTTOM, DoorOpenState.OPEN);
                        DoorwayPlane target = new DoorwayPlane(10, 64, 10, targetFacing, targetForm, DoorHalf.BOTTOM, DoorOpenState.OPEN);
                        for (DoorwayCrossing.Direction direction : DoorwayCrossing.Direction.values()) {
                            DoorVec3 look = new DoorVec3(source.normalX() * direction.exitSideSign(),
                                source.normalY() * direction.exitSideSign(), source.normalZ() * direction.exitSideSign());
                            float yaw = (float) Math.toDegrees(Math.atan2(-look.x(), look.z()));
                            float pitch = (float) Math.toDegrees(Math.atan2(-look.y(), Math.hypot(look.x(), look.z())));
                            DoorTransit transit = new DoorTransit(source, direction, yaw, pitch);
                            for (int side : new int[] {-1, 1}) {
                                DoorArrivals.Facing facing = DoorArrivals.arrivalFacing(target, transit, side);
                                double radians = Math.toRadians(facing.yaw());
                                double tilt = Math.toRadians(facing.pitch());
                                DoorVec3 actual = new DoorVec3(-Math.sin(radians) * Math.cos(tilt), -Math.sin(tilt), Math.cos(radians) * Math.cos(tilt));
                                assertEquals(target.normalX() * side, actual.x(), 1.0E-6);
                                assertEquals(target.normalY() * side, actual.y(), 1.0E-6);
                                assertEquals(target.normalZ() * side, actual.z(), 1.0E-6);
                                assertTrue(Float.isFinite(facing.yaw()) && Float.isFinite(facing.pitch()));
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void pocketEntryFacesIntoTheRoomFromEitherSourceSide() {
        DoorwayPlane target = new DoorwayPlane(10, 64, 10, Direction.S);
        for (Direction facing : new Direction[] {Direction.N, Direction.S, Direction.E, Direction.W}) {
            DoorwayPlane source = new DoorwayPlane(0, 64, 0, facing);
            for (DoorwayCrossing.Direction direction : DoorwayCrossing.Direction.values()) {
                float yaw = (float) Math.toDegrees(Math.atan2(-source.normalX() * direction.exitSideSign(),
                    source.normalZ() * direction.exitSideSign()));
                DoorTransit transit = new DoorTransit(source, direction, yaw, 12);
                DoorVec3 forward = heading(DoorArrivals.arrivalFacing(target, transit, -1).yaw());
                assertTrue(forward.z() < -0.99);
            }
        }
    }

    private static DoorVec3 heading(float yaw) {
        double radians = Math.toRadians(yaw);
        return new DoorVec3(-Math.sin(radians), 0, Math.cos(radians));
    }

    private static double angleFromOutward(DoorVec3 look, DoorwayPlane target, int side) {
        double x = target.normalX() * side;
        double z = target.normalZ() * side;
        return Math.toDegrees(Math.atan2(look.z() * x - look.x() * z, look.x() * x + look.z() * z));
    }
}
