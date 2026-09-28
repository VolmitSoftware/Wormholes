package art.arcane.wormholes.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WormholesSettingsTest {
    @TempDir
    Path directory;

    @Test
    void usesPackagedLocaleNamesAndPreservesCustomLocales() {
        Map<String, String> locales = Map.of(" FR-fr ", "fr_FR", "JA_jp", "ja-JP",
            "en-us", "en_US", " custom_ES ", "custom_ES", "../outside", "en_US");
        for (Map.Entry<String, String> locale : locales.entrySet()) {
            WormholesSettings settings = WormholesSettings.loadSnapshot(("schema = 3\nlanguage = \""
                + locale.getKey() + "\"\nmetrics = false\n").getBytes(StandardCharsets.UTF_8));
            assertEquals(locale.getValue(), settings.getLanguage());
            assertFalse(settings.isMetrics());
            assertEquals(locale.getValue(), WormholesSettings.loadSnapshot(settings.canonicalSnapshot()).getLanguage());
            assertEquals(locale.getValue(), settings.withLanguage(locale.getKey()).getLanguage());
        }
    }

    @Test
    void createsCanonicalDefaultConfiguration() throws Exception {
        WormholesSettings settings = WormholesSettings.loadAll(directory);
        String content = Files.readString(directory.resolve(WormholesSettings.CONFIG_FILE_NAME));
        assertTrue(content.contains("schema = 3"));
        assertEquals("en_US", settings.getLanguage());
        assertEquals(settings.getMain().chunkPreSendMaxChunks,
            WormholesSettings.loadSnapshot(content.getBytes(StandardCharsets.UTF_8)).getMain().chunkPreSendMaxChunks);
    }

    @Test
    void visualProfilesApplyToRuntimeValuesWithoutOverwritingConfiguredValues() {
        String source = "schema = 3\nquality = \"performance\"\n[projection]\nrange = 120.0\ndepth-blocks = 128\n"
            + "[render]\nlighting-fidelity = true\nentity-spoofing = true\n";
        WormholesSettings settings = WormholesSettings.loadSnapshot(source.getBytes(StandardCharsets.UTF_8));
        assertEquals(32.0, settings.getProjection().range);
        assertEquals(48, settings.getProjection().depthBlocks);
        assertFalse(settings.getRender().lightingFidelity);
        assertFalse(settings.getRender().entitySpoofing);
        String canonical = new String(settings.withLanguage("fr_FR").canonicalSnapshot(), StandardCharsets.UTF_8);
        WormholesSettings restored = WormholesSettings.loadSnapshot(canonical.replace("quality = \"performance\"", "quality = \"auto\"")
            .getBytes(StandardCharsets.UTF_8));
        assertEquals(120.0, restored.getProjection().range);
        assertEquals(128, restored.getProjection().depthBlocks);
        assertTrue(restored.getRender().lightingFidelity);
        assertTrue(restored.getRender().entitySpoofing);
    }

    @Test
    void balancedAndCinematicProfilesApplySharedBudgets() {
        WormholesSettings balanced = WormholesSettings.loadSnapshot("schema = 3\nquality = \"balanced\"\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(6, balanced.getRender().lightingRefreshIntervalTicks);
        assertEquals(2, balanced.getRender().entityUpdateIntervalTicks);
        assertEquals(16, balanced.getRender().maxSpoofedEntities);
        assertEquals(20, balanced.getProjection().maxProjectorsPerTick);
        WormholesSettings cinematic = WormholesSettings.loadSnapshot("schema = 3\nquality = \"cinematic\"\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(64.0, cinematic.getProjection().range);
        assertEquals(96, cinematic.getProjection().depthBlocks);
        assertEquals(48, cinematic.getRender().maxSpoofedEntities);
        assertEquals(2, cinematic.getRender().lightingRefreshIntervalTicks);
    }

    @Test
    void invalidCurrentConfigurationIsNotRewritten() throws Exception {
        Path file = directory.resolve(WormholesSettings.CONFIG_FILE_NAME);
        String invalid = "schema = 3\nquality = \"unknown\"\n";
        Files.writeString(file, invalid);
        assertThrows(IllegalArgumentException.class, () -> WormholesSettings.loadAll(directory));
        assertEquals(invalid, Files.readString(file));
    }
}
