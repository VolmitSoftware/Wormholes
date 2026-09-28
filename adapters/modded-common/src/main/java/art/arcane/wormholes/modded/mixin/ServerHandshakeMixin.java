package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.MinecraftNetworkService;
import art.arcane.wormholes.modded.MinecraftStatusConnection;
import art.arcane.wormholes.network.MinecraftStatusBridge;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.handshake.ClientIntent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerHandshakePacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerHandshakePacketListenerImpl.class)
public abstract class ServerHandshakeMixin {
    @Shadow @Final private MinecraftServer server;
    @Shadow @Final private Connection connection;

    @Redirect(method = "handleIntention", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;acceptsTransfers()Z"))
    private boolean wormholesAcceptTransfers(MinecraftServer server) {
        return server.acceptsTransfers() || MinecraftNetworkService.autoAcceptTransfers(server);
    }

    @Inject(method = "handleIntention", at = @At("RETURN"))
    private void wormholesStatusHandshake(ClientIntentionPacket packet, CallbackInfo callback) {
        if (packet.intention() != ClientIntent.STATUS) {
            return;
        }
        MinecraftStatusBridge bridge = MinecraftNetworkService.statusBridge(server);
        if (bridge != null) {
            MinecraftStatusConnection.attach(((ConnectionChannelAccess) connection).wormholesChannel(), bridge, packet.hostName());
        }
    }
}
