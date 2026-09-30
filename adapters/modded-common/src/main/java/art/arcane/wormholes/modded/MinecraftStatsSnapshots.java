package art.arcane.wormholes.modded;

import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.network.NetworkManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class MinecraftStatsSnapshots implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private final WormholesModRuntime runtime;
    private ExecutorService writer;
    private CompletableFuture<Path> pending;
    private long nextWrite;

    MinecraftStatsSnapshots(WormholesModRuntime runtime) {
        this.runtime = runtime;
    }

    Path path() {
        String configured = runtime.configuration().settings().getNetwork().stats.pathOverride;
        Path directory = runtime.server().getServerDirectory().resolve("config/wormholes");
        return configured == null || configured.isBlank() ? directory.resolve("stats-snapshot.txt") : directory.resolve(configured);
    }

    void tick() {
        NetworkConfig.StatsConfig configuration = runtime.configuration().settings().getNetwork().stats;
        long now = System.currentTimeMillis();
        if (!configuration.enabled || now < nextWrite || pending != null && !pending.isDone()) {
            return;
        }
        nextWrite = now + Math.max(1, configuration.intervalSec) * 1000L;
        write().whenComplete((path, failure) -> {
            if (failure != null) {
                LOGGER.error("Could not refresh Wormholes stats snapshot", failure);
            }
        });
    }

    CompletableFuture<Path> write() {
        runtime.requireServerThread();
        if (writer == null) {
            writer = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("Wormholes-stats").factory());
        }
        String snapshot = capture();
        Path path = path();
        pending = CompletableFuture.supplyAsync(() -> save(path, snapshot), writer);
        return pending;
    }

    String capture() {
        NetworkManager network = runtime.network().manager();
        StringBuilder text = new StringBuilder(1024);
        text.append("Wormholes ").append(MinecraftNetworkService.version()).append('\n');
        text.append("Generated: ").append(Instant.now()).append('\n');
        text.append("Server: ").append(network == null ? "offline" : network.getLocalName()).append('\n');
        text.append("Portals: ").append(runtime.portals().snapshot().size()).append('\n');
        text.append("Players: ").append(runtime.server().getPlayerCount()).append('\n');
        text.append("Projection observers: ").append(runtime.projections().observerCount()).append('\n');
        text.append("Projectors: ").append(runtime.projections().projectorCount()).append('\n');
        text.append("Section cache sections: ").append(runtime.projections().sectionCacheSections()).append('\n');
        text.append("Section cache bytes: ").append(runtime.projections().sectionCacheBytes()).append('\n');
        text.append("Plates: ").append(runtime.projections().plates().size()).append('\n');
        text.append("Plate bytes: ").append(runtime.projections().plates().bytes()).append('\n');
        text.append("Plate builds: ").append(runtime.projections().plates().buildsCompleted()).append('\n');
        text.append("Plate capture queue: ").append(runtime.projections().plateCaptureQueueSize()).append('\n');
        text.append("Mean tick milliseconds: ").append(runtime.server().getAverageTickTimeNanos() / 1_000_000.0D).append('\n');
        if (network != null) {
            text.append("Transport queues: ").append(network.debugSnapshot()).append('\n');
            for (NetworkManager.PeerSnapshot peer : network.peerSnapshots()) {
                text.append("Peer: ").append(peer.name()).append(" transport=").append(peer.transport())
                    .append(" connected=").append(peer.handshakeComplete() && !peer.disconnected())
                    .append(" rttMillis=").append(peer.rttMillis()).append('\n');
            }
        }
        return text.toString();
    }

    private static Path save(Path path, String snapshot) {
        Path temporary = null;
        try {
            Path destination = path.toAbsolutePath();
            Files.createDirectories(destination.getParent());
            temporary = Files.createTempFile(destination.getParent(), ".stats-", ".tmp");
            Files.writeString(temporary, snapshot, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unavailable) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            return destination;
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not write stats snapshot " + path, failure);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException failure) {
                    LOGGER.error("Could not remove temporary stats snapshot {}", temporary, failure);
                }
            }
        }
    }

    @Override
    public void close() {
        if (writer != null) {
            writer.close();
            writer = null;
        }
        pending = null;
        nextWrite = 0L;
    }
}
