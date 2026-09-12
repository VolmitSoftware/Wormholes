package art.arcane.wormholes.api.events;

import org.bukkit.event.HandlerList;
import java.util.UUID;

/** Fired when a traversal was refused, carrying the refusal message id. */
public final class HandoffDeniedEvent extends WormholesEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID travelerId;
    private final UUID portalId;
    private final String reason;

    public HandoffDeniedEvent(UUID travelerId, UUID portalId, String reason) {
        this.travelerId = travelerId;
        this.portalId = portalId;
        this.reason = reason;
    }

    public UUID travelerId() {
        return travelerId;
    }

    public UUID portalId() {
        return portalId;
    }

    public String reason() {
        return reason;
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
