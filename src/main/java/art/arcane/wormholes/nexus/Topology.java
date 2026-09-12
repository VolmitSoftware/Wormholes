package art.arcane.wormholes.nexus;

import java.util.Locale;

/** How a network's members may dial each other. */
public enum Topology {
    MESH,
    HUB,
    CHAIN,
    RING;

    public static Topology parse(String value, Topology fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notATopology) {
            return fallback;
        }
    }

    public Topology next() {
        Topology[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
