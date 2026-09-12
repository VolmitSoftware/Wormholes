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

import art.arcane.wormholes.config.toml.RenderConfig;

class FidelityConfigTest {
    @TempDir
    Path tempDir;

    @Test
    void freshInstallEmitsTheFidelitySectionsWithTheirDefaults() throws IOException {
        WormholesSettings settings = WormholesSettings.loadAll(tempDir);
        List<String> emitted = emittedSettings(tempDir.resolve(WormholesSettings.CONFIG_FILE_NAME));

        assertTrue(emitted.contains("[atmosphere]"));
        assertTrue(emitted.contains("mode-default = \"tint_light\""));
        assertTrue(emitted.contains("biome-tint = true"));
        assertTrue(emitted.contains("biome-dominance = 0.6"));
        assertTrue(emitted.contains("sky-light = true"));
        assertTrue(emitted.contains("fog-plate = true"));
        assertTrue(emitted.contains("weather = true"));
        assertTrue(emitted.contains("[lod]"));
        assertTrue(emitted.contains("distance-blocks = 32"));
        assertTrue(emitted.contains("merge-runs = true"));
        assertTrue(emitted.contains("detail-cutoff-blocks = 48"));
        assertTrue(emitted.contains("dissolve-ticks = 8"));
        assertTrue(emitted.contains("[acoustics]"));
        assertTrue(emitted.contains("profile-default = \"ambient\""));
        assertTrue(emitted.contains("radius = 24.0"));
        assertTrue(emitted.contains("rate-cap-per-observer = 8"));
        assertTrue(emitted.contains("[bedrock]"));
        assertTrue(emitted.contains("enabled = true"));
        assertTrue(emitted.contains("display-entities = false"));
        assertTrue(emitted.contains("lighting-fidelity = false"));
        assertTrue(emitted.contains("entity-cap = 8"));
        assertTrue(emitted.contains("shared-plate = true"));
        assertTrue(emitted.contains("plate-max-bytes = 33554432"));
        assertTrue(emitted.contains("plate-workers = 2"));
        assertTrue(emitted.contains("block-entities = true"));
        assertTrue(emitted.contains("block-entity-budget-per-tick = 64"));
        assertTrue(emitted.contains("block-entity-containers = false"));
        assertTrue(emitted.contains("block-entity-types = [\"sign\", \"hanging_sign\", \"banner\", \"skull\", \"decorated_pot\", \"bell\", \"spawner\"]"));

        assertEquals("tint_light", settings.getAtmosphere().modeDefault);
        assertEquals(0.6D, settings.getAtmosphere().biomeDominance);
        assertEquals(8, settings.getLod().dissolveTicks);
        assertEquals("ambient", settings.getAcoustics().profileDefault);
        assertEquals(8, settings.getBedrock().entityCap);
        assertTrue(settings.getProjection().sharedPlate);
        assertEquals(33_554_432L, settings.getProjection().plateMaxBytes);
        assertFalse(settings.getRender().blockEntityContainers);
        assertEquals(RenderConfig.DEFAULT_BLOCK_ENTITY_TYPES, settings.getRender().blockEntityTypes);
    }

    @Test
    void fidelityValuesRoundTripThroughAnExistingFile() throws IOException {
        Path config = tempDir.resolve(WormholesSettings.CONFIG_FILE_NAME);
        Files.writeString(config, """
            schema = 3
            [projection]
            shared-plate = false
            plate-workers = 3
            [render]
            block-entity-types = ["sign"]
            block-entity-containers = true
            [atmosphere]
            mode-default = "full"
            biome-dominance = 0.4
            [lod]
            dissolve-ticks = 12
            [acoustics]
            profile-default = "off"
            [bedrock]
            entity-cap = 3
            """, StandardCharsets.UTF_8);

        WormholesSettings settings = WormholesSettings.loadAll(tempDir);

        assertFalse(settings.getProjection().sharedPlate);
        assertEquals(3, settings.getProjection().plateWorkers);
        assertEquals(List.of("sign"), settings.getRender().blockEntityTypes);
        assertTrue(settings.getRender().blockEntityContainers);
        assertEquals("full", settings.getAtmosphere().modeDefault);
        assertEquals(0.4D, settings.getAtmosphere().biomeDominance);
        assertEquals(12, settings.getLod().dissolveTicks);
        assertEquals("off", settings.getAcoustics().profileDefault);
        assertEquals(3, settings.getBedrock().entityCap);
    }

    private static List<String> emittedSettings(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
            .map(String::trim)
            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
            .toList();
    }
}
