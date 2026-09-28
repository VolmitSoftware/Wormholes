package art.arcane.wormholes.network.mesh;

import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.network.GameEndpoint;
import art.arcane.wormholes.network.WireCapability;
import art.arcane.wormholes.network.WireCodec;
import art.arcane.wormholes.network.WireMessage;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerAnnounceCodecTest {
    private static KeyPair keyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static PeerAnnounce unsigned(KeyPair keys) {
        return new PeerAnnounce("gamma", WireCodec.PROTOCOL_VERSION, "test", "10.0.0.7", 8903, new GameEndpoint("gamma.example", 25567),
            new GameEndpoint("10.0.0.7", 25565), keys.getPublic().getEncoded(), 4L,
            WireCapability.localSet(), 1_700_000_000_000L, new byte[0]);
    }

    private static WireMessage roundTrip(WireMessage message) throws IOException {
        byte[] frame = WireCodec.encodeFrame(message);
        return WireCodec.readFrame(new DataInputStream(new ByteArrayInputStream(frame)));
    }

    @Test
    void signedAnnounceRoundTripsThroughTheWireAndVerifies() throws Exception {
        KeyPair keys = keyPair();
        PeerAnnounce signed = unsigned(keys).signWith(keys.getPrivate());
        assertTrue(signed.verify());

        WireMessage.PeerAnnounceMessage decoded = assertInstanceOf(WireMessage.PeerAnnounceMessage.class,
            roundTrip(new WireMessage.PeerAnnounceMessage(signed)));
        PeerAnnounce announce = decoded.announce();
        assertEquals("gamma", announce.name());
        assertEquals(WireCodec.PROTOCOL_VERSION, announce.protocolVersion());
        assertEquals("test", announce.pluginVersion());
        assertEquals("10.0.0.7", announce.advertiseHost());
        assertEquals(8903, announce.wormholePort());
        assertEquals(new GameEndpoint("gamma.example", 25567), announce.gameEndpoint());
        assertEquals(new GameEndpoint("10.0.0.7", 25565), announce.privateGameEndpoint());
        assertArrayEquals(keys.getPublic().getEncoded(), announce.publicKey());
        assertEquals(4L, announce.epoch());
        assertEquals(WireCapability.localSet(), announce.capabilities());
        assertEquals(1_700_000_000_000L, announce.issuedAtMillis());
        assertArrayEquals(signed.signature(), announce.signature());
        assertTrue(announce.verify());
    }

    @Test
    void signatureBindsEveryField() throws Exception {
        KeyPair keys = keyPair();
        PeerAnnounce signed = unsigned(keys).signWith(keys.getPrivate());
        byte[] signature = signed.signature();

        assertFalse(new PeerAnnounce("delta", signed.protocolVersion(), signed.pluginVersion(), signed.advertiseHost(), signed.wormholePort(), signed.gameEndpoint(), signed.privateGameEndpoint(), signed.publicKey(), signed.epoch(), signed.capabilities(), signed.issuedAtMillis(), signature).verify());
        assertFalse(new PeerAnnounce(signed.name(), signed.protocolVersion(), signed.pluginVersion(), "10.0.0.8", signed.wormholePort(), signed.gameEndpoint(), signed.privateGameEndpoint(), signed.publicKey(), signed.epoch(), signed.capabilities(), signed.issuedAtMillis(), signature).verify());
        assertFalse(new PeerAnnounce(signed.name(), signed.protocolVersion(), signed.pluginVersion(), signed.advertiseHost(), 8904, signed.gameEndpoint(), signed.privateGameEndpoint(), signed.publicKey(), signed.epoch(), signed.capabilities(), signed.issuedAtMillis(), signature).verify());
        assertFalse(new PeerAnnounce(signed.name(), signed.protocolVersion(), signed.pluginVersion(), signed.advertiseHost(), signed.wormholePort(), new GameEndpoint("other.example", 25567), signed.privateGameEndpoint(), signed.publicKey(), signed.epoch(), signed.capabilities(), signed.issuedAtMillis(), signature).verify());
        assertFalse(new PeerAnnounce(signed.name(), signed.protocolVersion(), signed.pluginVersion(), signed.advertiseHost(), signed.wormholePort(), signed.gameEndpoint(), null, signed.publicKey(), signed.epoch(), signed.capabilities(), signed.issuedAtMillis(), signature).verify());
        assertFalse(new PeerAnnounce(signed.name(), signed.protocolVersion(), signed.pluginVersion(), signed.advertiseHost(), signed.wormholePort(), signed.gameEndpoint(), signed.privateGameEndpoint(), keyPair().getPublic().getEncoded(), signed.epoch(), signed.capabilities(), signed.issuedAtMillis(), signature).verify());
        assertFalse(new PeerAnnounce(signed.name(), signed.protocolVersion(), signed.pluginVersion(), signed.advertiseHost(), signed.wormholePort(), signed.gameEndpoint(), signed.privateGameEndpoint(), signed.publicKey(), 5L, signed.capabilities(), signed.issuedAtMillis(), signature).verify());
        assertFalse(new PeerAnnounce(signed.name(), signed.protocolVersion(), signed.pluginVersion(), signed.advertiseHost(), signed.wormholePort(), signed.gameEndpoint(), signed.privateGameEndpoint(), signed.publicKey(), signed.epoch(), signed.capabilities() | (1L << 40), signed.issuedAtMillis(), signature).verify());
        assertFalse(new PeerAnnounce(signed.name(), signed.protocolVersion(), signed.pluginVersion(), signed.advertiseHost(), signed.wormholePort(), signed.gameEndpoint(), signed.privateGameEndpoint(), signed.publicKey(), signed.epoch(), signed.capabilities(), signed.issuedAtMillis() + 1L, signature).verify());
        assertFalse(new PeerAnnounce(signed.name(), signed.protocolVersion() + 1, signed.pluginVersion(), signed.advertiseHost(), signed.wormholePort(), signed.gameEndpoint(), signed.privateGameEndpoint(), signed.publicKey(), signed.epoch(), signed.capabilities(), signed.issuedAtMillis(), signature).verify());
        assertFalse(new PeerAnnounce(signed.name(), signed.protocolVersion(), "other", signed.advertiseHost(), signed.wormholePort(), signed.gameEndpoint(), signed.privateGameEndpoint(), signed.publicKey(), signed.epoch(), signed.capabilities(), signed.issuedAtMillis(), signature).verify());
        assertFalse(unsigned(keys).verify());
    }

    @Test
    void announceBecomesARouteWithEveryEndpoint() throws Exception {
        KeyPair keys = keyPair();
        NetworkConfig.PeerEntry route = unsigned(keys).toRoute();
        assertEquals("gamma", route.name);
        assertEquals("10.0.0.7", route.host);
        assertEquals(8903, route.port);
        assertEquals("gamma.example", route.publicHost);
        assertEquals(25567, route.publicPort);
        assertEquals("10.0.0.7", route.privateHost);
        assertEquals(25565, route.privatePort);
    }

    @Test
    void tombstoneRoundTripsAndVerifiesAgainstTheIssuerKey() throws Exception {
        KeyPair issuer = keyPair();
        KeyPair removed = keyPair();
        PeerTombstone signed = new PeerTombstone("gamma", 4L, removed.getPublic().getEncoded(), 1_700_000_000_500L, new byte[0])
            .signWith(issuer.getPrivate());
        assertTrue(signed.verify(issuer.getPublic().getEncoded()));
        assertFalse(signed.verify(removed.getPublic().getEncoded()));

        WireMessage.PeerTombstoneMessage decoded = assertInstanceOf(WireMessage.PeerTombstoneMessage.class,
            roundTrip(new WireMessage.PeerTombstoneMessage(signed)));
        PeerTombstone tombstone = decoded.tombstone();
        assertEquals("gamma", tombstone.name());
        assertEquals(4L, tombstone.epoch());
        assertArrayEquals(removed.getPublic().getEncoded(), tombstone.publicKey());
        assertEquals(1_700_000_000_500L, tombstone.issuedAtMillis());
        assertTrue(tombstone.verify(issuer.getPublic().getEncoded()));
        assertFalse(new PeerTombstone(tombstone.name(), 5L, tombstone.publicKey(), tombstone.issuedAtMillis(), tombstone.signature())
            .verify(issuer.getPublic().getEncoded()));
    }

    @Test
    void relayAnnouncementSetCoversPeerAnnounceAndTombstone() {
        assertTrue(WireMessage.Routed.isRelayAnnouncement(art.arcane.wormholes.network.WireMessageType.PEER_ANNOUNCE));
        assertTrue(WireMessage.Routed.isRelayAnnouncement(art.arcane.wormholes.network.WireMessageType.PEER_TOMBSTONE));
        assertFalse(WireMessage.Routed.isRelayAnnouncement(art.arcane.wormholes.network.WireMessageType.LOAD_BEACON));
        assertNull(art.arcane.wormholes.network.WireMessageType.byId((byte) 19));
    }
}
