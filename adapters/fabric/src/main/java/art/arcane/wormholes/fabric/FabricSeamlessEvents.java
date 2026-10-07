package art.arcane.wormholes.fabric;

import art.arcane.wormholes.modded.seamless.SeamlessMove;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;

final class FabricSeamlessEvents implements SeamlessMove.Events {
    @Override
    public boolean allowLevelChange(ServerPlayer player, ServerLevel destination) {
        return true;
    }

    @Override
    public void levelChanged(ServerPlayer player, ServerLevel origin, ServerLevel destination) {
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.invoker().afterChangeLevel(player, origin, destination);
    }

    @Override
    public void chunkWatched(ServerPlayer player, ServerLevel level, LevelChunk chunk) {
    }

    @Override
    public void chunkUnwatched(SeamlessMove.ChunkLeave leave) {
    }

    @Override
    public void entityTracked(ServerPlayer player, Entity entity) {
        EntityTrackingEvents.START_TRACKING.invoker().onStartTracking(entity, player);
    }

    @Override
    public void entityUntracked(ServerPlayer player, Entity entity) {
        EntityTrackingEvents.STOP_TRACKING.invoker().onStopTracking(entity, player);
    }
}
