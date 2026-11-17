package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.MinecraftRecipeBook;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public abstract class RecipeBookJoinMixin {
    @Inject(method = "placeNewPlayer", at = @At("TAIL"))
    private void wormholesRecipeBook(Connection connection, ServerPlayer player, CallbackInfo callback) {
        MinecraftRecipeBook book = MinecraftRecipeBook.forServer(player.server);
        if (book != null) {
            book.synchronize(player);
        }
    }

    @Inject(method = "reloadResources", at = @At("HEAD"))
    private void wormholesRecipeReload(CallbackInfo callback) {
        MinecraftRecipeBook book = MinecraftRecipeBook.forServer(((PlayerList) (Object) this).getServer());
        if (book != null) {
            book.reinstall();
        }
    }
}
