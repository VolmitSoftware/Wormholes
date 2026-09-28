package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.network.MinecraftStatusBridge;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.handshake.ClientIntent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.network.FriendlyByteBuf;

@Mixin(ClientIntentionPacket.class)
public abstract class ClientIntentionMixin {
    @Shadow public abstract String hostName();
    @Shadow public abstract ClientIntent intention();

    @ModifyArg(method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/network/FriendlyByteBuf;readUtf(I)Ljava/lang/String;"), index = 0)
    private static int wormholesHostLimit(int limit) {
        return MinecraftStatusBridge.MAX_HOST_LENGTH;
    }

    @Inject(method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V", at = @At("RETURN"))
    private void wormholesValidateHost(FriendlyByteBuf input, CallbackInfo callback) {
        if (hostName().length() > 255
            && (intention() != ClientIntent.STATUS || !MinecraftStatusBridge.isStatusAddress(hostName()))) {
            throw new DecoderException("Handshake hostname exceeds 255 characters");
        }
    }
}
