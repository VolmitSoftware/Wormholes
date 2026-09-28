package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.MinecraftDoorRecipePlacement;
import art.arcane.wormholes.modded.MinecraftDoorRecipes;
import net.minecraft.recipebook.ServerPlaceRecipe;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(ServerPlaceRecipe.class)
public abstract class DoorRecipePlacementMixin {
    @Inject(method = "placeRecipe(Lnet/minecraft/recipebook/ServerPlaceRecipe$CraftingMenuAccess;IILjava/util/List;Ljava/util/List;"
        + "Lnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/world/item/crafting/RecipeHolder;ZZ)"
        + "Lnet/minecraft/world/inventory/RecipeBookMenu$PostPlaceAction;", at = @At("HEAD"), cancellable = true)
    private static <R extends Recipe<?>> void wormholesPlaceDoorRecipe(ServerPlaceRecipe.CraftingMenuAccess<R> menu, int gridWidth, int gridHeight,
        List<Slot> inputGridSlots, List<Slot> slotsToClear, Inventory inventory, RecipeHolder<R> recipe, boolean useMaxItems,
        boolean allowDroppingItemsToClear, CallbackInfoReturnable<RecipeBookMenu.PostPlaceAction> callback) {
        if (recipe.value() instanceof MinecraftDoorRecipes.DoorRecipe door) {
            callback.setReturnValue(door.place(new MinecraftDoorRecipePlacement.Placement<>(menu, recipe, gridWidth, gridHeight,
                inputGridSlots, slotsToClear, inventory, useMaxItems, allowDroppingItemsToClear)));
        }
    }
}
