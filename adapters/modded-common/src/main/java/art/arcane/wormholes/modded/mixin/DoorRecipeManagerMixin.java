package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.MinecraftDoorRecipes;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RecipeManager.class)
public abstract class DoorRecipeManagerMixin {
    @Shadow private RecipeMap recipes;

    @Inject(method = "finalizeRecipeLoading", at = @At("HEAD"))
    private void wormholesDoorRecipes(FeatureFlagSet enabledFlags, CallbackInfo callback) {
        recipes = MinecraftDoorRecipes.inject((RecipeManager) (Object) this, recipes);
    }
}
