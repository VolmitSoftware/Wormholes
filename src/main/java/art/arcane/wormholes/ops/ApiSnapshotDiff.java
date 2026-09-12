package art.arcane.wormholes.ops;

import art.arcane.wormholes.api.network.PeerSnapshot;
import art.arcane.wormholes.api.portal.PortalSnapshot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Turns two consecutive snapshots into the API events that should fire between them. */
public final class ApiSnapshotDiff {
    /** Portals that appeared, portals whose link changed, and portals that went away. */
    public record Change(List<PortalSnapshot> created, List<PortalSnapshot> linked,
                         List<PortalSnapshot> destroyed) {
    }

    /** Peers whose link came up, and the names of peers whose link went down or vanished. */
    public record PeerChange(List<PeerSnapshot> connected, List<String> disconnected) {
    }

    private ApiSnapshotDiff() {
    }

    public static Change portals(Map<UUID, PortalSnapshot> previous, Collection<PortalSnapshot> current) {
        List<PortalSnapshot> created = new ArrayList<>();
        List<PortalSnapshot> linked = new ArrayList<>();
        List<UUID> seen = new ArrayList<>();
        for (PortalSnapshot snapshot : current) {
            seen.add(snapshot.id());
            PortalSnapshot before = previous.get(snapshot.id());
            if (before == null) {
                created.add(snapshot);
                continue;
            }
            boolean sameDestination = Objects.equals(before.destinationId(), snapshot.destinationId())
                && Objects.equals(before.destinationServer(), snapshot.destinationServer());
            if (!sameDestination) {
                linked.add(snapshot);
            }
        }
        List<PortalSnapshot> destroyed = new ArrayList<>();
        for (Map.Entry<UUID, PortalSnapshot> before : previous.entrySet()) {
            if (!seen.contains(before.getKey())) {
                destroyed.add(before.getValue());
            }
        }
        return new Change(List.copyOf(created), List.copyOf(linked), List.copyOf(destroyed));
    }

    public static PeerChange peers(Map<String, PeerSnapshot> previous, Collection<PeerSnapshot> current) {
        List<PeerSnapshot> connected = new ArrayList<>();
        List<String> disconnected = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (PeerSnapshot peer : current) {
            seen.add(peer.name());
            PeerSnapshot before = previous.get(peer.name());
            boolean wasConnected = before != null && before.connected();
            if (peer.connected() && !wasConnected) {
                connected.add(peer);
            } else if (!peer.connected() && wasConnected) {
                disconnected.add(peer.name());
            }
        }
        for (Map.Entry<String, PeerSnapshot> before : previous.entrySet()) {
            if (!seen.contains(before.getKey()) && before.getValue().connected()) {
                disconnected.add(before.getKey());
            }
        }
        return new PeerChange(List.copyOf(connected), List.copyOf(disconnected));
    }
}
