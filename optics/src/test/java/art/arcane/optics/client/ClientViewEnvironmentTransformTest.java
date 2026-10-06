package art.arcane.optics.client;

import art.arcane.optics.math.Vec3;
import art.arcane.optics.stream.ProjectionEnvironment;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.PortalCoordMap;
import art.arcane.optics.math.Face;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientViewEnvironmentTransformTest {
    @Test
    void inverseCameraMappingMatchesTerrainForEveryOrientationAndSide() {
        for (Face local : Face.values()) {
            for (Face remote : Face.values()) {
                for (boolean front : new boolean[] {false, true}) {
                    verify(new ClientViewEntityTransform.EntityFrame(-31.5D, 68.0D, -2.5D, Frame.canonical(local),
                        124.5D, -16.0D, 241.5D, Frame.canonical(remote), false, 0, front, 160));
                }
            }
        }
    }

    @Test
    void mirrorRotationAndNegativeOriginsPreserveDestinationEye() {
        for (Face normal : Face.values()) {
            for (int turns = 0; turns < 4; turns++) {
                verify(new ClientViewEntityTransform.EntityFrame(-31.5D, 68.0D, -2.5D, Frame.canonical(normal),
                    -31.5D, 68.0D, -2.5D, Frame.canonical(normal), true, turns, true, 160));
            }
        }
    }

    private static void verify(ClientViewEntityTransform.EntityFrame frame) {
        double[] display = new double[3];
        if (frame.mirror()) {
            PortalCoordMap.mirrorSourceToDisplayPointInto(138.125D, 92.25D, -134.5D, frame.remoteOriginX(), frame.remoteOriginY(),
                frame.remoteOriginZ(), frame.localFrame(), frame.quarterTurns(), display);
        } else {
            PortalCoordMap.transformPointInto(138.125D, 92.25D, -134.5D, frame.remoteOriginX(), frame.remoteOriginY(), frame.remoteOriginZ(),
                frame.localOriginX(), frame.localOriginY(), frame.localOriginZ(), frame.remoteViewFrame(), frame.localViewFrame(), display);
        }
        ProjectionEnvironment.Transform transform = ClientViewEnvironmentTransform.of(frame);
        Vec3 result = transform.destinationPoint(display[0], display[1], display[2]);
        assertEquals(138.125D, result.x(), 0.00001D);
        assertEquals(92.25D, result.y(), 0.00001D);
        assertEquals(-134.5D, result.z(), 0.00001D);
    }
}
