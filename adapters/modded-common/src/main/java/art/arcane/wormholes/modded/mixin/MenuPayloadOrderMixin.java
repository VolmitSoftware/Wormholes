package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.menu.MenuPayload;
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
public abstract class MenuPayloadOrderMixin {
    @Inject(method = "handle(Lnet/minecraft/network/protocol/common/ServerCommonPacketListener;)V", at = @At("HEAD"), cancellable = true)
    private void wormholesHandleMenuInPacketOrder(ServerCommonPacketListener listener, CallbackInfo callback) {
        ServerboundCustomPayloadPacket packet = (ServerboundCustomPayloadPacket) (Object) this;
        if (!(listener instanceof ServerGamePacketListenerImpl game) || !packet.payload().type().id().equals(MenuPayload.ID)) {
            return;
        }
        MinecraftServer server = game.player.level().getServer();
        PacketProcessor processor = server.packetProcessor();
        if (!processor.isSameThread()) {
            callback.cancel();
            processor.scheduleIfPossible(listener, packet);
            return;
        }
        if (!(packet.payload() instanceof MenuPayload payload)) {
            return;
        }
        callback.cancel();
        WormholesModRuntime runtime = WormholesModRuntime.forServer(server);
        if (runtime != null) {
            runtime.menuTransport().receive(game.player, payload.data());
        }
    }
}
