package art.arcane.wormholes.api.events;

import org.bukkit.event.HandlerList;
import art.arcane.wormholes.api.portal.PortalSnapshot;

/** Fired once a second after a new portal appears in the portal registry. */
public final class PortalCreatedEvent extends WormholesEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    private final PortalSnapshot portal;

    public PortalCreatedEvent(PortalSnapshot portal) {
        this.portal = portal;
    }

    public PortalSnapshot portal() {
        return portal;
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
