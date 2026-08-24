package art.arcane.wormholes;

import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.service.DebugTelemetryService;
import art.arcane.wormholes.service.MetricsRuntime;
import art.arcane.wormholes.service.StatsSnapshotWriter;

import org.bstats.charts.AdvancedPie;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

final class WormholesDiagnosticsRuntime {
    // bstats.org plugin id
    private static final int BSTATS_PLUGIN_ID = 33193;

    private final Wormholes plugin;
    private MetricsRuntime metricsRuntime;
    private DebugTelemetryService debugTelemetryService;
    private StatsSnapshotWriter statsSnapshotWriter;
    private Instant pluginStartedAt;

    WormholesDiagnosticsRuntime(Wormholes plugin) {
        this.plugin = Objects.requireNonNull(plugin);
    }

    void start() {
        WormholesSettings activeSettings = Wormholes.settings;
        if (BSTATS_PLUGIN_ID > 0 && activeSettings != null && activeSettings.isMetrics()) {
            MetricsRuntime runtime = MetricsRuntime.start(plugin, BSTATS_PLUGIN_ID);
            registerMetricsCharts(runtime);
            this.metricsRuntime = runtime;
        }
        this.pluginStartedAt = Instant.now();
        startDebugTelemetryService();
        startStatsSnapshotWriter();
    }

    // Chart callables run on the bStats daemon thread (never main, even on Folia): read only volatile
    // statics and immutable snapshots, and return null so the cycle is skipped when a manager is down.
    private void registerMetricsCharts(MetricsRuntime metrics) {
        metrics.addChart(new SingleLineChart("total_portals", () -> {
            PortalManager portals = Wormholes.portalManager;
            return portals == null ? null : Integer.valueOf(portals.getTotalPortalCount());
        }));

        metrics.addChart(new AdvancedPie("portals_by_type", () -> {
            PortalManager portals = Wormholes.portalManager;
            if (portals == null) {
                return null;
            }
            Map<String, Integer> counts = new HashMap<String, Integer>();
            for (ILocalPortal portal : portals.getLocalPortals()) {
                PortalType type = portal.getType();
                if (type == null) {
                    continue;
                }
                counts.merge(type.name().toLowerCase(Locale.ROOT), Integer.valueOf(1), Integer::sum);
            }
            return counts;
        }));

        metrics.addChart(new SimplePie("cross_server", () -> {
            WormholesSettings activeSettings = Wormholes.settings;
            NetworkConfig network = activeSettings == null ? null : activeSettings.getNetwork();
            return network == null ? null : String.valueOf(network.enabled);
        }));

        metrics.addChart(new SimplePie("wire_compression", () -> {
            NetworkManager network = Wormholes.networkManager;
            return network == null ? null : String.valueOf(network.compressionEnabled());
        }));

        metrics.addChart(new SingleLineChart("connected_peers", () -> {
            NetworkManager network = Wormholes.networkManager;
            return network == null ? null : Integer.valueOf(network.connectedPeers());
        }));
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
