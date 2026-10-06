package art.arcane.wormholes.config;

import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.render.ProjectionGazeScheduler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ProjectionGazeOptionsTest {
    @Test
    void configuredValuesInsideTheirBoundsPassThrough() {
        ProjectionConfig config = new ProjectionConfig();
        config.gazeFovDegrees = 90.0D;
        config.gazeLookaheadTicks = 4;
        config.gazeMaxStarveTicks = 40;
        assertEquals(new ProjectionGazeScheduler.Options(90.0D, 4, 40), ProjectionGazeOptions.from(config));
    }

    @Test
    void outOfRangeValuesAreClampedAndANonFiniteFieldOfViewFallsBack() {
        ProjectionConfig config = new ProjectionConfig();
        config.gazeFovDegrees = Double.NaN;
        config.gazeLookaheadTicks = 99;
        config.gazeMaxStarveTicks = 0;
        assertEquals(new ProjectionGazeScheduler.Options(110.0D, 20, 1), ProjectionGazeOptions.from(config));
        config.gazeFovDegrees = 400.0D;
        config.gazeLookaheadTicks = -3;
        config.gazeMaxStarveTicks = 5_000;
        assertEquals(new ProjectionGazeScheduler.Options(170.0D, 0, 200), ProjectionGazeOptions.from(config));
    }
}
