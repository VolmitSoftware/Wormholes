package art.arcane.wormholes.nexus;

import java.util.Locale;

/** What a player may do with a network. */
public enum NetworkRole {
    OWNER,
    MANAGER,
    MEMBER;

    public static NetworkRole parse(String value, NetworkRole fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notARole) {
            return fallback;
        }
    }

    public boolean canManage() {
        return this != MEMBER;
    }
}
