package art.arcane.wormholes.menu.nexus;

import java.util.Optional;
import java.util.UUID;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.config.toml.NexusConfig;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.nexus.PortalNetwork;

public interface NetworkMenuHost {
    Optional<NexusPortalView> portal(UUID portalId);

    boolean canManage(MenuViewer viewer, UUID portalId);

    boolean dialAdmission(MenuViewer viewer, UUID portalId);

    Optional<PortalNetwork> network(UUID networkId);

    Optional<PortalNetwork> networkByName(String name);

    int networksOwnedBy(UUID playerId);

    NexusConfig config();

    boolean saveNetwork(PortalNetwork network);

    void edit(MenuViewer viewer, UUID portalId, NetworkEdit edit);

    void tell(MenuViewer viewer, TextKey key, MessageArgs arguments);
}
