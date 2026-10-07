package art.arcane.optics.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.Test;

import com.sun.management.ThreadMXBean;

final class ViewStreamSessionPacingTest {
    private static final int STEADY_TICKS = 20_000;
    private static final long TICK_ALLOCATION_BUDGET_BYTES = 16L;

    private static SessionHarness withPortals(ViewStreamOptions options, int count) {
        return withPortals(options, count, Runnable::run);
    }

    private static SessionHarness withPortals(ViewStreamOptions options, int count, Executor lanes) {
        SessionHarness harness = new SessionHarness(options, lanes, 0L);
        SessionWorld world = new SessionWorld(30L);
        for (int i = 0; i < count; i++) {
            SessionPortal portal = harness.access.add(new SessionPortal("paced-" + i, i * 8));
            portal.plate = portal.build(world);
        }
        return harness;
    }

    @Test
    void theAckWindowPausesAtItsLimitAndResumesOnAck() throws ViewStreamProtocolException {
        SessionHarness harness = withPortals(SessionHarness.options(false, 2), 5);
        harness.handshake(SessionHarness.CLIENT_CAPS);
        harness.tick();
        assertEquals(2, harness.client.plates.size());
        assertEquals(2, harness.session.stats().outstandingGroups());
        harness.tick();
        harness.tick();
        assertEquals(2, harness.client.plates.size(), "an unacknowledged window never sends more");

        harness.ack();
        assertEquals(4, harness.client.plates.size());
        harness.ack();
        assertEquals(5, harness.client.plates.size());
        harness.ack();
        assertEquals(0, harness.session.stats().outstandingGroups());
        assertEquals(5L, harness.session.stats().ackedGroups());
        assertTrue(harness.session.stats().appliedCells() > 0L);
    }

    @Test
    void aZeroWindowNeverPauses() throws ViewStreamProtocolException {
        SessionHarness harness = withPortals(SessionHarness.options(true, 0), 5);
        harness.handshake(SessionHarness.CLIENT_CAPS);
        harness.tick();
        assertEquals(5, harness.client.plates.size());
    }

    @Test
    void openBrickCacheGroupsCountAgainstTheWindow() throws ViewStreamProtocolException {
        SessionHarness harness = withPortals(SessionHarness.options(true, 2), 4);
        harness.handshake(SessionHarness.CLIENT_CAPS);
        harness.client.autoMiss = false;
        harness.tick();
        assertEquals(2, harness.sent(ViewStreamMessageType.PLATE_BEGIN));
        assertEquals(2, harness.client.open.size());
    }

    @Test
    void aCumulativeAckNeverReleasesAManifestWhoseBricksAreUnsent() throws ViewStreamProtocolException {
        SessionHarness harness = withPortals(SessionHarness.options(true, 2), 3);
        harness.handshake(SessionHarness.CLIENT_CAPS);
        harness.client.autoMiss = false;
        harness.tick();
        assertEquals(2, harness.sent(ViewStreamMessageType.PLATE_BEGIN));
        assertEquals(2, harness.session.stats().outstandingGroups());

        assertEquals(ViewStreamInbound.HANDLED, harness.c2s(harness.client.ack(harness.client.lastSeq)));
        harness.pump();
        harness.tick();
        assertEquals(2, harness.sent(ViewStreamMessageType.PLATE_BEGIN), "an ack past the manifests must not open a third stream");
        assertEquals(2, harness.session.stats().outstandingGroups());
        assertEquals(0L, harness.session.stats().ackedGroups());

        ViewStreamMessage.PlateBegin first = (ViewStreamMessage.PlateBegin) harness.client.received.stream()
            .filter(message -> message.id() == ViewStreamMessageType.PLATE_BEGIN.id()).findFirst().orElseThrow();
        assertEquals(ViewStreamInbound.HANDLED, harness.c2s(miss(first.portalKey(), first.plateRevision(), first.brickCount(), 0)));
        harness.pump();
        assertEquals(1, harness.sent(ViewStreamMessageType.PLATE_END));
        assertEquals(2, harness.session.stats().outstandingGroups(), "the finished stream waits for its ack");
        harness.ack();
        assertEquals(1L, harness.session.stats().ackedGroups());
        harness.tick();
        assertEquals(3, harness.sent(ViewStreamMessageType.PLATE_BEGIN), "the freed slot admits the third stream");
        assertEquals(2, harness.session.stats().outstandingGroups());
    }

