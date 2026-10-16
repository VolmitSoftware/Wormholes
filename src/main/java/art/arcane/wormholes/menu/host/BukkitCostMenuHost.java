package art.arcane.wormholes.menu.host;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import art.arcane.volmlib.integration.VaultEconomy;
import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.menu.BukkitCapturedItem;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.cost.CostEdit;
import art.arcane.wormholes.menu.cost.CostMenuHost;
import art.arcane.wormholes.portal.LocalPortal;

public final class BukkitCostMenuHost implements CostMenuHost {
    private final PortalManager portals;

    public BukkitCostMenuHost(PortalManager portals) {
        this.portals = Objects.requireNonNull(portals, "portals");
    }

    @Override
    public boolean currencyAvailable() {
        VaultEconomy economy = Wormholes.vaultEconomy;
        return economy != null && economy.isAvailable();
    }

    @Override
    public boolean edit(MenuViewer viewer, UUID portalId, CostEdit edit) {
        Optional<LocalPortal> found = BukkitLocalPortals.local(portals, portalId);
        if (found.isEmpty()) {
            return false;
        }
        LocalPortal portal = found.get();
        try {
            return switch (edit) {
                case CostEdit.Clear _ -> applied(portal::clearTravelCost);
                case CostEdit.Item item -> item.item() instanceof BukkitCapturedItem captured
                    && applied(() -> portal.setVanillaTravelCostItem(captured.stack()));
                case CostEdit.Quantity quantity -> applied(() -> portal.setVanillaTravelCostQuantity(quantity.quantity()));
                case CostEdit.Currency currency -> applied(() -> portal.setVaultTravelCost(currency.amount()));
            };
        } catch (IllegalArgumentException rejected) {
            return false;
        }
    }

    private static boolean applied(Runnable mutation) {
        mutation.run();
        return true;
    }
}
