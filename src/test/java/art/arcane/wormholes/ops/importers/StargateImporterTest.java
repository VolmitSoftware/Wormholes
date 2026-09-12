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

class StargateImporterTest {
    @TempDir
    Path serverRoot;

    @Test
    void detectsOnlyWhenTheStargatePortalFolderExists() throws IOException {
        StargateImporter importer = new StargateImporter();
        assertFalse(importer.detect(serverRoot));
        writeDatabase("world.db", "gateA:1,2,3:10,64,20:1,0,0.0:nether.gate:gateB:hub:Steve:false:false:false");
        assertTrue(importer.detect(serverRoot));
        assertEquals("stargate", importer.id());
    }

    @Test
    void eachGateBecomesANetworkNamedPortalFacingItsModVector() throws IOException {
        writeDatabase("world.db", """
            gateA:1,2,3:10,64,20:1,0,0.0:nether.gate:gateB:hub:Steve:false:false:false
            gateB:4,5,6:-30,70,12:0,-1,0.0:nether.gate:gateA:hub:Steve:false:false:false
            """);
        RecordingPortalFactory factory = new RecordingPortalFactory()
            .withKnownDestination("hub:gateA").withKnownDestination("hub:gateB");

        PortalImportReport report = new StargateImporter().importFrom(serverRoot, false, factory);

        assertEquals(2, report.createdCount());
        assertEquals(0, report.skippedCount());
        ImportedPortal first = factory.created().get(0);
        assertEquals("hub:gateA", first.name());
        assertEquals("world", first.worldName());
        assertEquals(10, first.x());
        assertEquals(64, first.y());
        assertEquals(20, first.z());
        assertEquals(Direction.E, first.facing());
        assertEquals(Direction.N, factory.created().get(1).facing());
        assertEquals(2, factory.links().size());
        assertTrue(factory.links().containsValue("hub:gateB"));
        assertTrue(report.notes().stream().anyMatch(note -> note.contains("network")), report.notes().toString());
    }

    @Test
    void aSecondRunReportsTheSitesItAlreadyBuiltInsteadOfCountingThemTwice() throws IOException {
        writeDatabase("world.db", """
            gateA:1,2,3:10,64,20:1,0,0.0:nether.gate:gateB:hub:Steve:false:false:false
            gateB:4,5,6:-30,70,12:0,-1,0.0:nether.gate:gateA:hub:Steve:false:false:false
            """);
        RecordingPortalFactory second = new RecordingPortalFactory().withOccupiedSite("hub:gateA");

        PortalImportReport report = new StargateImporter().importFrom(serverRoot, false, second);

        assertEquals(1, report.createdCount(), "a portal that failed to build is not a created portal");
        assertEquals(1, report.skippedCount());
        assertEquals("hub:gateA", report.skipped().get(0).name());
        assertTrue(report.skipped().get(0).reason().contains("already exists"), report.skipped().toString());
    }

    @Test
    void aFailedBuildIsReportedWithItsRealCauseAndNotCountedAsCreated() throws IOException {
        writeDatabase("world.db", "gateA:1,2,3:10,64,20:1,0,0.0:nether.gate::hub:Steve:false:false:false\n");
        RecordingPortalFactory unloaded = new RecordingPortalFactory().withUnusableWorld("world");

        PortalImportReport report = new StargateImporter().importFrom(serverRoot, false, unloaded);

        assertEquals(0, report.createdCount());
        assertEquals(1, report.skippedCount());
        assertTrue(report.skipped().get(0).reason().contains("not loaded"), report.skipped().toString());
    }

    @Test
    void aDryRunStillCountsWhatItWouldCreate() throws IOException {
        writeDatabase("world.db", "gateA:1,2,3:10,64,20:1,0,0.0:nether.gate::hub:Steve:false:false:false\n");
        RecordingPortalFactory factory = new RecordingPortalFactory();

        PortalImportReport report = new StargateImporter().importFrom(serverRoot, true, factory);

        assertEquals(1, report.createdCount());
        assertEquals(0, report.skippedCount());
        assertTrue(factory.created().isEmpty(), "a dry run never touches the world");
    }

    @Test
    void unresolvableDestinationsAndMalformedLinesAreReportedNotSilentlyDropped() throws IOException {
        writeDatabase("world.db", """
            gateA:1,2,3:10,64,20:1,0,0.0:nether.gate:missing:hub:Steve:false:false:false
            nonsense
            gateC:1,2,3:bad,coords,here:1,0,0.0:nether.gate::hub:Steve:false:false:false
            """);
        RecordingPortalFactory factory = new RecordingPortalFactory();

        PortalImportReport report = new StargateImporter().importFrom(serverRoot, false, factory);

        assertEquals(1, report.createdCount());
        assertEquals(2, report.skippedCount());
        assertTrue(report.skipped().get(0).reason().contains("line"), report.skipped().toString());
        assertTrue(report.skipped().get(1).reason().contains("coordinates"), report.skipped().toString());
        assertEquals(1, report.unlinked().size());
        assertEquals("hub:missing", report.unlinked().get(0));
    }

    @Test
    void aDryRunCreatesNothing() throws IOException {
        writeDatabase("world.db", "gateA:1,2,3:10,64,20:1,0,0.0:nether.gate:gateB:hub:Steve:false:false:false");
        RecordingPortalFactory factory = new RecordingPortalFactory();

        PortalImportReport report = new StargateImporter().importFrom(serverRoot, true, factory);

        assertEquals(1, report.createdCount());
        assertTrue(factory.created().isEmpty(), "a dry run must not create portals");
        assertTrue(report.dryRun());
    }

    private void writeDatabase(String name, String content) throws IOException {
        Path file = serverRoot.resolve("plugins/Stargate/portals").resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
