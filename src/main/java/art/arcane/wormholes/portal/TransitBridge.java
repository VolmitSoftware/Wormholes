package art.arcane.wormholes.portal;

import org.bukkit.entity.Entity;

/** Package-private traversal operations the transit lane needs from outside the portal package. */
public final class TransitBridge {
    private TransitBridge() {
    }

    /**
     * Whether a rig member may leave {@code source} through {@code tunnel}: direction and permission checks,
     * the destination's arrival check, then the departure and arrival gates with a member attempt (no
     * traversive), exactly as the member's own crossing would be judged.
     */
    public static boolean memberMayTravel(LocalPortal source, Entity member, ITunnel tunnel, long nowMillis) {
        return source.traversal().memberMayTravel(member, tunnel, nowMillis);
    }

    /** Whether the portal's own sound toggle allows traversal sounds; cues follow the same switch. */
    public static boolean portalSoundEnabled(LocalPortal portal) {
        return portal.effects().isPortalSoundEnabled();
    }
}
