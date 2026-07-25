package art.arcane.wormholes.api.traversal.internal;

import art.arcane.wormholes.api.traversal.TraversalCostProvider;
import org.bukkit.plugin.ServicePriority;

import java.util.Objects;
import java.util.function.BooleanSupplier;

public record TraversalCostRegistration(TraversalCostProvider provider, String providerId, String pluginName,
                                        ServicePriority priority, BooleanSupplier pluginEnabled) {
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
}
