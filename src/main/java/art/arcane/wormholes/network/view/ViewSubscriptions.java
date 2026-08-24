package art.arcane.wormholes.network.view;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.network.replication.ChunkReplicationManager;
import art.arcane.wormholes.network.replication.ReplicationStreamKey;
import art.arcane.wormholes.portal.ILocalPortal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

final class ViewSubscriptions {
    private static final long INITIAL_RETRY_DELAY_SECONDS = 1L;

    private final ViewSessionRegistry registry;
    private final ViewTicketRegistry tickets;
    private final ViewTimeDelivery timeDelivery;
    private final ViewBulkPipeline bulkPipeline;
    private final Runnable startTask;
    private final InitialBulkWorkPump initialBulkPump;
    private final Map<InitialSubscriptionProgress, InitialBulkWorkPump.WorkHandle> initialBulkWorkHandles;

    ViewSubscriptions(ViewSessionRegistry registry, ViewTicketRegistry tickets, ViewTimeDelivery timeDelivery,
                      ViewBulkPipeline bulkPipeline, Runnable startTask) {
        this(registry, tickets, timeDelivery, bulkPipeline, startTask,
            (task, delayTicks) -> FoliaScheduler.runAsync(Wormholes.instance, task, delayTicks));
    }

    ViewSubscriptions(ViewSessionRegistry registry, ViewTicketRegistry tickets, ViewTimeDelivery timeDelivery,
                      ViewBulkPipeline bulkPipeline, Runnable startTask,
                      InitialBulkWorkPump.Scheduler asyncScheduler) {
        this.registry = registry;
        this.tickets = tickets;
        this.timeDelivery = timeDelivery;
        this.bulkPipeline = bulkPipeline;
        this.startTask = startTask;
        this.initialBulkPump = new InitialBulkWorkPump(
            asyncScheduler,
            ViewServer.MAX_BULK_SNAPSHOTS_PER_TICK,
            ViewServer.DIRTY_DRAIN_INTERVAL_TICKS,
            ViewSubscriptions::reportInitialBulkPumpFailure
        );
        this.initialBulkWorkHandles = new ConcurrentHashMap<InitialSubscriptionProgress, InitialBulkWorkPump.WorkHandle>();
    }

    void onSubscribe(String peerName, UUID portalId) {
        if (!registry.isActive()) {
            return;
        }
        ILocalPortal portal = Wormholes.portalManager == null ? null : Wormholes.portalManager.getLocalPortal(portalId);
        if (portal == null || portal.getStructure() == null || portal.getStructure().getWorld() == null) {
            return;
        }
        ViewSession session = registry.openSession(portal);
        tickets.retainSessionTickets(session);
        boolean peerAdded = session.peers.add(peerName);
        if (!peerAdded && session.initialSubscriptionProgress.containsKey(peerName)) {
            startTask.run();
            return;
        }
        if (!peerAdded) {
            bulkPipeline.sendBulkCompleteWithRetry(session, peerName);
            startTask.run();
            return;
        }
        startInitialSubscription(session, peerName);
        startTask.run();
    }

    private void startInitialSubscription(ViewSession session, String peerName) {
        int totalColumns = session.columns.size();
        if (totalColumns == 0) {
            bulkPipeline.sendBulkCompleteWithRetry(session, peerName);
            return;
        }
        InitialSubscriptionProgress progress = new InitialSubscriptionProgress(
            totalColumns,
            completed -> completeInitialSubscription(session, peerName, completed),
            failed -> failInitialSubscription(session, peerName, failed)
        );
        if (session.initialSubscriptionProgress.putIfAbsent(peerName, progress) != null) {
            return;
        }
        if (!isCurrent(session, peerName, progress)) {
            cancelInitialSubscription(session, peerName);
            return;
        }
        ChunkReplicationManager replication = registry.replication();
        session.sentProfiles.clear();
        session.sendStates.remove(peerName);
        session.lastSentPresentIds.remove(peerName);
        session.lastPeerSideband.remove(peerName);
        int initialSkyDarken = art.arcane.wormholes.render.view.ProjectionWorldView.computeSkyDarken(session.world.getTime());
        session.timeDeliveryStates.put(peerName, new ViewServer.TimeDeliveryState(initialSkyDarken));
        timeDelivery.queue(session, peerName, initialSkyDarken);
        for (long[] column : session.columns) {
            long chunkKey = ViewSlice.columnKey((int) column[0], (int) column[1]);
            replication.subscribe(peerName, session.subscriptionId, session.world, session.streamFor(chunkKey));
        }
        if (!isCurrent(session, peerName, progress)) {
            replication.unsubscribeAll(peerName, session.subscriptionId, session.streamKeys);
            cancelInitialSubscription(session, peerName);
            return;
        }
        InitialBulkWorkPump.WorkHandle handle = initialBulkPump.enqueue(
            new InitialBulkWork(session, peerName, progress),
            created -> initialBulkWorkHandles.put(progress, created));
        if (handle == null) {
            progress.fail();
            return;
        }
        if (!isCurrent(session, peerName, progress)) {
            cancelInitialWork(progress);
        }
    }

