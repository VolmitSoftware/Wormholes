package art.arcane.optics.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;

final class ViewStreamEffectSlotTest {
    private static final String SPARKS = "sparks";

    @Test
    void oneShotsTravelOnTheWorldKeyBatchedPerLaneRun() throws ViewStreamProtocolException {
        List<Runnable> queued = new ArrayList<Runnable>();
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8), queued::add, 0L);
        assertFalse(harness.session.burst(TestEffects.burst("portal")), "no session, no receiver");
        harness.handshake(SessionHarness.CLIENT_CAPS);
        drain(queued, harness);
        assertTrue(harness.session.effectsReceiver());
        assertTrue(harness.registry.effectsReceiver(harness.playerId));

        assertTrue(harness.session.burst(TestEffects.burst("portal")));
        assertTrue(harness.registry.burst(harness.playerId, TestEffects.burst("animation")));
        drain(queued, harness);

        assertEquals(1, harness.sent(TestEffects.ID));
        TestEffects.Effect effect = TestEffects.payload(harness.last(TestEffects.ID));
        assertEquals(TestEffects.WORLD_KEY, effect.key());
        assertEquals(List.of("portal", "animation"), effect.names());
        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
    }

    @Test
    void rejectedOneShotsDoNotKeepOccupyingTheBacklog() throws ViewStreamProtocolException {
        List<Runnable> queued = new ArrayList<Runnable>();
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8), queued::add, 0L);
        harness.handshake(SessionHarness.CLIENT_CAPS);
        drain(queued, harness);
        ViewStreamMessage.Extension burst = TestEffects.burst("portal");
        int accepted = saturate(harness.session, burst);
        assertTrue(accepted > 0);
        for (int i = 0; i < 64; i++) {
            assertFalse(harness.session.burst(burst), "a full backlog rejects further bursts");
        }
        drain(queued, harness);

        assertEquals(accepted, saturate(harness.session, burst), "once the lane drains, the whole backlog is free again");
        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
    }

    @Test
    void sessionsWithoutTheEffectCapabilityNeverTakeOneShots() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        harness.handshake(SessionHarness.CLIENT_CAPS & ~TestEffects.CAPABILITY);
        assertEquals(ViewStreamSessionState.CLIENT_VIEW, harness.session.state());
        assertFalse(harness.session.effectsReceiver());
        assertFalse(harness.session.burst(TestEffects.burst("smoke")));
        harness.pump();
        assertEquals(0, harness.sent(TestEffects.ID));
    }

    @Test
    void effectSlotsCarryFxWithoutOwningThePortalAndPromoteInPlace() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        Effects effects = new Effects();
        harness.scene = effects;
        Set<UUID> entityRequests = new HashSet<UUID>();
        harness.entities = (observer, target, tick) -> {
            entityRequests.add(target.portalId());
            return null;
        };
        SessionWorld world = new SessionWorld(3L);
        SessionPortal rtp = new SessionPortal("rtp", 0);
        harness.access.portals.put(rtp.id, rtp);
        harness.access.effect(rtp);
        effects.names.put(rtp.id, List.of(SPARKS));
        harness.handshake(SessionHarness.CLIENT_CAPS);

        harness.tick();

        assertEquals(1, harness.client.portals.size());
        int effectKey = harness.client.portals.keySet().iterator().next();
        assertTrue(harness.client.plates.isEmpty(), "an effect slot never streams a plate");
        assertFalse(harness.session.owns(rtp.id), "an effect slot leaves the portal to the vanilla projector");
        assertFalse(harness.events.contains("release " + rtp.id));
        TestEffects.Effect sparks = TestEffects.payload(harness.last(TestEffects.ID));
        assertEquals(effectKey, sparks.key());
        assertEquals(List.of(SPARKS), sparks.names());
        assertTrue(entityRequests.isEmpty(), "effect slots carry no entity frames");
        assertEquals(0, harness.session.stats().attended());

        rtp.plate = rtp.build(world);
        harness.access.interest.add(rtp.id);
        harness.tick();

        assertTrue(harness.session.owns(rtp.id));
        assertTrue(harness.events.contains("release " + rtp.id));
        assertEquals(Set.of(effectKey), harness.client.portals.keySet(), "promotion keeps the portal key");
        assertEquals(1, harness.client.plates.size());
        assertTrue(entityRequests.contains(rtp.id));
        assertEquals(1, harness.session.stats().attended());

        harness.access.interest.clear();
        for (int i = 0; i <= SessionHarness.defaults().interestGraceTicks() + 1; i++) {
            harness.tick();
        }

        assertFalse(harness.session.owns(rtp.id));
        assertEquals(1, harness.client.portals.size());
        int demotedKey = harness.client.portals.keySet().iterator().next();
        assertTrue(demotedKey != effectKey, "losing plate interest drops the plate slot and reopens an effect slot");
        assertTrue(harness.client.plates.isEmpty());

        harness.access.effects.clear();
        for (int i = 0; i <= SessionHarness.defaults().interestGraceTicks() + 1; i++) {
            harness.tick();
        }

        assertTrue(harness.client.portals.isEmpty());
        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
    }

    @Test
    void effectSlotsWithoutGeometryAreSkipped() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        SessionPortal closed = new SessionPortal("closed", 0);
        closed.geometryAvailable = false;
        harness.access.effect(closed);
        harness.handshake(SessionHarness.CLIENT_CAPS);
        harness.tick();
        harness.tick();
        assertTrue(harness.client.portals.isEmpty());
        assertEquals(0, harness.sent(ViewStreamMessageType.PORTAL));
    }

    private static int saturate(ViewStreamSession<String, String> session, ViewStreamMessage.Extension burst) {
        int accepted = 0;
        while (session.burst(burst)) {
            accepted++;
            assertTrue(accepted <= 1_000_000, "the burst backlog must be bounded");
        }
        return accepted;
    }

    private static void drain(List<Runnable> queued, SessionHarness harness) throws ViewStreamProtocolException {
        while (!queued.isEmpty()) {
            queued.remove(0).run();
            harness.pump();
        }
    }

    private static final class Effects implements ViewStreamScene<String> {
        private final Map<UUID, List<String>> names = new ConcurrentHashMap<UUID, List<String>>();

        @Override
        public ViewStreamMessage.Extension effects(String observer, UUID portal, int key, long tick, boolean full) {
            List<String> current = names.getOrDefault(portal, List.of());
            return current.isEmpty() ? null : TestEffects.scene(key, current);
        }

        @Override
        public ViewStreamMessage.Atmosphere atmosphere(String observer, UUID portal, int key, long tick, boolean full) {
            return null;
        }
    }
}
