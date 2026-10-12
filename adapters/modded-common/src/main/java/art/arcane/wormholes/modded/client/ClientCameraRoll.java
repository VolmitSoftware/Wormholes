package art.arcane.wormholes.modded.client;

import art.arcane.optics.animation.Easing;
import art.arcane.optics.math.Angles;
import art.arcane.wormholes.modded.mixin.client.CameraPoseAccess;
import net.minecraft.client.Camera;
import org.joml.Quaternionf;

public final class ClientCameraRoll {
    private static final float NEGLIGIBLE_DEGREES = 0.5F;

    private float rollDegrees;
    private long startMillis;
    private long durationMillis;

    public void start(float roll, double seconds, long nowMillis) {
        if (seconds <= 0.0D || !Float.isFinite(roll)) {
            cancel();
            return;
        }
        if (Math.abs(roll) <= NEGLIGIBLE_DEGREES) {
            if (!active(nowMillis)) {
                cancel();
            }
            return;
        }
        rollDegrees = Angles.unwrap(roll - degrees(nowMillis), 0.0F);
        if (Math.abs(rollDegrees) <= NEGLIGIBLE_DEGREES) {
            cancel();
            return;
        }
        startMillis = nowMillis;
        durationMillis = Math.max(1L, Math.round(seconds * 1000.0D));
    }

    public void cancel() {
        durationMillis = 0L;
        rollDegrees = 0.0F;
    }

    public boolean active(long nowMillis) {
        return durationMillis > 0L && nowMillis - startMillis < durationMillis;
    }

    public float degrees(long nowMillis) {
        if (!active(nowMillis)) {
            return 0.0F;
        }
        double progress = Math.max(0.0D, (double) (nowMillis - startMillis) / durationMillis);
        return (float) (-rollDegrees * (1.0D - Easing.CUBIC_OUT.apply(progress)));
    }

    public void apply(Camera camera, long nowMillis) {
        float degrees = degrees(nowMillis);
        if (degrees == 0.0F) {
            if (durationMillis > 0L && !active(nowMillis)) {
                cancel();
            }
            return;
        }
        rotate(camera, degrees);
    }

    static void rotate(Camera camera, float degrees) {
        if (degrees == 0.0F) {
            return;
        }
        Quaternionf rotation = camera.rotation();
        rotation.rotateZ((float) Math.toRadians(degrees));
        CameraPoseAccess access = (CameraPoseAccess) camera;
        access.wormholes$up().set(0.0F, 1.0F, 0.0F).rotate(rotation);
        access.wormholes$left().set(-1.0F, 0.0F, 0.0F).rotate(rotation);
    }
}
