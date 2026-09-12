package art.arcane.wormholes.render.atmosphere;

import java.util.Locale;

public enum AtmosphereMode {
    OFF,
    TINT,
    TINT_LIGHT,
    FULL;

    private static final AtmosphereMode[] VALUES = values();

    public String configName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public AtmosphereMode next() {
        return VALUES[(ordinal() + 1) % VALUES.length];
    }

    public boolean tintsBiomes() {
        return this != OFF;
    }

    public boolean promotesSkyLight() {
        return this == TINT_LIGHT || this == FULL;
    }

    public boolean usesFogPlate() {
        return this == FULL;
    }

    public boolean relaysWeather() {
        return this == FULL;
    }

    public static AtmosphereMode parse(String name, AtmosphereMode fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        String key = name.trim().toUpperCase(Locale.ROOT);
        for (AtmosphereMode mode : VALUES) {
            if (mode.name().equals(key)) {
                return mode;
            }
        }
        return fallback;
    }
}
