package art.arcane.wormholes.modded;

import art.arcane.wormholes.door.DoorForm;
import net.minecraft.network.protocol.game.ClientboundPlaceGhostRecipePacket;
import net.minecraft.recipebook.PlaceRecipe;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;

public final class MinecraftDoorRecipePlacement {
    private MinecraftDoorRecipePlacement() {
    }

    static Predicate<ItemStack> plain(Predicate<ItemStack> accepts) {
        return item -> usableForCrafting(item) && accepts.test(item);
    }

    static void shaped(Placement<?> placement, ShapedRecipe recipe, List<Predicate<ItemStack>> cells) {
        if (!placement.known() || recipe.getWidth() > placement.gridWidth() || recipe.getHeight() > placement.gridHeight()
            || !placement.clearable()) {
            return;
        }
        List<Predicate<ItemStack>> ingredients = new ArrayList<>(cells.size());
        for (Predicate<ItemStack> cell : cells) {
            if (cell != null) {
                ingredients.add(cell);
            }
        }
        Stock stock = placement.stock();
        if (!stock.pick(ingredients, 1, null)) {
            placement.ghost();
            return;
        }
        boolean placed = placement.placedMatches();
        int biggest = stock.pickAll(ingredients);
        if (placed) {
            for (Slot slot : placement.inputGridSlots()) {
                ItemStack item = slot.getItem();
                if (!item.isEmpty() && Math.min(biggest, item.getMaxStackSize()) < item.getCount() + 1) {
                    return;
                }
            }
        }
        int amount = placement.amount(biggest, placed);
        List<ItemStack> chosen = new ArrayList<>(ingredients.size());
        if (!stock.pick(ingredients, amount, chosen)) {
            return;
        }
        int clamped = amount;
        for (ItemStack item : chosen) {
            clamped = Math.min(clamped, item.getMaxStackSize());
        }
        if (clamped != amount) {
            chosen.clear();
            if (!stock.pick(ingredients, clamped, chosen)) {
                return;
            }
        }
        List<ItemStack> layout = new ArrayList<>(cells.size());
        Iterator<ItemStack> picked = chosen.iterator();
        for (Predicate<ItemStack> cell : cells) {
            layout.add(cell == null ? ItemStack.EMPTY : picked.next());
        }
        placement.fill(recipe, layout, clamped);
    }

    static void reskin(Placement<?> placement, ShapelessRecipe recipe, DoorForm form) {
        if (!placement.known() || !placement.clearable() || placement.placedMatches()) {
            return;
        }
        List<ItemStack> available = placement.available();
        for (ItemStack source : available) {
            if (MinecraftDoorItems.identity(source).filter(identity -> identity.form() == form).isEmpty()) {
                continue;
            }
            for (ItemStack target : available) {
                if (usableForCrafting(target) && !MinecraftDoorRecipes.skin(form,
                    List.of(source.copyWithCount(1), target.copyWithCount(1))).isEmpty()) {
                    placement.fill(recipe, List.of(source.copyWithCount(1), target.copyWithCount(1)), 1);
                    return;
                }
            }
        }
        placement.ghost();
    }

    private static boolean usableForCrafting(ItemStack item) {
        return !item.isDamaged() && !item.isEnchanted() && !item.hasCustomHoverName();
    }

    public record Placement<C extends Container>(RecipeBookMenu<C> menu, Recipe<C> recipe, ServerPlayer player, boolean useMaxItems) {
        int gridWidth() {
            return menu.getGridWidth();
        }

        int gridHeight() {
            return menu.getGridHeight();
        }

        Inventory inventory() {
            return player.getInventory();
        }

        List<Slot> inputGridSlots() {
            List<Slot> slots = new ArrayList<>(gridWidth() * gridHeight());
            for (int index = 0; index < gridWidth() * gridHeight() + 1; index++) {
                if (index != menu.getResultSlotIndex()) {
                    slots.add(menu.getSlot(index));
                }
            }
            return slots;
        }

        List<Slot> slotsToClear() {
            List<Slot> slots = new ArrayList<>(menu.getSize());
            for (int index = 0; index < menu.getSize(); index++) {
                if (menu.shouldMoveToInventory(index)) {
                    slots.add(menu.getSlot(index));
                }
            }
            return slots;
        }

        boolean known() {
            return player.getRecipeBook().contains(recipe);
        }

        boolean placedMatches() {
            return menu.recipeMatches(recipe);
        }

        boolean clearable() {
            if (player.isCreative()) {
                return true;
            }
            Inventory inventory = inventory();
            List<ItemStack> queued = new ArrayList<>();
            int free = 0;
            for (ItemStack item : inventory.items) {
                if (item.isEmpty()) {
                    free++;
                }
            }
            for (Slot slot : inputGridSlots()) {
                ItemStack item = slot.getItem().copy();
                if (item.isEmpty()) {
                    continue;
                }
                if (inventory.getSlotWithRemainingSpace(item) != -1) {
                    continue;
                }
                for (ItemStack pending : queued) {
                    if (ItemStack.isSameItemSameTags(pending, item) && pending.getCount() + item.getCount() <= pending.getMaxStackSize()) {
                        pending.grow(item.getCount());
                        item.setCount(0);
                        break;
                    }
                }
                if (!item.isEmpty()) {
                    if (queued.size() >= free) {
                        return false;
                    }
                    queued.add(item);
                }
            }
            return true;
        }

