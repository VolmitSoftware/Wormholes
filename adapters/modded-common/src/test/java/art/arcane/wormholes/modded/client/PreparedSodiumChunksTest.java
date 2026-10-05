package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkStatus;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTracker;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.when;

public class PreparedSodiumChunksTest extends MinecraftTestBase {
    @Test
    public void decodedNativeLightMakesTheStagedNeighborhoodAvailableToNormalSodiumRendering() {
        ChunkTracker tracker = new ChunkTracker();
        ClientLevel staged = mock(ClientLevel.class, withSettings().extraInterfaces(ChunkTrackerHolder.class));
        when(((ChunkTrackerHolder) staged).sodium$getTracker()).thenReturn(tracker);
        for (int z = -1; z <= 1; z++) {
            for (int x = -1; x <= 1; x++) {
                tracker.onChunkStatusAdded(x, z, ChunkStatus.FLAG_HAS_BLOCK_DATA);
            }
        }
        assertFalse(tracker.getReadyChunks().contains(ChunkPos.pack(0, 0)));
        for (int z = -1; z <= 1; z++) {
            for (int x = -1; x <= 1; x++) {
                ClientPreparedTravel.SodiumChunks.lightReady(staged, x, z);
            }
        }
        assertTrue(tracker.getReadyChunks().contains(ChunkPos.pack(0, 0)));
    }
}
