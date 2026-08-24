package art.arcane.wormholes.door;

import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.wormholes.util.VIO;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

final class PocketResizeJournal {
    private static final int SCHEMA = 1;
    private static final String DIRECTORY = "pending-resizes";

    private final Path directory;
    private final LinkedHashMap<UUID, PocketResizeIntent> pending;

    private boolean loaded;

    PocketResizeJournal(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        pending = new LinkedHashMap<>();
    }

    static PocketResizeJournal under(Path pluginDataDirectory) {
        Objects.requireNonNull(pluginDataDirectory, "pluginDataDirectory");
        return new PocketResizeJournal(pluginDataDirectory.resolve("doors").resolve(DIRECTORY));
    }

    synchronized List<PocketResizeIntent> load() throws IOException {
        if (loaded) {
            return List.copyOf(pending.values());
        }
        LinkedHashMap<UUID, PocketResizeIntent> restored = new LinkedHashMap<>();
        if (Files.isDirectory(directory)) {
            try (Stream<Path> paths = Files.list(directory)) {
                List<Path> files = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
                for (Path path : files) {
                    PocketResizeIntent intent = read(path);
                    if (!path.equals(file(intent.spaceId()))) {
                        throw new IOException("Pocket-resize journal filename does not match space ID at " + path);
                    }
                    if (restored.put(intent.spaceId(), intent) != null) {
                        throw new IOException("Duplicate pocket-resize journal for " + intent.spaceId());
                    }
                }
            }
        }
        pending.putAll(restored);
        loaded = true;
        return List.copyOf(pending.values());
    }

    synchronized PocketResizeIntent begin(PocketSpace source, PocketShell target) throws IOException {
        requireLoaded();
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        UUID spaceId = source.spaceId();
        if (pending.containsKey(spaceId)) {
            throw new IllegalStateException("pocket " + spaceId + " already has a pending resize");
        }
        PocketResizeIntent intent = new PocketResizeIntent(UUID.randomUUID(), spaceId, source.shell(), target);
        write(intent);
        pending.put(spaceId, intent);
        return intent;
    }

    synchronized void complete(PocketResizeIntent intent) throws IOException {
        requireLoaded();
        PocketResizeIntent required = Objects.requireNonNull(intent, "intent");
        PocketResizeIntent current = pending.get(required.spaceId());
        if (!required.equals(current)) {
            throw new IllegalStateException("pocket-resize journal changed for " + required.spaceId());
        }
        Files.deleteIfExists(file(required.spaceId()));
        pending.remove(required.spaceId());
    }

    synchronized Optional<PocketResizeIntent> pending(UUID spaceId) {
        requireLoaded();
        return Optional.ofNullable(pending.get(Objects.requireNonNull(spaceId, "spaceId")));
    }

    synchronized List<PocketResizeIntent> pending() {
        requireLoaded();
        return List.copyOf(pending.values());
    }

    private PocketResizeIntent read(Path path) throws IOException {
        try {
            JSONObject root = new JSONObject(VIO.readAll(path.toFile()));
            int schema = root.getInt("schema");
            if (schema != SCHEMA) {
                throw new IllegalArgumentException("unsupported pocket-resize journal schema " + schema);
            }
            return new PocketResizeIntent(
                UUID.fromString(root.getString("operationId")),
                UUID.fromString(root.getString("spaceId")),
                shell(root.getJSONObject("source")),
                shell(root.getJSONObject("target"))
            );
        } catch (RuntimeException exception) {
            throw new IOException("Could not parse pocket-resize journal at " + path, exception);
        }
    }

    private void write(PocketResizeIntent intent) throws IOException {
        JSONObject root = new JSONObject()
            .put("schema", SCHEMA)
            .put("operationId", intent.operationId().toString())
            .put("spaceId", intent.spaceId().toString())
            .put("source", shell(intent.source()))
            .put("target", shell(intent.target()));
        VIO.writeAll(file(intent.spaceId()).toFile(), root.toString(2));
    }

    private Path file(UUID spaceId) {
        return directory.resolve(spaceId + ".json");
    }

    private static JSONObject shell(PocketShell shell) {
        return new JSONObject()
            .put("size", shell.size())
            .put("shellMaterial", shell.shellMaterial())
            .put("returnDoorMaterial", shell.returnDoorMaterial());
    }

    private static PocketShell shell(JSONObject shell) {
        return new PocketShell(
            shell.getInt("size"),
            shell.getString("shellMaterial"),
            shell.getString("returnDoorMaterial")
        );
    }

    private void requireLoaded() {
        if (!loaded) {
            throw new IllegalStateException("pocket-resize journal is not loaded");
        }
    }
}
