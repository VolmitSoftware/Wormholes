package art.arcane.optics.crossing;

import java.util.Objects;

import art.arcane.optics.math.Vec3d;

public record MomentumRule(Mode mode, double factor, double maxSpeed, Vec3d impulse) {
    private static final Vec3d NO_IMPULSE = new Vec3d(0.0D, 0.0D, 0.0D);

    public MomentumRule {
        mode = Objects.requireNonNull(mode, "mode");
        factor = Double.isFinite(factor) ? factor : 1.0D;
        maxSpeed = Double.isFinite(maxSpeed) && maxSpeed > 0.0D ? maxSpeed : 0.0D;
        impulse = impulse == null ? NO_IMPULSE : impulse;
    }

    public enum Mode {
        PRESERVE,
        SCALE,
        CLAMP,
        ZERO,
        IMPULSE
    }
}
