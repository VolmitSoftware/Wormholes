package art.arcane.wormholes.nexus;

import art.arcane.wormholes.nexus.FrameIo.ComparatorOutput;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedstoneIoIndexTest {
    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID OTHER_WORLD = UUID.randomUUID();

    @Test
    void theProbeReturnsAPortalOnlyForTheExactRegisteredBlock() {
        RedstoneIoIndex index = new RedstoneIoIndex();
        UUID portalId = UUID.randomUUID();

        index.put(portalId, WORLD, 10, 64, -7);

        assertEquals(portalId, index.portalAt(WORLD, 10, 64, -7));
        assertNull(index.portalAt(WORLD, 11, 64, -7));
        assertNull(index.portalAt(WORLD, 10, 65, -7));
        assertNull(index.portalAt(WORLD, 10, 64, -6));
        assertNull(index.portalAt(OTHER_WORLD, 10, 64, -7));
        assertEquals(1, index.size());
    }

    @Test
    void blocksInOtherChunksAndUnregisteredChunksMissWithoutScanning() {
        RedstoneIoIndex index = new RedstoneIoIndex();
        UUID near = UUID.randomUUID();
        UUID far = UUID.randomUUID();

        index.put(near, WORLD, 3, 64, 3);
        index.put(far, WORLD, 500, 64, 500);

        assertEquals(near, index.portalAt(WORLD, 3, 64, 3));
        assertEquals(far, index.portalAt(WORLD, 500, 64, 500));
        assertNull(index.portalAt(WORLD, 250, 64, 250));
        assertEquals(2, index.size());
    }

    @Test
    void movingAPortalsControlBlockLeavesNothingBehindAtTheOldPosition() {
        RedstoneIoIndex index = new RedstoneIoIndex();
        UUID portalId = UUID.randomUUID();

        index.put(portalId, WORLD, 1, 64, 1);
        index.put(portalId, WORLD, 90, 70, 90);

        assertNull(index.portalAt(WORLD, 1, 64, 1));
        assertEquals(portalId, index.portalAt(WORLD, 90, 70, 90));
        assertEquals(1, index.size());
    }

    @Test
    void removingAndClearingDropEveryRegistration() {
        RedstoneIoIndex index = new RedstoneIoIndex();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        index.put(first, WORLD, 1, 64, 1);
        index.put(second, WORLD, 2, 64, 1);

        index.remove(first);
        assertNull(index.portalAt(WORLD, 1, 64, 1));
        assertEquals(second, index.portalAt(WORLD, 2, 64, 1));
        assertEquals(1, index.size());

        index.clear();
        assertNull(index.portalAt(WORLD, 2, 64, 1));
        assertEquals(0, index.size());
    }

    @Test
    void onlyARisingEdgeCountsAndARepeatedHighSignalDoesNot() {
        assertTrue(RedstoneIo.isRisingEdge(0, 15));
        assertTrue(RedstoneIo.isRisingEdge(0, 1));
        assertFalse(RedstoneIo.isRisingEdge(15, 15), "a held signal is not a new edge");
        assertFalse(RedstoneIo.isRisingEdge(9, 15), "a stronger held signal is not a new edge");
        assertFalse(RedstoneIo.isRisingEdge(15, 0));
        assertFalse(RedstoneIo.isRisingEdge(0, 0));
    }

    @Test
    void comparatorOutputReportsOpenStateOrRecentTraversalsCappedAtFifteen() {
        assertEquals(0, RedstoneIo.comparatorLevel(ComparatorOutput.NONE, true, 9));
        assertEquals(15, RedstoneIo.comparatorLevel(ComparatorOutput.STATE, true, 0));
        assertEquals(0, RedstoneIo.comparatorLevel(ComparatorOutput.STATE, false, 9));
        assertEquals(0, RedstoneIo.comparatorLevel(ComparatorOutput.TRAVERSALS, true, 0));
        assertEquals(9, RedstoneIo.comparatorLevel(ComparatorOutput.TRAVERSALS, true, 9));
        assertEquals(15, RedstoneIo.comparatorLevel(ComparatorOutput.TRAVERSALS, true, 40));
    }

    @Test
    void theTraversalCounterOnlyCountsTheLastMinute() {
        RedstoneIo.TraversalCounter counter = new RedstoneIo.TraversalCounter();
        UUID portalId = UUID.randomUUID();

        counter.record(portalId, 1_000L);
        counter.record(portalId, 2_000L);
        assertEquals(2, counter.inLastMinute(portalId, 3_000L));

        assertEquals(1, counter.inLastMinute(portalId, 61_500L));
        assertEquals(0, counter.inLastMinute(portalId, 120_000L));
        assertEquals(0, counter.inLastMinute(UUID.randomUUID(), 1_000L));
    }
}
