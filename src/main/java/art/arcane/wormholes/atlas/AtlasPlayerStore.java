package art.arcane.wormholes.atlas;

import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.wormholes.util.VIO;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Per-player atlas files under {@code atlas/players/}. State loads on join, is written back on quit
 * and on the debounced flush, and never touches disk while nothing changed.
 */
public final class AtlasPlayerStore {
    private static final Logger LOG = Logger.getLogger("Wormholes");

    private final Path directory;
    private final ConcurrentHashMap<UUID, AtlasPlayerState> loaded = new ConcurrentHashMap<>();

    public AtlasPlayerStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    public Path directory() {
        return directory;
    }

    public AtlasPlayerState load(UUID playerId) {
        return loaded.computeIfAbsent(playerId, this::read);
    }

    /** The cached state, or null when this player is not loaded. */
    public AtlasPlayerState cached(UUID playerId) {
        return playerId == null ? null : loaded.get(playerId);
    }

    public void save(UUID playerId) {
        write(loaded.get(playerId));
    }

    public void unload(UUID playerId) {
        AtlasPlayerState state = loaded.remove(playerId);
        write(state);
    }

    public void flushDirty() {
        for (AtlasPlayerState state : loaded.values()) {
            if (state.isDirty()) {
                write(state);
            }
        }
    }

    public void flushAll() {
        for (AtlasPlayerState state : loaded.values()) {
            write(state);
        }
        loaded.clear();
    }

    private AtlasPlayerState read(UUID playerId) {
        Path file = directory.resolve(playerId + ".json");
        if (!Files.isRegularFile(file)) {
            return new AtlasPlayerState(playerId);
        }
        try {
            return AtlasPlayerState.fromJSON(playerId, new JSONObject(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException failure) {
            LOG.log(Level.WARNING, "atlas state unreadable for " + playerId, failure);
            return new AtlasPlayerState(playerId);
        }
    }

    private void write(AtlasPlayerState state) {
        if (state == null) {
            return;
        }
        try {
            Files.createDirectories(directory);
            VIO.writeAll(directory.resolve(state.playerId() + ".json").toFile(), state.toJSON().toString());
            state.markClean();
        } catch (IOException failure) {
            LOG.log(Level.WARNING, "atlas state could not be saved for " + state.playerId(), failure);
        }
    }
}
