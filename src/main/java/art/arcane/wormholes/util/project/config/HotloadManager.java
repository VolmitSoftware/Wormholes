package art.arcane.wormholes.util.project.config;

import art.arcane.wormholes.config.WormholesSettings;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class HotloadManager {
    private static final long POLL_INTERVAL_MS = 200L;
    private static final long STABILITY_WINDOW_MS = 350L;
    private static final long HOTLOAD_COOLDOWN_MS = 3_000L;
    private static final long STOP_JOIN_TIMEOUT_MS = 2_000L;
    private static final long RETRY_BASE_MS = 1_000L;
    private static final long RETRY_MAX_MS = 30_000L;
    private static final long APPLICATION_TIMEOUT_MS = 10_000L;
    private static final long WATCH_RETRY_MS = 1_000L;
    private static final long CONTENT_RECONCILIATION_MS = 2_500L;
    private static final int MAX_CONFIG_BYTES = 8 * 1024 * 1024;

    private final Path configDir;
    private final Path configFile;
    private final Logger logger;
    private final ReloadCallback reloadCallback;
    private final Timing timing;
    private final boolean filesystemEventsEnabled;
    private final AtomicBoolean running;
    private final Object watchLock;
    private FileSignature lastApplied;
    private FileSignature lastRejected;
    private PendingChange pending;
    private FileSnapshot activeSnapshot;
    private long reloadAttempt;
    private long activeAttempt;
    private long retryAfterNanos;
    private long lastCompletionNanos;
    private long applicationStartedNanos;
    private long watchRetryAfterNanos;
    private long lastSnapshotReadNanos;
    private long snapshotReadAttempts;
    private int deferredRetryCount;
    private boolean applicationTimeoutLogged;
    private String lastReadFailure;
    private String lastWatchFailure;
    private volatile Thread watcherThread;
    private volatile WatchService watchService;

    public HotloadManager(Path dataFolder, Logger logger, ReloadCallback reloadCallback) {
        this(new Options(dataFolder, logger, reloadCallback, Timing.production(), true));
    }

    HotloadManager(Options options) {
        Options required = Objects.requireNonNull(options);
        Path dataFolder = Objects.requireNonNull(required.dataFolder()).toAbsolutePath().normalize();
        configDir = dataFolder.resolve("config");
        configFile = configDir.resolve(WormholesSettings.CONFIG_FILE_NAME);
        logger = Objects.requireNonNull(required.logger());
        reloadCallback = Objects.requireNonNull(required.reloadCallback());
        timing = Objects.requireNonNull(required.timing());
        filesystemEventsEnabled = required.filesystemEventsEnabled();
        running = new AtomicBoolean(false);
        watchLock = new Object();
    }

    public void start() {
        startInternal(null, true);
    }

    public void startWithAppliedSnapshot(byte[] appliedContent) {
        byte[] requiredContent = Objects.requireNonNull(appliedContent, "Applied configuration snapshot cannot be null");
        if (requiredContent.length == 0) {
            throw new IllegalArgumentException("Applied configuration snapshot size is invalid: " + requiredContent.length);
        }
        startInternal(new FileSignature(requiredContent.length, digest(requiredContent)), false);
    }

    public void startWithPendingSnapshot() {
        startInternal(null, false);
    }

    private void startInternal(FileSignature appliedSignature, boolean captureDiskBaseline) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            Files.createDirectories(configDir);
        } catch (IOException | SecurityException failure) {
            logger.log(Level.WARNING, "[Hotload] Failed to create configuration directory " + configDir, failure);
        }
        synchronized (this) {
            captureBaseline(appliedSignature, captureDiskBaseline);
            pending = null;
            activeSnapshot = null;
            activeAttempt = 0L;
            retryAfterNanos = 0L;
            lastCompletionNanos = 0L;
            lastSnapshotReadNanos = System.nanoTime();
            snapshotReadAttempts = 0L;
            deferredRetryCount = 0;
            applicationTimeoutLogged = false;
        }
        Thread thread = new Thread(this::watchLoop, "Wormholes-Hotload-Watcher");
        thread.setDaemon(true);
        watcherThread = thread;
        thread.start();
        logger.info("[Hotload] Watching " + configFile
            + " with filesystem events and content reconciliation (stability=" + timing.stabilityWindowMs()
            + "ms, cooldown=" + timing.hotloadCooldownMs() + "ms)");
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        closeWatchService();
        Thread thread = watcherThread;
        watcherThread = null;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(timing.stopJoinTimeoutMs());
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
            if (thread.isAlive()) {
                logger.warning("[Hotload] Watcher thread did not exit within "
                    + timing.stopJoinTimeoutMs() + "ms; abandoning it");
            }
        }
        synchronized (this) {
            pending = null;
            activeSnapshot = null;
            activeAttempt = 0L;
        }
    }

    private synchronized void captureBaseline(FileSignature appliedSignature, boolean captureDiskBaseline) {
        if (appliedSignature != null) {
            lastApplied = appliedSignature;
        } else if (captureDiskBaseline) {
            FileSnapshot baseline = readSnapshot();
            lastApplied = baseline == null ? null : baseline.signature();
        } else {
            lastApplied = null;
        }
        lastRejected = null;
    }

    private void watchLoop() {
        try {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                WatchService activeWatchService = ensureWatchService(System.nanoTime());
                boolean configEvent = false;
                if (activeWatchService == null) {
                    waitWithoutWatchService();
                } else {
                    configEvent = pollWatchService(activeWatchService);
                }
                long nowNanos = settleEventBurst(configEvent);
                if (!running.get()) {
                    continue;
                }
                if (shouldReadSnapshot(configEvent, nowNanos)) {
                    capturePendingSnapshot(nowNanos);
                }
                dispatchReadySnapshot(nowNanos);
            }
        } catch (Throwable failure) {
            if (running.get()) {
                logger.log(Level.SEVERE, "[Hotload] Configuration watcher terminated unexpectedly", failure);
            }
        } finally {
            running.set(false);
            closeWatchService();
        }
    }

    private WatchService ensureWatchService(long nowNanos) {
        if (!filesystemEventsEnabled) {
            return null;
        }
        WatchService current = watchService;
        if (current != null) {
            return current;
        }
        if (nowNanos < watchRetryAfterNanos || !Files.isDirectory(configDir)) {
            return null;
        }
        synchronized (watchLock) {
            if (watchService != null) {
                return watchService;
            }
            try {
                WatchService created = FileSystems.getDefault().newWatchService();
                try {
                    configDir.register(
                        created,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE
                    );
                } catch (Throwable failure) {
                    try {
                        created.close();
                    } catch (IOException closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                    throw failure;
                }
                watchService = created;
                lastWatchFailure = null;
                return created;
            } catch (IOException | RuntimeException failure) {
                watchRetryAfterNanos = nowNanos + toNanos(timing.watchRetryMs());
                logWatchFailure(failure);
                return null;
            }
        }
    }

    private boolean pollWatchService(WatchService activeWatchService) throws InterruptedException {
        boolean configEvent = false;
        try {
            WatchKey key = activeWatchService.poll(timing.pollIntervalMs(), TimeUnit.MILLISECONDS);
            while (key != null) {
                configEvent |= drainWatchKey(key);
                key = activeWatchService.poll();
            }
        } catch (ClosedWatchServiceException ignored) {
            if (running.get()) {
                invalidateWatchService(activeWatchService);
            }
        }
        return configEvent;
    }

    private boolean drainWatchKey(WatchKey key) {
        boolean configEvent = false;
        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                configEvent = true;
                continue;
            }
            Object context = event.context();
            if (context instanceof Path changed
                && changed.getFileName().equals(configFile.getFileName())) {
                configEvent = true;
            }
        }
        if (!key.reset()) {
            invalidateWatchService(watchService);
        }
        return configEvent;
    }

    private void invalidateWatchService(WatchService expected) {
        synchronized (watchLock) {
            if (watchService != expected) {
                return;
            }
            closeWatchServiceLocked();
            watchRetryAfterNanos = System.nanoTime() + toNanos(timing.watchRetryMs());
        }
    }

    private void closeWatchService() {
        synchronized (watchLock) {
            closeWatchServiceLocked();
        }
    }

    private void closeWatchServiceLocked() {
        WatchService activeWatchService = watchService;
        watchService = null;
        if (activeWatchService == null) {
            return;
        }
        try {
            activeWatchService.close();
        } catch (IOException failure) {
            logger.log(Level.WARNING, "[Hotload] Failed to close the configuration watch service", failure);
        }
    }

    private void waitWithoutWatchService() throws InterruptedException {
        Thread.sleep(timing.pollIntervalMs());
    }

    private long settleEventBurst(boolean configEvent) throws InterruptedException {
        if (configEvent) {
            Thread.sleep(Math.min(50L, timing.pollIntervalMs()));
        }
        return System.nanoTime();
    }

    private synchronized boolean shouldReadSnapshot(boolean configEvent, long nowNanos) {
        if (configEvent) {
            return true;
        }
        PendingChange candidate = pending;
        if (candidate != null
            && !candidate.stable()
            && nowNanos - candidate.firstSeenNanos() >= toNanos(timing.stabilityWindowMs())) {
            return true;
        }
        return nowNanos - lastSnapshotReadNanos >= toNanos(timing.contentReconciliationMs());
    }

    private synchronized void capturePendingSnapshot(long nowNanos) {
        FileSnapshot current = readSnapshot();
        lastSnapshotReadNanos = nowNanos;
        reconcilePending(current, nowNanos);
    }

    private synchronized void dispatchReadySnapshot(long nowNanos) {
        if (activeSnapshot != null) {
            reportApplicationTimeout(nowNanos);
            return;
        }
        PendingChange ready = pending;
        if (ready == null
            || !ready.stable()
            || nowNanos < retryAfterNanos
            || !cooldownElapsed(nowNanos)) {
            return;
        }
        beginApplication(ready.snapshot(), nowNanos);
    }

    private void reconcilePending(FileSnapshot current, long nowNanos) {
        if (current == null) {
            pending = null;
            return;
        }
        FileSignature signature = current.signature();
        if (signature.equals(lastApplied)
            || signature.equals(lastRejected)
            || (activeSnapshot != null && signature.equals(activeSnapshot.signature()))) {
            pending = null;
            return;
        }
        if (pending == null || !signature.equals(pending.snapshot().signature())) {
            pending = new PendingChange(current, nowNanos, false);
            resetDeferredRetry();
            return;
        }
        if (!pending.stable()
            && nowNanos - pending.firstSeenNanos() >= toNanos(timing.stabilityWindowMs())) {
            pending = new PendingChange(pending.snapshot(), pending.firstSeenNanos(), true);
        }
    }

    private boolean cooldownElapsed(long nowNanos) {
        return lastCompletionNanos == 0L
            || nowNanos - lastCompletionNanos >= toNanos(timing.hotloadCooldownMs());
    }

    private void beginApplication(FileSnapshot snapshot, long nowNanos) {
        WormholesSettings reloaded;
        try {
            reloaded = WormholesSettings.loadSnapshot(snapshot.content());
        } catch (RuntimeException failure) {
            lastRejected = snapshot.signature();
            pending = null;
            resetDeferredRetry();
            logger.log(
                Level.WARNING,
                "[Hotload] Failed to parse " + configFile + "; keeping the last-known-good settings",
                failure
            );
            return;
        }

        long attempt = ++reloadAttempt;
        activeAttempt = attempt;
        activeSnapshot = snapshot;
        applicationStartedNanos = nowNanos;
        applicationTimeoutLogged = false;
        pending = null;
        boolean accepted;
        try {
            accepted = reloadCallback.schedule(
                reloaded,
                (applied, failure) -> completeApplication(attempt, applied, failure)
            );
        } catch (RuntimeException failure) {
            deferActiveAttempt(attempt, failure, System.nanoTime());
            return;
        } catch (Error failure) {
            deferActiveAttempt(attempt, failure, System.nanoTime());
            throw failure;
        }
        if (!accepted) {
            deferActiveAttempt(attempt, null, System.nanoTime());
        }
    }

    private synchronized void completeApplication(long attempt, boolean applied, Throwable failure) {
        if (activeAttempt != attempt || activeSnapshot == null) {
            return;
        }
        FileSnapshot completed = activeSnapshot;
        activeAttempt = 0L;
        activeSnapshot = null;
        applicationStartedNanos = 0L;
        applicationTimeoutLogged = false;
        long completedAtNanos = System.nanoTime();
        lastCompletionNanos = completedAtNanos;
        if (!running.get()) {
            return;
        }
        if (!applied) {
            queueRetry(completed, completedAtNanos);
            registerDeferredRetry(failure, completedAtNanos);
            return;
        }
        lastApplied = completed.signature();
        if (completed.signature().equals(lastRejected)) {
            lastRejected = null;
        }
        resetDeferredRetry();
        logger.info("[Hotload] Configuration reloaded from " + configFile.getFileName());
    }

    private synchronized void deferActiveAttempt(long attempt, Throwable failure, long nowNanos) {
        if (activeAttempt != attempt || activeSnapshot == null) {
            return;
        }
        FileSnapshot deferred = activeSnapshot;
        activeAttempt = 0L;
        activeSnapshot = null;
        applicationStartedNanos = 0L;
        applicationTimeoutLogged = false;
        if (!running.get()) {
            return;
        }
        queueRetry(deferred, nowNanos);
        registerDeferredRetry(failure, nowNanos);
    }

    private void queueRetry(FileSnapshot deferred, long nowNanos) {
        if (pending == null) {
            pending = new PendingChange(
                deferred,
                nowNanos - toNanos(timing.stabilityWindowMs()),
                true
            );
        }
    }

    private void registerDeferredRetry(Throwable failure, long nowNanos) {
        deferredRetryCount++;
        int shift = Math.min(5, deferredRetryCount - 1);
        long delayMs = Math.min(timing.retryMaxMs(), timing.retryBaseMs() << shift);
        retryAfterNanos = nowNanos + toNanos(delayMs);
        if (failure != null) {
            logger.log(
                Level.WARNING,
                "[Hotload] Configuration application failed; retrying after " + delayMs + "ms",
                failure
            );
        } else if (deferredRetryCount == 1 || deferredRetryCount % 5 == 0) {
            logger.warning("[Hotload] The server refused the configuration application task; retrying after "
                + delayMs + "ms");
        }
    }

    private void resetDeferredRetry() {
        deferredRetryCount = 0;
        retryAfterNanos = 0L;
    }

    private void reportApplicationTimeout(long nowNanos) {
        if (applicationTimeoutLogged
            || nowNanos - applicationStartedNanos < toNanos(timing.applicationTimeoutMs())) {
            return;
        }
        applicationTimeoutLogged = true;
        logger.log(
            Level.WARNING,
            "[Hotload] The scheduled configuration application has not completed after "
                + timing.applicationTimeoutMs() + "ms; no later edit will apply until it completes",
            new IllegalStateException("Configuration application completion callback is still pending")
        );
    }

    private FileSnapshot readSnapshot() {
        snapshotReadAttempts++;
        try {
            if (!Files.isRegularFile(configFile)) {
                lastReadFailure = null;
                return null;
            }
            BasicFileAttributes before = Files.readAttributes(configFile, BasicFileAttributes.class);
            if (!before.isRegularFile() || before.size() <= 0L) {
                return null;
            }
            if (before.size() > MAX_CONFIG_BYTES) {
                throw new IOException("Configuration exceeds the " + MAX_CONFIG_BYTES + " byte hotload limit: "
                    + before.size() + " bytes");
            }
            byte[] content;
            try (InputStream input = Files.newInputStream(configFile)) {
                content = input.readNBytes(MAX_CONFIG_BYTES + 1);
            }
            if (content.length > MAX_CONFIG_BYTES) {
                throw new IOException("Configuration grew beyond the " + MAX_CONFIG_BYTES
                    + " byte hotload limit while being read");
            }
            BasicFileAttributes after = Files.readAttributes(configFile, BasicFileAttributes.class);
            if (!sameFileState(before, after) || content.length != after.size()) {
                return null;
            }
            lastReadFailure = null;
            return new FileSnapshot(new FileSignature(content.length, digest(content)), content);
        } catch (IOException | SecurityException failure) {
            logReadFailure(failure);
            return null;
        }
    }

    private boolean sameFileState(BasicFileAttributes before, BasicFileAttributes after) {
        return before.size() == after.size()
            && before.lastModifiedTime().equals(after.lastModifiedTime())
            && Objects.equals(before.fileKey(), after.fileKey());
    }

    private String digest(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is not available", failure);
        }
    }

    private void logReadFailure(Throwable failure) {
        String failureKey = failure.getClass().getName() + ":" + failure.getMessage();
        if (failureKey.equals(lastReadFailure)) {
            return;
        }
        lastReadFailure = failureKey;
        logger.log(Level.WARNING, "[Hotload] Could not capture a stable snapshot of " + configFile, failure);
    }

    private void logWatchFailure(Throwable failure) {
        String failureKey = failure.getClass().getName() + ":" + failure.getMessage();
        if (failureKey.equals(lastWatchFailure)) {
            return;
        }
        lastWatchFailure = failureKey;
        logger.log(Level.WARNING, "[Hotload] Could not register the filesystem watcher for " + configDir
            + "; content reconciliation remains active", failure);
    }

    private static long toNanos(long milliseconds) {
        return TimeUnit.MILLISECONDS.toNanos(milliseconds);
    }

    synchronized long snapshotReadAttempts() {
        return snapshotReadAttempts;
    }

    @FunctionalInterface
    public interface ReloadCallback {
        boolean schedule(WormholesSettings settings, ReloadCompletion completion);
    }

    @FunctionalInterface
    public interface ReloadCompletion {
        void complete(boolean applied, Throwable failure);
    }

    record Options(
        Path dataFolder,
        Logger logger,
        ReloadCallback reloadCallback,
        Timing timing,
        boolean filesystemEventsEnabled
    ) {
    }

    record Timing(
        long pollIntervalMs,
        long stabilityWindowMs,
        long hotloadCooldownMs,
        long stopJoinTimeoutMs,
        long retryBaseMs,
        long retryMaxMs,
        long applicationTimeoutMs,
        long watchRetryMs,
        long contentReconciliationMs
    ) {
        Timing {
            if (pollIntervalMs <= 0L
                || stabilityWindowMs < 0L
                || hotloadCooldownMs < 0L
                || stopJoinTimeoutMs <= 0L
                || retryBaseMs <= 0L
                || retryMaxMs < retryBaseMs
                || applicationTimeoutMs <= 0L
                || watchRetryMs <= 0L
                || contentReconciliationMs <= 0L) {
                throw new IllegalArgumentException("Hotload timing values are invalid");
            }
        }

        private static Timing production() {
            return new Timing(
                POLL_INTERVAL_MS,
                STABILITY_WINDOW_MS,
                HOTLOAD_COOLDOWN_MS,
                STOP_JOIN_TIMEOUT_MS,
                RETRY_BASE_MS,
                RETRY_MAX_MS,
                APPLICATION_TIMEOUT_MS,
                WATCH_RETRY_MS,
                CONTENT_RECONCILIATION_MS
            );
        }
    }

    private record FileSignature(long size, String digest) {
    }

    private static final class FileSnapshot {
        private final FileSignature signature;
        private final byte[] content;

        private FileSnapshot(FileSignature signature, byte[] content) {
            this.signature = signature;
            this.content = content.clone();
        }

        private FileSignature signature() {
            return signature;
        }

        private byte[] content() {
            return content.clone();
        }
    }

    private record PendingChange(FileSnapshot snapshot, long firstSeenNanos, boolean stable) {
    }
}
