package art.arcane.wormholes.forge;

import art.arcane.wormholes.modded.seamless.SeamlessMove;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.ForgeEventFactory;

final class ForgeSeamlessEvents implements SeamlessMove.Events {
    @Override
    public boolean allowLevelChange(ServerPlayer player, ServerLevel destination) {
        return !ForgeEventFactory.onTravelToDimension(player, destination.dimension());
    }

    @Override
    public void levelChanged(ServerPlayer player, ServerLevel origin, ServerLevel destination) {
        ForgeEventFactory.onPlayerChangedDimension(player, origin.dimension(), destination.dimension());
    }

    @Override
    public void chunkWatched(ServerPlayer player, ServerLevel level, LevelChunk chunk) {
        ForgeEventFactory.fireChunkWatch(player, chunk, level);
    }

    @Override
    public void chunkUnwatched(SeamlessMove.ChunkLeave leave) {
        if (leave.sent()) {
            ForgeEventFactory.fireChunkUnWatch(leave.player(), leave.pos(), leave.level());
        }
    }

    @Override
    public void entityTracked(ServerPlayer player, Entity entity) {
        ForgeEventFactory.onStartEntityTracking(entity, player);
    }

    @Override
    public void entityUntracked(ServerPlayer player, Entity entity) {
        ForgeEventFactory.onStopEntityTracking(entity, player);
    }
}
