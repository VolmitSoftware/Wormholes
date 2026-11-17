package art.arcane.wormholes.modded.client.render.sodium;

import art.arcane.wormholes.modded.client.PreparedChunkColumns;
import art.arcane.wormholes.modded.mixin.client.SodiumArrivalRendererAccess;
import art.arcane.wormholes.modded.mixin.client.SodiumArrivalSectionsAccess;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMaps;
import it.unimi.dsi.fastutil.longs.LongIterator;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder;
import me.jellysquid.mods.sodium.client.render.chunk.map.ChunkStatus;
import me.jellysquid.mods.sodium.client.render.chunk.map.ChunkTracker;
import me.jellysquid.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import me.jellysquid.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;
import me.jellysquid.mods.sodium.client.world.WorldRendererExtended;
import me.jellysquid.mods.sodium.client.world.cloned.ClonedChunkSectionCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;

public final class SodiumArrivalTerrain {
    private SodiumArrivalTerrain() {
    }

    public static boolean ready(LevelRenderer renderer, List<Long> required) {
        SodiumPortalRenderer world = world(renderer);
        RenderSectionManager sections = world == null ? null : world.wormholes$sections();
        if (sections == null) {
            return false;
        }
        SodiumLayers.managed(sections::uploadChunks);
        if (sections.getBuilder().getScheduledJobCount() != 0 || sections.getBuilder().getBusyThreadCount() != 0) {
            return false;
        }
        SodiumPortalSections access = (SodiumPortalSections) sections;
        for (long key : required) {
            if (!SodiumSectionCollector.complete(access.wormholes$storage().get(key))) {
                return false;
            }
        }
        return true;
    }

    public static long bytes(LevelRenderer renderer) {
        SodiumPortalRenderer world = world(renderer);
        RenderSectionManager sections = world == null ? null : world.wormholes$sections();
        if (sections == null) {
            return 0L;
        }
        long bytes = 0L;
        for (RenderRegion region : ((SodiumPortalSections) sections).wormholes$regions().getLoadedRegions()) {
            RenderRegion.DeviceResources resources = region.getResources();
            if (resources != null) {
                bytes += resources.getGeometryArena().getDeviceAllocatedMemory();
            }
        }
        return bytes;
    }

    public static void adoptChunks(LevelRenderer renderer, ClientLevel target) {
        ChunkTracker tracker = ChunkTrackerHolder.get(target);
        AtomicReferenceArray<LevelChunk> columns = ((PreparedChunkColumns) target.getChunkSource()).wormholes$columns();
        for (int index = 0; index < columns.length(); index++) {
            LevelChunk chunk = columns.get(index);
            if (chunk != null) {
                tracker.onChunkStatusAdded(chunk.getPos().x, chunk.getPos().z, ChunkStatus.FLAG_ALL);
            }
        }
        SodiumPortalRenderer world = world(renderer);
        if (world != null && world.wormholes$sections() != null) {
            addMissingSections(world.wormholes$sections(), target, tracker);
        }
    }

    public static void rebind(LevelRenderer renderer, ClientLevel target) {
        SodiumWorldRenderer sodium = ((WorldRendererExtended) renderer).sodium$getWorldRenderer();
        RenderSectionManager sections = ((SodiumPortalRenderer) sodium).wormholes$sections();
        SodiumPortalSections access = (SodiumPortalSections) sections;
        if (sections.getBuilder().getScheduledJobCount() != 0 || sections.getBuilder().getBusyThreadCount() != 0) {
            throw new IllegalStateException("Cannot transfer a Sodium renderer with active mesh workers");
        }
        ClonedChunkSectionCache cache = new ClonedChunkSectionCache(target);
        OcclusionCuller culler = new OcclusionCuller(Long2ReferenceMaps.unmodifiable(access.wormholes$storage()), target);
        ChunkBuilder replacement = new ChunkBuilder(target, access.wormholes$vertexType());
        sections.getBuilder().shutdown();
        SodiumArrivalSectionsAccess mutable = (SodiumArrivalSectionsAccess) sections;
        mutable.wormholes$arrivalLevel(target);
        mutable.wormholes$arrivalBuilder(replacement);
        mutable.wormholes$arrivalCache(cache);
        mutable.wormholes$arrivalCuller(culler);
        ((SodiumArrivalRendererAccess) sodium).wormholes$arrivalLevel(target);
        sections.markGraphDirty();
    }

    private static SodiumPortalRenderer world(LevelRenderer renderer) {
        return (SodiumPortalRenderer) ((WorldRendererExtended) renderer).sodium$getWorldRenderer();
    }

    private static void addMissingSections(RenderSectionManager sections, ClientLevel target, ChunkTracker tracker) {
        SodiumPortalSections access = (SodiumPortalSections) sections;
        LongIterator ready = tracker.getReadyChunks().iterator();
        while (ready.hasNext()) {
            long position = ready.nextLong();
            int x = ChunkPos.getX(position);
            int z = ChunkPos.getZ(position);
            for (int y = target.getMinSection(); y < target.getMaxSection(); y++) {
                if (!access.wormholes$storage().containsKey(SectionPos.asLong(x, y, z))) {
                    sections.onSectionAdded(x, y, z);
                }
            }
        }
    }
}
