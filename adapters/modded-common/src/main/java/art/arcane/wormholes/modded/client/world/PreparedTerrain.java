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
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceArray;

public final class PreparedTerrain {
    private PreparedTerrain() {
    }

    public static void transfer(ClientLevel prepared, ClientLevel target) {
        if (prepared.dimension() != target.dimension()) {
            throw new IllegalArgumentException("Arrival terrain belongs to a different dimension");
        }
        ClientChunkCache incoming = prepared.getChunkSource();
        if (!((ArrivalLevelAccess) prepared).wormholes$pendingLight().isEmpty() || incoming.getLightEngine().hasLightWork()) {
            throw new IllegalStateException("Arrival terrain still has pending light updates");
        }
        ClientChunkCache outgoing = target.getChunkSource();
        AtomicReferenceArray<LevelChunk> previous = ((PreparedChunkColumns) outgoing).wormholes$columns();
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
                if (((PreparedChunkColumns) incoming).wormholes$retainColumn(chunk)) {
                    retainLight(chunk, outgoing.getLightEngine(), incoming.getLightEngine());
                } else {
                    target.unload(chunk);
                }
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
        incoming.getLightEngine().runLightUpdates();
        target.clearTintCaches();
    }

    private static void retainLight(LevelChunk chunk, LevelLightEngine source, LevelLightEngine destination) {
        for (int sectionY = source.getMinLightSection(); sectionY < source.getMaxLightSection(); sectionY++) {
            SectionPos section = SectionPos.of(chunk.getPos(), sectionY);
            retainLightLayer(source, destination, LightLayer.BLOCK, section);
            retainLightLayer(source, destination, LightLayer.SKY, section);
        }
        destination.setLightEnabled(chunk.getPos(), true);
        LevelChunkSection[] sections = chunk.getSections();
        for (int index = 0; index < sections.length; index++) {
            destination.updateSectionStatus(SectionPos.of(chunk.getPos(), chunk.getSectionYFromSectionIndex(index)),
                sections[index].hasOnlyAir());
        }
    }

    private static void retainLightLayer(LevelLightEngine source, LevelLightEngine destination, LightLayer layer, SectionPos section) {
        DataLayer data = source.getLayerListener(layer).getDataLayerData(section);
        if (data != null) {
            destination.queueSectionData(layer, section, data.copy());
        }
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
