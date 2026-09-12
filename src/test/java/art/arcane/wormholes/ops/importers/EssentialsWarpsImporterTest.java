package art.arcane.wormholes.ops.importers;

import art.arcane.wormholes.util.Direction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EssentialsWarpsImporterTest {
    @TempDir
    Path serverRoot;

    @Test
    void warpsWithoutAFrameAreReportedAsAtlasOnlyEntries() throws IOException {
        writeWarp("spawn.yml", "name: spawn\nworld: world\nx: 10.5\ny: 64.0\nz: 20.5\nyaw: 90.0\npitch: 0.0\n");
        RecordingPortalFactory factory = new RecordingPortalFactory();

        EssentialsWarpsImporter importer = new EssentialsWarpsImporter(0, 0);
        assertTrue(importer.detect(serverRoot));
        assertEquals("essentials", importer.id());
        PortalImportReport report = importer.importFrom(serverRoot, false, factory);

        assertEquals(0, report.createdCount());
        assertEquals(1, report.skippedCount());
        assertEquals("spawn", report.skipped().get(0).name());
        assertEquals("no frame", report.skipped().get(0).reason());
        assertTrue(factory.created().isEmpty());
    }

    @Test
    void aFrameSizeBuildsAVerticalPortalFacingTheWarpYaw() throws IOException {
        writeWarp("spawn.yml", "name: spawn\nworld: world\nx: 10.5\ny: 64.0\nz: 20.5\nyaw: 90.0\npitch: 0.0\n");
        writeWarp("arena.yml", "name: arena\nworld: world\nx: -3.5\ny: 70.0\nz: 8.5\nyaw: 180.0\npitch: 0.0\n");
        RecordingPortalFactory factory = new RecordingPortalFactory();

        PortalImportReport report = new EssentialsWarpsImporter(2, 3).importFrom(serverRoot, false, factory);

        assertEquals(2, report.createdCount());
        ImportedPortal arena = factory.created().get(0);
        assertEquals("arena", arena.name());
        assertEquals("world", arena.worldName());
        assertEquals(-4, arena.x());
        assertEquals(70, arena.y());
        assertEquals(8, arena.z());
        assertEquals(Direction.N, arena.facing());
        assertEquals(2, arena.width());
        assertEquals(3, arena.height());
        assertEquals(Direction.W, factory.created().get(1).facing());
    }

    @Test
    void warpsWithoutAWorldAreSkipped() throws IOException {
        writeWarp("broken.yml", "name: broken\nx: 1.0\ny: 2.0\nz: 3.0\n");

        PortalImportReport report = new EssentialsWarpsImporter(2, 3)
            .importFrom(serverRoot, false, new RecordingPortalFactory());

        assertEquals(0, report.createdCount());
        assertEquals("no world", report.skipped().get(0).reason());
    }

    private void writeWarp(String name, String yaml) throws IOException {
        Path file = serverRoot.resolve("plugins/Essentials/warps").resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
    }
}
