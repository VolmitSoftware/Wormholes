package art.arcane.optics.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public final class ResampleReasonsTest {
    @Test
    public void emptyWindowDescribesAsNone() {
        ResampleReasons reasons = new ResampleReasons();

        assertEquals(0, reasons.mask());
        assertEquals("none", reasons.describe());
    }

    @Test
    public void passesAccumulateTheirReasonsAndCounts() {
        ResampleReasons reasons = new ResampleReasons();

        reasons.record(ResampleReasons.STABLE_CADENCE | ResampleReasons.DEST_STALE_DIRTY);
        reasons.record(ResampleReasons.STABLE_CADENCE);
        reasons.record(ResampleReasons.UNRESOLVED_OCCLUSION);

        assertEquals(ResampleReasons.STABLE_CADENCE | ResampleReasons.DEST_STALE_DIRTY
            | ResampleReasons.UNRESOLVED_OCCLUSION, reasons.mask());
        assertEquals("destStaleDirty:1,stableCadence:2,unresolvedOcclusion:1", reasons.describe());
    }

    @Test
    public void passesWithoutAKnownReasonCountAsOther() {
        ResampleReasons reasons = new ResampleReasons();

        reasons.record(0);

        assertEquals("other:1", reasons.describe());
    }

    @Test
    public void resetStartsANewWindow() {
        ResampleReasons reasons = new ResampleReasons();
        reasons.record(ResampleReasons.INVALIDATED | ResampleReasons.LIGHTING);

        reasons.reset();
        reasons.record(ResampleReasons.REMOTE_PENDING);

        assertEquals(ResampleReasons.REMOTE_PENDING, reasons.mask());
        assertEquals("remotePending:1", reasons.describe());
    }

    @Test
    public void everyReasonHasADistinctBitAndName() {
        ResampleReasons reasons = new ResampleReasons();
        int all = 0;
        for (int bit = 0; bit < ResampleReasons.REASON_COUNT; bit++) {
            all |= 1 << bit;
        }

        reasons.record(all);

        assertEquals(all, reasons.mask());
        assertEquals("invalidated:1,destStaleRecursive:1,destStaleDirty:1,destOverBudget:1,localDirty:1,stableCadence:1,"
            + "presentation:1,remotePending:1,lighting:1,unresolvedOcclusion:1,camera:1,fullSend:1,holdsExposed:1", reasons.describe());
    }
}
