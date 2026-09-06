package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerRouteStoreTest {
    @TempDir
    Path directory;

    @Test
    void independentRawPublicAndPrivateEndpointsSurviveReload() throws Exception {
        PeerRouteStore store = PeerRouteStore.loadOrCreate(directory);
        NetworkConfig.PeerEntry peer = new NetworkConfig.PeerEntry();
        peer.name = "beta";
        peer.host = "wire.example";
        peer.fallbackHosts = "10.0.0.2";
        peer.port = 8902;
        peer.publicHost = "play.example";
        peer.publicPort = 25580;
        peer.privateHost = "10.0.0.2";
        peer.privatePort = 25566;
        peer.useProxy = true;
        store.save(peer);
        peer.publicPort = 1111;
        store.get("beta").privatePort = 2222;

        NetworkConfig.PeerEntry loaded = PeerRouteStore.loadOrCreate(directory).get("beta");
        assertEquals("wire.example", loaded.host);
        assertEquals(8902, loaded.port);
        assertEquals("play.example", loaded.publicHost);
        assertEquals(25580, loaded.publicPort);
        assertEquals("10.0.0.2", loaded.privateHost);
        assertEquals(25566, loaded.privatePort);
        assertTrue(loaded.useProxy);
    }
}
