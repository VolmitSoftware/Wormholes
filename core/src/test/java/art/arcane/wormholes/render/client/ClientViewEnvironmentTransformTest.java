package art.arcane.wormholes.render.client;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.render.PortalCoordMap;
import art.arcane.wormholes.util.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientViewEnvironmentTransformTest {
    @Test
    void inverseCameraMappingMatchesTerrainForEveryOrientationAndSide() {
        for (Direction local : Direction.values()) {
            for (Direction remote : Direction.values()) {
                for (boolean front : new boolean[] {false, true}) {
                    verify(new ClientViewEntityTransform.Frame(-31.5D, 68.0D, -2.5D, PortalFrame.canonical(local),
                        124.5D, -16.0D, 241.5D, PortalFrame.canonical(remote), false, 0, front, 160));
                }
            }
        }
    }

    @Test
    void mirrorRotationAndNegativeOriginsPreserveDestinationEye() {
        for (Direction normal : Direction.values()) {
            for (int turns = 0; turns < 4; turns++) {
                verify(new ClientViewEntityTransform.Frame(-31.5D, 68.0D, -2.5D, PortalFrame.canonical(normal),
                    -31.5D, 68.0D, -2.5D, PortalFrame.canonical(normal), true, turns, true, 160));
            }
        }
    }

    private static void verify(ClientViewEntityTransform.Frame frame) {
        double[] display = new double[3];
        if (frame.mirror()) {
            PortalCoordMap.mirrorSourceToDisplayPointInto(138.125D, 92.25D, -134.5D, frame.remoteOriginX(), frame.remoteOriginY(),
                frame.remoteOriginZ(), frame.localFrame(), frame.quarterTurns(), display);
        } else {
            PortalCoordMap.transformPointInto(138.125D, 92.25D, -134.5D, frame.remoteOriginX(), frame.remoteOriginY(), frame.remoteOriginZ(),
                frame.localOriginX(), frame.localOriginY(), frame.localOriginZ(), frame.remoteViewFrame(), frame.localViewFrame(), display);
        }
        ClientViewEnvironment.Transform transform = ClientViewEnvironmentTransform.of(frame);
        GeometryVector result = transform.destinationPoint(display[0], display[1], display[2]);
        assertEquals(138.125D, result.x(), 0.00001D);
        assertEquals(92.25D, result.y(), 0.00001D);
        assertEquals(-134.5D, result.z(), 0.00001D);
    }
}
