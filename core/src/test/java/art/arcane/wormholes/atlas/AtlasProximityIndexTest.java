package art.arcane.wormholes.atlas;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtlasProximityIndexTest {
    @Test
    void findsAcrossNegativeChunkBoundariesAndKeepsWorldsSeparate() {
        UUID near = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        AtlasProximityIndex<String> index = new AtlasProximityIndex<>();
        index.rebuild(List.of(new AtlasProximityIndex.Anchor<>(near, "overworld", -17, 64, -1),
            new AtlasProximityIndex.Anchor<>(other, "nether", -17, 64, -1)));
        assertEquals(List.of(near), index.near("overworld", -15, 64, -1, 3));
        assertTrue(index.near("overworld", -15, 68, -1, 3).isEmpty());
        assertEquals(2, index.portalCount());
        index.clear();
        assertTrue(index.near("overworld", -15, 64, -1, 3).isEmpty());
    }

    @Test
    void rebuildingReplacesRemovedAnchors() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        AtlasProximityIndex<String> index = new AtlasProximityIndex<>();
        index.rebuild(List.of(new AtlasProximityIndex.Anchor<>(first, "world", 0, 64, 0)));
        index.rebuild(List.of(new AtlasProximityIndex.Anchor<>(second, "world", 0, 64, 0)));
        assertEquals(List.of(second), index.near("world", 0, 64, 0, 0));
    }
}
