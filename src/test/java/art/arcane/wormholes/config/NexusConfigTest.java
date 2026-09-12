package art.arcane.wormholes.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NexusConfigTest {
    @TempDir
    Path tempDir;

    @Test
    void freshInstallEmitsNexusAndAtlasSectionsWithTheirDefaults() throws IOException {
        WormholesSettings settings = WormholesSettings.loadAll(tempDir);

        assertEquals("ABCDEFGHJKLMNPQRSTUVWXYZ23456789", settings.getNexus().addressAlphabet);
        assertEquals(4, settings.getNexus().addressLength);
        assertEquals(400, settings.getNexus().dialDebounceMillis);
        assertEquals(120, settings.getNexus().dialHoldSeconds);
        assertEquals(20, settings.getNexus().schedulerIntervalTicks);
        assertEquals(4, settings.getNexus().maxNetworksPerPlayer);
        assertEquals(256, settings.getNexus().maxMembersPerNetwork);
        assertTrue(settings.getNexus().redstoneEnabled);

        assertTrue(settings.getAtlas().enabled);
        assertTrue(settings.getAtlas().discoveryRequired);
        assertEquals(6.0D, settings.getAtlas().discoveryRadius);
        assertEquals(10, settings.getAtlas().recentLimit);
        assertEquals(27, settings.getAtlas().favoritesLimit);
        assertTrue(settings.getAtlas().guideEnabled);

        List<String> emitted = emittedSettings(tempDir.resolve(WormholesSettings.CONFIG_FILE_NAME));
        assertTrue(emitted.contains("[nexus]"), "missing [nexus] table");
        assertTrue(emitted.contains("[atlas]"), "missing [atlas] table");
        assertTrue(emitted.contains("address-alphabet = \"ABCDEFGHJKLMNPQRSTUVWXYZ23456789\""));
        assertTrue(emitted.contains("address-length = 4"));
        assertTrue(emitted.contains("dial-debounce-millis = 400"));
        assertTrue(emitted.contains("dial-hold-seconds = 120"));
        assertTrue(emitted.contains("scheduler-interval-ticks = 20"));
        assertTrue(emitted.contains("max-networks-per-player = 4"));
        assertTrue(emitted.contains("max-members-per-network = 256"));
        assertTrue(emitted.contains("redstone-enabled = true"));
        assertTrue(emitted.contains("discovery-required = true"));
        assertTrue(emitted.contains("discovery-radius = 6.0"));
        assertTrue(emitted.contains("show-coordinates = false"));
        assertTrue(emitted.contains("recent-limit = 10"));
        assertTrue(emitted.contains("favorites-limit = 27"));
        assertTrue(emitted.contains("guide-enabled = true"));
    }

    @Test
    void operatorValuesSurviveACanonicalRewrite() throws IOException {
        Path config = tempDir.resolve(WormholesSettings.CONFIG_FILE_NAME);
        Files.createDirectories(tempDir);
        Files.writeString(config, """
            schema = 3
            [nexus]
            address-length = 6
            dial-debounce-millis = 750
            [atlas]
            discovery-required = false
            show-coordinates = true
            """, StandardCharsets.UTF_8);

        WormholesSettings settings = WormholesSettings.loadAll(tempDir);

        assertEquals(6, settings.getNexus().addressLength);
        assertEquals(750, settings.getNexus().dialDebounceMillis);
        assertEquals(false, settings.getAtlas().discoveryRequired);
        assertTrue(settings.getAtlas().showCoordinates);

        List<String> emitted = emittedSettings(config);
        assertTrue(emitted.contains("address-length = 6"));
        assertTrue(emitted.contains("dial-debounce-millis = 750"));
        assertTrue(emitted.contains("discovery-required = false"));
        assertTrue(emitted.contains("show-coordinates = true"));
    }

    private static List<String> emittedSettings(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
            .map(String::trim)
            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
            .toList();
    }
}
