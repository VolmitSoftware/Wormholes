package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.MinecraftDoorRecipePlacement;
import art.arcane.wormholes.modded.MinecraftDoorRecipes;
import net.minecraft.recipebook.ServerPlaceRecipe;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.item.crafting.Recipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlaceRecipe.class)
public abstract class DoorRecipePlacementMixin<C extends Container> {
    @Shadow protected RecipeBookMenu<C> menu;

    @Inject(method = "recipeClicked", at = @At("HEAD"), cancellable = true)
    private void wormholesPlaceDoorRecipe(ServerPlayer player, Recipe<C> recipe, boolean useMaxItems, CallbackInfo callback) {
        if (recipe instanceof MinecraftDoorRecipes.DoorRecipe door && player.getRecipeBook().contains(recipe)) {
            callback.cancel();
            door.place(new MinecraftDoorRecipePlacement.Placement<>(menu, recipe, player, useMaxItems));
        }
    }
}
