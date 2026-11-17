package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.network.MinecraftStatusBridge;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientIntentionPacket.class)
public abstract class ClientIntentionForgeMixin {
    @Shadow public abstract String getHostName();
    @Shadow public abstract ConnectionProtocol getIntention();

    // Forge reads the host name in the constructor body instead of before super(), so this handler is an instance method.
    @ModifyArg(method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/network/FriendlyByteBuf;readUtf(I)Ljava/lang/String;"), index = 0)
    private int wormholesHostLimit(int limit) {
        return MinecraftStatusBridge.MAX_HOST_LENGTH;
    }

    @Inject(method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V", at = @At("RETURN"))
    private void wormholesValidateHost(FriendlyByteBuf input, CallbackInfo callback) {
        if (getHostName().length() > 255
            && (getIntention() != ConnectionProtocol.STATUS || !MinecraftStatusBridge.isStatusAddress(getHostName()))) {
            throw new DecoderException("Handshake hostname exceeds 255 characters");
        }
    }
}
