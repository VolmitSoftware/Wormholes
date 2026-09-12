package art.arcane.wormholes.transit;

import java.util.Locale;
import java.util.Objects;

import org.bukkit.util.Vector;

/**
 * How a portal derives a traveler's exit speed from their entry speed. {@code maxSpeed} of zero means
 * "use the configured ceiling"; {@code impulse} is only used by {@link Mode#IMPULSE}.
 */
public record MomentumPolicy(Mode mode, double factor, double maxSpeed, Vector impulse) {
    private static final String SEPARATOR = ";";

    public enum Mode {
        PRESERVE,
        SCALE,
        CLAMP,
        ZERO,
        IMPULSE;

        public static Mode parse(String name, Mode fallback) {
            if (name == null || name.isBlank()) {
                return fallback;
            }
            for (Mode mode : values()) {
                if (mode.name().equalsIgnoreCase(name.trim())) {
                    return mode;
                }
            }
            return fallback;
        }

        public Mode next() {
            Mode[] modes = values();
            return modes[(ordinal() + 1) % modes.length];
        }

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public MomentumPolicy {
        mode = Objects.requireNonNull(mode, "mode");
        factor = Double.isFinite(factor) ? factor : 1.0D;
        maxSpeed = Double.isFinite(maxSpeed) && maxSpeed > 0.0D ? maxSpeed : 0.0D;
        impulse = impulse == null ? new Vector() : impulse.clone();
    }

    public static MomentumPolicy of(Mode mode) {
        return new MomentumPolicy(mode, 1.0D, 0.0D, new Vector());
    }

    @Override
    public Vector impulse() {
        return impulse.clone();
    }

    public MomentumPolicy withMode(Mode nextMode) {
        return new MomentumPolicy(nextMode, factor, maxSpeed, impulse);
    }

    public MomentumPolicy withFactor(double nextFactor) {
        return new MomentumPolicy(mode, nextFactor, maxSpeed, impulse);
    }

    public String encode() {
        return mode.name() + SEPARATOR + factor + SEPARATOR + maxSpeed + SEPARATOR
            + impulse.getX() + "," + impulse.getY() + "," + impulse.getZ();
    }

    /** Returns null for anything that is not a complete encoding. */
    public static MomentumPolicy decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        String[] parts = encoded.split(SEPARATOR, -1);
        if (parts.length != 4) {
            return null;
        }
        Mode mode = Mode.parse(parts[0], null);
        if (mode == null) {
            return null;
        }
        String[] impulseParts = parts[3].split(",", -1);
        if (impulseParts.length != 3) {
            return null;
        }
        try {
            double factor = Double.parseDouble(parts[1]);
            double maxSpeed = Double.parseDouble(parts[2]);
            Vector impulse = new Vector(
                Double.parseDouble(impulseParts[0]),
                Double.parseDouble(impulseParts[1]),
                Double.parseDouble(impulseParts[2]));
            return new MomentumPolicy(mode, factor, maxSpeed, impulse);
        } catch (NumberFormatException malformed) {
            return null;
        }
    }
}
