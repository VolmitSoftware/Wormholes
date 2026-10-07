package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.WormholesModRuntime;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class SeamlessMoveOrderMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handleMovePlayer", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
        shift = At.Shift.AFTER))
    private void wormholesSettleCrossing(ServerboundMovePlayerPacket packet, CallbackInfo callback) {
        WormholesModRuntime runtime = WormholesModRuntime.forServer(player.level().getServer());
        if (runtime != null) {
            runtime.clientViews().settleCrossing(player);
        }
    }
}
