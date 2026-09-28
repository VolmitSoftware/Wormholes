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
        return PortalPermissionKey.sanitize(name);
    }

    public static boolean isValid(String key) {
        return PortalPermissionKey.isValid(key);
    }

    public static String node(String key) {
        return PortalPermissionKey.node(key);
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

}
