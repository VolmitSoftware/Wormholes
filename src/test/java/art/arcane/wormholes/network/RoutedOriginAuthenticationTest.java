package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutedOriginAuthenticationTest {
    private static final Logger LOGGER = Logger.getLogger("RoutedOriginAuthenticationTest");

    @TempDir
    Path tempDir;

    private final List<NetworkManager> managers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (NetworkManager manager : managers) {
            manager.stop();
        }
    }

    @Test
    void trustedOriginSignatureSurvivesRelayTtlChangesAndDelivers() throws IOException {
        NetworkManager origin = manager("origin");
        NetworkManager destination = manager("destination");
        destination.trustPeer("origin", origin.getPublicKey());
        List<String> delivered = new ArrayList<>();
        destination.setMessageSink((peerName, message) -> delivered.add(peerName + ":" + message.type()));
        WireMessage.Routed routed = origin.relay().createRouted(
            "destination",
            RelayRouter.ROUTE_TTL,
            new WireMessage.ViewTime(UUID.randomUUID(), 7)
        );

        assertTrue(destination.relay().handleRouted("trusted-relay", routed.withTtl(2)));
        assertEquals(List.of("origin:VIEW_TIME"), delivered);
        assertEquals("trusted-relay", destination.relay().nextHop("origin"));
    }

    @Test
    void trustedRelayCannotReplaceTheSignedOrigin() throws IOException {
        NetworkManager origin = manager("origin");
        NetworkManager relay = manager("trusted-relay");
        NetworkManager destination = manager("destination");
        destination.trustPeer("origin", origin.getPublicKey());
        destination.trustPeer("trusted-relay", relay.getPublicKey());
        List<WireMessage> delivered = new ArrayList<>();
        destination.setMessageSink((peerName, message) -> delivered.add(message));
        WireMessage.Routed relayMessage = relay.relay().createRouted(
            "destination",
            RelayRouter.ROUTE_TTL,
            new WireMessage.ViewTime(UUID.randomUUID(), 7)
        );
        WireMessage.Routed forged = new WireMessage.Routed(
            "origin",
            relayMessage.targetServer(),
            relayMessage.ttl(),
            relayMessage.innerType(),
            relayMessage.payload(),
            relayMessage.signature()
        );

        assertFalse(destination.relay().handleRouted("trusted-relay", forged));
        assertTrue(delivered.isEmpty());
        assertNull(destination.relay().nextHop("origin"));
    }

    @Test
    void unpinnedIndirectOriginIsRejectedBeforeRouteLearning() throws IOException {
        NetworkManager origin = manager("origin");
        NetworkManager destination = manager("destination");
        WireMessage.Routed routed = origin.relay().createRouted(
            "destination",
            RelayRouter.ROUTE_TTL,
            new WireMessage.ViewTime(UUID.randomUUID(), 7)
        );

        assertFalse(destination.relay().handleRouted("trusted-relay", routed));
        assertNull(destination.relay().nextHop("origin"));
    }

    @Test
    void payloadAndSensitiveTargetTamperingInvalidateTheSignature() throws IOException {
        NetworkManager origin = manager("origin");
        NetworkManager destination = manager("destination");
        destination.trustPeer("origin", origin.getPublicKey());
        WireMessage.Routed routed = origin.relay().createRouted(
            "destination",
            RelayRouter.ROUTE_TTL,
            new WireMessage.ViewTime(UUID.randomUUID(), 7)
        );
        byte[] changedPayload = routed.payload().clone();
        changedPayload[changedPayload.length - 1] ^= 1;
        WireMessage.Routed payloadTampered = new WireMessage.Routed(
            routed.sourceServer(),
            routed.targetServer(),
            routed.ttl(),
            routed.innerType(),
            changedPayload,
            routed.signature()
        );

        assertFalse(destination.relay().handleRouted("trusted-relay", payloadTampered));
        assertFalse(routed.withTarget("another-destination").authenticates(destination.trust().publicKey("origin")));
    }

    @Test
    void signedAnnouncementCanBeRetargetedWithoutChangingItsOriginProof() throws IOException {
        NetworkManager origin = manager("origin");
        NetworkManager destination = manager("destination");
        destination.trustPeer("origin", origin.getPublicKey());
        WireMessage.Routed announcement = origin.relay().createRouted(
            "relay",
            RelayRouter.ROUTE_TTL,
            new WireMessage.PortalDirectory(List.of())
        );
        WireMessage.Routed retargeted = announcement.withTarget("destination");
        byte[] encoded = WireCodec.encodeFrame(retargeted);
        WireMessage.Routed decoded = assertInstanceOf(WireMessage.Routed.class, WireCodec.readFrame(
            new DataInputStream(new ByteArrayInputStream(encoded))
        ));

        assertTrue(decoded.authenticates(destination.trust().publicKey("origin")));
        assertArrayEquals(announcement.signature(), decoded.signature());
    }

    @Test
    void unknownOriginAnnounceViaTrustedIntroducerLandsInQuarantineUnderTheIntroducerName() throws IOException {
        NetworkManager origin = manager("origin");
        NetworkManager relay = manager("trusted-relay");
        NetworkManager destination = manager("destination");
        destination.trustPeer("trusted-relay", relay.getPublicKey());
        List<String> delivered = new ArrayList<>();
        destination.setMessageSink((peerName, message) -> delivered.add(peerName + ":" + message.type()));
        WireMessage.Routed routed = origin.relay().createRouted(
            "destination",
            RelayRouter.ROUTE_TTL,
            new WireMessage.PeerAnnounceMessage(origin.buildAnnounce(3L, 1_000L))
        );

        assertTrue(destination.relay().handleRouted("trusted-relay", routed));
        assertTrue(delivered.isEmpty());
        art.arcane.wormholes.network.mesh.PeerQuarantineStore.Entry entry = destination.quarantine().get("origin");
        assertEquals("trusted-relay", entry.introducer());
        assertEquals(3L, entry.announce().epoch());
        assertNull(destination.trust().key("origin"));
        assertNull(destination.getPeer("origin"));
        assertNull(destination.relay().nextHop("origin"));
    }

    @Test
    void unknownOriginAnnounceIsAcceptedRoutedAndRelayedWhenAutoAcceptIsOn() throws IOException {
        NetworkManager origin = manager("origin");
        NetworkManager relay = manager("trusted-relay");
        NetworkConfig config = new NetworkConfig();
        config.enabled = true;
        config.serverName = "destination";
        config.listenEnabled = false;
        config.mesh.autoAcceptIntroductions = true;
        NetworkManager destination = new NetworkManager(LOGGER, config, "26.2", "test", 25565, tempDir.resolve("destination"));
        managers.add(destination);
        destination.trustPeer("trusted-relay", relay.getPublicKey());
        WireMessage.Routed routed = origin.relay().createRouted(
            "destination",
            RelayRouter.ROUTE_TTL,
            new WireMessage.PeerAnnounceMessage(origin.buildAnnounce(3L, 1_000L))
        );

        assertTrue(destination.relay().handleRouted("trusted-relay", routed));
        assertArrayEquals(Handshake.decodePublicKeyText(origin.getPublicKey()), destination.trust().key("origin"));
        assertEquals(8901, destination.getPeer("origin").port);
        assertEquals("trusted-relay", destination.relay().nextHop("origin"));
        assertNull(destination.quarantine().get("origin"));
    }

    @Test
    void unknownOriginAnnounceWithoutATrustedIntroducerOrWithAForeignInnerTypeIsDropped() throws IOException {
        NetworkManager origin = manager("origin");
        NetworkManager destination = manager("destination");
        WireMessage.Routed announce = origin.relay().createRouted(
            "destination",
            RelayRouter.ROUTE_TTL,
            new WireMessage.PeerAnnounceMessage(origin.buildAnnounce(3L, 1_000L))
        );
        assertFalse(destination.relay().handleRouted("untrusted-relay", announce));
        assertNull(destination.quarantine().get("origin"));

        NetworkManager relay = manager("trusted-relay");
        destination.trustPeer("trusted-relay", relay.getPublicKey());
        WireMessage.Routed foreign = origin.relay().createRouted(
            "destination",
            RelayRouter.ROUTE_TTL,
            new WireMessage.PortalDirectory(List.of())
        );
        assertFalse(destination.relay().handleRouted("trusted-relay", foreign));
        assertNull(destination.relay().nextHop("origin"));
    }

    @Test
    void tombstoneFromATrustedIssuerForgetsThePeerAndBlocksItsStaleAnnounce() throws IOException {
        NetworkManager origin = manager("origin");
        NetworkManager issuer = manager("issuer");
        NetworkManager destination = manager("destination");
        destination.trustPeer("origin", origin.getPublicKey());
        destination.trustPeer("issuer", issuer.getPublicKey());
        NetworkConfig.PeerEntry route = new NetworkConfig.PeerEntry();
        route.name = "origin";
        route.host = "127.0.0.1";
        route.port = 8901;
        destination.savePeer(route);
        assertTrue(destination.relay().handleRouted("origin", origin.relay().createRouted("destination", RelayRouter.ROUTE_TTL,
            new WireMessage.PeerAnnounceMessage(origin.buildAnnounce(7L, 1_000L)))));
        assertEquals(7L, destination.members().epochOf("origin", 0L));

        art.arcane.wormholes.network.mesh.PeerTombstone tombstone = issuer.buildTombstone("origin", 7L,
            Handshake.decodePublicKeyText(origin.getPublicKey()), 2_000L);
        assertTrue(destination.relay().handleRouted("issuer", issuer.relay().createRouted("destination", RelayRouter.ROUTE_TTL,
            new WireMessage.PeerTombstoneMessage(tombstone))));
        assertNull(destination.trust().key("origin"));
        assertNull(destination.getPeer("origin"));
        assertNull(destination.members().get("origin"));

        NetworkManager relay = manager("trusted-relay");
        destination.trustPeer("trusted-relay", relay.getPublicKey());
        assertTrue(destination.relay().handleRouted("trusted-relay", origin.relay().createRouted("destination", RelayRouter.ROUTE_TTL,
            new WireMessage.PeerAnnounceMessage(origin.buildAnnounce(7L, 3_000L)))));
        assertNull(destination.quarantine().get("origin"));
        assertTrue(destination.relay().handleRouted("trusted-relay", origin.relay().createRouted("destination", RelayRouter.ROUTE_TTL,
            new WireMessage.PeerAnnounceMessage(origin.buildAnnounce(8L, 4_000L)))));
        assertEquals("trusted-relay", destination.quarantine().get("origin").introducer());
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
}
