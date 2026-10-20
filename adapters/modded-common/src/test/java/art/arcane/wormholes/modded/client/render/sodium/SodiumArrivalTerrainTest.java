package art.arcane.wormholes.modded.client.render.sodium;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.PreparedChunkColumns;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTracker;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkStatus;
import net.caffeinemc.mods.sodium.client.render.chunk.storage.QueuedSectionStorage;
import net.caffeinemc.mods.sodium.client.world.LevelRendererExtension;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class SodiumArrivalTerrainTest extends MinecraftTestBase {
    @Test
    public void authoritativeHaloMakesOnlyTheInteriorNeighborhoodReady() {
        Terrain terrain = new Terrain();
        SodiumArrivalTerrain.adoptChunks(terrain.renderer, terrain.target);
        assertEquals(9, terrain.tracker.getReadyChunks().size());
        assertTrue(terrain.tracker.getReadyChunks().contains(ChunkPos.pack(32, 0)));
        assertTrue(terrain.tracker.getReadyChunks().contains(ChunkPos.pack(31, -1)));
        assertFalse(terrain.tracker.getReadyChunks().contains(ChunkPos.pack(30, 0)));
        assertNull(terrain.storage.getConsistent(SectionPos.asLong(30, 4, 0)));
    }

    @Test
    public void retainedReadyColumnsPopulateMissingSectionsWithoutReplacingPreparedMeshes() {
        Terrain terrain = new Terrain();
        terrain.populateTracker();
        terrain.tracker.forEachEvent((x, z) -> {}, (x, z) -> {});
        RenderSection prepared = mock(RenderSection.class);
        long preparedPosition = SectionPos.asLong(32, 4, 0);
        terrain.storage.queuePut(preparedPosition, prepared);

        SodiumArrivalTerrain.adoptChunks(terrain.renderer, terrain.target);
        SodiumArrivalTerrain.adoptChunks(terrain.renderer, terrain.target);

        for (int x = 31; x <= 33; x++) {
            for (int z = -1; z <= 1; z++) {
                assertNotNull(terrain.storage.getConsistent(SectionPos.asLong(x, 4, z)));
                assertNotNull(terrain.storage.getConsistent(SectionPos.asLong(x, 5, z)));
            }
        }
        assertSame(prepared, terrain.storage.getConsistent(preparedPosition));
        verify(terrain.sections, never()).onSectionAdded(32, 4, 0);
        verify(terrain.sections, times(17)).onSectionAdded(anyInt(), anyInt(), anyInt());
        verify(terrain.sections, never()).onSectionRemoved(anyInt(), anyInt(), anyInt());
        Set<Long> events = new HashSet<>();
        terrain.tracker.forEachEvent((x, z) -> events.add(ChunkPos.pack(x, z)),
            (x, z) -> events.add(ChunkPos.pack(x, z)));
        assertTrue(events.isEmpty());
    }

    @Test
    public void rendererAdoptionPreservesQueuedChunkLoadsAndUnloads() {
        Terrain terrain = new Terrain();
        for (int x = 99; x <= 101; x++) {
            for (int z = -1; z <= 1; z++) {
                terrain.tracker.onChunkStatusAdded(x, z, ChunkStatus.FLAG_ALL);
            }
        }
        terrain.tracker.forEachEvent((x, z) -> {}, (x, z) -> {});
        terrain.tracker.onChunkStatusRemoved(100, 0, ChunkStatus.FLAG_ALL);
        terrain.populateTracker();

        SodiumArrivalTerrain.adoptChunks(terrain.renderer, terrain.target);

        Set<Long> loaded = new HashSet<>();
        Set<Long> unloaded = new HashSet<>();
        terrain.tracker.forEachEvent((x, z) -> loaded.add(ChunkPos.pack(x, z)),
            (x, z) -> unloaded.add(ChunkPos.pack(x, z)));
        assertEquals(9, loaded.size());
        assertTrue(loaded.contains(ChunkPos.pack(32, 0)));
        assertEquals(Set.of(ChunkPos.pack(100, 0)), unloaded);
        assertNotNull(terrain.storage.getConsistent(SectionPos.asLong(32, 4, 0)));
        assertNull(terrain.storage.getConsistent(SectionPos.asLong(100, 4, 0)));
    }

    private static final class Terrain {
        private final ChunkTracker tracker = new ChunkTracker();
        private final ClientLevel target = mock(ClientLevel.class, withSettings().extraInterfaces(ChunkTrackerHolder.class));
        private final LevelRenderer renderer = mock(LevelRenderer.class, withSettings().extraInterfaces(LevelRendererExtension.class));
        private final RenderSectionManager sections = mock(RenderSectionManager.class, withSettings().extraInterfaces(SodiumPortalSections.class));
        private final QueuedSectionStorage storage = new QueuedSectionStorage();
        private final AtomicReferenceArray<LevelChunk> columns = new AtomicReferenceArray<>(25);

        private Terrain() {
            ClientChunkCache cache = mock(ClientChunkCache.class, withSettings().extraInterfaces(PreparedChunkColumns.class));
            SodiumWorldRenderer world = mock(SodiumWorldRenderer.class, withSettings().extraInterfaces(SodiumPortalRenderer.class));
            when(((LevelRendererExtension) renderer).sodium$getWorldRenderer()).thenReturn(world);
            when(((SodiumPortalRenderer) world).wormholes$sections()).thenReturn(sections);
            when(((SodiumPortalSections) sections).wormholes$storage()).thenReturn(storage);
            when(target.getChunkSource()).thenReturn(cache);
            when(target.getMinSectionY()).thenReturn(4);
            when(target.getMaxSectionY()).thenReturn(5);
            when(((PreparedChunkColumns) cache).wormholes$columns()).thenReturn(columns);
            when(((ChunkTrackerHolder) target).sodium$getTracker()).thenReturn(tracker);
            int index = 0;
            for (int x = 30; x <= 34; x++) {
                for (int z = -2; z <= 2; z++) {
                    LevelChunk chunk = mock(LevelChunk.class);
                    when(chunk.getPos()).thenReturn(new ChunkPos(x, z));
                    columns.set(index++, chunk);
                }
            }
            doAnswer(invocation -> {
                int x = invocation.getArgument(0);
                int y = invocation.getArgument(1);
                int z = invocation.getArgument(2);
                storage.queuePut(SectionPos.asLong(x, y, z), mock(RenderSection.class));
                return null;
            }).when(sections).onSectionAdded(anyInt(), anyInt(), anyInt());
        }

        private void populateTracker() {
            for (int index = 0; index < columns.length(); index++) {
                ChunkPos position = columns.get(index).getPos();
                tracker.onChunkStatusAdded(position.x(), position.z(), ChunkStatus.FLAG_ALL);
            }
        }
    }
}
