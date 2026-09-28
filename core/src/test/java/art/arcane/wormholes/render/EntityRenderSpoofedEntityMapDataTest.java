package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.network.view.ProjectedMapData;

public final class EntityRenderSpoofedEntityMapDataTest {
    @Test
    public void mapCacheComparesExactImmutableContentAndHandedness() {
        EntityRenderSpoofedEntity state = EntityRenderSpoofedEntity.create(false, false, false);
        byte[] pixels = new byte[ProjectedMapData.PIXEL_COUNT];
        pixels[0] = 1;
        ProjectedMapData original = new ProjectedMapData(17, (byte) 2, true, false, pixels);
        ProjectedMapData equalCopy = new ProjectedMapData(17, (byte) 2, true, false, pixels);

        assertTrue(state.updateMapData(original, false));
        assertFalse(state.updateMapData(equalCopy, false));
        assertTrue(state.updateMapData(equalCopy, true));
        assertFalse(state.updateMapData(original, true));

        pixels[1] = 2;
        ProjectedMapData changedPixels = new ProjectedMapData(17, (byte) 2, true, false, pixels);
        assertTrue(state.updateMapData(changedPixels, true));
        assertTrue(state.updateMapData(
            new ProjectedMapData(17, (byte) 3, true, false, pixels), true));
    }
}
