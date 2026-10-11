package art.arcane.wormholes.portal;

import art.arcane.optics.crossing.ArrivalOrientation;
import art.arcane.optics.crossing.OrientationRule;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PortalConstructionTest {
    @Test
    void floorAndCeilingCreatedFromInsideTheRoomPreserveFallingInBothDirections() {
        Frame floor = horizontalFrame(new Vec3d(0, -1, 0));
        Frame ceiling = horizontalFrame(new Vec3d(0, 1, 0));
        assertEquals(floor, ceiling);
        Vec3d floorOrigin = new Vec3d(0, 80.5, 0);
        Vec3d ceilingOrigin = new Vec3d(0, 100.5, 0);
        Vec3d falling = new Vec3d(0, -3.9, 0);
        for (boolean front : new boolean[]{true, false}) {
            Similarity upward = Similarity.between(floor.view(front), floorOrigin, ceiling.view(front), ceilingOrigin, 1);
            assertEquals(falling, upward.vector(falling));
            assertEquals(new Vec3d(1, 100.25, 1), upward.point(new Vec3d(1, 80.25, 1)));
            assertEquals(falling, upward.inverse().vector(falling));
        }
    }

    @Test
    void horizontalYawChangesRetainVerticalMomentum() {
        Frame floor = horizontalFrame(new Vec3d(1, -1, 0));
        Frame ceiling = horizontalFrame(new Vec3d(0, 1, -1));
        assertEquals(floor, ceiling);
        Similarity transform = Similarity.between(floor, Vec3d.ZERO, ceiling, new Vec3d(0, 20, 0), 1);
        Vec3d falling = transform.vector(new Vec3d(0, -3, 0));
        assertEquals(0, falling.x(), 0);
        assertEquals(-3, falling.y(), 0);
        assertEquals(0, falling.z(), 0);
        assertEquals(Face.D, ceiling.getNormal());
    }

    @Test
    void horizontalConstructionFramesIgnoreCreatorYawAndPitch() {
        for (float yaw : new float[]{-180, -135, -90, -45, 0, 45, 90, 135, 180}) {
            for (float pitch : new float[]{-90, -60, 0, 60, 90}) {
                Frame frame = horizontalFrame(Angles.direction(yaw, pitch));
                assertEquals(Face.D, frame.getNormal());
                assertEquals(Face.N, frame.getUp());
                assertEquals(Frame.canonical(Face.D), frame);
            }
        }
    }

    @Test
    void repeatedHorizontalFrameArrivalsPreserveLookOnBothFaces() {
        Frame floor = horizontalFrame(new Vec3d(1, -1, 0));
        Frame ceiling = horizontalFrame(new Vec3d(0, 1, -1));
        for (boolean front : new boolean[]{true, false}) {
            for (float yaw : new float[]{-179, -90, -15, 0, 47, 90, 179}) {
                for (float pitch : new float[]{-90, -89.99F, -45, 0, 45, 89.99F, 90}) {
                    Angles.Look look = new Angles.Look(yaw, pitch);
                    for (int crossingIndex = 0; crossingIndex < 40; crossingIndex++) {
                        Frame source = crossingIndex % 2 == 0 ? floor : ceiling;
                        Frame destination = crossingIndex % 2 == 0 ? ceiling : floor;
                        PlaneCrossing crossing = new PlaneCrossing(source.view(front), Vec3d.ZERO, Vec3d.ZERO,
                            new Vec3d(0, -3.9, 0), Angles.direction(look.yaw(), look.pitch()), front);
                        look = ArrivalOrientation.apply(crossing, destination, OrientationRule.FRAME, false);
                        assertEquals(0, Angles.unwrap(look.yaw() - yaw, 0), 0.0001F);
                        assertEquals(pitch, look.pitch(), 0.0001F);
                    }
                }
            }
        }
    }

    @Test
    void explicitHorizontalFrameRotationStillTurnsArrivingLook() {
        Frame source = horizontalFrame(new Vec3d(1, -1, 0));
        Frame destination = source.rotateClockwise();
        PlaneCrossing crossing = new PlaneCrossing(source, Vec3d.ZERO, Vec3d.ZERO, new Vec3d(0, -3.9, 0),
            Angles.direction(25, 17), true);
        Angles.Look arrived = ArrivalOrientation.apply(crossing, destination, OrientationRule.FRAME, false);
        assertEquals(90, Math.abs(Angles.unwrap(arrived.yaw() - 25, 0)), 0.0001F);
        assertEquals(17, arrived.pitch(), 0.0001F);
    }

    private static Frame horizontalFrame(Vec3d look) {
        Face normal = PortalConstruction.derivePortalNormal(2, 0, 2, look.x(), look.y(), look.z());
        return Frame.canonical(normal);
    }
}
