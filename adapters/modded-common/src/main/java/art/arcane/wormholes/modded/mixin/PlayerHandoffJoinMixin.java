package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.MinecraftNetworkService;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.chat.Component;
import java.net.SocketAddress;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public abstract class PlayerHandoffJoinMixin {
    @Shadow public abstract MinecraftServer getServer();

    @Inject(method = "canPlayerLogin", at = @At("RETURN"), cancellable = true)
    private void wormholesReservedCapacity(SocketAddress address, NameAndId profile, CallbackInfoReturnable<Component> callback) {
        MinecraftNetworkService network = MinecraftNetworkService.forServer(getServer());
        if (callback.getReturnValue() == null && network != null && network.handoffs().reservedCapacityFull(profile)) {
            callback.setReturnValue(Component.translatable("multiplayer.disconnect.server_full"));
        }
    }

    @Inject(method = "placeNewPlayer", at = @At("TAIL"))
    private void wormholesArrival(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo callback) {
        MinecraftNetworkService network = MinecraftNetworkService.forServer(player.level().getServer());
        if (network != null) {
            network.handoffs().joined(player);
        }
    }
}
