package art.arcane.wormholes;

import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.network.replication.RemoteChunkStore;
import art.arcane.wormholes.network.view.RemoteViewCache;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WormholesNetworkRuntimeTest {
    @Test
    void receiverChunkStoreUsesConfiguredReplicationRecoveryWindow() {
        NetworkConfig network = new NetworkConfig();
        network.replication.diffWindowSize = 9;
        network.replication.resyncTimeoutSec = 13;

        RemoteViewCache cache = WormholesNetworkRuntime.createRemoteViewCache(network);
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
        RemoteViewCache cache = new RemoteViewCache(3, 4_000L);
        RemoteChunkStore existing = cache.chunkStore("hub");

        cache.applyReplicationSettings(11, 17_000L);

        assertEquals(11, existing.diffWindowSize());
        assertEquals(17_000L, existing.resyncTimeoutMillis());
        RemoteChunkStore future = cache.chunkStore("survival");
        assertEquals(11, future.diffWindowSize());
        assertEquals(17_000L, future.resyncTimeoutMillis());
    }
}
