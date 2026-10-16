package art.arcane.wormholes.menu.fidelity;

import java.util.Optional;
import java.util.UUID;

import art.arcane.wormholes.menu.MenuViewer;

public interface FidelityMenuHost {
    Optional<FidelityMenuView> fidelity(UUID portalId);

    int bedrockViewers();

    void apply(MenuViewer viewer, UUID portalId, FidelityEdit edit);
}
