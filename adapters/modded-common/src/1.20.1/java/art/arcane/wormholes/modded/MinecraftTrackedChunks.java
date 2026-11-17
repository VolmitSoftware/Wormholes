package art.arcane.wormholes.modded;

import art.arcane.wormholes.modded.mixin.SeamlessChunkMapAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

final class MinecraftTrackedChunks {
    private MinecraftTrackedChunks() {
    }

    static boolean tracked(ServerLevel level, ServerPlayer player, int chunkX, int chunkZ) {
        if (player.level() != level || level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) {
            return false;
        }
        SectionPos section = player.getLastSectionPos();
        int viewDistance = ((SeamlessChunkMapAccess) level.getChunkSource().chunkMap).wormholesViewDistance();
        return ChunkMap.isChunkInRange(chunkX, chunkZ, section.x(), section.z(), viewDistance);
    }
}
