package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.SystemNotices;
import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatListener.class)
public abstract class SystemNoticeTap {
    @Inject(method = "handleOverlay", at = @At("HEAD"))
    private void wormholesTest$notice(Component message, CallbackInfo callback) {
        SystemNotices.record(message.getString());
    }
}
