package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.network.ClientEndpointRoutes;
import art.arcane.wormholes.network.GameEndpoint;
import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@ConfigDoc({
    "Cross-server networking. Portal codes discover peers automatically."
})
public class NetworkConfig {
    public static final int DEFAULT_LISTEN_PORT = 8901;
    public static final int MIN_LISTEN_PORT = 1;
    public static final int MAX_LISTEN_PORT = 65_535;
    public static final long MIN_HANDOFF_TIMEOUT_MS = 50L;
    public static final long MAX_HANDOFF_TIMEOUT_MS = 60_000L;

    @ConfigDescription("Enable cross-server portals.")
    public boolean enabled = false;
    public boolean listenEnabled = true;

    @ConfigDescription("Optional raw-stream port. Without forwarding, Wormholes uses the game-port sideband.")
    public int listenPort = DEFAULT_LISTEN_PORT;
    public boolean trustOnFirstUse = true;
    public String entityTransferDenyTypes = "";
    public String advertiseHostOverride = "";
    @ConfigDescription("Public Minecraft host for transfers and game-port sideband; blank uses the advertised host.")
    public String gameHostOverride = "";
    @ConfigDescription("Public Minecraft port, including NAT mappings; zero uses server.properties server-port.")
    public int gamePortOverride = 0;
    @ConfigDescription("Private Minecraft host; blank uses a concrete server bind address, or detects LAN for a wildcard bind.")
    public String privateGameHostOverride = "";
    @ConfigDescription("Private Minecraft port; zero uses server.properties server-port.")
    public int privateGamePortOverride = 0;
    public List<ClientRoute> clientRoutes = new ArrayList<>();
    public String serverName = "";
    public String transferMode = "auto";
    @ConfigDescription("Destination server names routed through the proxy when transfer-mode is auto.")
    public List<String> proxyServers = new ArrayList<>();
    public long handoffTimeoutMs = 5000L;
    public boolean autoAcceptTransfers = true;
    public TransportConfig transport = new TransportConfig();
    public ViewConfig view = new ViewConfig();
    public StatsConfig stats = new StatsConfig();
    public ReplicationConfig replication = new ReplicationConfig();

    public void normalizeRuntimeBounds() {
        if (listenPort < MIN_LISTEN_PORT || listenPort > MAX_LISTEN_PORT) {
            listenPort = DEFAULT_LISTEN_PORT;
        }
        handoffTimeoutMs = Math.max(MIN_HANDOFF_TIMEOUT_MS, Math.min(MAX_HANDOFF_TIMEOUT_MS, handoffTimeoutMs));
        if (gamePortOverride < 0 || gamePortOverride > MAX_LISTEN_PORT
            || privateGamePortOverride < 0 || privateGamePortOverride > MAX_LISTEN_PORT) {
            throw new IllegalArgumentException("Network game-port overrides must be between 0 and 65535");
        }
        advertiseHostOverride = normalizeHostOverride(advertiseHostOverride, gamePortOverride);
        gameHostOverride = normalizeHostOverride(gameHostOverride, gamePortOverride);
        privateGameHostOverride = normalizeHostOverride(privateGameHostOverride, privateGamePortOverride);
        if (clientRoutes == null) {
            clientRoutes = new ArrayList<>();
        }
        ClientEndpointRoutes.validate(clientRoutes);
        List<String> normalizedProxyServers = new ArrayList<>(proxyServers == null ? 0 : proxyServers.size());
        if (proxyServers != null) {
            for (String server : proxyServers) {
                if (server == null || server.isBlank()) {
                    throw new IllegalArgumentException("Network proxy-servers entries must be nonblank server names");
                }
                normalizedProxyServers.add(server.trim());
            }
        }
        proxyServers = normalizedProxyServers;
        if (replication == null) {
            replication = new ReplicationConfig();
        }
        replication.normalizeRuntimeBounds();
    }

    public String effectiveTransferMode(String peerName, String requestedMode) {
        String mode = requestedMode == null ? "auto" : requestedMode.trim().toLowerCase(Locale.ROOT);
        if (!"auto".equals(mode)) {
            return mode;
        }
        for (String server : proxyServers) {
            if (server.equalsIgnoreCase(peerName)) {
                return "proxy";
            }
        }
        return mode;
    }

