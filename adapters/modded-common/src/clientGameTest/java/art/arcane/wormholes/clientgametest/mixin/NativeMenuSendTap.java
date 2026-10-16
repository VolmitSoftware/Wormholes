package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.menu.NativeMenuSink;
import art.arcane.wormholes.menu.protocol.MenuClientMessage;
import art.arcane.wormholes.modded.client.menu.ClientMenuSession;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientMenuSession.class)
public abstract class NativeMenuSendTap {
    @Inject(method = "send", at = @At("RETURN"))
    private void nativeMenu$send(MenuClientMessage message, CallbackInfoReturnable<Boolean> callback) {
        if (callback.getReturnValue()) {
            NativeMenuSink.record(message);
        }
    }
}
