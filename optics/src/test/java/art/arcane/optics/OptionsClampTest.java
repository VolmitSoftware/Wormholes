package art.arcane.optics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import art.arcane.optics.scan.CellScan;
import art.arcane.optics.scan.ResampleSchedule;
import art.arcane.optics.view.SectionCache;
import art.arcane.optics.volume.FrustumFit;
import art.arcane.optics.volume.GazeScheduler;

final class OptionsClampTest {
    @Test
    void scanSettingsInsideTheirBoundsPassThrough() {
        CellScan.ScanSettings settings = new CellScan.ScanSettings(3, 1.0D, 0.75D, true, true, 65_536, true);
        assertEquals(3, settings.recursiveDepth());
        assertEquals(1.0D, settings.revealMarginDegrees());
        assertEquals(0.75D, settings.aperturePadding());
        assertEquals(65_536, settings.maxHeldClaims());
    }

    @Test
    void scanSettingsClampToTheConfiguredRanges() {
        assertEquals(new CellScan.ScanSettings(3, 0.0D, 0.0D, false, true, 0, false),
            new CellScan.ScanSettings(-4, -2.0D, -1.0D, false, true, -9, false));
        assertEquals(new CellScan.ScanSettings(64, 15.0D, 8.0D, false, false, 50_000_000, true),
            new CellScan.ScanSettings(500, 90.0D, 30.0D, false, false, Integer.MAX_VALUE, true));
    }

    @Test
    void frustumFitOptionsClampToTheConfiguredRanges() {
        assertEquals(new FrustumFit.Options(250_000, 2.0D, 0.2D, 0.75D), new FrustumFit.Options(250_000, 2.0D, 0.2D, 0.75D));
        assertEquals(new FrustumFit.Options(0, 0.0D, 0.0D, 0.0D), new FrustumFit.Options(-1, -3.0D, -0.5D, -2.0D));
        assertEquals(new FrustumFit.Options(50_000_000, 16.0D, 1.0D, 8.0D),
            new FrustumFit.Options(Integer.MAX_VALUE, 40.0D, 3.0D, 12.0D));
    }

    @Test
    void cadenceClampsEveryIntervalToItsRange() {
        assertEquals(new ResampleSchedule.Cadence(1, 4, 4, 1), new ResampleSchedule.Cadence(1, 4, 4, 1));
        assertEquals(new ResampleSchedule.Cadence(1, 1, 1, 1), new ResampleSchedule.Cadence(0, -5, 0, -2));
        assertEquals(new ResampleSchedule.Cadence(20, 200, 40, 20), new ResampleSchedule.Cadence(99, 9_999, 99, 99));
    }

    @Test
    void gazeOptionsInsideTheirBoundsPassThrough() {
        GazeScheduler.Options options = new GazeScheduler.Options(90.0D, 4, 40);
        assertEquals(90.0D, options.fovDegrees());
        assertEquals(4, options.lookaheadTicks());
        assertEquals(40, options.maxStarveTicks());
    }

    @Test
    void gazeOptionsClampAndANonFiniteFieldOfViewFallsBack() {
        assertEquals(new GazeScheduler.Options(110.0D, 20, 1), new GazeScheduler.Options(Double.NaN, 99, 0));
        assertEquals(new GazeScheduler.Options(170.0D, 0, 200), new GazeScheduler.Options(400.0D, -3, 5_000));
        assertEquals(new GazeScheduler.Options(30.0D, 0, 1), new GazeScheduler.Options(1.0D, 0, 1));
        assertEquals(110.0D, new GazeScheduler.Options(Double.POSITIVE_INFINITY, 3, 20).fovDegrees());
    }

    @Test
    void sectionCacheLimitsKeepTheirEngineFloors() {
        SectionCache.Limits limits = new SectionCache.Limits(true, 0L, -3, 0);
        assertEquals(1L, limits.maxBytes());
        assertEquals(0, limits.chunksPerTick());
        assertEquals(1, limits.ttlTicks());
    }

    @Test
    void sectionCacheLimitsFromConfigurationClampToTheConfiguredRanges() {
        assertEquals(new SectionCache.Limits(true, 64L << 20, 16, 200), SectionCache.Limits.from(true, 64, 16, 200));
        assertEquals(new SectionCache.Limits(false, 1L << 20, 1, 20), SectionCache.Limits.from(false, 0, 0, 1));
        assertEquals(new SectionCache.Limits(true, 4096L << 20, 1024, 72_000), SectionCache.Limits.from(true, 10_000, 5_000, 1_000_000));
    }
}
