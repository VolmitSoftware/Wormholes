package art.arcane.wormholes.ops;

import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.OpsConfig;
import art.arcane.wormholes.hook.WormholesRegistrar;
import art.arcane.wormholes.hook.WormholesSubsystem;
import art.arcane.wormholes.localization.OpsMessages;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.ops.console.MetricsEndpoint;
import art.arcane.wormholes.ops.console.MetricsHistory;
import art.arcane.wormholes.ops.console.MetricsSource;
import art.arcane.wormholes.ops.console.RuntimeMetricsSource;
import art.arcane.wormholes.ops.backup.BackupService;
import art.arcane.wormholes.ops.webmap.BlueMapMarkers;
import art.arcane.wormholes.ops.webmap.DynmapMarkers;
import art.arcane.wormholes.ops.webmap.Pl3xMapMarkers;
import art.arcane.wormholes.ops.webmap.RuntimeMarkers;
import art.arcane.wormholes.ops.webmap.SquaremapMarkers;
import art.arcane.wormholes.ops.webmap.WebMapPublisher;
import art.arcane.wormholes.ops.webmap.WebMapService;
import art.arcane.wormholes.util.J;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Lifecycle for the ops lane: metrics endpoint, rolling history, backups, and web-map markers. */
public final class OpsSubsystem implements WormholesSubsystem {
    private static final int SAMPLE_INTERVAL_TICKS = 20;
    private static final int WEBMAP_INTERVAL_TICKS = 20;
    private static final int API_INTERVAL_TICKS = 20;

    private final MetricsSource metricsSource = new RuntimeMetricsSource();
    private final ApiBridge api = new ApiBridge();
    private MetricsEndpoint endpoint;
    private MetricsHistory history;
    private int samplerTaskId;
    private int backupTaskId;
    private int webmapTaskId;
    private int apiTaskId;
    private ConsoleSettings applied;
    private BackupSettings appliedBackup;
    private WebmapSettings appliedWebmap;
    private WebMapService webmap;

    private record ConsoleSettings(boolean enabled, String bind, int port, String token, int historyMinutes) {
        static ConsoleSettings of(OpsConfig.ConsoleConfig console) {
            return new ConsoleSettings(console.enabled, console.bind, console.port, console.token,
                console.historyMinutes);
        }
    }

    private record BackupSettings(boolean enabled, int intervalMinutes, int retain) {
        static BackupSettings of(OpsConfig.BackupConfig backup) {
            return new BackupSettings(backup.enabled, Math.max(OpsConfig.BackupConfig.MIN_INTERVAL_MINUTES,
                backup.intervalMinutes), Math.max(OpsConfig.BackupConfig.MIN_RETAIN, backup.retain));
        }
    }

    private record WebmapSettings(boolean enabled, boolean linkLines, boolean respectListed) {
        static WebmapSettings of(OpsConfig.WebmapConfig webmap) {
            return new WebmapSettings(webmap.enabled, webmap.linkLines, webmap.respectListed);
        }
    }

    @Override
    public String id() {
        return "ops";
    }

    @Override
    public void register(WormholesRegistrar registrar) {
        api.register(registrar);
    }

    @Override
    public void start(Wormholes plugin) {
        api.start(plugin);
        apiTaskId = J.sr(api::tick, API_INTERVAL_TICKS);
        OpsConfig config = settingsOrDefaults();
        applyConsole(config);
        applyBackups(config);
        applyWebmap(config);
    }

    @Override
    public void stop() {
        if (apiTaskId != 0) {
            J.csr(apiTaskId);
            apiTaskId = 0;
        }
        api.stop();
        stopConsole();
        stopBackups();
        stopWebmap();
        applied = null;
        appliedBackup = null;
        appliedWebmap = null;
    }

    @Override
    public void onSettingsReloaded(WormholesSettings settings) {
        OpsConfig config = settings == null ? new OpsConfig() : settings.getOps();
        applyConsole(config);
        applyBackups(config);
        stopWebmap();
        appliedWebmap = null;
        applyWebmap(config);
    }

    private static OpsConfig settingsOrDefaults() {
        WormholesSettings settings = Wormholes.settings;
        return settings == null ? new OpsConfig() : settings.getOps();
    }

