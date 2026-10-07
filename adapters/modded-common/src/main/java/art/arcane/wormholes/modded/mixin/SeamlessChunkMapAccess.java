package art.arcane.wormholes.modded.mixin;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntFunction;

@Mixin(ChunkMap.class)
public interface SeamlessChunkMapAccess {
    @Invoker("getVisibleChunkIfPresent")
    ChunkHolder wormholesVisibleChunk(long key);

    @Invoker("getChunkRangeFuture")
    CompletableFuture<ChunkResult<List<ChunkAccess>>> wormholesChunkRangeFuture(ChunkHolder holder, int range,
                                                                              IntFunction<ChunkStatus> distanceToStatus);
}
