package art.arcane.wormholes.network;

import art.arcane.wormholes.Wormholes;

import art.arcane.wormholes.portal.ILocalPortal;

import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.util.Cuboid;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalSyncServiceTest {
    @Test
    void inboundBurstsWaitForThePortalRegionAndCoalesceBeforeRefreshingMenus() {
        UUID senderPortalId = UUID.randomUUID();
        RecordingLocalPortal portal = localPortal();
        assertTrue(portal.linkRemote("alpha", senderPortalId));
        int initialDepth = portal.getNetworkViewDepth();
        Queue<Runnable> regionTasks = new ArrayDeque<Runnable>();
        Queue<ScheduledRetry> retries = new ArrayDeque<ScheduledRetry>();
        List<String> failures = new ArrayList<String>();
        PortalSettingsApplyQueue<ILocalPortal> queue = new PortalSettingsApplyQueue<>(BukkitPortalSyncAccess.INSTANCE, new PortalSettingsApplyQueue.Dispatch<>((target, task, retired) -> regionTasks.offer(task), (task, delayTicks) -> retries.offer(new ScheduledRetry(task, delayTicks)), (reason, target, failure) -> failures.add(reason)));
        PortalSyncService<ILocalPortal> sync = new PortalSyncService<>(null, new PortalSyncService.Options<>(() -> List.of(portal), Runnable::run, () -> Wormholes.remotePortalRegistry, BukkitPortalSyncAccess.INSTANCE, queue));

        sync.applySettingsUpdate("alpha", update(senderPortalId, Map.of(
            PortalSettingsCodec.KEY_VIEW_DEPTH, "24",
            PortalSettingsCodec.KEY_SURFACE_SKIN, "minecraft:glass"
        )));
        sync.applySettingsUpdate("alpha", update(senderPortalId, Map.of(
            PortalSettingsCodec.KEY_VIEW_DEPTH, "48",
            PortalSettingsCodec.KEY_BLACKOUT_BACKGROUND, "false"
        )));

        assertEquals(initialDepth, portal.getNetworkViewDepth());
        assertEquals("", portal.getSurfaceSkin());
        assertEquals(0, portal.menuRefreshes.get());
        assertEquals(1, regionTasks.size());
        assertTrue(retries.isEmpty());

        regionTasks.remove().run();

        assertEquals(48, portal.getNetworkViewDepth());
        assertEquals("minecraft:glass", portal.getSurfaceSkin());
        assertFalse(portal.isBlackoutBackground());
        assertEquals(1, portal.menuRefreshes.get());
        assertEquals(0, queue.trackedPortalCount());
        assertTrue(failures.isEmpty());
    }

    @Test
    void rejectedRegionSubmissionReportsOnceAndRetainsTheUpdateForRetry() {
        UUID senderPortalId = UUID.randomUUID();
        RecordingLocalPortal portal = localPortal();
        assertTrue(portal.linkRemote("alpha", senderPortalId));
        Queue<Runnable> regionTasks = new ArrayDeque<Runnable>();
        Queue<ScheduledRetry> retries = new ArrayDeque<ScheduledRetry>();
        List<FailureRecord> failures = new ArrayList<FailureRecord>();
        AtomicInteger attempts = new AtomicInteger();
        PortalSettingsApplyQueue<ILocalPortal> queue = new PortalSettingsApplyQueue<>(BukkitPortalSyncAccess.INSTANCE, new PortalSettingsApplyQueue.Dispatch<>((target, task, retired) -> attempts.incrementAndGet() > 1 && regionTasks.offer(task), (task, delayTicks) -> retries.offer(new ScheduledRetry(task, delayTicks)), (reason, target, failure) -> failures.add(new FailureRecord(reason, target, failure))));
        PortalSyncService<ILocalPortal> sync = new PortalSyncService<>(null, new PortalSyncService.Options<>(() -> List.of(portal), Runnable::run, () -> Wormholes.remotePortalRegistry, BukkitPortalSyncAccess.INSTANCE, queue));

        sync.applySettingsUpdate("alpha", update(senderPortalId,
            Map.of(PortalSettingsCodec.KEY_VIEW_DEPTH, "72")));

        assertEquals(1, attempts.get());
        assertEquals(1, retries.size());
        assertEquals(1, failures.size());
        assertEquals(PortalSettingsApplyQueue.FAILURE_SCHEDULE, failures.getFirst().reason());
        assertSame(portal, failures.getFirst().portal());
        assertNull(failures.getFirst().failure());

        retries.remove().task().run();
        assertEquals(2, attempts.get());
        assertEquals(1, regionTasks.size());
        regionTasks.remove().run();

        assertEquals(72, portal.getNetworkViewDepth());
        assertEquals(1, portal.menuRefreshes.get());
        assertEquals(0, queue.trackedPortalCount());
        assertEquals(1, failures.size());
    }

    @Test
    void retiredAcceptedRegionTaskReleasesTheClaimAndRetriesTheLatestUpdate() {
        UUID senderPortalId = UUID.randomUUID();
        RecordingLocalPortal portal = localPortal();
        assertTrue(portal.linkRemote("alpha", senderPortalId));
        Queue<Runnable> regionTasks = new ArrayDeque<Runnable>();
        Queue<Runnable> retirements = new ArrayDeque<Runnable>();
        Queue<ScheduledRetry> retries = new ArrayDeque<ScheduledRetry>();
        List<String> failures = new ArrayList<String>();
        PortalSettingsApplyQueue<ILocalPortal> queue = new PortalSettingsApplyQueue<>(BukkitPortalSyncAccess.INSTANCE, new PortalSettingsApplyQueue.Dispatch<>((target, task, retired) -> {
                regionTasks.offer(task);
                retirements.offer(retired);
                return true;
            }, (task, delayTicks) -> retries.offer(new ScheduledRetry(task, delayTicks)), (reason, target, failure) -> failures.add(reason)));
        PortalSyncService<ILocalPortal> sync = new PortalSyncService<>(null, new PortalSyncService.Options<>(() -> List.of(portal), Runnable::run, () -> Wormholes.remotePortalRegistry, BukkitPortalSyncAccess.INSTANCE, queue));

        sync.applySettingsUpdate("alpha", update(senderPortalId,
            Map.of(PortalSettingsCodec.KEY_VIEW_DEPTH, "80")));
        sync.applySettingsUpdate("alpha", update(senderPortalId,
            Map.of(PortalSettingsCodec.KEY_VIEW_DEPTH, "96")));

        retirements.remove().run();

        assertEquals(1, retries.size());
        assertEquals(1, queue.trackedPortalCount());
        assertEquals(List.of(PortalSettingsApplyQueue.FAILURE_SCHEDULE), failures);

        retries.remove().task().run();
        assertEquals(2, regionTasks.size());
        regionTasks.remove();
        regionTasks.remove().run();

        assertEquals(96, portal.getNetworkViewDepth());
        assertEquals(1, portal.menuRefreshes.get());
        assertEquals(0, queue.trackedPortalCount());
    }

    @Test
    void shutdownDiscardsAcceptedAndQueuedRetriesWithoutRetainingThePortal() {
        RecordingLocalPortal portal = localPortal();
        Queue<Runnable> regionTasks = new ArrayDeque<Runnable>();
        Queue<Runnable> retirements = new ArrayDeque<Runnable>();
        Queue<ScheduledRetry> retries = new ArrayDeque<ScheduledRetry>();
        PortalSettingsApplyQueue<ILocalPortal> queue = new PortalSettingsApplyQueue<>(BukkitPortalSyncAccess.INSTANCE, new PortalSettingsApplyQueue.Dispatch<>((target, task, retired) -> {
                regionTasks.offer(task);
                retirements.offer(retired);
                return true;
            }, (task, delayTicks) -> retries.offer(new ScheduledRetry(task, delayTicks)), (reason, target, failure) -> { }));

        queue.enqueue(portal, Map.of(PortalSettingsCodec.KEY_VIEW_DEPTH, "88"));
        queue.shutdown();
        retirements.remove().run();
        regionTasks.remove().run();
        queue.enqueue(portal, Map.of(PortalSettingsCodec.KEY_VIEW_DEPTH, "104"));

        assertEquals(0, queue.trackedPortalCount());
        assertTrue(retries.isEmpty());
        assertEquals(64, portal.getNetworkViewDepth());
        assertEquals(0, portal.menuRefreshes.get());
    }

    @Test
    void unavailableWorldRetriesBackOffAndFreshUpdatesCannotBypassTheDelay() {
        RecordingLocalPortal portal = localPortal();
        Queue<Runnable> regionTasks = new ArrayDeque<Runnable>();
        Queue<Runnable> retirements = new ArrayDeque<Runnable>();
        Queue<ScheduledRetry> retries = new ArrayDeque<ScheduledRetry>();
        AtomicBoolean worldAvailable = new AtomicBoolean(false);
        AtomicInteger attempts = new AtomicInteger();
        PortalSettingsApplyQueue<ILocalPortal> queue = new PortalSettingsApplyQueue<>(BukkitPortalSyncAccess.INSTANCE, new PortalSettingsApplyQueue.Dispatch<>((target, task, retired) -> {
                attempts.incrementAndGet();
                if (!worldAvailable.get()) {
                    retired.run();
                    return false;
                }
                regionTasks.offer(task);
                retirements.offer(retired);
                return true;
            }, (task, delayTicks) -> retries.offer(new ScheduledRetry(task, delayTicks)), (reason, target, failure) -> { }));

        queue.enqueue(portal, Map.of(PortalSettingsCodec.KEY_VIEW_DEPTH, "72"));
        queue.enqueue(portal, Map.of(PortalSettingsCodec.KEY_VIEW_DEPTH, "88"));
        queue.enqueue(portal, Map.of(PortalSettingsCodec.KEY_VIEW_DEPTH, "96"));

        assertEquals(1, attempts.get());
        assertEquals(1, retries.size());
        assertEquals(1, queue.trackedPortalCount());

        for (int retryAttempt = 0; retryAttempt < 7; retryAttempt++) {
            ScheduledRetry retry = retries.remove();
            long ceiling = Math.min(PortalSettingsApplyQueue.RETRY_MAX_TICKS,
                PortalSettingsApplyQueue.RETRY_BASE_TICKS << Math.min(retryAttempt + 1, 30));
            long floor = Math.max(PortalSettingsApplyQueue.RETRY_BASE_TICKS, ceiling / 2L);
            assertTrue(retry.delayTicks() >= floor);
            assertTrue(retry.delayTicks() <= ceiling);
            retry.task().run();
            assertEquals(1, retries.size());
        }

        worldAvailable.set(true);
        retries.remove().task().run();
        assertEquals(1, regionTasks.size());
        assertEquals(1, retirements.size());

        worldAvailable.set(false);
        retirements.remove().run();
        ScheduledRetry resetRetry = retries.remove();
        assertTrue(resetRetry.delayTicks() >= PortalSettingsApplyQueue.RETRY_BASE_TICKS);
        assertTrue(resetRetry.delayTicks() <= PortalSettingsApplyQueue.RETRY_BASE_TICKS * 2L);

        queue.enqueue(portal, Map.of(PortalSettingsCodec.KEY_VIEW_DEPTH, "104"));
        assertEquals(9, attempts.get());
        assertTrue(retries.isEmpty());

        worldAvailable.set(true);
        resetRetry.task().run();
        regionTasks.remove();
        regionTasks.remove().run();

        assertEquals(104, portal.getNetworkViewDepth());
        assertEquals(1, portal.menuRefreshes.get());
        assertEquals(0, queue.trackedPortalCount());
    }

    @Test
    void shutdownCancelsAQueuedBackoffWithoutRedispatchingThePortal() {
        RecordingLocalPortal portal = localPortal();
        Queue<ScheduledRetry> retries = new ArrayDeque<ScheduledRetry>();
        AtomicInteger attempts = new AtomicInteger();
        PortalSettingsApplyQueue<ILocalPortal> queue = new PortalSettingsApplyQueue<>(BukkitPortalSyncAccess.INSTANCE, new PortalSettingsApplyQueue.Dispatch<>((target, task, retired) -> {
                attempts.incrementAndGet();
                retired.run();
                return false;
            }, (task, delayTicks) -> retries.offer(new ScheduledRetry(task, delayTicks)), (reason, target, failure) -> { }));

        queue.enqueue(portal, Map.of(PortalSettingsCodec.KEY_VIEW_DEPTH, "72"));
        ScheduledRetry retry = retries.remove();
        queue.shutdown();
        retry.task().run();

        assertEquals(1, attempts.get());
        assertEquals(0, queue.trackedPortalCount());
        assertEquals(64, portal.getNetworkViewDepth());
    }

    private static WireMessage.PortalSettingsUpdate update(UUID portalId, Map<String, String> settings) {
        return new WireMessage.PortalSettingsUpdate(portalId, settings);
    }

    private static RecordingLocalPortal localPortal() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put("worldKey", "minecraft:overworld");
        values.put("x1", Integer.valueOf(0));
        values.put("y1", Integer.valueOf(64));
        values.put("z1", Integer.valueOf(0));
        values.put("x2", Integer.valueOf(0));
        values.put("y2", Integer.valueOf(66));
        values.put("z2", Integer.valueOf(2));
        PortalStructure structure = new PortalStructure();
        structure.setArea(new Cuboid(values));
        return new RecordingLocalPortal(UUID.randomUUID(), structure);
    }

    private record FailureRecord(String reason, ILocalPortal portal, Throwable failure) {
    }

    private record ScheduledRetry(Runnable task, long delayTicks) {
    }

    private static final class RecordingLocalPortal extends LocalPortal {
        private final AtomicInteger menuRefreshes = new AtomicInteger();

        private RecordingLocalPortal(UUID id, PortalStructure structure) {
            super(id, PortalType.GATEWAY, structure);
        }

        @Override
        public void refreshOpenMenus() {
            menuRefreshes.incrementAndGet();
        }
    }
    @Test
    void shutdownRetiresQueuedDirectoryWorkAndRejectsFurtherAdmission() {
        Queue<Runnable> tasks = new ArrayDeque<Runnable>();
        AtomicInteger reads = new AtomicInteger();
        PortalSettingsApplyQueue<ILocalPortal> settings = new PortalSettingsApplyQueue<>(BukkitPortalSyncAccess.INSTANCE,
            new PortalSettingsApplyQueue.Dispatch<>((portal, task, retired) -> true, (task, delay) -> { },
                (reason, portal, failure) -> { }));
        PortalSyncService<ILocalPortal> sync = new PortalSyncService<>(null, new PortalSyncService.Options<>(() -> {
            reads.incrementAndGet();
            return List.of();
        }, tasks::offer, () -> null, BukkitPortalSyncAccess.INSTANCE, settings));
        sync.onPeerStateChanged("peer", true);
        assertEquals(1, tasks.size());
        sync.shutdown();
        sync.shutdown();
        tasks.remove().run();
        sync.sendDirectory("peer");
        sync.onPeerStateChanged("peer", true);
        sync.applySettingsUpdate("peer", null);
        assertTrue(sync.isClosed());
        assertEquals(0, reads.get());
        assertTrue(tasks.isEmpty());
    }
}
