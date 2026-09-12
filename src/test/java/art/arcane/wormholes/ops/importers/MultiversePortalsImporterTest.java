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

class MultiversePortalsImporterTest {
    @TempDir
    Path serverRoot;

    @Test
    void portalDestinationsLinkAndWorldDestinationsAreReported() throws IOException {
        writePortals("""
            portals:
              gate:
                world: world
                location: 10,64,20:10,66,22
                destination: p:arena
                owner: Steve
              arena:
                world: world
                location: 30,70,40:32,72,40
                destination: w:world_nether
            """);
        RecordingPortalFactory factory = new RecordingPortalFactory().withKnownDestination("arena");

        MultiversePortalsImporter importer = new MultiversePortalsImporter();
        assertTrue(importer.detect(serverRoot));
        assertEquals("multiverse", importer.id());
        PortalImportReport report = importer.importFrom(serverRoot, false, factory);

        assertEquals(2, report.createdCount());
        assertEquals(0, report.skippedCount());
        ImportedPortal gate = factory.created().get(0);
        assertEquals("gate", gate.name());
        assertEquals(Direction.E, gate.facing());
        assertEquals(3, gate.width());
        assertEquals(3, gate.height());
        assertEquals("arena", gate.destination());
        assertEquals(1, factory.links().size());
        assertTrue(report.notes().stream().anyMatch(note -> note.contains("world_nether")), report.notes().toString());
    }

    @Test
    void malformedLocationsAreSkippedWithAReason() throws IOException {
        writePortals("""
            portals:
              broken:
                world: world
                location: nonsense
                destination: p:arena
              cube:
                world: world
                location: 0,64,0:4,68,4
            """);

        PortalImportReport report = new MultiversePortalsImporter()
            .importFrom(serverRoot, false, new RecordingPortalFactory());

        assertEquals(0, report.createdCount());
        assertEquals(2, report.skippedCount());
        assertTrue(report.skipped().get(0).reason().contains("location"), report.skipped().toString());
        assertTrue(report.skipped().get(1).reason().contains("flat"), report.skipped().toString());
    }

    @Test
    void anAbsentFileDetectsFalse() {
        assertFalse(new MultiversePortalsImporter().detect(serverRoot));
    }

    private void writePortals(String yaml) throws IOException {
        Path file = serverRoot.resolve("plugins/Multiverse-Portals/portals.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
    }
}