    void onUnsubscribe(String peerName, UUID portalId) {
        ViewSession session = registry.get(portalId);
        if (session == null) {
            return;
        }
        cancelInitialSubscription(session, peerName);
        ChunkReplicationManager replication = registry.replication();
        replication.unsubscribeAll(peerName, session.subscriptionId, session.streamKeys);
        session.peers.remove(peerName);
        session.sendStates.remove(peerName);
        session.lastSentPresentIds.remove(peerName);
        session.lastPeerSideband.remove(peerName);
        session.timeDeliveryStates.remove(peerName);
        if (session.peers.isEmpty()) {
            registry.remove(portalId);
            tickets.releaseSessionTickets(session);
        }
    }

    void onPeerDisconnected(String peerName) {
        for (ViewSession session : registry.sessions()) {
            onUnsubscribe(peerName, session.portalId);
        }
    }

    void refreshPortal(ILocalPortal portal) {
        if (portal == null || portal.getId() == null) {
            return;
        }
        ViewSession removed = registry.get(portal.getId());
        List<String> peers = new ArrayList<>();
        if (removed != null) {
            peers.addAll(removed.peers);
            cancelInitialSubscriptions(removed);
            registry.unsubscribeSessionReplication(removed);
            registry.remove(portal.getId(), removed);
            tickets.releaseSessionTickets(removed);
        }
        tickets.releaseGatewayTicket(portal.getId());
        if (ViewTicketRegistry.isTicketedGateway(portal)) {
            tickets.retainGatewayTickets(portal);
        }
        for (String peer : peers) {
            onSubscribe(peer, portal.getId());
        }
    }

    void shutdown() {
        registry.deactivate();
        initialBulkPump.close();
        registry.replication().setBulkRetryListener(null);
        for (ViewSession session : registry.sessions()) {
            cancelInitialSubscriptions(session);
            registry.unsubscribeSessionReplication(session);
            tickets.releaseSessionTickets(session);
        }
        registry.clear();
        initialBulkWorkHandles.clear();
        bulkPipeline.clear();
        tickets.releaseAllGatewayTickets();
    }

    private void completeInitialSubscription(ViewSession session, String peerName,
                                             InitialSubscriptionProgress progress) {
        if (!session.initialSubscriptionProgress.remove(peerName, progress)) {
            return;
        }
        cancelInitialWork(progress);
        if (!registry.isSessionPeerActive(session, peerName)) {
            return;
        }
        bulkPipeline.sendBulkCompleteWithRetry(session, peerName);
    }

    private void failInitialSubscription(ViewSession session, String peerName,
                                         InitialSubscriptionProgress progress) {
        if (!session.initialSubscriptionProgress.remove(peerName, progress)) {
            return;
        }
        cancelInitialWork(progress);
        ChunkReplicationManager replication = registry.replication();
        for (ReplicationStreamKey stream : session.streamKeys) {
            replication.requestResync(peerName, stream);
        }
        if (!registry.isSessionPeerActive(session, peerName)) {
            return;
        }
        CompletableFuture.delayedExecutor(INITIAL_RETRY_DELAY_SECONDS, TimeUnit.SECONDS).execute(
            () -> startInitialSubscription(session, peerName));
    }

    private boolean isCurrent(ViewSession session, String peerName, InitialSubscriptionProgress progress) {
        return registry.isSessionPeerActive(session, peerName)
            && session.initialSubscriptionProgress.get(peerName) == progress;
    }

    private void cancelInitialSubscription(ViewSession session, String peerName) {
        InitialSubscriptionProgress progress = session.initialSubscriptionProgress.remove(peerName);
        if (progress != null) {
            cancelInitialWork(progress);
            progress.cancel();
        }
    }

    private void cancelInitialSubscriptions(ViewSession session) {
        List<InitialSubscriptionProgress> progresses = new ArrayList<InitialSubscriptionProgress>(
            session.initialSubscriptionProgress.values());
        session.initialSubscriptionProgress.clear();
        for (InitialSubscriptionProgress progress : progresses) {
            cancelInitialWork(progress);
            progress.cancel();
        }
    }

    private void cancelInitialWork(InitialSubscriptionProgress progress) {
        InitialBulkWorkPump.WorkHandle handle = initialBulkWorkHandles.remove(progress);
        initialBulkPump.cancel(handle);
    }

    private static void reportInitialBulkPumpFailure(Throwable failure) {
        if (Wormholes.instance == null) {
            failure.printStackTrace();
            return;
        }
        Wormholes.instance.getLogger().log(Level.SEVERE,
            "Could not retire rejected initial gateway view work", failure);
    }

    private final class InitialBulkWork implements InitialBulkWorkPump.Work {
        private final ViewSession session;
        private final String peerName;
        private final InitialSubscriptionProgress progress;

        private int nextColumn;

        private InitialBulkWork(ViewSession session, String peerName, InitialSubscriptionProgress progress) {
            this.session = session;
            this.peerName = peerName;
            this.progress = progress;
        }

        @Override
        public boolean runNext() {
            if (!isCurrent(session, peerName, progress) || nextColumn >= session.columns.size()) {
                return false;
            }
            long[] column = session.columns.get(nextColumn++);
            int chunkX = (int) column[0];
            int chunkZ = (int) column[1];
            bulkPipeline.sendInitialBulkWithRetry(session, peerName, chunkX, chunkZ)
                .whenComplete(progress::complete);
            return nextColumn < session.columns.size() && isCurrent(session, peerName, progress);
        }

        @Override
        public void reject(Throwable failure) {
            if (failure != null && Wormholes.instance != null) {
                Wormholes.instance.getLogger().log(Level.WARNING,
                    "Could not dispatch an initial gateway view bulk for " + peerName, failure);
            }
            progress.fail();
        }
    }
}
