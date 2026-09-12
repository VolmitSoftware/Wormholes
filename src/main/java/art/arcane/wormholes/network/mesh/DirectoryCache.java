package art.arcane.wormholes.network.mesh;

import art.arcane.volmlib.util.json.JSONArray;
import art.arcane.volmlib.util.json.JSONException;
import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.wormholes.network.PortalInfo;
import art.arcane.wormholes.network.RemotePortalRegistry;
import art.arcane.wormholes.util.VIO;

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
    private final Map<String, PeerEntry> peers = new ConcurrentHashMap<>();

    private DirectoryCache(Path file) {
        this.file = file;
    }

    public static DirectoryCache loadOrCreate(Path dataDirectory) throws IOException {
        Path meshDirectory = dataDirectory.resolve("mesh");
        Files.createDirectories(meshDirectory);
        DirectoryCache cache = new DirectoryCache(meshDirectory.resolve(DIRECTORY_FILE));
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
            JSONObject root = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
            JSONObject peerTable = root.optJSONObject(KEY_PEERS);
            if (peerTable == null) {
                return;
            }
            for (String peerName : peerTable.keySet()) {
                JSONObject peer = peerTable.getJSONObject(peerName);
                Map<UUID, PortalInfo> portals = new LinkedHashMap<>();
                JSONArray list = peer.optJSONArray(KEY_PORTALS);
                for (int index = 0; list != null && index < list.length(); index++) {
                    PortalInfo info = readPortal(list.getJSONObject(index));
                    portals.put(info.id(), info);
                }
                Set<UUID> tombstones = ConcurrentHashMap.newKeySet();
                JSONArray removed = peer.optJSONArray(KEY_TOMBSTONES);
                for (int index = 0; removed != null && index < removed.length(); index++) {
                    tombstones.add(UUID.fromString(removed.getString(index)));
                }
                peers.put(peerName, new PeerEntry(portals, tombstones));
            }
        } catch (IOException | JSONException | IllegalArgumentException e) {
            peers.clear();
        }
    }

    private synchronized void persist() {
        JSONObject peerTable = new JSONObject();
        for (Map.Entry<String, PeerEntry> entry : peers.entrySet()) {
            JSONObject peer = new JSONObject();
            JSONArray list = new JSONArray();
            for (PortalInfo info : entry.getValue().portals().values()) {
                list.put(writePortal(info));
            }
            JSONArray removed = new JSONArray();
            for (UUID id : entry.getValue().tombstones()) {
                removed.put(id.toString());
            }
            peer.put(KEY_PORTALS, list);
            peer.put(KEY_TOMBSTONES, removed);
            peerTable.put(entry.getKey(), peer);
        }
        JSONObject root = new JSONObject();
        root.put(KEY_PEERS, peerTable);
        try {
            VIO.writeAllBytes(file.toFile(), root.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Could not persist Wormholes directory cache", e);
        }
    }

    private static JSONObject writePortal(PortalInfo info) {
        JSONObject json = new JSONObject();
        json.put("id", info.id().toString());
        json.put("name", info.name());
        json.put("world", info.worldKey());
        json.put("type", info.typeName());
        json.put("open", info.open());
        json.put("normal", info.frameNormal());
        json.put("right", info.frameRight());
        json.put("up", info.frameUp());
        json.put("origin", coords(info.originX(), info.originY(), info.originZ()));
        json.put("min", coords(info.minX(), info.minY(), info.minZ()));
        json.put("max", coords(info.maxX(), info.maxY(), info.maxZ()));
        return json;
    }

    private static PortalInfo readPortal(JSONObject json) {
        JSONArray origin = json.getJSONArray("origin");
        JSONArray min = json.getJSONArray("min");
        JSONArray max = json.getJSONArray("max");
        return new PortalInfo(UUID.fromString(json.getString("id")), json.getString("name"), json.getString("world"), json.getString("type"),
            json.getBoolean("open"), json.getString("normal"), json.getString("right"), json.getString("up"),
            origin.getDouble(0), origin.getDouble(1), origin.getDouble(2),
            min.getDouble(0), min.getDouble(1), min.getDouble(2),
            max.getDouble(0), max.getDouble(1), max.getDouble(2));
    }

    private static JSONArray coords(double x, double y, double z) {
        JSONArray array = new JSONArray();
        array.put(x);
        array.put(y);
        array.put(z);
        return array;
    }
}
