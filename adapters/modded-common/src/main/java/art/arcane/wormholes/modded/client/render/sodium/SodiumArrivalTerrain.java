package art.arcane.wormholes.modded.client.render.sodium;

import art.arcane.wormholes.modded.mixin.client.SodiumArrivalRendererAccess;
import art.arcane.wormholes.modded.client.PreparedChunkColumns;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTracker;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import java.util.concurrent.atomic.AtomicReferenceArray;
import art.arcane.wormholes.modded.mixin.client.SodiumArrivalBuilderAccess;
import art.arcane.wormholes.modded.mixin.client.SodiumArrivalBuffersAccess;
import art.arcane.wormholes.modded.mixin.client.SodiumArrivalSectionsAccess;
import net.caffeinemc.mods.sodium.client.gpu.arena.ArenaAggregator;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.estimation.MeshTaskSizeEstimator;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import net.caffeinemc.mods.sodium.client.render.chunk.storage.SectionStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.world.LevelRendererExtension;
import net.caffeinemc.mods.sodium.client.world.cloned.ClonedChunkSectionCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;

import java.util.List;

public final class SodiumArrivalTerrain {
    private SodiumArrivalTerrain() {
    }

    public static boolean ready(LevelRenderer renderer, List<Long> required) {
        RenderSectionManager sections = sections(renderer);
        if (sections == null || sections.getBuilder().getScheduledJobCount() != 0 || sections.getBuilder().getBusyThreadCount() != 0) {
            return false;
        }
        SodiumPortalSections access = (SodiumPortalSections) sections;
        if (access.wormholes$culling()) {
            access.wormholes$settleCulling();
        }
        for (long key : required) {
            RenderSection section = access.wormholes$storage().getConsistent(key);
            if (section == null || !((SodiumArrivalSection) section).wormholes$terrainComplete()) {
                return false;
            }
        }
        return true;
    }

    public static long bytes(LevelRenderer renderer) {
        RenderSectionManager sections = sections(renderer);
        if (sections == null) {
            return 0L;
        }
        ArenaAggregator arenas = sections.regions.getArenaAggregator();
        return arenas.getGeometryDeviceAllocatedMemory() + arenas.getIndexDeviceAllocatedMemory() + arenas.getMiscAllocatedMemory();
    }

    public static void adoptChunks(LevelRenderer renderer, ClientLevel target) {
        ChunkTracker tracker = ChunkTrackerHolder.get(target);
        AtomicReferenceArray<LevelChunk> columns = ((PreparedChunkColumns) target.getChunkSource()).wormholes$columns();
        for (int index = 0; index < columns.length(); index++) {
            LevelChunk chunk = columns.get(index);
            if (chunk != null) {
                tracker.onChunkStatusAdded(chunk.getPos().x(), chunk.getPos().z(), ChunkStatus.FLAG_ALL);
            }
        }
        addMissingSections(sections(renderer), target, tracker);
    }

    public static void rebind(LevelRenderer renderer, ClientLevel target) {
        SodiumWorldRenderer world = ((LevelRendererExtension) renderer).sodium$getWorldRenderer();
        RenderSectionManager sections = ((SodiumPortalRenderer) world).wormholes$sections();
        SodiumPortalSections access = (SodiumPortalSections) sections;
        if (access.wormholes$culling()) {
            access.wormholes$settleCulling();
        }
        if (sections.getBuilder().getScheduledJobCount() != 0 || sections.getBuilder().getBusyThreadCount() != 0) {
            throw new IllegalStateException("Cannot transfer a Sodium renderer with active mesh workers");
        }
        ClonedChunkSectionCache cache = new ClonedChunkSectionCache(target);
        MeshTaskSizeEstimator estimator = new MeshTaskSizeEstimator(target);
        OcclusionCuller culler = new OcclusionCuller(access.wormholes$storage(), target);
        ChunkVertexType vertices = ((SodiumArrivalBuffersAccess) ((SodiumArrivalBuilderAccess) sections.getBuilder())
            .wormholes$context().buffers).wormholes$vertexType();
        ChunkBuilder replacement = new ChunkBuilder(target, vertices);
        sections.getBuilder().shutdown();
        SodiumArrivalSectionsAccess mutable = (SodiumArrivalSectionsAccess) sections;
        mutable.wormholes$arrivalLevel(target);
        mutable.wormholes$arrivalBuilder(replacement);
        mutable.wormholes$arrivalCache(cache);
        mutable.wormholes$arrivalEstimator(estimator);
        mutable.wormholes$arrivalCuller(culler);
        ((SodiumArrivalRendererAccess) world).wormholes$arrivalLevel(target);
        sections.markGraphDirty();
    }

    private static RenderSectionManager sections(LevelRenderer renderer) {
        return ((SodiumPortalRenderer) ((LevelRendererExtension) renderer).sodium$getWorldRenderer()).wormholes$sections();
    }

    private static void addMissingSections(RenderSectionManager sections, ClientLevel target, ChunkTracker tracker) {
        sections.beforeSectionUpdates();
        SectionStorage storage = ((SodiumPortalSections) sections).wormholes$storage();
        LongIterator ready = tracker.getReadyChunks().iterator();
        while (ready.hasNext()) {
            long position = ready.nextLong();
            int x = ChunkPos.getX(position);
            int z = ChunkPos.getZ(position);
            for (int y = target.getMinSectionY(); y <= target.getMaxSectionY(); y++) {
                if (!storage.hasSectionConsistent(SectionPos.asLong(x, y, z))) {
                    sections.onSectionAdded(x, y, z);
                }
            }
        }
    }
}
