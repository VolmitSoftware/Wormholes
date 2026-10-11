package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import art.arcane.optics.math.CellKeys;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.junit.jupiter.api.Test;

public final class ProjectionBlockUpdateRepairTest {
    private static final UUID OBSERVER_ID = UUID.fromString("00000000-0000-0000-0000-000000000081");
    private static final long CELL = CellKeys.pack(-3, -30, -5);

    @Test
    public void fullChunkOutcomesFollowPacketOrderAcrossReplacementAndReset() {
        ProjectionClientChunkTracker tracker = new ProjectionClientChunkTracker();
        try (ProjectionBlockPacketFixture fixture = new ProjectionBlockPacketFixture(OBSERVER_ID)) {
            PacketSendEvent first = fixture.chunk(-1, -1);
            PacketSendEvent replacement = fixture.chunk(-1, -1);
            tracker.onPacketSend(first);
            tracker.onPacketSend(replacement);
            fixture.finish(first);
            assertTrue(tracker.isTracked(OBSERVER_ID, -1, -1));
            fixture.finish(replacement);
            assertTrue(tracker.isTracked(OBSERVER_ID, -1, -1));
            long revision = tracker.chunkRevision(OBSERVER_ID, -1, -1);
            PacketSendEvent obsolete = fixture.chunk(-1, -1);
            tracker.onPacketSend(obsolete);
            fixture.send(tracker, fixture.lifecycle(PacketType.Play.Server.RESPAWN));
            fixture.finish(obsolete);
            assertFalse(tracker.isTracked(OBSERVER_ID, -1, -1));
            fixture.send(tracker, fixture.chunk(-1, -1));
            assertTrue(tracker.chunkRevision(OBSERVER_ID, -1, -1) > revision);
        }
    }

    @Test
    public void singleUpdateSchedulesOnlyAfterSendAndPreservesPacketReaderIndex() {
        List<Runnable> scheduled = new ArrayList<Runnable>();
        LongOpenHashSet repaired = new LongOpenHashSet();
        ProjectionBlockUpdateRepair repair = repair(scheduled, repaired);
        ProjectionClientChunkTracker tracker = tracker(repair);
        try (ProjectionBlockPacketFixture fixture = new ProjectionBlockPacketFixture(OBSERVER_ID)) {
            PacketSendEvent event = fixture.event(new WrapperPlayServerBlockChange(new Vector3i(-3, -30, -5), 5));
            tracker.onPacketSend(event);
            assertTrue(scheduled.isEmpty());
            assertEquals(0, ByteBufHelper.readerIndex(event.getByteBuf()));
            fixture.finish(event);
            assertEquals(1, scheduled.size());
            scheduled.removeFirst().run();
            assertEquals(LongOpenHashSet.of(CELL), repaired);
        }
    }

    @Test
    public void sectionAndSingleUpdatesCoalesceOnlyTouchedClaimedCells() {
        List<Runnable> scheduled = new ArrayList<Runnable>();
        LongOpenHashSet repaired = new LongOpenHashSet();
        ProjectionBlockUpdateRepair repair = repair(scheduled, repaired);
        long otherClaim = CellKeys.pack(-4, -29, -6);
        repair.claim(otherClaim, 42);
        ProjectionClientChunkTracker tracker = tracker(repair);
        try (ProjectionBlockPacketFixture fixture = new ProjectionBlockPacketFixture(OBSERVER_ID)) {
            fixture.send(tracker, fixture.event(new WrapperPlayServerMultiBlockChange(new Vector3i(-1, -2, -1), null,
                new WrapperPlayServerMultiBlockChange.EncodedBlock[] {
                    new WrapperPlayServerMultiBlockChange.EncodedBlock(5, -3, -30, -5),
                    new WrapperPlayServerMultiBlockChange.EncodedBlock(5, -2, -30, -5),
                    new WrapperPlayServerMultiBlockChange.EncodedBlock(5, -4, -29, -6)
                })));
            fixture.send(tracker, fixture.event(new WrapperPlayServerBlockChange(new Vector3i(-3, -30, -5), 6)));
            assertEquals(1, scheduled.size());
            scheduled.removeFirst().run();
            assertEquals(LongOpenHashSet.of(CELL, otherClaim), repaired);
        }
    }

    @Test
    public void projectedIdsAndCountedBukkitSendsDoNotScheduleFeedback() {
        List<Runnable> scheduled = new ArrayList<Runnable>();
        ProjectionBlockUpdateRepair repair = repair(scheduled, new LongOpenHashSet());
        ProjectionClientChunkTracker tracker = tracker(repair);
        try (ProjectionBlockPacketFixture fixture = new ProjectionBlockPacketFixture(OBSERVER_ID)) {
            fixture.send(tracker, fixture.event(new WrapperPlayServerMultiBlockChange(new Vector3i(-1, -2, -1), null,
                new WrapperPlayServerMultiBlockChange.EncodedBlock[] {
                    new WrapperPlayServerMultiBlockChange.EncodedBlock(42, -3, -30, -5)
                })));
            repair.claim(CELL, -1);
            repair.expectBukkitBlockChange(CELL);
            repair.expectBukkitBlockChange(CELL);
            fixture.send(tracker, fixture.event(new WrapperPlayServerBlockChange(new Vector3i(-3, -30, -5), 42)));
            fixture.send(tracker, fixture.event(new WrapperPlayServerBlockChange(new Vector3i(-3, -30, -5), 42)));
            assertTrue(scheduled.isEmpty());
            fixture.send(tracker, fixture.event(new WrapperPlayServerBlockChange(new Vector3i(-3, -30, -5), 5)));
            assertEquals(1, scheduled.size());
        }
    }

