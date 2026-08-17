package art.arcane.wormholes.service;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public final class WormholesHudListener implements Listener {
    @EventHandler(priority = EventPriority.MONITOR)
    public void on(PlayerQuitEvent event) {
        WormholesHud.clear(event.getPlayer());
    }
}
