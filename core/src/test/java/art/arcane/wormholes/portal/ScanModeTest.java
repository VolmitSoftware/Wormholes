package art.arcane.wormholes.portal;

import art.arcane.optics.scan.ScanMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class ScanModeTest {
    @Test
    void panopticScansWithoutCullingOrObserverOcclusion() {
        assertEquals(new ScanMode(false, false), ProjectionRenderMode.PANOPTIC.scanMode());
    }

    @Test
    void venticularCullsBuriedCellsAndOccludesByObserver() {
        assertEquals(new ScanMode(true, true), ProjectionRenderMode.VENTICULAR.scanMode());
    }

    @Test
    void scanModeIsResolvedOncePerRenderMode() {
        for (ProjectionRenderMode mode : ProjectionRenderMode.values()) {
            assertSame(mode.scanMode(), mode.scanMode());
        }
    }
}
