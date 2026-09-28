package art.arcane.wormholes.transit;

import java.util.Locale;

/** Which way a traveler faces when they leave a portal. */
public enum OrientationPolicy {
    /** Rotate the entry look through the portal frames (existing behaviour). */
    FRAME,
    /** Keep the traveler's absolute look. */
    LOOK,
    /** Face straight out of the exit. */
    SNAP,
    /** Frame look with the exit-normal component reflected, so the traveler faces the portal they came out of. */
    MIRROR;

    public static OrientationPolicy parse(String name, OrientationPolicy fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        for (OrientationPolicy policy : values()) {
            if (policy.name().equalsIgnoreCase(name.trim())) {
                return policy;
            }
        }
        return fallback;
    }

    public OrientationPolicy next() {
        OrientationPolicy[] policies = values();
        return policies[(ordinal() + 1) % policies.length];
    }

    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
