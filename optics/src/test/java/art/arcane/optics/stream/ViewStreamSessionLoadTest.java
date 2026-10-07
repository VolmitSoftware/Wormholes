package art.arcane.optics.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

final class ViewStreamSessionLoadTest {
    private static final int PORTALS = 9;
    private static final int TICKS = 200;
    private static final int TICKS_PER_SECOND = 20;
    private static final int C2S_ROUNDS_PER_TICK = 2;
    private static final int C2S_MESSAGES_PER_ROUND = 2;

    @Test
    void ninePortalsAtTwentyHertzWithAFullPlateFillStayFarBelowTheInboundLimit() throws ViewStreamProtocolException {
        SessionHarness harness = new SessionHarness(SessionHarness.options(true, ViewStreamLimits.DEFAULT_ACK_WINDOW_FRAMES));
        harness.c2sSpacingNanos = 0L;
        harness.client.autoAck = true;
        harness.entities = (observer, target, tick) ->
            new ViewStreamMessage.EntityFrame(target.portalKey(), (int) tick, List.of(), List.of(), true);
        harness.scene = new SceneEveryTick();
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
            assertEquals(ViewStreamSessionState.CLIENT_VIEW, harness.session.state(), "session reset on tick " + i);
        }
        ViewStreamSessionStats stats = harness.session.stats();
        assertEquals(PORTALS, harness.client.plates.size(), "every plate filled");
        assertEquals(0L, stats.c2sDropped(), "no inbound message was dropped");
        assertEquals(0, stats.outstandingGroups(), "every paced group was acknowledged");
        assertTrue(harness.sent(ViewStreamMessageType.ENTITY_FRAME) >= PORTALS * (TICKS - 10), "entity frames flowed at 20 Hz");
        assertTrue(harness.sent(TestEffects.ID) >= PORTALS * (TICKS - 10), "effects flowed at 20 Hz");
        assertTrue(harness.sent(ViewStreamMessageType.ATMOSPHERE) >= PORTALS * (TICKS - 10), "atmosphere flowed at 20 Hz");
        assertEquals(0, harness.client.closed(ViewStreamMessageType.ENTITY_FRAME), "entity frames never demand an ack");
        assertEquals(0, harness.client.closed(TestEffects.ID), "effects never demand an ack");
        assertEquals(0, harness.client.closed(ViewStreamMessageType.ATMOSPHERE), "atmosphere never demands an ack");
        assertTrue(peakPerTick <= C2S_ROUNDS_PER_TICK * C2S_MESSAGES_PER_ROUND, peakPerTick + " inbound messages in one tick");
        assertTrue(peakPerSecond <= ViewStreamLimits.MAX_C2S_MESSAGES_PER_SECOND / 2, peakPerSecond + " inbound messages in one second");
        assertTrue(stats.c2sAdmitted() <= TICKS / 4, stats.c2sAdmitted() + " inbound messages over " + TICKS + " ticks of scene traffic");
        assertTrue(harness.warnings.isEmpty(), "lane warnings: " + harness.warnings);
    }

    private static final class SceneEveryTick implements ViewStreamScene<String> {
        @Override
        public ViewStreamMessage.Extension effects(String observer, UUID portal, int portalKey, long tick, boolean full) {
            return TestEffects.scene(portalKey, List.of("dust"));
        }

        @Override
        public ViewStreamMessage.Atmosphere atmosphere(String observer, UUID portal, int portalKey, long tick, boolean full) {
            return new ViewStreamMessage.Atmosphere(portalKey, tick, 0.0F, 0.0F, ViewStreamMessage.Atmosphere.FLAG_TIME);
        }
    }
}
