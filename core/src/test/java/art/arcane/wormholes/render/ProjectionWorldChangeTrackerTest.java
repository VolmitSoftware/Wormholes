package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import it.unimi.dsi.fastutil.longs.LongArrayList;

import org.junit.jupiter.api.Test;

public final class ProjectionWorldChangeTrackerTest {
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID OTHER_WORLD = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    public void markChangedInsideWindowReportsDirty() {
        ProjectionWorldChangeTracker tracker = new ProjectionWorldChangeTracker();
        tracker.markChanged(WORLD, 35, -18);

        assertTrue(tracker.dirtySince(WORLD, 0, -4, 4, 0, 0L));
        assertFalse(tracker.dirtySince(WORLD, 10, 10, 14, 14, 0L));
        assertFalse(tracker.dirtySince(OTHER_WORLD, 0, -4, 4, 0, 0L));
    }

    @Test
    public void sinceCurrentVersionReportsClean() {
        ProjectionWorldChangeTracker tracker = new ProjectionWorldChangeTracker();
        tracker.markChanged(WORLD, 35, -18);
        tracker.markChanged(WORLD, 100, 100);
        long current = tracker.currentVersion();

        assertFalse(tracker.dirtySince(WORLD, -100, -100, 100, 100, current));
        assertTrue(tracker.dirtySince(WORLD, -100, -100, 100, 100, current - 1L));
    }

    @Test
    public void repeatedSameChunkMarksStayDetectableAfterResample() {
        ProjectionWorldChangeTracker tracker = new ProjectionWorldChangeTracker();
        tracker.markChanged(WORLD, 35, -18);
        long afterFirst = tracker.currentVersion();
        tracker.markChanged(WORLD, 36, -17);

        assertEquals(2L, tracker.currentVersion());
        assertTrue(tracker.dirtySince(WORLD, 0, -4, 4, 0, afterFirst));
    }

    @Test
    public void overflowClearFloorsAllQueries() {
        ProjectionWorldChangeTracker tracker = new ProjectionWorldChangeTracker();
        for (int i = 0; i <= 8200; i++) {
            tracker.markChanged(WORLD, i << 4, 0);
        }

        assertTrue(tracker.dirtySince(WORLD, 500_000, 500_000, 500_001, 500_001, 1L));
    }

    @Test
    public void blockChangesStampTheChunkAndNotifyListenersWithTheBlockPosition() {
        ProjectionWorldChangeTracker tracker = new ProjectionWorldChangeTracker();
        List<String> events = new ArrayList<String>();
        ProjectionWorldChangeTracker.ChangeListener listener = recording(events);
        tracker.addListener(listener);
        tracker.addListener(listener);

        tracker.markChanged(WORLD, 35, -70, -18);
        tracker.markChanged(WORLD, 100, 100);
        tracker.clearWorld(OTHER_WORLD);

        assertTrue(tracker.dirtySince(WORLD, 2, -2, 2, -2, 0L));
        assertEquals(List.of("block 35,-70,-18", "column 6,6", "cleared " + OTHER_WORLD), events);

        tracker.removeListener(listener);
        tracker.markChanged(WORLD, 1, 2, 3);
        assertEquals(3, events.size());
    }

    @Test
    public void collectingDirtyChunksReportsOnlyNewerChangesInsideTheRect() {
        ProjectionWorldChangeTracker tracker = new ProjectionWorldChangeTracker();
        tracker.markChanged(WORLD, 35, -18);
        long since = tracker.currentVersion();
        tracker.markChanged(WORLD, 20, 5);
        tracker.markChanged(WORLD, 400, 400);
        tracker.markChanged(OTHER_WORLD, 20, 5);
        LongArrayList collected = new LongArrayList();

        assertTrue(tracker.collectDirtySince(WORLD, -2, -2, 4, 4, since, collected));

        assertEquals(List.of(Long.valueOf(ProjectionWorldChangeTracker.chunkKey(1, 0))), collected);
    }

    @Test
    public void collectingAfterAnOverflowReportsTheWholeRectDirty() {
        ProjectionWorldChangeTracker tracker = new ProjectionWorldChangeTracker();
        for (int i = 0; i <= 8200; i++) {
            tracker.markChanged(WORLD, i << 4, 0);
        }

        assertFalse(tracker.collectDirtySince(WORLD, 500_000, 500_000, 500_001, 500_001, 1L, new LongArrayList()));
    }

    @Test
    public void clearWorldResetsTracking() {
        ProjectionWorldChangeTracker tracker = new ProjectionWorldChangeTracker();
        tracker.markChanged(WORLD, 35, -18);
        tracker.clearWorld(WORLD);

        assertFalse(tracker.dirtySince(WORLD, 0, -4, 4, 0, 0L));
    }

    private static ProjectionWorldChangeTracker.ChangeListener recording(List<String> events) {
        return new ProjectionWorldChangeTracker.ChangeListener() {
            @Override
            public void blockChanged(UUID worldId, long blockKey) {
                events.add("block " + ProjectionCellKey.unpackX(blockKey) + "," + ProjectionCellKey.unpackY(blockKey)
                    + "," + ProjectionCellKey.unpackZ(blockKey));
            }

            @Override
            public void columnChanged(UUID worldId, int chunkX, int chunkZ) {
                events.add("column " + chunkX + "," + chunkZ);
            }

            @Override
            public void worldCleared(UUID worldId) {
                events.add("cleared " + worldId);
            }
        };
    }
}