    private void applyConsole(OpsConfig config) {
        ConsoleSettings wanted = ConsoleSettings.of(config.console);
        if (wanted.equals(applied) && (endpoint != null || !wanted.enabled())) {
            return;
        }
        stopConsole();
        applied = wanted;
        if (!wanted.enabled()) {
            return;
        }
        if (wanted.token() == null || wanted.token().isBlank()) {
            Wormholes.w(Wormholes.text().plain(OpsMessages.CONSOLE_NO_TOKEN));
            return;
        }
        MetricsHistory started = new MetricsHistory(wanted.historyMinutes());
        MetricsEndpoint listener = new MetricsEndpoint(wanted.bind(), wanted.port(), wanted.token(),
            metricsSource, started);
        try {
            listener.start();
        } catch (IOException | IllegalStateException failure) {
            Wormholes.w(Wormholes.text().plain(OpsMessages.CONSOLE_FAILED, WormholesLocalization.args(
                MessageArgument.untrusted("value", wanted.bind() + ":" + wanted.port()),
                MessageArgument.untrusted("reason", String.valueOf(failure.getMessage())))));
            return;
        }
        this.history = started;
        this.endpoint = listener;
        this.samplerTaskId = J.ar(this::sample, SAMPLE_INTERVAL_TICKS);
        Wormholes.i(Wormholes.text().plain(OpsMessages.CONSOLE_STARTED, WormholesLocalization.args(
            MessageArgument.untrusted("value", listener.boundAddress()))));
    }

    private void applyBackups(OpsConfig config) {
        BackupSettings wanted = BackupSettings.of(config.backup);
        if (wanted.equals(appliedBackup)) {
            return;
        }
        stopBackups();
        appliedBackup = wanted;
        if (!wanted.enabled()) {
            return;
        }
        backupTaskId = J.ar(this::runScheduledBackup, wanted.intervalMinutes() * 60 * 20);
    }

    private void runScheduledBackup() {
        Wormholes plugin = Wormholes.instance;
        BackupSettings settings = appliedBackup;
        if (plugin == null || settings == null) {
            return;
        }
        Path dataFolder = plugin.getDataFolder().toPath();
        try {
            BackupService service = BackupService.forRuntime(dataFolder);
            BackupService.BackupEntry entry = service.now();
            int removed = service.rotate(settings.retain());
            Wormholes.v(() -> "[ops] backup " + entry.id() + " portals=" + entry.portalCount()
                + " signed=" + entry.signed() + " rotated=" + removed);
        } catch (IOException failure) {
            Wormholes.w("[ops] scheduled backup failed: " + failure.getMessage());
        }
    }

    private void stopBackups() {
        if (backupTaskId != 0) {
            J.csr(backupTaskId);
            backupTaskId = 0;
        }
    }

    private void applyWebmap(OpsConfig config) {
        WebmapSettings wanted = WebmapSettings.of(config.webmap);
        if (wanted.equals(appliedWebmap) && (webmap != null || !wanted.enabled())) {
            return;
        }
        stopWebmap();
        appliedWebmap = wanted;
        if (!wanted.enabled()) {
            return;
        }
        List<WebMapPublisher> publishers = List.of(new DynmapMarkers(), new BlueMapMarkers(),
            new Pl3xMapMarkers(), new SquaremapMarkers());
        webmap = new WebMapService(RuntimeMarkers::markers, publishers);
        webmapTaskId = J.sr(this::publishMarkers, WEBMAP_INTERVAL_TICKS);
    }

    private void publishMarkers() {
        WebMapService service = webmap;
        WebmapSettings settings = appliedWebmap;
        if (service == null || settings == null) {
            return;
        }
        int published = service.tick(settings.respectListed(), settings.linkLines());
        if (published > 0) {
            for (WebMapPublisher publisher : service.publishers()) {
                if (publisher.available()) {
                    Wormholes.v(Wormholes.text().plain(OpsMessages.WEBMAP_PUBLISHED, WormholesLocalization.args(
                        MessageArgument.untrusted("count", Integer.valueOf(published)),
                        MessageArgument.untrusted("name", publisher.id()))));
                }
            }
        }
    }

    private void stopWebmap() {
        if (webmapTaskId != 0) {
            J.csr(webmapTaskId);
            webmapTaskId = 0;
        }
        webmap = null;
    }

    private void sample() {
        MetricsHistory active = history;
        if (active != null) {
            active.record(metricsSource.metrics(), System.currentTimeMillis());
        }
    }

    private void stopConsole() {
        if (samplerTaskId != 0) {
            J.csr(samplerTaskId);
            samplerTaskId = 0;
        }
        MetricsEndpoint running = endpoint;
        endpoint = null;
        history = null;
        if (running != null) {
            running.stop();
        }
    }
}
