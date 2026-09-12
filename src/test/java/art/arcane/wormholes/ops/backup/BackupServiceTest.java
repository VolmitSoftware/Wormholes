package art.arcane.wormholes.ops.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void everyBackupLandsUnderBackupsAndIsListedNewestFirst() throws Exception {
        BackupService service = service();
        writePortal(UUID.randomUUID());

        BackupService.BackupEntry first = service.now(1_000L);
        BackupService.BackupEntry second = service.now(2_000L);

        assertTrue(Files.isRegularFile(first.file()));
        assertTrue(first.file().startsWith(tempDir.resolve("backups")));
        assertEquals(1, first.portalCount());
        assertFalse(first.signed(), "no identity on disk means an unsigned bundle");

        List<BackupService.BackupEntry> listed = service.list();
        assertEquals(2, listed.size());
        assertEquals(second.id(), listed.get(0).id());
        assertEquals(first.id(), listed.get(1).id());
    }

    @Test
    void retentionDeletesTheOldestScheduledBackups() throws Exception {
        BackupService service = service();
        writePortal(UUID.randomUUID());
        for (int index = 0; index < 5; index++) {
            service.now(1_000L + index * 1_000L);
        }

        int removed = service.rotate(2);

        assertEquals(3, removed);
        List<BackupService.BackupEntry> listed = service.list();
        assertEquals(2, listed.size());
        assertEquals("wormholes-1970-01-01-000005", listed.get(0).id());
        assertEquals("wormholes-1970-01-01-000004", listed.get(1).id());
    }

    @Test
    void aBackupIsResolvableByIdAndExportableToAnotherFile() throws Exception {
        BackupService service = service();
        writePortal(UUID.randomUUID());
        BackupService.BackupEntry entry = service.now(1_000L);

        Optional<Path> resolved = service.resolve(entry.id());
        assertTrue(resolved.isPresent());
        assertTrue(service.resolve("missing").isEmpty());

        Path exported = tempDir.resolve("out/moved.zip");
        assertEquals("moved", service.exportTo(exported, 2_000L).id());
        assertTrue(Files.isRegularFile(exported));
        assertEquals(1, BackupBundle.read(exported).portalEntries().size());
    }

    @Test
    void aPlanFromABundleClassifiesAgainstTheLiveDataFolder() throws Exception {
        BackupService service = service();
        UUID portalId = UUID.randomUUID();
        writePortal(portalId);
        BackupService.BackupEntry entry = service.now(1_000L);

        RestorePlan sameFolder = service.propose(entry.file(), WorldKeyRemap.none()).plan();
        assertEquals(1, sameFolder.replaced().size());
        assertEquals(0, sameFolder.created().size());
        assertEquals(portalId, sameFolder.replaced().get(0).portalId());
    }

    private BackupService service() {
        return new BackupService(tempDir, () -> new BackupManifest("2.0.5-26.2", 3, 8, 1L, Map.of(), "alpha", "unsigned"));
    }

    private void writePortal(UUID portalId) throws IOException {
        String[] parts = portalId.toString().split("-");
        Path file = tempDir.resolve("portals").resolve(parts[1]).resolve(parts[0]).resolve(portalId + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"id\":\"" + portalId + "\",\"name\":\"gate\",\"owner\":\"" + portalId
            + "\",\"structure\":{\"worldKey\":\"minecraft:overworld\"}}", StandardCharsets.UTF_8);
    }
}
