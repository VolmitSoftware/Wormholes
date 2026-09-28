package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.MinecraftRecipeBook;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public abstract class RecipeBookJoinMixin {
    @Inject(method = "placeNewPlayer", at = @At("TAIL"))
    private void wormholesRecipeBook(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo callback) {
        MinecraftRecipeBook book = MinecraftRecipeBook.forServer(player.level().getServer());
        if (book != null) {
            book.synchronize(player);
        }
    }
}
