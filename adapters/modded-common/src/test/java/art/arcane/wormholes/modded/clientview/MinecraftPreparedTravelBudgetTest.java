package art.arcane.wormholes.modded.clientview;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MinecraftPreparedTravelBudgetTest {
    @Test
    public void cacheProofCaptureUsesWorkBoundsInsteadOfPayloadBandwidth() {
        MinecraftPreparedTravel.CaptureBudget budget = new MinecraftPreparedTravel.CaptureBudget(true, 1_000L);
        int bytes = 0;
        for (int column = 0; column < 16; column++) {
            assertTrue(budget.allows(2_000L));
            budget.captured(64 * 1024);
            bytes += 64 * 1024;
        }
        assertEquals(1024 * 1024, bytes);
        assertFalse(budget.allows(2_000L));
    }

    @Test
    public void ordinaryTransferKeepsPayloadBudgetAndOversizeProgress() {
        MinecraftPreparedTravel.CaptureBudget budget = new MinecraftPreparedTravel.CaptureBudget(false, 1_000L);
        for (int column = 0; column < 2; column++) {
            assertTrue(budget.allows(2_000L));
            budget.captured(64 * 1024);
        }
        assertFalse(budget.allows(2_000L));
        MinecraftPreparedTravel.CaptureBudget oversized = new MinecraftPreparedTravel.CaptureBudget(false, 1_000L);
        assertTrue(oversized.allows(2_000L));
        oversized.captured(256 * 1024);
        assertFalse(oversized.allows(2_000L));
    }

    @Test
    public void cacheProofCaptureStillStopsAtElapsedWorkLimit() {
        MinecraftPreparedTravel.CaptureBudget budget = new MinecraftPreparedTravel.CaptureBudget(true, 1_000L);
        assertTrue(budget.allows(2_000L));
        budget.captured(64 * 1024);
        assertFalse(budget.allows(2_001_000L));
        MinecraftPreparedTravel.CaptureBudget waiting = new MinecraftPreparedTravel.CaptureBudget(true, 1_000L);
        assertTrue(waiting.allows(2_000L));
        assertFalse(waiting.allows(2_001_000L));
    }
}
