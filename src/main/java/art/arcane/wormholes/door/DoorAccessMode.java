package art.arcane.wormholes.door;

import java.util.Locale;

public enum DoorAccessMode {
    UNRESTRICTED,
    WHITELIST,
    BLACKLIST;

    public static DoorAccessMode fromName(String name) {
        if (name == null) {
            return UNRESTRICTED;
        }
        try {
            return DoorAccessMode.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return UNRESTRICTED;
        }
    }

    public DoorAccessMode next() {
        return switch (this) {
            case UNRESTRICTED -> WHITELIST;
            case WHITELIST -> BLACKLIST;
            case BLACKLIST -> UNRESTRICTED;
        };
    }
}
