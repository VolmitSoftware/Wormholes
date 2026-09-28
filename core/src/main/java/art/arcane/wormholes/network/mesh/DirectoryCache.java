package art.arcane.wormholes.network.mesh;

import art.arcane.wormholes.network.PortalInfo;
import art.arcane.wormholes.network.RemotePortalRegistry;
import art.arcane.wormholes.util.VIO;
import art.arcane.wormholes.util.JsonDocuments;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Last-known remote portal directory, persisted as mesh/directory.json so a restart shows every
 * linked portal as stale instead of empty until its peer sends a fresh directory. Removed portals
 * are tombstoned per peer so a hydrate never brings them back until the peer re-creates them.
 */
public final class DirectoryCache implements RemotePortalRegistry.Listener {
    private static final String DIRECTORY_FILE = "directory.json";
    private static final String KEY_PEERS = "peers";
    private static final String KEY_PORTALS = "portals";
    private static final String KEY_TOMBSTONES = "tombstones";

    private record PeerEntry(Map<UUID, PortalInfo> portals, Set<UUID> tombstones) {
    }

    private final Path file;
    private final JsonDocuments documents;
    private final Map<String, PeerEntry> peers = new ConcurrentHashMap<>();

    private DirectoryCache(Path file, JsonDocuments documents) {
        this.file = file;
        this.documents = documents;
    }

    public static DirectoryCache loadOrCreate(Path dataDirectory, JsonDocuments documents) throws IOException {
        Path meshDirectory = dataDirectory.resolve("mesh");
        Files.createDirectories(meshDirectory);
        DirectoryCache cache = new DirectoryCache(meshDirectory.resolve(DIRECTORY_FILE), documents);
        cache.load();
        return cache;
    }

    /** Replaces the cached portal list for a peer; a portal present again drops its tombstone. */
    public void record(String peerName, List<PortalInfo> portals) {
        if (peerName == null || peerName.isBlank()) {
            return;
        }
        peers.compute(peerName, (name, existing) -> {
            Map<UUID, PortalInfo> fresh = new LinkedHashMap<>();
            Set<UUID> tombstones = existing == null ? ConcurrentHashMap.newKeySet() : existing.tombstones();
            for (PortalInfo info : portals) {
                fresh.put(info.id(), info);
                tombstones.remove(info.id());
            }
            return new PeerEntry(fresh, tombstones);
        });
        persist();
    }

    public void tombstone(String peerName, UUID portalId) {
        if (peerName == null || portalId == null) {
            return;
        }
        peers.compute(peerName, (name, existing) -> {
            PeerEntry entry = existing == null ? new PeerEntry(new LinkedHashMap<>(), ConcurrentHashMap.newKeySet()) : existing;
            entry.portals().remove(portalId);
            entry.tombstones().add(portalId);
            return entry;
        });
        persist();
    }

    public void forget(String peerName) {
        if (peerName != null && peers.remove(peerName) != null) {
            persist();
        }
    }

    public List<PortalInfo> portals(String peerName) {
        PeerEntry entry = peerName == null ? null : peers.get(peerName);
        return entry == null ? List.of() : new ArrayList<>(entry.portals().values());
    }

    /** Seeds peers the registry has not heard from yet with their last-known portals, marked stale. */
    public void hydrate(RemotePortalRegistry registry) {
        for (Map.Entry<String, PeerEntry> entry : peers.entrySet()) {
            if (registry.hasPeer(entry.getKey()) || entry.getValue().portals().isEmpty()) {
                continue;
            }
            registry.hydrateStale(entry.getKey(), new ArrayList<>(entry.getValue().portals().values()));
        }
    }

    @Override
    public void onDirectoryChanged(String peerName, List<PortalInfo> portals) {
        record(peerName, portals);
    }

    @Override
    public void onPortalRemoved(String peerName, UUID portalId) {
        tombstone(peerName, portalId);
    }

    @Override
    public void onPeerRemoved(String peerName) {
        forget(peerName);
    }

