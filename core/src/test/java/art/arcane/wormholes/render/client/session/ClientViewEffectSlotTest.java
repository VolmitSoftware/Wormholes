package art.arcane.wormholes.render.client.session;

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

import art.arcane.wormholes.config.VisualQualityProfile;
import art.arcane.optics.math.Vec3;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.optics.stream.ViewStreamMessageType;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.stream.ClientViewProtocolException;
import art.arcane.wormholes.portal.effects.PortalAnimation;
import art.arcane.optics.stream.ClientViewSessionState;

final class ClientViewEffectSlotTest {
    private static final ClientViewMessage.FxEmitter SPARKS = new ClientViewMessage.FxEmitter(ClientViewMessage.FxKind.SURFACE,
        ClientViewEmitters.SPARK_PARTICLE, 10.0D, 66.0D, 20.0D, 0.29F, 0.0F, 1, 3);

    @Test
    void oneShotsTravelOnTheWorldKeyBatchedPerLaneRun() throws ClientViewProtocolException {
        List<Runnable> queued = new ArrayList<Runnable>();
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8), queued::add, 0L);
        ClientViewMessage.FxEmitter burst = ClientViewEmitters.burst("minecraft:portal", 1.0D, 2.0D, 3.0D, 4, 0.45D, 0.65D, 0.18D);
        assertFalse(harness.session.oneShot(burst), "no session, no receiver");
        harness.handshake(SessionHarness.CLIENT_CAPS);
        drain(queued, harness);
        assertTrue(harness.session.effectsReceiver());
        assertTrue(harness.registry.effectsReceiver(harness.playerId));
        ClientViewMessage.FxEmitter animation = ClientViewEmitters.animation(PortalAnimation.Mode.OPEN, new Vec3(5.5D, 66.0D, 20.5D),
            new Vec3(3.0D, 4.0D, 0.0D), VisualQualityProfile.BALANCED);

        assertTrue(harness.session.oneShot(burst));
        assertTrue(harness.registry.oneShot(harness.playerId, animation));
        drain(queued, harness);

        assertEquals(1, harness.sent(ViewStreamMessageType.FX));
        ClientViewMessage.Fx fx = (ClientViewMessage.Fx) harness.last(ViewStreamMessageType.FX);
        assertEquals(ViewStreamLimits.WORLD_FX_KEY, fx.portalKey());
        assertEquals(List.of(burst, animation), fx.emitters());
        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
    }

    @Test
    void rejectedOneShotsDoNotKeepOccupyingTheBacklog() throws ClientViewProtocolException {
        List<Runnable> queued = new ArrayList<Runnable>();
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8), queued::add, 0L);
        harness.handshake(SessionHarness.CLIENT_CAPS);
        drain(queued, harness);
        ClientViewMessage.FxEmitter burst = ClientViewEmitters.burst("minecraft:portal", 1.0D, 2.0D, 3.0D, 4, 0.45D, 0.65D, 0.18D);
        int accepted = saturate(harness.session, burst);
        assertTrue(accepted > 0);
        for (int i = 0; i < 64; i++) {
            assertFalse(harness.session.oneShot(burst), "a full backlog rejects further bursts");
        }
        drain(queued, harness);

        assertEquals(accepted, saturate(harness.session, burst), "once the lane drains, the whole backlog is free again");
        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
    }

    @Test
    void sessionsWithoutFxEmittersNeverTakeOneShots() throws ClientViewProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        harness.handshake(SessionHarness.CLIENT_CAPS & ~ViewStreamCapability.FX_EMITTERS.mask());
        assertEquals(ClientViewSessionState.CLIENT_VIEW, harness.session.state());
        assertFalse(harness.session.effectsReceiver());
        assertFalse(harness.session.oneShot(ClientViewEmitters.burst("minecraft:smoke", 0.0D, 0.0D, 0.0D, 6, 0.0D, 0.0D, 0.01D)));
        harness.pump();
        assertEquals(0, harness.sent(ViewStreamMessageType.FX));
    }

    @Test
    void effectSlotsCarryFxWithoutOwningThePortalAndPromoteInPlace() throws ClientViewProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, 8));
        Effects effects = new Effects();
        harness.fx = new ClientViewSceneFx<String>(effects);
        Set<UUID> entityRequests = new HashSet<UUID>();
        harness.entities = (observer, portal, key, tick, full, hideObserver) -> {
            entityRequests.add(portal);
            return null;
        };
        SessionWorld world = new SessionWorld(3L);
        SessionPortal rtp = new SessionPortal("rtp", 0);
        harness.access.portals.put(rtp.id, rtp);
        harness.access.effect(rtp);
        effects.emitters.put(rtp.id, List.of(SPARKS));
        harness.handshake(SessionHarness.CLIENT_CAPS);

        harness.tick();

        assertEquals(1, harness.client.portals.size());
        int effectKey = harness.client.portals.keySet().iterator().next();
        assertTrue(harness.client.plates.isEmpty(), "an effect slot never streams a plate");
        assertFalse(harness.session.owns(rtp.id), "an effect slot leaves the portal to the vanilla projector");
        assertFalse(harness.events.contains("release " + rtp.id));
        ClientViewMessage.Fx sparks = (ClientViewMessage.Fx) harness.last(ViewStreamMessageType.FX);
        assertEquals(effectKey, sparks.portalKey());
        assertEquals(List.of(SPARKS), sparks.emitters());
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
        for (int i = 0; i <= ClientViewOptions.defaults().interestGraceTicks() + 1; i++) {
            harness.tick();
        }

        assertFalse(harness.session.owns(rtp.id));
        assertEquals(1, harness.client.portals.size());
        int demotedKey = harness.client.portals.keySet().iterator().next();
        assertTrue(demotedKey != effectKey, "losing plate interest drops the plate slot and reopens an effect slot");
        assertTrue(harness.client.plates.isEmpty());

        harness.access.effects.clear();
        for (int i = 0; i <= ClientViewOptions.defaults().interestGraceTicks() + 1; i++) {
            harness.tick();
        }

        assertTrue(harness.client.portals.isEmpty());
        assertTrue(harness.warnings.isEmpty(), harness.warnings.toString());
    }

    @Test
    void effectSlotsWithoutGeometryAreSkipped() throws ClientViewProtocolException {
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

    private static int saturate(ClientViewServerSession<String, String> session, ClientViewMessage.FxEmitter burst) {
        int accepted = 0;
        while (session.oneShot(burst)) {
            accepted++;
            assertTrue(accepted <= 1_000_000, "the burst backlog must be bounded");
        }
        return accepted;
    }

    private static void drain(List<Runnable> queued, SessionHarness harness) throws ClientViewProtocolException {
        while (!queued.isEmpty()) {
            queued.remove(0).run();
            harness.pump();
        }
    }

    private static final class Effects implements ClientViewSceneFx.Effects<String> {
        private final Map<UUID, List<ClientViewMessage.FxEmitter>> emitters = new ConcurrentHashMap<UUID, List<ClientViewMessage.FxEmitter>>();

        @Override
        public List<ClientViewMessage.FxEmitter> emitters(String observer, UUID portal, long tick) {
            return emitters.getOrDefault(portal, List.of());
        }

        @Override
        public ClientViewSceneFx.Sample atmosphere(String observer, UUID portal, long tick) {
            return null;
        }
    }
}
