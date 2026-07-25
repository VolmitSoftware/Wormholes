package art.arcane.wormholes.api.traversal.internal;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.service.WormholesTelemetry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class BukkitTraversalEventSink implements TraversalEventSink {
    public static final String FAILURE_EVENT_DROPPED = "TRAVERSAL_EVENT_DROPPED";
    public static final String FAILURE_EVENT_DISPATCH = "TRAVERSAL_EVENT_DISPATCH_FAILED";

    private final Plugin plugin;
    private final Logger logger;

    public BukkitTraversalEventSink(Plugin plugin, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public void fireImmediate(Event event) {
        if (event == null || event.getHandlers().getRegisteredListeners().length == 0) {
            return;
        }

        try {
            Bukkit.getPluginManager().callEvent(event);
        } catch (Throwable error) {
            WormholesTelemetry.countFailure(FAILURE_EVENT_DISPATCH);
            logger.log(Level.WARNING, "Failed to dispatch " + event.getEventName(), error);
        }
    }

    @Override
    public void fireOnEntity(Player traveler, Event event) {
        if (event == null || event.getHandlers().getRegisteredListeners().length == 0) {
            return;
        }

        if (traveler == null) {
            fireImmediate(event);
            return;
        }

        if (FoliaScheduler.runEntity(plugin, traveler, () -> fireImmediate(event))) {
            return;
        }

        WormholesTelemetry.countFailure(FAILURE_EVENT_DROPPED);
        logger.warning("Dropped " + event.getEventName() + " for " + traveler.getUniqueId()
            + " because the entity scheduler refused it");
    }
}
