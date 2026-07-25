package art.arcane.wormholes;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class WormholesDiagnosticsRuntimeTest {
    private static final Path DATA_FOLDER = Path.of("/servers/alpha/plugins/Wormholes");

    @Test
    void resolveStatsOutputPathFallsBackToDefaultFileWhenOverrideIsNull() {
        assertEquals(
            DATA_FOLDER.resolve("wormholes-stats.txt"),
            WormholesDiagnosticsRuntime.resolveStatsOutputPath(DATA_FOLDER, null));
    }

    @Test
    void resolveStatsOutputPathFallsBackToDefaultFileWhenOverrideIsBlank() {
        assertEquals(
            DATA_FOLDER.resolve("wormholes-stats.txt"),
            WormholesDiagnosticsRuntime.resolveStatsOutputPath(DATA_FOLDER, "   "));
    }

    @Test
    void resolveStatsOutputPathKeepsAbsoluteOverrideUnchanged() {
        Path absolute = Path.of("/var/log/wormholes/stats.txt");
        assertEquals(
            absolute,
            WormholesDiagnosticsRuntime.resolveStatsOutputPath(DATA_FOLDER, absolute.toString()));
    }

    @Test
    void resolveStatsOutputPathResolvesRelativeOverrideUnderDataFolder() {
        assertEquals(
            DATA_FOLDER.resolve("reports/stats.txt"),
            WormholesDiagnosticsRuntime.resolveStatsOutputPath(DATA_FOLDER, "reports/stats.txt"));
    }
}
