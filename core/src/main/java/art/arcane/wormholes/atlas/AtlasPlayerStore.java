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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Per-player atlas files under {@code atlas/players/}. State loads on join, is written back on quit
 * and on the debounced flush, and never touches disk while nothing changed.
 */
public final class AtlasPlayerStore {
    private static final Logger LOG = Logger.getLogger("Wormholes");

    private final Path directory;
    private final JsonDocuments json;
    private final ConcurrentHashMap<UUID, AtlasPlayerState> loaded = new ConcurrentHashMap<>();

    public AtlasPlayerStore(Path directory, JsonDocuments json) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.json = Objects.requireNonNull(json, "json");
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
            return decode(playerId, json.decode(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException failure) {
            LOG.log(Level.WARNING, "atlas state unreadable for " + playerId, failure);
            return new AtlasPlayerState(playerId);
        }
    }

    private void write(AtlasPlayerState state) {
        if (state == null) {
            return;
        }
        AtlasPlayerState.Snapshot snapshot = state.snapshot();
        try {
            Files.createDirectories(directory);
            VIO.writeAll(directory.resolve(state.playerId() + ".json").toFile(), json.encode(encode(snapshot)));
            state.markClean(snapshot);
        } catch (IOException failure) {
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
