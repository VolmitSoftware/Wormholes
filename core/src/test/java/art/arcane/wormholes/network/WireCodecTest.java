package art.arcane.wormholes.network;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WireCodecTest {
    private static WireMessage roundTrip(WireMessage message) throws IOException {
        byte[] frame = WireCodec.encodeFrame(message);
        return WireCodec.readFrame(new DataInputStream(new ByteArrayInputStream(frame)));
    }

    @Test
    void helloRoundTripPreservesAllFields() throws Exception {
        byte[] nonce = Handshake.newNonce();
        byte[] publicKey = publicKey();
        WireMessage.Hello hello = new WireMessage.Hello(WireCodec.PROTOCOL_VERSION, "26.2", "1.0.0", "alpha", "10.0.0.5", 8901, new GameEndpoint("10.0.0.5", 25565), null, nonce, publicKey, false, CompressionDictionary.ZERO_HASH, 0, WireCapability.localSet());
        WireMessage.Hello decoded = assertInstanceOf(WireMessage.Hello.class, roundTrip(hello));
        assertEquals(WireCodec.PROTOCOL_VERSION, decoded.protocolVersion());
        assertEquals("26.2", decoded.mcVersion());
        assertEquals("1.0.0", decoded.pluginVersion());
        assertEquals("alpha", decoded.serverName());
        assertArrayEquals(nonce, decoded.nonce());
        assertArrayEquals(publicKey, decoded.publicKey());
        assertEquals(false, decoded.compressionSupported());
        assertArrayEquals(CompressionDictionary.ZERO_HASH, decoded.currentDictHash());
        assertEquals(0, decoded.currentDictVersion());
    }

    @Test
    void challengeRoundTripPreservesAllFields() throws Exception {
        byte[] nonce = Handshake.newNonce();
        byte[] publicKey = publicKey();
        byte[] signature = new byte[] {1, 2, 3, 4};
        WireMessage.Challenge challenge = new WireMessage.Challenge("beta", "10.0.0.2", 8901, new GameEndpoint("10.0.0.2", 25565), null, nonce, publicKey, signature, true, CompressionDictionary.ZERO_HASH, 0, WireCapability.localSet());
        WireMessage.Challenge decoded = assertInstanceOf(WireMessage.Challenge.class, roundTrip(challenge));
        assertEquals("beta", decoded.serverName());
        assertEquals("10.0.0.2", decoded.advertiseHost());
        assertEquals(8901, decoded.wormholePort());
        assertEquals(25565, decoded.gameEndpoint().port());
        assertArrayEquals(nonce, decoded.nonce());
        assertArrayEquals(publicKey, decoded.publicKey());
        assertArrayEquals(signature, decoded.signature());
        assertEquals(true, decoded.compressionSupported());
        assertArrayEquals(CompressionDictionary.ZERO_HASH, decoded.currentDictHash());
        assertEquals(0, decoded.currentDictVersion());
    }

    @Test
    void authReadyPingPongRoundTrip() throws IOException {
        byte[] signature = new byte[] {5, 6, 7, 8};
        WireMessage.Auth auth = assertInstanceOf(WireMessage.Auth.class, roundTrip(new WireMessage.Auth(signature)));
        assertArrayEquals(signature, auth.signature());

        assertInstanceOf(WireMessage.Ready.class, roundTrip(new WireMessage.Ready()));

        WireMessage.Ping ping = assertInstanceOf(WireMessage.Ping.class, roundTrip(new WireMessage.Ping(123456789L)));
        assertEquals(123456789L, ping.sentAtMillis());

        WireMessage.Pong pong = assertInstanceOf(WireMessage.Pong.class, roundTrip(new WireMessage.Pong(987654321L)));
        assertEquals(987654321L, pong.echoMillis());
    }

    @Test
    void largePayloadIsCompressedAndRoundTrips() throws Exception {
        String bigVersion = "x".repeat(50_000);
        WireMessage.Hello hello = new WireMessage.Hello(WireCodec.PROTOCOL_VERSION, "26.2", bigVersion, "alpha", "10.0.0.5", 8901, new GameEndpoint("10.0.0.5", 25565), null, Handshake.newNonce(), publicKey(), true, CompressionDictionary.ZERO_HASH, 0, WireCapability.localSet());
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            byte[] frame = WireCodec.encodeFrame(hello, compression, 0);
            assertTrue(frame.length < 10_000, "highly repetitive payload should compress well, frame was " + frame.length);
            WireMessage.Hello decoded = assertInstanceOf(WireMessage.Hello.class, WireCodec.readFrame(new DataInputStream(new ByteArrayInputStream(frame)), compression));
            assertEquals(bigVersion, decoded.pluginVersion());
        } finally {
            compression.close();
        }
    }

    @Test
    void oversizedFrameLengthIsRejected() {
        byte[] bogus = new byte[]{0x7F, 0x7F, 0x7F, 0x7F, 0, 0};
        assertThrows(IOException.class, () -> WireCodec.readFrame(new DataInputStream(new ByteArrayInputStream(bogus))));
    }

    @Test
    void unknownTypeIdIsRejected() {
        byte[] frame = new byte[]{0, 0, 0, 2, (byte) 200, 0};
        assertThrows(IOException.class, () -> WireCodec.readFrame(new DataInputStream(new ByteArrayInputStream(frame))));
    }

    @Test
    void truncatedFrameIsRejected() throws IOException {
        byte[] frame = WireCodec.encodeFrame(new WireMessage.Ping(42L));
        byte[] truncated = new byte[frame.length - 4];
        System.arraycopy(frame, 0, truncated, 0, truncated.length);
        assertThrows(IOException.class, () -> WireCodec.readFrame(new DataInputStream(new ByteArrayInputStream(truncated))));
    }

    @Test
    void byteArrayHelpersEnforceCaps() throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(buffer);
        byte[] payload = new byte[256];
        WireCodec.writeByteArray(out, payload, 256);
        assertThrows(IOException.class, () -> WireCodec.writeByteArray(out, payload, 255));

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(buffer.toByteArray()));
        assertThrows(IOException.class, () -> WireCodec.readByteArray(in, 255));
    }

    @Test
    void readFrameFeedsSamplerWithTypeAndExactPayload() throws IOException {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            java.util.UUID portalId = java.util.UUID.randomUUID();
            WireMessage.PortalUpsert upsert = new WireMessage.PortalUpsert(new PortalInfo(portalId, "Gateway test", "world", "GATEWAY", true, "N", "E", "U",
                10.5D, 64.0D, 20.5D,
                9.5D, 63.5D, 19.5D,
                11.5D, 66.5D, 21.5D));
            byte[] frame = WireCodec.encodeFrame(upsert, compression, 0);
            java.util.List<WireMessageType> sampledTypes = new java.util.ArrayList<>();
            java.util.List<byte[]> sampledPayloads = new java.util.ArrayList<>();
            WireMessage decoded = WireCodec.readFrame(new DataInputStream(new ByteArrayInputStream(frame)), compression, (type, payload) -> {
                sampledTypes.add(type);
                sampledPayloads.add(payload);
            });
            WireMessage.PortalUpsert echoed = assertInstanceOf(WireMessage.PortalUpsert.class, decoded);
            assertEquals(portalId, echoed.portal().id());
            assertEquals(1, sampledTypes.size());
            assertEquals(WireMessageType.PORTAL_UPSERT, sampledTypes.get(0));
            assertArrayEquals(WireCodec.encodePayload(upsert), sampledPayloads.get(0));
        } finally {
            compression.close();
        }
    }

    @Test
    void readFrameWithoutSamplerDoesNotThrow() throws IOException {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            byte[] frame = WireCodec.encodeFrame(new WireMessage.Ping(7L), compression, 0);
            WireMessage.Ping decoded = assertInstanceOf(WireMessage.Ping.class,
                WireCodec.readFrame(new DataInputStream(new ByteArrayInputStream(frame)), compression, null));
            assertEquals(7L, decoded.sentAtMillis());
        } finally {
            compression.close();
        }
    }

    @Test
    void handoffRequestRoundTripsWithAndWithoutTheConvoyGroupId() throws IOException {
        UUID transferId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID portalId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        WireTraversive traversive = new WireTraversive("N", "E", "U", 1.0D, 64.0D, 2.0D, 1.5D, 64.5D, 2.0D, 0.0D, 0.0D, -0.4D, 0.0D, 0.0D, -1.0D, true);
        WireMessage.HandoffRequest plain = new WireMessage.HandoffRequest(transferId, playerId, "steve", portalId, true, true, false, traversive);
        WireMessage.HandoffRequest grouped = new WireMessage.HandoffRequest(transferId, playerId, "steve", portalId, true, true, false, traversive, groupId);

        assertNull(plain.groupId());
        WireMessage.HandoffRequest decodedPlain = assertInstanceOf(WireMessage.HandoffRequest.class, roundTrip(plain));
        assertNull(decodedPlain.groupId());
        assertEquals(portalId, decodedPlain.destPortalId());
        assertFalse(decodedPlain.accessBypass());
        WireMessage.HandoffRequest decodedGrouped = assertInstanceOf(WireMessage.HandoffRequest.class, roundTrip(grouped));
        assertEquals(groupId, decodedGrouped.groupId());
        assertEquals(playerId, decodedGrouped.playerId());
        assertTrue(WireCodec.encodePayload(grouped).length > WireCodec.encodePayload(plain).length,
            "the group id is a trailing field that only appears when set");
        assertArrayEquals(WireCodec.encodePayload(plain), WireCodec.encodePayload(new WireMessage.HandoffRequest(transferId, playerId, "steve", portalId, true, true, false, traversive, null)),
            "a null group id writes the seam layout byte for byte");
        WireMessage.HandoffRequest privileged = new WireMessage.HandoffRequest(
            transferId, playerId, "steve", portalId, true, true, true, traversive, groupId);
        assertEquals(privileged, roundTrip(privileged));
    }

    @Test
    void convoyMessagesCarryTheReservedIdsAndTheConvoyCapabilityBit() throws IOException {
        assertEquals(38, WireMessageType.CONVOY_TRANSFER.id());
        assertEquals(39, WireMessageType.CONVOY_ACK.id());
        assertEquals(16, WireCapability.CONVOY.bit());
        assertTrue(WireCapability.CONVOY.in(WireCapability.localSet()));
        WireMessage.ConvoyAck ack = assertInstanceOf(WireMessage.ConvoyAck.class, roundTrip(new WireMessage.ConvoyAck(UUID.randomUUID(), true, "admitted")));
        assertTrue(ack.accepted());
    }

    @Test
    void meshLaneMessagesRoundTrip() throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        art.arcane.wormholes.network.mesh.PeerAnnounce announce = new art.arcane.wormholes.network.mesh.PeerAnnounce(
            "gamma", WireCodec.PROTOCOL_VERSION, "test", "10.0.0.7", 8903, new GameEndpoint("gamma.example", 25567), null,
            keys.getPublic().getEncoded(), 2L, WireCapability.localSet(), 42L, new byte[0]).signWith(keys.getPrivate());
        WireMessage.PeerAnnounceMessage announceMessage = assertInstanceOf(WireMessage.PeerAnnounceMessage.class,
            roundTrip(new WireMessage.PeerAnnounceMessage(announce)));
        assertEquals(WireMessageType.PEER_ANNOUNCE, announceMessage.type());
        assertTrue(announceMessage.announce().verify());

        art.arcane.wormholes.network.mesh.PeerTombstone tombstone = new art.arcane.wormholes.network.mesh.PeerTombstone(
            "gamma", 2L, keys.getPublic().getEncoded(), 43L, new byte[0]).signWith(keys.getPrivate());
        WireMessage.PeerTombstoneMessage tombstoneMessage = assertInstanceOf(WireMessage.PeerTombstoneMessage.class,
            roundTrip(new WireMessage.PeerTombstoneMessage(tombstone)));
        assertEquals(WireMessageType.PEER_TOMBSTONE, tombstoneMessage.type());
        assertTrue(tombstoneMessage.tombstone().verify(keys.getPublic().getEncoded()));

        art.arcane.wormholes.network.mesh.LoadBeacon beacon = new art.arcane.wormholes.network.mesh.LoadBeacon(
            12, 40, 3, 19.75D, 31.5D, true, WireCapability.localSet(), 44L);
        WireMessage.LoadBeaconMessage beaconMessage = assertInstanceOf(WireMessage.LoadBeaconMessage.class,
            roundTrip(new WireMessage.LoadBeaconMessage(beacon)));
        assertEquals(WireMessageType.LOAD_BEACON, beaconMessage.type());
        assertEquals(beacon, beaconMessage.beacon());

        WireMessage.PortalQuery query = assertInstanceOf(WireMessage.PortalQuery.class,
            roundTrip(new WireMessage.PortalQuery("hub*", 25)));
        assertEquals(WireMessageType.PORTAL_QUERY, query.type());
        assertEquals("hub*", query.filter());
        assertEquals(25, query.limit());

        java.util.UUID portalId = java.util.UUID.randomUUID();
        PortalInfo info = new PortalInfo(portalId, "Hub", "world", "GATEWAY", true, "N", "E", "U",
            1.5D, 64.0D, 2.5D, 0.5D, 63.5D, 1.5D, 2.5D, 66.5D, 3.5D);
        WireMessage.PortalQueryResult result = assertInstanceOf(WireMessage.PortalQueryResult.class,
            roundTrip(new WireMessage.PortalQueryResult(java.util.List.of(info), true)));
        assertEquals(WireMessageType.PORTAL_QUERY_RESULT, result.type());
        assertEquals(1, result.portals().size());
        assertEquals(portalId, result.portals().get(0).id());
        assertTrue(result.truncated());

        java.util.UUID transferId = java.util.UUID.randomUUID();
        WireMessage.HandoffQueueStatus status = assertInstanceOf(WireMessage.HandoffQueueStatus.class,
            roundTrip(new WireMessage.HandoffQueueStatus(transferId, 3, 4_500L)));
        assertEquals(WireMessageType.HANDOFF_QUEUE_STATUS, status.type());
        assertEquals(transferId, status.transferId());
        assertEquals(3, status.position());
        assertEquals(4_500L, status.etaMillis());
    }

    @Test
    void viewSoundRoundTripsEveryField() throws IOException {
        java.util.UUID portalId = java.util.UUID.randomUUID();
        WireMessage.ViewSound sound = new WireMessage.ViewSound(portalId, "minecraft:block.stone.break",
            10.5D, -64.25D, 300.125D, 0.75F, 1.2F, (byte) 1);
        WireMessage.ViewSound decoded = assertInstanceOf(WireMessage.ViewSound.class, roundTrip(sound));
        assertEquals(portalId, decoded.portalId());
        assertEquals("minecraft:block.stone.break", decoded.soundKey());
        assertEquals(10.5D, decoded.x());
        assertEquals(-64.25D, decoded.y());
        assertEquals(300.125D, decoded.z());
        assertEquals(0.75F, decoded.volume());
        assertEquals(1.2F, decoded.pitch());
        assertEquals((byte) 1, decoded.soundClass());
        assertEquals(WireMessageType.VIEW_SOUND, decoded.type());
        assertEquals(27, WireMessageType.VIEW_SOUND.id());
        assertEquals(25, WireCapability.VIEW_ACOUSTICS.bit());
    }

    @Test
    void viewWeatherRoundTripsAndIsGatedOnTheAtmosphereBit() throws IOException {
        java.util.UUID portalId = java.util.UUID.randomUUID();
        WireMessage.ViewWeather weather = assertInstanceOf(WireMessage.ViewWeather.class,
            roundTrip(new WireMessage.ViewWeather(portalId, true, false)));
        assertEquals(portalId, weather.portalId());
        assertTrue(weather.storm());
        assertEquals(false, weather.thunder());
        assertEquals(WireMessageType.VIEW_WEATHER, weather.type());
        assertEquals(28, WireMessageType.VIEW_WEATHER.id());
        assertEquals(26, WireCapability.VIEW_ATMOSPHERE.bit());
    }

    private static byte[] publicKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        KeyPair keyPair = generator.generateKeyPair();
        return keyPair.getPublic().getEncoded();
    }
}
