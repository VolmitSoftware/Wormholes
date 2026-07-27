package art.arcane.wormholes.util.project.config;

import art.arcane.wormholes.config.VisualQualityProfile;
import art.arcane.wormholes.config.WormholesSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HotloadManagerTest {
    @TempDir
    Path tempDir;

    @Test
    void malformedEditKeepsLastKnownGoodAndCorrectedEditRecovers() throws Exception {
        WormholesSettings initial = WormholesSettings.loadAll(tempDir);
        AtomicReference<WormholesSettings> live = new AtomicReference<>(initial);
        AtomicInteger callbacks = new AtomicInteger();
        CountDownLatch corrected = new CountDownLatch(1);
        HotloadManager manager = new HotloadManager(tempDir, Logger.getLogger("HotloadManagerTest"), (settings, completion) -> {
            live.set(settings);
            callbacks.incrementAndGet();
            completion.complete(true, null);
            corrected.countDown();
            return true;
        });
        Path config = tempDir.resolve("config").resolve(WormholesSettings.CONFIG_FILE_NAME);

        manager.start();
        try {
            Files.writeString(config, "schema = 2\nquality = \"unterminated\n", StandardCharsets.UTF_8);
            Thread.sleep(2_500L);

            assertSame(initial, live.get());
            assertEquals(0, callbacks.get());

            Files.writeString(config, "schema = 2\nquality = \"performance\"\n", StandardCharsets.UTF_8);
            assertTrue(corrected.await(5, TimeUnit.SECONDS));
            assertEquals(1, callbacks.get());
            assertEquals(VisualQualityProfile.PERFORMANCE, live.get().getVisualQualityProfile());
        } finally {
            manager.stop();
        }
    }

    @Test
    void validEditRetriesWhenApplicationIsTemporarilyRefused() throws Exception {
        AtomicInteger callbacks = new AtomicInteger();
        CountDownLatch applied = new CountDownLatch(1);
        HotloadManager manager = new HotloadManager(tempDir, Logger.getLogger("HotloadManagerRetryTest"), (settings, completion) -> {
            if (callbacks.incrementAndGet() == 1) {
                return false;
            }
            completion.complete(true, null);
            applied.countDown();
            return true;
        });
        Path config = tempDir.resolve("config").resolve(WormholesSettings.CONFIG_FILE_NAME);

        manager.start();
        try {
            Files.writeString(config, "schema = 2\nquality = \"performance\"\n", StandardCharsets.UTF_8);

            assertTrue(applied.await(5, TimeUnit.SECONDS));
            assertTrue(callbacks.get() >= 2);
        } finally {
            manager.stop();
        }
    }

    @Test
    void acceptedTaskIsRecordedOnlyAfterApplicationCompletesAndFailuresRetry() throws Exception {
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<HotloadManager.ReloadCompletion> firstCompletion = new AtomicReference<>();
        CountDownLatch firstScheduled = new CountDownLatch(1);
        CountDownLatch applied = new CountDownLatch(1);
        HotloadManager manager = new HotloadManager(
            tempDir,
            Logger.getLogger("HotloadManagerCompletionTest"),
            (settings, completion) -> {
                if (callbacks.incrementAndGet() == 1) {
                    firstCompletion.set(completion);
                    firstScheduled.countDown();
                    return true;
                }
                completion.complete(true, null);
                applied.countDown();
                return true;
            }
        );
        Path config = tempDir.resolve("config").resolve(WormholesSettings.CONFIG_FILE_NAME);

        manager.start();
        try {
            Files.writeString(config, "schema = 2\nquality = \"performance\"\n", StandardCharsets.UTF_8);
            assertTrue(firstScheduled.await(5, TimeUnit.SECONDS));
            Thread.sleep(1_200L);
            assertEquals(1, callbacks.get());

            firstCompletion.get().complete(false, new IllegalStateException("cancelled"));
            assertTrue(applied.await(5, TimeUnit.SECONDS));
            assertEquals(2, callbacks.get());
        } finally {
            manager.stop();
        }
    }
}
