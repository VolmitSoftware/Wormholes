package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.MinecraftDoorRecipes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.CrafterBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(CrafterBlock.class)
public abstract class DoorCrafterMixin {
    @Inject(method = "getPotentialResults", at = @At("RETURN"), cancellable = true)
    private static void wormholesPreventAutomatedDoorCraft(ServerLevel level, CraftingInput input,
        CallbackInfoReturnable<Optional<RecipeHolder<CraftingRecipe>>> callback) {
        if (callback.getReturnValue().filter(recipe -> recipe.value() instanceof MinecraftDoorRecipes.DoorRecipe).isPresent()) {
            callback.setReturnValue(Optional.empty());
        }
    }
}
