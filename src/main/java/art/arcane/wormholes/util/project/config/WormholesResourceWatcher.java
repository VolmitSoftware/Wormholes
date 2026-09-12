package art.arcane.wormholes.util.project.config;

import art.arcane.volmlib.util.hotload.ConfigHotloadEngine;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

public final class WormholesResourceWatcher implements AutoCloseable {
    private static final long POLL_INTERVAL_MS = 200L;
    private static final long RETRY_DELAY_NANOS = TimeUnit.SECONDS.toNanos(1L);

    private final Path languages;
    private final Path metricsFile;
    private final Logger logger;
    private final Function<Set<Path>, CompletableFuture<Boolean>> reload;
    private final ConfigHotloadEngine engine;
    private final AtomicBoolean running = new AtomicBoolean();
    private ScheduledExecutorService executor;
    private final Map<Resource, ReloadState> reloads = new EnumMap<>(Resource.class);
    private String lastFailure;

    public WormholesResourceWatcher(Options options) {
        Objects.requireNonNull(options);
        Path dataFolder = options.dataFolder().toAbsolutePath().normalize();
        languages = dataFolder.resolve("languages");
        metricsFile = dataFolder.resolveSibling("bStats").resolve("config.yml");
        logger = Objects.requireNonNull(options.logger());
        reload = Objects.requireNonNull(options.reload());
        engine = new ConfigHotloadEngine(this::managed, this::knownFiles, this::read, content -> content);
        reloads.put(Resource.LANGUAGE, new ReloadState(languages.resolve("en_US.toml")));
        reloads.put(Resource.METRICS, new ReloadState(metricsFile));
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        engine.configure(POLL_INTERVAL_MS, 350L, List.of(metricsFile.toFile()), List.of(languages.toFile()));
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "Wormholes-Resource-Watcher");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::poll, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        running.set(false);
        ScheduledExecutorService active = executor;
        executor = null;
        if (active != null) {
            active.shutdownNow();
        }
        engine.clear();
    }

    private void poll() {
        if (!running.get()) {
            return;
        }
        try {
            for (ConfigHotloadEngine.StableContentSnapshot snapshot : engine.pollTouchedSnapshots()) {
                Path path = snapshot.file().toPath().toAbsolutePath().normalize();
                Resource resource = path.equals(metricsFile) ? Resource.METRICS : Resource.LANGUAGE;
                reloads.get(resource).pending.put(path, snapshot);
            }
            for (ReloadState state : reloads.values()) {
                pollReload(state);
            }
            lastFailure = null;
        } catch (RuntimeException failure) {
            String signature = failure.getClass().getName() + ":" + failure.getMessage();
            if (!signature.equals(lastFailure)) {
                logger.log(Level.WARNING, "Could not watch Wormholes language or bStats files; retrying.", failure);
                lastFailure = signature;
            }
        } finally {
            if (!running.get()) {
                engine.clear();
            }
        }
    }

    private void pollReload(ReloadState state) {
        try {
            if (state.application != null) {
                if (!state.application.isDone()) {
                    return;
                }
                CompletableFuture<Boolean> completed = state.application;
                state.application = null;
                if (Boolean.TRUE.equals(completed.join())) {
                    for (ConfigHotloadEngine.StableContentSnapshot snapshot : state.inFlight) {
                        engine.processSnapshotChange(snapshot, ignored -> true, null);
                        state.pending.remove(snapshot.file().toPath().toAbsolutePath().normalize(), snapshot);
                    }
                    state.initial = false;
                    state.lastFailure = null;
                } else {
                    state.retryAfterNanos = System.nanoTime() + RETRY_DELAY_NANOS;
                }
            }
            if (System.nanoTime() < state.retryAfterNanos || !running.get()
                || (!state.initial && state.pending.isEmpty())) {
                return;
            }
            state.inFlight = List.copyOf(state.pending.values());
            Set<Path> paths = new HashSet<>(state.pending.keySet());
            if (state.initial) {
                paths.add(state.initialPath);
            }
            state.application = Objects.requireNonNull(reload.apply(Set.copyOf(paths)));
        } catch (RuntimeException failure) {
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
            String signature = cause.getClass().getName() + ":" + cause.getMessage();
            if (!signature.equals(state.lastFailure)) {
                logger.log(Level.WARNING, "Could not hot-reload " + state.initialPath + "; retrying.", cause);
                state.lastFailure = signature;
            }
            state.retryAfterNanos = System.nanoTime() + RETRY_DELAY_NANOS;
        }
    }

    private boolean managed(File file) {
        Path path = file.toPath().toAbsolutePath().normalize();
        return path.equals(metricsFile)
            || (languages.equals(path.getParent()) && path.getFileName().toString().endsWith(".toml"));
    }

    private Collection<File> knownFiles() {
        List<File> files = new ArrayList<>();
        files.add(metricsFile.toFile());
        if (Files.isDirectory(languages)) {
            try (Stream<Path> paths = Files.list(languages)) {
                paths.filter(Files::isRegularFile).map(Path::toFile).filter(this::managed).forEach(files::add);
            } catch (IOException failure) {
                throw new UncheckedIOException("Could not list Wormholes language files", failure);
            }
        }
        return files;
    }

    private String read(File file) {
        if (!file.isFile()) {
            return null;
        }
        try {
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not read " + file, failure);
        }
    }

    public record Options(Path dataFolder, Logger logger, Function<Set<Path>, CompletableFuture<Boolean>> reload) {
    }

    private enum Resource {
        LANGUAGE,
        METRICS
    }

    private static final class ReloadState {
        private final Path initialPath;
        private final Map<Path, ConfigHotloadEngine.StableContentSnapshot> pending = new LinkedHashMap<>();
        private List<ConfigHotloadEngine.StableContentSnapshot> inFlight = List.of();
        private CompletableFuture<Boolean> application;
        private boolean initial = true;
        private long retryAfterNanos;
        private String lastFailure;

        private ReloadState(Path initialPath) {
            this.initialPath = initialPath;
        }
    }
}
