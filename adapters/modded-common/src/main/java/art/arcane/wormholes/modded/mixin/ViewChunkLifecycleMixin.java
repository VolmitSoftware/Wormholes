package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.MinecraftNetworkService;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ChunkMap.class)
public abstract class ViewChunkLifecycleMixin {
    @Shadow @Final private ServerLevel level;

    @Inject(method = "onChunkReadyToSend", at = @At("TAIL"))
    private void wormholesViewChunkLoaded(ChunkHolder holder, LevelChunk chunk, CallbackInfo callback) {
        MinecraftNetworkService network = MinecraftNetworkService.forServer(level.getServer());
        if (network != null) {
            network.worldChanged(level, chunk.getPos().x(), chunk.getPos().z());
        }
    }

    @Inject(method = "resendBiomesForChunks", at = @At("TAIL"))
    private void wormholesViewBiomes(List<ChunkAccess> chunks, CallbackInfo callback) {
        MinecraftNetworkService network = MinecraftNetworkService.forServer(level.getServer());
        if (network != null) {
            for (ChunkAccess chunk : chunks) {
                network.worldChanged(level, chunk.getPos().x(), chunk.getPos().z());
                network.viewServer().biomesChanged(level, chunk.getPos().x(), chunk.getPos().z());
            }
        }
    }
}
