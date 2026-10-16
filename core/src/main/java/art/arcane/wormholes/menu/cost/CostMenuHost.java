package art.arcane.wormholes.menu.cost;

import java.util.UUID;

import art.arcane.wormholes.menu.MenuViewer;

public interface CostMenuHost {
    boolean currencyAvailable();

    boolean edit(MenuViewer viewer, UUID portalId, CostEdit edit);
}
