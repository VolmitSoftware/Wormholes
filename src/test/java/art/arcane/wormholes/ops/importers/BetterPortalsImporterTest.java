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

class BetterPortalsImporterTest {
    @TempDir
    Path serverRoot;

    @Test
    void eachEntryBecomesAPortalFacingItsStoredDirection() throws IOException {
        writePortals("""
            {"portals":[
              {"name":"north","originPos":{"world":"world","x":10,"y":64,"z":20},
               "portalDirection":"NORTH","portalSize":{"x":2,"y":3}},
              {"name":"east","originPos":{"world":"world","x":-5,"y":70,"z":8},
               "portalDirection":"EAST","portalSize":{"x":4,"y":5}}
            ]}
            """);
        RecordingPortalFactory factory = new RecordingPortalFactory();

        BetterPortalsImporter importer = new BetterPortalsImporter();
        assertTrue(importer.detect(serverRoot));
        assertEquals("betterportals", importer.id());
        PortalImportReport report = importer.importFrom(serverRoot, false, factory);

        assertEquals(2, report.createdCount());
        ImportedPortal north = factory.created().get(0);
        assertEquals("north", north.name());
        assertEquals("world", north.worldName());
        assertEquals(10, north.x());
        assertEquals(Direction.N, north.facing());
        assertEquals(2, north.width());
        assertEquals(3, north.height());
        assertEquals(Direction.E, factory.created().get(1).facing());
    }

    @Test
    void entriesWithoutAWorldAreSkippedAndBrokenJsonIsReportedOnce() throws IOException {
        writePortals("""
            {"portals":[{"name":"nowhere","originPos":{"x":1,"y":2,"z":3}}]}
            """);
        PortalImportReport report = new BetterPortalsImporter()
            .importFrom(serverRoot, false, new RecordingPortalFactory());
        assertEquals(0, report.createdCount());
        assertEquals(1, report.skippedCount());
        assertTrue(report.skipped().get(0).reason().contains("world"), report.skipped().toString());

        writePortals("not json at all");
        PortalImportReport broken = new BetterPortalsImporter()
            .importFrom(serverRoot, false, new RecordingPortalFactory());
        assertEquals(0, broken.createdCount());
        assertEquals(1, broken.skippedCount());
        assertTrue(broken.skipped().get(0).reason().contains("unreadable"), broken.skipped().toString());
    }

    @Test
    void anAbsentFileDetectsFalse() {
        assertFalse(new BetterPortalsImporter().detect(serverRoot));
    }

    private void writePortals(String json) throws IOException {
        Path file = serverRoot.resolve("plugins/BetterPortals/portals.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }
}
