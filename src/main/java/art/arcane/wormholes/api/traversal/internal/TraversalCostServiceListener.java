package art.arcane.wormholes.api.traversal.internal;

import art.arcane.wormholes.api.traversal.TraversalCostProvider;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.Objects;

public final class TraversalCostServiceListener implements Listener {
    private final BukkitTraversalCostProviderSource source;
    private final TraversalCostGateway gateway;

    public TraversalCostServiceListener(BukkitTraversalCostProviderSource source, TraversalCostGateway gateway) {
        this.source = Objects.requireNonNull(source, "source");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    @EventHandler
    public void onServiceRegister(ServiceRegisterEvent event) {
        invalidate(event.getProvider());
    }

    @EventHandler
    public void onServiceUnregister(ServiceUnregisterEvent event) {
        invalidate(event.getProvider());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        gateway.travelerJoined(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        gateway.travelerQuit(event.getPlayer());
    }

    private void invalidate(RegisteredServiceProvider<?> registration) {
        if (registration != null && TraversalCostProvider.class.equals(registration.getService())) {
            source.invalidate();
        }
    }
}
