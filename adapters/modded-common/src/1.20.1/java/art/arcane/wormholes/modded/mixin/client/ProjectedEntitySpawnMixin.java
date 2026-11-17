package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.ProjectedEntityGuard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundAddPlayerPacket;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ProjectedEntitySpawnMixin {
    @Inject(method = "handleAddEntity(Lnet/minecraft/network/protocol/game/ClientboundAddEntityPacket;)V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/util/thread/BlockableEventLoop;)V",
        shift = At.Shift.AFTER), cancellable = true)
    private void wormholes$refuseLocalPlayerId(ClientboundAddEntityPacket packet, CallbackInfo callback) {
        if (wormholes$refuses(packet.getId(), BuiltInRegistries.ENTITY_TYPE.getKey(packet.getType()).toString())) {
            callback.cancel();
        }
    }

    @Inject(method = "handleAddPlayer(Lnet/minecraft/network/protocol/game/ClientboundAddPlayerPacket;)V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/util/thread/BlockableEventLoop;)V",
        shift = At.Shift.AFTER), cancellable = true)
    private void wormholes$refuseLocalPlayerIdForPlayer(ClientboundAddPlayerPacket packet, CallbackInfo callback) {
        if (wormholes$refuses(packet.getEntityId(), BuiltInRegistries.ENTITY_TYPE.getKey(EntityType.PLAYER).toString())) {
            callback.cancel();
        }
    }

    @Unique
    private static boolean wormholes$refuses(int id, String type) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !ProjectedEntityGuard.refusesSpawn(id, player.getId())) {
            return false;
        }
        ProjectedEntityGuard.refused(id, type);
        return true;
    }
}
