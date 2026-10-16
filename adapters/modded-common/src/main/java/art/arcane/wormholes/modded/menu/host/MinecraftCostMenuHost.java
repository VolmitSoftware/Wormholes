package art.arcane.wormholes.modded.menu.host;

import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.cost.CostEdit;
import art.arcane.wormholes.menu.cost.CostMenuHost;
import art.arcane.wormholes.modded.MinecraftItemEncoding;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.menu.MinecraftCapturedItem;
import art.arcane.wormholes.modded.menu.MinecraftMenuViewer;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

public final class MinecraftCostMenuHost implements CostMenuHost {
    private final WormholesModRuntime runtime;

    public MinecraftCostMenuHost(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public boolean currencyAvailable() {
        return runtime.costs().currencyAvailable();
    }

    @Override
    public boolean edit(MenuViewer viewer, UUID portalId, CostEdit edit) {
        Consumer<MinecraftPortal> mutation = switch (edit) {
            case CostEdit.Clear _ -> MinecraftPortal::clearTravelCost;
            case CostEdit.Item item -> item.item() instanceof MinecraftCapturedItem captured ? template(captured) : null;
            case CostEdit.Quantity quantity -> portal -> portal.setTravelCostQuantity(quantity.quantity());
            case CostEdit.Currency currency -> portal -> portal.setCurrencyTravelCost(currency.amount());
        };
        if (mutation == null) {
            return false;
        }
        try {
            if (!runtime.portals().update(MinecraftMenuViewer.player(viewer), portalId, mutation)) {
                return false;
            }
        } catch (IllegalArgumentException rejected) {
            return false;
        }
        runtime.menus().invalidate(new MenuScope.Portal(portalId));
        return true;
    }

    private Consumer<MinecraftPortal> template(MinecraftCapturedItem captured) {
        String encoded = MinecraftItemEncoding.encode(captured.stack(), runtime.server().registryAccess());
        return portal -> portal.setItemTravelCost(encoded);
    }
}
