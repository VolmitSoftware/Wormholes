package art.arcane.wormholes.network.view;

import art.arcane.wormholes.network.client.SectionBiomes;
import art.arcane.wormholes.util.AxisAlignedBB;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class NativeViewBoundsTest {
    @Test
    void requestedMeshExtentIncludesAlignedSectionEdgesAndBiomeHalo() {
        AxisAlignedBB aperture = new AxisAlignedBB(-0.5D, 2.5D, 70, 74, -200.5D, -200.5D);
        for (int distance : new int[] {32, 160, 512}) {
            ViewBox box = ViewCaptureBounds.computeMesh(aperture, distance, -64, 320);
            assertTrue(box.minX() <= -distance - 16 - SectionBiomes.PADDING);
            assertTrue(box.maxX() >= distance + 3 + 16 + SectionBiomes.PADDING);
            assertTrue(box.minZ() <= -201 - distance - 16 - SectionBiomes.PADDING);
            assertTrue(box.maxZ() >= -200 + distance + 16 + SectionBiomes.PADDING);
        }
    }

    @Test
    void nativeExtentRespectsWorldHeightAndMaximumRenderDistance() {
        AxisAlignedBB aperture = new AxisAlignedBB(0, 1, 300, 304, 0, 1);
        ViewBox box = ViewCaptureBounds.computeMesh(aperture, 1024, -64, 320);
        assertEquals(-64, box.minY());
        assertEquals(319, box.maxY());
        assertEquals(544, box.maxX() - 1);
    }
}
