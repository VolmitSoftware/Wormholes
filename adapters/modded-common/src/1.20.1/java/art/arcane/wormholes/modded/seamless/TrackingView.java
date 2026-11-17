package art.arcane.wormholes.modded.seamless;

import art.arcane.wormholes.modded.mixin.SeamlessChunkMapAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.util.function.Consumer;

/**
 * The square-ish chunk area a 1.20.1 player tracks: vanilla derives it from the last section position and the
 * level's view distance instead of keeping a view object on the player.
 */
public record TrackingView(int centerX, int centerZ, int radius) {
    public static final TrackingView EMPTY = new TrackingView(0, 0, -1);

    public static TrackingView of(ServerPlayer player) {
        SectionPos section = player.getLastSectionPos();
        return new TrackingView(section.x(), section.z(), viewDistance(player.serverLevel()));
    }

    public static TrackingView around(ServerPlayer player, ServerLevel level) {
        return new TrackingView(SectionPos.blockToSectionCoord(player.getBlockX()), SectionPos.blockToSectionCoord(player.getBlockZ()),
            viewDistance(level));
    }

    public static int viewDistance(ServerLevel level) {
        return ((SeamlessChunkMapAccess) level.getChunkSource().chunkMap).wormholesViewDistance();
    }

    public boolean contains(int chunkX, int chunkZ) {
        return radius >= 0 && ChunkMap.isChunkInRange(chunkX, chunkZ, centerX, centerZ, radius);
    }

    public void forEach(Consumer<ChunkPos> action) {
        if (radius < 0) {
            return;
        }
        for (int x = centerX - radius - 1; x <= centerX + radius + 1; x++) {
            for (int z = centerZ - radius - 1; z <= centerZ + radius + 1; z++) {
                if (contains(x, z)) {
                    action.accept(new ChunkPos(x, z));
                }
            }
        }
    }
}
