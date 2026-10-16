package art.arcane.wormholes.menu.destination;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import art.arcane.wormholes.menu.MenuViewer;

public interface DestinationMenuHost {
    List<DestinationMenuView> candidates(UUID portalId);

    List<DestinationMenuView> portals();

    Optional<DestinationMenuView> localDestination(UUID portalId);

    void link(MenuViewer viewer, UUID portalId, DestinationMenuView target);

    void unlink(MenuViewer viewer, UUID portalId);

    PairOutcome pair(MenuViewer viewer, UUID portalId, UUID targetId);
}
