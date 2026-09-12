package art.arcane.wormholes.config;

import art.arcane.wormholes.config.toml.DimensionalConfig;
import art.arcane.wormholes.config.toml.OpsConfig;
import art.arcane.wormholes.config.toml.WormholesConfigFile;
import art.arcane.wormholes.util.project.config.TomlCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpsConfigTest {
    @TempDir
    Path tempDir;

    @Test
    void freshInstallEmitsTheOpsAndDimensionalSections() throws IOException {
        WormholesSettings settings = WormholesSettings.loadAll(tempDir);
        List<String> emitted = emittedSettings(tempDir.resolve(WormholesSettings.CONFIG_FILE_NAME));

        assertTrue(emitted.contains("[ops.console]"), emitted.toString());
        assertTrue(emitted.contains("enabled = false"), emitted.toString());
        assertTrue(emitted.contains("bind = \"127.0.0.1\""), emitted.toString());
        assertTrue(emitted.contains("port = 8905"), emitted.toString());
        assertTrue(emitted.contains("token = \"\""), emitted.toString());
        assertTrue(emitted.contains("history-minutes = 30"), emitted.toString());
        assertTrue(emitted.contains("[ops.backup]"), emitted.toString());
        assertTrue(emitted.contains("interval-minutes = 60"), emitted.toString());
        assertTrue(emitted.contains("retain = 24"), emitted.toString());
        assertTrue(emitted.contains("[ops.webmap]"), emitted.toString());
        assertTrue(emitted.contains("link-lines = true"), emitted.toString());
        assertTrue(emitted.contains("respect-listed = true"), emitted.toString());
        assertTrue(emitted.contains("[dimensional]"), emitted.toString());
        assertTrue(emitted.contains("scales = [\"minecraft:the_nether:8.0\"]"), emitted.toString());
        assertTrue(emitted.contains("groups = []"), emitted.toString());

        OpsConfig ops = settings.getOps();
        assertFalse(ops.console.enabled);
        assertEquals("127.0.0.1", ops.console.bind);
        assertEquals(8905, ops.console.port);
        assertEquals("", ops.console.token);
        assertEquals(30, ops.console.historyMinutes);
        assertTrue(ops.backup.enabled);
        assertEquals(60, ops.backup.intervalMinutes);
        assertEquals(24, ops.backup.retain);
        assertTrue(ops.webmap.enabled);
        assertTrue(ops.webmap.linkLines);
        assertTrue(ops.webmap.respectListed);

        DimensionalConfig dimensional = settings.getDimensional();
        assertEquals(List.of("minecraft:the_nether:8.0"), dimensional.scales);
        assertEquals(List.of(), dimensional.groups);
    }

    @Test
    void opsAndDimensionalValuesRoundTripThroughCanonicalWrite() throws IOException {
        File file = tempDir.resolve("wormholes.toml").toFile();
        WormholesConfigFile created = new WormholesConfigFile();
        created.ops.console.enabled = true;
        created.ops.console.bind = "10.0.0.4";
        created.ops.console.port = 9905;
        created.ops.console.token = "secret";
        created.ops.console.historyMinutes = 5;
        created.ops.backup.enabled = false;
        created.ops.backup.intervalMinutes = 15;
        created.ops.backup.retain = 3;
        created.ops.webmap.enabled = false;
        created.ops.webmap.linkLines = false;
        created.ops.webmap.respectListed = false;
        created.dimensional.scales = List.of("minecraft:the_nether:4.0", "custom:mine:2.0");
        created.dimensional.groups = List.of("world,world_nether", "arena,arena");
        TomlCodec.writeCanonical(file, created);

        String written = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        assertTrue(written.contains("bind = \"10.0.0.4\""), written);
        assertTrue(written.contains("scales = [\"minecraft:the_nether:4.0\", \"custom:mine:2.0\"]"), written);
        assertTrue(written.contains("groups = [\"world,world_nether\", \"arena,arena\"]"), written);

        TomlCodec.LoadResult<WormholesConfigFile> result = TomlCodec.readExisting(file, WormholesConfigFile.class);
        assertTrue(result.isSuccess());
        WormholesConfigFile loaded = result.value();
        assertTrue(loaded.ops.console.enabled);
        assertEquals(9905, loaded.ops.console.port);
        assertEquals("secret", loaded.ops.console.token);
        assertEquals(5, loaded.ops.console.historyMinutes);
        assertFalse(loaded.ops.backup.enabled);
        assertEquals(15, loaded.ops.backup.intervalMinutes);
        assertEquals(3, loaded.ops.backup.retain);
        assertFalse(loaded.ops.webmap.linkLines);
        assertEquals(List.of("minecraft:the_nether:4.0", "custom:mine:2.0"), loaded.dimensional.scales);
        assertEquals(List.of("world,world_nether", "arena,arena"), loaded.dimensional.groups);
    }

    private static List<String> emittedSettings(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
            .map(String::trim)
            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
            .toList();
    }
}
