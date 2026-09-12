package art.arcane.wormholes.network.mesh;

import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.network.NetworkManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerAnnouncerTest {
    private static final Logger LOGGER = Logger.getLogger("PeerAnnouncerTest");

    @TempDir
    Path tempDir;

    private final List<NetworkManager> managers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (NetworkManager manager : managers) {
            manager.stop();
        }
    }

    private NetworkManager manager(String name) {
        NetworkConfig config = new NetworkConfig();
        config.enabled = true;
        config.serverName = name;
        config.listenEnabled = false;
        NetworkManager manager = new NetworkManager(LOGGER, config, "26.2", "test", 25565, tempDir.resolve(name));
        managers.add(manager);
        return manager;
    }

    @Test
    void epochIsMonotonicAcrossRestartsAndPersistedUnderIdentity() {
        NetworkManager manager = manager("alpha");
        AtomicLong clock = new AtomicLong(1_000L);
        PeerAnnouncer first = new PeerAnnouncer(manager, LOGGER, tempDir.resolve("alpha"), clock::get);
        first.start();
        assertEquals(1L, first.epoch());
        first.start();
        assertEquals(2L, first.epoch());
        assertTrue(Files.isRegularFile(tempDir.resolve("alpha").resolve("identity").resolve("epoch")));

        PeerAnnouncer restarted = new PeerAnnouncer(manager, LOGGER, tempDir.resolve("alpha"), clock::get);
        restarted.start();
        assertEquals(3L, restarted.epoch());
    }

    @Test
    void announceCarriesTheCurrentEpochAndTicksFollowTheIntervalAndEndpointChanges() {
        NetworkManager manager = manager("alpha");
        manager.activeConfig().mesh.announceIntervalSec = 60;
        AtomicLong clock = new AtomicLong(10_000L);
        PeerAnnouncer announcer = new PeerAnnouncer(manager, LOGGER, tempDir.resolve("alpha"), clock::get);
        announcer.start();

        PeerAnnounce announce = announcer.announceNow();
        assertTrue(announce.verify());
        assertEquals("alpha", announce.name());
        assertEquals(1L, announce.epoch());
        assertEquals(10_000L, announce.issuedAtMillis());

        clock.addAndGet(1_000L);
        assertFalse(announcer.tick());
        clock.addAndGet(60_000L);
        assertTrue(announcer.tick());
        assertEquals(1L, announcer.epoch());

        manager.setInferredAdvertiseHost("203.0.113.9");
        clock.addAndGet(1_000L);
        assertTrue(announcer.tick());
        assertEquals(2L, announcer.epoch());
        assertEquals("203.0.113.9", announcer.announceNow().advertiseHost());
    }

    @Test
    void disabledMeshNeverAnnounces() {
        NetworkManager manager = manager("alpha");
        manager.activeConfig().mesh.enabled = false;
        AtomicLong clock = new AtomicLong(10_000L);
        PeerAnnouncer announcer = new PeerAnnouncer(manager, LOGGER, tempDir.resolve("alpha"), clock::get);
        announcer.start();
        clock.addAndGet(120_000L);
        assertFalse(announcer.tick());
    }
}
