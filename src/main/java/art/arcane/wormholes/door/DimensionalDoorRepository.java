package art.arcane.wormholes.door;

import art.arcane.volmlib.util.json.JSONArray;
import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.wormholes.util.VIO;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** JSON persistence for dimensional-door identity and allocation state. */
public final class DimensionalDoorRepository {
    private static final String STATE_FILE = "state.json";
    private static final int LEGACY_KIND_SCHEMA = 2;
    private static final int DOOR_MODE_ACCESS_SCHEMA = 3;
    private static final int PRE_TRAPDOOR_SCHEMA = 4;
    private static final int LEGACY_POLARITY_SCHEMA = 5;
    private static final Pattern NEXT_POCKET_SLOT = Pattern.compile("\\\"nextPocketSlot\\\"\\s*:\\s*(\\d+)");
    private static final Pattern POCKET_SLOT = Pattern.compile("\\\"slot\\\"\\s*:\\s*(\\d+)");

    private final Path stateFile;
    private final Path ticketDirectory;
    private DoorStoreSnapshot loaded;

    /** Uses {@code <plugin data>/doors/state.json}. */
    public static DimensionalDoorRepository under(Path pluginDataDirectory) {
        Objects.requireNonNull(pluginDataDirectory, "pluginDataDirectory");
        return new DimensionalDoorRepository(pluginDataDirectory.resolve("doors").resolve(STATE_FILE));
    }

    /** Accepts the exact state-file path, primarily for tests and migrations. */
    public DimensionalDoorRepository(Path stateFile) {
        this.stateFile = Objects.requireNonNull(stateFile, "stateFile").toAbsolutePath().normalize();
        ticketDirectory = this.stateFile.resolveSibling(this.stateFile.getFileName() + ".tickets");
    }

    public synchronized DoorStoreSnapshot load() throws IOException {
        if (loaded != null) {
            return loaded;
        }
        DoorStoreSnapshot stored = DoorStoreSnapshot.empty();
        int storedSchema = DoorStoreSnapshot.CURRENT_SCHEMA;
        if (Files.isRegularFile(stateFile)) {
            try {
                JSONObject root = new JSONObject(VIO.readAll(stateFile.toFile()));
                storedSchema = root.getInt("schema");
                stored = fromJson(root);
            } catch (RuntimeException e) {
                throw new IOException("Could not parse dimensional-door state at " + stateFile, e);
            }
        }

        LinkedHashMap<UUID, ReturnTicket> tickets = new LinkedHashMap<>();
        for (ReturnTicket ticket : stored.returnTickets()) {
            tickets.put(ticket.playerId(), ticket);
        }
        for (ReturnTicket ticket : loadTicketFiles()) {
            tickets.put(ticket.playerId(), ticket);
        }
        DoorStoreSnapshot combined = withTickets(stored, new ArrayList<>(tickets.values()));
        if (!stored.returnTickets().isEmpty()) {
            reconcileTicketFiles(combined.returnTickets());
            writeStateFile(combined);
        } else if (storedSchema != DoorStoreSnapshot.CURRENT_SCHEMA) {
            // upgrade the on-disk shape once so the migration never runs twice
            writeStateFile(combined);
        }
        loaded = combined;
        return loaded;
    }

    public synchronized void save(DoorStoreSnapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        reconcileTicketFiles(snapshot.returnTickets());
        writeStateFile(snapshot);
        loaded = snapshot;
    }

    public synchronized Optional<ReturnTicket> getReturnTicket(UUID playerId) throws IOException {
        return load().returnTicket(Objects.requireNonNull(playerId, "playerId"));
    }

    public synchronized void putReturnTicket(ReturnTicket ticket) throws IOException {
        ReturnTicket required = Objects.requireNonNull(ticket, "ticket");
        DoorStoreSnapshot current = load();
        writeTicketFile(required);
        loaded = current.withReturnTicket(required);
    }

    public synchronized Optional<ReturnTicket> removeReturnTicket(UUID playerId) throws IOException {
        Objects.requireNonNull(playerId, "playerId");
        DoorStoreSnapshot current = load();
        Optional<ReturnTicket> removed = current.returnTicket(playerId);
        if (removed.isPresent()) {
            Files.deleteIfExists(ticketFile(playerId));
            loaded = current.withoutReturnTicket(playerId);
        }
        return removed;
    }

    public Path stateFile() {
        return stateFile;
    }

    synchronized void saveState(DoorStoreSnapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        writeStateFile(snapshot);
        loaded = snapshot;
    }

