package art.arcane.wormholes.api.events;

import org.bukkit.event.HandlerList;
import java.util.UUID;

/** Fired when a traveler has arrived through a portal. */
public final class HandoffCompletedEvent extends WormholesEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID travelerId;
    private final UUID portalId;

    public HandoffCompletedEvent(UUID travelerId, UUID portalId) {
        this.travelerId = travelerId;
        this.portalId = portalId;
    }

    public UUID travelerId() {
        return travelerId;
    }

    public UUID portalId() {
        return portalId;
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
