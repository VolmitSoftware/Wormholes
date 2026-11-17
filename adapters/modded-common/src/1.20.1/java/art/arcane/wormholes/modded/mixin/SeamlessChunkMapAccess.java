package art.arcane.wormholes.modded.mixin;

import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkMap.class)
public interface SeamlessChunkMapAccess {
    @Invoker("getVisibleChunkIfPresent")
    ChunkHolder wormholesVisibleChunk(long key);

    @Accessor("viewDistance")
    int wormholesViewDistance();

    @Invoker("playerLoadedChunk")
    void wormholesPlayerLoadedChunk(ServerPlayer player, MutableObject<ClientboundLevelChunkWithLightPacket> packet, LevelChunk chunk);
}
