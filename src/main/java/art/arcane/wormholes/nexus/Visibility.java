package art.arcane.wormholes.nexus;

import java.util.Locale;

/** Who may see a network and its members listed. */
public enum Visibility {
    PUBLIC,
    MEMBERS,
    HIDDEN;

    public static Visibility parse(String value, Visibility fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notAVisibility) {
            return fallback;
        }
    }

    public Visibility next() {
        Visibility[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
