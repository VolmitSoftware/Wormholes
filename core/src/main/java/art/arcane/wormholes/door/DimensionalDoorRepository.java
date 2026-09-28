package art.arcane.wormholes.door;

import art.arcane.wormholes.util.JsonDocuments;
import art.arcane.wormholes.util.VIO;

import java.io.IOException;
import java.math.BigDecimal;
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
    private static final int PRE_POCKET_SHELL_SCHEMA = 6;
    private static final int PRE_POCKET_V2_SCHEMA = 7;
    private static final int LEGACY_POCKET_SIZE = 32;
    private static final Pattern NEXT_POCKET_SLOT = Pattern.compile("\\\"nextPocketSlot\\\"\\s*:\\s*(\\d+)");
    private static final Pattern POCKET_SLOT = Pattern.compile("\\\"slot\\\"\\s*:\\s*(\\d+)");

    private final JsonDocuments documents;
    private final Path stateFile;
    private final Path ticketDirectory;
    private DoorStoreSnapshot loaded;

    /** Uses {@code <plugin data>/doors/state.json}. */
    public static DimensionalDoorRepository under(Path pluginDataDirectory, JsonDocuments documents) {
        Objects.requireNonNull(pluginDataDirectory, "pluginDataDirectory");
        return new DimensionalDoorRepository(pluginDataDirectory.resolve("doors").resolve(STATE_FILE), documents);
    }

    /** Accepts the exact state-file path, primarily for tests and migrations. */
    public DimensionalDoorRepository(Path stateFile, JsonDocuments documents) {
        this.documents = Objects.requireNonNull(documents, "documents");
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
                Map<String, Object> root = documents.decode(VIO.readAll(stateFile.toFile()));
                storedSchema = integer(root, "schema");
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

    private static Map<String, Object> toJson(DoorStoreSnapshot snapshot) {
        Map<String, Object> root = document("schema", snapshot.schema(), "nextPocketSlot", snapshot.nextPocketSlot());

        List<Object> pairs = new ArrayList<>();
        for (DoorPairIdentity pair : snapshot.pairs()) {
            pairs.add(document("pairId", pair.pairId().toString(), "endpointAItemId", pair.endpointAItemId().toString(), "endpointBItemId", pair.endpointBItemId().toString()));
        }
        root.put("pairs", pairs);

        List<Object> endpoints = new ArrayList<>();
        for (PlacedDoorEndpoint endpoint : snapshot.endpoints()) {
            DoorPosition position = endpoint.position();
            DoorItemIdentity identity = endpoint.identity();
            Map<String, Object> item = document("itemId", identity.itemId().toString(), "kind", identity.kind().name(), "form", identity.form().name());
            if (identity.pairId() != null) {
                item.put("pairId", identity.pairId().toString());
            }
            if (identity.pairEndpoint() != null) {
                item.put("pairEndpoint", identity.pairEndpoint().name());
            }
            if (identity.spaceId() != null) {
                item.put("spaceId", identity.spaceId().toString());
            }
            endpoints.add(document("worldId", position.worldId().toString(), "worldKey", position.worldKey(), "x", position.x(), "y", position.y(), "z", position.z(), "openState", endpoint.openState().name(), "projection", endpoint.projection().name(), "item", item));
        }
        root.put("endpoints", endpoints);

        List<Object> spaces = new ArrayList<>();
        for (PocketSpace space : snapshot.spaces()) {
            Map<String, Object> encoded = document("spaceId", space.spaceId().toString(), "bindingKind", space.binding().kind().name(), "bindingId", space.binding().bindingId().toString(), "slot", space.slot(), "centerX", space.centerX(), "centerY", space.centerY(), "centerZ", space.centerZ(), "shell", document("size", space.shell().size(), "shellMaterial", space.shell().shellMaterial(), "returnDoorMaterial", space.shell().returnDoorMaterial()), "templateName", space.templateName(), "rules", rulesToJson(space.rules()), "roster", rosterToJson(space.roster()), "rooms", roomsToJson(space.rooms()));
            if (space.instance() != null) {
                encoded.put("instance", instanceToJson(space.instance()));
            }
            spaces.add(encoded);
        }
        root.put("spaces", spaces);

        root.put("returnTickets", new ArrayList<>());

        List<Object> access = new ArrayList<>();
        for (DoorAccessRecord record : snapshot.accessRecords()) {
            List<Object> players = new ArrayList<>();
            for (Map.Entry<UUID, DoorAccessState> listed : record.players().entrySet()) {
                players.add(document("id", listed.getKey().toString(), "state", listed.getValue().name()));
            }
            access.add(document("itemId", record.itemId().toString(), "ownerId", record.ownerId().toString(), "players", players));
        }
        root.put("access", access);
        return root;
    }

    private void writeStateFile(DoorStoreSnapshot snapshot) throws IOException {
        VIO.writeAll(stateFile.toFile(), documents.encode(toJson(snapshot)));
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
                    ReturnTicket ticket = ticketFromJson(documents.decode(VIO.readAll(path.toFile())));
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
        VIO.writeAll(ticketFile(ticket.playerId()).toFile(), documents.encode(ticketToJson(ticket)));
    }

    private Path ticketFile(UUID playerId) {
        return ticketDirectory.resolve(playerId + ".json");
    }

    private static Map<String, Object> ticketToJson(ReturnTicket ticket) {
        return document("playerId", ticket.playerId().toString(), "sourceEndpointId", ticket.sourceEndpointId().toString(), "sourceWorldId", ticket.sourceWorldId().toString(), "sourceWorldKey", ticket.sourceWorldKey(), "x", ticket.x(), "y", ticket.y(), "z", ticket.z(), "yaw", ticket.yaw(), "pitch", ticket.pitch());
    }

    private static ReturnTicket ticketFromJson(Map<String, Object> ticket) {
        return new ReturnTicket(
            uuid(ticket, "playerId"),
            uuid(ticket, "sourceEndpointId"),
            uuid(ticket, "sourceWorldId"),
            string(ticket, "sourceWorldKey"),
            number(ticket, "x"),
            number(ticket, "y"),
            number(ticket, "z"),
            (float) number(ticket, "yaw"),
            (float) number(ticket, "pitch")
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

    private static DoorStoreSnapshot fromJson(Map<String, Object> root) {
        int schema = integer(root, "schema");
        if (schema < LEGACY_KIND_SCHEMA || schema > DoorStoreSnapshot.CURRENT_SCHEMA) {
            throw new IllegalArgumentException("unsupported dimensional-door schema " + schema);
        }
        long nextPocketSlot = longNumber(root, "nextPocketSlot");

        List<Object> pairJson = array(root, "pairs");
        List<DoorPairIdentity> pairs = new ArrayList<>(pairJson.size());
        for (int i = 0; i < pairJson.size(); i++) {
            Map<String, Object> pair = object(pairJson.get(i));
            pairs.add(new DoorPairIdentity(
                uuid(pair, "pairId"),
                uuid(pair, "endpointAItemId"),
                uuid(pair, "endpointBItemId")
            ));
        }

        List<Object> endpointJson = array(root, "endpoints");
        List<PlacedDoorEndpoint> endpoints = new ArrayList<>(endpointJson.size());
        for (int i = 0; i < endpointJson.size(); i++) {
            Map<String, Object> endpoint = object(endpointJson.get(i));
            Map<String, Object> item = objectAt(endpoint, "item");
            DoorKind kind = decodeDoorKind(schema, string(item, "kind"));
            DoorItemIdentity identity = new DoorItemIdentity(
                uuid(item, "itemId"),
                kind,
                decodeDoorForm(schema, item),
                optionalUuid(item, "pairId"),
                item.containsKey("pairEndpoint") ? PairEndpoint.valueOf(string(item, "pairEndpoint")) : null,
                optionalUuid(item, "spaceId")
            );
            endpoints.add(new PlacedDoorEndpoint(
                new DoorPosition(
                    uuid(endpoint, "worldId"),
                    string(endpoint, "worldKey"),
                    integer(endpoint, "x"),
                    integer(endpoint, "y"),
                    integer(endpoint, "z")
                ),
                identity,
                decodeOpenState(schema, endpoint),
                decodeProjection(schema, endpoint)
            ));
        }

        List<Object> spaceJson = array(root, "spaces");
        List<PocketSpace> spaces = new ArrayList<>(spaceJson.size());
        for (int i = 0; i < spaceJson.size(); i++) {
            Map<String, Object> space = object(spaceJson.get(i));
            spaces.add(new PocketSpace(
                uuid(space, "spaceId"),
                new PocketBinding(
                    PocketBindingKind.valueOf(string(space, "bindingKind")),
                    uuid(space, "bindingId")
                ),
                longNumber(space, "slot"),
                integer(space, "centerX"),
                integer(space, "centerY"),
                integer(space, "centerZ"),
                decodePocketShell(schema, space),
                schema <= PRE_POCKET_V2_SCHEMA ? PocketSpace.NO_TEMPLATE : optionalString(space, "templateName", PocketSpace.NO_TEMPLATE),
                decodeRules(schema, space),
                decodeRoster(schema, space),
                decodeRooms(schema, space),
                decodeInstance(schema, space)
            ));
        }

        List<Object> ticketJson = array(root, "returnTickets");
        List<ReturnTicket> tickets = new ArrayList<>(ticketJson.size());
        for (int i = 0; i < ticketJson.size(); i++) {
            Map<String, Object> ticket = object(ticketJson.get(i));
            tickets.add(new ReturnTicket(
                uuid(ticket, "playerId"),
                uuid(ticket, "sourceEndpointId"),
                uuid(ticket, "sourceWorldId"),
                string(ticket, "sourceWorldKey"),
                number(ticket, "x"),
                number(ticket, "y"),
                number(ticket, "z"),
                (float) number(ticket, "yaw"),
                (float) number(ticket, "pitch")
            ));
        }
        return new DoorStoreSnapshot(
            DoorStoreSnapshot.CURRENT_SCHEMA,
            nextPocketSlot,
            pairs,
            endpoints,
            spaces,
            tickets,
            decodeAccessRecords(optionalArray(root, "access"), schema)
        );
    }

    private static Map<String, Object> rulesToJson(PocketRules rules) {
        return document("mobs", rules.mobs(), "pvp", rules.pvp(), "keepInventory", rules.keepInventory(), "fixedTime", rules.fixedTime(), "build", rules.build().name());
    }

    private static List<Object> rosterToJson(PocketRoster roster) {
        List<Object> members = new ArrayList<>();
        for (Map.Entry<UUID, PocketRole> member : roster.members().entrySet()) {
            members.add(document("id", member.getKey().toString(), "role", member.getValue().name()));
        }
        return members;
    }

    private static List<Object> roomsToJson(List<PocketRoom> rooms) {
        List<Object> encoded = new ArrayList<>();
        for (PocketRoom room : rooms) {
            Map<String, Object> entry = document("index", room.index(), "offsetX", room.offsetX(), "offsetZ", room.offsetZ());
            if (room.doorItemId() != null) {
                entry.put("doorItemId", room.doorItemId().toString());
            }
            if (room.linkedDoorItemId() != null) {
                entry.put("linkedDoorItemId", room.linkedDoorItemId().toString());
            }
            encoded.add(entry);
        }
        return encoded;
    }

    private static Map<String, Object> instanceToJson(PocketInstanceInfo instance) {
        return document("templateName", instance.templateName(), "bindingKind", instance.ownerBinding().kind().name(), "bindingId", instance.ownerBinding().bindingId().toString(), "createdAtMillis", instance.createdAtMillis(), "resetPolicy", instance.resetPolicy(), "lastOccupiedMillis", instance.lastOccupiedMillis(), "lastResetMillis", instance.lastResetMillis());
    }

    /** Doors written before the projection toggle existed follow the global flag. */
    private static DoorProjectionState decodeProjection(int schema, Map<String, Object> endpoint) {
        return schema <= PRE_POCKET_V2_SCHEMA
            ? DoorProjectionState.INHERIT
            : DoorProjectionState.valueOf(string(endpoint, "projection"));
    }

    private static PocketRules decodeRules(int schema, Map<String, Object> space) {
        Map<String, Object> rules = schema <= PRE_POCKET_V2_SCHEMA ? null : optionalObject(space, "rules");
        if (rules == null) {
            return PocketRules.defaults();
        }
        PocketRules defaults = PocketRules.defaults();
        return new PocketRules(
            optionalBoolean(rules, "mobs", defaults.mobs()),
            optionalBoolean(rules, "pvp", defaults.pvp()),
            optionalBoolean(rules, "keepInventory", defaults.keepInventory()),
            optionalLong(rules, "fixedTime", defaults.fixedTime()),
            PocketRules.BuildPolicy.parse(optionalString(rules, "build", defaults.build().name()))
        );
    }

    private static PocketRoster decodeRoster(int schema, Map<String, Object> space) {
        List<Object> members = schema <= PRE_POCKET_V2_SCHEMA ? null : optionalArray(space, "roster");
        if (members == null) {
            return PocketRoster.empty();
        }
        LinkedHashMap<UUID, PocketRole> decoded = new LinkedHashMap<>();
        for (int i = 0; i < members.size(); i++) {
            Map<String, Object> member = object(members.get(i));
            decoded.put(uuid(member, "id"), PocketRole.valueOf(string(member, "role")));
        }
        return new PocketRoster(decoded);
    }

    private static List<PocketRoom> decodeRooms(int schema, Map<String, Object> space) {
        List<Object> rooms = schema <= PRE_POCKET_V2_SCHEMA ? null : optionalArray(space, "rooms");
        if (rooms == null) {
            return List.of();
        }
        List<PocketRoom> decoded = new ArrayList<>(rooms.size());
        for (int i = 0; i < rooms.size(); i++) {
            Map<String, Object> room = object(rooms.get(i));
            decoded.add(new PocketRoom(
                integer(room, "index"),
                integer(room, "offsetX"),
                integer(room, "offsetZ"),
                optionalUuid(room, "doorItemId"),
                optionalUuid(room, "linkedDoorItemId")
            ));
        }
        return List.copyOf(decoded);
    }

    private static PocketInstanceInfo decodeInstance(int schema, Map<String, Object> space) {
        Map<String, Object> instance = schema <= PRE_POCKET_V2_SCHEMA ? null : optionalObject(space, "instance");
        if (instance == null) {
            return null;
        }
        return new PocketInstanceInfo(
            string(instance, "templateName"),
            new PocketBinding(
                PocketBindingKind.valueOf(string(instance, "bindingKind")),
                uuid(instance, "bindingId")
            ),
            longNumber(instance, "createdAtMillis"),
            string(instance, "resetPolicy"),
            longNumber(instance, "lastOccupiedMillis"),
            optionalLong(instance, "lastResetMillis", longNumber(instance, "lastOccupiedMillis"))
        );
    }

    /** Every pocket written before shells were configurable is the original 32-block smooth-stone room. */
    private static PocketShell decodePocketShell(int schema, Map<String, Object> space) {
        if (schema <= PRE_POCKET_SHELL_SCHEMA) {
            return new PocketShell(
                LEGACY_POCKET_SIZE,
                PocketShell.DEFAULT_SHELL_MATERIAL,
                PocketShell.DEFAULT_RETURN_DOOR_MATERIAL
            );
        }
        Map<String, Object> shell = optionalObject(space, "shell");
        if (shell == null) {
            return PocketShell.defaults();
        }
        PocketShell defaults = PocketShell.defaults();
        return new PocketShell(
            optionalInteger(shell, "size", defaults.size()),
            optionalString(shell, "shellMaterial", defaults.shellMaterial()),
            optionalString(shell, "returnDoorMaterial", defaults.returnDoorMaterial())
        );
    }

    private static List<DoorAccessRecord> decodeAccessRecords(List<Object> accessJson, int schema) {
        if (accessJson == null) {
            return List.of();
        }
        List<DoorAccessRecord> accessRecords = new ArrayList<>(accessJson.size());
        for (int i = 0; i < accessJson.size(); i++) {
            Map<String, Object> record = object(accessJson.get(i));
            accessRecords.add(new DoorAccessRecord(
                uuid(record, "itemId"),
                uuid(record, "ownerId"),
                schema <= DOOR_MODE_ACCESS_SCHEMA
                    ? migrateAccessPlayers(optionalString(record, "mode"), optionalArray(record, "players"))
                    : decodeAccessPlayers(optionalArray(record, "players"))
            ));
        }
        return accessRecords;
    }

    /** Schema 3 carried one door-wide mode plus a flat player list; every listed player inherits it. */
    private static Map<UUID, DoorAccessState> migrateAccessPlayers(String mode, List<Object> playerJson) {
        if (playerJson == null) {
            return Map.of();
        }
        DoorAccessState migrated = switch (mode == null ? "" : mode.toUpperCase(Locale.ROOT)) {
            case "WHITELIST" -> DoorAccessState.WHITELIST;
            case "BLACKLIST" -> DoorAccessState.BLACKLIST;
            default -> DoorAccessState.NEUTRAL;
        };
        LinkedHashMap<UUID, DoorAccessState> players = new LinkedHashMap<>();
        for (int i = 0; i < playerJson.size(); i++) {
            players.put(UUID.fromString((String) playerJson.get(i)), migrated);
        }
        return players;
    }

    private static Map<UUID, DoorAccessState> decodeAccessPlayers(List<Object> playerJson) {
        if (playerJson == null) {
            return Map.of();
        }
        LinkedHashMap<UUID, DoorAccessState> players = new LinkedHashMap<>();
        for (int i = 0; i < playerJson.size(); i++) {
            Map<String, Object> listed = object(playerJson.get(i));
            players.put(uuid(listed, "id"), DoorAccessState.valueOf(string(listed, "state")));
        }
        return players;
    }

    /** Everything written before trapdoors existed is a hinged door. */
    private static DoorForm decodeDoorForm(int schema, Map<String, Object> item) {
        return schema <= PRE_TRAPDOOR_SCHEMA ? DoorForm.DOOR : DoorForm.valueOf(string(item, "form"));
    }

    private static DoorOpenState decodeOpenState(int schema, Map<String, Object> endpoint) {
        if (schema <= PRE_TRAPDOOR_SCHEMA) {
            return DoorOpenState.OPEN;
        }
        if (schema == LEGACY_POLARITY_SCHEMA) {
            return DoorOpenState.fromLegacy(bool(endpoint, "activeWhenOpen"));
        }
        return DoorOpenState.valueOf(string(endpoint, "openState"));
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

    private static UUID uuid(Map<String, Object> json, String key) {
        return UUID.fromString(string(json, key));
    }

    private static UUID optionalUuid(Map<String, Object> json, String key) {
        return json.containsKey(key) ? uuid(json, key) : null;
    }

    private static long parseRecoveredSlot(String encoded) throws IOException {
        try {
            return Long.parseLong(encoded);
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid dimensional-door pocket slot '" + encoded + "'", exception);
        }
    }

    private static Map<String, Object> document(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>(entries.length / 2);
        for (int i = 0; i < entries.length; i += 2) {
            result.put((String) entries[i], entries[i + 1]);
        }
        return result;
    }

    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            throw new IllegalArgumentException("Expected a dimensional-door object");
        }
        Map<String, Object> result = new LinkedHashMap<>(source.size());
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("Expected a dimensional-door field name");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static Map<String, Object> objectAt(Map<String, Object> source, String key) {
        return object(source.get(key));
    }

    private static Map<String, Object> optionalObject(Map<String, Object> source, String key) {
        return source.get(key) instanceof Map<?, ?> ? objectAt(source, key) : null;
    }

    private static List<Object> array(Map<String, Object> source, String key) {
        if (!(source.get(key) instanceof List<?> values)) {
            throw new IllegalArgumentException("Expected dimensional-door array " + key);
        }
        return new ArrayList<>(values);
    }

    private static List<Object> optionalArray(Map<String, Object> source, String key) {
        return source.get(key) instanceof List<?> ? array(source, key) : null;
    }

    private static String string(Map<String, Object> source, String key) {
        if (!(source.get(key) instanceof String value)) {
            throw new IllegalArgumentException("Expected dimensional-door string " + key);
        }
        return value;
    }

    private static Number numeric(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value instanceof Number number) {
            return number;
        }
        if (value instanceof String encoded) {
            return new BigDecimal(encoded);
        }
        throw new IllegalArgumentException("Expected dimensional-door number " + key);
    }

    private static int integer(Map<String, Object> source, String key) {
        return numeric(source, key).intValue();
    }

    private static long longNumber(Map<String, Object> source, String key) {
        return numeric(source, key).longValue();
    }

    private static double number(Map<String, Object> source, String key) {
        return numeric(source, key).doubleValue();
    }

    private static boolean bool(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String encoded && (encoded.equalsIgnoreCase("true") || encoded.equalsIgnoreCase("false"))) {
            return Boolean.parseBoolean(encoded);
        }
        throw new IllegalArgumentException("Expected dimensional-door boolean " + key);
    }

    private static String optionalString(Map<String, Object> source, String key, String fallback) {
        Object value = source.get(key);
        return value == null ? fallback : value.toString();
    }

    private static String optionalString(Map<String, Object> source, String key) {
        return optionalString(source, key, "");
    }

    private static int optionalInteger(Map<String, Object> source, String key, int fallback) {
        try {
            return integer(source, key);
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private static long optionalLong(Map<String, Object> source, String key, long fallback) {
        try {
            return longNumber(source, key);
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private static boolean optionalBoolean(Map<String, Object> source, String key, boolean fallback) {
        try {
            return bool(source, key);
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }
}
