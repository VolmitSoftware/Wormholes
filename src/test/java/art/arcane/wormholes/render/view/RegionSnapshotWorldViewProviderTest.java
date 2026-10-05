package art.arcane.wormholes.render.view;

import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.network.view.RemoteViewCache;
import art.arcane.wormholes.render.ProjectionWorldChangeTracker;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionSnapshotWorldViewProviderTest {
    @Test
    void spatialCaptureGridDistributesLookupsAcrossConcurrentMapBins() {
        int[] bins = new int[512];
        for (int x = -8; x < 8; x++) {
            for (int z = -8; z < 8; z++) {
                int hash = Long.hashCode(RegionSnapshotWorldViewProvider.chunkLookupKey(x, z));
                bins[(hash ^ (hash >>> 16)) & (bins.length - 1)]++;
            }
        }
        int occupied = 0;
        int maximum = 0;
        for (int count : bins) {
            if (count > 0) {
                occupied++;
            }
            maximum = Math.max(maximum, count);
        }
        assertTrue(occupied > 128);
        assertTrue(maximum <= 4);
    }

    @Test
    void lookupIdentitySeparatesSignedCoordinatesAndWorldBorderColumns() {
        int[] coordinates = {-1_875_000, -1, 0, 1, 1_875_000};
        Set<Long> identities = new HashSet<Long>();
        for (int x : coordinates) {
            for (int z : coordinates) {
                long identity = RegionSnapshotWorldViewProvider.chunkLookupKey(x, z);
                assertEquals(identity, RegionSnapshotWorldViewProvider.chunkLookupKey(x, z));
                assertTrue(identities.add(Long.valueOf(identity)));
            }
        }
        assertEquals(25, identities.size());
    }

    @Test
    void keepsEntityStateStableWhenOnlyMotionChanges() {
        UUID id = UUID.randomUUID();
        EntityVisual first = visual(id, 1.0D, new byte[] {1, 2}, new byte[] {3});
        EntityVisual moved = visual(id, 9.0D, new byte[] {1, 2}, new byte[] {3});
        RemoteViewCache.RemoteProfile profile = new RemoteViewCache.RemoteProfile("Player", "texture", "signature");

        assertTrue(RegionSnapshotWorldViewProvider.sameEntityState(first, profile, moved, profile));
    }

    @Test
    void changesEntityStateForMetadataEquipmentMapOrProfileChanges() {
        UUID id = UUID.randomUUID();
        EntityVisual base = visual(id, 1.0D, new byte[] {1}, new byte[] {2});
        RemoteViewCache.RemoteProfile profile = new RemoteViewCache.RemoteProfile("Player", "texture", "signature");

        assertFalse(RegionSnapshotWorldViewProvider.sameEntityState(base, profile,
            visual(id, 1.0D, new byte[] {9}, new byte[] {2}), profile));
        assertFalse(RegionSnapshotWorldViewProvider.sameEntityState(base, profile,
            visual(id, 1.0D, new byte[] {1}, new byte[] {9}), profile));
        assertFalse(RegionSnapshotWorldViewProvider.sameEntityState(base, profile,
            visual(id, 1.0D, new byte[] {1}, new byte[] {2}, new byte[] {9}), profile));
        assertFalse(RegionSnapshotWorldViewProvider.sameEntityState(base, profile, base,
            new RemoteViewCache.RemoteProfile("Other", "texture", "signature")));
    }

    @Test
    void invalidatesOnlyChangedChunkAfterCapturedVersion() {
        ProjectionWorldChangeTracker tracker = new ProjectionWorldChangeTracker();
        UUID worldId = UUID.randomUUID();
        long capturedVersion = tracker.currentVersion();
        tracker.markChanged(worldId, 2 << 4, 3 << 4);

        assertTrue(RegionSnapshotWorldViewProvider.isChunkDirty(tracker, worldId, 2, 3, capturedVersion));
        assertFalse(RegionSnapshotWorldViewProvider.isChunkDirty(tracker, worldId, 1, 3, capturedVersion));
        assertFalse(RegionSnapshotWorldViewProvider.isChunkDirty(tracker, worldId, 2, 3, tracker.currentVersion()));
    }

    @Test
    void reusesBlockSnapshotUntilItsChunkChanges() {
        ProjectionWorldChangeTracker tracker = new ProjectionWorldChangeTracker();
        UUID worldId = UUID.randomUUID();
        long capturedVersion = tracker.currentVersion();

        assertTrue(RegionSnapshotWorldViewProvider.requiresBlockSnapshotRefresh(
            tracker, worldId, 2, 3, false, capturedVersion, 1_000L, 1_001L));
        assertFalse(RegionSnapshotWorldViewProvider.requiresBlockSnapshotRefresh(
            tracker, worldId, 2, 3, true, capturedVersion, 1_000L, 1_001L));

        tracker.markChanged(worldId, 2 << 4, 3 << 4);

        assertTrue(RegionSnapshotWorldViewProvider.requiresBlockSnapshotRefresh(
            tracker, worldId, 2, 3, true, capturedVersion, 1_000L, 1_001L));
    }

    @Test
    void refreshesBlockSnapshotWhenChangeTrackingIsUnavailable() {
        assertTrue(RegionSnapshotWorldViewProvider.requiresBlockSnapshotRefresh(
            null, UUID.randomUUID(), 2, 3, true, 4L, 1_000L, 1_001L));
    }

    @Test
    void refreshesBlockSnapshotAfterSafetyBackstop() {
        ProjectionWorldChangeTracker tracker = new ProjectionWorldChangeTracker();

        assertTrue(RegionSnapshotWorldViewProvider.requiresBlockSnapshotRefresh(
            tracker, UUID.randomUUID(), 2, 3, true, tracker.currentVersion(), 1_000L, 61_000L));
    }

    private static EntityVisual visual(UUID id, double x, byte[] metadata, byte[] equipment) {
        return visual(id, x, metadata, equipment, new byte[0]);
    }

    private static EntityVisual visual(UUID id, double x, byte[] metadata, byte[] equipment, byte[] mapData) {
        return EntityVisual.full(id, "minecraft:zombie", x, 64.0D, 0.0D, 1.8D,
            0.0D, 0.0D, 1.0D, 0.0F, 0.0F, 0.0D, 0.0D, 0.0D, true,
            "", "", "", null, null, metadata, equipment, mapData, 0);
    }
}
