package art.arcane.wormholes.config;

import art.arcane.wormholes.config.toml.DoorsConfig;
import art.arcane.wormholes.config.toml.PocketsConfig;
import art.arcane.wormholes.localization.DoorViewMessages;
import art.arcane.wormholes.localization.PocketsMessages;
import art.arcane.volmlib.util.localization.MessageKey;
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

class DoorsConfigTest {
    @TempDir
    Path tempDir;

    @Test
    void freshInstallEmitsTheDoorsAndPocketsSectionsWithProjectionOff() throws IOException {
        WormholesSettings settings = WormholesSettings.loadAll(tempDir);

        DoorsConfig doors = settings.getDoors();
        assertFalse(doors.projectionEnabled);
        assertEquals(64, doors.projectionMaxActive);
        assertEquals(24, doors.projectionRange);
        assertEquals(24, doors.projectionDepthBlocks);
        assertEquals(20, doors.projectionAttendanceSlots);
        assertTrue(doors.projectionHideBacking);

        PocketsConfig pockets = settings.getPockets();
        assertEquals("pockets/templates", pockets.templatesDir);
        assertEquals("", pockets.defaultTemplate);
        assertEquals(9, pockets.roomsPerPocketMax);
        assertEquals("on-empty", pockets.instanceReset);
        assertEquals(600, pockets.instanceResetSeconds);
        assertEquals(64, pockets.instanceMaxLive);
        assertFalse(pockets.rulesDefaultMobs);
        assertFalse(pockets.rulesDefaultPvp);
        assertTrue(pockets.rulesDefaultKeepInventory);
        assertEquals(-1L, pockets.rulesDefaultFixedTime);
        assertEquals("builders", pockets.rulesDefaultBuild);

        List<String> emitted = emitted(tempDir.resolve(WormholesSettings.CONFIG_FILE_NAME));
        assertTrue(emitted.contains("[doors]"), "doors section");
        assertTrue(emitted.contains("projection-enabled = false"), "projection-enabled");
        assertTrue(emitted.contains("projection-max-active = 64"), "projection-max-active");
        assertTrue(emitted.contains("projection-hide-backing = true"), "projection-hide-backing");
        assertTrue(emitted.contains("[pockets]"), "pockets section");
        assertTrue(emitted.contains("instance-reset = \"on-empty\""), "instance-reset");
        assertTrue(emitted.contains("rooms-per-pocket-max = 9"), "rooms-per-pocket-max");
        assertTrue(emitted.contains("rules-default-keep-inventory = true"), "rules-default-keep-inventory");
    }

    @Test
    void everyDoorsLaneMessageKeyUsesItsLanePrefix() {
        List<MessageKey> doorView = DoorViewMessages.keys();
        List<MessageKey> pockets = PocketsMessages.keys();

        assertFalse(doorView.isEmpty());
        assertFalse(pockets.isEmpty());
        for (MessageKey key : doorView) {
            assertTrue(key.id().startsWith("doorview."), key.id());
        }
        for (MessageKey key : pockets) {
            assertTrue(key.id().startsWith("pockets."), key.id());
        }
    }

    private static List<String> emitted(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
    }
}
