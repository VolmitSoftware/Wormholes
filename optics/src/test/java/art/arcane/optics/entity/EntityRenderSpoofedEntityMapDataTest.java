package art.arcane.optics.entity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;


public final class EntityRenderSpoofedEntityMapDataTest {
    @Test
    public void mapCacheComparesExactImmutableContentAndHandedness() {
        SpoofedEntity state = SpoofedEntity.create(false, false, false);
        byte[] pixels = new byte[MapSnapshot.PIXEL_COUNT];
        pixels[0] = 1;
        MapSnapshot original = new MapSnapshot(17, (byte) 2, true, false, pixels);
        MapSnapshot equalCopy = new MapSnapshot(17, (byte) 2, true, false, pixels);

        assertTrue(state.updateMapData(original, false));
        assertFalse(state.updateMapData(equalCopy, false));
        assertTrue(state.updateMapData(equalCopy, true));
        assertFalse(state.updateMapData(original, true));

        pixels[1] = 2;
        MapSnapshot changedPixels = new MapSnapshot(17, (byte) 2, true, false, pixels);
        assertTrue(state.updateMapData(changedPixels, true));
        assertTrue(state.updateMapData(
            new MapSnapshot(17, (byte) 3, true, false, pixels), true));
    }
}
