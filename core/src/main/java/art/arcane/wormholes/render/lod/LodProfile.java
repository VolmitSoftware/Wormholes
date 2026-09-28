package art.arcane.wormholes.render.lod;

import java.util.Locale;

public enum LodProfile {
    NEAR(0.5D),
    BALANCED(1.0D),
    FAR(1.5D);

    private static final LodProfile[] VALUES = values();

    private final double distanceScale;

    LodProfile(double distanceScale) {
        this.distanceScale = distanceScale;
    }

    public double distanceScale() {
        return distanceScale;
    }

    public String configName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public LodProfile next() {
        return VALUES[(ordinal() + 1) % VALUES.length];
    }

    public static LodProfile parse(String name, LodProfile fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        String key = name.trim().toUpperCase(Locale.ROOT);
        for (LodProfile profile : VALUES) {
            if (profile.name().equals(key)) {
                return profile;
            }
        }
        return fallback;
    }
}
