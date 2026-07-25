package art.arcane.wormholes.network.view;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.ILocalPortal;

import org.bukkit.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class ViewTicketRegistry {
    private final Map<UUID, ViewServer.TicketLease> gatewayTickets = new ConcurrentHashMap<>();

    void syncGatewayTickets() {
        if (Wormholes.settings == null || Wormholes.portalManager == null || !Wormholes.settings.getNetwork().enabled) {
            releaseAllGatewayTickets();
            return;
        }
        Set<UUID> active = new HashSet<>();
        for (ILocalPortal portal : Wormholes.portalManager.getLocalPortals()) {
            if (!isTicketedGateway(portal)) {
                continue;
            }
            active.add(portal.getId());
            retainGatewayTickets(portal);
        }
        releaseMissingGatewayTickets(active);
    }

    void retainGatewayTickets(ILocalPortal portal) {
        World world = portal.getStructure().getWorld();
        ViewBox box = ViewServer.computeBox(portal, portal.getNetworkViewDepth());
        ViewServer.TicketLease previous;
        ViewServer.TicketLease next;
        synchronized (gatewayTickets) {
            previous = gatewayTickets.get(portal.getId());
            if (previous != null && previous.matches(world, box)) {
                previous.ensure();
                return;
            }
            next = new ViewServer.TicketLease(portal.getId(), world, box);
            gatewayTickets.put(portal.getId(), next);
        }
        if (previous != null) {
            previous.close();
        }
    }

    void releaseGatewayTicket(UUID portalId) {
        ViewServer.TicketLease removed;
        synchronized (gatewayTickets) {
            removed = gatewayTickets.remove(portalId);
        }
        if (removed != null) {
            removed.close();
        }
    }

    void releaseAllGatewayTickets() {
        List<ViewServer.TicketLease> leases;
        synchronized (gatewayTickets) {
            leases = new ArrayList<>(gatewayTickets.values());
            gatewayTickets.clear();
        }
        for (ViewServer.TicketLease lease : leases) {
            lease.close();
        }
    }

    void retainSessionTickets(ViewSession session) {
        synchronized (session) {
            if (session.ticketLease != null) {
                session.ticketLease.ensure();
                return;
            }
            session.ticketLease = new ViewServer.TicketLease(session.portalId, session.world, session.box);
        }
    }

    void releaseSessionTickets(ViewSession session) {
        ViewServer.TicketLease lease;
        synchronized (session) {
            lease = session.ticketLease;
            session.ticketLease = null;
        }
        if (lease != null) {
            lease.close();
        }
    }

    static boolean isTicketedGateway(ILocalPortal portal) {
        return portal != null
            && portal.isGateway()
            && portal.getStructure() != null
            && portal.getStructure().getWorld() != null
            && portal.getStructure().getArea() != null;
    }

    private void releaseMissingGatewayTickets(Set<UUID> active) {
        List<ViewServer.TicketLease> removed = new ArrayList<>();
        synchronized (gatewayTickets) {
            for (Map.Entry<UUID, ViewServer.TicketLease> entry : gatewayTickets.entrySet()) {
                if (active.contains(entry.getKey())) {
                    continue;
                }
                removed.add(entry.getValue());
            }
            for (ViewServer.TicketLease lease : removed) {
                gatewayTickets.remove(lease.portalId, lease);
            }
        }
        for (ViewServer.TicketLease lease : removed) {
            lease.close();
        }
    }
}
