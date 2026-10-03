package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.WormholesClient;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    @Inject(method = "replaceWithPacketData",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;initializeLightSources()V", shift = At.Shift.AFTER))
    private void wormholesReapplyOverlay(int chunkX, int chunkZ, ClientboundLevelChunkPacketData data, CallbackInfo callback) {
        WormholesClient.chunkReplaced((LevelChunk) (Object) this);
    }

    @Inject(method = "replaceWithPacketData", at = @At("RETURN"))
    private void wormholesRebuildProjectedBlockEntities(int chunkX, int chunkZ, ClientboundLevelChunkPacketData data, CallbackInfo callback) {
        LevelChunk chunk = (LevelChunk) (Object) this;
        WormholesClient.chunkBlockEntitiesReplaced(chunk);
        if (chunk.getLevel() instanceof ClientLevel level) {
            WormholesClient.localChunkChanged(level, chunk.getPos().x(), chunk.getPos().z());
        }
    }
}
