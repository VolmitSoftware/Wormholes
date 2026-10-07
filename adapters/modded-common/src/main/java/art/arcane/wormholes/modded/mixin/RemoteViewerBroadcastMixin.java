package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.WormholesModRuntime;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public abstract class RemoteViewerBroadcastMixin {
    @Shadow @Final private MinecraftServer server;

    @Inject(method = "broadcastAll(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/resources/ResourceKey;)V", at = @At("HEAD"))
    @SuppressWarnings("unchecked")
    private void wormholesRemoteDimension(Packet<?> packet, ResourceKey<Level> dimension, CallbackInfo callback) {
        WormholesModRuntime runtime = WormholesModRuntime.forServer(server);
        if (runtime != null && server.isSameThread()) {
            runtime.remoteRoutes().broadcast(dimension, (Packet<? super ClientGamePacketListener>) packet);
        }
    }
}
