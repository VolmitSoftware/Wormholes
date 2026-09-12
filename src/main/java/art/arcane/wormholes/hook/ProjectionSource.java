package art.arcane.wormholes.hook;

import art.arcane.wormholes.portal.ILocalPortal;

import java.util.Collection;

/**
 * Supplies additional projectable apertures (for example Dimensional Door facades) that are not
 * registered in the portal manager. Returned portals are treated like RTP-provided portals: they are
 * projected whenever {@code isProjecting()} and {@code isOpen()} hold, without needing a tunnel.
 */
public interface ProjectionSource {
    Collection<ILocalPortal> activeProjectionPortals();
}
