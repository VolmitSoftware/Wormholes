package art.arcane.optics.math;

public final class Angles {
    private static final double TWO_PI = 2.0D * Math.PI;
    private static final double FULL_TURN = 360.0D;

    private Angles() {
    }

    public static float yaw(double x, double z) {
        return (float) Math.toDegrees(Math.atan2(-x, z));
    }

    public static float pitch(double x, double y, double z) {
        return (float) Math.toDegrees(-Math.atan2(y, Math.sqrt(x * x + z * z)));
    }

    public static Look look(double x, double y, double z) {
        return new Look(yaw(x, z), pitch(x, y, z));
    }

    public static void directionInto(float yaw, float pitch, double[] out3) {
        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);
        double horizontal = Math.cos(pitchRadians);
        out3[0] = -horizontal * Math.sin(yawRadians);
        out3[1] = -Math.sin(pitchRadians);
        out3[2] = horizontal * Math.cos(yawRadians);
    }

    public static Vec3d direction(float yaw, float pitch) {
        double[] direction = new double[3];
        directionInto(yaw, pitch, direction);
        return new Vec3d(direction[0], direction[1], direction[2]);
    }

    public static float rotateYaw(float yaw, int quarterTurnsClockwise) {
        return yaw + 90.0F * quarterTurnsClockwise;
    }

    public static Vec3d rotateYaw(Vec3d vector, float yawDelta) {
        if (!Float.isFinite(yawDelta)) {
            throw new IllegalArgumentException("Yaw delta must be finite");
        }
        double radians = Math.toRadians(yawDelta);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new Vec3d((vector.x() * cos) - (vector.z() * sin), vector.y(), (vector.z() * cos) + (vector.x() * sin));
    }

    public static float unwrap(float angle, float reference) {
        return (float) (angle + FULL_TURN * Math.floor((reference - angle) / FULL_TURN + 0.5D));
    }

    public static Vec3d reflect(Vec3d vector, Vec3d normal) {
        return vector.subtract(normal.multiply(2.0D * vector.dot(normal)));
    }

    public record Look(float yaw, float pitch) {
        public static Look of(Vec3d direction) {
            double x = direction.x();
            double y = direction.y();
            double z = direction.z();
            if (x == 0.0D && z == 0.0D) {
                return new Look(0.0F, y > 0.0D ? -90.0F : 90.0F);
            }
            double theta = Math.atan2(-x, z);
            float yaw = (float) Math.toDegrees((theta + TWO_PI) % TWO_PI);
            float pitch = (float) Math.toDegrees(Math.atan(-y / Math.sqrt(x * x + z * z)));
            return new Look(yaw, pitch);
        }
    }
}
