package art.arcane.wormholes.util.project.config;

import art.arcane.wormholes.config.VisualQualityProfile;
import art.arcane.wormholes.config.WormholesSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HotloadManagerTest {
    private static final long TEST_COOLDOWN_MS = 250L;

    @TempDir
    Path tempDir;

    @Test
    void malformedEditKeepsLastKnownGoodAndCorrectedEditRecovers() throws Exception {
        WormholesSettings initial = WormholesSettings.loadAll(tempDir);
        AtomicReference<WormholesSettings> live = new AtomicReference<>(initial);
        AtomicInteger callbacks = new AtomicInteger();
        CountDownLatch corrected = new CountDownLatch(1);
        HotloadManager manager = manager("HotloadManagerMalformedTest", (settings, completion) -> {
            live.set(settings);
            callbacks.incrementAndGet();
            completion.complete(true, null);
            corrected.countDown();
            return true;
        });
        Path config = configFile();

        manager.start();
        try {
            Files.writeString(config, "schema = 3\nquality = \"unterminated\n", StandardCharsets.UTF_8);
            assertFalse(corrected.await(300L, TimeUnit.MILLISECONDS));
            assertSame(initial, live.get());
            assertEquals(0, callbacks.get());

            Files.writeString(config, config(VisualQualityProfile.PERFORMANCE), StandardCharsets.UTF_8);
            assertTrue(corrected.await(2L, TimeUnit.SECONDS));
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
        HotloadManager manager = manager("HotloadManagerRetryTest", (settings, completion) -> {
            if (callbacks.incrementAndGet() == 1) {
                return false;
            }
            completion.complete(true, null);
            applied.countDown();
            return true;
        });

        manager.start();
        try {
            Files.writeString(configFile(), config(VisualQualityProfile.PERFORMANCE), StandardCharsets.UTF_8);

            assertTrue(applied.await(2L, TimeUnit.SECONDS));
            assertEquals(2, callbacks.get());
        } finally {
            manager.stop();
        }
    }

    @Test
    void failedApplicationIsRetriedAndAcknowledgedOnlyAfterCompletion() throws Exception {
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<HotloadManager.ReloadCompletion> firstCompletion = new AtomicReference<>();
        CountDownLatch firstScheduled = new CountDownLatch(1);
        CountDownLatch applied = new CountDownLatch(1);
        HotloadManager manager = manager("HotloadManagerCompletionTest", (settings, completion) -> {
            if (callbacks.incrementAndGet() == 1) {
                firstCompletion.set(completion);
                firstScheduled.countDown();
                return true;
            }
            completion.complete(true, null);
            applied.countDown();
            return true;
        });

        manager.start();
        try {
            Files.writeString(configFile(), config(VisualQualityProfile.PERFORMANCE), StandardCharsets.UTF_8);
            assertTrue(firstScheduled.await(2L, TimeUnit.SECONDS));
            assertFalse(applied.await(200L, TimeUnit.MILLISECONDS));
            assertEquals(1, callbacks.get());

            firstCompletion.get().complete(false, new IllegalStateException("cancelled"));
            assertTrue(applied.await(2L, TimeUnit.SECONDS));
            assertEquals(2, callbacks.get());
        } finally {
            manager.stop();
        }
    }

    @Test
    void burstQueuesOnlyLatestSnapshotBehindCompletionAnchoredCooldown() throws Exception {
        List<VisualQualityProfile> appliedProfiles = new CopyOnWriteArrayList<>();
        List<Long> completionTimes = new CopyOnWriteArrayList<>();
        CountDownLatch firstApplied = new CountDownLatch(1);
        CountDownLatch secondApplied = new CountDownLatch(1);
        HotloadManager manager = manager("HotloadManagerBurstTest", (settings, completion) -> {
            appliedProfiles.add(settings.getVisualQualityProfile());
            completion.complete(true, null);
            completionTimes.add(System.nanoTime());
            if (appliedProfiles.size() == 1) {
                firstApplied.countDown();
            } else {
                secondApplied.countDown();
            }
            return true;
        });

        manager.start();
        try {
            Files.writeString(configFile(), config(VisualQualityProfile.PERFORMANCE), StandardCharsets.UTF_8);
            assertTrue(firstApplied.await(2L, TimeUnit.SECONDS));
            Files.writeString(configFile(), config(VisualQualityProfile.BALANCED), StandardCharsets.UTF_8);
            Files.writeString(configFile(), config(VisualQualityProfile.CINEMATIC), StandardCharsets.UTF_8);

            assertTrue(secondApplied.await(2L, TimeUnit.SECONDS));
            assertEquals(List.of(VisualQualityProfile.PERFORMANCE, VisualQualityProfile.CINEMATIC), appliedProfiles);
            long spacingMs = TimeUnit.NANOSECONDS.toMillis(completionTimes.get(1) - completionTimes.get(0));
            assertTrue(spacingMs >= TEST_COOLDOWN_MS - 25L, "hotload completions were only " + spacingMs + "ms apart");
        } finally {
            manager.stop();
        }
    }

    @Test
    void sameMetadataAtomicReplacementIsDetectedWithoutCanonicalRewrite() throws Exception {
        String initial = config(VisualQualityProfile.CINEMATIC);
        String replacement = "schema = 3\nquality = \"balanced\" \n";
        assertEquals(initial.length(), replacement.length());
        Files.createDirectories(configFile().getParent());
        Files.writeString(configFile(), initial, StandardCharsets.UTF_8);
        FileTime originalModified = Files.getLastModifiedTime(configFile());
        CountDownLatch applied = new CountDownLatch(1);
        AtomicReference<VisualQualityProfile> profile = new AtomicReference<>();
        HotloadManager manager = manager("HotloadManagerAtomicReplaceTest", (settings, completion) -> {
            profile.set(settings.getVisualQualityProfile());
            completion.complete(true, null);
            applied.countDown();
            return true;
        });

        manager.start();
        try {
            Path temporary = Files.createTempFile(configFile().getParent(), "wormholes-", ".tmp");
            Files.writeString(temporary, replacement, StandardCharsets.UTF_8);
            Files.setLastModifiedTime(temporary, originalModified);
            try {
                Files.move(
                    temporary,
                    configFile(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException | UnsupportedOperationException failure) {
                Files.move(temporary, configFile(), StandardCopyOption.REPLACE_EXISTING);
                Files.setLastModifiedTime(configFile(), originalModified);
            }

            assertTrue(applied.await(2L, TimeUnit.SECONDS));
            assertEquals(VisualQualityProfile.BALANCED, profile.get());
            assertEquals(replacement, Files.readString(configFile(), StandardCharsets.UTF_8));
        } finally {
            manager.stop();
        }
    }

    @Test
    void editSavedDuringApplicationRemainsQueuedAndAppliesNext() throws Exception {
        List<VisualQualityProfile> appliedProfiles = new CopyOnWriteArrayList<>();
        AtomicReference<HotloadManager.ReloadCompletion> firstCompletion = new AtomicReference<>();
        CountDownLatch firstScheduled = new CountDownLatch(1);
        CountDownLatch secondApplied = new CountDownLatch(1);
        HotloadManager manager = manager("HotloadManagerSaveDuringApplyTest", (settings, completion) -> {
            appliedProfiles.add(settings.getVisualQualityProfile());
            if (appliedProfiles.size() == 1) {
                firstCompletion.set(completion);
                firstScheduled.countDown();
            } else {
                completion.complete(true, null);
                secondApplied.countDown();
            }
            return true;
        });

        manager.start();
        try {
            Files.writeString(configFile(), config(VisualQualityProfile.PERFORMANCE), StandardCharsets.UTF_8);
            assertTrue(firstScheduled.await(2L, TimeUnit.SECONDS));
            Files.writeString(configFile(), config(VisualQualityProfile.CINEMATIC), StandardCharsets.UTF_8);
            firstCompletion.get().complete(true, null);

            assertTrue(secondApplied.await(2L, TimeUnit.SECONDS));
            assertEquals(List.of(VisualQualityProfile.PERFORMANCE, VisualQualityProfile.CINEMATIC), appliedProfiles);
        } finally {
            manager.stop();
        }
    }

    @Test
    void stopClosesWatcherAndPreventsLaterCallbacks() throws Exception {
        AtomicInteger callbacks = new AtomicInteger();
        HotloadManager manager = manager("HotloadManagerStopTest", (settings, completion) -> {
            callbacks.incrementAndGet();
            completion.complete(true, null);
            return true;
        });

        manager.start();
        manager.stop();
        Files.writeString(configFile(), config(VisualQualityProfile.PERFORMANCE), StandardCharsets.UTF_8);

        Thread.sleep(300L);
        assertEquals(0, callbacks.get());
    }

    @Test
    void startupEditAfterAppliedSnapshotIsQueuedInsteadOfBaselined() throws Exception {
        String applied = config(VisualQualityProfile.BALANCED);
        String newer = config(VisualQualityProfile.PERFORMANCE);
        Files.createDirectories(configFile().getParent());
        Files.writeString(configFile(), newer, StandardCharsets.UTF_8);
        CountDownLatch appliedNewer = new CountDownLatch(1);
        AtomicReference<VisualQualityProfile> profile = new AtomicReference<>();
        HotloadManager manager = manager("HotloadManagerStartupWindowTest", (settings, completion) -> {
            profile.set(settings.getVisualQualityProfile());
            completion.complete(true, null);
            appliedNewer.countDown();
            return true;
        });

        manager.startWithAppliedSnapshot(applied.getBytes(StandardCharsets.UTF_8));
        try {
            assertTrue(appliedNewer.await(2L, TimeUnit.SECONDS));
            assertEquals(VisualQualityProfile.PERFORMANCE, profile.get());
        } finally {
            manager.stop();
        }
    }

    @Test
    void idleEventPollingDefersFullSnapshotReadsUntilReconciliation() throws Exception {
        String initial = config(VisualQualityProfile.CINEMATIC);
        Files.createDirectories(configFile().getParent());
        Files.writeString(configFile(), initial, StandardCharsets.UTF_8);
        HotloadManager manager = manager("HotloadManagerIdleReadTest", (settings, completion) -> {
            completion.complete(true, null);
            return true;
        });

        manager.startWithAppliedSnapshot(initial.getBytes(StandardCharsets.UTF_8));
        try {
            Thread.sleep(250L);
            assertEquals(0L, manager.snapshotReadAttempts());

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
            while (manager.snapshotReadAttempts() == 0L && System.nanoTime() < deadline) {
                Thread.sleep(20L);
            }
            assertTrue(manager.snapshotReadAttempts() >= 1L);
        } finally {
            manager.stop();
        }
    }

    @Test
    void periodicReconciliationFindsSameMetadataChangeWithoutFilesystemEvents() throws Exception {
        String initial = config(VisualQualityProfile.CINEMATIC);
        String replacement = "schema = 3\nquality = \"balanced\" \n";
        assertEquals(initial.length(), replacement.length());
        Files.createDirectories(configFile().getParent());
        Files.writeString(configFile(), initial, StandardCharsets.UTF_8);
        FileTime originalModified = Files.getLastModifiedTime(configFile());
        CountDownLatch applied = new CountDownLatch(1);
        AtomicReference<VisualQualityProfile> profile = new AtomicReference<>();
        HotloadManager manager = manager(
            "HotloadManagerReconciliationFallbackTest",
            (settings, completion) -> {
                profile.set(settings.getVisualQualityProfile());
                completion.complete(true, null);
                applied.countDown();
                return true;
            },
            false,
            100L
        );

        manager.startWithAppliedSnapshot(initial.getBytes(StandardCharsets.UTF_8));
        try {
            Files.writeString(configFile(), replacement, StandardCharsets.UTF_8);
            Files.setLastModifiedTime(configFile(), originalModified);

            assertTrue(applied.await(2L, TimeUnit.SECONDS));
            assertEquals(VisualQualityProfile.BALANCED, profile.get());
        } finally {
            manager.stop();
        }
    }

    private HotloadManager manager(String loggerName, HotloadManager.ReloadCallback callback) {
        return manager(loggerName, callback, true, 500L);
    }

    private HotloadManager manager(String loggerName,
                                   HotloadManager.ReloadCallback callback,
                                   boolean filesystemEventsEnabled,
                                   long contentReconciliationMs) {
        HotloadManager.Timing timing = new HotloadManager.Timing(
            20L,
            60L,
            TEST_COOLDOWN_MS,
            1_000L,
            50L,
            200L,
            1_000L,
            50L,
            contentReconciliationMs
        );
        HotloadManager.Options options = new HotloadManager.Options(
            tempDir,
            Logger.getLogger(loggerName),
            callback,
            timing,
            filesystemEventsEnabled
        );
        return new HotloadManager(options);
    }

    private Path configFile() {
        return tempDir.resolve(WormholesSettings.CONFIG_FILE_NAME);
    }

    private String config(VisualQualityProfile profile) {
        return "schema = 3\nquality = \"" + profile.configValue() + "\"\n";
    }
}
