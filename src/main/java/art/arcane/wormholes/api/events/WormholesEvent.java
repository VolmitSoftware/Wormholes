package art.arcane.wormholes.api.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Base for the Wormholes lifecycle events. All are informational and none are cancellable. */
public abstract class WormholesEvent extends Event {
    protected WormholesEvent() {
        super(false);
    }

    /** True when at least one plugin is listening, so callers can skip building an event. */
    public static boolean hasListeners(HandlerList handlers) {
        return handlers.getRegisteredListeners().length > 0;
    }
}
