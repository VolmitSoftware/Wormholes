package art.arcane.wormholes.transit;

import art.arcane.wormholes.geometry.GeometryVector;

/** Applies a {@link MomentumPolicy} to the frame-transformed exit velocity. Never mutates its input. */
public final class MomentumTransform {
    private MomentumTransform() {
    }

    public static GeometryVector reflect(GeometryVector velocity, GeometryVector normal, double multiplier) {
        double along = velocity.x() * normal.x() + velocity.y() * normal.y() + velocity.z() * normal.z();
        return velocity.subtract(normal.multiply(2.0D * along)).multiply(multiplier);
    }

    public static GeometryVector apply(GeometryVector outVelocity, MomentumPolicy policy, double maxSpeedConfig) {
        GeometryVector velocity = outVelocity;
        if (policy == null) {
            return velocity;
        }
        double ceiling = policy.maxSpeed() > 0.0D ? policy.maxSpeed() : maxSpeedConfig;
        return switch (policy.mode()) {
            case PRESERVE -> velocity;
            case SCALE -> clamp(velocity.multiply(policy.factor()), ceiling);
            case CLAMP -> clamp(velocity, ceiling);
            case ZERO -> new GeometryVector(0, 0, 0);
            case IMPULSE -> velocity.add(policy.impulse());
        };
    }

    private static double lengthSquared(GeometryVector vector) {
        return vector.x() * vector.x() + vector.y() * vector.y() + vector.z() * vector.z();
    }

    private static GeometryVector clamp(GeometryVector velocity, double ceiling) {
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
