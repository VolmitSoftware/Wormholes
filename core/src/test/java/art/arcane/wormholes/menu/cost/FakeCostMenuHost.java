package art.arcane.wormholes.menu.cost;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import art.arcane.wormholes.menu.FakePortals;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.portal.PortalMenuView;
import art.arcane.wormholes.portal.ExactItemPayment;
import art.arcane.wormholes.portal.TravelCurrencyAmount;

final class FakeCostMenuHost implements CostMenuHost {
    final List<String> edits = new ArrayList<>();
    boolean currency = true;

    private final FakePortals portals;

    FakeCostMenuHost(FakePortals portals) {
        this.portals = portals;
    }

    @Override
    public boolean currencyAvailable() {
        return currency;
    }

    @Override
    public boolean edit(MenuViewer viewer, UUID portalId, CostEdit edit) {
        edits.add(String.valueOf(edit));
        PortalMenuView view = portals.views.get(portalId);
        PortalMenuView.Cost cost = view.cost();
        PortalMenuView.Cost next = switch (edit) {
            case CostEdit.Clear _ -> PortalMenuView.Cost.FREE;
            case CostEdit.Item item -> item.item().itemId().equals("minecraft:air") ? null : new PortalMenuView.Cost(PortalMenuView.CostMode.VANILLA,
                item.item().itemId(), item.item().label(), cost.mode() == PortalMenuView.CostMode.VANILLA ? cost.quantity() : 1, null, "cost-template");
            case CostEdit.Quantity quantity -> cost.mode() != PortalMenuView.CostMode.VANILLA ? cost : new PortalMenuView.Cost(PortalMenuView.CostMode.VANILLA,
                cost.itemId(), cost.itemLabel(), ExactItemPayment.clampQuantity(quantity.quantity()), null, cost.templateRef());
            case CostEdit.Currency amount -> currencyCost(amount.amount());
        };
        if (next == null) {
            return false;
        }
        portals.put(view.toBuilder().cost(next).build());
        return true;
    }

    private static PortalMenuView.Cost currencyCost(String amount) {
        try {
            BigDecimal parsed = TravelCurrencyAmount.parse(amount);
            return new PortalMenuView.Cost(PortalMenuView.CostMode.VAULT, null, null, 0, parsed.toPlainString(), null);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
