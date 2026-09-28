package art.arcane.wormholes.api.traversal.internal;

import art.arcane.wormholes.api.traversal.TraversalCostProvider;
import art.arcane.wormholes.api.traversal.TraversalContext;
import art.arcane.wormholes.api.traversal.TraversalQuote;
import art.arcane.wormholes.api.traversal.TraversalReservation;
import art.arcane.wormholes.api.traversal.TraversalReceipt;
import art.arcane.wormholes.api.traversal.TraversalRefundReason;
import org.bukkit.plugin.ServicePriority;

import java.util.Objects;
import java.util.function.BooleanSupplier;

public record TraversalCostRegistration(TraversalCostProvider provider, String providerId, String pluginName,
                                        ServicePriority priority, BooleanSupplier pluginEnabled) implements TraversalCostEngine.Registration<TraversalContext> {
    public TraversalCostRegistration {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(pluginName, "pluginName");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(pluginEnabled, "pluginEnabled");
    }

    public static TraversalCostRegistration of(TraversalCostProvider provider, String providerId, String pluginName,
                                               ServicePriority priority) {
        return new TraversalCostRegistration(provider, providerId, pluginName, priority, () -> true);
    }

    public boolean ownerEnabled() {
        return pluginEnabled.getAsBoolean();
    }

    @Override
    public String ownerName() {
        return pluginName;
    }

    @Override
    public int priorityValue() {
        return priority.ordinal();
    }

    @Override
    public Object providerIdentity() {
        return provider;
    }

    @Override
    public TraversalQuote quote(TraversalContext context) {
        return provider.quote(context);
    }

    @Override
    public TraversalReservation reserve(TraversalContext context, TraversalQuote quote) {
        return provider.reserve(context, quote);
    }

    @Override
    public void commit(TraversalReceipt receipt) {
        provider.commit(receipt);
    }

    @Override
    public void refund(TraversalReceipt receipt, TraversalRefundReason reason) {
        provider.refund(receipt, reason);
    }
}
