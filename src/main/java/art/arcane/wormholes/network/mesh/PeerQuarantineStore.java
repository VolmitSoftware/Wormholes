package art.arcane.wormholes.network.mesh;

import art.arcane.wormholes.network.Handshake;
import art.arcane.wormholes.util.VIO;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Introduced peers waiting for {@code /wh network accept}. Persisted as trust/quarantine.properties
 * (name = firstSeen|introducer|announce) next to the trust store so the whole introduction, including
 * the endpoints needed to save a route on accept, survives a restart.
 */
public final class PeerQuarantineStore {
    private static final String QUARANTINE_FILE = "quarantine.properties";

    public record Entry(PeerAnnounce announce, String introducer, long firstSeenMillis) {
        public String name() {
            return announce.name();
        }
    }

    private final Path file;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    private PeerQuarantineStore(Path file) {
        this.file = file;
    }

    public static PeerQuarantineStore loadOrCreate(Path dataDirectory) throws IOException {
        Path trustDirectory = dataDirectory.resolve("trust");
        Files.createDirectories(trustDirectory);
        PeerQuarantineStore store = new PeerQuarantineStore(trustDirectory.resolve(QUARANTINE_FILE));
        store.load();
        return store;
    }

    public Entry get(String name) {
        return name == null ? null : entries.get(name);
    }

    public List<Entry> all() {
        return new ArrayList<>(entries.values());
    }

    public boolean matches(String name, byte[] publicKey) {
        Entry entry = get(name);
        return entry != null && Handshake.sameKey(entry.announce().publicKey(), publicKey);
    }

    /** Stores a verified introduction; an announce older than the stored epoch is ignored. Returns true when stored. */
    public boolean put(PeerAnnounce announce, String introducer, long nowMillis) {
        if (announce == null || announce.name() == null || announce.name().isBlank()) {
            return false;
        }
        boolean[] stored = new boolean[1];
        entries.compute(announce.name(), (name, existing) -> {
            if (existing != null && existing.announce().epoch() > announce.epoch()) {
                return existing;
            }
            stored[0] = true;
            long firstSeen = existing == null ? nowMillis : existing.firstSeenMillis();
            return new Entry(announce, introducer, firstSeen);
        });
        if (stored[0]) {
            persist();
        }
        return stored[0];
    }

    public boolean remove(String name) {
        if (name == null || entries.remove(name) == null) {
            return false;
        }
        persist();
        return true;
    }

    private void load() throws IOException {
        entries.clear();
        if (!Files.isRegularFile(file)) {
            persist();
            return;
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        }
        for (String name : properties.stringPropertyNames()) {
            Entry entry = decode(properties.getProperty(name));
            if (entry != null && entry.name().equals(name)) {
                entries.put(name, entry);
            }
        }
    }

    private synchronized void persist() {
        Properties properties = new Properties();
        for (Map.Entry<String, Entry> entry : entries.entrySet()) {
            properties.setProperty(entry.getKey(), encode(entry.getValue()));
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            properties.store(output, "Wormholes introduced peers waiting for approval");
            VIO.writeAllBytes(file.toFile(), output.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Could not persist Wormholes peer quarantine store", e);
        }
    }

    private static String encode(Entry entry) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
            DataOutputStream out = new DataOutputStream(buffer);
            entry.announce().write(out);
            out.flush();
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return entry.firstSeenMillis() + "|"
                + encoder.encodeToString(entry.introducer().getBytes(StandardCharsets.UTF_8)) + "|"
                + encoder.encodeToString(buffer.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Could not encode quarantined peer " + entry.name(), e);
        }
    }

    private static Entry decode(String value) {
        if (value == null) {
            return null;
        }
        String[] parts = value.split("\\|", 3);
        if (parts.length != 3) {
            return null;
        }
        try {
            long firstSeen = Long.parseLong(parts[0]);
            Base64.Decoder decoder = Base64.getUrlDecoder();
            String introducer = new String(decoder.decode(parts[1]), StandardCharsets.UTF_8);
            PeerAnnounce announce = PeerAnnounce.read(new DataInputStream(new ByteArrayInputStream(decoder.decode(parts[2]))));
            return new Entry(announce, introducer, firstSeen);
        } catch (IllegalArgumentException | IOException e) {
            return null;
        }
    }
}