    public synchronized long recoverNextPocketSlot() throws IOException {
        if (!Files.isRegularFile(stateFile)) {
            return 0L;
        }
        String encoded = VIO.readAll(stateFile.toFile());
        Matcher nextSlot = NEXT_POCKET_SLOT.matcher(encoded);
        long recoveredNextSlot = nextSlot.find() ? parseRecoveredSlot(nextSlot.group(1)) : -1L;

        long highestSlot = -1L;
        Matcher allocatedSlot = POCKET_SLOT.matcher(encoded);
        while (allocatedSlot.find()) {
            highestSlot = Math.max(highestSlot, parseRecoveredSlot(allocatedSlot.group(1)));
        }
        if (recoveredNextSlot >= 0L || highestSlot >= 0L) {
            long afterHighestSlot = highestSlot < 0L ? 0L : Math.incrementExact(highestSlot);
            return Math.max(recoveredNextSlot, afterHighestSlot);
        }
        throw new IOException("Could not recover the next pocket slot from " + stateFile);
    }

    private static JSONObject toJson(DoorStoreSnapshot snapshot) {
        JSONObject root = new JSONObject()
            .put("schema", snapshot.schema())
            .put("nextPocketSlot", snapshot.nextPocketSlot());

        JSONArray pairs = new JSONArray();
        for (DoorPairIdentity pair : snapshot.pairs()) {
            pairs.put(new JSONObject()
                .put("pairId", pair.pairId().toString())
                .put("endpointAItemId", pair.endpointAItemId().toString())
                .put("endpointBItemId", pair.endpointBItemId().toString()));
        }
        root.put("pairs", pairs);

        JSONArray endpoints = new JSONArray();
        for (PlacedDoorEndpoint endpoint : snapshot.endpoints()) {
            DoorPosition position = endpoint.position();
            DoorItemIdentity identity = endpoint.identity();
            JSONObject item = new JSONObject()
                .put("itemId", identity.itemId().toString())
                .put("kind", identity.kind().name())
                .put("form", identity.form().name());
            if (identity.pairId() != null) {
                item.put("pairId", identity.pairId().toString());
            }
            if (identity.pairEndpoint() != null) {
                item.put("pairEndpoint", identity.pairEndpoint().name());
            }
            if (identity.spaceId() != null) {
                item.put("spaceId", identity.spaceId().toString());
            }
            endpoints.put(new JSONObject()
                .put("worldId", position.worldId().toString())
                .put("worldKey", position.worldKey())
                .put("x", position.x())
                .put("y", position.y())
                .put("z", position.z())
                .put("openState", endpoint.openState().name())
                .put("item", item));
        }
        root.put("endpoints", endpoints);

        JSONArray spaces = new JSONArray();
        for (PocketSpace space : snapshot.spaces()) {
            spaces.put(new JSONObject()
                .put("spaceId", space.spaceId().toString())
                .put("bindingKind", space.binding().kind().name())
                .put("bindingId", space.binding().bindingId().toString())
                .put("slot", space.slot())
                .put("centerX", space.centerX())
                .put("centerY", space.centerY())
                .put("centerZ", space.centerZ()));
        }
        root.put("spaces", spaces);

        root.put("returnTickets", new JSONArray());

        JSONArray access = new JSONArray();
        for (DoorAccessRecord record : snapshot.accessRecords()) {
            JSONArray players = new JSONArray();
            for (Map.Entry<UUID, DoorAccessState> listed : record.players().entrySet()) {
                players.put(new JSONObject()
                    .put("id", listed.getKey().toString())
                    .put("state", listed.getValue().name()));
            }
            access.put(new JSONObject()
                .put("itemId", record.itemId().toString())
                .put("ownerId", record.ownerId().toString())
                .put("players", players));
        }
        root.put("access", access);
        return root;
    }

    private void writeStateFile(DoorStoreSnapshot snapshot) throws IOException {
        VIO.writeAll(stateFile.toFile(), toJson(snapshot).toString(2));
    }

    private void reconcileTicketFiles(List<ReturnTicket> tickets) throws IOException {
        Set<String> expectedFiles = new HashSet<>();
        for (ReturnTicket ticket : tickets) {
            expectedFiles.add(ticketFile(ticket.playerId()).getFileName().toString());
            writeTicketFile(ticket);
        }
        if (!Files.isDirectory(ticketDirectory)) {
            return;
        }
        try (Stream<Path> paths = Files.list(ticketDirectory)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (!expectedFiles.contains(path.getFileName().toString())) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private List<ReturnTicket> loadTicketFiles() throws IOException {
        if (!Files.isDirectory(ticketDirectory)) {
            return List.of();
        }
        List<ReturnTicket> tickets = new ArrayList<>();
        try (Stream<Path> paths = Files.list(ticketDirectory)) {
            List<Path> files = paths
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".json"))
                .sorted()
                .toList();
            for (Path path : files) {
                try {
                    ReturnTicket ticket = ticketFromJson(new JSONObject(VIO.readAll(path.toFile())));
                    if (!path.equals(ticketFile(ticket.playerId()))) {
                        throw new IOException("Return-ticket filename does not match player ID at " + path);
                    }
                    tickets.add(ticket);
                } catch (RuntimeException exception) {
                    throw new IOException("Could not parse dimensional-door return ticket at " + path, exception);
                }
            }
        }
        return tickets;
    }