        List<ItemStack> available() {
            List<ItemStack> items = new ArrayList<>();
            for (ItemStack item : inventory().items) {
                if (!item.isEmpty()) {
                    items.add(item);
                }
            }
            for (Slot slot : inputGridSlots()) {
                if (!slot.getItem().isEmpty()) {
                    items.add(slot.getItem());
                }
            }
            return items;
        }

        Stock stock() {
            Stock stock = new Stock();
            for (ItemStack item : available()) {
                stock.account(item);
            }
            return stock;
        }

        int amount(int biggest, boolean placed) {
            if (useMaxItems) {
                return biggest;
            }
            if (!placed) {
                return 1;
            }
            int smallest = Integer.MAX_VALUE;
            for (Slot slot : inputGridSlots()) {
                ItemStack item = slot.getItem();
                if (!item.isEmpty()) {
                    smallest = Math.min(smallest, item.getCount());
                }
            }
            return smallest == Integer.MAX_VALUE ? smallest : smallest + 1;
        }

        void ghost() {
            clear();
            inventory().setChanged();
            player.connection.send(new ClientboundPlaceGhostRecipePacket(player.containerMenu.containerId, recipe));
        }

        // layout follows the recipe's own cell order: width * height cells for shaped recipes, ingredient order otherwise.
        void fill(Recipe<?> shape, List<ItemStack> layout, int amount) {
            clear();
            PlaceRecipe<ItemStack> placer = (iterator, slot, count, row, column) -> {
                ItemStack variant = iterator.next();
                if (!variant.isEmpty()) {
                    move(menu.getSlot(slot), variant, count);
                }
            };
            placer.placeRecipe(gridWidth(), gridHeight(), menu.getResultSlotIndex(), shape, layout.iterator(), amount);
            inventory().setChanged();
        }

        private void clear() {
            for (Slot slot : slotsToClear()) {
                ItemStack item = slot.getItem().copy();
                MinecraftItemDelivery.returnToInventory(inventory(), item);
                slot.set(item);
            }
            menu.clearCraftingContent();
        }

        private void move(Slot target, ItemStack variant, int amount) {
            Inventory inventory = inventory();
            int remaining = amount;
            while (remaining > 0) {
                ItemStack existing = target.getItem();
                int source = find(variant, existing);
                if (source == -1) {
                    return;
                }
                ItemStack stored = inventory.getItem(source);
                ItemStack taken = remaining < stored.getCount() ? inventory.removeItem(source, remaining) : inventory.removeItemNoUpdate(source);
                if (existing.isEmpty()) {
                    target.set(taken);
                } else {
                    existing.grow(taken.getCount());
                }
                remaining -= taken.getCount();
            }
        }

        private int find(ItemStack variant, ItemStack existing) {
            List<ItemStack> items = inventory().items;
            for (int index = 0; index < items.size(); index++) {
                ItemStack item = items.get(index);
                if (!item.isEmpty() && ItemStack.isSameItemSameTags(item, variant)
                    && (existing.isEmpty() || ItemStack.isSameItemSameTags(existing, item))) {
                    return index;
                }
            }
            return -1;
        }
    }

    // Item variants (item plus tag) with their total counts; picks one variant per ingredient by bipartite matching.
    static final class Stock {
        private final List<ItemStack> variants = new ArrayList<>();
        private final List<Integer> counts = new ArrayList<>();

        void account(ItemStack item) {
            for (int index = 0; index < variants.size(); index++) {
                if (ItemStack.isSameItemSameTags(variants.get(index), item)) {
                    counts.set(index, counts.get(index) + item.getCount());
                    return;
                }
            }
            variants.add(item.copyWithCount(1));
            counts.add(item.getCount());
        }

        boolean pick(List<Predicate<ItemStack>> ingredients, int amount, List<ItemStack> output) {
            if (amount <= 0) {
                return false;
            }
            int[] assigned = new int[ingredients.size()];
            Arrays.fill(assigned, -1);
            int[] used = new int[variants.size()];
            for (int ingredient = 0; ingredient < ingredients.size(); ingredient++) {
                if (!augment(ingredients, amount, ingredient, assigned, used, new boolean[variants.size()])) {
                    return false;
                }
            }
            if (output != null) {
                for (int variant : assigned) {
                    output.add(variants.get(variant));
                }
            }
            return true;
        }

        int pickAll(List<Predicate<ItemStack>> ingredients) {
            int high = 0;
            for (int count : counts) {
                high = Math.max(high, count);
            }
            int low = 0;
            while (low < high) {
                int middle = low + (high - low + 1) / 2;
                if (pick(ingredients, middle, null)) {
                    low = middle;
                } else {
                    high = middle - 1;
                }
            }
            return low;
        }

        private boolean augment(List<Predicate<ItemStack>> ingredients, int amount, int ingredient, int[] assigned, int[] used, boolean[] visited) {
            for (int variant = 0; variant < variants.size(); variant++) {
                if (visited[variant] || !ingredients.get(ingredient).test(variants.get(variant))) {
                    continue;
                }
                visited[variant] = true;
                if (used[variant] < counts.get(variant) / amount) {
                    assigned[ingredient] = variant;
                    used[variant]++;
                    return true;
                }
                for (int other = 0; other < assigned.length; other++) {
                    if (assigned[other] == variant && augment(ingredients, amount, other, assigned, used, visited)) {
                        used[variant]--;
                        assigned[ingredient] = variant;
                        used[variant]++;
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
