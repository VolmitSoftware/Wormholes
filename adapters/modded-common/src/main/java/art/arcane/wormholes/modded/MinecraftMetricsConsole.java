package art.arcane.wormholes.modded;

import art.arcane.wormholes.config.toml.OpsConfig;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.ops.console.MetricsEndpoint;
import art.arcane.wormholes.ops.console.MetricsHistory;
import art.arcane.wormholes.ops.console.MetricsSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class MinecraftMetricsConsole implements MetricsSource, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private final WormholesModRuntime runtime;
    private volatile Sample sample = new Sample(Map.of(), List.of(), Map.of());
    private volatile MetricsEndpoint endpoint;
    private volatile long generation;
    private ExecutorService worker;
    private Settings settings;
    private MetricsHistory history;
    private long nextSample;

    MinecraftMetricsConsole(WormholesModRuntime runtime) {
        this.runtime = runtime;
    }

    void tick() {
        long now = System.currentTimeMillis();
        if (now < nextSample) {
            return;
        }
        nextSample = now + 1000L;
        OpsConfig.ConsoleConfig configured = runtime.configuration().settings().getOps().console;
        Settings wanted = new Settings(configured.enabled, configured.bind, configured.port, configured.token, configured.historyMinutes);
        if (!wanted.equals(settings)) {
            settings = wanted;
            history = new MetricsHistory(wanted.historyMinutes());
            long current = ++generation;
            if (worker == null && (wanted.enabled() || endpoint != null)) {
                worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("Wormholes-metrics-lifecycle").factory());
            }
            if (worker != null) {
                MetricsHistory window = history;
                worker.execute(() -> configure(wanted, window, current));
            }
        }
        if (wanted.enabled()) {
            sample = capture();
            history.record(sample.metrics(), now);
        }
    }

    int boundPort() {
        MetricsEndpoint current = endpoint;
        return current == null ? 0 : current.boundPort();
    }

    @Override
    public Map<String, Double> metrics() {
        return sample.metrics();
    }

    @Override
    public List<Peer> peers() {
        return sample.peers();
    }

    @Override
    public Map<String, Long> failures() {
        return sample.failures();
    }

    @Override
    public void close() {
        ++generation;
        if (worker != null) {
            worker.execute(this::stopEndpoint);
            worker.close();
            worker = null;
        }
        settings = null;
        history = null;
        nextSample = 0L;
        sample = new Sample(Map.of(), List.of(), Map.of());
    }

    private Sample capture() {
        runtime.requireServerThread();
        Map<String, Double> values = new HashMap<>();
        values.put("wormholes.portals", (double) runtime.portals().snapshot().size());
        values.put("wormholes.projections-active", (double) runtime.projections().projectorCount());
        values.put("wormholes.players", (double) runtime.server().getPlayerCount());
        values.put("wormholes.tick-milliseconds", runtime.server().getAverageTickTimeNanos() / 1_000_000.0D);
        NetworkManager network = runtime.network().manager();
        List<Peer> peers = new ArrayList<>();
        if (network != null) {
            int connected = 0;
            for (NetworkManager.PeerSnapshot peer : network.peerSnapshots()) {
                boolean active = peer.handshakeComplete() && !peer.disconnected();
                connected += active ? 1 : 0;
                peers.add(new Peer(peer.name(), peer.transport(), peer.compressionMode(), peer.rttMillis(), active));
            }
            values.put("wormholes.peers-connected", (double) connected);
        }
        return new Sample(Map.copyOf(values), List.copyOf(peers), runtime.costs().failures());
    }

    private void configure(Settings wanted, MetricsHistory window, long current) {
        stopEndpoint();
        if (!wanted.enabled() || generation != current) {
            return;
        }
        MetricsEndpoint started = new MetricsEndpoint(new MetricsEndpoint.Options(wanted.bind(), wanted.port(), wanted.token(), this, window));
        try {
            started.start();
            if (generation != current) {
                started.stop();
                return;
            }
            endpoint = started;
            LOGGER.info("Wormholes metrics listening on {}", started.boundAddress());
        } catch (IOException | IllegalStateException failure) {
            started.stop();
            LOGGER.error("Could not start Wormholes metrics endpoint", failure);
        }
    }

    private void stopEndpoint() {
        MetricsEndpoint current = endpoint;
        endpoint = null;
        if (current != null) {
            current.stop();
        }
    }

    private record Settings(boolean enabled, String bind, int port, String token, int historyMinutes) {
    }

    private record Sample(Map<String, Double> metrics, List<Peer> peers, Map<String, Long> failures) {
    }
}
