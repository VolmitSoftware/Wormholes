package art.arcane.wormholes.network.mesh;

import art.arcane.wormholes.util.VIO;

import java.io.ByteArrayOutputStream;
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
 * Last announce seen from every trusted member: epoch, capability set, time and the link it arrived
 * on. Persisted as mesh/members.properties so a tombstone issued right after a restart still carries
 * the removed peer's real epoch.
 */
public final class MeshMemberTable {
    private static final String MEMBERS_FILE = "members.properties";

    public record Member(String name, long epoch, long capabilities, long lastAnnounceMillis, String via, int protocolVersion, String pluginVersion) {
    }

    private final Path file;
    private final Map<String, Member> members = new ConcurrentHashMap<>();

    private MeshMemberTable(Path file) {
        this.file = file;
    }

    public static MeshMemberTable loadOrCreate(Path dataDirectory) throws IOException {
        Path meshDirectory = dataDirectory.resolve("mesh");
        Files.createDirectories(meshDirectory);
        MeshMemberTable table = new MeshMemberTable(meshDirectory.resolve(MEMBERS_FILE));
        table.load();
        return table;
    }

    public Member get(String name) {
        return name == null ? null : members.get(name);
    }

    public List<Member> all() {
        return new ArrayList<>(members.values());
    }

    public long epochOf(String name, long fallback) {
        Member member = get(name);
        return member == null ? fallback : member.epoch();
    }

    public void record(PeerAnnounce announce, String via, long nowMillis) {
        if (announce == null || announce.name() == null || announce.name().isBlank()) {
            return;
        }
        members.put(announce.name(), new Member(announce.name(), announce.epoch(), announce.capabilities(), nowMillis, via == null ? "" : via,
            announce.protocolVersion(), announce.pluginVersion() == null ? "" : announce.pluginVersion()));
        persist();
    }

    public boolean remove(String name) {
        if (name == null || members.remove(name) == null) {
            return false;
        }
        persist();
        return true;
    }

    private void load() throws IOException {
        members.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        }
        for (String name : properties.stringPropertyNames()) {
            Member member = decode(name, properties.getProperty(name));
            if (member != null) {
                members.put(name, member);
            }
        }
    }

    private synchronized void persist() {
        Properties properties = new Properties();
        for (Map.Entry<String, Member> entry : members.entrySet()) {
            Member member = entry.getValue();
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            properties.setProperty(entry.getKey(), member.epoch() + "|" + member.capabilities() + "|" + member.lastAnnounceMillis() + "|"
                + encoder.encodeToString(member.via().getBytes(StandardCharsets.UTF_8)) + "|" + member.protocolVersion() + "|"
                + encoder.encodeToString(member.pluginVersion().getBytes(StandardCharsets.UTF_8)));
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            properties.store(output, "Wormholes mesh members: last announce per trusted peer");
            VIO.writeAllBytes(file.toFile(), output.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Could not persist Wormholes mesh members", e);
        }
    }

    private static Member decode(String name, String value) {
        if (value == null) {
            return null;
        }
        String[] parts = value.split("\\|", 6);
        if (parts.length != 6) {
            return null;
        }
        try {
            Base64.Decoder decoder = Base64.getUrlDecoder();
            return new Member(name, Long.parseLong(parts[0]), Long.parseLong(parts[1]), Long.parseLong(parts[2]),
                new String(decoder.decode(parts[3]), StandardCharsets.UTF_8), Integer.parseInt(parts[4]),
                new String(decoder.decode(parts[5]), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
