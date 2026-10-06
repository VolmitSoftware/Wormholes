package art.arcane.wormholes.config;

import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.render.ProjectionGazeScheduler;

public final class ProjectionGazeOptions {
    private ProjectionGazeOptions() {
    }

    public static ProjectionGazeScheduler.Options from(ProjectionConfig config) {
        double fov = Double.isFinite(config.gazeFovDegrees) ? Math.clamp(config.gazeFovDegrees, 30.0D, 170.0D) : 110.0D;
        return new ProjectionGazeScheduler.Options(fov, Math.clamp(config.gazeLookaheadTicks, 0, 20), Math.clamp(config.gazeMaxStarveTicks, 1, 200));
    }
}
