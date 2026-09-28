package art.arcane.wormholes.render.acoustics;

import java.util.Locale;

public enum AcousticsProfile {
    OFF,
    AMBIENT,
    AMBIENT_EVENTS,
    FULL;

    private static final AcousticsProfile[] VALUES = values();

    public String configName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public AcousticsProfile next() {
        return VALUES[(ordinal() + 1) % VALUES.length];
    }

    public boolean admitsAmbient() {
        return this != OFF;
    }

    public boolean admits(SoundClass soundClass) {
        return switch (this) {
            case OFF -> false;
            case AMBIENT -> soundClass == SoundClass.AMBIENT;
            case AMBIENT_EVENTS -> soundClass == SoundClass.AMBIENT || soundClass == SoundClass.WORLD;
            case FULL -> true;
        };
    }

    public static AcousticsProfile parse(String name, AcousticsProfile fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        String key = name.trim().toUpperCase(Locale.ROOT);
        for (AcousticsProfile profile : VALUES) {
            if (profile.name().equals(key)) {
                return profile;
            }
        }
        return fallback;
    }

    public enum SoundClass {
        AMBIENT,
        WORLD,
        ENTITY
    }
}
