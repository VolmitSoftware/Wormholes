package art.arcane.wormholes.chunk;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.config.WormholesSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkSendRateSettingsTest {
    @TempDir
    Path tempDir;

    @Test
    void freshInstallEmitsTheTunerKeysAndEnablesItByDefault() throws IOException {
        WormholesSettings settings = WormholesSettings.loadAll(tempDir);
        Settings.refresh(settings);

        String content = Files.readString(
            tempDir.resolve(WormholesSettings.CONFIG_FILE_NAME), StandardCharsets.UTF_8);

        assertTrue(content.contains("chunk-send-rate-tuner = true"), content);
        assertTrue(content.contains("chunk-send-rate-target = 1000.0"), content);
        assertTrue(content.contains("chunk-load-rate-target = 1000.0"), content);
        assertTrue(Settings.CHUNK_SEND_RATE_TUNER);
        assertEquals(1000.0D, Settings.CHUNK_SEND_RATE_TARGET);
        assertEquals(1000.0D, Settings.CHUNK_LOAD_RATE_TARGET);
    }

    @Test
    void operatorValuesReachTheLiveSettings() throws IOException {
        write("schema = 3\n[main]\nchunk-send-rate-tuner = false\nchunk-send-rate-target = 250.0\nchunk-load-rate-target = 300.0\n");

        Settings.refresh(WormholesSettings.loadAll(tempDir));

        assertFalse(Settings.CHUNK_SEND_RATE_TUNER);
        assertEquals(250.0D, Settings.CHUNK_SEND_RATE_TARGET);
        assertEquals(300.0D, Settings.CHUNK_LOAD_RATE_TARGET);
    }

    @Test
    void negativeAndAboveCapTargetsBecomeUnlimited() throws IOException {
        write("schema = 3\n[main]\nchunk-send-rate-target = -1.0\nchunk-load-rate-target = 99999.0\n");

        Settings.refresh(WormholesSettings.loadAll(tempDir));

        assertEquals(0.0D, Settings.CHUNK_SEND_RATE_TARGET);
        assertEquals(0.0D, Settings.CHUNK_LOAD_RATE_TARGET);
        assertEquals(ChunkSendRateTuner.UNLIMITED_RATE, ChunkSendRateTuner.effectiveRate(Settings.CHUNK_SEND_RATE_TARGET));
        assertEquals(ChunkSendRateTuner.UNLIMITED_RATE, ChunkSendRateTuner.effectiveRate(Settings.CHUNK_LOAD_RATE_TARGET));
    }

    @Test
    void exactCapStaysFiniteAndZeroStaysUnlimited() throws IOException {
        write("schema = 3\n[main]\nchunk-send-rate-target = 10000.0\nchunk-load-rate-target = 0.0\n");

        Settings.refresh(WormholesSettings.loadAll(tempDir));

        assertEquals(10000.0D, Settings.CHUNK_SEND_RATE_TARGET);
        assertEquals(0.0D, Settings.CHUNK_LOAD_RATE_TARGET);
        assertEquals(10000.0D, ChunkSendRateTuner.effectiveRate(Settings.CHUNK_SEND_RATE_TARGET));
        assertEquals(ChunkSendRateTuner.UNLIMITED_RATE, ChunkSendRateTuner.effectiveRate(Settings.CHUNK_LOAD_RATE_TARGET));
    }

    private void write(String toml) throws IOException {
        Path config = tempDir.resolve(WormholesSettings.CONFIG_FILE_NAME);
        Files.createDirectories(config.getParent());
        Files.writeString(config, toml, StandardCharsets.UTF_8);
    }
}
