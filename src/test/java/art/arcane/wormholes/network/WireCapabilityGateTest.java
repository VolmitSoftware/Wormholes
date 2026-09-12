package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.network.mesh.PeerAnnounce;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC 6.5: every mesh wire id is gated on a capability bit, and the set a peer advertises describes
 * the features it actually runs, not the ids its build happens to know.
 */
class WireCapabilityGateTest {
    private static final Logger LOGGER = Logger.getLogger(WireCapabilityGateTest.class.getName());

    @TempDir
    Path dataDirectory;

    @Test
    void everyMeshLaneIdIsGatedAndTheBaselineIsNot() {
        assertEquals(WireCapability.MESH_ANNOUNCE, WireGuard.of(WireMessageType.PEER_ANNOUNCE));
        assertEquals(WireCapability.MESH_ANNOUNCE, WireGuard.of(WireMessageType.PEER_TOMBSTONE));
        assertEquals(WireCapability.LOAD_BEACON, WireGuard.of(WireMessageType.LOAD_BEACON));
        assertEquals(WireCapability.PORTAL_QUERY, WireGuard.of(WireMessageType.PORTAL_QUERY));
        assertEquals(WireCapability.PORTAL_QUERY, WireGuard.of(WireMessageType.PORTAL_QUERY_RESULT));
        assertEquals(WireCapability.HANDOFF_QUEUE, WireGuard.of(WireMessageType.HANDOFF_QUEUE_STATUS));
        assertEquals(WireCapability.VIEW_ACOUSTICS, WireGuard.of(WireMessageType.VIEW_SOUND));
        assertEquals(WireCapability.VIEW_ATMOSPHERE, WireGuard.of(WireMessageType.VIEW_WEATHER));
        assertEquals(WireCapability.CONVOY, WireGuard.of(WireMessageType.CONVOY_TRANSFER));
        assertEquals(WireCapability.CONVOY, WireGuard.of(WireMessageType.CONVOY_ACK));

        for (WireMessageType type : WireMessageType.values()) {
            int id = type.id() & 0xFF;
            if (id >= 13 && id <= 19) {
                assertNotNull(WireGuard.of(type), type + " is a mesh-lane id and must be gated");
            }
        }
        assertNull(WireGuard.of(WireMessageType.HELLO));
        assertNull(WireGuard.of(WireMessageType.READY));
        assertNull(WireGuard.of(WireMessageType.PORTAL_UPSERT));
    }

    @Test
    void aPeerThatDoesNotAdvertiseTheBitIsNotSentTheFrameItGuards() throws Exception {
        NetworkConfig config = config();
        NetworkManager network = new NetworkManager(LOGGER, config, "26.2", "test", 25565, dataDirectory);
        NetworkConfig.PeerEntry peer = new NetworkConfig.PeerEntry();
        peer.name = "creative";
        peer.publicHost = "127.0.0.1";
        peer.publicPort = 25565;
        network.savePeer(peer);
        advertise(network, "creative", WireCapability.PROTOCOL_21.mask());

        PeerAnnounce announce = network.buildAnnounce(1L, 1L);
        assertFalse(network.send("creative", new WireMessage.PeerAnnounceMessage(announce)),
            "a peer without MESH_ANNOUNCE must not be sent one");
        assertTrue(network.send("creative", new WireMessage.PortalRemove(java.util.UUID.randomUUID())),
            "an ungated frame still goes out over the sideband");

        advertise(network, "creative", WireCapability.PROTOCOL_21.mask() | WireCapability.MESH_ANNOUNCE.mask());
        assertTrue(network.send("creative", new WireMessage.PeerAnnounceMessage(announce)),
            "the same frame goes out once the peer advertises the bit");
    }

    @Test
    void aDisabledMeshIsNotAdvertisedAsACapability() {
        NetworkConfig config = config();
        config.mesh.enabled = true;
        long meshOn = WireCapability.localSet(config);
        assertTrue(WireCapability.MESH_ANNOUNCE.in(meshOn));
        assertTrue(WireCapability.LOAD_BEACON.in(meshOn));
        assertTrue(WireCapability.PORTAL_QUERY.in(meshOn));
        assertTrue(WireCapability.HANDOFF_QUEUE.in(meshOn));

        config.mesh.enabled = false;
        long meshOff = WireCapability.localSet(config);
        assertFalse(WireCapability.MESH_ANNOUNCE.in(meshOff));
        assertFalse(WireCapability.LOAD_BEACON.in(meshOff));
        assertFalse(WireCapability.PORTAL_QUERY.in(meshOff));
        assertFalse(WireCapability.HANDOFF_QUEUE.in(meshOff));
        assertTrue(WireCapability.PROTOCOL_21.in(meshOff), "the baseline never depends on configuration");
        assertTrue(WireCapability.VIEW_ACOUSTICS.in(meshOff), "other lanes keep their bits");
        assertEquals(WireCapability.localSet(), WireCapability.localSet(null), "an absent config means the whole build");
    }

    @Test
    void aServerWithTheMeshOffAdvertisesTheReducedSetAndRefusesToSendMeshFrames() throws Exception {
        NetworkConfig config = config();
        config.mesh.enabled = false;
        NetworkManager network = new NetworkManager(LOGGER, config, "26.2", "test", 25565, dataDirectory);
        NetworkConfig.PeerEntry peer = new NetworkConfig.PeerEntry();
        peer.name = "creative";
        peer.publicHost = "127.0.0.1";
        peer.publicPort = 25565;
        network.savePeer(peer);
        advertise(network, "creative", WireCapability.localSet());

        assertFalse(WireCapability.MESH_ANNOUNCE.in(network.localCapabilities()));
        assertFalse(WireCapability.MESH_ANNOUNCE.in(network.peerCapabilities("creative")),
            "the negotiated set is the intersection, so a lane we turned off is not in it");
        assertFalse(network.send("creative", new WireMessage.PeerAnnounceMessage(network.buildAnnounce(1L, 1L))));
    }

    @SuppressWarnings("unchecked")
    private static void advertise(NetworkManager network, String peerName, long capabilities) throws Exception {
        Field field = NetworkManager.class.getDeclaredField("statusPeerCapabilities");
        field.setAccessible(true);
        Map<String, Long> advertised = (ConcurrentHashMap<String, Long>) field.get(network);
        advertised.put(peerName, Long.valueOf(capabilities));
    }

    private static NetworkConfig config() {
        NetworkConfig config = new NetworkConfig();
        config.enabled = true;
        config.listenEnabled = false;
        config.serverName = "survival";
        config.advertiseHostOverride = "127.0.0.1";
        return config;
    }
}
