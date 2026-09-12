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

/**
 * Pending world mutations inside pockets, one file per pocket.
 *
 * <p>The directory and file layout are unchanged from when this only recorded resizes, so a server
 * that crashed mid-resize before this build still finishes that resize on the next start: a file
 * without a {@code kind} is a RESIZE.</p>
 */
final class PocketMutationJournal {
    private static final int SCHEMA = 1;
    private static final String DIRECTORY = "pending-resizes";

    private final Path directory;
    private final LinkedHashMap<UUID, PocketMutationIntent> pending;

    private boolean loaded;

    PocketMutationJournal(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        pending = new LinkedHashMap<>();
    }

    static PocketMutationJournal under(Path pluginDataDirectory) {
        Objects.requireNonNull(pluginDataDirectory, "pluginDataDirectory");
        return new PocketMutationJournal(pluginDataDirectory.resolve("doors").resolve(DIRECTORY));
    }

    synchronized List<PocketMutationIntent> load() throws IOException {
        if (loaded) {
            return List.copyOf(pending.values());
        }
        LinkedHashMap<UUID, PocketMutationIntent> restored = new LinkedHashMap<>();
        if (Files.isDirectory(directory)) {
            try (Stream<Path> paths = Files.list(directory)) {
                List<Path> files = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
                for (Path path : files) {
                    PocketMutationIntent intent = read(path);
                    if (!path.equals(file(intent.spaceId()))) {
                        throw new IOException("Pocket-mutation journal filename does not match space ID at " + path);
                    }
                    if (restored.put(intent.spaceId(), intent) != null) {
                        throw new IOException("Duplicate pocket-mutation journal for " + intent.spaceId());
                    }
                }
            }
        }
        pending.putAll(restored);
        loaded = true;
        return List.copyOf(pending.values());
    }

    synchronized PocketMutationIntent beginResize(PocketSpace source, PocketShell target) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        return begin(PocketMutationIntent.resize(UUID.randomUUID(), source.spaceId(), source.shell(), target));
    }

    synchronized PocketMutationIntent beginPaste(PocketSpace source, String templateName) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(templateName, "templateName");
        return begin(PocketMutationIntent.paste(UUID.randomUUID(), source.spaceId(), source.shell(), templateName));
    }

    synchronized PocketMutationIntent beginReset(PocketSpace source, String templateName) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(templateName, "templateName");
        return begin(PocketMutationIntent.reset(UUID.randomUUID(), source.spaceId(), source.shell(), templateName));
    }

    synchronized void complete(PocketMutationIntent intent) throws IOException {
        requireLoaded();
        PocketMutationIntent required = Objects.requireNonNull(intent, "intent");
        PocketMutationIntent current = pending.get(required.spaceId());
        if (!required.equals(current)) {
            throw new IllegalStateException("pocket-mutation journal changed for " + required.spaceId());
        }
        Files.deleteIfExists(file(required.spaceId()));
        pending.remove(required.spaceId());
    }

    synchronized Optional<PocketMutationIntent> pending(UUID spaceId) {
        requireLoaded();
        return Optional.ofNullable(pending.get(Objects.requireNonNull(spaceId, "spaceId")));
    }

    synchronized List<PocketMutationIntent> pending() {
        requireLoaded();
        return List.copyOf(pending.values());
    }

    private PocketMutationIntent begin(PocketMutationIntent intent) throws IOException {
        requireLoaded();
        if (pending.containsKey(intent.spaceId())) {
            throw new IllegalStateException("pocket " + intent.spaceId() + " already has a pending mutation");
        }
        write(intent);
        pending.put(intent.spaceId(), intent);
        return intent;
    }

    private PocketMutationIntent read(Path path) throws IOException {
        try {
            JSONObject root = new JSONObject(VIO.readAll(path.toFile()));
            int schema = root.getInt("schema");
            if (schema != SCHEMA) {
                throw new IllegalArgumentException("unsupported pocket-mutation journal schema " + schema);
            }
            return new PocketMutationIntent(
                kind(root.optString("kind", PocketMutationIntent.Kind.RESIZE.name())),
                UUID.fromString(root.getString("operationId")),
                UUID.fromString(root.getString("spaceId")),
                shell(root.getJSONObject("source")),
                shell(root.getJSONObject("target")),
                root.optString("templateName", PocketMutationIntent.NO_TEMPLATE)
            );
        } catch (RuntimeException exception) {
            throw new IOException("Could not parse pocket-mutation journal at " + path, exception);
        }
    }

    /** Files written before intent kinds existed only ever recorded resizes. */
    private static PocketMutationIntent.Kind kind(String encoded) {
        for (PocketMutationIntent.Kind kind : PocketMutationIntent.Kind.values()) {
            if (kind.name().equals(encoded)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("unknown pocket mutation kind " + encoded);
    }

    private void write(PocketMutationIntent intent) throws IOException {
        JSONObject root = new JSONObject()
            .put("schema", SCHEMA)
            .put("operationId", intent.operationId().toString())
            .put("spaceId", intent.spaceId().toString())
            .put("kind", intent.kind().name())
            .put("templateName", intent.templateName())
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
            throw new IllegalStateException("pocket-mutation journal is not loaded");
        }
    }
}
