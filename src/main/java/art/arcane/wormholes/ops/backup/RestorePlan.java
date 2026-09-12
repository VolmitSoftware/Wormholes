package art.arcane.wormholes.ops.backup;

import art.arcane.volmlib.util.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * What a restore would do, portal by portal. Building a plan never writes; {@link #apply(Path)}
 * replaces the portal files and the operator reloads to pick them up.
 */
public final class RestorePlan {
    /** One portal file the restore would write, after any remap. */
    public record PortalChange(UUID portalId, String name, String entry, byte[] content, boolean replaces) {
    }

    private final List<PortalChange> created;
    private final List<PortalChange> replaced;
    private final List<String> skipped;

    private RestorePlan(List<PortalChange> created, List<PortalChange> replaced, List<String> skipped) {
        this.created = Collections.unmodifiableList(created);
        this.replaced = Collections.unmodifiableList(replaced);
        this.skipped = Collections.unmodifiableList(skipped);
    }

    public List<PortalChange> created() {
        return created;
    }

    public List<PortalChange> replaced() {
        return replaced;
    }

    /** Bundle entries that are not restorable portal documents, with the reason. */
    public List<String> skipped() {
        return skipped;
    }

    public int changeCount() {
        return created.size() + replaced.size();
    }

    public static RestorePlan of(BackupBundle bundle, Path dataFolder, WorldKeyRemap remap) {
        List<PortalChange> created = new ArrayList<>();
        List<PortalChange> replaced = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        WorldKeyRemap remapping = remap == null ? WorldKeyRemap.none() : remap;

        for (String entry : bundle.portalEntries()) {
            byte[] raw = bundle.entries().get(entry);
            JSONObject portal;
            UUID portalId;
            try {
                portal = new JSONObject(new String(raw, StandardCharsets.UTF_8));
                portalId = UUID.fromString(portal.getString("id"));
            } catch (RuntimeException malformed) {
                skipped.add(entry + ": not a portal document");
                continue;
            }
            remapping.applyTo(portal);
            byte[] content = portal.toString().getBytes(StandardCharsets.UTF_8);
            String name = portal.optString("name", portalId.toString());
            boolean exists = Files.isRegularFile(portalFile(dataFolder, portalId));
            PortalChange change = new PortalChange(portalId, name, entry, content, exists);
            if (exists) {
                replaced.add(change);
            } else {
                created.add(change);
            }
        }
        return new RestorePlan(created, replaced, skipped);
    }

    /** Writes every planned portal file and returns how many landed. */
    public int apply(Path dataFolder) throws IOException {
        int written = 0;
        for (PortalChange change : created) {
            write(dataFolder, change);
            written++;
        }
        for (PortalChange change : replaced) {
            write(dataFolder, change);
            written++;
        }
        return written;
    }

    private static void write(Path dataFolder, PortalChange change) throws IOException {
        Path file = portalFile(dataFolder, change.portalId());
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".restore");
        Files.write(temporary, change.content());
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** The same nested layout {@code PortalRegistryStorage} writes: portals/&lt;part1&gt;/&lt;part0&gt;/&lt;id&gt;.json. */
    static Path portalFile(Path dataFolder, UUID portalId) {
        String[] parts = portalId.toString().split("-");
        return dataFolder.resolve("portals").resolve(parts[1]).resolve(parts[0]).resolve(portalId + ".json");
    }
}
