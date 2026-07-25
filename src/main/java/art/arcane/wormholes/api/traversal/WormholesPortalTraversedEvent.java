package art.arcane.wormholes.api.traversal;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.List;
import java.util.Objects;

public final class WormholesPortalTraversedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final TraversalContext context;
    private final TraversalOutcome outcome;
    private final List<String> chargedProviderIds;

    public WormholesPortalTraversedEvent(TraversalContext context, TraversalOutcome outcome,
                                         List<String> chargedProviderIds) {
        this.context = Objects.requireNonNull(context, "context");
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.chargedProviderIds = chargedProviderIds == null ? List.of() : List.copyOf(chargedProviderIds);
    }

    public TraversalContext getContext() {
        return context;
    }

    public TraversalOutcome getOutcome() {
        return outcome;
    }

    public List<String> getChargedProviderIds() {
        return chargedProviderIds;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