    @Test
    public void cancelledUpdateConsumesOwnExpectationWithoutRepairing() {
        List<Runnable> scheduled = new ArrayList<Runnable>();
        ProjectionBlockUpdateRepair repair = repair(scheduled, new LongOpenHashSet());
        ProjectionClientChunkTracker tracker = tracker(repair);
        try (ProjectionBlockPacketFixture fixture = new ProjectionBlockPacketFixture(OBSERVER_ID)) {
            repair.expectBukkitBlockChange(CELL);
            PacketSendEvent event = fixture.event(new WrapperPlayServerBlockChange(new Vector3i(-3, -30, -5), 5));
            when(event.isCancelled()).thenReturn(true);
            fixture.send(tracker, event);
            assertTrue(scheduled.isEmpty());
            assertFalse(repair.consumeBukkitBlockChange(CELL));
            fixture.send(tracker, fixture.event(new WrapperPlayServerBlockChange(new Vector3i(-3, -30, -5), 5)));
            assertEquals(1, scheduled.size());
        }
    }

    @Test
    public void resetRejectsOldAfterSendAndKeepsNewClaimsRepairable() {
        List<Runnable> scheduled = new ArrayList<Runnable>();
        ProjectionBlockUpdateRepair repair = repair(scheduled, new LongOpenHashSet());
        ProjectionClientChunkTracker tracker = tracker(repair);
        try (ProjectionBlockPacketFixture fixture = new ProjectionBlockPacketFixture(OBSERVER_ID)) {
            PacketSendEvent old = fixture.event(new WrapperPlayServerBlockChange(new Vector3i(-3, -30, -5), 5));
            tracker.onPacketSend(old);
            fixture.send(tracker, fixture.lifecycle(PacketType.Play.Server.RESPAWN));
            fixture.finish(old);
            scheduled.removeFirst().run();
            assertTrue(scheduled.isEmpty());
            fixture.send(tracker, fixture.event(new WrapperPlayServerBlockChange(new Vector3i(-3, -30, -5), 5)));
            assertEquals(1, scheduled.size());
        }
    }

    @Test
    public void forgetAndClearDropQueuedTasksAndOwnExpectations() {
        for (boolean clear : List.of(false, true)) {
            List<Runnable> scheduled = new ArrayList<Runnable>();
            AtomicInteger runs = new AtomicInteger();
            ProjectionBlockUpdateRepair repair = new ProjectionBlockUpdateRepair(task -> scheduled.add(task), runs::incrementAndGet);
            repair.claim(CELL, 42);
            ProjectionClientChunkTracker tracker = tracker(repair);
            repair.overwritten(new long[] { CELL });
            repair.expectBukkitBlockChange(CELL);
            if (clear) {
                tracker.clear();
            } else {
                tracker.forget(OBSERVER_ID);
            }
            scheduled.removeFirst().run();
            assertEquals(0, runs.get());
            assertFalse(repair.consumeBukkitBlockChange(CELL));
            assertFalse(repair.touchesClaim(CELL, 5));
        }
    }

    @Test
    public void rejectedSchedulingKeepsPendingCellsForTheNextObserverPass() {
        ProjectionBlockUpdateRepair repair = new ProjectionBlockUpdateRepair(task -> false, () -> { });
        repair.claim(CELL, 42);
        repair.overwritten(new long[] { CELL });
        LongOpenHashSet keys = new LongOpenHashSet();
        repair.drain(keys);
        assertEquals(LongOpenHashSet.of(CELL), keys);
        repair.overwritten(new long[] { CELL });
        repair.release(CELL);
        keys.clear();
        repair.drain(keys);
        assertTrue(keys.isEmpty());
    }

    private static ProjectionClientChunkTracker tracker(ProjectionBlockUpdateRepair repair) {
        ProjectionClientChunkTracker tracker = new ProjectionClientChunkTracker();
        tracker.watchBlockUpdates(OBSERVER_ID, repair);
        return tracker;
    }

    private static ProjectionBlockUpdateRepair repair(List<Runnable> scheduled, LongOpenHashSet repaired) {
        ProjectionBlockUpdateRepair[] holder = new ProjectionBlockUpdateRepair[1];
        holder[0] = new ProjectionBlockUpdateRepair(task -> scheduled.add(task), () -> holder[0].drain(repaired));
        holder[0].claim(CELL, 42);
        return holder[0];
    }
}
