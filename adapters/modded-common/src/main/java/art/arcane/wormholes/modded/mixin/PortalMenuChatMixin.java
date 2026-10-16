package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.MinecraftChatInput;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class PortalMenuChatMixin {
    @Shadow public ServerPlayer player;
    @Shadow protected abstract void detectChatRateSpam();

    @Inject(method = "broadcastChatMessage", at = @At("HEAD"), cancellable = true)
    private void wormholesMenuInput(PlayerChatMessage message, CallbackInfo callback) {
        if (MinecraftChatInput.chat(player, message.signedContent())) {
            detectChatRateSpam();
            callback.cancel();
        }
    }
}
