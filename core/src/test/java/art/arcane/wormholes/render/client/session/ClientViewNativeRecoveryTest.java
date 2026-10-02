package art.arcane.wormholes.render.client.session;

import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewMessageType;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.network.client.ClientViewProtocolException;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClientViewNativeRecoveryTest {
    @Test
    void automaticSessionFailuresRebuildNativeStreamsWithoutReturningOwnership() throws ClientViewProtocolException {
        for (ClientViewMessage.ResetReason reason : List.of(ClientViewMessage.ResetReason.PROTOCOL, ClientViewMessage.ResetReason.OVERLOAD,
            ClientViewMessage.ResetReason.TELEPORT, ClientViewMessage.ResetReason.DIMENSION, ClientViewMessage.ResetReason.RESPAWN)) {
            SessionHarness harness = nativeSession();
            SessionPortal portal = harness.access.portals.values().iterator().next();
            harness.tick();
            int oldKey = harness.client.portals.keySet().iterator().next();
            harness.session.reset(reason);
            assertTrue(harness.registry.owns(harness.playerId, portal.id));
            assertTrue(harness.session.nativeRendererSelected());
            harness.tick();
            assertEquals(ClientViewSessionState.CLIENT_VIEW, harness.session.state());
            assertTrue(harness.registry.owns(harness.playerId, portal.id));
            assertFalse(harness.client.portals.containsKey(oldKey));
            assertEquals(1, harness.client.portals.size());
            assertTrue(harness.sent(ClientViewMessageType.MESH_BEGIN) >= 2);
            assertEquals(0, harness.sent(ClientViewMessageType.PLATE_BEGIN));
            assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
        }
    }

    @Test
    void invalidGeometryRemainsNativeAndRetriesAfterCooldown() throws ClientViewProtocolException {
        SessionHarness harness = nativeSession();
        SessionPortal portal = harness.access.portals.values().iterator().next();
        portal.geometryAvailable = false;
        harness.tick();
        assertTrue(harness.session.owns(portal.id));
        assertTrue(harness.client.portals.isEmpty());
        portal.geometryAvailable = true;
        for (int tick = 0; tick < ClientViewServerSession.NATIVE_RETRY_TICKS; tick++) {
            harness.tick();
            assertTrue(harness.session.owns(portal.id));
        }
        assertEquals(1, harness.client.portals.size());
        assertEquals(0, harness.sent(ClientViewMessageType.PLATE_BEGIN));
    }

    @Test
    void clientResyncHelloRecoversNativeFramesButExplicitDisableReleasesSelection() throws ClientViewProtocolException {
        SessionHarness harness = nativeSession();
        harness.tick();
        SessionPortal portal = harness.access.portals.values().iterator().next();
        int oldKey = harness.client.portals.keySet().iterator().next();
        assertEquals(ClientViewInbound.HANDLED, harness.c2s(harness.client.hello(SessionHarness.DATA_VERSION,
            SessionHarness.NATIVE_CAPS, "fabric", 0L)));
        harness.tick();
        assertFalse(harness.client.portals.containsKey(oldKey));
        assertTrue(harness.session.owns(portal.id));
        harness.registry.runtimeEnabled(false);
        harness.pump();
        assertEquals(ClientViewSessionState.VANILLA, harness.session.state());
        assertFalse(harness.session.nativeRendererSelected());
        assertFalse(harness.session.owns(portal.id));
    }

    @Test
    void protocolViolationResetKeepsNativeOwnershipAndRecoversOnTheOwnerTick() throws ClientViewProtocolException {
        SessionHarness harness = nativeSession();
        harness.tick();
        SessionPortal portal = harness.access.portals.values().iterator().next();
        ClientViewInbound result = ClientViewInbound.IGNORED;
        for (int violation = 0; violation < ClientViewProtocol.C2S_VIOLATION_LIMIT; violation++) {
            result = harness.c2s(new byte[] {(byte) 99});
        }
        assertEquals(ClientViewInbound.RESET, result);
        assertTrue(harness.session.nativeRendererSelected());
        assertTrue(harness.session.owns(portal.id));
        harness.tick();
        assertEquals(1, harness.client.portals.size());
        assertEquals(ClientViewSessionState.CLIENT_VIEW, harness.session.state());
        assertEquals(0, harness.sent(ClientViewMessageType.PLATE_BEGIN));
    }

    @Test
    void queuedResetDoesNotDiscardMeshStateRebuiltBeforeTheLaneRuns() throws ClientViewProtocolException {
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        SessionHarness harness = new SessionHarness(SessionHarness.options(false, 0), tasks::add, 0L);
        harness.access.meshDistance = 64;
        harness.access.add(new SessionPortal("native", 0));
        harness.handshake(SessionHarness.NATIVE_CAPS);
        drain(tasks, harness);
        harness.tick();
        drain(tasks, harness);
        int before = harness.sent(ClientViewMessageType.MESH_BEGIN);
        harness.session.end(ClientViewMessage.ResetReason.PROTOCOL);
        harness.tick();
        drain(tasks, harness);
        assertTrue(harness.sent(ClientViewMessageType.MESH_BEGIN) > before);
        assertEquals(1, harness.client.portals.size());
    }

    private static void drain(ArrayDeque<Runnable> tasks, SessionHarness harness) throws ClientViewProtocolException {
        while (!tasks.isEmpty()) {
            tasks.remove().run();
        }
        harness.pump();
    }

    private static SessionHarness nativeSession() throws ClientViewProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(false, 0));
        harness.access.meshDistance = 64;
        harness.access.add(new SessionPortal("native", 0));
        harness.handshake(SessionHarness.NATIVE_CAPS);
        return harness;
    }
}
