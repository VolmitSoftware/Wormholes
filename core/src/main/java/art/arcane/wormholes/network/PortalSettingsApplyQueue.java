package art.arcane.wormholes.network;

import art.arcane.wormholes.portal.IPortal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PortalSettingsApplyQueue<P extends IPortal> {
    static final String FAILURE_APPLY = "PORTAL_SETTINGS_APPLY_FAILED";
    static final String FAILURE_REFRESH = "PORTAL_SETTINGS_MENU_REFRESH_FAILED";
    static final String FAILURE_SCHEDULE = "PORTAL_SETTINGS_APPLY_SCHEDULE_REJECTED";
    static final String FAILURE_RETRY = "PORTAL_SETTINGS_APPLY_RETRY_REJECTED";
    static final long RETRY_BASE_TICKS = 20L;
    static final long RETRY_MAX_TICKS = 600L;

    @FunctionalInterface
    public interface RegionDispatcher<P> {
        boolean dispatch(P portal, Runnable task, Runnable retired);
    }

    @FunctionalInterface
    public interface FailureReporter<P> {
        void report(String reason, P portal, Throwable failure);
    }

    @FunctionalInterface
    public interface RetryDispatcher {
        void dispatch(Runnable task, long delayTicks);
    }

    private final RegionDispatcher<P> regionDispatcher;
    private final RetryDispatcher retryDispatcher;
    private final FailureReporter<P> failureReporter;
    private final PortalSyncAccess<P> access;
    private final Map<P, PendingUpdate> pendingUpdates;
    private final AtomicBoolean closed;

    public PortalSettingsApplyQueue(PortalSyncAccess<P> access, Dispatch<P> dispatch) {
        this.access = access;
        this.regionDispatcher = dispatch.regionDispatcher();
        this.retryDispatcher = dispatch.retryDispatcher();
        this.failureReporter = dispatch.failureReporter();
        pendingUpdates = new ConcurrentHashMap<>();
        closed = new AtomicBoolean(false);
    }

    public void enqueue(P portal, Map<String, String> settings) {
        while (true) {
            if (closed.get()) {
                return;
            }
            PendingUpdate pending = pendingUpdates.computeIfAbsent(portal,
                ignored -> new PendingUpdate(portal.getId().hashCode()));
            PendingUpdate.OfferResult result = pending.offer(settings);
            if (result == PendingUpdate.OfferResult.RETIRED) {
                pendingUpdates.remove(portal, pending);
                continue;
            }
            if (result == PendingUpdate.OfferResult.DISPATCH) {
                dispatch(portal, pending);
            }
            return;
        }
    }

    public int trackedPortalCount() {
        return pendingUpdates.size();
    }

    public void shutdown() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (Map.Entry<P, PendingUpdate> entry : pendingUpdates.entrySet()) {
            entry.getValue().close();
            pendingUpdates.remove(entry.getKey(), entry.getValue());
        }
    }

    private void dispatch(P portal, PendingUpdate pending) {
        if (closed.get()) {
            pending.close();
            pendingUpdates.remove(portal, pending);
            return;
        }
        boolean scheduled;
        Throwable dispatchFailure = null;
        try {
            scheduled = regionDispatcher.dispatch(
                portal,
                () -> drain(portal, pending),
                () -> retired(portal, pending));
        } catch (Throwable failure) {
            scheduled = false;
            dispatchFailure = failure;
        }
        if (scheduled) {
            pending.accepted();
            return;
        }
        if (pending.rejected()) {
            report(FAILURE_SCHEDULE, portal, dispatchFailure);
        }
        queueRetry(portal, pending);
    }

    private void retired(P portal, PendingUpdate pending) {
        if (closed.get()) {
            pending.close();
            pendingUpdates.remove(portal, pending);
            return;
        }
        if (pending.rejected()) {
            report(FAILURE_SCHEDULE, portal, null);
        }
        queueRetry(portal, pending);
    }

    private void queueRetry(P portal, PendingUpdate pending) {
        long delayTicks = pending.queueRetry();
        if (delayTicks < 0L) {
            return;
        }
        try {
            retryDispatcher.dispatch(() -> {
                if (closed.get()) {
                    pending.close();
                    pendingUpdates.remove(portal, pending);
                    return;
                }
                pending.retryReady();
                if (pending.claim()) {
                    dispatch(portal, pending);
                }
            }, delayTicks);
        } catch (Throwable failure) {
            pending.retryReady();
            report(FAILURE_RETRY, portal, failure);
        }
    }

    private void drain(P portal, PendingUpdate pending) {
        boolean applied = false;
        while (true) {
            Map<String, String> settings = pending.take();
            if (!settings.isEmpty()) {
                applied = true;
                try {
                    PortalSyncService.applyRemote(() -> access.applySettings(portal, settings));
                } catch (Throwable failure) {
                    report(FAILURE_APPLY, portal, failure);
                }
            }
            if (pending.retireIfDrained()) {
                pendingUpdates.remove(portal, pending);
                break;
            }
        }
        if (!applied) {
            return;
        }
        try {
            access.refreshMenus(portal);
        } catch (Throwable failure) {
            report(FAILURE_REFRESH, portal, failure);
        }
    }

    private void report(String reason, P portal, Throwable failure) {
        failureReporter.report(reason, portal, failure);
    }

    public record Dispatch<P>(RegionDispatcher<P> regionDispatcher, RetryDispatcher retryDispatcher, FailureReporter<P> failureReporter) {
    }

    private static final class PendingUpdate {
        private final Map<String, String> settings = new LinkedHashMap<String, String>();
        private boolean scheduled;
        private boolean retryQueued;
        private boolean rejectionReported;
        private boolean retired;
        private final int retrySalt;
        private int retryAttempt;

        private PendingUpdate(int retrySalt) {
            this.retrySalt = retrySalt;
        }

        synchronized OfferResult offer(Map<String, String> update) {
            if (retired) {
                return OfferResult.RETIRED;
            }
            settings.putAll(update);
            if (scheduled || retryQueued) {
                return OfferResult.QUEUED;
            }
            scheduled = true;
            return OfferResult.DISPATCH;
        }

        synchronized boolean claim() {
            if (scheduled || settings.isEmpty()) {
                return false;
            }
            scheduled = true;
            return true;
        }

        synchronized Map<String, String> take() {
            if (settings.isEmpty()) {
                return Map.of();
            }
            Map<String, String> batch = new LinkedHashMap<String, String>(settings);
            settings.clear();
            return batch;
        }

        synchronized boolean retireIfDrained() {
            if (!settings.isEmpty()) {
                return false;
            }
            scheduled = false;
            retired = true;
            return true;
        }

        synchronized void accepted() {
            rejectionReported = false;
            retryAttempt = 0;
        }

        synchronized boolean rejected() {
            scheduled = false;
            if (rejectionReported) {
                return false;
            }
            rejectionReported = true;
            return true;
        }

        synchronized long queueRetry() {
            if (retryQueued || settings.isEmpty()) {
                return -1L;
            }
            retryQueued = true;
            long ceiling = retryCeiling(retryAttempt);
            long floor = Math.max(RETRY_BASE_TICKS, ceiling / 2L);
            long window = ceiling - floor + 1L;
            int mixedSalt = retrySalt ^ (retryAttempt * 0x9E3779B9);
            retryAttempt = Math.min(retryAttempt + 1, 30);
            return floor + Math.floorMod((long) mixedSalt, window);
        }

        synchronized void retryReady() {
            retryQueued = false;
        }

        synchronized void close() {
            settings.clear();
            scheduled = false;
            retryQueued = false;
            retired = true;
        }

        private static long retryCeiling(int attempt) {
            int shift = Math.min(attempt + 1, 30);
            long scaled = RETRY_BASE_TICKS << shift;
            return Math.min(RETRY_MAX_TICKS, scaled);
        }

        private enum OfferResult {
            DISPATCH,
            QUEUED,
            RETIRED
        }
    }
}
