package art.arcane.wormholes.modded.client.render.sodium;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.PreparedChunkColumns;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTracker;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReferenceArray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class SodiumArrivalTerrainTest extends MinecraftTestBase {
    @Test
    public void authoritativeHaloMakesOnlyTheInteriorNeighborhoodReady() {
        ChunkTracker tracker = new ChunkTracker();
        ClientLevel target = mock(ClientLevel.class, withSettings().extraInterfaces(ChunkTrackerHolder.class));
        ClientChunkCache cache = mock(ClientChunkCache.class, withSettings().extraInterfaces(PreparedChunkColumns.class));
        AtomicReferenceArray<LevelChunk> columns = new AtomicReferenceArray<>(25);
        int index = 0;
        for (int x = 30; x <= 34; x++) {
            for (int z = -2; z <= 2; z++) {
                LevelChunk chunk = mock(LevelChunk.class);
                when(chunk.getPos()).thenReturn(new ChunkPos(x, z));
                columns.set(index++, chunk);
            }
        }
        when(target.getChunkSource()).thenReturn(cache);
        when(((PreparedChunkColumns) cache).wormholes$columns()).thenReturn(columns);
        when(((ChunkTrackerHolder) target).sodium$getTracker()).thenReturn(tracker);
        SodiumArrivalTerrain.adoptChunks(target);
        assertEquals(9, tracker.getReadyChunks().size());
        assertTrue(tracker.getReadyChunks().contains(ChunkPos.pack(32, 0)));
        assertTrue(tracker.getReadyChunks().contains(ChunkPos.pack(31, -1)));
        assertFalse(tracker.getReadyChunks().contains(ChunkPos.pack(30, 0)));
    }
}
