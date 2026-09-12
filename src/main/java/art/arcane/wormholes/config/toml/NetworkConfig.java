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
    public static final String PLUGIN_VERSION_POLICY_COMPATIBLE = "compatible";
    public static final String PLUGIN_VERSION_POLICY_EXACT = "exact";

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
    @ConfigDescription({
        "Wormholes version policy for peer links: \"compatible\" links any protocol-21 peer and reports reduced capability,",
        "\"exact\" requires identical Wormholes versions."
    })
    public String pluginVersionPolicy = "compatible";
    @ConfigDescription("Persist the remote portal directory to mesh/directory.json so linked portals show as stale after a restart instead of vanishing.")
    public boolean directoryCacheEnabled = true;
    public TransportConfig transport = new TransportConfig();
    public ViewConfig view = new ViewConfig();
    public StatsConfig stats = new StatsConfig();
    public ReplicationConfig replication = new ReplicationConfig();
    public MeshConfig mesh = new MeshConfig();
    public PolicyConfig policy = new PolicyConfig();
    public ProxyConfig proxy = new ProxyConfig();

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
        pluginVersionPolicy = normalizePluginVersionPolicy(pluginVersionPolicy);
        if (mesh == null) {
            mesh = new MeshConfig();
        }
        mesh.normalizeRuntimeBounds();
        if (policy == null) {
            policy = new PolicyConfig();
        }
        policy.normalizeRuntimeBounds();
        if (proxy == null) {
            proxy = new ProxyConfig();
        }
        proxy.normalizeRuntimeBounds();
    }

    public boolean exactPluginVersionPolicy() {
        return PLUGIN_VERSION_POLICY_EXACT.equals(pluginVersionPolicy);
    }

    private static String normalizePluginVersionPolicy(String value) {
        String normalized = value == null ? PLUGIN_VERSION_POLICY_COMPATIBLE : value.trim().toLowerCase(Locale.ROOT);
        if (!PLUGIN_VERSION_POLICY_COMPATIBLE.equals(normalized) && !PLUGIN_VERSION_POLICY_EXACT.equals(normalized)) {
            throw new IllegalArgumentException("Network plugin-version-policy must be \"compatible\" or \"exact\", got \"" + value + "\"");
        }
        return normalized;
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
            "Capture block-entity appearance (signs, banners, heads, pots, bells, spawners) in cross-server replication streams.",
            "Container contents never cross; the projection block-entity layer consumes this payload."
        })
        public boolean captureBlockEntityEnabled = true;

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

    @ConfigDoc({
        "Signed peer federation: linked servers introduce each other and the whole network converges from one pasted code."
    })
    public static class MeshConfig {
        public static final String INTRODUCERS_TRUSTED = "trusted";
        public static final String INTRODUCERS_ALLOWLIST = "allowlist";
        public static final String INTRODUCERS_MANUAL = "manual";
        public static final int MIN_ANNOUNCE_INTERVAL_SEC = 5;

        @ConfigDescription("Accept and flood signed peer announcements.")
        public boolean enabled = true;
        @ConfigDescription("Who may introduce new peers: \"trusted\" (any trusted peer), \"allowlist\" (introducer-allowlist only), \"manual\" (introductions are ignored).")
        public String introducers = INTRODUCERS_TRUSTED;
        @ConfigDescription("Peer names allowed to introduce when introducers = \"allowlist\".")
        public List<String> introducerAllowlist = new ArrayList<>();
        @ConfigDescription("Trust introduced peers on their first handshake instead of holding them in quarantine for /wh network accept.")
        public boolean autoAcceptIntroductions = false;
        public int announceIntervalSec = 60;
        public int announceRateLimitPerSourcePerMin = 30;
        public int tombstoneTtlDays = 30;

        public void normalizeRuntimeBounds() {
            String policy = introducers == null ? INTRODUCERS_TRUSTED : introducers.trim().toLowerCase(Locale.ROOT);
            if (!INTRODUCERS_TRUSTED.equals(policy) && !INTRODUCERS_ALLOWLIST.equals(policy) && !INTRODUCERS_MANUAL.equals(policy)) {
                throw new IllegalArgumentException("Network mesh introducers must be \"trusted\", \"allowlist\" or \"manual\", got \"" + introducers + "\"");
            }
            introducers = policy;
            List<String> normalizedAllowlist = new ArrayList<>(introducerAllowlist == null ? 0 : introducerAllowlist.size());
            if (introducerAllowlist != null) {
                for (String server : introducerAllowlist) {
                    if (server != null && !server.isBlank()) {
                        normalizedAllowlist.add(server.trim());
                    }
                }
            }
            introducerAllowlist = normalizedAllowlist;
            announceIntervalSec = Math.max(MIN_ANNOUNCE_INTERVAL_SEC, announceIntervalSec);
            announceRateLimitPerSourcePerMin = Math.max(1, announceRateLimitPerSourcePerMin);
            tombstoneTtlDays = Math.max(1, tombstoneTtlDays);
        }

        public boolean mayIntroduce(String peerName) {
            if (INTRODUCERS_MANUAL.equals(introducers)) {
                return false;
            }
            if (INTRODUCERS_TRUSTED.equals(introducers)) {
                return true;
            }
            for (String allowed : introducerAllowlist) {
                if (allowed.equalsIgnoreCase(peerName)) {
                    return true;
                }
            }
            return false;
        }
    }

    @ConfigDoc({
        "Load beacons and gateway destination policy: candidate selection, failover, queueing."
    })
    public static class PolicyConfig {
        public static final int MIN_BEACON_INTERVAL_SEC = 1;
        /** Must stay under the 30 s teleport in-flight limit that bounds a departure hold. */
        public static final int MAX_QUEUE_WAIT_SEC = 29;

        public int beaconIntervalSec = 5;
        public int beaconStaleSec = 20;
        @ConfigDescription("Hold travelers at a gateway while every policy candidate is full instead of bouncing them.")
        public boolean queueEnabled = true;
        @ConfigDescription("Longest hold before a queued traveler is released (1-29 s; the in-flight limit is 30 s).")
        public int queueMaxWaitSec = 25;

        public void normalizeRuntimeBounds() {
            beaconIntervalSec = Math.max(MIN_BEACON_INTERVAL_SEC, beaconIntervalSec);
            beaconStaleSec = Math.max(beaconIntervalSec * 2, beaconStaleSec);
            queueMaxWaitSec = Math.max(1, Math.min(MAX_QUEUE_WAIT_SEC, queueMaxWaitSec));
        }
    }

    @ConfigDoc({
        "Wormholes proxy module (Velocity or BungeeCord): backends enroll over a plugin channel instead of pasting codes."
    })
    public static class ProxyConfig {
        public static final String DEFAULT_CHANNEL = "wormholes:proxy";

        @ConfigDescription("Use the proxy module channel when the WormholesProxy plugin is installed on the proxy.")
        public boolean enabled = false;
        public String channel = DEFAULT_CHANNEL;
        @ConfigDescription({
            "Shared secret for the enrollment channel, copied from plugins/WormholesProxy/secret.txt on the proxy.",
            "Clients can send on this channel too, so frames are signed with it; the module stays off while it is blank."
        })
        public String secret = "";

        public void normalizeRuntimeBounds() {
            if (channel == null || channel.isBlank() || channel.indexOf(':') <= 0) {
                channel = DEFAULT_CHANNEL;
            } else {
                channel = channel.trim().toLowerCase(Locale.ROOT);
            }
            secret = secret == null ? "" : secret.trim();
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
