package art.arcane.wormholes.api.events;

import art.arcane.wormholes.api.portal.PortalSnapshot;
import org.bukkit.event.HandlerList;

import java.util.Objects;

public final class PortalProjectionChangedEvent extends WormholesEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    private final PortalSnapshot portal;

    public PortalProjectionChangedEvent(PortalSnapshot portal) {
        this.portal = Objects.requireNonNull(portal, "portal");
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

    public static boolean listening() {
        return hasListeners(HANDLERS);
    }
}
