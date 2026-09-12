package art.arcane.wormholes.api.events;

import org.bukkit.event.HandlerList;
import java.util.UUID;

/** Reserved: a traveler used a Dimensional Door. Wormholes does not fire this yet; it is published
 * so listeners written against API v2 keep compiling when it starts firing. */
public final class DoorTransitEvent extends WormholesEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID travelerId;
    private final UUID doorId;

    public DoorTransitEvent(UUID travelerId, UUID doorId) {
        this.travelerId = travelerId;
        this.doorId = doorId;
    }

    public UUID travelerId() {
        return travelerId;
    }

    public UUID doorId() {
        return doorId;
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