    private void writeTicketFile(ReturnTicket ticket) throws IOException {
        VIO.writeAll(ticketFile(ticket.playerId()).toFile(), ticketToJson(ticket).toString(2));
    }

    private Path ticketFile(UUID playerId) {
        return ticketDirectory.resolve(playerId + ".json");
    }

    private static JSONObject ticketToJson(ReturnTicket ticket) {
        return new JSONObject()
            .put("playerId", ticket.playerId().toString())
            .put("sourceEndpointId", ticket.sourceEndpointId().toString())
            .put("sourceWorldId", ticket.sourceWorldId().toString())
            .put("sourceWorldKey", ticket.sourceWorldKey())
            .put("x", ticket.x())
            .put("y", ticket.y())
            .put("z", ticket.z())
            .put("yaw", ticket.yaw())
            .put("pitch", ticket.pitch());
    }

    private static ReturnTicket ticketFromJson(JSONObject ticket) {
        return new ReturnTicket(
            uuid(ticket, "playerId"),
            uuid(ticket, "sourceEndpointId"),
            uuid(ticket, "sourceWorldId"),
            ticket.getString("sourceWorldKey"),
            ticket.getDouble("x"),
            ticket.getDouble("y"),
            ticket.getDouble("z"),
            (float) ticket.getDouble("yaw"),
            (float) ticket.getDouble("pitch")
        );
    }

    private static DoorStoreSnapshot withTickets(DoorStoreSnapshot snapshot, List<ReturnTicket> tickets) {
        return new DoorStoreSnapshot(
            snapshot.schema(),
            snapshot.nextPocketSlot(),
            snapshot.pairs(),
            snapshot.endpoints(),
            snapshot.spaces(),
            tickets,
            snapshot.accessRecords()
        );
    }

    private static DoorStoreSnapshot fromJson(JSONObject root) {
        int schema = root.getInt("schema");
        if (schema < LEGACY_KIND_SCHEMA || schema > DoorStoreSnapshot.CURRENT_SCHEMA) {
            throw new IllegalArgumentException("unsupported dimensional-door schema " + schema);
        }
        long nextPocketSlot = root.getLong("nextPocketSlot");

        JSONArray pairJson = root.getJSONArray("pairs");
        List<DoorPairIdentity> pairs = new ArrayList<>(pairJson.length());
        for (int i = 0; i < pairJson.length(); i++) {
            JSONObject pair = pairJson.getJSONObject(i);
            pairs.add(new DoorPairIdentity(
                uuid(pair, "pairId"),
                uuid(pair, "endpointAItemId"),
                uuid(pair, "endpointBItemId")
            ));
        }

        JSONArray endpointJson = root.getJSONArray("endpoints");
        List<PlacedDoorEndpoint> endpoints = new ArrayList<>(endpointJson.length());
        for (int i = 0; i < endpointJson.length(); i++) {
            JSONObject endpoint = endpointJson.getJSONObject(i);
            JSONObject item = endpoint.getJSONObject("item");
            DoorKind kind = decodeDoorKind(schema, item.getString("kind"));
            DoorItemIdentity identity = new DoorItemIdentity(
                uuid(item, "itemId"),
                kind,
                decodeDoorForm(schema, item),
                optionalUuid(item, "pairId"),
                item.has("pairEndpoint") ? PairEndpoint.valueOf(item.getString("pairEndpoint")) : null,
                optionalUuid(item, "spaceId")
            );
            endpoints.add(new PlacedDoorEndpoint(
                new DoorPosition(
                    uuid(endpoint, "worldId"),
                    endpoint.getString("worldKey"),
                    endpoint.getInt("x"),
                    endpoint.getInt("y"),
                    endpoint.getInt("z")
                ),
                identity,
                decodeOpenState(schema, endpoint)
            ));
        }

        JSONArray spaceJson = root.getJSONArray("spaces");
        List<PocketSpace> spaces = new ArrayList<>(spaceJson.length());
        for (int i = 0; i < spaceJson.length(); i++) {
            JSONObject space = spaceJson.getJSONObject(i);
            spaces.add(new PocketSpace(
                uuid(space, "spaceId"),
                new PocketBinding(
                    PocketBindingKind.valueOf(space.getString("bindingKind")),
                    uuid(space, "bindingId")
                ),
                space.getLong("slot"),
                space.getInt("centerX"),
                space.getInt("centerY"),
                space.getInt("centerZ")
            ));
        }

        JSONArray ticketJson = root.getJSONArray("returnTickets");
        List<ReturnTicket> tickets = new ArrayList<>(ticketJson.length());
        for (int i = 0; i < ticketJson.length(); i++) {
            JSONObject ticket = ticketJson.getJSONObject(i);
            tickets.add(new ReturnTicket(
                uuid(ticket, "playerId"),
                uuid(ticket, "sourceEndpointId"),
                uuid(ticket, "sourceWorldId"),
                ticket.getString("sourceWorldKey"),
                ticket.getDouble("x"),
                ticket.getDouble("y"),
                ticket.getDouble("z"),
                (float) ticket.getDouble("yaw"),
                (float) ticket.getDouble("pitch")
            ));
        }
        return new DoorStoreSnapshot(
            DoorStoreSnapshot.CURRENT_SCHEMA,
            nextPocketSlot,
            pairs,
            endpoints,
            spaces,
            tickets,
            decodeAccessRecords(root.optJSONArray("access"), schema)
        );
    }

