package art.arcane.wormholes.access;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;

import java.util.UUID;

/**
 * Stable per-portal permission identity. A key is the tail of {@code wormholes.portal.<key>}; it is
 * seeded from the portal name once and then never follows a rename.
 */
public final class PermissionKeys {
    private PermissionKeys() {
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

}
