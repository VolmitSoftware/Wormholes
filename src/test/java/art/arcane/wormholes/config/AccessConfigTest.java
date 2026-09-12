package art.arcane.wormholes.config;

import art.arcane.wormholes.config.toml.AccessConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessConfigTest {
    @TempDir
    Path tempDir;

    @Test
    void freshInstallEmitsTheAccessSectionWithProtectionDefaults() throws IOException {
        WormholesSettings settings = WormholesSettings.loadAll(tempDir);

        AccessConfig access = settings.getAccess();
        assertTrue(access.legacyNameNodeEnabled);
        assertTrue(access.claimCheckOnConstruct);
        assertTrue(access.claimCheckOnLink);
        assertFalse(access.claimCheckOnUse);
        assertTrue(access.grandfatherExistingPortals);
        assertEquals(0, access.portalLimitDefault);
        assertTrue(access.worldguardFlagsEnabled);
        assertEquals("worldguard,griefprevention,towny,lands,plotsquared", access.claimAdapters);

        List<String> emitted = emittedSettings(tempDir.resolve(WormholesSettings.CONFIG_FILE_NAME));
        assertTrue(emitted.contains("[access]"));
        assertTrue(emitted.contains("legacy-name-node-enabled = true"));
        assertTrue(emitted.contains("claim-check-on-construct = true"));
        assertTrue(emitted.contains("claim-check-on-link = true"));
        assertTrue(emitted.contains("claim-check-on-use = false"));
        assertTrue(emitted.contains("grandfather-existing-portals = true"));
        assertTrue(emitted.contains("portal-limit-default = 0"));
        assertTrue(emitted.contains("worldguard-flags-enabled = true"));
        assertTrue(emitted.contains("claim-adapters = \"worldguard,griefprevention,towny,lands,plotsquared\""));
    }

    @Test
    void operatorValuesSurviveAReloadOfTheAccessSection() throws IOException {
        Path config = tempDir.resolve(WormholesSettings.CONFIG_FILE_NAME);
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
            schema = 3
            [access]
            legacy-name-node-enabled = false
            portal-limit-default = 12
            claim-adapters = "griefprevention"
            """, StandardCharsets.UTF_8);

        AccessConfig access = WormholesSettings.loadAll(tempDir).getAccess();

        assertFalse(access.legacyNameNodeEnabled);
        assertEquals(12, access.portalLimitDefault);
        assertEquals("griefprevention", access.claimAdapters);
        assertTrue(access.claimCheckOnConstruct);
    }

    private static List<String> emittedSettings(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
            .map(String::trim)
            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
            .toList();
    }
}
