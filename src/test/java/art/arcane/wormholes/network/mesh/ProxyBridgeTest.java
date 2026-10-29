package art.arcane.wormholes.network.mesh;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.network.GameEndpoint;
import art.arcane.wormholes.network.ServerCode;
import art.arcane.wormholes.network.WireCapability;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.proxy.protocol.EnrollmentCodec;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Isolated("Changes the active proxy bridge and plugin instance")
class ProxyBridgeTest {
    private static final byte[] SECRET = "proxy-secret".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER_SECRET = "another-secret".getBytes(StandardCharsets.UTF_8);

    private Wormholes previousPlugin;
    private ProxyBridge previousBridge;

    @BeforeEach
    void rememberRuntime() {
        previousPlugin = Wormholes.instance;
        previousBridge = ProxyBridge.active();
    }

    @AfterEach
    void restoreRuntime() {
        Wormholes.instance = previousPlugin;
        ProxyBridge.setActive(previousBridge);
    }

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

    private static Wormholes activate(ProxyBridge bridge) {
        Wormholes plugin = mock(Wormholes.class);
        Wormholes.instance = plugin;
        ProxyBridge.setActive(bridge);
        return plugin;
    }

    private static ScheduledEnrollment captureEnrollment(MockedStatic<WormholesPlatform> platform,
                                                         Wormholes plugin, Player player, long delay) {
        ArgumentCaptor<Runnable> action = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Runnable> retired = ArgumentCaptor.forClass(Runnable.class);
        platform.when(() -> WormholesPlatform.scheduleEntity(eq(plugin), eq(player), action.capture(), retired.capture(), eq(delay)))
            .thenReturn(true);
        return new ScheduledEnrollment(action, retired);
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
    void reenrollUsesOneAcceptedEntitySchedulerAndImportsTheReturnedRoster() throws Exception {
        ServerCode local = code("survival");
        ServerCode creative = code("creative");
        List<ServerCode> imported = new ArrayList<>();
        ProxyBridge bridge = bridge(local, imported::add);
        Wormholes plugin = activate(bridge);
        Player rejected = mock(Player.class);
        Player first = mock(Player.class);
        Player second = mock(Player.class);
        when(first.isOnline()).thenReturn(true);

        try (MockedStatic<WormholesPlatform> platform = mockStatic(WormholesPlatform.class)) {
            platform.when(() -> WormholesPlatform.scheduleEntity(eq(plugin), eq(rejected), any(), any(), eq(1L)))
                .thenAnswer(invocation -> {
                    invocation.getArgument(3, Runnable.class).run();
                    return false;
                });
            ScheduledEnrollment scheduled = captureEnrollment(platform, plugin, first, 1L);
            bridge.reenroll(List.of());
            platform.verifyNoInteractions();
            bridge.reenroll(List.of(rejected, first, second));
            verifyNoInteractions(rejected, first, second);
            platform.verify(() -> WormholesPlatform.scheduleEntity(eq(plugin), eq(second), any(), any(), eq(1L)), never());

            scheduled.run();

            ArgumentCaptor<byte[]> frame = ArgumentCaptor.forClass(byte[].class);
            verify(first).sendPluginMessage(eq(plugin), eq(EnrollmentCodec.CHANNEL), frame.capture());
            verifyNoInteractions(rejected, second);
            UUID nonce = assertInstanceOf(EnrollmentCodec.Enroll.class, EnrollmentCodec.decode(frame.getValue(), SECRET)).nonce();
            bridge.onPluginMessageReceived(EnrollmentCodec.CHANNEL, first,
                EnrollmentCodec.encode(new EnrollmentCodec.Roster(nonce, List.of(creative.encode())), SECRET));
            assertEquals(1, imported.size());
            assertEquals("creative", imported.get(0).serverName());
        }
    }

    @Test
    void aRetiredCarrierDoesNotReadPlayerStateOrSend() throws Exception {
        ProxyBridge bridge = bridge(code("survival"), code -> { });
        Wormholes plugin = activate(bridge);
        Player player = mock(Player.class);
        try (MockedStatic<WormholesPlatform> platform = mockStatic(WormholesPlatform.class)) {
            ScheduledEnrollment scheduled = captureEnrollment(platform, plugin, player, 1L);
            bridge.reenroll(List.of(player));

            scheduled.retire();

            verifyNoInteractions(player);
        }
    }

    @Test
    void aCarrierDisconnectedBeforeExecutionDoesNotSend() throws Exception {
        ProxyBridge bridge = bridge(code("survival"), code -> { });
        Wormholes plugin = activate(bridge);
        Player player = mock(Player.class);
        try (MockedStatic<WormholesPlatform> platform = mockStatic(WormholesPlatform.class)) {
            ScheduledEnrollment scheduled = captureEnrollment(platform, plugin, player, 1L);
            bridge.reenroll(List.of(player));
            verifyNoInteractions(player);

            scheduled.run();

            verify(player).isOnline();
            verify(player, never()).sendPluginMessage(any(), anyString(), any());
        }
    }

    @Test
    void aDisabledBridgeDoesNotSendQueuedEnrollmentOrScheduleMore() throws Exception {
        ProxyBridge bridge = bridge(code("survival"), code -> { });
        Wormholes plugin = activate(bridge);
        Player player = mock(Player.class);
        try (MockedStatic<WormholesPlatform> platform = mockStatic(WormholesPlatform.class)) {
            ScheduledEnrollment scheduled = captureEnrollment(platform, plugin, player, 1L);
            bridge.reenroll(List.of(player));
            ProxyBridge.setActive(null);

            scheduled.run();
            platform.clearInvocations();
            bridge.reenroll(List.of(player));

            platform.verifyNoInteractions();
            verifyNoInteractions(player);
        }
    }

    @Test
    void aReplacedBridgeDoesNotSendQueuedEnrollmentOrScheduleMore() throws Exception {
        ProxyBridge bridge = bridge(code("survival"), code -> { });
        Wormholes plugin = activate(bridge);
        Player player = mock(Player.class);
        try (MockedStatic<WormholesPlatform> platform = mockStatic(WormholesPlatform.class)) {
            ScheduledEnrollment scheduled = captureEnrollment(platform, plugin, player, 1L);
            bridge.reenroll(List.of(player));
            ProxyBridge.setActive(bridge(code("replacement"), code -> { }));

            scheduled.run();
            platform.clearInvocations();
            bridge.reenroll(List.of(player));

            platform.verifyNoInteractions();
            verifyNoInteractions(player);
        }
    }

    @Test
    void aDelayedJoinCannotEnrollThroughAReplacedBridge() throws Exception {
        ProxyBridge bridge = bridge(code("survival"), code -> { });
        Wormholes plugin = activate(bridge);
        Player player = mock(Player.class);
        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(player);
        try (MockedStatic<WormholesPlatform> platform = mockStatic(WormholesPlatform.class)) {
            ScheduledEnrollment scheduled = captureEnrollment(platform, plugin, player, 40L);
            bridge.onJoin(event);
            ProxyBridge.setActive(bridge(code("replacement"), code -> { }));

            scheduled.run();

            verifyNoInteractions(player);
        }
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

    private record ScheduledEnrollment(ArgumentCaptor<Runnable> action, ArgumentCaptor<Runnable> retired) {
        private void run() {
            action.getValue().run();
        }

        private void retire() {
            retired.getValue().run();
        }
    }
}
