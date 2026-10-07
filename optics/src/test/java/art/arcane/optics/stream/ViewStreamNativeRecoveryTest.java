package art.arcane.optics.stream;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ViewStreamNativeRecoveryTest {
    @Test
    void automaticSessionFailuresRebuildNativeStreamsWithoutReturningOwnership() throws ViewStreamProtocolException {
        for (ViewStreamMessage.ResetReason reason : List.of(ViewStreamMessage.ResetReason.PROTOCOL, ViewStreamMessage.ResetReason.OVERLOAD,
            ViewStreamMessage.ResetReason.TELEPORT, ViewStreamMessage.ResetReason.DIMENSION, ViewStreamMessage.ResetReason.RESPAWN)) {
            SessionHarness harness = nativeSession();
            SessionPortal portal = harness.access.portals.values().iterator().next();
            harness.tick();
            int oldKey = harness.client.portals.keySet().iterator().next();
            harness.session.reset(reason);
            assertTrue(harness.registry.owns(harness.playerId, portal.id));
            assertTrue(harness.session.nativeRendererSelected());
            harness.tick();
            assertEquals(ViewStreamSessionState.CLIENT_VIEW, harness.session.state());
            assertTrue(harness.registry.owns(harness.playerId, portal.id));
            assertFalse(harness.client.portals.containsKey(oldKey));
            assertEquals(1, harness.client.portals.size());
            assertTrue(harness.sent(ViewStreamMessageType.MESH_BEGIN) >= 2);
            assertEquals(0, harness.sent(ViewStreamMessageType.PLATE_BEGIN));
            assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
        }
    }

    @Test
    void invalidGeometryRemainsNativeAndRetriesAfterCooldown() throws ViewStreamProtocolException {
        SessionHarness harness = nativeSession();
        SessionPortal portal = harness.access.portals.values().iterator().next();
        portal.geometryAvailable = false;
        harness.tick();
        assertTrue(harness.session.owns(portal.id));
        assertTrue(harness.client.portals.isEmpty());
        portal.geometryAvailable = true;
        for (int tick = 0; tick < ViewStreamSession.NATIVE_RETRY_TICKS; tick++) {
            harness.tick();
            assertTrue(harness.session.owns(portal.id));
        }
        assertEquals(1, harness.client.portals.size());
        assertEquals(0, harness.sent(ViewStreamMessageType.PLATE_BEGIN));
    }

    @Test
    void clientResyncHelloRecoversNativeFramesButExplicitDisableReleasesSelection() throws ViewStreamProtocolException {
        SessionHarness harness = nativeSession();
        harness.tick();
        SessionPortal portal = harness.access.portals.values().iterator().next();
        int oldKey = harness.client.portals.keySet().iterator().next();
        assertEquals(ViewStreamInbound.HANDLED, harness.c2s(harness.client.hello(SessionHarness.DATA_VERSION,
            SessionHarness.NATIVE_CAPS, "fabric", 0L)));
        harness.tick();
        assertFalse(harness.client.portals.containsKey(oldKey));
        assertTrue(harness.session.owns(portal.id));
        harness.registry.runtimeEnabled(false);
        harness.pump();
        assertEquals(ViewStreamSessionState.VANILLA, harness.session.state());
        assertFalse(harness.session.nativeRendererSelected());
        assertFalse(harness.session.owns(portal.id));
    }

    @Test
    void protocolViolationResetKeepsNativeOwnershipAndRecoversOnTheOwnerTick() throws ViewStreamProtocolException {
        SessionHarness harness = nativeSession();
        harness.tick();
        SessionPortal portal = harness.access.portals.values().iterator().next();
        ViewStreamInbound result = ViewStreamInbound.IGNORED;
        for (int violation = 0; violation < ViewStreamLimits.C2S_VIOLATION_LIMIT; violation++) {
            result = harness.c2s(new byte[] {(byte) 99});
        }
        assertEquals(ViewStreamInbound.RESET, result);
        assertTrue(harness.session.nativeRendererSelected());
        assertTrue(harness.session.owns(portal.id));
        harness.tick();
        assertEquals(1, harness.client.portals.size());
        assertEquals(ViewStreamSessionState.CLIENT_VIEW, harness.session.state());
        assertEquals(0, harness.sent(ViewStreamMessageType.PLATE_BEGIN));
    }

    @Test
    void queuedResetDoesNotDiscardMeshStateRebuiltBeforeTheLaneRuns() throws ViewStreamProtocolException {
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        SessionHarness harness = new SessionHarness(SessionHarness.options(false, 0), tasks::add, 0L);
        harness.access.meshDistance = 64;
        harness.access.add(new SessionPortal("native", 0));
        harness.handshake(SessionHarness.NATIVE_CAPS);
        drain(tasks, harness);
        harness.tick();
        drain(tasks, harness);
        int before = harness.sent(ViewStreamMessageType.MESH_BEGIN);
        harness.session.end(ViewStreamMessage.ResetReason.PROTOCOL);
        harness.tick();
        drain(tasks, harness);
        assertTrue(harness.sent(ViewStreamMessageType.MESH_BEGIN) > before);
        assertEquals(1, harness.client.portals.size());
    }

    private static void drain(ArrayDeque<Runnable> tasks, SessionHarness harness) throws ViewStreamProtocolException {
        while (!tasks.isEmpty()) {
            tasks.remove().run();
        }
        harness.pump();
    }

    private static SessionHarness nativeSession() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(false, 0));
        harness.access.meshDistance = 64;
        harness.access.add(new SessionPortal("native", 0));
        harness.handshake(SessionHarness.NATIVE_CAPS);
        return harness;
    }
}
