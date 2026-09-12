package art.arcane.wormholes.access;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Stable per-portal permission identity. A key is the tail of {@code wormholes.portal.<key>}; it is
 * seeded from the portal name once and then never follows a rename.
 */
public final class PermissionKeys {
    public static final String NODE_PREFIX = "wormholes.portal.";
    public static final int MAX_LENGTH = 64;

    private static final String FALLBACK = "unnamed";

    private PermissionKeys() {
    }

    /**
     * Same rules as the legacy name-derived node: lowercase, keep a-z, 0-9, dot, dash and
     * underscore, collapse every other run into one underscore, trim the edges.
     */
    public static String sanitize(String name) {
        String source = name == null || name.isBlank() ? FALLBACK : name.toLowerCase(Locale.ROOT);
        StringBuilder builder = new StringBuilder(source.length());
        boolean previousSeparator = false;
        for (int index = 0; index < source.length(); index++) {
            char character = source.charAt(index);
            boolean allowed = (character >= 'a' && character <= 'z')
                || (character >= '0' && character <= '9')
                || character == '.' || character == '-' || character == '_';
            if (allowed) {
                builder.append(character);
                previousSeparator = false;
                continue;
            }
            if (!previousSeparator) {
                builder.append('_');
                previousSeparator = true;
            }
        }
        String trimmed = trimSeparators(builder.toString());
        return trimmed.isEmpty() ? FALLBACK : trimmed;
    }

    public static boolean isValid(String key) {
        if (key == null || key.isEmpty() || key.length() > MAX_LENGTH) {
            return false;
        }
        for (int index = 0; index < key.length(); index++) {
            char character = key.charAt(index);
            boolean allowed = (character >= 'a' && character <= 'z')
                || (character >= '0' && character <= '9')
                || character == '.' || character == '-' || character == '_';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    public static String node(String key) {
        return NODE_PREFIX + key;
    }

    /** True when a portal other than {@code portalId} already answers to {@code key}. */
    public static boolean isTaken(String key, UUID portalId, Iterable<? extends ILocalPortal> portals) {
        if (key == null || portals == null) {
            return false;
        }
        for (ILocalPortal portal : portals) {
            if (!(portal instanceof LocalPortal local) || local.getId().equals(portalId)) {
                continue;
            }
            AccessPortalExtension access = local.extension(AccessPortalExtension.class);
            if (access != null && key.equals(access.permissionKey())) {
                return true;
            }
        }
        return false;
    }

    /** Uniqueness against the loaded portals; free while the portal manager does not exist yet. */
    public static boolean isTaken(String key, UUID portalId) {
        PortalManager manager = Wormholes.portalManager;
        if (manager == null) {
            return false;
        }
        List<ILocalPortal> portals = manager.getLocalPortals();
        return isTaken(key, portalId, portals);
    }

    private static String trimSeparators(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '_') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '_') {
            end--;
        }
        return value.substring(start, end);
    }
}
