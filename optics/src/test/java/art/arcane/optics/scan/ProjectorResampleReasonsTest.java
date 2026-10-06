package art.arcane.optics.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public final class ProjectorResampleReasonsTest {
    @Test
    public void emptyWindowDescribesAsNone() {
        ProjectorResampleReasons reasons = new ProjectorResampleReasons();

        assertEquals(0, reasons.mask());
        assertEquals("none", reasons.describe());
    }

    @Test
    public void passesAccumulateTheirReasonsAndCounts() {
        ProjectorResampleReasons reasons = new ProjectorResampleReasons();

        reasons.record(ProjectorResampleReasons.STABLE_CADENCE | ProjectorResampleReasons.DEST_STALE_DIRTY);
        reasons.record(ProjectorResampleReasons.STABLE_CADENCE);
        reasons.record(ProjectorResampleReasons.UNRESOLVED_OCCLUSION);

        assertEquals(ProjectorResampleReasons.STABLE_CADENCE | ProjectorResampleReasons.DEST_STALE_DIRTY
            | ProjectorResampleReasons.UNRESOLVED_OCCLUSION, reasons.mask());
        assertEquals("destStaleDirty:1,stableCadence:2,unresolvedOcclusion:1", reasons.describe());
    }

    @Test
    public void passesWithoutAKnownReasonCountAsOther() {
        ProjectorResampleReasons reasons = new ProjectorResampleReasons();

        reasons.record(0);

        assertEquals("other:1", reasons.describe());
    }

    @Test
    public void resetStartsANewWindow() {
        ProjectorResampleReasons reasons = new ProjectorResampleReasons();
        reasons.record(ProjectorResampleReasons.INVALIDATED | ProjectorResampleReasons.LIGHTING);

        reasons.reset();
        reasons.record(ProjectorResampleReasons.REMOTE_PENDING);

        assertEquals(ProjectorResampleReasons.REMOTE_PENDING, reasons.mask());
        assertEquals("remotePending:1", reasons.describe());
    }

    @Test
    public void everyReasonHasADistinctBitAndName() {
        ProjectorResampleReasons reasons = new ProjectorResampleReasons();
        int all = 0;
        for (int bit = 0; bit < ProjectorResampleReasons.REASON_COUNT; bit++) {
            all |= 1 << bit;
        }

        reasons.record(all);

        assertEquals(all, reasons.mask());
        assertEquals("invalidated:1,destStaleRecursive:1,destStaleDirty:1,destOverBudget:1,localDirty:1,stableCadence:1,"
            + "presentation:1,remotePending:1,lighting:1,unresolvedOcclusion:1,camera:1,fullSend:1,holdsExposed:1", reasons.describe());
    }
}
