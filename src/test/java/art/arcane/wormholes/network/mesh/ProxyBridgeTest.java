package art.arcane.wormholes.network.mesh;

import art.arcane.wormholes.network.GameEndpoint;
import art.arcane.wormholes.network.ServerCode;
import art.arcane.wormholes.network.WireCapability;
import art.arcane.wormholes.proxy.protocol.EnrollmentCodec;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyBridgeTest {
    private static final byte[] SECRET = "proxy-secret".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER_SECRET = "another-secret".getBytes(StandardCharsets.UTF_8);

    private static ServerCode code(String name) throws Exception {
        String key = Base64.getUrlEncoder().withoutPadding().encodeToString(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded());
        return new ServerCode(name, "10.0.0.5", List.of(), 8901, new GameEndpoint("10.0.0.5", 25565), null, key);
    }

    private static ProxyBridge bridge(ServerCode local, ProxyBridge.Importer importer) {
        return bridge(local, importer, new AtomicLong(1_000L));
    }

    private static ProxyBridge bridge(ServerCode local, ProxyBridge.Importer importer, AtomicLong clock) {
        return new ProxyBridge(EnrollmentCodec.CHANNEL, SECRET, () -> local, () -> List.of("Hub"), WireCapability::localSet, importer, clock::get);
    }

    private static UUID enrollNonce(ProxyBridge bridge) throws IOException {
        return assertInstanceOf(EnrollmentCodec.Enroll.class, EnrollmentCodec.decode(bridge.enrollFrame(), SECRET)).nonce();
    }

    @Test
    void rosterEntriesAreImportedThroughTheSeamExceptOurOwnCode() throws Exception {
        ServerCode local = code("survival");
        ServerCode creative = code("creative");
        ServerCode lobby = code("lobby");
        List<ServerCode> imported = new ArrayList<>();
        ProxyBridge bridge = bridge(local, imported::add);
        UUID nonce = enrollNonce(bridge);

        bridge.onPluginMessageReceived(EnrollmentCodec.CHANNEL, (Player) null,
            EnrollmentCodec.encode(new EnrollmentCodec.Roster(nonce, List.of(local.encode(), creative.encode(), "garbage", lobby.encode())), SECRET));

        assertEquals(2, imported.size());
        assertEquals("creative", imported.get(0).serverName());
        assertEquals("lobby", imported.get(1).serverName());
        assertEquals(2, bridge.importedCount());
    }

    @Test
    void aClientForgedRosterNeverReachesTheImporter() throws Exception {
        ServerCode local = code("survival");
        ServerCode attacker = code("attacker");
        List<ServerCode> imported = new ArrayList<>();
        ProxyBridge bridge = bridge(local, imported::add);
        UUID solicited = enrollNonce(bridge);
        EnrollmentCodec.Roster forged = new EnrollmentCodec.Roster(UUID.randomUUID(), List.of(attacker.encode()));

        bridge.onPluginMessageReceived(EnrollmentCodec.CHANNEL, (Player) null, EnrollmentCodec.encode(forged, OTHER_SECRET));
        bridge.onPluginMessageReceived(EnrollmentCodec.CHANNEL, (Player) null, EnrollmentCodec.encode(forged, SECRET));
        bridge.onPluginMessageReceived(EnrollmentCodec.CHANNEL, (Player) null, new byte[] {1, 2, 3});

        assertTrue(imported.isEmpty(), "a frame that is unsigned, wrongly signed or unsolicited must not import a peer");
        assertEquals(0, bridge.importedCount());
        assertEquals(3, bridge.refusedCount());

        bridge.onPluginMessageReceived(EnrollmentCodec.CHANNEL, (Player) null,
            EnrollmentCodec.encode(new EnrollmentCodec.Roster(solicited, List.of(attacker.encode())), SECRET));
        assertEquals(1, imported.size(), "the proxy's signed answer to our own enroll still lands");
    }

    @Test
    void aRosterNonceIsSingleUseAndExpires() throws Exception {
        ServerCode local = code("survival");
        ServerCode creative = code("creative");
        List<ServerCode> imported = new ArrayList<>();
        AtomicLong clock = new AtomicLong(1_000L);
        ProxyBridge bridge = bridge(local, imported::add, clock);

        UUID replayed = enrollNonce(bridge);
        byte[] roster = EnrollmentCodec.encode(new EnrollmentCodec.Roster(replayed, List.of(creative.encode())), SECRET);
        bridge.onPluginMessageReceived(EnrollmentCodec.CHANNEL, (Player) null, roster);
        bridge.onPluginMessageReceived(EnrollmentCodec.CHANNEL, (Player) null, roster);
        assertEquals(1, imported.size(), "a captured roster must not replay");

        UUID stale = enrollNonce(bridge);
        clock.addAndGet(ProxyBridge.NONCE_TTL_MILLIS + 1L);
        bridge.onPluginMessageReceived(EnrollmentCodec.CHANNEL, (Player) null,
            EnrollmentCodec.encode(new EnrollmentCodec.Roster(stale, List.of(creative.encode())), SECRET));
        assertEquals(1, imported.size(), "a nonce older than the window no longer admits a roster");
    }

    @Test
    void enrollAndHandoffFramesCarryTheLocalCodePortalsAndCapabilities() throws Exception {
        ServerCode local = code("survival");
        ProxyBridge bridge = new ProxyBridge(SECRET, () -> local, () -> List.of("Hub", "Mine"), WireCapability::localSet, code -> { });

        EnrollmentCodec.Enroll enroll = assertInstanceOf(EnrollmentCodec.Enroll.class, EnrollmentCodec.decode(bridge.enrollFrame(), SECRET));
        assertEquals("survival", enroll.serverName());
        assertEquals(local.encode(), enroll.serverCode());
        assertEquals(WireCapability.localSet(), enroll.capabilities());
        assertEquals(List.of("Hub", "Mine"), enroll.portals());

        UUID transferId = UUID.randomUUID();
        EnrollmentCodec.Handoff handoff = assertInstanceOf(EnrollmentCodec.Handoff.class,
            EnrollmentCodec.decode(bridge.handoffFrame(transferId, "creative"), SECRET));
        assertEquals(transferId, handoff.transferId());
        assertEquals("creative", handoff.targetServer());

        bridge.onPluginMessageReceived(EnrollmentCodec.CHANNEL, (Player) null,
            EnrollmentCodec.encode(new EnrollmentCodec.Receipt(transferId, true, ""), SECRET));
        assertEquals(1, bridge.receiptCount());
        bridge.onPluginMessageReceived("other:channel", (Player) null, new byte[] {1, 2, 3});
        bridge.onPluginMessageReceived(EnrollmentCodec.CHANNEL, (Player) null, new byte[] {1, 2, 3});
        assertEquals(1, bridge.receiptCount());
        assertTrue(bridge.enrollFrame().length > 0);
    }

    @Test
    void aReceiptForAHandoffWeNeverSentIsDropped() throws Exception {
        ServerCode local = code("survival");
        ProxyBridge bridge = bridge(local, code -> { });
        UUID transferId = UUID.randomUUID();

        bridge.onPluginMessageReceived(EnrollmentCodec.CHANNEL, (Player) null,
            EnrollmentCodec.encode(new EnrollmentCodec.Receipt(transferId, true, ""), SECRET));
        assertEquals(0, bridge.receiptCount());

        bridge.handoffFrame(transferId, "creative");
        bridge.onPluginMessageReceived(EnrollmentCodec.CHANNEL, (Player) null,
            EnrollmentCodec.encode(new EnrollmentCodec.Receipt(transferId, true, ""), SECRET));
        bridge.onPluginMessageReceived(EnrollmentCodec.CHANNEL, (Player) null,
            EnrollmentCodec.encode(new EnrollmentCodec.Receipt(transferId, true, ""), SECRET));
        assertEquals(1, bridge.receiptCount(), "a receipt is consumed by the handoff it answers");
    }

    @Test
    void aBridgeCannotBeBuiltWithoutASecret() throws Exception {
        ServerCode local = code("survival");
        assertThrows(IllegalArgumentException.class,
            () -> new ProxyBridge(new byte[0], () -> local, List::of, WireCapability::localSet, code -> { }));
    }

    @Test
    void frameRoundTripMatchesTheProxyProtocolModuleByteForByte() throws IOException {
        UUID transferId = UUID.randomUUID();
        byte[] frame = EnrollmentCodec.encode(new EnrollmentCodec.Handoff(transferId, "creative"), SECRET);
        assertEquals(new EnrollmentCodec.Handoff(transferId, "creative"), EnrollmentCodec.decode(frame, SECRET));
    }
}
