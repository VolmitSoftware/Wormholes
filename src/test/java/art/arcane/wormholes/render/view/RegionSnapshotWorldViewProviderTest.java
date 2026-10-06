package art.arcane.wormholes.render.view;

import art.arcane.optics.entity.EntityProfile;
import art.arcane.optics.entity.EntitySnapshot;
import art.arcane.optics.view.WorldChangeTracker;

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
        EntitySnapshot first = visual(id, 1.0D, new byte[] {1, 2}, new byte[] {3});
        EntitySnapshot moved = visual(id, 9.0D, new byte[] {1, 2}, new byte[] {3});
        EntityProfile profile = new EntityProfile("Player", "texture", "signature");

        assertTrue(RegionSnapshotWorldViewProvider.sameEntityState(first, profile, moved, profile));
    }

    @Test
    void changesEntityStateForMetadataEquipmentMapOrProfileChanges() {
        UUID id = UUID.randomUUID();
        EntitySnapshot base = visual(id, 1.0D, new byte[] {1}, new byte[] {2});
        EntityProfile profile = new EntityProfile("Player", "texture", "signature");

        assertFalse(RegionSnapshotWorldViewProvider.sameEntityState(base, profile,
            visual(id, 1.0D, new byte[] {9}, new byte[] {2}), profile));
        assertFalse(RegionSnapshotWorldViewProvider.sameEntityState(base, profile,
            visual(id, 1.0D, new byte[] {1}, new byte[] {9}), profile));
        assertFalse(RegionSnapshotWorldViewProvider.sameEntityState(base, profile,
            visual(id, 1.0D, new byte[] {1}, new byte[] {2}, new byte[] {9}), profile));
        assertFalse(RegionSnapshotWorldViewProvider.sameEntityState(base, profile, base,
            new EntityProfile("Other", "texture", "signature")));
    }

    @Test
    void invalidatesOnlyChangedChunkAfterCapturedVersion() {
        WorldChangeTracker tracker = new WorldChangeTracker();
        UUID worldId = UUID.randomUUID();
        long capturedVersion = tracker.currentVersion();
        tracker.markChanged(worldId, 2 << 4, 3 << 4);

        assertTrue(RegionSnapshotWorldViewProvider.isChunkDirty(tracker, worldId, 2, 3, capturedVersion));
        assertFalse(RegionSnapshotWorldViewProvider.isChunkDirty(tracker, worldId, 1, 3, capturedVersion));
        assertFalse(RegionSnapshotWorldViewProvider.isChunkDirty(tracker, worldId, 2, 3, tracker.currentVersion()));
    }

    @Test
    void reusesBlockSnapshotUntilItsChunkChanges() {
        WorldChangeTracker tracker = new WorldChangeTracker();
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
        WorldChangeTracker tracker = new WorldChangeTracker();

        assertTrue(RegionSnapshotWorldViewProvider.requiresBlockSnapshotRefresh(
            tracker, UUID.randomUUID(), 2, 3, true, tracker.currentVersion(), 1_000L, 61_000L));
    }

    private static EntitySnapshot visual(UUID id, double x, byte[] metadata, byte[] equipment) {
        return visual(id, x, metadata, equipment, new byte[0]);
    }

    private static EntitySnapshot visual(UUID id, double x, byte[] metadata, byte[] equipment, byte[] mapData) {
        return EntitySnapshot.full(id, "minecraft:zombie", x, 64.0D, 0.0D, 1.8D,
            0.0D, 0.0D, 1.0D, 0.0F, 0.0F, 0.0D, 0.0D, 0.0D, true,
            "", "", "", null, null, metadata, equipment, mapData, 0);
    }
}
