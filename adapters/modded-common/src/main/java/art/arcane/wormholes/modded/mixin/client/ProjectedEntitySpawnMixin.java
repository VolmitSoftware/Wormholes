package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.ProjectedEntityGuard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ProjectedEntitySpawnMixin {
    @Inject(method = "handleAddEntity", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
        shift = At.Shift.AFTER), cancellable = true)
    private void wormholes$refuseLocalPlayerId(ClientboundAddEntityPacket packet, CallbackInfo callback) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !ProjectedEntityGuard.refusesSpawn(packet.getId(), player.getId())) {
            return;
        }
        ProjectedEntityGuard.refused(packet.getId(), BuiltInRegistries.ENTITY_TYPE.getKey(packet.getType()).toString());
        callback.cancel();
    }
}
