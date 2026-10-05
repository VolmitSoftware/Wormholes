package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.ClientPreparedTravel;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class PreparedTravelChunkCacheMixin {
    @Inject(method = "handleLevelChunkWithLight", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
        shift = At.Shift.AFTER), cancellable = true)
    private void wormholes$receiveChunk(ClientboundLevelChunkWithLightPacket packet, CallbackInfo callback) {
        WormholesClient client = WormholesClient.instance();
        if (client != null && client.preparedTravel().receiveNativeChunk(((ClientPacketListener) (Object) this).getLevel(), packet)) {
            callback.cancel();
        }
    }

    @WrapOperation(method = "handleLevelChunkWithLight", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/multiplayer/ClientChunkCache;replaceWithPacketData(IILnet/minecraft/network/protocol/game/ClientboundLevelChunkPacketData;)Lnet/minecraft/world/level/chunk/LevelChunk;"))
    private LevelChunk wormholes$replaceColumn(ClientChunkCache cache, int x, int z, ClientboundLevelChunkPacketData data,
                                               Operation<LevelChunk> original) {
        WormholesClient client = WormholesClient.instance();
        ClientLevel level = ((ClientPacketListener) (Object) this).getLevel();
        return client == null ? original.call(cache, x, z, data)
            : client.preparedTravel().replaceNativeColumn(level, x, z, () -> original.call(cache, x, z, data));
    }

    @WrapOperation(method = "handleLevelChunkWithLight", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/multiplayer/ClientLevel;queueLightUpdate(Ljava/lang/Runnable;)V"))
    private void wormholes$queueLight(ClientLevel level, Runnable update, Operation<Void> original,
                                      @Local(argsOnly = true) ClientboundLevelChunkWithLightPacket packet) {
        WormholesClient client = WormholesClient.instance();
        original.call(level, client == null ? update : client.preparedTravel().nativeLightUpdate(level, packet.x(), packet.z(), update));
    }

    @WrapOperation(method = "handleLightUpdatePacket", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/multiplayer/ClientLevel;queueLightUpdate(Ljava/lang/Runnable;)V"))
    private void wormholes$queueSectionLight(ClientLevel level, Runnable update, Operation<Void> original,
                                             @Local(argsOnly = true) ClientboundLightUpdatePacket packet) {
        WormholesClient client = WormholesClient.instance();
        original.call(level, client == null ? update
            : client.preparedTravel().nativeLightUpdate(level, packet.x(), packet.z(), update));
    }

    @WrapOperation(method = "readSectionList", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/multiplayer/ClientLevel;setSectionDirtyWithNeighbors(III)V"))
    private void wormholes$sectionLightGeometry(ClientLevel level, int x, int y, int z, Operation<Void> original) {
        if (!ClientPreparedTravel.applyingNativeLight(level, x, z)) {
            original.call(level, x, y, z);
        }
    }

    @WrapOperation(method = "enableChunkLight", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/multiplayer/ClientLevel;setSectionRangeDirty(IIIIII)V"))
    private void wormholes$lightGeometry(ClientLevel level, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                         Operation<Void> original) {
        if (!ClientPreparedTravel.applyingColumn(level, minX + 1, minZ + 1)) {
            original.call(level, minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

}
