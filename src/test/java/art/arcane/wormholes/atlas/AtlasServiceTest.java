package art.arcane.wormholes.atlas;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.toml.AtlasConfig;
import art.arcane.wormholes.hook.TraversalAttempt;
import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.nexus.NetworkRegistry;
import art.arcane.wormholes.util.BukkitJsonDocuments;
import art.arcane.wormholes.util.JsonDocuments;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AtlasServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void discoveryReadsPlayerLocationOnlyInsideTheOwningSchedulerAndOnlyOnce() {
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.isOnline()).thenReturn(true);
        when(player.getLocation()).thenReturn(new Location(null, 0.0D, 64.0D, 0.0D));
        AtlasConfig settings = new AtlasConfig();
        AtomicReference<Runnable> task = new AtomicReference<>();
        try (AtlasPlayerStore store = new AtlasPlayerStore(tempDir, BukkitJsonDocuments.INSTANCE);
             MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
            store.loadAsync(id).join();
            AtlasService service = new AtlasService(store,
                new NetworkRegistry(tempDir.resolve("networks"), BukkitJsonDocuments.INSTANCE),
                () -> settings, List::of);
            scheduler.when(() -> FoliaScheduler.runEntity(eq(Wormholes.instance), eq(player), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    task.set(invocation.getArgument(2));
                    return true;
                });

            service.tick(List.of(player));

            verify(player, never()).getLocation();
            assertNotNull(task.get());
            task.get().run();
            verify(player, times(1)).getLocation();
        }
    }

    @Test
    void playersWithoutAGuideDoNotScheduleWorkWhenDiscoveryIsDisabled() {
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        AtlasConfig settings = new AtlasConfig();
        settings.discoveryRequired = false;
        try (AtlasPlayerStore store = new AtlasPlayerStore(tempDir, BukkitJsonDocuments.INSTANCE);
             MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
            store.loadAsync(id).join();
            AtlasService service = new AtlasService(store,
                new NetworkRegistry(tempDir.resolve("networks"), BukkitJsonDocuments.INSTANCE),
                () -> settings, List::of);

            service.tick(List.of(player));

            scheduler.verifyNoInteractions();
            verify(player, never()).getLocation();
        }
    }

    @Test
    @Timeout(10)
    void departureDuringPendingLoadSurvivesImmediateDisconnect() throws IOException, InterruptedException {
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        UUID portalId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        LocalPortal portal = mock(LocalPortal.class);
        when(portal.getId()).thenReturn(portalId);
        when(portal.getDimensionalPortalKind()).thenReturn(DimensionalPortalKind.NONE);
        TraversalAttempt attempt = mock(TraversalAttempt.class);
        when(attempt.traveler()).thenReturn(player);
        when(attempt.portal()).thenReturn(portal);
        Files.writeString(tempDir.resolve(playerId + ".json"), "{}");
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JsonDocuments json = new JsonDocuments() {
            @Override
            public Map<String, Object> decode(String source) {
                reading.countDown();
                try {
                    if (!release.await(5L, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Atlas read was not released");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Atlas read was interrupted", failure);
                }
                return BukkitJsonDocuments.INSTANCE.decode(source);
            }

            @Override
            public String encode(Map<String, Object> document) {
                return BukkitJsonDocuments.INSTANCE.encode(document);
            }
        };
        try (AtlasPlayerStore store = new AtlasPlayerStore(tempDir, json)) {
            AtlasService service = new AtlasService(store,
                new NetworkRegistry(tempDir.resolve("networks"), BukkitJsonDocuments.INSTANCE),
                AtlasConfig::new, List::of);
            store.loadAsync(playerId);
            try {
                assertTrue(reading.await(5L, TimeUnit.SECONDS));
                assertNull(store.cached(playerId));

                service.onDeparted(attempt);
                store.unloadAsync(playerId);
                release.countDown();
            } finally {
                release.countDown();
            }
        }
        try (AtlasPlayerStore reopened = new AtlasPlayerStore(tempDir, BukkitJsonDocuments.INSTANCE)) {
            AtlasPlayerState state = reopened.loadAsync(playerId).join();
            assertTrue(state.isDiscovered(portalId));
            assertEquals(List.of(portalId), state.recents());
        }
        verify(player, never()).isOnline();
        verify(player, never()).getLocation();
    }

}
