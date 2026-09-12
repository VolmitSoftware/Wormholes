package art.arcane.wormholes.ops.backup;

import art.arcane.volmlib.util.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestorePlanTest {
    private static final UUID EXISTING = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID INCOMING = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OLD_OWNER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID NEW_OWNER = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @TempDir
    Path tempDir;

    @Test
    void aDryRunClassifiesReplacementsAndCreationsWithoutTouchingDisk() throws Exception {
        Path dataFolder = dataFolderWith(EXISTING);
        Path bundle = bundleWith(EXISTING, INCOMING);
        Path existingFile = portalFile(dataFolder, EXISTING);
        String before = Files.readString(existingFile, StandardCharsets.UTF_8);

        RestorePlan plan = RestorePlan.of(BackupBundle.read(bundle), dataFolder, WorldKeyRemap.none());

        assertEquals(1, plan.replaced().size());
        assertEquals(1, plan.created().size());
        assertEquals(EXISTING, plan.replaced().get(0).portalId());
        assertEquals(INCOMING, plan.created().get(0).portalId());
        assertEquals(2, plan.changeCount());
        assertEquals(before, Files.readString(existingFile, StandardCharsets.UTF_8));
    }

    @Test
    void remapsRewriteTheWorldKeyAndOwnerBeforeTheFilesLand() throws Exception {
        Path dataFolder = dataFolderWith(EXISTING);
        Path bundle = bundleWith(INCOMING);
        WorldKeyRemap remap = WorldKeyRemap.parse("minecraft:overworld=minecraft:the_nether",
            OLD_OWNER + "=" + NEW_OWNER);

        RestorePlan plan = RestorePlan.of(BackupBundle.read(bundle), dataFolder, remap);
        JSONObject planned = new JSONObject(new String(plan.created().get(0).content(), StandardCharsets.UTF_8));

        assertEquals("minecraft:the_nether", planned.getJSONObject("structure").getString("worldKey"));
        assertEquals(NEW_OWNER.toString(), planned.getString("owner"));

        int written = plan.apply(dataFolder);
        assertEquals(1, written);
        JSONObject onDisk = new JSONObject(Files.readString(portalFile(dataFolder, INCOMING), StandardCharsets.UTF_8));
        assertEquals("minecraft:the_nether", onDisk.getJSONObject("structure").getString("worldKey"));
        assertEquals(NEW_OWNER.toString(), onDisk.getString("owner"));
    }

    @Test
    void applyReplacesExistingPortalFilesAndLeavesUnrelatedFilesAlone() throws Exception {
        Path dataFolder = dataFolderWith(EXISTING);
        Path unrelated = dataFolder.resolve("portals/keep.txt");
        Files.writeString(unrelated, "keep me", StandardCharsets.UTF_8);
        Path bundle = bundleWith(EXISTING);

        RestorePlan plan = RestorePlan.of(BackupBundle.read(bundle), dataFolder, WorldKeyRemap.none());
        assertEquals(1, plan.apply(dataFolder));

        JSONObject onDisk = new JSONObject(Files.readString(portalFile(dataFolder, EXISTING), StandardCharsets.UTF_8));
        assertEquals(OLD_OWNER.toString(), onDisk.getString("owner"));
        assertEquals("from-bundle", onDisk.getString("name"));
        assertEquals("keep me", Files.readString(unrelated, StandardCharsets.UTF_8));
    }

    @Test
    void anUnparseableRemapIsRejected() {
        assertTrue(WorldKeyRemap.none().worlds().isEmpty());
        assertFalse(WorldKeyRemap.parse("a=b", "").worlds().isEmpty());
        try {
            WorldKeyRemap.parse("broken", "");
            assertTrue(false, "a remap without '=' must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("broken"));
        }
    }

    private Path dataFolderWith(UUID portalId) throws IOException {
        Path dataFolder = tempDir.resolve("data");
        Path file = portalFile(dataFolder, portalId);
        Files.createDirectories(file.getParent());
        Files.writeString(file, portalJson(portalId, "on-disk").toString(), StandardCharsets.UTF_8);
        return dataFolder;
    }

    private Path bundleWith(UUID... portalIds) throws IOException {
        Path source = tempDir.resolve("source-" + UUID.randomUUID());
        for (UUID portalId : portalIds) {
            Path file = portalFile(source, portalId);
            Files.createDirectories(file.getParent());
            Files.writeString(file, portalJson(portalId, "from-bundle").toString(), StandardCharsets.UTF_8);
        }
        Path bundle = tempDir.resolve("bundle-" + UUID.randomUUID() + ".zip");
        BackupBundle.write(source, bundle, new BackupManifest("2.0.5-26.2", 3, 8, 1L,
            Map.of(), "alpha", "unsigned"), null);
        return bundle;
    }

    private static Path portalFile(Path dataFolder, UUID portalId) {
        List<String> parts = List.of(portalId.toString().split("-"));
        return dataFolder.resolve("portals").resolve(parts.get(1)).resolve(parts.get(0))
            .resolve(portalId + ".json");
    }

    private static JSONObject portalJson(UUID portalId, String name) {
        JSONObject structure = new JSONObject();
        structure.put("worldKey", "minecraft:overworld");
        JSONObject portal = new JSONObject();
        portal.put("id", portalId.toString());
        portal.put("name", name);
        portal.put("owner", OLD_OWNER.toString());
        portal.put("structure", structure);
        return portal;
    }
}
