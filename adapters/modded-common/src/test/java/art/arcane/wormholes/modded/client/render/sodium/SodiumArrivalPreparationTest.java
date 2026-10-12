package art.arcane.wormholes.modded.client.render.sodium;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SodiumArrivalPreparationTest {
    @Test
    public void incompleteAdmissionContinuesUntilBuildsAndUploadsFinish() {
        SodiumArrivalPreparation preparation = new SodiumArrivalPreparation();
        assertTrue(preparation.needed(17L, 4L));
        preparation.prepared(17L, 4L, false);
        assertTrue(preparation.needed(17L, 4L));
        preparation.prepared(17L, 4L, true);
        assertFalse(preparation.needed(17L, 4L));
    }

    @Test
    public void completionOnlySuppressesWorkForTheSameCameraCellAndTerrainRevision() {
        SodiumArrivalPreparation preparation = new SodiumArrivalPreparation();
        preparation.prepared(17L, 4L, true);
        assertTrue(preparation.needed(18L, 4L));
        assertTrue(preparation.needed(17L, 5L));
        assertFalse(preparation.needed(17L, 4L));
    }

    @Test
    public void anotherResidentWorldDoesNotInheritCompletedAdmission() {
        SodiumArrivalPreparation first = new SodiumArrivalPreparation();
        SodiumArrivalPreparation second = new SodiumArrivalPreparation();
        first.prepared(17L, 4L, true);
        assertFalse(first.needed(17L, 4L));
        assertTrue(second.needed(17L, 4L));
    }
}
