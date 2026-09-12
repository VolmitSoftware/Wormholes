package art.arcane.wormholes.api.events;

import org.bukkit.event.HandlerList;
import java.util.UUID;

/** Fired when a traveler has passed every gate and is departing through a portal. */
public final class HandoffAdmittedEvent extends WormholesEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID travelerId;
    private final UUID portalId;
    private final String destinationServer;

    public HandoffAdmittedEvent(UUID travelerId, UUID portalId, String destinationServer) {
        this.travelerId = travelerId;
        this.portalId = portalId;
        this.destinationServer = destinationServer;
    }

    public UUID travelerId() {
        return travelerId;
    }

    public UUID portalId() {
        return portalId;
    }

    public String destinationServer() {
        return destinationServer;
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
