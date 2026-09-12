package art.arcane.wormholes.access;

import java.util.Locale;

/**
 * What one player may do with a frame portal. Roles live in {@link AccessPortalExtension}; a portal
 * with at least one {@link #USER} or {@link #CO_OWNER} entry is whitelist-only.
 */
public enum PortalRole {
    OWNER,
    CO_OWNER,
    USER,
    DENIED;

    private static final PortalRole[] CYCLE = values();

    /** Menu cycling: left click walks forward through the four roles. */
    public PortalRole next() {
        return CYCLE[(ordinal() + 1) % CYCLE.length];
    }

    /** Menu cycling: right click walks back through the four roles. */
    public PortalRole previous() {
        return CYCLE[(ordinal() + CYCLE.length - 1) % CYCLE.length];
    }

    /** Owners and co-owners may edit the portal's settings. */
    public boolean manages() {
        return this == OWNER || this == CO_OWNER;
    }

    /** Anything but DENIED counts as listed for the whitelist-only check. */
    public boolean trusted() {
        return this != DENIED;
    }

    /** Null when the stored value is not a role, so the caller drops the entry. */
    public static PortalRole fromName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notARole) {
            return null;
        }
    }
}
