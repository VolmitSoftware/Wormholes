package art.arcane.optics.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import art.arcane.optics.math.CellKeys;

public final class ProjectorRemoteFootprintTest {
    @Test
    public void changesInsideARecordedSectionAffectTheFootprint() {
        ProjectorRemoteFootprint footprint = new ProjectorRemoteFootprint();
        footprint.record(20, 70, -5);

        assertTrue(footprint.affectsBlock(31, 79, -16));
        assertTrue(footprint.affectsBlock(16, 64, -1));
        assertFalse(footprint.affectsBlock(20, 40, -5));
        assertFalse(footprint.affectsBlock(60, 70, -5));
    }

    @Test
    public void changesWithinTheBuriedMarginOfANeighbouringSectionAffectTheFootprint() {
        ProjectorRemoteFootprint footprint = new ProjectorRemoteFootprint();
        footprint.record(16, 64, 0);

        assertTrue(footprint.affectsBlock(14, 64, 0));
        assertTrue(footprint.affectsBlock(16, 62, 0));
        assertTrue(footprint.affectsBlock(16, 64, -2));
        assertFalse(footprint.affectsBlock(13, 64, 0));
        assertFalse(footprint.affectsBlock(16, 61, 0));
        assertFalse(footprint.affectsBlock(16, 64, -3));
    }

    @Test
    public void columnLoadsAffectRecordedAndAdjacentColumns() {
        ProjectorRemoteFootprint footprint = new ProjectorRemoteFootprint();
        assertFalse(footprint.affectsColumn(0, 0));
        footprint.recordCell(CellKeys.pack(40, -30, 40));

        assertTrue(footprint.affectsColumn(2, 2));
        assertTrue(footprint.affectsColumn(3, 1));
        assertFalse(footprint.affectsColumn(4, 2));
        assertEquals(1, footprint.queryMinChunkX());
        assertEquals(3, footprint.queryMaxChunkZ());
    }

    @Test
    public void nestedReadsSurviveUnionAndResetOnClear() {
        ProjectorRemoteFootprint first = new ProjectorRemoteFootprint();
        ProjectorRemoteFootprint second = new ProjectorRemoteFootprint();
        second.markNested();

        first.addAll(second);

        assertTrue(first.nested());
        first.clear();
        assertFalse(first.nested());
    }

    @Test
    public void unionKeepsBothFootprintsAndClearForgetsEverything() {
        ProjectorRemoteFootprint first = new ProjectorRemoteFootprint();
        ProjectorRemoteFootprint second = new ProjectorRemoteFootprint();
        first.record(0, 64, 0);
        second.record(100, 64, 100);
        second.record(100, 64, 100);
        second.record(101, 65, 100);

        first.addAll(second);

        assertEquals(2, first.size());
        assertTrue(first.affectsBlock(0, 64, 0));
        assertTrue(first.affectsBlock(100, 64, 100));
        first.clear();
        assertTrue(first.isEmpty());
        assertFalse(first.affectsBlock(0, 64, 0));
        first.record(0, 64, 0);
        assertTrue(first.affectsBlock(0, 64, 0));
    }
}
