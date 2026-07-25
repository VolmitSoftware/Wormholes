package art.arcane.wormholes.api.traversal.internal;

import art.arcane.wormholes.api.traversal.TraversalCostProvider;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.Objects;

public final class TraversalCostServiceListener implements Listener {
    private final BukkitTraversalCostProviderSource source;

    public TraversalCostServiceListener(BukkitTraversalCostProviderSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    @EventHandler
    public void onServiceRegister(ServiceRegisterEvent event) {
        invalidate(event.getProvider());
    }

    @EventHandler
    public void onServiceUnregister(ServiceUnregisterEvent event) {
        invalidate(event.getProvider());
    }

    private void invalidate(RegisteredServiceProvider<?> registration) {
        if (registration != null && TraversalCostProvider.class.equals(registration.getService())) {
            source.invalidate();
        }
    }
}
