package art.arcane.wormholes;

import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.service.MetricsRuntime;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

final class WormholesDiagnosticsRuntimeTest {
    private static final Path DATA_FOLDER = Path.of("/servers/alpha/plugins/Wormholes");

    @Test
    void metricsFollowSettingChangesAndRestartOnlyForExternalConfigurationChanges() {
        WormholesSettings previous = Wormholes.settings;
        Wormholes plugin = mock(Wormholes.class);
        MetricsRuntime first = mock(MetricsRuntime.class);
        MetricsRuntime second = mock(MetricsRuntime.class);
        MetricsRuntime third = mock(MetricsRuntime.class);
        try (MockedStatic<MetricsRuntime> factory = mockStatic(MetricsRuntime.class)) {
            factory.when(() -> MetricsRuntime.start(plugin, 33193)).thenReturn(first, second, third);
            WormholesDiagnosticsRuntime diagnostics = new WormholesDiagnosticsRuntime(plugin);
            Wormholes.settings = settings(true);
            diagnostics.synchronizeMetricsSetting(false);
            diagnostics.synchronizeMetricsSetting(false);
            factory.verify(() -> MetricsRuntime.start(plugin, 33193), times(1));
            Wormholes.settings = settings(false);
            diagnostics.synchronizeMetricsSetting(false);
            verify(first).shutdown();
            Wormholes.settings = settings(true);
            diagnostics.synchronizeMetricsSetting(false);
            diagnostics.synchronizeMetricsSetting(true);
            verify(second).shutdown();
            factory.verify(() -> MetricsRuntime.start(plugin, 33193), times(3));
            diagnostics.shutdownMetrics();
            verify(third).shutdown();
        } finally {
            Wormholes.settings = previous;
        }
    }

    private WormholesSettings settings(boolean metrics) {
        return WormholesSettings.loadSnapshot(("schema = 3\nmetrics = " + metrics + "\n")
            .getBytes(StandardCharsets.UTF_8));
    }

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
