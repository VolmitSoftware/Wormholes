package art.arcane.wormholes.modded;

import art.arcane.wormholes.chunk.presend.ChunkPreSendOptions;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.render.client.session.ClientViewOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class WormholesModConfiguration implements AutoCloseable {
    private final Path directory;
    private final Execution execution;
    private final ExecutorService reader;
    private final Set<CompletableFuture<WormholesSettings>> pending = new HashSet<>();
    private final Set<CompletableFuture<Void>> pendingWrites = new HashSet<>();
    private volatile Snapshot current;
    private boolean closed;
    private long revision;

    public WormholesModConfiguration(Path directory, Execution execution) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.execution = Objects.requireNonNull(execution, "execution");
        execution.requireServerThread().run();
        current = Snapshot.from(WormholesSettings.loadAll(directory));
        reader = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("Wormholes-config").factory());
    }

    public WormholesSettings settings() {
        return current.settings();
    }

    public ChunkPreSendOptions preSendOptions() {
        return current.preSendOptions();
    }

    public ClientViewOptions clientViewOptions() {
        return current.clientViewOptions();
    }

    public synchronized CompletableFuture<WormholesSettings> reload() {
        execution.requireServerThread().run();
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Wormholes configuration is closed"));
        }
        CompletableFuture<WormholesSettings> result = new CompletableFuture<>();
        pending.add(result);
        reader.execute(() -> read(result));
        return result;
    }

    public synchronized CompletableFuture<Void> setLanguage(String locale) {
        execution.requireServerThread().run();
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Wormholes configuration is closed"));
        }
        String previous = current.settings().getLanguage();
        current = Snapshot.from(current.settings().withLanguage(locale));
        long selectedRevision = ++revision;
        return persist().whenComplete((ignored, failure) -> {
            synchronized (this) {
                if (failure != null && revision == selectedRevision) {
                    current = Snapshot.from(current.settings().withLanguage(previous));
                    revision++;
                }
            }
        });
    }

    public synchronized CompletableFuture<Void> persist() {
        execution.requireServerThread().run();
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Wormholes configuration is closed"));
        }
        byte[] content = current.settings().canonicalSnapshot();
        CompletableFuture<Void> result = new CompletableFuture<>();
        pendingWrites.add(result);
        reader.execute(() -> write(content, result));
        return result;
    }

    @Override
    public synchronized void close() {
        execution.requireServerThread().run();
        if (closed) {
            return;
        }
        closed = true;
        reader.shutdownNow();
        for (CompletableFuture<WormholesSettings> result : pending) {
            result.completeExceptionally(new CancellationException("Wormholes stopped before configuration reload completed"));
        }
        pending.clear();
        for (CompletableFuture<Void> result : pendingWrites) {
            result.completeExceptionally(new CancellationException("Wormholes stopped before configuration persistence completed"));
        }
        pendingWrites.clear();
    }

    private void write(byte[] content, CompletableFuture<Void> result) {
        Exception failure = null;
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, ".wormholes-", ".tmp");
            Files.write(temporary, content);
            Path destination = directory.resolve(WormholesSettings.CONFIG_FILE_NAME);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
        } catch (IOException | RuntimeException error) {
            failure = error;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanupFailure) {
                    if (failure == null) {
                        failure = cleanupFailure;
                    } else {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
            }
        }
        Exception outcome = failure;
        execution.serverExecutor().execute(() -> completeWrite(result, outcome));
    }

    private synchronized void completeWrite(CompletableFuture<Void> result, Exception failure) {
        execution.requireServerThread().run();
        if (closed || !pendingWrites.remove(result)) {
            return;
        }
        if (failure == null) {
            result.complete(null);
        } else {
            result.completeExceptionally(failure);
        }
    }

    private void read(CompletableFuture<WormholesSettings> result) {
        Snapshot candidate;
        try {
            byte[] content = Files.readAllBytes(directory.resolve(WormholesSettings.CONFIG_FILE_NAME));
            candidate = Snapshot.from(WormholesSettings.loadSnapshot(content));
        } catch (IOException | RuntimeException error) {
            execution.serverExecutor().execute(() -> complete(result, null, error));
            return;
        }
        execution.serverExecutor().execute(() -> complete(result, candidate, null));
    }

    private synchronized void complete(CompletableFuture<WormholesSettings> result, Snapshot candidate, Exception error) {
        if (closed || !pending.remove(result)) {
            return;
        }
        execution.requireServerThread().run();
        if (error != null) {
            result.completeExceptionally(error);
            return;
        }
        current = Objects.requireNonNull(candidate, "candidate");
        revision++;
        result.complete(current.settings());
    }

    public record Execution(Executor serverExecutor, Runnable requireServerThread) {
        public Execution {
            Objects.requireNonNull(serverExecutor, "serverExecutor");
            Objects.requireNonNull(requireServerThread, "requireServerThread");
        }
    }

    private record Snapshot(WormholesSettings settings, ChunkPreSendOptions preSendOptions, ClientViewOptions clientViewOptions) {
        private static Snapshot from(WormholesSettings settings) {
            MainConfig main = settings.getMain();
            return new Snapshot(settings, ChunkPreSendOptions.of(main.chunkPreSendEnabled,
                main.chunkPreSendRadiusChunks, main.chunkPreSendMaxChunks, main.chunkPreSendBudgetMicros),
                ClientViewOptions.from(settings.getClientView(), settings.getProjection().interestGraceTicks));
        }
    }
}
