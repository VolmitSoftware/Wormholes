package art.arcane.wormholes.portal;

public final class ExactItemPayment {
    public static final int MAX_QUANTITY = 2304;

    private ExactItemPayment() {
    }

    public static int clampQuantity(int quantity) {
        return Math.max(1, Math.min(quantity, MAX_QUANTITY));
    }

    public static <I> boolean canAfford(Inventory<I> inventory, I template, int quantity) {
        int found = 0;
        for (int slot = 0; slot < inventory.storageSize(); slot++) {
            I stack = inventory.get(slot);
            if (!inventory.matches(stack, template)) {
                continue;
            }
            found += inventory.count(stack);
            if (found >= quantity) {
                return true;
            }
        }
        return false;
    }

    public static <I> boolean take(Inventory<I> inventory, I template, int quantity) {
        if (!canAfford(inventory, template, quantity)) {
            return false;
        }
        int remaining = quantity;
        for (int slot = 0; slot < inventory.storageSize() && remaining > 0; slot++) {
            I stack = inventory.get(slot);
            if (!inventory.matches(stack, template)) {
                continue;
            }
            int removed = Math.min(remaining, inventory.count(stack));
            int retained = inventory.count(stack) - removed;
            inventory.set(slot, retained == 0 ? inventory.empty() : inventory.copy(stack, retained));
            remaining -= removed;
        }
        return true;
    }

    public static <I> void restore(Inventory<I> inventory, I template, int quantity) {
        int remaining = quantity;
        while (remaining > 0) {
            int restored = Math.min(remaining, inventory.maximumStackSize(template));
            inventory.give(inventory.copy(template, restored));
            remaining -= restored;
        }
    }

    public interface Inventory<I> {
        int storageSize();
        I get(int slot);
        void set(int slot, I item);
        I empty();
        boolean matches(I stack, I template);
        int count(I item);
        I copy(I item, int count);
        int maximumStackSize(I item);
        void give(I item);
    }
}
