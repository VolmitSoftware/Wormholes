package art.arcane.wormholes.api.events;

import org.bukkit.event.HandlerList;

/** Fired when a federated peer's link goes down or the peer is forgotten. */
public final class PeerDisconnectedEvent extends WormholesEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    private final String peerName;

    public PeerDisconnectedEvent(String peerName) {
        this.peerName = peerName;
    }

    public String peerName() {
        return peerName;
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
