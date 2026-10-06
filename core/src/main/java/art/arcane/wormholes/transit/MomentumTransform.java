package art.arcane.wormholes.transit;

import art.arcane.optics.math.Vec3d;

/** Applies a {@link MomentumPolicy} to the frame-transformed exit velocity. Never mutates its input. */
public final class MomentumTransform {
    private MomentumTransform() {
    }

    public static Vec3d reflect(Vec3d velocity, Vec3d normal, double multiplier) {
        double along = velocity.x() * normal.x() + velocity.y() * normal.y() + velocity.z() * normal.z();
        return velocity.subtract(normal.multiply(2.0D * along)).multiply(multiplier);
    }

    public static Vec3d apply(Vec3d outVelocity, MomentumPolicy policy, double maxSpeedConfig) {
        Vec3d velocity = outVelocity;
        if (policy == null) {
            return velocity;
        }
        double ceiling = policy.maxSpeed() > 0.0D ? policy.maxSpeed() : maxSpeedConfig;
        return switch (policy.mode()) {
            case PRESERVE -> velocity;
            case SCALE -> clamp(velocity.multiply(policy.factor()), ceiling);
            case CLAMP -> clamp(velocity, ceiling);
            case ZERO -> new Vec3d(0, 0, 0);
            case IMPULSE -> velocity.add(policy.impulse());
        };
    }

    private static double lengthSquared(Vec3d vector) {
        return vector.x() * vector.x() + vector.y() * vector.y() + vector.z() * vector.z();
    }

    private static Vec3d clamp(Vec3d velocity, double ceiling) {
        if (ceiling <= 0.0D) {
            return velocity;
        }
        double lengthSquared = lengthSquared(velocity);
        if (lengthSquared <= ceiling * ceiling) {
            return velocity;
        }
        return velocity.multiply(ceiling / Math.sqrt(lengthSquared));
    }
}
