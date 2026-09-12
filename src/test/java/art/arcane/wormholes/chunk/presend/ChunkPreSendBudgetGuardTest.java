package art.arcane.wormholes.chunk.presend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.service.WormholesTelemetry;

final class ChunkPreSendBudgetGuardTest {
    private static final long MILLI = 1_000_000L;

    @Test
    void verdictsSoftStopAtTheBudgetAndHardStopAtTheOverrunLine() {
        ChunkPreSendBudgetGuard guard = new ChunkPreSendBudgetGuard(0L, MILLI);
        assertEquals(ChunkPreSendBudgetGuard.Verdict.CONTINUE, guard.check(500_000L));
        assertEquals(ChunkPreSendBudgetGuard.Verdict.STOP, guard.check(MILLI));
        assertEquals(ChunkPreSendBudgetGuard.Verdict.STOP, guard.check(24L * MILLI));
        assertEquals(ChunkPreSendBudgetGuard.Verdict.OVERRUN, guard.check(25L * MILLI), "half a tick is a stall whatever the budget");
        assertEquals(MILLI, guard.deadlineNanos());
        assertEquals(25L * MILLI, guard.hardStopNanos());

        ChunkPreSendBudgetGuard wide = new ChunkPreSendBudgetGuard(10L * MILLI, 5L * MILLI);
        assertEquals(ChunkPreSendBudgetGuard.Verdict.STOP, wide.check(49L * MILLI));
        assertEquals(ChunkPreSendBudgetGuard.Verdict.OVERRUN, wide.check(50L * MILLI), "eight budgets is a stall");
        assertEquals(40L * MILLI, wide.elapsedNanos(50L * MILLI));
    }

    @Test
    void anOverrunMidBurstRollsTheClientBackToItsSourceViewAndDeliversNothing() {
        RecordingPreSendPlatform platform = new RecordingPreSendPlatform().playerChunk(40, 40).clockStep(300L * MILLI);
        ChunkPreSendService<String, String> service = new ChunkPreSendService<>(platform, () -> ChunkPreSendOptions.of(true, 1, 64, 5000));
        long before = failureCount("PRESEND_BUDGET_OVERRUN");

        ChunkPreSendTicket<String, String> ticket = service.preSend(
            RecordingPreSendPlatform.PLAYER, RecordingPreSendPlatform.DESTINATION_WORLD, 0, 0
        );

        assertEquals(ChunkPreSendOutcome.ROLLED_BACK_BUDGET_OVERRUN, ticket.outcome());
        assertFalse(ticket.outcome().delivered());
        assertFalse(ticket.rollbackRequired(), "the service already undid the half-sent burst");
        assertEquals(List.of(new ChunkCoordinate(0, 0), new ChunkCoordinate(40, 40)), platform.announced(),
            "the destination centre goes out, then the overrun re-centres the client on the source");
        assertEquals(RecordingPreSendPlatform.DESTINATION_WORLD, platform.sent().getFirst().world());
        assertTrue(platform.sent().size() >= 2, "the rollback re-sends the source slot the burst overwrote");
        assertEquals(RecordingPreSendPlatform.SOURCE_WORLD, platform.sent().get(1).world());
        assertEquals(before + 1L, failureCount("PRESEND_BUDGET_OVERRUN"));
    }

    @Test
    void anExhaustedButNotOverrunBudgetStillTruncatesInsteadOfRollingBack() {
        RecordingPreSendPlatform platform = new RecordingPreSendPlatform().clockStep(5L * MILLI);
        ChunkPreSendService<String, String> service = new ChunkPreSendService<>(platform, () -> ChunkPreSendOptions.of(true, 2, 64, 1000));

        ChunkPreSendTicket<String, String> ticket = service.preSend(
            RecordingPreSendPlatform.PLAYER, RecordingPreSendPlatform.DESTINATION_WORLD, 0, 0
        );

        assertEquals(ChunkPreSendOutcome.PRE_SENT_PARTIAL, ticket.outcome());
        assertEquals(1, platform.sent().size());
        assertEquals(1, platform.announced().size());
        assertEquals(25, ticket.plannedChunks());
    }

    private static long failureCount(String reason) {
        Long count = WormholesTelemetry.failureBreakdown().get(reason);
        return count == null ? 0L : count;
    }
}
