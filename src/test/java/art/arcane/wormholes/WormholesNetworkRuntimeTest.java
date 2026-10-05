package art.arcane.wormholes;

import art.arcane.wormholes.network.view.BukkitRemoteViewCodec;

import com.github.retrooper.packetevents.protocol.player.Equipment;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;

import org.bukkit.block.data.BlockData;

import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.network.replication.RemoteChunkStore;
import art.arcane.wormholes.network.view.RemoteViewCache;

import org.junit.jupiter.api.Test;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.PortalSyncService;
import art.arcane.wormholes.portal.ILocalPortal;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WormholesNetworkRuntimeTest {
    @Test
    void receiverChunkStoreUsesConfiguredReplicationRecoveryWindow() {
        NetworkConfig network = new NetworkConfig();
        network.replication.diffWindowSize = 9;
        network.replication.resyncTimeoutSec = 13;

        RemoteViewCache<BlockData, EntityData<?>, Equipment> cache = WormholesNetworkRuntime.createRemoteViewCache(network);
        RemoteChunkStore store = cache.chunkStore("hub");

        assertEquals(9, store.diffWindowSize());
        assertEquals(13_000L, store.resyncTimeoutMillis());
    }

    @Test
    void missingReplicationSettingsKeepReceiverDefaults() {
        NetworkConfig network = new NetworkConfig();
        network.replication = null;

        RemoteChunkStore store = WormholesNetworkRuntime.createRemoteViewCache(network).chunkStore("hub");

        assertEquals(RemoteChunkStore.DEFAULT_DIFF_WINDOW_SIZE, store.diffWindowSize());
        assertEquals(RemoteChunkStore.DEFAULT_RESYNC_TIMEOUT_MS, store.resyncTimeoutMillis());
    }

    @Test
    void replicationSettingsApplyToExistingAndFuturePeerStores() {
        RemoteViewCache<BlockData, EntityData<?>, Equipment> cache = new RemoteViewCache<>(BukkitRemoteViewCodec.INSTANCE, new RemoteViewCache.Options(3, 4_000L));
        RemoteChunkStore existing = cache.chunkStore("hub");

        cache.applyReplicationSettings(11, 17_000L);

        assertEquals(11, existing.diffWindowSize());
        assertEquals(17_000L, existing.resyncTimeoutMillis());
        RemoteChunkStore future = cache.chunkStore("survival");
        assertEquals(11, future.diffWindowSize());
        assertEquals(17_000L, future.resyncTimeoutMillis());
    }
    @Test
    @SuppressWarnings("unchecked")
    void delayedSyncTaskCannotCrossNetworkGenerationsOrRunAfterSyncShutdown() throws ReflectiveOperationException {
        NetworkManager previousNetwork = Wormholes.networkManager;
        PortalSyncService<ILocalPortal> previousSync = Wormholes.portalSyncService;
        try {
            Wormholes plugin = mock(Wormholes.class);
            when(plugin.isEnabled()).thenReturn(true);
            WormholesNetworkRuntime runtime = new WormholesNetworkRuntime(plugin);
            NetworkManager original = mock(NetworkManager.class);
            PortalSyncService<ILocalPortal> sync = mock(PortalSyncService.class);
            Wormholes.networkManager = original;
            Wormholes.portalSyncService = sync;
            AtomicInteger executions = new AtomicInteger();
            Method execute = WormholesNetworkRuntime.class.getDeclaredMethod("runCurrentPortalSyncTask", NetworkManager.class, Runnable.class);
            execute.setAccessible(true);
            execute.invoke(runtime, original, (Runnable) executions::incrementAndGet);
            assertEquals(1, executions.get());
            Wormholes.networkManager = mock(NetworkManager.class);
            execute.invoke(runtime, original, (Runnable) executions::incrementAndGet);
            assertEquals(1, executions.get());
            Wormholes.networkManager = original;
            when(sync.isClosed()).thenReturn(true);
            execute.invoke(runtime, original, (Runnable) executions::incrementAndGet);
            assertEquals(1, executions.get());
        } finally {
            Wormholes.networkManager = previousNetwork;
            Wormholes.portalSyncService = previousSync;
        }
    }
}
