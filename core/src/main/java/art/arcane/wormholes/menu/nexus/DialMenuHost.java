package art.arcane.wormholes.menu.nexus;

import java.util.Optional;
import java.util.UUID;

import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.nexus.PortalNetwork;

public interface DialMenuHost {
    Optional<NexusPortalView> portal(UUID portalId);

    Optional<PortalNetwork> network(UUID networkId);

    boolean dialAdmission(MenuViewer viewer, UUID portalId);

    String worldName(UUID portalId);

    DialOutcome dial(MenuViewer viewer, UUID portalId, String address);
}
