package art.arcane.wormholes.nexus;

import java.util.Locale;

/** How a portal with more than one destination picks between them. */
public enum DestinationMode {
    /** One destination: the portal's ordinary tunnel decides, the policy stays out of the way. */
    SINGLE,
    /** First entry whose schedule window is open right now. */
    ORDERED,
    /** Random draw weighted by entry weight among the open entries. */
    WEIGHTED,
    /** Only the entry whose window is open; closed outside every window. */
    SCHEDULED,
    /** A stable entry per traveler. */
    PER_PLAYER,
    /** Back where the traveler came from; the resolver supplies the recorded source. */
    RETURN;

    public static DestinationMode parse(String value, DestinationMode fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notAMode) {
            return fallback;
        }
    }

    public DestinationMode next() {
        DestinationMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
