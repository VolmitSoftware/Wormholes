package art.arcane.wormholes.nexus;

import java.util.Locale;

/**
 * Overrides applied before {@link DestinationMode}. {@code ENTRY_SIDE}, {@code SNEAK} and
 * {@code RANDOM} decide inside {@link DestinationPolicy#choose}; {@code LAST_USED} and
 * {@code ROUND_ROBIN} are cursor rules the scheduler advances, so {@code choose} leaves them to the mode.
 */
public enum SelectionRule {
    ENTRY_SIDE,
    SNEAK,
    LAST_USED,
    ROUND_ROBIN,
    RANDOM;

    public static SelectionRule parse(String value, SelectionRule fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notARule) {
            return fallback;
        }
    }

    public SelectionRule next() {
        SelectionRule[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
