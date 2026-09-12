package art.arcane.wormholes.ops.importers;

import art.arcane.wormholes.util.Direction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvancedPortalsImporterTest {
    @TempDir
    Path serverRoot;

    @Test
    void aFlatRegionBecomesAPortalAndAThickRegionIsSkippedWithAReason() throws IOException {
        writePortals("""
            flat:
              world: world
              pos1:
                X: 10
                Y: 64
                Z: 20
              pos2:
                X: 12
                Y: 66
                Z: 20
              destination: spawn
            thick:
              world: world
              pos1:
                X: 0
                Y: 64
                Z: 0
              pos2:
                X: 4
                Y: 68
                Z: 4
              destination: spawn
            """);
        RecordingPortalFactory factory = new RecordingPortalFactory();

        AdvancedPortalsImporter importer = new AdvancedPortalsImporter();
        assertTrue(importer.detect(serverRoot));
        assertEquals("advancedportals", importer.id());
        PortalImportReport report = importer.importFrom(serverRoot, false, factory);

        assertEquals(1, report.createdCount());
        assertEquals(1, report.skippedCount());
        assertEquals("thick", report.skipped().get(0).name());
        assertTrue(report.skipped().get(0).reason().contains("flat"), report.skipped().toString());

        ImportedPortal created = factory.created().get(0);
        assertEquals("flat", created.name());
        assertEquals("world", created.worldName());
        assertEquals(10, created.x());
        assertEquals(64, created.y());
        assertEquals(20, created.z());
        assertEquals(Direction.N, created.facing());
        assertEquals(3, created.width());
        assertEquals(3, created.height());
        assertEquals("spawn", created.destination());
        assertEquals(1, report.unlinked().size());
    }

    @Test
    void destinationsFileEntriesAreReportedAsLocationsNotPortals() throws IOException {
        writePortals("""
            flat:
              world: world
              pos1:
                X: 10
                Y: 64
                Z: 20
              pos2:
                X: 10
                Y: 66
                Z: 22
              destination: spawn
            """);
        Path destinations = serverRoot.resolve("plugins/AdvancedPortals/destinations.yml");
        Files.writeString(destinations, "spawn:\n  world: world\n  pos:\n    X: 0\n    Y: 64\n    Z: 0\n",
            StandardCharsets.UTF_8);

        PortalImportReport report = new AdvancedPortalsImporter()
            .importFrom(serverRoot, false, new RecordingPortalFactory());

        assertEquals(1, report.createdCount());
        assertEquals(Direction.E, factoryFacing());
        assertTrue(report.notes().stream().anyMatch(note -> note.contains("destinations.yml")), report.notes().toString());
    }

    @Test
    void anAbsentPluginFolderDetectsFalseAndImportsNothing() {
        AdvancedPortalsImporter importer = new AdvancedPortalsImporter();
        assertFalse(importer.detect(serverRoot));
        assertEquals(0, importer.importFrom(serverRoot, true, new RecordingPortalFactory()).createdCount());
    }

    private Direction factoryFacing() throws IOException {
        RecordingPortalFactory factory = new RecordingPortalFactory();
        new AdvancedPortalsImporter().importFrom(serverRoot, false, factory);
        return factory.created().get(0).facing();
    }

    private void writePortals(String yaml) throws IOException {
        Path file = serverRoot.resolve("plugins/AdvancedPortals/portals.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
    }
}
