package art.arcane.wormholes.atlas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtlasPlayerStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void everyPieceOfAtlasStateSurvivesASaveAndReload() throws IOException {
        UUID playerId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        AtlasPlayerStore store = new AtlasPlayerStore(tempDir);

        AtlasPlayerState state = store.load(playerId);
        assertTrue(state.discover(first));
        assertTrue(state.discover(second));
        assertFalse(state.discover(first), "rediscovering a portal is not news");
        assertEquals(AtlasPlayerState.FavoriteResult.ADDED, state.toggleFavorite(first, 27));
        state.recordRecent(second, 10);
        state.setGuideTarget(second);
        store.save(playerId);

        assertTrue(Files.isRegularFile(tempDir.resolve(playerId + ".json")));

        AtlasPlayerState reloaded = new AtlasPlayerStore(tempDir).load(playerId);
        assertTrue(reloaded.isDiscovered(first));
        assertTrue(reloaded.isDiscovered(second));
        assertEquals(List.of(first), reloaded.favorites());
        assertEquals(List.of(second), reloaded.recents());
        assertEquals(second, reloaded.guideTarget());
    }

    @Test
    void aPlayerWithNoFileStartsEmptyAndIsNotWrittenUntilSomethingChanges() {
        UUID playerId = UUID.randomUUID();
        AtlasPlayerStore store = new AtlasPlayerStore(tempDir);

        AtlasPlayerState state = store.load(playerId);

        assertTrue(state.discovered().isEmpty());
        assertTrue(state.favorites().isEmpty());
        assertTrue(state.recents().isEmpty());
        assertNull(state.guideTarget());
        assertFalse(state.isDirty());
        assertFalse(Files.isRegularFile(tempDir.resolve(playerId + ".json")));
    }

    @Test
    void favoritesToggleOffAndRefuseToGrowPastTheLimit() {
        AtlasPlayerState state = new AtlasPlayerState(UUID.randomUUID());
        UUID pinned = UUID.randomUUID();

        assertEquals(AtlasPlayerState.FavoriteResult.ADDED, state.toggleFavorite(pinned, 2));
        assertEquals(AtlasPlayerState.FavoriteResult.ADDED, state.toggleFavorite(UUID.randomUUID(), 2));
        assertEquals(AtlasPlayerState.FavoriteResult.FULL, state.toggleFavorite(UUID.randomUUID(), 2));
        assertEquals(2, state.favorites().size());

        assertEquals(AtlasPlayerState.FavoriteResult.REMOVED, state.toggleFavorite(pinned, 2));
        assertFalse(state.isFavorite(pinned));
        assertEquals(AtlasPlayerState.FavoriteResult.ADDED, state.toggleFavorite(UUID.randomUUID(), 2));
    }

    @Test
    void recentsKeepTheNewestFirstWithoutDuplicatesAndStayInsideTheLimit() {
        AtlasPlayerState state = new AtlasPlayerState(UUID.randomUUID());
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();

        state.recordRecent(first, 2);
        state.recordRecent(second, 2);
        assertEquals(List.of(second, first), state.recents());

        state.recordRecent(first, 2);
        assertEquals(List.of(first, second), state.recents(), "using a portal again moves it to the front");

        state.recordRecent(third, 2);
        assertEquals(List.of(third, first), state.recents());
    }

    @Test
    void unloadingWritesPendingChangesAndDropsTheCachedState() throws IOException {
        UUID playerId = UUID.randomUUID();
        AtlasPlayerStore store = new AtlasPlayerStore(tempDir);
        store.load(playerId).discover(UUID.randomUUID());

        store.unload(playerId);

        assertTrue(Files.isRegularFile(tempDir.resolve(playerId + ".json")));
        assertNull(store.cached(playerId));
    }

    @Test
    void flushingWritesOnlyThePlayersWhoChangedSomething() throws IOException {
        UUID changed = UUID.randomUUID();
        UUID untouched = UUID.randomUUID();
        AtlasPlayerStore store = new AtlasPlayerStore(tempDir);
        store.load(changed).discover(UUID.randomUUID());
        store.load(untouched);

        store.flushDirty();

        assertTrue(Files.isRegularFile(tempDir.resolve(changed + ".json")));
        assertFalse(Files.isRegularFile(tempDir.resolve(untouched + ".json")));
        assertFalse(store.cached(changed).isDirty());
    }
}
