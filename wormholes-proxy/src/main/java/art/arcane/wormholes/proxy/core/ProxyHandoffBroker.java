package art.arcane.wormholes.proxy.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Handoffs the proxy is moving right now: reserved on HANDOFF, released once the connect result is known. */
public final class ProxyHandoffBroker {
    public record Reservation(UUID transferId, UUID playerId, String sourceServer, String targetServer, long reservedAtMillis) {
    }

    private final Map<UUID, Reservation> reservations = new ConcurrentHashMap<>();

    public Reservation reserve(UUID transferId, UUID playerId, String sourceServer, String targetServer, long nowMillis) {
        Reservation reservation = new Reservation(transferId, playerId, sourceServer, targetServer, nowMillis);
        reservations.put(transferId, reservation);
        return reservation;
    }

    /** Releases and returns the reservation, or null when it was unknown or already completed. */
    public Reservation complete(UUID transferId) {
        return transferId == null ? null : reservations.remove(transferId);
    }

    public int pending() {
        return reservations.size();
    }

    public List<Reservation> snapshot() {
        return new ArrayList<>(reservations.values());
    }

    public List<Reservation> expire(long nowMillis, long ttlMillis) {
        List<Reservation> expired = new ArrayList<>();
        for (Reservation reservation : reservations.values()) {
            if (nowMillis - reservation.reservedAtMillis() > ttlMillis && reservations.remove(reservation.transferId(), reservation)) {
                expired.add(reservation);
            }
        }
        return expired;
    }
}
