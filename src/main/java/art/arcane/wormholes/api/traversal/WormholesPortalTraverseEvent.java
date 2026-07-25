package art.arcane.wormholes.api.traversal;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Objects;

public final class WormholesPortalTraverseEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final TraversalContext context;
    private boolean cancelled;
    private String cancelReason = "";

    public WormholesPortalTraverseEvent(TraversalContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    public TraversalContext getContext() {
        return context;
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

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