    private void load() {
        peers.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            Map<String, Object> root = documents.decode(Files.readString(file, StandardCharsets.UTF_8));
            if (!(root.get(KEY_PEERS) instanceof Map<?, ?> peerTable)) {
                return;
            }
            for (Map.Entry<?, ?> entry : peerTable.entrySet()) {
                String peerName = (String) entry.getKey();
                Map<?, ?> peer = object(entry.getValue());
                Map<UUID, PortalInfo> portals = new LinkedHashMap<>();
                for (Object value : array(peer.get(KEY_PORTALS))) {
                    PortalInfo info = readPortal(object(value));
                    portals.put(info.id(), info);
                }
                Set<UUID> tombstones = ConcurrentHashMap.newKeySet();
                for (Object value : array(peer.get(KEY_TOMBSTONES))) {
                    tombstones.add(UUID.fromString((String) value));
                }
                peers.put(peerName, new PeerEntry(portals, tombstones));
            }
        } catch (IOException | RuntimeException error) {
            peers.clear();
        }
    }

    private synchronized void persist() {
        Map<String, Object> peerTable = new LinkedHashMap<>();
        for (Map.Entry<String, PeerEntry> entry : peers.entrySet()) {
            List<Map<String, Object>> portals = new ArrayList<>(entry.getValue().portals().size());
            for (PortalInfo info : entry.getValue().portals().values()) {
                portals.add(writePortal(info));
            }
            List<String> removed = new ArrayList<>(entry.getValue().tombstones().size());
            for (UUID id : entry.getValue().tombstones()) {
                removed.add(id.toString());
            }
            Map<String, Object> peer = new LinkedHashMap<>();
            peer.put(KEY_PORTALS, portals);
            peer.put(KEY_TOMBSTONES, removed);
            peerTable.put(entry.getKey(), peer);
        }
        try {
            VIO.writeAllBytes(file.toFile(), documents.encode(Map.of(KEY_PEERS, peerTable)).getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new IllegalStateException("Could not persist Wormholes directory cache", error);
        }
    }

    private static Map<String, Object> writePortal(PortalInfo info) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", info.id().toString());
        values.put("name", info.name());
        values.put("world", info.worldKey());
        values.put("type", info.typeName());
        values.put("open", info.open());
        values.put("normal", info.frameNormal());
        values.put("right", info.frameRight());
        values.put("up", info.frameUp());
        values.put("origin", List.of(info.originX(), info.originY(), info.originZ()));
        values.put("min", List.of(info.minX(), info.minY(), info.minZ()));
        values.put("max", List.of(info.maxX(), info.maxY(), info.maxZ()));
        return values;
    }

    private static PortalInfo readPortal(Map<?, ?> values) {
        List<?> origin = array(values.get("origin"));
        List<?> min = array(values.get("min"));
        List<?> max = array(values.get("max"));
        return new PortalInfo(UUID.fromString(string(values, "id")), string(values, "name"), string(values, "world"), string(values, "type"),
            booleanValue(values, "open"), string(values, "normal"), string(values, "right"), string(values, "up"),
            coordinate(origin, 0), coordinate(origin, 1), coordinate(origin, 2),
            coordinate(min, 0), coordinate(min, 1), coordinate(min, 2),
            coordinate(max, 0), coordinate(max, 1), coordinate(max, 2));
    }

    private static Map<?, ?> object(Object value) {
        if (value instanceof Map<?, ?> object) {
            return object;
        }
        throw new IllegalArgumentException("Expected a directory object");
    }

    private static List<?> array(Object value) {
        return value instanceof List<?> array ? array : List.of();
    }

    private static String string(Map<?, ?> values, String key) {
        if (values.get(key) instanceof String value) {
            return value;
        }
        throw new IllegalArgumentException("Missing directory field " + key);
    }

    private static boolean booleanValue(Map<?, ?> values, String key) {
        Object value = values.get(key);
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text)) {
                return true;
            }
            if ("false".equalsIgnoreCase(text)) {
                return false;
            }
        }
        throw new IllegalArgumentException("Invalid directory field " + key);
    }

    private static double coordinate(List<?> values, int index) {
        Object value = values.get(index);
        return value instanceof Number number ? number.doubleValue() : Double.parseDouble(String.valueOf(value));
    }
}
