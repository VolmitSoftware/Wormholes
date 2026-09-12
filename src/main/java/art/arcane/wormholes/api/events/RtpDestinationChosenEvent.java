package art.arcane.wormholes.api.events;

import org.bukkit.event.HandlerList;
import java.util.UUID;

/** Reserved: an RTP portal settled on a destination. Wormholes does not fire this yet; it is
 * published so listeners written against API v2 keep compiling when it starts firing. */
public final class RtpDestinationChosenEvent extends WormholesEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID portalId;
    private final String worldKey;
    private final int blockX;
    private final int blockY;
    private final int blockZ;

    public RtpDestinationChosenEvent(UUID portalId, String worldKey, int blockX, int blockY, int blockZ) {
        this.portalId = portalId;
        this.worldKey = worldKey;
        this.blockX = blockX;
        this.blockY = blockY;
        this.blockZ = blockZ;
    }

    public UUID portalId() {
        return portalId;
    }

    public String worldKey() {
        return worldKey;
    }

    public int blockX() {
        return blockX;
    }

    public int blockY() {
        return blockY;
    }

    public int blockZ() {
        return blockZ;
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
