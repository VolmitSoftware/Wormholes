package art.arcane.wormholes.chunk.presend;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkPreSendRollbackTest {
    private static final ClientChunkWindow SOURCE = ClientChunkWindow.around(0, 0, 2);

    @Test
    void aCrossWorldBurstClobbersOneSourceChunkPerChunkSentSoRollbackCostsExactlyTheSame() {
        List<ChunkCoordinate> sent = ChunkPreSendPlanner.ring(1000, 1000, 2);

        ChunkPreSendRollback rollback = ChunkPreSendRollback.of(SOURCE, sent, false);

        assertEquals(sent.size(), rollback.size());
        assertEquals(rollback.size(), new HashSet<>(rollback.resend()).size());
        for (ChunkCoordinate coordinate : rollback.resend()) {
            assertTrue(SOURCE.contains(coordinate.x(), coordinate.z()),
                coordinate + " must be a chunk the player can actually see from the source");
        }
    }

    @Test
    void aRollbackChunkOccupiesTheSameStorageSlotAsTheDestinationChunkThatEvictedIt() {
        List<ChunkCoordinate> sent = ChunkPreSendPlanner.ring(1000, 1000, 1);

        ChunkPreSendRollback rollback = ChunkPreSendRollback.of(SOURCE, sent, false);

        Set<Integer> destinationSlots = new HashSet<>();
        for (ChunkCoordinate coordinate : sent) {
            destinationSlots.add(SOURCE.slotIndex(coordinate.x(), coordinate.z()));
        }
        Set<Integer> rollbackSlots = new HashSet<>();
        for (ChunkCoordinate coordinate : rollback.resend()) {
            rollbackSlots.add(SOURCE.slotIndex(coordinate.x(), coordinate.z()));
        }
        assertEquals(destinationSlots, rollbackSlots);
    }

    @Test
    void aSameWorldBurstOnTheSameCentreClobbersNothingBecauseEveryChunkReplacesItself() {
        List<ChunkCoordinate> sent = ChunkPreSendPlanner.ring(0, 0, 2);

        ChunkPreSendRollback rollback = ChunkPreSendRollback.of(SOURCE, sent, true);

        assertTrue(rollback.empty());
        assertEquals(0, rollback.size());
    }

    @Test
    void aSameWorldBurstPastTheWindowEdgeOnlyRollsBackTheChunksThatActuallyChanged() {
        List<ChunkCoordinate> sent = ChunkPreSendPlanner.ring(6, 0, 1);

        ChunkPreSendRollback sameWorld = ChunkPreSendRollback.of(SOURCE, sent, true);
        ChunkPreSendRollback crossWorld = ChunkPreSendRollback.of(SOURCE, sent, false);

        assertEquals(9, sent.size());
        assertEquals(6, sameWorld.size());
        assertEquals(9, crossWorld.size());
        for (ChunkCoordinate coordinate : sameWorld.resend()) {
            assertFalse(sent.contains(coordinate), coordinate + " was never overwritten and must not be re-sent");
        }
    }

    @Test
    void destinationChunksThatCollideOnTheSameSlotProduceASingleRollbackEntry() {
        List<ChunkCoordinate> sent = List.of(new ChunkCoordinate(0, 0), new ChunkCoordinate(11, 0));

        ChunkPreSendRollback rollback = ChunkPreSendRollback.of(SOURCE, sent, false);

        assertEquals(1, rollback.size());
        assertEquals(new ChunkCoordinate(0, 0), rollback.resend().get(0));
    }

    @Test
    void rollbackRestoresTheChunksClosestToThePlayerFirst() {
        List<ChunkCoordinate> sent = ChunkPreSendPlanner.ring(1000, 1000, 3);

        ChunkPreSendRollback rollback = ChunkPreSendRollback.of(SOURCE, sent, false);

        int previous = 0;
        for (ChunkCoordinate coordinate : rollback.resend()) {
            int distance = coordinate.chebyshevDistance(0, 0);
            assertTrue(distance >= previous, "rollback order must never step back towards the player");
            previous = distance;
        }
    }

    @Test
    void aRollbackCarriesTheSourceCentreSoTheClientWindowIsRestoredBeforeAnyChunkArrives() {
        ClientChunkWindow window = ClientChunkWindow.around(-77, 42, 6);

        ChunkPreSendRollback rollback = ChunkPreSendRollback.of(window, List.of(new ChunkCoordinate(5000, 5000)), false);

        assertEquals(-77, rollback.sourceCenterX());
        assertEquals(42, rollback.sourceCenterZ());
    }

    @Test
    void aBurstThatSentNothingNeedsNoRollback() {
        assertTrue(ChunkPreSendRollback.of(SOURCE, List.of(), false).empty());
        assertTrue(ChunkPreSendRollback.none(3, 4).empty());
    }

    @Test
    void theRollbackCostIsBoundedByTheSameChunkBudgetThatBoundedTheBurst() {
        ChunkPreSendOptions options = ChunkPreSendOptions.of(true, 8, 12, 4000);
        ChunkPreSendRequest request = new ChunkPreSendRequest(
            true, true, true, true, true, false, 0, 0, 4000, -4000, 10, options
        );

        ChunkPreSendPlan plan = ChunkPreSendPlanner.plan(request);
        ChunkPreSendRollback rollback = ChunkPreSendRollback.of(SOURCE, plan.chunks(), false);

        assertEquals(12, plan.size());
        assertTrue(rollback.size() <= options.maxChunks(),
            "rollback must never cost more chunks than the burst it undoes");
        assertEquals(12, rollback.size());
    }
}
