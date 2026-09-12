package art.arcane.wormholes.nexus;

import art.arcane.wormholes.portal.LocalPortal;

/** Lane-internal callback for portal lifecycle events that need network bookkeeping. */
public interface NexusPortalListener {
    void onPortalDestroyed(LocalPortal portal, NexusPortalExtension extension);
}
