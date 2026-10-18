package art.arcane.wormholes.modded.client.render.sodium;

import art.arcane.wormholes.modded.mixin.client.SodiumArrivalRendererAccess;
import art.arcane.wormholes.modded.client.PreparedChunkColumns;
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
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.world.LevelRendererExtension;
import net.caffeinemc.mods.sodium.client.world.cloned.ClonedChunkSectionCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;

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

    public static void adoptChunks(ClientLevel target) {
        ChunkTracker tracker = ChunkTrackerHolder.get(target);
        AtomicReferenceArray<LevelChunk> columns = ((PreparedChunkColumns) target.getChunkSource()).wormholes$columns();
        for (int index = 0; index < columns.length(); index++) {
            LevelChunk chunk = columns.get(index);
            if (chunk != null) {
                tracker.onChunkStatusAdded(chunk.getPos().x(), chunk.getPos().z(), ChunkStatus.FLAG_ALL);
            }
        }
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
}
