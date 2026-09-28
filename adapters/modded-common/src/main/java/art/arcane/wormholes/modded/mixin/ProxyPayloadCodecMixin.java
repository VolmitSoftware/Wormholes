package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.MinecraftProxyPayload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.ArrayList;
import java.util.List;

@Mixin(CustomPacketPayload.class)
public interface ProxyPayloadCodecMixin {
    @ModifyVariable(method = {
        "codec(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload$FallbackProvider;Ljava/util/List;)Lnet/minecraft/network/codec/StreamCodec;",
        "codec(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload$FallbackProvider;Ljava/util/List;Lnet/minecraft/network/ConnectionProtocol;Lnet/minecraft/network/protocol/PacketFlow;)Lnet/minecraft/network/codec/StreamCodec;"
    }, at = @At("HEAD"), argsOnly = true)
    private static <B extends FriendlyByteBuf> List<CustomPacketPayload.TypeAndCodec<? super B, ?>> wormholesProxyPayload(
        List<CustomPacketPayload.TypeAndCodec<? super B, ?>> types) {
        for (CustomPacketPayload.TypeAndCodec<? super B, ?> entry : types) {
            if (entry.type().id().equals(MinecraftProxyPayload.TYPE.id())) {
                return types;
            }
        }
        List<CustomPacketPayload.TypeAndCodec<? super B, ?>> registered = new ArrayList<>(types);
        registered.add(new CustomPacketPayload.TypeAndCodec<>(MinecraftProxyPayload.TYPE, MinecraftProxyPayload.CODEC));
        return registered;
    }
}
