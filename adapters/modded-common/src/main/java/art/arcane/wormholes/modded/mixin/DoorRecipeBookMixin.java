package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.MinecraftDoorService;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public abstract class DoorRecipeBookMixin {
    @Inject(method = "placeNewPlayer", at = @At("TAIL"))
    private void wormholesDoorRecipeBook(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo callback) {
        MinecraftDoorService doors = MinecraftDoorService.forServer(player.level().getServer());
        if (doors != null) {
            doors.recipes().synchronize(player);
        }
    }
}