    @Test
    void sceneFramesCarryNoLastFlagAndControlFramesNeedNoAck() throws ViewStreamProtocolException {
        SessionHarness harness = withPortals(SessionHarness.options(false, 8), 1);
        harness.entities = (observer, target, tick) ->
            new ViewStreamMessage.EntityFrame(target.portalKey(), (int) tick, List.of(), List.of(), true);
        harness.handshake(SessionHarness.CLIENT_CAPS);
        harness.tick();
        harness.tick();
        assertTrue(harness.sent(ViewStreamMessageType.ENTITY_FRAME) >= 2);
        assertEquals(0, harness.client.closed(ViewStreamMessageType.ENTITY_FRAME));
        assertEquals(1, harness.client.closed(ViewStreamMessageType.PLATE_END));
        harness.access.interest.clear();
        for (int i = 0; i < ViewStreamOptions.DEFAULT_INTEREST_GRACE_TICKS + 2; i++) {
            harness.tick();
        }
        assertEquals(1, harness.sent(ViewStreamMessageType.PORTAL_DROP));
        assertEquals(0, harness.client.closed(ViewStreamMessageType.PORTAL_DROP));
    }

    @Test
    void aDetachedManifestReleasesItsWindowSlot() throws ViewStreamProtocolException {
        SessionHarness harness = withPortals(SessionHarness.options(true, 1), 2);
        harness.handshake(SessionHarness.CLIENT_CAPS);
        harness.client.autoMiss = false;
        harness.tick();
        assertEquals(1, harness.sent(ViewStreamMessageType.PLATE_BEGIN));
        assertEquals(1, harness.session.stats().outstandingGroups());
        harness.access.interest.remove(0);
        for (int i = 0; i < ViewStreamOptions.DEFAULT_INTEREST_GRACE_TICKS + 2; i++) {
            harness.tick();
        }
        assertEquals(1, harness.sent(ViewStreamMessageType.PORTAL_DROP));
        assertEquals(2, harness.sent(ViewStreamMessageType.PLATE_BEGIN), "the abandoned manifest slot admits the next stream");
        assertEquals(1, harness.session.stats().outstandingGroups());
    }

    @Test
    void oneBrickMissMessageAnswersEveryManifestOfATick() throws ViewStreamProtocolException {
        SessionHarness harness = withPortals(SessionHarness.options(true, 8), 4);
        harness.handshake(SessionHarness.CLIENT_CAPS);
        int before = harness.c2sCount;
        harness.tick();
        assertEquals(4, harness.client.plates.size());
        assertEquals(1, harness.c2sCount - before, "four manifests answered by one BRICK_MISS message");
        assertEquals(4, harness.sent(ViewStreamMessageType.PLATE_END));
    }

    @Test
    void brickMissIsHonouredOnlyForAdvertisedBricks() throws ViewStreamProtocolException {
        SessionHarness harness = withPortals(SessionHarness.options(true, 8), 1);
        harness.handshake(SessionHarness.CLIENT_CAPS);
        harness.client.autoMiss = false;
        harness.tick();
        ViewStreamMessage.PlateBegin begin = (ViewStreamMessage.PlateBegin) harness.last(ViewStreamMessageType.PLATE_BEGIN);
        int key = begin.portalKey();
        int revision = begin.plateRevision();

        assertEquals(ViewStreamInbound.HANDLED, harness.c2s(miss(key, revision + 1, begin.brickCount(), 0)));
        assertEquals(ViewStreamInbound.HANDLED, harness.c2s(miss(key + 40, revision, begin.brickCount(), 0)));
        harness.pump();
        assertEquals(0, harness.sent(ViewStreamMessageType.PLATE_BRICKS));
        assertEquals(2L, harness.session.stats().staleBrickMisses());

        assertEquals(ViewStreamInbound.HANDLED, harness.c2s(miss(key, revision, begin.brickCount(), 128)));
        harness.pump();
        ViewStreamMessage.PlateBricks bricks = (ViewStreamMessage.PlateBricks) harness.last(ViewStreamMessageType.PLATE_BRICKS);
        assertEquals(begin.brickCount(), bricks.bricks().size(), "bits past the advertised brick count are ignored");
        assertEquals(1, harness.sent(ViewStreamMessageType.PLATE_END));
        assertTrue(harness.client.open.isEmpty());

        assertEquals(ViewStreamInbound.HANDLED, harness.c2s(miss(key, revision, begin.brickCount(), 0)));
        harness.pump();
        assertEquals(1, harness.sent(ViewStreamMessageType.PLATE_BRICKS), "a completed stream is never answered twice");
        assertEquals(3L, harness.session.stats().staleBrickMisses());
    }

