package art.arcane.wormholes.api.events;

import org.bukkit.event.HandlerList;
import java.util.UUID;

/** Fired once a second after a portal leaves the portal registry. */
public final class PortalDestroyedEvent extends WormholesEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID portalId;
    private final String name;

    public PortalDestroyedEvent(UUID portalId, String name) {
        this.portalId = portalId;
        this.name = name;
    }

    public UUID portalId() {
        return portalId;
    }

    public String name() {
        return name;
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
