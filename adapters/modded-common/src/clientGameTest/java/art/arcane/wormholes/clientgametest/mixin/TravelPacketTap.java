package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.TravelTap;
import art.arcane.wormholes.modded.client.WormholesClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class TravelPacketTap {
    @Inject(method = "handleRespawn", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
        shift = At.Shift.AFTER))
    private void wormholesTest$respawn(CallbackInfo callback) {
        TravelTap.respawn();
    }

    @Inject(method = "handleMovePlayer", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
        shift = At.Shift.AFTER))
    private void wormholesTest$position(CallbackInfo callback) {
        TravelTap.position();
    }

    @Inject(method = "handleAddEntity", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
        shift = At.Shift.AFTER))
    private void wormholesTest$added(ClientboundAddEntityPacket packet, CallbackInfo callback) {
        if (WormholesClient.activeLevel(Minecraft.getInstance().level)) {
            TravelTap.added(packet.getUUID());
        }
    }

    @Inject(method = "handleUpdateAttributes", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
        shift = At.Shift.AFTER))
    private void wormholesTest$attributes(ClientboundUpdateAttributesPacket packet, CallbackInfo callback) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || packet.getEntityId() != minecraft.player.getId()) {
            return;
        }
        for (ClientboundUpdateAttributesPacket.AttributeSnapshot snapshot : packet.getValues()) {
            if (snapshot.attribute().value() == Attributes.SCALE.value()) {
                TravelTap.scalePacket(minecraft.player.getAttributeValue(Attributes.SCALE));
                return;
            }
        }
    }
}
