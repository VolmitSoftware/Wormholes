package art.arcane.wormholes.modded.seamless;

import art.arcane.wormholes.modded.MinecraftTestBase;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.world.level.ChunkPos;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RemoteRoutesBudgetTest extends MinecraftTestBase {
    @Test
    public void planTakesTheNearestUndeliveredColumnsWithinTheChunkBudget() {
        RouteStream stream = new RouteStream(new RouteWindow(0, 0, 3));

        LongList first = stream.plan(8);
        assertEquals(8, first.size());
        assertEquals(ChunkPos.pack(0, 0), first.getLong(0));
        for (int index = 0; index < first.size(); index++) {
            stream.markDelivered(first.getLong(index));
        }
        LongList second = stream.plan(8);
        for (int index = 0; index < second.size(); index++) {
            assertFalse(first.contains(second.getLong(index)));
            assertTrue(Math.max(Math.abs(ChunkPos.getX(second.getLong(index))), Math.abs(ChunkPos.getZ(second.getLong(index)))) >= 1);
        }
    }

    @Test
    public void clientAcknowledgementHintCapsTheConfiguredBudgetAndIsClamped() {
        RouteStream stream = new RouteStream(new RouteWindow(0, 0, 2));

        assertEquals(8, stream.chunkBudget(8));
        stream.ack(3);
        assertEquals(3, stream.chunkBudget(8));
        stream.ack(0);
        assertEquals(1, stream.chunkBudget(8));
        stream.ack(500);
        assertEquals(8, stream.chunkBudget(8));
        assertEquals(64, stream.chunkBudget(200));
    }

    @Test
    public void aColumnThatFailedToSendWaitsForItsNextChangeInsteadOfCountingAsDelivered() {
        RouteStream stream = new RouteStream(new RouteWindow(0, 0, 1));
        long key = ChunkPos.pack(0, 0);

        stream.failed(key);

        assertFalse(stream.delivered(key));
        assertFalse(stream.needs(key));
        assertFalse(stream.plan(64).contains(key));
        assertFalse(stream.changed(key));
        assertTrue(stream.needs(key));
        assertTrue(stream.plan(64).contains(key));
    }

    @Test
    public void changedColumnsAreResentWithAHigherRevision() {
        RouteStream stream = new RouteStream(new RouteWindow(0, 0, 1));
        long key = ChunkPos.pack(1, 0);

        assertEquals(1, stream.markDelivered(key));
        assertTrue(stream.delivered(key));
        assertFalse(stream.needs(key));
        assertTrue(stream.changed(key));
        assertFalse(stream.delivered(key));
        assertTrue(stream.needs(key));
        assertTrue(stream.plan(64).contains(key));
        assertEquals(2, stream.markDelivered(key));
        assertFalse(stream.changed(ChunkPos.pack(9, 9)));
    }

    @Test
    public void columnsLeavingTheWindowAreForgottenOnlyAfterTheHysteresis() {
        RouteStream stream = new RouteStream(new RouteWindow(0, 0, 3));
        long edge = ChunkPos.pack(4, 0);
        stream.markDelivered(edge);
        stream.markDelivered(ChunkPos.pack(0, 0));

        stream.window(new RouteWindow(0, 0, 1), 100L);
        assertTrue(stream.forgets(100L + RouteStream.FORGET_HYSTERESIS_TICKS - 1).isEmpty());
        stream.window(new RouteWindow(0, 0, 3), 120L);
        assertTrue(stream.forgets(1_000L).isEmpty());
        stream.window(new RouteWindow(0, 0, 1), 200L);
        LongList forgotten = stream.forgets(200L + RouteStream.FORGET_HYSTERESIS_TICKS);
        assertEquals(1, forgotten.size());
        assertEquals(edge, forgotten.getLong(0));
        assertEquals(0, stream.revision(edge));
        assertTrue(stream.delivered(ChunkPos.pack(0, 0)));
    }

    @Test
    public void deliveredRadiusIsTheLargestCompletelyDeliveredWindow() {
        RouteWindow window = new RouteWindow(5, -5, 3);
        RouteStream stream = new RouteStream(window);
        assertEquals(0, stream.deliveredRadius());
        LongList inner = window.withRadius(2).keys();
        for (int index = 0; index < inner.size(); index++) {
            stream.markDelivered(inner.getLong(index));
        }
        assertEquals(2, stream.deliveredRadius());
        assertTrue(stream.complete(window.withRadius(1)));
        assertFalse(stream.complete(window));
    }

    @Test
    public void adoptedColumnsOutsideTheWindowLeaveAfterTheHysteresis() {
        RouteStream stream = new RouteStream(new RouteWindow(0, 0, 1));
        long inside = ChunkPos.pack(0, 1);
        long outside = ChunkPos.pack(8, 8);

        stream.adopt(inside, 50L);
        stream.adopt(outside, 50L);

        assertTrue(stream.delivered(inside));
        assertTrue(stream.delivered(outside));
        assertTrue(stream.forgets(109L).isEmpty());
        assertEquals(1, stream.forgets(110L).size());
        assertTrue(stream.delivered(inside));
    }

    @Test
    public void onlyDeliveredTickingColumnsAreTrustedToLiveBroadcasts() {
        RouteStream stream = new RouteStream(new RouteWindow(0, 0, 1));
        long key = ChunkPos.pack(0, 0);

        stream.live(key, true);
        assertFalse(stream.live(key));
        stream.markDelivered(key);
        stream.live(key, false);
        assertFalse(stream.live(key));
        stream.live(key, true);
        assertTrue(stream.live(key));
        stream.changed(key);
        assertFalse(stream.live(key));
        stream.markDelivered(key);
        stream.live(key, true);
        stream.window(new RouteWindow(9, 9, 1), 0L);
        stream.forgets(RouteStream.FORGET_HYSTERESIS_TICKS);
        assertFalse(stream.live(key));
    }
}
