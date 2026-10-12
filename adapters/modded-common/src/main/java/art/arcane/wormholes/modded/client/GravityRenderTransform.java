package art.arcane.wormholes.modded.client;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.transform.Quaternion;
import org.joml.Quaternionf;

public final class GravityRenderTransform {
    private GravityRenderTransform() {
    }

    public static Quaternionf orientation(GravityFrame frame) {
        return rotation(frame.orientation());
    }

    public static Quaternionf rotation(AxisPermutation permutation) {
        Quaternion rotation = Quaternion.of(permutation);
        return new Quaternionf((float) rotation.x(), (float) rotation.y(), (float) rotation.z(), (float) rotation.w());
    }

    public static Quaternionf camera(GravityFrame frame, float yaw, float pitch) {
        Quaternionf local = new Quaternionf().rotationYXZ((float) Math.PI - (float) Math.toRadians(yaw),
            -(float) Math.toRadians(pitch), 0.0F);
        return orientation(frame).mul(local);
    }

    public static Quaternionf through(AxisPermutation portalRotation, Quaternionf camera) {
        return rotation(portalRotation).mul(camera);
    }
}
