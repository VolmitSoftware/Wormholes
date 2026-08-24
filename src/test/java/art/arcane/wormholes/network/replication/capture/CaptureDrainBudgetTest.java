package art.arcane.wormholes.network.replication.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

public final class CaptureDrainBudgetTest {
    @Test
    public void oneThousandDirtyChunksStayCappedAndCompleteAFairRotation() {
        CaptureDrainBudget budget = new CaptureDrainBudget();
        List<CaptureRegionScheduler.DrainKey> candidates = candidates(1_000);
        Set<CaptureRegionScheduler.DrainKey> visited = new HashSet<CaptureRegionScheduler.DrainKey>();

        for (int pass = 0; pass < 16; pass++) {
            List<CaptureRegionScheduler.DrainKey> selected = budget.select(candidates, Set.of(), 64);
            assertTrue(selected.size() <= 64);
            visited.addAll(selected);
        }

        assertEquals(1_000, visited.size());
    }

    @Test
    public void inFlightChunksDoNotConsumeAdmissions() {
        CaptureDrainBudget budget = new CaptureDrainBudget();
        List<CaptureRegionScheduler.DrainKey> candidates = candidates(128);
        Set<CaptureRegionScheduler.DrainKey> unavailable = Set.copyOf(candidates.subList(0, 64));

        List<CaptureRegionScheduler.DrainKey> selected = budget.select(candidates, unavailable, 64);

        assertEquals(64, selected.size());
        for (CaptureRegionScheduler.DrainKey key : selected) {
            assertFalse(unavailable.contains(key));
        }
    }

    private static List<CaptureRegionScheduler.DrainKey> candidates(int count) {
        UUID worldId = UUID.fromString("58b9027d-ab98-4ee4-a680-d29767853d4b");
        List<CaptureRegionScheduler.DrainKey> candidates = new ArrayList<CaptureRegionScheduler.DrainKey>(count);
        for (int index = 0; index < count; index++) {
            candidates.add(new CaptureRegionScheduler.DrainKey(worldId, index));
        }
        return candidates;
    }
}