    @Test
    void anUnansweredManifestFallsBackToEveryBrick() throws ViewStreamProtocolException {
        SessionHarness harness = withPortals(SessionHarness.options(true, 8), 1);
        harness.handshake(SessionHarness.CLIENT_CAPS);
        harness.client.autoMiss = false;
        harness.tick();
        ViewStreamMessage.PlateBegin begin = (ViewStreamMessage.PlateBegin) harness.last(ViewStreamMessageType.PLATE_BEGIN);
        harness.clock.addAndGet(ViewStreamSession.BRICK_MISS_TIMEOUT_NANOS);
        harness.tick();
        ViewStreamMessage.PlateBricks bricks = (ViewStreamMessage.PlateBricks) harness.last(ViewStreamMessageType.PLATE_BRICKS);
        assertEquals(begin.brickCount(), bricks.bricks().size());
        assertEquals(1, harness.client.plates.size());
    }

    @Test
    void aRejectedLaneIsRescheduledOnTheNextTick() throws ViewStreamProtocolException {
        int[] rejections = {1};
        Executor flaky = task -> {
            if (rejections[0] > 0) {
                rejections[0]--;
                throw new RejectedExecutionException("queue full");
            }
            task.run();
        };
        SessionHarness harness = withPortals(SessionHarness.options(true, 8), 1, flaky);
        harness.handshake(SessionHarness.CLIENT_CAPS);
        harness.tick();
        assertEquals(1, harness.client.plates.size());
    }

    @Test
    void steadyStateTicksStayWithinTheAllocationBudget() throws ViewStreamProtocolException {
        SessionHarness harness = withPortals(SessionHarness.options(true, 8), 6);
        harness.handshake(SessionHarness.CLIENT_CAPS);
        for (int i = 0; i < 40; i++) {
            harness.tick();
        }
        assertEquals(6, harness.client.plates.size());
        ViewStreamSession<String, String> session = harness.session;
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertTrue(threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled());
        long controlStart = threads.getCurrentThreadAllocatedBytes();
        byte[] control = new byte[64 * 1024];
        assertTrue(threads.getCurrentThreadAllocatedBytes() - controlStart >= control.length, "the allocation counter must move");
        long tick = harness.serverTick;
        for (int i = 0; i < STEADY_TICKS; i++) {
            session.tick(++tick);
        }
        long before = threads.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < STEADY_TICKS; i++) {
            session.tick(++tick);
        }
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        assertTrue(harness.frames.isEmpty());
        assertTrue(allocated / STEADY_TICKS <= TICK_ALLOCATION_BUDGET_BYTES,
            allocated + " bytes over " + STEADY_TICKS + " ticks exceeds " + TICK_ALLOCATION_BUDGET_BYTES + " bytes per tick");
    }

    @Test
    void statsCountFramesAndBytes() throws ViewStreamProtocolException {
        SessionHarness harness = withPortals(SessionHarness.options(true, 8), 2);
        harness.handshake(SessionHarness.CLIENT_CAPS);
        harness.tick();
        List<ViewStreamSessionStats> stats = new ArrayList<ViewStreamSessionStats>(harness.registry.stats());
        assertEquals(1, stats.size());
        ViewStreamSessionStats row = stats.get(0);
        assertEquals(harness.client.received.size(), (int) row.framesSent());
        assertEquals(2, row.attended());
        assertTrue(row.bytesSent() > 1000L);
        assertTrue(harness.flushes > 0);
        harness.registry.forget(harness.playerId);
        assertTrue(harness.registry.stats().isEmpty());
        assertEquals(ViewStreamSessionState.VANILLA, harness.session.state());
    }

    private static byte[] miss(int key, int revision, int brickCount, int extraBits) throws ViewStreamProtocolException {
        int bits = brickCount + extraBits;
        long[] words = new long[(bits + 63) >>> 6];
        for (int i = 0; i < bits; i++) {
            words[i >>> 6] |= 1L << (i & 63);
        }
        return ViewStreamFixtures.CODEC.encodeC2S(ViewStreamMessage.BrickMiss.of(new ViewStreamMessage.BrickMiss.Plate(key, revision, words)));
    }
}
