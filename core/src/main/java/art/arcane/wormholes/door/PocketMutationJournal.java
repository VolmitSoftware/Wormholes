package art.arcane.wormholes.door;

import art.arcane.wormholes.util.JsonDocuments;
import java.util.Map;
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
public final class PocketMutationJournal {
    private static final int SCHEMA = 1;
    private static final String DIRECTORY = "pending-resizes";

    private final Path directory;
    private final JsonDocuments json;
    private final LinkedHashMap<UUID, PocketMutationIntent> pending;

    private boolean loaded;

    public PocketMutationJournal(Path directory, JsonDocuments json) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        pending = new LinkedHashMap<>();
        this.json = Objects.requireNonNull(json, "json");
    }

    public static PocketMutationJournal under(Path pluginDataDirectory, JsonDocuments json) {
        Objects.requireNonNull(pluginDataDirectory, "pluginDataDirectory");
        return new PocketMutationJournal(pluginDataDirectory.resolve("doors").resolve(DIRECTORY), json);
    }

    public synchronized List<PocketMutationIntent> load() throws IOException {
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

    public synchronized PocketMutationIntent beginResize(PocketSpace source, PocketShell target) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        return begin(PocketMutationIntent.resize(UUID.randomUUID(), source.spaceId(), source.shell(), target));
    }

    public synchronized PocketMutationIntent beginPaste(PocketSpace source, String templateName) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(templateName, "templateName");
        return begin(PocketMutationIntent.paste(UUID.randomUUID(), source.spaceId(), source.shell(), templateName));
    }

    public synchronized PocketMutationIntent beginReset(PocketSpace source, String templateName) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(templateName, "templateName");
        return begin(PocketMutationIntent.reset(UUID.randomUUID(), source.spaceId(), source.shell(), templateName));
    }

    public synchronized void complete(PocketMutationIntent intent) throws IOException {
        requireLoaded();
        PocketMutationIntent required = Objects.requireNonNull(intent, "intent");
        PocketMutationIntent current = pending.get(required.spaceId());
        if (!required.equals(current)) {
            throw new IllegalStateException("pocket-mutation journal changed for " + required.spaceId());
        }
        Files.deleteIfExists(file(required.spaceId()));
        pending.remove(required.spaceId());
    }

    public synchronized Optional<PocketMutationIntent> pending(UUID spaceId) {
        requireLoaded();
        return Optional.ofNullable(pending.get(Objects.requireNonNull(spaceId, "spaceId")));
    }

    public synchronized List<PocketMutationIntent> pending() {
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
            Map<String, Object> root = json.decode(VIO.readAll(path.toFile()));
            int schema = ((Number) root.get("schema")).intValue();
            if (schema != SCHEMA) {
                throw new IllegalArgumentException("unsupported pocket-mutation journal schema " + schema);
            }
            return new PocketMutationIntent(
                kind((String) root.getOrDefault("kind", PocketMutationIntent.Kind.RESIZE.name())),
                UUID.fromString((String) root.get("operationId")),
                UUID.fromString((String) root.get("spaceId")),
                shell(root.get("source")),
                shell(root.get("target")),
                (String) root.getOrDefault("templateName", PocketMutationIntent.NO_TEMPLATE)
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
        Map<String, Object> root = Map.of(
            "schema", SCHEMA,
            "operationId", intent.operationId().toString(),
            "spaceId", intent.spaceId().toString(),
            "kind", intent.kind().name(),
            "templateName", intent.templateName(),
            "source", shell(intent.source()),
            "target", shell(intent.target()));
        VIO.writeAll(file(intent.spaceId()).toFile(), json.encode(root));
    }

    private Path file(UUID spaceId) {
        return directory.resolve(spaceId + ".json");
    }

    private static Map<String, Object> shell(PocketShell shell) {
        return Map.of("size", shell.size(), "shellMaterial", shell.shellMaterial(),
            "returnDoorMaterial", shell.returnDoorMaterial());
    }

    private static PocketShell shell(Object encoded) {
        if (!(encoded instanceof Map<?, ?> shell)) {
            throw new IllegalArgumentException("Expected a pocket shell object");
        }
        return new PocketShell(((Number) shell.get("size")).intValue(),
            (String) shell.get("shellMaterial"), (String) shell.get("returnDoorMaterial"));
    }

    private void requireLoaded() {
        if (!loaded) {
            throw new IllegalStateException("pocket-mutation journal is not loaded");
        }
    }
}
