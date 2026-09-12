package art.arcane.wormholes.transit;

import org.bukkit.util.Vector;

/** Applies a {@link MomentumPolicy} to the frame-transformed exit velocity. Never mutates its input. */
public final class MomentumTransform {
    private MomentumTransform() {
    }

    public static Vector apply(Vector outVelocity, MomentumPolicy policy, double maxSpeedConfig) {
        Vector velocity = outVelocity.clone();
        if (policy == null) {
            return velocity;
        }
        double ceiling = policy.maxSpeed() > 0.0D ? policy.maxSpeed() : maxSpeedConfig;
        return switch (policy.mode()) {
            case PRESERVE -> velocity;
            case SCALE -> clamp(velocity.multiply(policy.factor()), ceiling);
            case CLAMP -> clamp(velocity, ceiling);
            case ZERO -> new Vector();
            case IMPULSE -> velocity.add(policy.impulse());
        };
    }

    private static Vector clamp(Vector velocity, double ceiling) {
        if (ceiling <= 0.0D) {
            return velocity;
        }
        double lengthSquared = velocity.lengthSquared();
        if (lengthSquared <= ceiling * ceiling) {
            return velocity;
        }
        return velocity.multiply(ceiling / Math.sqrt(lengthSquared));
    }
}
