package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "Operator console, backups, importers, and web-map markers. Changes hot-reload."
})
public class OpsConfig {
    public ConsoleConfig console = new ConsoleConfig();
    public BackupConfig backup = new BackupConfig();
    public WebmapConfig webmap = new WebmapConfig();

    @ConfigDoc({
        "Read-only HTTP metrics endpoint. Off by default; it refuses to start without a token."
    })
    public static class ConsoleConfig {
        public static final int MIN_PORT = 1;
        public static final int MAX_PORT = 65_535;
        public static final int MIN_HISTORY_MINUTES = 1;
        public static final int MAX_HISTORY_MINUTES = 1_440;

        @ConfigDescription("Serve OpenMetrics at /metrics and a JSON snapshot at /snapshot.")
        public boolean enabled = false;

        @ConfigDescription("Bind address. Loopback keeps the endpoint off the network; use 0.0.0.0 only behind a firewall.")
        public String bind = "127.0.0.1";

        public int port = 8905;

        @ConfigDescription("Bearer token required on every request. An empty token keeps the endpoint down.")
        public String token = "";

        @ConfigDescription("Minutes of in-memory 1 Hz history kept per metric.")
        public int historyMinutes = 30;
    }

    @ConfigDoc({
        "Scheduled portal backups under plugins/Wormholes/backups."
    })
    public static class BackupConfig {
        public static final int MIN_INTERVAL_MINUTES = 5;
        public static final int MIN_RETAIN = 1;

        public boolean enabled = true;

        @ConfigDescription("Minutes between scheduled backups.")
        public int intervalMinutes = 60;

        @ConfigDescription("Scheduled backups kept before the oldest is deleted.")
        public int retain = 24;
    }

    @ConfigDoc({
        "Portal markers published to Dynmap, BlueMap, Pl3xMap, and squaremap when one is installed."
    })
    public static class WebmapConfig {
        public boolean enabled = true;

        @ConfigDescription("Draw a line between a portal and its local destination.")
        public boolean linkLines = true;

        @ConfigDescription("Skip portals whose owner marked them unlisted.")
        public boolean respectListed = true;
    }
}
