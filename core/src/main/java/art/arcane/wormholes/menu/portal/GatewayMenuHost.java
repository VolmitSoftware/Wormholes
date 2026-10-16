package art.arcane.wormholes.menu.portal;

import java.util.UUID;

import art.arcane.wormholes.menu.MenuViewer;

public interface GatewayMenuHost {
    GatewayView gateway(UUID portalId);

    void exportToChat(MenuViewer viewer, UUID portalId);

    ImportOutcome importCode(MenuViewer viewer, UUID portalId, String code);
}
