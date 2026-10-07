package art.arcane.wormholes.modded.mixin;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkMap.class)
public interface SeamlessChunkMapAccess {
    @Invoker("updatePlayerStatus")
    void wormholesUpdatePlayerStatus(ServerPlayer player, boolean added);

    @Invoker("getVisibleChunkIfPresent")
    ChunkHolder wormholesVisibleChunk(long key);
}
