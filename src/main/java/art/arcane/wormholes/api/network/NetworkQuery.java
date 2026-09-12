package art.arcane.wormholes.api.network;

import art.arcane.wormholes.api.portal.PortalSnapshot;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only federation lookups. Safe from any thread. */
public interface NetworkQuery {
    /** This server's federation name, empty when networking is off. */
    String localName();

    List<PeerSnapshot> peers();

    Optional<PeerSnapshot> peer(String name);

    /** Remote portals this server has learned about, as portal snapshots with a destination server. */
    List<PortalSnapshot> remotePortals();

    Optional<PortalSnapshot> remotePortal(UUID id);
}
