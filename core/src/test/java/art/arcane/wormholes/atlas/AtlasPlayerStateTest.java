package art.arcane.wormholes.atlas;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtlasPlayerStateTest {
    @Test
    void savingAnOlderSnapshotDoesNotClearConcurrentChanges() {
        AtlasPlayerState state = new AtlasPlayerState(UUID.randomUUID());
        state.discover(UUID.randomUUID());
        AtlasPlayerState.Snapshot saved = state.snapshot();
        state.setGuideTarget(UUID.randomUUID());
        state.markClean(saved);
        assertTrue(state.isDirty());
        state.markClean(state.snapshot());
        assertFalse(state.isDirty());
    }

    @Test
    void snapshotsRetainEveryFieldWithoutSharingMutableCollections() {
        UUID player = UUID.randomUUID();
        UUID portal = UUID.randomUUID();
        AtlasPlayerState state = new AtlasPlayerState(player);
        state.discover(portal);
        state.toggleFavorite(portal, 5);
        state.recordRecent(portal, 10);
        state.setGuideTarget(portal);
        AtlasPlayerState.Snapshot snapshot = state.snapshot();
        state.forget(portal);
        AtlasPlayerState restored = AtlasPlayerState.restore(snapshot);
        assertEquals(player, restored.playerId());
        assertTrue(restored.isDiscovered(portal));
        assertEquals(List.of(portal), restored.favorites());
        assertEquals(List.of(portal), restored.recents());
        assertEquals(portal, restored.guideTarget());
        assertFalse(restored.isDirty());
        assertTrue(state.isDirty());
    }
}
