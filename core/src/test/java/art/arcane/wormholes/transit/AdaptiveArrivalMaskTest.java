package art.arcane.wormholes.transit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.chunk.presend.ChunkPreSendOutcome;

final class AdaptiveArrivalMaskTest {
    private static final int MIN = 5;
    private static final int MAX = 25;

    @Test
    void noMaskWithoutAWorldChangeOrWhenTheWholeRingWasPreSent() {
        assertEquals(0, AdaptiveArrivalMask.maskTicks(false, null, 0, 0, MIN, MAX));
        assertEquals(0, AdaptiveArrivalMask.maskTicks(false, ChunkPreSendOutcome.PRE_SENT_PARTIAL, 1, 9, MIN, MAX));
        assertEquals(0, AdaptiveArrivalMask.maskTicks(true, ChunkPreSendOutcome.PRE_SENT, 9, 9, MIN, MAX));
        assertEquals(0, AdaptiveArrivalMask.maskTicks(true, ChunkPreSendOutcome.PRE_SENT_PARTIAL, 9, 9, MIN, MAX), "partial with nothing missing is complete");
    }

    @Test
    void aPartialBurstMasksInProportionToWhatStillHasToStreamWithAFloor() {
        assertEquals(14, AdaptiveArrivalMask.maskTicks(true, ChunkPreSendOutcome.PRE_SENT_PARTIAL, 4, 9, MIN, MAX), "5 of 9 missing -> ceil(25 * 5 / 9)");
        assertEquals(MIN, AdaptiveArrivalMask.maskTicks(true, ChunkPreSendOutcome.PRE_SENT_PARTIAL, 8, 9, MIN, MAX), "one missing chunk still gets the floor");
        assertEquals(MAX, AdaptiveArrivalMask.maskTicks(true, ChunkPreSendOutcome.PRE_SENT_PARTIAL, 0, 9, MIN, MAX));
        assertEquals(MAX, AdaptiveArrivalMask.maskTicks(true, ChunkPreSendOutcome.PRE_SENT_PARTIAL, 0, 0, MIN, MAX), "an empty plan cannot have pre-sent anything");
    }

    @Test
    void noPreSendOrARolledBackOneFallsBackToTheFixedMask() {
        assertEquals(MAX, AdaptiveArrivalMask.maskTicks(true, null, 0, 0, MIN, MAX));
        assertEquals(MAX, AdaptiveArrivalMask.maskTicks(true, ChunkPreSendOutcome.ROLLED_BACK_BUDGET_OVERRUN, 3, 9, MIN, MAX));
        assertEquals(MAX, AdaptiveArrivalMask.maskTicks(true, ChunkPreSendOutcome.SKIPPED_DISABLED, 0, 0, MIN, MAX));
    }

    @Test
    void boundsAreRespectedWhenTheOperatorPicksOddValues() {
        assertEquals(0, AdaptiveArrivalMask.maskTicks(true, null, 0, 0, MIN, 0), "a zero fixed mask disables masking");
        assertEquals(3, AdaptiveArrivalMask.maskTicks(true, ChunkPreSendOutcome.PRE_SENT_PARTIAL, 8, 9, 40, 3), "the floor never exceeds the ceiling");
        assertEquals(3, AdaptiveArrivalMask.maskTicks(true, ChunkPreSendOutcome.PRE_SENT_PARTIAL, 8, 9, -7, MAX), "a negative floor counts as zero so the proportional size stands");
    }
}
