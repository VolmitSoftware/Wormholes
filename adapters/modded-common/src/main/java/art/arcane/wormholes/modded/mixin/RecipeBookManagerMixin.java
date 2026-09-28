package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.MinecraftRecipeBook;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RecipeManager.class)
public abstract class RecipeBookManagerMixin {
    @Shadow private RecipeMap recipes;

    @Inject(method = "finalizeRecipeLoading", at = @At("HEAD"))
    private void wormholesRecipes(FeatureFlagSet enabledFlags, CallbackInfo callback) {
        recipes = MinecraftRecipeBook.inject((RecipeManager) (Object) this, recipes);
    }
}
