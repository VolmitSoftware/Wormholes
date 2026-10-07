package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.seamless.RoutedProtocolHolder;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class RoutedProtocolMixin implements RoutedProtocolHolder {
    @Unique
    private volatile ProtocolInfo<?> wormholesInbound;
    @Unique
    private volatile ProtocolInfo<?> wormholesOutbound;

    @Override
    public ProtocolInfo<?> wormholesInboundProtocol() {
        return wormholesInbound;
    }

    @Override
    public ProtocolInfo<?> wormholesOutboundProtocol() {
        return wormholesOutbound;
    }

    @Inject(method = "setupInboundProtocol", at = @At("HEAD"))
    private <T extends PacketListener> void wormholesCaptureInbound(ProtocolInfo<T> protocol, T listener, CallbackInfo callback) {
        wormholesInbound = protocol;
    }

    @Inject(method = "setupOutboundProtocol", at = @At("HEAD"))
    private void wormholesCaptureOutbound(ProtocolInfo<?> protocol, CallbackInfo callback) {
        wormholesOutbound = protocol;
    }
}
