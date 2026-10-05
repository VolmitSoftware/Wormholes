package art.arcane.wormholes.atlas;

import art.arcane.wormholes.util.BukkitJsonDocuments;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import art.arcane.wormholes.util.JsonDocuments;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class AtlasPlayerStoreTest {
    private final List<AtlasPlayerStore> stores = new ArrayList<>();
    @TempDir
    Path tempDir;

    @Test
    void everyPieceOfAtlasStateSurvivesASaveAndReload() throws IOException {
        UUID playerId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        AtlasPlayerStore store = newStore(BukkitJsonDocuments.INSTANCE);

        AtlasPlayerState state = store.loadAsync(playerId).join();
        assertTrue(state.discover(first));
        assertTrue(state.discover(second));
        assertFalse(state.discover(first), "rediscovering a portal is not news");
        assertEquals(AtlasPlayerState.FavoriteResult.ADDED, state.toggleFavorite(first, 27));
        state.recordRecent(second, 10);
        state.setGuideTarget(second);
        store.flushDirtyAsync().join();

        assertTrue(Files.isRegularFile(tempDir.resolve(playerId + ".json")));

        AtlasPlayerState reloaded = newStore(BukkitJsonDocuments.INSTANCE).loadAsync(playerId).join();
        assertTrue(reloaded.isDiscovered(first));
        assertTrue(reloaded.isDiscovered(second));
        assertEquals(List.of(first), reloaded.favorites());
        assertEquals(List.of(second), reloaded.recents());
        assertEquals(second, reloaded.guideTarget());
    }

    @Test
    void aPlayerWithNoFileStartsEmptyAndIsNotWrittenUntilSomethingChanges() {
        UUID playerId = UUID.randomUUID();
        AtlasPlayerStore store = newStore(BukkitJsonDocuments.INSTANCE);

        AtlasPlayerState state = store.loadAsync(playerId).join();

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
        AtlasPlayerStore store = newStore(BukkitJsonDocuments.INSTANCE);
        store.loadAsync(playerId).join().discover(UUID.randomUUID());

        store.unloadAsync(playerId).join();

        assertTrue(Files.isRegularFile(tempDir.resolve(playerId + ".json")));
        assertNull(store.cached(playerId));
    }

    @Test
    void flushingWritesOnlyThePlayersWhoChangedSomething() throws IOException {
        UUID changed = UUID.randomUUID();
        UUID untouched = UUID.randomUUID();
        AtlasPlayerStore store = newStore(BukkitJsonDocuments.INSTANCE);
        store.loadAsync(changed).join().discover(UUID.randomUUID());
        store.loadAsync(untouched).join();

        store.flushDirtyAsync().join();

        assertTrue(Files.isRegularFile(tempDir.resolve(changed + ".json")));
        assertFalse(Files.isRegularFile(tempDir.resolve(untouched + ".json")));
        assertFalse(store.cached(changed).isDirty());
    }

    @Test
    void cleanQuitAndShutdownDoNotCreatePlayerFiles() {
        UUID quitting = UUID.randomUUID();
        UUID online = UUID.randomUUID();
        AtlasPlayerStore store = newStore(BukkitJsonDocuments.INSTANCE);
        store.loadAsync(quitting).join();
        store.loadAsync(online).join();

        store.unloadAsync(quitting).join();
        store.close();

        assertFalse(Files.exists(tempDir.resolve(quitting + ".json")));
        assertFalse(Files.exists(tempDir.resolve(online + ".json")));
    }

    @Test
    void quitDetachesWithoutWaitingForDiskAndReconnectKeepsLatestState() throws InterruptedException {
        UUID playerId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        BlockingJson json = new BlockingJson();
        AtlasPlayerStore store = newStore(json);
        AtlasPlayerState state = store.loadAsync(playerId).join();
        state.discover(first);
        CompletableFuture<Void> flush = store.flushDirtyAsync();
        assertTrue(json.writing.await(5L, TimeUnit.SECONDS));
        try {
            state.discover(second);
            CompletableFuture<Void> quit = store.unloadAsync(playerId);
            assertNull(store.cached(playerId));
            assertFalse(quit.isDone());
            CompletableFuture<AtlasPlayerState> reconnect = store.loadAsync(playerId);
            assertFalse(reconnect.isDone());

            json.release.countDown();
            AtlasPlayerState reloaded = reconnect.join();
            flush.join();
            quit.join();

            assertTrue(reloaded.isDiscovered(first));
            assertTrue(reloaded.isDiscovered(second));
            assertFalse(reloaded.isDirty());
            assertEquals(2, json.writes.get());
        } finally {
            json.release.countDown();
        }
    }

    @Test
    void failedEncodingKeepsDirtyStateAndDoesNotAbortOtherPlayerSaves() {
        AtomicInteger writes = new AtomicInteger();
        JsonDocuments json = new JsonDocuments() {
            @Override
            public Map<String, Object> decode(String source) {
                return BukkitJsonDocuments.INSTANCE.decode(source);
            }

            @Override
            public String encode(Map<String, Object> document) {
                if (writes.incrementAndGet() == 1) {
                    throw new IllegalStateException("Atlas encoding failed");
                }
                return BukkitJsonDocuments.INSTANCE.encode(document);
            }
        };
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        AtlasPlayerStore store = newStore(json);
        AtlasPlayerState firstState = store.loadAsync(first).join();
        AtlasPlayerState secondState = store.loadAsync(second).join();
        firstState.discover(UUID.randomUUID());
        secondState.discover(UUID.randomUUID());

        store.flushDirtyAsync().join();

        assertEquals(2, writes.get());
        assertTrue(firstState.isDirty() != secondState.isDirty());
        store.flushDirtyAsync().join();
        assertFalse(firstState.isDirty());
        assertFalse(secondState.isDirty());
        assertTrue(Files.isRegularFile(tempDir.resolve(first + ".json")));
        assertTrue(Files.isRegularFile(tempDir.resolve(second + ".json")));
    }

    @Test
    void shutdownDrainsAlreadyQueuedQuitSaves() throws InterruptedException {
        UUID playerId = UUID.randomUUID();
        UUID portalId = UUID.randomUUID();
        BlockingJson json = new BlockingJson();
        AtlasPlayerStore store = newStore(json);
        store.loadAsync(playerId).join().discover(portalId);
        store.unloadAsync(playerId);
        assertTrue(json.writing.await(5L, TimeUnit.SECONDS));
        CompletableFuture<Void> shutdown = CompletableFuture.runAsync(store::close);
        try {
            assertFalse(shutdown.isDone());
            json.release.countDown();
            shutdown.join();

            AtlasPlayerState reloaded = newStore(BukkitJsonDocuments.INSTANCE).loadAsync(playerId).join();
            assertTrue(reloaded.isDiscovered(portalId));
        } finally {
            json.release.countDown();
        }
    }

    @AfterEach
    void closeStores() {
        for (AtlasPlayerStore store : stores) {
            store.close();
        }
    }

    private AtlasPlayerStore newStore(JsonDocuments json) {
        AtlasPlayerStore store = new AtlasPlayerStore(tempDir, json);
        stores.add(store);
        return store;
    }

    private static final class BlockingJson implements JsonDocuments {
        private final CountDownLatch writing = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger writes = new AtomicInteger();

        @Override
        public Map<String, Object> decode(String source) {
            return BukkitJsonDocuments.INSTANCE.decode(source);
        }

        @Override
        public String encode(Map<String, Object> document) {
            if (writes.incrementAndGet() == 1) {
                writing.countDown();
                try {
                    if (!release.await(5L, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Atlas write was not released");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Atlas write was interrupted", failure);
                }
            }
            return BukkitJsonDocuments.INSTANCE.encode(document);
        }
    }

}
