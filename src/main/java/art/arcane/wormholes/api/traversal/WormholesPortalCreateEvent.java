package art.arcane.wormholes.api.traversal;

import art.arcane.wormholes.portal.PortalType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Set;
import java.util.UUID;

/**
 * Fired on the region thread that owns the frame just before a frame portal is built, after the
 * claim policy has allowed it. Cancelling stops the build; the wand refunds the selection and the
 * rune path rolls its runes back. A cancel reason is shown to the builder; leave it empty for a
 * silent veto.
 */
public final class WormholesPortalCreateEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID ownerId;
    private final World world;
    private final Set<Block> cells;
    private final PortalType type;
    private boolean cancelled;
    private String cancelReason = "";

    public WormholesPortalCreateEvent(UUID ownerId, World world, Set<Block> cells, PortalType type) {
        this.ownerId = ownerId;
        this.world = world;
        this.cells = Set.copyOf(cells);
        this.type = type;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    /** The player the portal will belong to, or null when nothing owns it. */
    public UUID getOwnerId() {
        return ownerId;
    }

    public World getWorld() {
        return world;
    }

    /** The frame cells the portal will occupy. Unmodifiable snapshot. */
    public Set<Block> getCells() {
        return cells;
    }

    public PortalType getType() {
        return type;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public void setCancelReason(String cancelReason) {
        this.cancelReason = TraversalText.sanitize(cancelReason);
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }
}
