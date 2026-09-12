package art.arcane.wormholes.network.mesh;

import art.arcane.wormholes.network.GameEndpoint;
import art.arcane.wormholes.network.WireCapability;
import art.arcane.wormholes.network.WireCodec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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

class PeerQuarantineStoreTest {
    @TempDir
    Path tempDir;

    private static PeerAnnounce announce(KeyPair keys, String name, long epoch) {
        return new PeerAnnounce(name, WireCodec.PROTOCOL_VERSION, "test", "10.0.0.7", 8903, new GameEndpoint("10.0.0.7", 25567), new GameEndpoint("192.168.0.7", 25565),
            keys.getPublic().getEncoded(), epoch, WireCapability.localSet(), 100L + epoch, new byte[0]).signWith(keys.getPrivate());
    }

    @Test
    void entriesPersistAcrossReloadWithIntroducerEpochAndFirstSeen() throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        PeerQuarantineStore store = PeerQuarantineStore.loadOrCreate(tempDir);
        assertTrue(store.put(announce(keys, "gamma", 3L), "beta", 5_000L));
        assertTrue(Files.isRegularFile(tempDir.resolve("trust").resolve("quarantine.properties")));

        PeerQuarantineStore reloaded = PeerQuarantineStore.loadOrCreate(tempDir);
        PeerQuarantineStore.Entry entry = reloaded.get("gamma");
        assertNotNull(entry);
        assertEquals("beta", entry.introducer());
        assertEquals(3L, entry.announce().epoch());
        assertEquals(5_000L, entry.firstSeenMillis());
        assertArrayEquals(keys.getPublic().getEncoded(), entry.announce().publicKey());
        assertTrue(entry.announce().verify());
        assertEquals(1, reloaded.all().size());
    }

    @Test
    void newerEpochReplacesAndOlderEpochIsIgnoredWhileFirstSeenIsKept() throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        PeerQuarantineStore store = PeerQuarantineStore.loadOrCreate(tempDir);
        assertTrue(store.put(announce(keys, "gamma", 3L), "beta", 5_000L));
        assertFalse(store.put(announce(keys, "gamma", 2L), "delta", 6_000L));
        assertEquals("beta", store.get("gamma").introducer());
        assertTrue(store.put(announce(keys, "gamma", 4L), "delta", 7_000L));
        assertEquals(4L, store.get("gamma").announce().epoch());
        assertEquals("delta", store.get("gamma").introducer());
        assertEquals(5_000L, store.get("gamma").firstSeenMillis());
    }

    @Test
    void matchesOnlyTheQuarantinedKeyAndRemoveForgetsTheEntry() throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        PeerQuarantineStore store = PeerQuarantineStore.loadOrCreate(tempDir);
        store.put(announce(keys, "gamma", 1L), "beta", 1L);
        assertTrue(store.matches("gamma", keys.getPublic().getEncoded()));
        assertFalse(store.matches("gamma", other.getPublic().getEncoded()));
        assertFalse(store.matches("delta", keys.getPublic().getEncoded()));
        assertTrue(store.remove("gamma"));
        assertFalse(store.remove("gamma"));
        assertNull(PeerQuarantineStore.loadOrCreate(tempDir).get("gamma"));
    }

    @Test
    void tombstoneServicePersistsAndAppliesEpochAndKeyRules() throws IOException {
        byte[] key = new byte[] {1, 2, 3};
        byte[] otherKey = new byte[] {4, 5, 6};
        TombstoneService service = TombstoneService.loadOrCreate(tempDir);
        service.record(new PeerTombstone("gamma", 4L, key, 10_000L, new byte[0]));
        long ttl = 1_000L;
        assertTrue(service.ignoresAnnounce("gamma", 4L, 10_500L, ttl));
        assertTrue(service.ignoresAnnounce("gamma", 3L, 10_500L, ttl));
        assertFalse(service.ignoresAnnounce("gamma", 5L, 10_500L, ttl));
        assertFalse(service.ignoresAnnounce("gamma", 4L, 11_001L, ttl));
        assertTrue(service.blocksKey("gamma", key, 10_500L, ttl));
        assertFalse(service.blocksKey("gamma", otherKey, 10_500L, ttl));
        assertFalse(service.blocksKey("delta", key, 10_500L, ttl));

        TombstoneService reloaded = TombstoneService.loadOrCreate(tempDir);
        assertTrue(reloaded.blocksKey("gamma", key, 10_500L, ttl));
        assertEquals(4L, reloaded.epochOf("gamma", 0L));
        reloaded.clear("gamma");
        assertFalse(reloaded.blocksKey("gamma", key, 10_500L, ttl));
        assertFalse(TombstoneService.loadOrCreate(tempDir).blocksKey("gamma", key, 10_500L, ttl));
    }
}
