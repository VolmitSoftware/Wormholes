package art.arcane.optics.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import it.unimi.dsi.fastutil.longs.LongArrayList;

import org.junit.jupiter.api.Test;
import art.arcane.optics.math.CellKeys;

public final class ProjectionWorldChangeTrackerTest {
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID OTHER_WORLD = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    public void signedWorldBorderColumnsRetainDirtyVersionsAndPackedCollectionKeys() {
        WorldChangeTracker tracker = new WorldChangeTracker();
        int[] coordinates = {-1_875_000, -1, 0, 1, 1_875_000};
        for (int x : coordinates) {
            for (int z : coordinates) {
                long before = tracker.currentVersion();
                tracker.markChanged(WORLD, x << 4, z << 4);
                assertTrue(tracker.dirtySince(WORLD, x, z, x, z, before));
                assertFalse(tracker.dirtySince(WORLD, x, z, x, z, tracker.currentVersion()));
                assertFalse(tracker.dirtySince(OTHER_WORLD, x, z, x, z, before));
                LongArrayList dirty = new LongArrayList();
                assertTrue(tracker.collectDirtySince(WORLD, x, z, x, z, before, dirty));
                assertEquals(List.of(Long.valueOf(CellKeys.chunkKey(x, z))), dirty);
            }
        }
        tracker.clearWorld(WORLD);
        for (int x : coordinates) {
            for (int z : coordinates) {
                assertFalse(tracker.dirtySince(WORLD, x, z, x, z, 0));
            }
        }
    }

    @Test
    public void markChangedInsideWindowReportsDirty() {
        WorldChangeTracker tracker = new WorldChangeTracker();
        tracker.markChanged(WORLD, 35, -18);

        assertTrue(tracker.dirtySince(WORLD, 0, -4, 4, 0, 0L));
        assertFalse(tracker.dirtySince(WORLD, 10, 10, 14, 14, 0L));
        assertFalse(tracker.dirtySince(OTHER_WORLD, 0, -4, 4, 0, 0L));
    }

    @Test
    public void sinceCurrentVersionReportsClean() {
        WorldChangeTracker tracker = new WorldChangeTracker();
        tracker.markChanged(WORLD, 35, -18);
        tracker.markChanged(WORLD, 100, 100);
        long current = tracker.currentVersion();

        assertFalse(tracker.dirtySince(WORLD, -100, -100, 100, 100, current));
        assertTrue(tracker.dirtySince(WORLD, -100, -100, 100, 100, current - 1L));
    }

    @Test
    public void repeatedSameChunkMarksStayDetectableAfterResample() {
        WorldChangeTracker tracker = new WorldChangeTracker();
        tracker.markChanged(WORLD, 35, -18);
        long afterFirst = tracker.currentVersion();
        tracker.markChanged(WORLD, 36, -17);

        assertEquals(2L, tracker.currentVersion());
        assertTrue(tracker.dirtySince(WORLD, 0, -4, 4, 0, afterFirst));
    }

    @Test
    public void overflowClearFloorsAllQueries() {
        WorldChangeTracker tracker = new WorldChangeTracker();
        for (int i = 0; i <= 8200; i++) {
            tracker.markChanged(WORLD, i << 4, 0);
        }

        assertTrue(tracker.dirtySince(WORLD, 500_000, 500_000, 500_001, 500_001, 1L));
    }

    @Test
    public void blockChangesStampTheChunkAndNotifyListenersWithTheBlockPosition() {
        WorldChangeTracker tracker = new WorldChangeTracker();
        List<String> events = new ArrayList<String>();
        WorldChangeListener listener = recording(events);
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
        WorldChangeTracker tracker = new WorldChangeTracker();
        tracker.markChanged(WORLD, 35, -18);
        long since = tracker.currentVersion();
        tracker.markChanged(WORLD, 20, 5);
        tracker.markChanged(WORLD, 400, 400);
        tracker.markChanged(OTHER_WORLD, 20, 5);
        LongArrayList collected = new LongArrayList();

        assertTrue(tracker.collectDirtySince(WORLD, -2, -2, 4, 4, since, collected));

        assertEquals(List.of(Long.valueOf(CellKeys.chunkKey(1, 0))), collected);
    }

    @Test
    public void collectingAfterAnOverflowReportsTheWholeRectDirty() {
        WorldChangeTracker tracker = new WorldChangeTracker();
        for (int i = 0; i <= 8200; i++) {
            tracker.markChanged(WORLD, i << 4, 0);
        }

        assertFalse(tracker.collectDirtySince(WORLD, 500_000, 500_000, 500_001, 500_001, 1L, new LongArrayList()));
    }

    @Test
    public void clearWorldResetsTracking() {
        WorldChangeTracker tracker = new WorldChangeTracker();
        tracker.markChanged(WORLD, 35, -18);
        tracker.clearWorld(WORLD);

        assertFalse(tracker.dirtySince(WORLD, 0, -4, 4, 0, 0L));
    }

