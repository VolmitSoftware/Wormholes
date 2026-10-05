package art.arcane.wormholes.atlas;

import art.arcane.wormholes.util.JsonDocuments;
import art.arcane.wormholes.util.VIO;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Per-player atlas files under {@code atlas/players/}. State loads on join, is written back on quit
 * and on the debounced flush, and never touches disk while nothing changed.
 */
public final class AtlasPlayerStore implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger("Wormholes");

    private final Path directory;
    private final JsonDocuments json;
    private final ConcurrentHashMap<UUID, CompletableFuture<AtlasPlayerState>> loaded = new ConcurrentHashMap<>();
    private final ExecutorService storage = Executors.newSingleThreadExecutor(
        Thread.ofVirtual().name("wormholes-atlas-storage").factory());
    private CompletableFuture<Void> closing;

    public AtlasPlayerStore(Path directory, JsonDocuments json) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.json = Objects.requireNonNull(json, "json");
    }

    public Path directory() {
        return directory;
    }

    public synchronized CompletableFuture<AtlasPlayerState> loadAsync(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (closing != null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Atlas store is closed"));
        }
        return loaded.computeIfAbsent(playerId,
            id -> CompletableFuture.supplyAsync(() -> read(id), storage));
    }

    /** The cached state, or null when this player is not loaded. */
    public AtlasPlayerState cached(UUID playerId) {
        CompletableFuture<AtlasPlayerState> pending = playerId == null ? null : loaded.get(playerId);
        return pending != null && pending.state() == Future.State.SUCCESS ? pending.resultNow() : null;
    }

    public synchronized CompletableFuture<Void> unloadAsync(UUID playerId) {
        CompletableFuture<AtlasPlayerState> pending = loaded.remove(playerId);
        if (pending == null || closing != null) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> write(pending.join()), storage);
    }

    public synchronized CompletableFuture<Void> flushDirtyAsync() {
        if (closing != null) {
            return CompletableFuture.completedFuture(null);
        }
        List<CompletableFuture<AtlasPlayerState>> states = List.copyOf(loaded.values());
        return CompletableFuture.runAsync(() -> flush(states), storage);
    }

    @Override
    public void close() {
        CompletableFuture<Void> pending;
        synchronized (this) {
            if (closing == null) {
                List<CompletableFuture<AtlasPlayerState>> states = List.copyOf(loaded.values());
                loaded.clear();
                closing = CompletableFuture.runAsync(() -> flush(states), storage);
                storage.shutdown();
            }
            pending = closing;
        }
        try {
            pending.join();
        } finally {
            storage.close();
        }
    }

    private void flush(List<CompletableFuture<AtlasPlayerState>> states) {
        for (CompletableFuture<AtlasPlayerState> pending : states) {
            write(pending.join());
        }
    }

    private AtlasPlayerState read(UUID playerId) {
        Path file = directory.resolve(playerId + ".json");
        try {
            if (!Files.isRegularFile(file)) {
                return new AtlasPlayerState(playerId);
            }
            return decode(playerId, json.decode(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException failure) {
            LOG.log(Level.WARNING, "atlas state unreadable for " + playerId, failure);
            return new AtlasPlayerState(playerId);
        }
    }

    private void write(AtlasPlayerState state) {
        if (state == null || !state.isDirty()) {
            return;
        }
        AtlasPlayerState.Snapshot snapshot = state.snapshot();
        try {
            Files.createDirectories(directory);
            VIO.writeAll(directory.resolve(state.playerId() + ".json").toFile(), json.encode(encode(snapshot)));
            state.markClean(snapshot);
        } catch (IOException | RuntimeException failure) {
            LOG.log(Level.WARNING, "atlas state could not be saved for " + state.playerId(), failure);
        }
    }

    private static Map<String, Object> encode(AtlasPlayerState.Snapshot snapshot) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("player", snapshot.playerId().toString());
        json.put("discovered", ids(snapshot.discovered()));
        json.put("favorites", ids(snapshot.favorites()));
        json.put("recents", ids(snapshot.recents()));
        if (snapshot.guideTarget() != null) {
            json.put("guide", snapshot.guideTarget().toString());
        }
        return json;
    }

    private static AtlasPlayerState decode(UUID playerId, Map<String, Object> json) {
        return AtlasPlayerState.restore(new AtlasPlayerState.Snapshot(playerId,
            readIds(json.get("discovered")), readIds(json.get("favorites")),
            readIds(json.get("recents")), parse(json.get("guide") instanceof String guide ? guide : "")));
    }

    private static List<String> ids(Iterable<UUID> values) {
        List<String> array = new ArrayList<>();
        for (UUID value : values) {
            array.add(value.toString());
        }
        return array;
    }

    private static List<UUID> readIds(Object source) {
        List<UUID> values = new ArrayList<>();
        if (!(source instanceof List<?> array)) {
            return values;
        }
        for (int index = 0; index < array.size(); index++) {
            UUID parsed = parse(array.get(index) instanceof String value ? value : "");
            if (parsed != null && !values.contains(parsed)) {
                values.add(parsed);
            }
        }
        return values;
    }

    private static UUID parse(String value) {
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException | NullPointerException notAUuid) {
            return null;
        }
    }
}
