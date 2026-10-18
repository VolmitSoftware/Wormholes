package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientChunkCache.class)
public interface ArrivalChunkCacheAccess {
    @Mutable
    @Accessor("level")
    void wormholes$arrivalLevel(ClientLevel level);

    @Accessor("emptyChunk")
    LevelChunk wormholes$emptyChunk();
}
