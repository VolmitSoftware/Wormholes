package art.arcane.wormholes;

import art.arcane.wormholes.service.DebugTelemetryService;
import art.arcane.wormholes.service.MetricsRuntime;
import art.arcane.wormholes.service.StatsSnapshotWriter;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

final class WormholesDiagnosticsRuntime {
    private static final int BSTATS_PLUGIN_ID = 27950;

    private final Wormholes plugin;
    private MetricsRuntime metricsRuntime;
    private DebugTelemetryService debugTelemetryService;
    private StatsSnapshotWriter statsSnapshotWriter;
    private Instant pluginStartedAt;

    WormholesDiagnosticsRuntime(Wormholes plugin) {
        this.plugin = Objects.requireNonNull(plugin);
    }

    void start() {
        this.metricsRuntime = MetricsRuntime.start(plugin, BSTATS_PLUGIN_ID);
        this.pluginStartedAt = Instant.now();
        startDebugTelemetryService();
        startStatsSnapshotWriter();
    }

    void toggleDebugTelemetry(String actor) {
        DebugTelemetryService service = debugTelemetryService;
        if (service != null) {
            service.toggle(actor);
            return;
        }
        boolean enabled = !Settings.DEBUG;
        Settings.DEBUG = enabled;
        plugin.getLogger().info("[debug] verbose logging " + (enabled ? "ENABLED" : "DISABLED") + " by " + actor + "; telemetry reporter unavailable");
    }

    private void startDebugTelemetryService() {
        if (debugTelemetryService != null) {
            return;
        }
        DebugTelemetryService service = new DebugTelemetryService(plugin);
        service.start();
        debugTelemetryService = service;
    }

    void stopDebugTelemetryService() {
        DebugTelemetryService service = debugTelemetryService;
        debugTelemetryService = null;
        if (service != null) {
            service.stop();
        }
    }

    void synchronizeDebugTelemetrySetting() {
        DebugTelemetryService service = debugTelemetryService;
        if (service != null) {
            service.onSettingsReloaded();
        }
    }

    void restartStatsSnapshotWriter() {
        stopStatsSnapshotWriter();
        startStatsSnapshotWriter();
    }

    private void startStatsSnapshotWriter() {
        if (statsSnapshotWriter != null) {
            return;
        }
        if (Wormholes.settings == null) {
            return;
        }
        if (Wormholes.settings.getNetwork() == null || Wormholes.settings.getNetwork().stats == null) {
            return;
        }
        if (!Wormholes.settings.getNetwork().stats.enabled) {
            return;
        }
        int intervalSec = Math.max(1, Wormholes.settings.getNetwork().stats.intervalSec);
        Path output = resolveStatsOutputPath(plugin.getDataFolder().toPath(), Wormholes.settings.getNetwork().stats.pathOverride);
        StatsSnapshotWriter writer = StatsSnapshotWriter.forRuntime(
            plugin,
            Wormholes.networkManager,
            Wormholes.viewServer,
            Wormholes.traversalService,
            output,
            Duration.ofSeconds(intervalSec),
            pluginStartedAt == null ? Instant.now() : pluginStartedAt
        );
        writer.start();
        statsSnapshotWriter = writer;
    }

    void stopStatsSnapshotWriter() {
        StatsSnapshotWriter writer = statsSnapshotWriter;
        statsSnapshotWriter = null;
        if (writer != null) {
            writer.stop();
        }
    }

    StatsSnapshotWriter statsSnapshotWriter() {
        return statsSnapshotWriter;
    }

    static Path resolveStatsOutputPath(Path dataFolder, String override) {
        if (override == null || override.isBlank()) {
            return dataFolder.resolve("wormholes-stats.txt");
        }
        Path path = Path.of(override);
        if (path.isAbsolute()) {
            return path;
        }
        return dataFolder.resolve(override);
    }

    void shutdownMetrics() {
        MetricsRuntime activeMetrics = metricsRuntime;
        metricsRuntime = null;
        if (activeMetrics != null) {
            activeMetrics.shutdown();
        }
    }
}
