package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkPersistenceTest {
    @TempDir
    Path tempDir;

    @Test
    void identityStoreMigratesLegacyPairAndRepairsMirrors() throws Exception {
        IdentityStore created = IdentityStore.loadOrCreate(tempDir);
        byte[] expectedPublicKey = created.publicKeyBytes();
        Path identityDirectory = tempDir.resolve("identity");
        Path identityPath = identityDirectory.resolve("server.identity");
        Path privatePath = identityDirectory.resolve("server.key");
        Path publicPath = identityDirectory.resolve("server.pub");
        assertTrue(Files.isRegularFile(identityPath));

        Files.delete(identityPath);
        IdentityStore migrated = IdentityStore.loadOrCreate(tempDir);
        assertArrayEquals(expectedPublicKey, migrated.publicKeyBytes());
        assertTrue(Files.isRegularFile(identityPath));

        Files.write(privatePath, new byte[]{1, 2, 3});
        Files.write(publicPath, new byte[]{4, 5, 6});
        IdentityStore repaired = IdentityStore.loadOrCreate(tempDir);
        assertArrayEquals(expectedPublicKey, repaired.publicKeyBytes());
        assertArrayEquals(expectedPublicKey, Files.readAllBytes(publicPath));
        assertTrue(Files.readAllBytes(privatePath).length > 3);
    }

    @Test
    void peerRouteStoreRoundTripsPersistedRoute() throws Exception {
        PeerRouteStore store = PeerRouteStore.loadOrCreate(tempDir);
        NetworkConfig.PeerEntry route = new NetworkConfig.PeerEntry();
        route.name = "beta";
        route.host = "10.0.0.2";
        route.fallbackHosts = "198.51.100.2";
        route.port = 8902;
        route.publicHost = "203.0.113.2";
        route.publicPort = 25566;
        route.useProxy = true;
        store.save(route);

        NetworkConfig.PeerEntry reloaded = PeerRouteStore.loadOrCreate(tempDir).get("beta");
        assertNotNull(reloaded);
        assertEquals(route.host, reloaded.host);
        assertEquals(route.fallbackHosts, reloaded.fallbackHosts);
        assertEquals(route.port, reloaded.port);
        assertEquals(route.publicHost, reloaded.publicHost);
        assertEquals(route.publicPort, reloaded.publicPort);
        assertTrue(reloaded.useProxy);
    }

    @Test
    void peerTrustStoreRoundTripsPersistedKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        KeyPair keyPair = generator.generateKeyPair();
        byte[] publicKey = keyPair.getPublic().getEncoded();
        PeerTrustStore store = PeerTrustStore.loadOrCreate(tempDir);
        assertTrue(store.trust("beta", publicKey));

        PeerTrustStore reloaded = PeerTrustStore.loadOrCreate(tempDir);
        assertTrue(reloaded.isTrusted("beta", publicKey));
        assertArrayEquals(publicKey, reloaded.get("beta"));
    }

    @Test
    void peerRouteStoreRemoveDeletesPersistedRoute() throws Exception {
        PeerRouteStore store = PeerRouteStore.loadOrCreate(tempDir);
        NetworkConfig.PeerEntry route = new NetworkConfig.PeerEntry();
        route.name = "beta";
        route.host = "10.0.0.2";
        store.save(route);

        assertTrue(store.remove("beta"));
        assertNull(store.get("beta"));
        assertFalse(store.remove("beta"));
        assertNull(PeerRouteStore.loadOrCreate(tempDir).get("beta"));
    }

    @Test
    void peerTrustStoreRemoveForgetsPersistedKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        byte[] publicKey = generator.generateKeyPair().getPublic().getEncoded();
        PeerTrustStore store = PeerTrustStore.loadOrCreate(tempDir);
        assertTrue(store.trust("beta", publicKey));

        assertTrue(store.remove("beta"));
        assertNull(store.get("beta"));
        assertNull(store.getPublicKey("beta"));
        assertFalse(store.isTrusted("beta", publicKey));
        assertFalse(store.remove("beta"));
        assertNull(PeerTrustStore.loadOrCreate(tempDir).get("beta"));
    }
}
