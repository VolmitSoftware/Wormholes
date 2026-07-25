package art.arcane.wormholes.chunk.presend;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkPreSendPlannerTest {
    private static final ChunkPreSendOptions GENEROUS = ChunkPreSendOptions.of(true, 2, 1000, 5000);

    @Test
    void aRadiusOfZeroSelectsOnlyTheDestinationChunk() {
        List<ChunkCoordinate> selected = ChunkPreSendPlanner.ring(4, -9, 0);

        assertEquals(List.of(new ChunkCoordinate(4, -9)), selected);
    }

    @Test
    void selectionIsTheFullChebyshevSquareAroundTheDestinationCentre() {
        List<ChunkCoordinate> selected = ChunkPreSendPlanner.ring(5, -3, 2);

        assertEquals(25, selected.size());
        assertEquals(25, new HashSet<>(selected).size());
        for (ChunkCoordinate coordinate : selected) {
            assertTrue(coordinate.chebyshevDistance(5, -3) <= 2, coordinate + " is outside the requested radius");
        }
    }

    @Test
    void selectionIsOrderedNearestFirstSoATruncatedBurstStillCoversTheCentre() {
        List<ChunkCoordinate> selected = ChunkPreSendPlanner.ring(0, 0, 3);

        assertEquals(new ChunkCoordinate(0, 0), selected.get(0));
        int previous = 0;
        for (ChunkCoordinate coordinate : selected) {
            int distance = coordinate.chebyshevDistance(0, 0);
            assertTrue(distance >= previous, "ring order must never step back towards the centre");
            previous = distance;
        }
    }

    @Test
    void theRequestedRadiusIsClampedToTheClientAcceptanceRadius() {
        assertEquals(13, ChunkPreSendPlanner.effectiveRadius(16, 10));
        assertEquals(5, ChunkPreSendPlanner.effectiveRadius(9, 2));
        assertEquals(2, ChunkPreSendPlanner.effectiveRadius(2, 32));
        assertEquals(0, ChunkPreSendPlanner.effectiveRadius(-4, 32));
    }

    @Test
    void aPlanWithinBudgetIsWholeAndNotMarkedPartial() {
        ChunkPreSendPlan plan = ChunkPreSendPlanner.plan(request(GENEROUS));

        assertEquals(ChunkPreSendOutcome.PRE_SENT, plan.outcome());
        assertEquals(25, plan.size());
        assertFalse(plan.truncated());
        assertEquals(2, plan.radiusChunks());
    }

    @Test
    void aPlanOverTheChunkBudgetIsTruncatedToTheBudgetAndReportedAsPartial() {
        ChunkPreSendPlan plan = ChunkPreSendPlanner.plan(request(ChunkPreSendOptions.of(true, 2, 10, 5000)));

        assertEquals(ChunkPreSendOutcome.PRE_SENT_PARTIAL, plan.outcome());
        assertEquals(10, plan.size());
        assertTrue(plan.truncated());
        Set<ChunkCoordinate> kept = new HashSet<>(plan.chunks());
        for (ChunkCoordinate coordinate : ChunkPreSendPlanner.ring(30, -40, 1)) {
            assertTrue(kept.contains(coordinate), "the inner ring must survive truncation: " + coordinate);
        }
    }

    @Test
    void theBudgetCeilingCapsTheConfiguredValuesRatherThanTrustingThem() {
        ChunkPreSendOptions options = ChunkPreSendOptions.of(true, 9999, 99999, 999999);

        assertEquals(ChunkPreSendOptions.MAX_RADIUS_CHUNKS, options.radiusChunks());
        assertEquals(ChunkPreSendOptions.MAX_CHUNKS_CEILING, options.maxChunks());
        assertEquals(
            ChunkPreSendOptions.MAX_BUDGET_MICROS * ChunkPreSendOptions.NANOS_PER_MICRO,
            options.budgetNanos()
        );
    }

    @Test
    void theFeatureIsOffByDefaultSoAnUnconfiguredServerBehavesExactlyAsItDoesToday() {
        assertFalse(ChunkPreSendOptions.defaults().enabled());
        assertFalse(ChunkPreSendOptions.defaults().usable());
        assertEquals(
            ChunkPreSendOutcome.SKIPPED_DISABLED,
            ChunkPreSendPlanner.plan(request(ChunkPreSendOptions.defaults())).outcome()
        );
    }

    @Test
    void anUnsupportedPlatformDegradesToASkipRatherThanAnError() {
        ChunkPreSendRequest request = new ChunkPreSendRequest(
            false, true, true, true, true, false, 0, 0, 30, -40, 10, GENEROUS
        );

        assertEquals(ChunkPreSendOutcome.SKIPPED_UNSUPPORTED_PLATFORM, ChunkPreSendPlanner.plan(request).outcome());
    }

    @Test
    void aPlayerWhoLeftTheServerDegradesToASkip() {
        ChunkPreSendRequest request = new ChunkPreSendRequest(
            true, false, true, true, true, false, 0, 0, 30, -40, 10, GENEROUS
        );

        assertEquals(ChunkPreSendOutcome.SKIPPED_PLAYER_OFFLINE, ChunkPreSendPlanner.plan(request).outcome());
    }

    @Test
    void aCrossServerDestinationIsSkippedBeforeAnyLocalWorldQuestionIsAsked() {
        ChunkPreSendRequest request = new ChunkPreSendRequest(
            true, true, false, false, false, false, 0, 0, 30, -40, 10, GENEROUS
        );

        assertEquals(ChunkPreSendOutcome.SKIPPED_CROSS_SERVER, ChunkPreSendPlanner.plan(request).outcome());
    }

    @Test
    void aZeroChunkBudgetIsRefusedInsteadOfAnnouncingAViewCentreWithNothingBehindIt() {
        ChunkPreSendRequest request = new ChunkPreSendRequest(
            true, true, true, true, true, false, 0, 0, 30, -40, 10, ChunkPreSendOptions.of(true, 2, 0, 5000)
        );

        assertEquals(ChunkPreSendOutcome.SKIPPED_NO_BUDGET, ChunkPreSendPlanner.plan(request).outcome());
    }

    @Test
    void aZeroTimeBudgetIsRefusedForTheSameReason() {
        ChunkPreSendRequest request = new ChunkPreSendRequest(
            true, true, true, true, true, false, 0, 0, 30, -40, 10, ChunkPreSendOptions.of(true, 2, 32, 0)
        );

        assertEquals(ChunkPreSendOutcome.SKIPPED_NO_BUDGET, ChunkPreSendPlanner.plan(request).outcome());
    }

    @Test
    void anUnloadedDestinationDegradesToASkipInsteadOfForcingAChunkLoad() {
        ChunkPreSendRequest request = new ChunkPreSendRequest(
            true, true, true, false, true, false, 0, 0, 30, -40, 10, GENEROUS
        );

        assertEquals(ChunkPreSendOutcome.SKIPPED_DESTINATION_UNLOADED, ChunkPreSendPlanner.plan(request).outcome());
    }

    @Test
    void aDestinationOwnedByAnotherRegionThreadIsSkippedRatherThanReadAcrossRegions() {
        ChunkPreSendRequest request = new ChunkPreSendRequest(
            true, true, true, true, false, false, 0, 0, 30, -40, 10, GENEROUS
        );

        assertEquals(ChunkPreSendOutcome.SKIPPED_REGION_NOT_OWNED, ChunkPreSendPlanner.plan(request).outcome());
    }

    @Test
    void aForeignRegionIsAnsweredBeforeTheLoadedQuestionBecauseTheChunkMapIsAlsoRegionState() {
        ChunkPreSendRequest request = new ChunkPreSendRequest(
            true, true, true, false, false, false, 0, 0, 30, -40, 10, GENEROUS
        );

        assertEquals(ChunkPreSendOutcome.SKIPPED_REGION_NOT_OWNED, ChunkPreSendPlanner.plan(request).outcome());
    }

    @Test
    void everyDeliveredOutcomeIsASuccessAndCarriesNoFailureReason() {
        for (ChunkPreSendOutcome outcome : ChunkPreSendOutcome.values()) {
            if (!outcome.delivered()) {
                continue;
            }
            assertNull(
                outcome.telemetryReason(),
                outcome + " delivered chunks and must not be counted against failuresPerMinute"
            );
        }
    }

    @Test
    void theShippedDefaultChunkBudgetCoversTheWholeShippedDefaultRing() {
        int ring = ChunkPreSendPlanner.ring(0, 0, ChunkPreSendOptions.DEFAULT_RADIUS_CHUNKS).size();

        assertTrue(
            ChunkPreSendOptions.DEFAULT_MAX_CHUNKS >= ring,
            "the shipped defaults would otherwise report every traversal as a truncated burst"
        );
    }

    @Test
    void aBurstAtShippedDefaultsIsWholeRatherThanPartial() {
        ChunkPreSendRequest request = new ChunkPreSendRequest(
            true, true, true, true, true, false, 0, 0, 30, -40, 10,
            ChunkPreSendOptions.of(
                true,
                ChunkPreSendOptions.DEFAULT_RADIUS_CHUNKS,
                ChunkPreSendOptions.DEFAULT_MAX_CHUNKS,
                ChunkPreSendOptions.DEFAULT_BUDGET_MICROS
            )
        );

        ChunkPreSendPlan plan = ChunkPreSendPlanner.plan(request);

        assertEquals(ChunkPreSendOutcome.PRE_SENT, plan.outcome());
        assertFalse(plan.truncated());
        assertEquals(49, plan.size());
    }

    private static ChunkPreSendRequest request(ChunkPreSendOptions options) {
        return new ChunkPreSendRequest(true, true, true, true, true, false, 0, 0, 30, -40, 10, options);
    }
}
