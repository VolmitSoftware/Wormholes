package art.arcane.wormholes.network.mesh;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Travelers held at a gateway because every policy candidate was full. Each ticket re-resolves on
 * every tick (1 Hz from the caller), reports its position while it waits, and is released through
 * {@code onTimeout} when the deadline passes or the policy stops queueing. Callbacks run outside the
 * queue lock. Deadlines come from {@code queue-max-wait-sec} and stay under the 30 s in-flight limit.
 */
public final class HandoffQueue {
    public interface PositionListener {
        void onPosition(int position, long remainingMillis);
    }

    public record Ticket(UUID playerId, long enqueuedAtMillis, long deadlineMillis, Supplier<DestinationPolicyEngine.Resolution> resolver,
                         Consumer<DestinationPolicyEngine.Resolution> onResolved, Runnable onTimeout, PositionListener onPosition) {
    }

    private final Map<UUID, Ticket> tickets = new LinkedHashMap<>();

    public Ticket enqueue(UUID playerId, long nowMillis, long maxWaitMillis, Supplier<DestinationPolicyEngine.Resolution> resolver,
                          Consumer<DestinationPolicyEngine.Resolution> onResolved, Runnable onTimeout, PositionListener onPosition) {
        Ticket ticket = new Ticket(playerId, nowMillis, nowMillis + Math.max(0L, maxWaitMillis), resolver, onResolved, onTimeout, onPosition);
        synchronized (tickets) {
            tickets.remove(playerId);
            tickets.put(playerId, ticket);
        }
        return ticket;
    }

    public boolean remove(Ticket ticket) {
        synchronized (tickets) {
            if (ticket == null || tickets.get(ticket.playerId()) != ticket) {
                return false;
            }
            tickets.remove(ticket.playerId());
            return true;
        }
    }

    public Ticket ticket(UUID playerId) {
        synchronized (tickets) {
            return tickets.get(playerId);
        }
    }

    /** 1-based position among waiting tickets, 0 when not queued. */
    public int position(UUID playerId) {
        synchronized (tickets) {
            int position = 0;
            for (UUID queued : tickets.keySet()) {
                position++;
                if (queued.equals(playerId)) {
                    return position;
                }
            }
            return 0;
        }
    }

    public int size() {
        synchronized (tickets) {
            return tickets.size();
        }
    }

    /** Advances every ticket. */
    public void tick(long nowMillis) {
        List<Ticket> snapshot;
        synchronized (tickets) {
            snapshot = new ArrayList<>(tickets.values());
        }
        for (Ticket ticket : snapshot) {
            tick(ticket, nowMillis);
        }
    }

    /** Advances one ticket: timeout, chosen, released, or a position report. */
    public void tick(Ticket ticket, long nowMillis) {
        synchronized (tickets) {
            if (ticket == null || tickets.get(ticket.playerId()) != ticket) {
                return;
            }
        }
        if (nowMillis >= ticket.deadlineMillis()) {
            if (remove(ticket)) {
                ticket.onTimeout().run();
            }
            return;
        }
        DestinationPolicyEngine.Resolution resolution = ticket.resolver().get();
        switch (resolution.kind()) {
            case CHOSEN -> {
                if (remove(ticket)) {
                    ticket.onResolved().accept(resolution);
                }
            }
            case NONE -> {
                if (remove(ticket)) {
                    ticket.onTimeout().run();
                }
            }
            case QUEUE -> {
                if (ticket(ticket.playerId()) == ticket) {
                    ticket.onPosition().onPosition(position(ticket.playerId()), ticket.deadlineMillis() - nowMillis);
                }
            }
        }
    }
}
