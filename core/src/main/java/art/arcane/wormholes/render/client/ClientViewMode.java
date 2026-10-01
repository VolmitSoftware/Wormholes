package art.arcane.wormholes.render.client;

import java.util.Locale;

public enum ClientViewMode {
    AUTO("Auto"),
    OFF("Off");

    private static final ClientViewMode[] VALUES = values();

    private final String displayName;

    ClientViewMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public boolean allowsClientView() {
        return this == AUTO;
    }

    public ClientViewMode next() {
        return VALUES[(ordinal() + 1) % VALUES.length];
    }

    public static ClientViewMode fromName(String name, ClientViewMode fallback) {
        if (name == null) {
            return fallback;
        }
        String key = name.trim().toUpperCase(Locale.ROOT);
        for (ClientViewMode mode : VALUES) {
            if (mode.name().equals(key)) {
                return mode;
            }
        }
        return fallback;
    }
}
