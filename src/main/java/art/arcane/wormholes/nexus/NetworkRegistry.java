package art.arcane.wormholes.nexus;

import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.wormholes.util.VIO;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Every {@link PortalNetwork} on this server, backed by one JSON file per network under
 * {@code atlas/networks/}. Keeps a name index and a portal-to-network reverse index so dialing and
 * the portal menu never scan.
 */
public final class NetworkRegistry {
    private static final Logger LOG = Logger.getLogger("Wormholes");

    private final Path directory;
    private final ConcurrentHashMap<UUID, PortalNetwork> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> byName = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, UUID> networkByPortal = new ConcurrentHashMap<>();

    public NetworkRegistry(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    public Path directory() {
        return directory;
    }

    public synchronized void load() {
        byId.clear();
        byName.clear();
        networkByPortal.clear();
        if (!Files.isDirectory(directory)) {
            return;
        }
        int loaded = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.json")) {
            for (Path file : files) {
                PortalNetwork network = read(file);
                if (network != null) {
                    index(network);
                    loaded++;
                }
            }
        } catch (IOException failure) {
            LOG.log(Level.WARNING, "nexus registry load failed in " + directory, failure);
        }
        if (loaded > 0) {
            LOG.info("nexus loaded " + loaded + " networks");
        }
    }

    public synchronized void save(PortalNetwork network) throws IOException {
        Objects.requireNonNull(network, "network");
        index(network);
        Files.createDirectories(directory);
        VIO.writeAll(directory.resolve(network.id() + ".json").toFile(), NetworkRegistryCodec.encode(network).toString(2));
    }

    public synchronized void delete(UUID networkId) throws IOException {
        PortalNetwork removed = networkId == null ? null : byId.remove(networkId);
        if (removed == null) {
            return;
        }
        byName.remove(nameKey(removed.name()), networkId);
        networkByPortal.entrySet().removeIf(entry -> entry.getValue().equals(networkId));
        Files.deleteIfExists(directory.resolve(networkId + ".json"));
    }

    public PortalNetwork byId(UUID networkId) {
        return networkId == null ? null : byId.get(networkId);
    }

    public PortalNetwork byName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        UUID networkId = byName.get(nameKey(name));
        return networkId == null ? null : byId.get(networkId);
    }

    public Collection<PortalNetwork> all() {
        return List.copyOf(byId.values());
    }

    public List<PortalNetwork> ownedBy(UUID ownerId) {
        List<PortalNetwork> owned = new ArrayList<>();
        for (PortalNetwork network : byId.values()) {
            if (ownerId != null && ownerId.equals(network.ownerId())) {
                owned.add(network);
            }
        }
        return owned;
    }

    /** The network a portal belongs to, or null. Reverse index; never scans. */
    public PortalNetwork memberOf(UUID portalId) {
        UUID networkId = portalId == null ? null : networkByPortal.get(portalId);
        return networkId == null ? null : byId.get(networkId);
    }

    public NetworkMember resolve(UUID networkId, String address) {
        PortalNetwork network = byId(networkId);
        return network == null ? null : network.memberAt(address);
    }

    /**
     * The destination one policy entry points at. Address entries go through {@code networkId};
     * local and remote entries name their portal directly and need no network.
     */
    public NetworkMember resolveEntry(UUID networkId, DestinationEntry entry) {
        if (entry == null) {
            return null;
        }
        return switch (entry.kind()) {
            case ADDRESS -> resolve(networkId, entry.target());
            case LOCAL -> {
                UUID portalId = entry.localPortalId();
                yield portalId == null ? null : new NetworkMember(portalId, "", entry.label(), 0L, null);
            }
            case REMOTE -> {
                UUID portalId = entry.remotePortalId();
                String server = entry.remoteServer();
                yield portalId == null || server == null
                        ? null : new NetworkMember(portalId, "", entry.label(), 0L, server);
            }
        };
    }

    private void index(PortalNetwork network) {
        PortalNetwork previous = byId.put(network.id(), network);
        if (previous != null && !previous.name().equalsIgnoreCase(network.name())) {
            byName.remove(nameKey(previous.name()), network.id());
        }
        byName.put(nameKey(network.name()), network.id());
        networkByPortal.entrySet().removeIf(entry ->
                entry.getValue().equals(network.id()) && !network.members().containsKey(entry.getKey()));
        for (Map.Entry<UUID, NetworkMember> member : network.members().entrySet()) {
            networkByPortal.put(member.getKey(), network.id());
        }
    }

    private PortalNetwork read(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return NetworkRegistryCodec.decode(new JSONObject(content));
        } catch (IOException | RuntimeException failure) {
            LOG.log(Level.WARNING, "nexus network unreadable: " + file.getFileName(), failure);
            return null;
        }
    }

    private static String nameKey(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
