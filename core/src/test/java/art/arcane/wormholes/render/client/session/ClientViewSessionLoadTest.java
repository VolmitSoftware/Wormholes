package art.arcane.wormholes.render.client.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewMessageType;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.network.client.ClientViewProtocolException;

final class ClientViewSessionLoadTest {
    private static final int PORTALS = 9;
    private static final int TICKS = 200;
    private static final int TICKS_PER_SECOND = 20;
    private static final int C2S_ROUNDS_PER_TICK = 2;
    private static final int C2S_MESSAGES_PER_ROUND = 2;

    @Test
    void ninePortalsAtTwentyHertzWithAFullPlateFillStayFarBelowTheInboundLimit() throws ClientViewProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, ClientViewProtocol.DEFAULT_ACK_WINDOW_FRAMES));
        harness.c2sSpacingNanos = 0L;
        harness.client.autoAck = true;
        harness.entities = (observer, portal, key, tick, full, hideObserver) ->
            new ClientViewMessage.EntityFrame(key, (int) tick, List.of(), List.of(), true);
        harness.fx = new SceneEveryTick();
        SessionWorld world = new SessionWorld(9L);
        for (int i = 0; i < PORTALS; i++) {
            SessionPortal portal = harness.access.add(new SessionPortal("load-" + i, i * 8));
            portal.plate = portal.build(world);
        }
        harness.handshake(SessionHarness.CLIENT_CAPS);
        int[] perTick = new int[TICKS];
        int peakPerTick = 0;
        int peakPerSecond = 0;
        for (int i = 0; i < TICKS; i++) {
            int before = harness.c2sCount;
            harness.tick();
            perTick[i] = harness.c2sCount - before;
            peakPerTick = Math.max(peakPerTick, perTick[i]);
            int second = 0;
            for (int back = Math.max(0, i - TICKS_PER_SECOND + 1); back <= i; back++) {
                second += perTick[back];
            }
            peakPerSecond = Math.max(peakPerSecond, second);
            assertEquals(ClientViewSessionState.CLIENT_VIEW, harness.session.state(), "session reset on tick " + i);
        }
        ClientViewSessionStats stats = harness.session.stats();
        assertEquals(PORTALS, harness.client.plates.size(), "every plate filled");
        assertEquals(0L, stats.c2sDropped(), "no inbound message was dropped");
        assertEquals(0, stats.outstandingGroups(), "every paced group was acknowledged");
        assertTrue(harness.sent(ClientViewMessageType.ENTITY_FRAME) >= PORTALS * (TICKS - 10), "entity frames flowed at 20 Hz");
        assertTrue(harness.sent(ClientViewMessageType.FX) >= PORTALS * (TICKS - 10), "fx flowed at 20 Hz");
        assertTrue(harness.sent(ClientViewMessageType.ATMOSPHERE) >= PORTALS * (TICKS - 10), "atmosphere flowed at 20 Hz");
        assertEquals(0, harness.client.closed(ClientViewMessageType.ENTITY_FRAME), "entity frames never demand an ack");
        assertEquals(0, harness.client.closed(ClientViewMessageType.FX), "fx never demands an ack");
        assertEquals(0, harness.client.closed(ClientViewMessageType.ATMOSPHERE), "atmosphere never demands an ack");
        assertTrue(peakPerTick <= C2S_ROUNDS_PER_TICK * C2S_MESSAGES_PER_ROUND, peakPerTick + " inbound messages in one tick");
        assertTrue(peakPerSecond <= ClientViewProtocol.MAX_C2S_MESSAGES_PER_SECOND / 2, peakPerSecond + " inbound messages in one second");
        assertTrue(stats.c2sAdmitted() <= TICKS / 4, stats.c2sAdmitted() + " inbound messages over " + TICKS + " ticks of scene traffic");
        assertTrue(harness.warnings.isEmpty(), "lane warnings: " + harness.warnings);
    }

    private static final class SceneEveryTick implements ClientViewFxSource<String> {
        @Override
        public ClientViewMessage.Fx fx(String observer, UUID portal, int portalKey, long tick, boolean full) {
            return new ClientViewMessage.Fx(portalKey, List.of(new ClientViewMessage.FxEmitter(ClientViewMessage.FxKind.RIM_DUST, "dust",
                0.0D, 64.0D, 0.0D, 0.0F, 0.0F, 1, 0)));
        }

        @Override
        public ClientViewMessage.Atmosphere atmosphere(String observer, UUID portal, int portalKey, long tick, boolean full) {
            return new ClientViewMessage.Atmosphere(portalKey, tick, 0.0F, 0.0F, ClientViewMessage.Atmosphere.FLAG_TIME);
        }
    }
}
