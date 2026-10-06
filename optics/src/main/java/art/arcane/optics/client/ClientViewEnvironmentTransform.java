package art.arcane.optics.client;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.stream.ProjectionEnvironment;
import art.arcane.optics.frame.PortalCoordMap;
import art.arcane.optics.math.Face;

public final class ClientViewEnvironmentTransform {
    private ClientViewEnvironmentTransform() {
    }

    public static ProjectionEnvironment.Transform of(ClientViewEntityTransform.EntityFrame frame) {
        double[] vector = new double[3];
        Face x = axis(frame, 1, 0, 0, vector);
        Face y = axis(frame, 0, 1, 0, vector);
        Face z = axis(frame, 0, 0, 1, vector);
        if (frame.mirror()) {
            PortalCoordMap.mirrorSourceToDisplayPointInto(0, 0, 0, frame.remoteOriginX(), frame.remoteOriginY(), frame.remoteOriginZ(),
                frame.localFrame(), frame.quarterTurns(), vector);
        } else {
            PortalCoordMap.transformPointInto(0, 0, 0, frame.remoteOriginX(), frame.remoteOriginY(), frame.remoteOriginZ(),
                frame.localOriginX(), frame.localOriginY(), frame.localOriginZ(), frame.remoteViewFrame(), frame.localViewFrame(), vector);
        }
        return new ProjectionEnvironment.Transform(x, y, z, new Vec3d(vector[0], vector[1], vector[2]));
    }

    private static Face axis(ClientViewEntityTransform.EntityFrame frame, double x, double y, double z, double[] vector) {
        if (frame.mirror()) {
            PortalCoordMap.mirrorSourceToDisplayVectorInto(x, y, z, frame.localFrame(), frame.quarterTurns(), vector);
        } else {
            frame.remoteViewFrame().transformVectorInto(x, y, z, frame.localViewFrame(), vector);
        }
        return Face.closest(vector[0], vector[1], vector[2]);
    }
}