    private static String normalizeHostOverride(String host, int port) {
        return host == null || host.isBlank() ? "" : new GameEndpoint(host, port == 0 ? 25565 : port).host();
    }

    public static class ViewConfig {
        public boolean entityDeltaEnabled = true;
        public double entityRateNearRange = 16.0D;
        public double entityRateMidRange = 64.0D;
        public double entityRateFarRange = 128.0D;
        public double entityRateNearHz = 20.0D;
        public double entityRateMidHz = 10.0D;
        public double entityRateFarHz = 4.0D;
        public double entityRateVeryFarHz = 1.0D;
    }

    public static class TransportConfig {
        public boolean compressionEnabled = true;
        public int compressionLevel = 3;
        public int compressionDictTrainBytes = 10_485_760;
        public int compressionDictTargetSize = 65_536;
        public int compressionRetrainIntervalSec = 600;
        public boolean udsEnabled = true;
        public String udsDir = "";
    }

    public static class ReplicationConfig {
        public static final int MIN_HASH_PROBE_INTERVAL_SEC = 1;
        public static final int MIN_HASH_PROBE_CHUNKS = 1;
        public static final int MAX_HASH_PROBE_CHUNKS = 1024;
        public static final int MIN_DIFF_WINDOW_SIZE = 1;
        public static final int MIN_RESYNC_TIMEOUT_SEC = 0;
        public static final int MIN_QUEUED_DIFFS_PER_PEER = 1;
        public static final int MIN_CAPTURE_SNAPSHOT_INTERVAL_TICKS = 20;
        public static final int MIN_CAPTURE_QUEUED_DIFFS_PER_CHUNK = 16;

        public int hashProbeIntervalSec = 30;
        public int hashProbeChunksPerTick = 16;
        public int diffWindowSize = 32;
        public int resyncTimeoutSec = 5;
        public int maxQueuedDiffsPerPeer = 4096;
        public int captureSnapshotIntervalTicks = 100;
        public int captureMaxQueuedDiffsPerChunk = 256;
        public boolean captureLightEnabled = true;
        @ConfigDescription({
            "Capture block-entity NBT in cross-server replication streams.",
            "The projection renderer does not currently consume this payload, so it is disabled by default to avoid unused traffic."
        })
        public boolean captureBlockEntityEnabled = false;

        public void normalizeRuntimeBounds() {
            hashProbeIntervalSec = Math.max(MIN_HASH_PROBE_INTERVAL_SEC, hashProbeIntervalSec);
            hashProbeChunksPerTick = Math.max(MIN_HASH_PROBE_CHUNKS, Math.min(MAX_HASH_PROBE_CHUNKS, hashProbeChunksPerTick));
            diffWindowSize = Math.max(MIN_DIFF_WINDOW_SIZE, diffWindowSize);
            resyncTimeoutSec = Math.max(MIN_RESYNC_TIMEOUT_SEC, resyncTimeoutSec);
            maxQueuedDiffsPerPeer = Math.max(MIN_QUEUED_DIFFS_PER_PEER, maxQueuedDiffsPerPeer);
            captureSnapshotIntervalTicks = Math.max(MIN_CAPTURE_SNAPSHOT_INTERVAL_TICKS, captureSnapshotIntervalTicks);
            captureMaxQueuedDiffsPerChunk = Math.max(MIN_CAPTURE_QUEUED_DIFFS_PER_CHUNK, captureMaxQueuedDiffsPerChunk);
        }
    }

    public static class StatsConfig {
        public boolean enabled = true;
        public int intervalSec = 10;
        public String pathOverride = "";
    }

    public static class PeerEntry {
        public String name = "";
        public String host = "";
        public String fallbackHosts = "";
        public int port = 8901;
        public String publicHost = "";
        public int publicPort = 25565;
        public String privateHost = "";
        public int privatePort = 0;
        public boolean useProxy = false;
    }

    public static class ClientRoute {
        public String server = "";
        public String clientCidr = "";
        public String host = "";
        public int port = 25565;
    }
}
