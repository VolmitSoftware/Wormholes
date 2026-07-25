package art.arcane.wormholes.network.view;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.network.replication.ChunkReplicationManager;
import art.arcane.wormholes.portal.ILocalPortal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

final class ViewSubscriptions {
    private final ViewSessionRegistry registry;
    private final ViewTicketRegistry tickets;
    private final ViewTimeDelivery timeDelivery;
    private final ViewBulkPipeline bulkPipeline;
    private final Runnable startTask;

    ViewSubscriptions(ViewSessionRegistry registry, ViewTicketRegistry tickets, ViewTimeDelivery timeDelivery,
                      ViewBulkPipeline bulkPipeline, Runnable startTask) {
        this.registry = registry;
        this.tickets = tickets;
        this.timeDelivery = timeDelivery;
        this.bulkPipeline = bulkPipeline;
        this.startTask = startTask;
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
        session.peers.add(peerName);
        session.sentProfiles.clear();
        session.sendStates.remove(peerName);
        int initialSkyDarken = art.arcane.wormholes.render.view.ProjectionWorldView.computeSkyDarken(session.world.getTime());
        session.timeDeliveryStates.put(peerName, new ViewServer.TimeDeliveryState(initialSkyDarken));
        timeDelivery.queue(session, peerName, initialSkyDarken);
        ChunkReplicationManager replication = registry.replication();
        for (long[] column : session.columns) {
            replication.subscribe(peerName, session.subscriptionId, session.world, ViewSlice.columnKey((int) column[0], (int) column[1]));
        }
        int totalColumns = session.columns.size();
        if (totalColumns == 0) {
            bulkPipeline.sendBulkCompleteWithRetry(session, peerName);
            startTask.run();
            return;
        }
        AtomicInteger remainingBulks = new AtomicInteger(totalColumns);
        int columnIndex = 0;
        for (long[] column : session.columns) {
            int chunkX = (int) column[0];
            int chunkZ = (int) column[1];
            long delayTicks = (long) (columnIndex / ViewServer.MAX_BULK_SNAPSHOTS_PER_TICK) * ViewServer.DIRTY_DRAIN_INTERVAL_TICKS;
            columnIndex++;
            FoliaScheduler.runAsync(Wormholes.instance, () -> {
                if (!session.peers.contains(peerName)) {
                    remainingBulks.decrementAndGet();
                    return;
                }
                bulkPipeline.sendInitialBulkWithRetry(session, peerName, chunkX, chunkZ).whenComplete((accepted, error) -> {
                    if (Boolean.TRUE.equals(accepted) && remainingBulks.decrementAndGet() == 0 && session.peers.contains(peerName)) {
                        bulkPipeline.sendBulkCompleteWithRetry(session, peerName);
                    }
                });
            }, delayTicks);
        }
        startTask.run();
    }

    void onUnsubscribe(String peerName, UUID portalId) {
        ViewSession session = registry.get(portalId);
        if (session == null) {
            return;
        }
        ChunkReplicationManager replication = registry.replication();
        replication.unsubscribeAll(peerName, session.subscriptionId, session.chunkKeys);
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
        registry.replication().setBulkRetryListener(null);
        for (ViewSession session : registry.sessions()) {
            registry.unsubscribeSessionReplication(session);
            tickets.releaseSessionTickets(session);
        }
        registry.clear();
        bulkPipeline.clear();
        tickets.releaseAllGatewayTickets();
    }
}
