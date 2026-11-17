package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.MinecraftDoorService;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class PocketClockMixin {
    @Shadow @Final private MinecraftServer server;

    @ModifyVariable(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
        at = @At("HEAD"), argsOnly = true)
    private Packet<?> wormholesPocketTime(Packet<?> packet) {
        if (packet instanceof ClientboundSetTimePacket time && (Object) this instanceof ServerGamePacketListenerImpl listener) {
            MinecraftDoorService doors = MinecraftDoorService.forServer(server);
            if (doors != null) {
                return doors.rules().clockPacket(listener.player.getUUID(), time);
            }
        }
        return packet;
    }
}
