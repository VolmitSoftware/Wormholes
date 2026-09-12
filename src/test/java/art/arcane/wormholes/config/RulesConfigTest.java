package art.arcane.wormholes.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RulesConfigTest {
    @TempDir
    Path tempDir;

    @Test
    void freshInstallEmitsRulesSectionWithDefaults() throws Exception {
        WormholesSettings settings = WormholesSettings.loadAll(tempDir);
        assertEquals(0.5D, settings.getRules().warmupCancelMoveBlocks);
        assertEquals(30, settings.getRules().warmupMaxSeconds);
        assertTrue(settings.getRules().routeCardEnabled);
        List<String> lines = Files.readAllLines(tempDir.resolve(WormholesSettings.CONFIG_FILE_NAME));
        assertTrue(lines.contains("[rules]"));
        assertTrue(lines.contains("warmup-cancel-move-blocks = 0.5"));
        assertTrue(lines.contains("expensive-condition-cache-millis = 1000"));
    }
}
