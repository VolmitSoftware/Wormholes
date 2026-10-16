package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.ServerStartedCrossingTap;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class ServerStartedHoldTap {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"), cancellable = true)
    private void wormholesTest$hold(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo callback) {
        if (ServerStartedCrossingTap.hold((Connection) (Object) this, packet, listener, flush)) {
            callback.cancel();
        }
    }
}
