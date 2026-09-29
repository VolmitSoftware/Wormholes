package art.arcane.wormholes;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;

public final class ProjectionTickHeadroomListener implements Listener {
    private final ProjectionTickHeadroom headroom;

    ProjectionTickHeadroomListener(ProjectionTickHeadroom headroom) {
        this.headroom = headroom;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void on(ServerTickEndEvent event) {
        headroom.recordTickEnd(event.getTimeRemaining());
    }
}
