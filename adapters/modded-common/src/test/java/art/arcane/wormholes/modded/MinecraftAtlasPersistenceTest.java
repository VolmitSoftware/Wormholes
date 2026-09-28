package art.arcane.wormholes.modded;

import art.arcane.wormholes.atlas.AtlasPlayerState;
import art.arcane.wormholes.atlas.AtlasPlayerStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MinecraftAtlasPersistenceTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void nativeJsonPreservesDiscoveryFavoritesRecentsAndGuide() {
        Path directory = temporary.getRoot().toPath();
        UUID player = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        AtlasPlayerStore store = new AtlasPlayerStore(directory, MinecraftJsonDocuments.INSTANCE);
        AtlasPlayerState state = store.load(player);
        state.discover(first);
        state.discover(second);
        state.toggleFavorite(second, 27);
        state.recordRecent(first, 10);
        state.recordRecent(second, 10);
        state.setGuideTarget(first);
        store.flushDirty();
        assertFalse(state.isDirty());
        AtlasPlayerState loaded = new AtlasPlayerStore(directory, MinecraftJsonDocuments.INSTANCE).load(player);
        assertTrue(loaded.isDiscovered(first));
        assertTrue(loaded.isDiscovered(second));
        assertEquals(List.of(second), loaded.favorites());
        assertEquals(List.of(second, first), loaded.recents());
        assertEquals(first, loaded.guideTarget());
    }
}
