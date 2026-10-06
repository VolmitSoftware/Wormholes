package art.arcane.wormholes.door;

import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.math.Angles;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoorArrivalFacingTest {
    @Test
    void forwardLookAndMomentumLeaveTheChosenSideForEveryVerticalDoorFrameAndApproach() {
        for (Face sourceFacing : new Face[] {Face.N, Face.S, Face.E, Face.W}) {
            for (Face targetFacing : new Face[] {Face.N, Face.S, Face.E, Face.W}) {
                DoorwayPlane source = new DoorwayPlane(0, 64, 0, sourceFacing);
                DoorwayPlane target = new DoorwayPlane(10, 64, 10, targetFacing);
                for (boolean frontSide : new boolean[] {true, false}) {
                    for (int side : new int[] {-1, 1}) {
                        for (float offset : new float[] {-30, 0, 30}) {
                            float yaw = (float) Math.toDegrees(Math.atan2(-source.normalX() * (frontSide ? -1 : 1),
                                source.normalZ() * (frontSide ? -1 : 1))) + offset;
                            DoorTransit transit = new DoorTransit(source, frontSide, yaw, 17);
                            float arrival = DoorArrivals.arrivalFacing(target, transit, side).yaw();
                            Vec3d forward = heading(arrival);
                            Vec3d velocity = Angles.rotateYaw(heading(yaw), arrival - yaw);
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
        for (Face sourceFacing : new Face[] {Face.N, Face.S, Face.E, Face.W}) {
            for (Face targetFacing : new Face[] {Face.N, Face.S, Face.E, Face.W}) {
                for (DoorForm sourceForm : DoorForm.values()) {
                    for (DoorForm targetForm : DoorForm.values()) {
                        DoorwayPlane source = new DoorwayPlane(0, 64, 0, sourceFacing, sourceForm, DoorHalf.BOTTOM, DoorOpenState.OPEN);
                        DoorwayPlane target = new DoorwayPlane(10, 64, 10, targetFacing, targetForm, DoorHalf.BOTTOM, DoorOpenState.OPEN);
                        for (boolean frontSide : new boolean[] {true, false}) {
                            Vec3d look = new Vec3d(source.normalX() * (frontSide ? -1 : 1),
                                source.normalY() * (frontSide ? -1 : 1), source.normalZ() * (frontSide ? -1 : 1));
                            float yaw = (float) Math.toDegrees(Math.atan2(-look.x(), look.z()));
                            float pitch = (float) Math.toDegrees(Math.atan2(-look.y(), Math.hypot(look.x(), look.z())));
                            DoorTransit transit = new DoorTransit(source, frontSide, yaw, pitch);
                            for (int side : new int[] {-1, 1}) {
                                DoorArrivals.Facing facing = DoorArrivals.arrivalFacing(target, transit, side);
                                double radians = Math.toRadians(facing.yaw());
                                double tilt = Math.toRadians(facing.pitch());
                                Vec3d actual = new Vec3d(-Math.sin(radians) * Math.cos(tilt), -Math.sin(tilt), Math.cos(radians) * Math.cos(tilt));
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
        DoorwayPlane target = new DoorwayPlane(10, 64, 10, Face.S);
        for (Face facing : new Face[] {Face.N, Face.S, Face.E, Face.W}) {
            DoorwayPlane source = new DoorwayPlane(0, 64, 0, facing);
            for (boolean frontSide : new boolean[] {true, false}) {
                float yaw = (float) Math.toDegrees(Math.atan2(-source.normalX() * (frontSide ? -1 : 1),
                    source.normalZ() * (frontSide ? -1 : 1)));
                DoorTransit transit = new DoorTransit(source, frontSide, yaw, 12);
                Vec3d forward = heading(DoorArrivals.arrivalFacing(target, transit, -1).yaw());
                assertTrue(forward.z() < -0.99);
            }
        }
    }

    private static Vec3d heading(float yaw) {
        double radians = Math.toRadians(yaw);
        return new Vec3d(-Math.sin(radians), 0, Math.cos(radians));
    }

    private static double angleFromOutward(Vec3d look, DoorwayPlane target, int side) {
        double x = target.normalX() * side;
        double z = target.normalZ() * side;
        return Math.toDegrees(Math.atan2(look.z() * x - look.x() * z, look.x() * x + look.z() * z));
    }
}
