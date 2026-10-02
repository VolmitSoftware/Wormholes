package art.arcane.wormholes.render.client;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import art.arcane.wormholes.render.PortalCoordMap;
import art.arcane.wormholes.util.Direction;

public final class ClientViewEnvironmentTransform {
    private ClientViewEnvironmentTransform() {
    }

    public static ClientViewEnvironment.Transform of(ClientViewEntityTransform.Frame frame) {
        double[] vector = new double[3];
        Direction x = axis(frame, 1, 0, 0, vector);
        Direction y = axis(frame, 0, 1, 0, vector);
        Direction z = axis(frame, 0, 0, 1, vector);
        if (frame.mirror()) {
            PortalCoordMap.mirrorSourceToDisplayPointInto(0, 0, 0, frame.remoteOriginX(), frame.remoteOriginY(), frame.remoteOriginZ(),
                frame.localFrame(), frame.quarterTurns(), vector);
        } else {
            PortalCoordMap.transformPointInto(0, 0, 0, frame.remoteOriginX(), frame.remoteOriginY(), frame.remoteOriginZ(),
                frame.localOriginX(), frame.localOriginY(), frame.localOriginZ(), frame.remoteViewFrame(), frame.localViewFrame(), vector);
        }
        return new ClientViewEnvironment.Transform(x, y, z, new GeometryVector(vector[0], vector[1], vector[2]));
    }

    private static Direction axis(ClientViewEntityTransform.Frame frame, double x, double y, double z, double[] vector) {
        if (frame.mirror()) {
            PortalCoordMap.mirrorSourceToDisplayVectorInto(x, y, z, frame.localFrame(), frame.quarterTurns(), vector);
        } else {
            frame.remoteViewFrame().transformVectorInto(x, y, z, frame.localViewFrame(), vector);
        }
        return Direction.closest(vector[0], vector[1], vector[2]);
    }
}
