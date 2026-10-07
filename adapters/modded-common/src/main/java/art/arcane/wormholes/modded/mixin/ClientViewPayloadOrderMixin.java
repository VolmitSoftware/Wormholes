package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.clientview.ClientViewPayload;
import net.minecraft.network.PacketProcessor;
import net.minecraft.network.protocol.common.ServerCommonPacketListener;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerboundCustomPayloadPacket.class)
public abstract class ClientViewPayloadOrderMixin {
    @Inject(method = "handle(Lnet/minecraft/network/protocol/common/ServerCommonPacketListener;)V", at = @At("HEAD"), cancellable = true)
    private void wormholesHandleInPacketOrder(ServerCommonPacketListener listener, CallbackInfo callback) {
        ServerboundCustomPayloadPacket packet = (ServerboundCustomPayloadPacket) (Object) this;
        if (!(packet.payload() instanceof ClientViewPayload payload) || !(listener instanceof ServerGamePacketListenerImpl game)) {
            return;
        }
        callback.cancel();
        MinecraftServer server = game.player.level().getServer();
        PacketProcessor processor = server.packetProcessor();
        if (!processor.isSameThread()) {
            processor.scheduleIfPossible(listener, packet);
            return;
        }
        WormholesModRuntime runtime = WormholesModRuntime.forServer(server);
        if (runtime != null) {
            runtime.clientViews().receivePlay(game.player, payload.data());
        }
    }
}
