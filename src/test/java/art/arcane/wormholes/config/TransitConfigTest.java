package art.arcane.wormholes.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import art.arcane.wormholes.config.toml.TransitConfig;

final class TransitConfigTest {
    @TempDir
    Path tempDir;

    @Test
    void freshInstallEmitsTheTransitSectionWithSpecDefaults() throws IOException {
        WormholesSettings settings = WormholesSettings.loadAll(tempDir);
        List<String> emitted = emittedSettings(tempDir.resolve(WormholesSettings.CONFIG_FILE_NAME));

        assertTrue(emitted.contains("[transit]"));
        assertTrue(emitted.contains("momentum-default = \"preserve\""));
        assertTrue(emitted.contains("momentum-max-speed = 4.0"));
        assertTrue(emitted.contains("orientation-default = \"frame\""));
        assertTrue(emitted.contains("gravity-flip-enabled = true"));
        assertTrue(emitted.contains("object-transit-continuous = true"));
        assertTrue(emitted.contains("convoy-enabled = true"));
        assertTrue(emitted.contains("convoy-max-entities = 16"));
        assertTrue(emitted.contains("convoy-cross-server-enabled = true"));
        assertTrue(emitted.contains("convoy-cross-server-timeout-sec = 20"));
        assertTrue(emitted.contains("cinematics-enabled = true"));
        assertFalse(emitted.contains("cinematics-approach-range"));
        assertTrue(emitted.contains("arrival-mask-adaptive = true"));
        assertTrue(emitted.contains("arrival-mask-min-ticks = 5"));

        TransitConfig transit = settings.getTransit();
        assertEquals("preserve", transit.momentumDefault);
        assertEquals(4.0D, transit.momentumMaxSpeed);
        assertEquals("frame", transit.orientationDefault);
        assertTrue(transit.gravityFlipEnabled);
        assertTrue(transit.objectTransitContinuous);
        assertTrue(transit.convoyEnabled);
        assertEquals(16, transit.convoyMaxEntities);
        assertTrue(transit.convoyCrossServerEnabled);
        assertEquals(20, transit.convoyCrossServerTimeoutSec);
        assertTrue(transit.cinematicsEnabled);
        assertTrue(transit.arrivalMaskAdaptive);
        assertEquals(5, transit.arrivalMaskMinTicks);
    }

    @Test
    void operatorValuesSurviveTheCanonicalRewrite() throws IOException {
        Path config = tempDir.resolve(WormholesSettings.CONFIG_FILE_NAME);
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
            schema = 3
            [transit]
            momentum-default = "clamp"
            convoy-max-entities = 4
            cinematics-enabled = false
            """, StandardCharsets.UTF_8);

        WormholesSettings settings = WormholesSettings.loadAll(tempDir);

        assertEquals("clamp", settings.getTransit().momentumDefault);
        assertEquals(4, settings.getTransit().convoyMaxEntities);
        assertEquals(false, settings.getTransit().cinematicsEnabled);
        List<String> emitted = emittedSettings(config);
        assertTrue(emitted.contains("momentum-default = \"clamp\""));
        assertTrue(emitted.contains("convoy-max-entities = 4"));
        assertTrue(emitted.contains("orientation-default = \"frame\""));
    }

    private static List<String> emittedSettings(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
            .map(String::trim)
            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
            .toList();
    }
}
