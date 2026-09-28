package art.arcane.wormholes.api.traversal.internal;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.api.traversal.TraversalContext;
import art.arcane.wormholes.api.traversal.WormholesPortalTraverseEvent;
import art.arcane.wormholes.api.traversal.WormholesPortalTraversedEvent;
import art.arcane.wormholes.service.WormholesTelemetry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;

public final class TraversalCostGateway extends TraversalCostEngine<Player, TraversalContext, TraversalCostRegistration> {
    private static final TravelerExecutor DIRECT_EXECUTOR = new TravelerExecutor() {
        @Override
        public boolean isOwned(Player traveler) {
            return true;
        }

        @Override
        public boolean dispatch(Player traveler, Runnable task, Runnable retired) {
            task.run();
            return true;
        }

        @Override
        public boolean retry(Runnable task, long delayTicks) {
            task.run();
            return true;
        }
    };

    private Listener serviceListener;

    public TraversalCostGateway(Supplier<List<TraversalCostRegistration>> registrations,
                                Supplier<TraversalCostPolicy> policy, TraversalEventSink events, Logger logger,
                                LongSupplier clock) {
        this(registrations, policy, events, logger, clock, DIRECT_EXECUTOR);
    }

    TraversalCostGateway(Supplier<List<TraversalCostRegistration>> registrations,
                         Supplier<TraversalCostPolicy> policy, TraversalEventSink events, Logger logger,
                         LongSupplier clock, TravelerExecutor executor) {
        super(new Options<>(registrations, policy, new Events(events), logger, clock, executor,
            WormholesTelemetry::countFailure));
    }

    public static TraversalCostGateway bukkit(Plugin plugin, Supplier<TraversalCostPolicy> policy) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(policy, "policy");
        Logger logger = plugin.getLogger();
        BukkitTraversalCostProviderSource source = new BukkitTraversalCostProviderSource(logger);
        TraversalCostGateway gateway = new TraversalCostGateway(source, policy,
            new BukkitTraversalEventSink(plugin, logger), logger, System::currentTimeMillis,
            new BukkitTravelerExecutor(plugin));
        TraversalCostServiceListener listener = new TraversalCostServiceListener(source, gateway);
        gateway.serviceListener = listener;
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        return gateway;
    }

    @Override
    public void shutdown() {
        super.shutdown();
        if (serviceListener != null) {
            HandlerList.unregisterAll(serviceListener);
            serviceListener = null;
        }
    }

    interface TravelerExecutor extends TraversalCostEngine.TravelerExecutor<Player> {
        @Override
        default UUID id(Player traveler) {
            return traveler.getUniqueId();
        }
    }

    private record Events(TraversalEventSink sink) implements TraversalCostEngine.Events<Player, TraversalContext> {
        private Events {
            Objects.requireNonNull(sink);
        }

        @Override
        public String before(TraversalContext context) {
            WormholesPortalTraverseEvent event = new WormholesPortalTraverseEvent(context);
            sink.fireImmediate(event);
            return event.isCancelled() ? event.getCancelReason() : null;
        }

        @Override
        public void committed(Player player, Completion<TraversalContext> completion) {
            sink.fireOnEntity(player, new WormholesPortalTraversedEvent(completion.context(), completion.outcome(), completion.chargedProviders()));
        }
    }

    private record BukkitTravelerExecutor(Plugin plugin) implements TravelerExecutor {
        @Override
        public boolean isOwned(Player traveler) {
            return FoliaScheduler.isOwnedByCurrentRegion(traveler);
        }

        @Override
        public boolean dispatch(Player traveler, Runnable task, Runnable retired) {
            return FoliaScheduler.runEntity(plugin, traveler, task, 0L, retired);
        }

        @Override
        public boolean retry(Runnable task, long delayTicks) {
            return FoliaScheduler.runGlobal(plugin, task, delayTicks);
        }
    }
}
