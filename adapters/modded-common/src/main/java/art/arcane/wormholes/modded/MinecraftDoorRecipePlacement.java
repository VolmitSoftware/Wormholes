package art.arcane.wormholes.modded;

import art.arcane.wormholes.door.DoorForm;
import net.minecraft.core.NonNullList;
import net.minecraft.recipebook.PlaceRecipeHelper;
import net.minecraft.recipebook.ServerPlaceRecipe;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public final class MinecraftDoorRecipePlacement {
    private MinecraftDoorRecipePlacement() {
    }

    static Predicate<ItemStack> plain(Predicate<ItemStack> accepts) {
        return item -> Inventory.isUsableForCrafting(item) && accepts.test(item);
    }

    static RecipeBookMenu.PostPlaceAction shaped(Placement<?> placement, ShapedRecipe recipe, List<Predicate<ItemStack>> slots) {
        if (recipe.getWidth() > placement.gridWidth() || recipe.getHeight() > placement.gridHeight() || !placement.clearable()) {
            return RecipeBookMenu.PostPlaceAction.NOTHING;
        }
        List<StackedContents.IngredientInfo<ItemStack>> ingredients = new ArrayList<>(slots.size());
        for (Predicate<ItemStack> slot : slots) {
            ingredients.add(slot::test);
        }
        StackedContents<ItemStack> stock = placement.stock();
        if (!stock.tryPick(ingredients, 1, null)) {
            return placement.ghost();
        }
        boolean placed = placement.placedMatches();
        int biggest = stock.tryPickAll(ingredients, Integer.MAX_VALUE, null);
        if (placed) {
            for (Slot slot : placement.inputGridSlots()) {
                ItemStack item = slot.getItem();
                if (!item.isEmpty() && Math.min(biggest, item.getMaxStackSize()) < item.getCount() + 1) {
                    return RecipeBookMenu.PostPlaceAction.NOTHING;
                }
            }
        }
        int amount = placement.amount(biggest, placed);
        List<ItemStack> chosen = new ArrayList<>(slots.size());
        if (!stock.tryPick(ingredients, amount, chosen::add)) {
            return RecipeBookMenu.PostPlaceAction.NOTHING;
        }
        int clamped = amount;
        for (ItemStack item : chosen) {
            clamped = Math.min(clamped, item.getMaxStackSize());
        }
        if (clamped != amount) {
            chosen.clear();
            if (!stock.tryPick(ingredients, clamped, chosen::add)) {
                return RecipeBookMenu.PostPlaceAction.NOTHING;
            }
        }
        placement.fill(recipe, chosen, clamped);
        return RecipeBookMenu.PostPlaceAction.NOTHING;
    }

    static RecipeBookMenu.PostPlaceAction reskin(Placement<?> placement, ShapelessRecipe recipe, DoorForm form) {
        if (!placement.clearable() || placement.placedMatches()) {
            return RecipeBookMenu.PostPlaceAction.NOTHING;
        }
        List<ItemStack> available = placement.available();
        for (ItemStack source : available) {
            if (MinecraftDoorItems.identity(source).filter(identity -> identity.form() == form).isEmpty()) {
                continue;
            }
            for (ItemStack target : available) {
                if (Inventory.isUsableForCrafting(target) && !MinecraftDoorRecipes.skin(form,
                    CraftingInput.of(2, 1, List.of(source.copyWithCount(1), target.copyWithCount(1)))).isEmpty()) {
                    placement.fill(recipe, List.of(source.copyWithCount(1), target.copyWithCount(1)), 1);
                    return RecipeBookMenu.PostPlaceAction.NOTHING;
                }
            }
        }
        return placement.ghost();
    }

    public record Placement<R extends Recipe<?>>(ServerPlaceRecipe.CraftingMenuAccess<R> menu, RecipeHolder<R> recipe, int gridWidth,
        int gridHeight, List<Slot> inputGridSlots, List<Slot> slotsToClear, Inventory inventory, boolean useMaxItems,
        boolean allowDroppingItemsToClear) {
        boolean placedMatches() {
            return menu.recipeMatches(recipe);
        }

        boolean clearable() {
            if (allowDroppingItemsToClear) {
                return true;
            }
            List<ItemStack> queued = new ArrayList<>();
            int free = 0;
            for (ItemStack item : inventory.getNonEquipmentItems()) {
                if (item.isEmpty()) {
                    free++;
                }
            }
            for (Slot slot : inputGridSlots) {
                ItemStack item = slot.getItem().copy();
                if (item.isEmpty()) {
                    continue;
                }
                if (inventory.getSlotWithRemainingSpace(item) != -1) {
                    continue;
                }
                for (ItemStack pending : queued) {
                    if (ItemStack.isSameItemSameComponents(pending, item) && pending.getCount() + item.getCount() <= pending.getMaxStackSize()) {
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
            for (ItemStack item : inventory.getNonEquipmentItems()) {
                if (!item.isEmpty()) {
                    items.add(item);
                }
            }
            for (Slot slot : inputGridSlots) {
                if (!slot.getItem().isEmpty()) {
                    items.add(slot.getItem());
                }
            }
            return items;
        }

        StackedContents<ItemStack> stock() {
            StackedContents<ItemStack> stock = new StackedContents<>();
            List<ItemStack> variants = new ArrayList<>();
            for (ItemStack item : available()) {
                ItemStack variant = null;
                for (ItemStack known : variants) {
                    if (ItemStack.isSameItemSameComponents(known, item)) {
                        variant = known;
                        break;
                    }
                }
                if (variant == null) {
                    variant = item.copyWithCount(1);
                    variants.add(variant);
                }
                stock.account(variant, item.getCount());
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
            for (Slot slot : inputGridSlots) {
                ItemStack item = slot.getItem();
                if (!item.isEmpty()) {
                    smallest = Math.min(smallest, item.getCount());
                }
            }
            return smallest == Integer.MAX_VALUE ? smallest : smallest + 1;
        }

        RecipeBookMenu.PostPlaceAction ghost() {
            clear();
            inventory.setChanged();
            return RecipeBookMenu.PostPlaceAction.PLACE_GHOST_RECIPE;
        }

        void fill(Recipe<?> shape, List<ItemStack> chosen, int amount) {
            clear();
            PlaceRecipeHelper.placeRecipe(gridWidth, gridHeight, shape, shape.placementInfo().slotsToIngredientIndex(),
                (ingredient, gridIndex, column, row) -> {
                    if (ingredient != -1) {
                        move(inputGridSlots.get(gridIndex), chosen.get(ingredient), amount);
                    }
                });
            inventory.setChanged();
        }

        private void clear() {
            for (Slot slot : slotsToClear) {
                ItemStack item = slot.getItem().copy();
                inventory.placeItemBackInInventory(item, false);
                slot.set(item);
            }
            menu.clearCraftingContent();
        }

        private void move(Slot target, ItemStack variant, int amount) {
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
            NonNullList<ItemStack> items = inventory.getNonEquipmentItems();
            for (int index = 0; index < items.size(); index++) {
                ItemStack item = items.get(index);
                if (!item.isEmpty() && ItemStack.isSameItemSameComponents(item, variant)
                    && (existing.isEmpty() || ItemStack.isSameItemSameComponents(existing, item))) {
                    return index;
                }
            }
            return -1;
        }
    }
}
