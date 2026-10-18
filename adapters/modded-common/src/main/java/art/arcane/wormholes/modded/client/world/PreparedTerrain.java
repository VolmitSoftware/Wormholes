package art.arcane.wormholes.modded.client.world;

import art.arcane.wormholes.modded.client.PreparedChunkColumns;
import art.arcane.wormholes.modded.mixin.client.ArrivalChunkAccess;
import art.arcane.wormholes.modded.mixin.client.ArrivalChunkCacheAccess;
import art.arcane.wormholes.modded.mixin.client.ArrivalChunkHeightAccess;
import art.arcane.wormholes.modded.mixin.client.ArrivalLevelAccess;
import art.arcane.wormholes.modded.mixin.client.ArrivalLevelTickersAccess;
import art.arcane.wormholes.modded.mixin.client.ArrivalLightEngineAccess;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceArray;

public final class PreparedTerrain {
    private PreparedTerrain() {
    }

    public static void transfer(ClientLevel prepared, ClientLevel target) {
        ClientChunkCache incoming = prepared.getChunkSource();
        if (!((ArrivalLevelAccess) prepared).wormholes$pendingLight().isEmpty() || incoming.getLightEngine().hasLightWork()) {
            throw new IllegalStateException("Arrival terrain still has pending light updates");
        }
        AtomicReferenceArray<LevelChunk> previous = ((PreparedChunkColumns) target.getChunkSource()).wormholes$columns();
        AtomicReferenceArray<LevelChunk> columns = ((PreparedChunkColumns) incoming).wormholes$columns();
        ArrivalChunkCacheAccess cache = (ArrivalChunkCacheAccess) incoming;
        ArrivalLightEngineAccess light = (ArrivalLightEngineAccess) incoming.getLightEngine();
        ArrivalLevelAccess destinationLevel = (ArrivalLevelAccess) target;
        ArrivalLevelTickersAccess source = (ArrivalLevelTickersAccess) prepared;
        ArrivalLevelTickersAccess destination = (ArrivalLevelTickersAccess) target;
        Objects.requireNonNull(source.wormholes$blockEntityTickers());
        Objects.requireNonNull(source.wormholes$pendingBlockEntityTickers());
        Objects.requireNonNull(destination.wormholes$blockEntityTickers());
        Objects.requireNonNull(destination.wormholes$pendingBlockEntityTickers());
        validate(cache.wormholes$emptyChunk());
        for (int index = 0; index < columns.length(); index++) {
            LevelChunk chunk = columns.get(index);
            if (chunk != null) {
                validate(chunk);
            }
        }
        for (int index = 0; index < previous.length(); index++) {
            LevelChunk chunk = previous.get(index);
            if (chunk != null) {
                target.unload(chunk);
            }
        }
        target.getGloballyRenderedBlockEntities().clear();
        cache.wormholes$arrivalLevel(target);
        rebind(cache.wormholes$emptyChunk(), target);
        light.wormholes$arrivalHeight(target);
        destinationLevel.wormholes$arrivalChunks(incoming);
        for (int index = 0; index < columns.length(); index++) {
            LevelChunk chunk = columns.get(index);
            if (chunk != null) {
                rebind(chunk, target);
                target.onChunkLoaded(chunk.getPos());
            }
        }
        destination.wormholes$blockEntityTickers().addAll(source.wormholes$blockEntityTickers());
        destination.wormholes$pendingBlockEntityTickers().addAll(source.wormholes$pendingBlockEntityTickers());
        source.wormholes$blockEntityTickers().clear();
        source.wormholes$pendingBlockEntityTickers().clear();
        target.clearTintCaches();
    }

    private static void validate(LevelChunk chunk) {
        if (!(chunk instanceof ArrivalChunkAccess) || !(chunk instanceof ArrivalChunkHeightAccess)) {
            throw new IllegalStateException("Arrival chunk does not expose its level ownership");
        }
        Objects.requireNonNull(chunk.getBlockEntities());
    }

    private static void rebind(LevelChunk chunk, ClientLevel target) {
        ((ArrivalChunkAccess) chunk).wormholes$arrivalLevel(target);
        ((ArrivalChunkHeightAccess) chunk).wormholes$arrivalHeight(target);
        for (BlockEntity entity : chunk.getBlockEntities().values()) {
            entity.setLevel(target);
            target.onBlockEntityAdded(entity);
        }
    }
}
