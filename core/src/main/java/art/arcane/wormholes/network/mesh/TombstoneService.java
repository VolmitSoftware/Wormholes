package art.arcane.wormholes.network.mesh;

import art.arcane.wormholes.network.Handshake;
import art.arcane.wormholes.util.VIO;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ledger of network-wide removals, persisted as mesh/tombstones.properties (name = epoch|issuedAt|key).
 * A tombstone ignores announces from its name at or below its epoch and blocks trust-on-first-use
 * for its key until {@code tombstone-ttl-days} pass or an operator re-imports the peer.
 */
public final class TombstoneService {
    private static final String TOMBSTONES_FILE = "tombstones.properties";

    public record Tombstone(String name, long epoch, long issuedAtMillis, byte[] publicKey) {
        boolean active(long nowMillis, long ttlMillis) {
            return nowMillis - issuedAtMillis <= ttlMillis;
        }
    }

    private final Path file;
    private final Map<String, Tombstone> tombstones = new ConcurrentHashMap<>();

    private TombstoneService(Path file) {
        this.file = file;
    }

    public static TombstoneService loadOrCreate(Path dataDirectory) throws IOException {
        Path meshDirectory = dataDirectory.resolve("mesh");
        Files.createDirectories(meshDirectory);
        TombstoneService service = new TombstoneService(meshDirectory.resolve(TOMBSTONES_FILE));
        service.load();
        return service;
    }

    /** Records a tombstone, keeping the highest epoch seen for the name. */
    public void record(PeerTombstone tombstone) {
        if (tombstone == null || tombstone.name() == null || tombstone.name().isBlank()) {
            return;
        }
        Tombstone incoming = new Tombstone(tombstone.name(), tombstone.epoch(), tombstone.issuedAtMillis(),
            tombstone.publicKey() == null ? new byte[0] : tombstone.publicKey().clone());
        boolean[] changed = new boolean[1];
        tombstones.compute(tombstone.name(), (name, existing) -> {
            if (existing != null && existing.epoch() > incoming.epoch()) {
                return existing;
            }
            changed[0] = true;
            return incoming;
        });
        if (changed[0]) {
            persist();
        }
    }

    public boolean ignoresAnnounce(String name, long epoch, long nowMillis, long ttlMillis) {
        Tombstone tombstone = name == null ? null : tombstones.get(name);
        return tombstone != null && tombstone.active(nowMillis, ttlMillis) && epoch <= tombstone.epoch();
    }

    public boolean blocksKey(String name, byte[] publicKey, long nowMillis, long ttlMillis) {
        Tombstone tombstone = name == null ? null : tombstones.get(name);
        return tombstone != null && tombstone.active(nowMillis, ttlMillis) && Handshake.sameKey(tombstone.publicKey(), publicKey);
    }

    public long epochOf(String name, long fallback) {
        Tombstone tombstone = name == null ? null : tombstones.get(name);
        return tombstone == null ? fallback : tombstone.epoch();
    }

    public void clear(String name) {
        if (name != null && tombstones.remove(name) != null) {
            persist();
        }
    }

    public void prune(long nowMillis, long ttlMillis) {
        boolean changed = tombstones.entrySet().removeIf(entry -> !entry.getValue().active(nowMillis, ttlMillis));
        if (changed) {
            persist();
        }
    }

    private void load() throws IOException {
        tombstones.clear();
        if (!Files.isRegularFile(file)) {
            persist();
            return;
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        }
        for (String name : properties.stringPropertyNames()) {
            Tombstone tombstone = decode(name, properties.getProperty(name));
            if (tombstone != null) {
                tombstones.put(name, tombstone);
            }
        }
    }

    private synchronized void persist() {
        Properties properties = new Properties();
        for (Map.Entry<String, Tombstone> entry : tombstones.entrySet()) {
            Tombstone tombstone = entry.getValue();
            properties.setProperty(entry.getKey(), tombstone.epoch() + "|" + tombstone.issuedAtMillis() + "|"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(tombstone.publicKey()));
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            properties.store(output, "Wormholes network-wide peer removals");
            VIO.writeAllBytes(file.toFile(), output.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Could not persist Wormholes tombstones", e);
        }
    }

    private static Tombstone decode(String name, String value) {
        if (value == null) {
            return null;
        }
        String[] parts = value.split("\\|", 3);
        if (parts.length != 3) {
            return null;
        }
        try {
            return new Tombstone(name, Long.parseLong(parts[0]), Long.parseLong(parts[1]), Base64.getUrlDecoder().decode(parts[2]));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
