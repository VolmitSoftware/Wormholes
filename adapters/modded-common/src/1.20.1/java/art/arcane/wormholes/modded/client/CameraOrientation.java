package art.arcane.wormholes.modded.client;

import net.minecraft.client.Camera;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;

/**
 * Converts between the camera quaternion Minecraft 1.20.1 stores (forward +Z) and the 26.x convention (forward -Z) that
 * {@link GravityRenderTransform} and {@link GravityCameraAccess#wormholes$orientation(Quaternionf)} use. The two differ
 * by a half turn about the local Y axis, and the 1.20.1 view rotation is the conjugate of the 26.x quaternion.
 */
public final class CameraOrientation {
    private static final float HALF_TURN = (float) Math.PI;

    private CameraOrientation() {
    }

    /** The camera orientation in the 26.x convention. */
    public static Quaternionf of(Camera camera) {
        return new Quaternionf(camera.rotation()).rotateY(HALF_TURN);
    }

    /** The 1.20.1 camera quaternion for a 26.x-convention orientation. */
    public static Quaternionf stored(Quaternionfc orientation) {
        return new Quaternionf(orientation).rotateY(HALF_TURN);
    }

    /** The world-to-view rotation of the camera. */
    public static Quaternionf view(Camera camera) {
        return of(camera).conjugate();
    }

    /** Whether the camera orientation was set directly instead of from its yaw and pitch. */
    public static boolean oriented(Camera camera) {
        return camera instanceof Holder holder && holder.wormholes$oriented();
    }

    /** Implemented on {@link Camera} by the gravity camera mixin. */
    public interface Holder {
        boolean wormholes$oriented();
    }
}
