package art.arcane.wormholes.api.events;

import org.bukkit.event.HandlerList;
import art.arcane.wormholes.api.network.PeerSnapshot;

/** Fired when a federated peer's link comes up. */
public final class PeerConnectedEvent extends WormholesEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    private final PeerSnapshot peer;

    public PeerConnectedEvent(PeerSnapshot peer) {
        this.peer = peer;
    }

    public PeerSnapshot peer() {
        return peer;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    /** True when a plugin is listening; callers skip building the event otherwise. */
    public static boolean listening() {
        return hasListeners(HANDLERS);
    }
}