    private static List<DoorAccessRecord> decodeAccessRecords(JSONArray accessJson, int schema) {
        if (accessJson == null) {
            return List.of();
        }
        List<DoorAccessRecord> accessRecords = new ArrayList<>(accessJson.length());
        for (int i = 0; i < accessJson.length(); i++) {
            JSONObject record = accessJson.getJSONObject(i);
            accessRecords.add(new DoorAccessRecord(
                uuid(record, "itemId"),
                uuid(record, "ownerId"),
                schema <= DOOR_MODE_ACCESS_SCHEMA
                    ? migrateAccessPlayers(record.optString("mode"), record.optJSONArray("players"))
                    : decodeAccessPlayers(record.optJSONArray("players"))
            ));
        }
        return accessRecords;
    }

    /** Schema 3 carried one door-wide mode plus a flat player list; every listed player inherits it. */
    private static Map<UUID, DoorAccessState> migrateAccessPlayers(String mode, JSONArray playerJson) {
        if (playerJson == null) {
            return Map.of();
        }
        DoorAccessState migrated = switch (mode == null ? "" : mode.toUpperCase(Locale.ROOT)) {
            case "WHITELIST" -> DoorAccessState.WHITELIST;
            case "BLACKLIST" -> DoorAccessState.BLACKLIST;
            default -> DoorAccessState.NEUTRAL;
        };
        LinkedHashMap<UUID, DoorAccessState> players = new LinkedHashMap<>();
        for (int i = 0; i < playerJson.length(); i++) {
            players.put(UUID.fromString(playerJson.getString(i)), migrated);
        }
        return players;
    }

    private static Map<UUID, DoorAccessState> decodeAccessPlayers(JSONArray playerJson) {
        if (playerJson == null) {
            return Map.of();
        }
        LinkedHashMap<UUID, DoorAccessState> players = new LinkedHashMap<>();
        for (int i = 0; i < playerJson.length(); i++) {
            JSONObject listed = playerJson.getJSONObject(i);
            players.put(uuid(listed, "id"), DoorAccessState.valueOf(listed.getString("state")));
        }
        return players;
    }

    /** Everything written before trapdoors existed is a hinged door. */
    private static DoorForm decodeDoorForm(int schema, JSONObject item) {
        return schema <= PRE_TRAPDOOR_SCHEMA ? DoorForm.DOOR : DoorForm.valueOf(item.getString("form"));
    }

    private static DoorOpenState decodeOpenState(int schema, JSONObject endpoint) {
        if (schema <= PRE_TRAPDOOR_SCHEMA) {
            return DoorOpenState.OPEN;
        }
        if (schema == LEGACY_POLARITY_SCHEMA) {
            return DoorOpenState.fromLegacy(endpoint.getBoolean("activeWhenOpen"));
        }
        return DoorOpenState.valueOf(endpoint.getString("openState"));
    }

    private static DoorKind decodeDoorKind(int schema, String value) {
        if (schema > LEGACY_KIND_SCHEMA) {
            return DoorKind.valueOf(value);
        }
        return switch (value) {
            case "PAIRED" -> DoorKind.PAIR;
            case "IRON" -> DoorKind.PUBLIC;
            case "PERSONAL" -> DoorKind.PERSONAL;
            case "RETURN" -> DoorKind.RETURN;
            default -> throw new IllegalArgumentException("Unknown legacy door kind " + value);
        };
    }

    private static UUID uuid(JSONObject json, String key) {
        return UUID.fromString(json.getString(key));
    }

    private static UUID optionalUuid(JSONObject json, String key) {
        return json.has(key) ? uuid(json, key) : null;
    }

    private static long parseRecoveredSlot(String encoded) throws IOException {
        try {
            return Long.parseLong(encoded);
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid dimensional-door pocket slot '" + encoded + "'", exception);
        }
    }
}