    @Test
    public void unaffectedThroughSkipsChangesTheFilterRejects() {
        WorldChangeTracker tracker = new WorldChangeTracker();
        tracker.markChanged(WORLD, 35, 64, -18);
        tracker.markChanged(WORLD, 36, 64, -18);
        List<String> seen = new ArrayList<String>();

        long through = tracker.unaffectedThrough(WORLD, 0, -4, 4, 0, 0L, filter(seen, false));

        assertEquals(tracker.currentVersion(), through);
        assertEquals(List.of("block 36,64,-18", "block 35,64,-18"), seen);
    }

    @Test
    public void unaffectedThroughReportsAChangeTheFilterAccepts() {
        WorldChangeTracker tracker = new WorldChangeTracker();
        tracker.markChanged(WORLD, 35, 64, -18);

        assertEquals(WorldChangeTracker.AFFECTED,
            tracker.unaffectedThrough(WORLD, 0, -4, 4, 0, 0L, filter(new ArrayList<String>(), true)));
    }

    @Test
    public void unaffectedThroughIgnoresChangesOutsideTheRectAndInOtherWorlds() {
        WorldChangeTracker tracker = new WorldChangeTracker();
        tracker.markChanged(WORLD, 400, 64, 400);
        tracker.markChanged(OTHER_WORLD, 35, 64, -18);
        List<String> seen = new ArrayList<String>();

        assertEquals(tracker.currentVersion(), tracker.unaffectedThrough(WORLD, 0, -4, 4, 0, 0L, filter(seen, true)));
        assertTrue(seen.isEmpty());
    }

    @Test
    public void unaffectedThroughOnlyVisitsChangesNewerThanTheVersion() {
        WorldChangeTracker tracker = new WorldChangeTracker();
        tracker.markChanged(WORLD, 35, 64, -18);
        long since = tracker.currentVersion();
        tracker.markChanged(WORLD, 20, 70, 5);
        List<String> seen = new ArrayList<String>();

        long through = tracker.unaffectedThrough(WORLD, -2, -2, 4, 4, since, filter(seen, false));
        long again = tracker.unaffectedThrough(WORLD, -2, -2, 4, 4, through, filter(seen, false));

        assertEquals(tracker.currentVersion(), again);
        assertEquals(List.of("block 20,70,5"), seen);
    }

    @Test
    public void unaffectedThroughConsultsTheColumnFilterForChunkMarks() {
        WorldChangeTracker tracker = new WorldChangeTracker();
        tracker.markChanged(WORLD, 35, -18);
        List<String> seen = new ArrayList<String>();

        assertEquals(WorldChangeTracker.AFFECTED, tracker.unaffectedThrough(WORLD, 0, -4, 4, 0, 0L, filter(seen, true)));
        assertEquals(List.of("column 2,-2"), seen);
    }

    @Test
    public void unaffectedThroughReportsDroppedHistoryAsAffected() {
        WorldChangeTracker tracker = new WorldChangeTracker();
        tracker.markChanged(WORLD, 35, 64, -18);
        long since = tracker.currentVersion();
        for (int i = 0; i <= WorldChangeTracker.CHANGE_LOG_CAPACITY; i++) {
            tracker.markChanged(WORLD, 10_000, 64, i);
        }

        assertEquals(WorldChangeTracker.AFFECTED,
            tracker.unaffectedThrough(WORLD, 0, -4, 4, 0, since, filter(new ArrayList<String>(), false)));
        assertEquals(tracker.currentVersion(),
            tracker.unaffectedThrough(WORLD, 0, -4, 4, 0, tracker.currentVersion() - 1L, filter(new ArrayList<String>(), false)));
    }

    @Test
    public void clearWorldReportsOlderVersionsAsAffected() {
        WorldChangeTracker tracker = new WorldChangeTracker();
        tracker.markChanged(WORLD, 35, 64, -18);
        long since = tracker.currentVersion();
        tracker.clearWorld(WORLD);

        assertEquals(WorldChangeTracker.AFFECTED,
            tracker.unaffectedThrough(WORLD, 0, -4, 4, 0, since, filter(new ArrayList<String>(), false)));
        assertEquals(tracker.currentVersion(),
            tracker.unaffectedThrough(WORLD, 0, -4, 4, 0, tracker.currentVersion(), filter(new ArrayList<String>(), false)));
    }

    @Test
    public void unaffectedThroughWithoutChangesReturnsTheCurrentVersion() {
        WorldChangeTracker tracker = new WorldChangeTracker();
        tracker.markChanged(OTHER_WORLD, 1, 2, 3);

        assertEquals(tracker.currentVersion(), tracker.unaffectedThrough(WORLD, 0, 0, 1, 1, 0L, filter(new ArrayList<String>(), true)));
    }

    private static WorldChangeFilter filter(List<String> seen, boolean affects) {
        return new WorldChangeFilter() {
            @Override
            public boolean affectsBlock(int x, int y, int z) {
                seen.add("block " + x + "," + y + "," + z);
                return affects;
            }

            @Override
            public boolean affectsColumn(int chunkX, int chunkZ) {
                seen.add("column " + chunkX + "," + chunkZ);
                return affects;
            }
        };
    }

    private static WorldChangeListener recording(List<String> events) {
        return new WorldChangeListener() {
            @Override
            public void blockChanged(UUID worldId, long blockKey) {
                events.add("block " + CellKeys.unpackX(blockKey) + "," + CellKeys.unpackY(blockKey)
                    + "," + CellKeys.unpackZ(blockKey));
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
