package art.arcane.wormholes.menu.transit;

import java.util.Optional;
import java.util.UUID;

import art.arcane.wormholes.menu.MenuViewer;

public interface TransitMenuHost {
    Optional<TransitMenuView> transit(UUID portalId);

    boolean supportsGravityRotation();

    void apply(MenuViewer viewer, UUID portalId, TransitEdit edit);
}
