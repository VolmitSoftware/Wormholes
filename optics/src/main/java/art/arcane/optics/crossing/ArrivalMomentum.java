package art.arcane.optics.crossing;

import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Vec3d;

public final class ArrivalMomentum {
    private static final Vec3d ZERO = new Vec3d(0.0D, 0.0D, 0.0D);

    private ArrivalMomentum() {
    }

    public static Vec3d apply(Vec3d outVelocity, MomentumRule rule, double maxSpeedDefault) {
        if (rule == null) {
            return outVelocity;
        }
        double ceiling = rule.maxSpeed() > 0.0D ? rule.maxSpeed() : maxSpeedDefault;
        return switch (rule.mode()) {
            case PRESERVE -> outVelocity;
            case SCALE -> clamp(outVelocity.multiply(rule.factor()), ceiling);
            case CLAMP -> clamp(outVelocity, ceiling);
            case ZERO -> ZERO;
            case IMPULSE -> outVelocity.add(rule.impulse());
        };
    }

    public static Vec3d reflect(Vec3d velocity, Vec3d normal, double multiplier) {
        return Angles.reflect(velocity, normal).multiply(multiplier);
    }

    private static Vec3d clamp(Vec3d velocity, double ceiling) {
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
