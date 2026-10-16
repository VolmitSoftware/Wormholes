package art.arcane.wormholes.menu.cost;

import java.util.Objects;

import art.arcane.wormholes.menu.CapturedItem;

public sealed interface CostEdit permits CostEdit.Clear, CostEdit.Item, CostEdit.Quantity, CostEdit.Currency {
    record Clear() implements CostEdit {
    }

    record Item(CapturedItem item) implements CostEdit {
        public Item {
            Objects.requireNonNull(item, "item");
        }
    }

    record Quantity(int quantity) implements CostEdit {
    }

    record Currency(String amount) implements CostEdit {
        public Currency {
            Objects.requireNonNull(amount, "amount");
        }
    }
}
