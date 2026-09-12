package art.arcane.wormholes.rules;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.RulesConfig;
import art.arcane.wormholes.hook.WormholesRegistrar;
import art.arcane.wormholes.hook.WormholesSubsystem;
import art.arcane.wormholes.localization.RulesMessages;
import art.arcane.wormholes.service.WormholesHud;
import art.arcane.wormholes.util.J;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;

import java.util.UUID;

/** Lifecycle entry point for the rules lane. */
public final class RulesSubsystem implements WormholesSubsystem {
    private static final int LEDGER_SWEEP_TICKS = 20;

    private final RulesEnvironment environment = new BukkitRulesEnvironment();
    private final WarmupTracker warmups = new WarmupTracker(new BukkitPinner());
    private final RuleTraversalLedger ledger = new RuleTraversalLedger();
    private final RouteCardService routeCards = new RouteCardService(environment);
    private final TravelerListener travelers = new TravelerListener();
    private volatile Wormholes plugin;
    private int ledgerSweepTask = -1;

    @Override
    public String id() {
        return "rules";
    }

    @Override
    public void register(WormholesRegistrar registrar) {
        registrar.portalExtension(new RulesExtensionFactory());
        registrar.traversalGate(new RulesGate(environment, warmups, ledger));
        registrar.traversalObserver(new RulesObserver(ledger));
        registrar.portalMenuEntry(new RulesMenuEntry());
    }

    @Override
    public void start(Wormholes plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(travelers, plugin);
        plugin.getServer().getPluginManager().registerEvents(routeCards, plugin);
        ledgerSweepTask = J.sr(() -> ledger.prune(System.currentTimeMillis()), LEDGER_SWEEP_TICKS);
    }

    @Override
    public void onSettingsReloaded(WormholesSettings settings) {
        RulesConfig rules = settings.getRules();
        routeCards.clear();
        ConditionCache.clear();
        Wormholes.v("rules reloaded: route card " + (rules.routeCardEnabled ? rules.routeCardIntervalTicks + "t/"
            + rules.routeCardRange + "b" : "off") + ", condition cache " + rules.expensiveConditionCacheMillis + "ms");
    }

    @Override
    public void stop() {
        if (ledgerSweepTask != -1) {
            J.csr(ledgerSweepTask);
            ledgerSweepTask = -1;
        }
        HandlerList.unregisterAll(travelers);
        HandlerList.unregisterAll(routeCards);
        routeCards.clear();
        plugin = null;
        warmups.clear();
        ledger.clear();
        PortalCooldowns.clear();
        ConditionCache.clear();
    }

    /** Drops the in-memory traveler state a departed player owned, and cancels a warmup on damage. */
    private final class TravelerListener implements Listener {
        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
            UUID playerId = event.getPlayer().getUniqueId();
            warmups.clear(playerId);
            ledger.discard(playerId);
            routeCards.forget(playerId);
            PortalCooldowns.clear(playerId);
            ConditionCache.clear(playerId);
        }

        @EventHandler(ignoreCancelled = true)
        public void onDamage(EntityDamageEvent event) {
            if (!(event.getEntity() instanceof Player player) || !RulesLimits.config().warmupCancelOnDamage) {
                return;
            }
            warmups.cancelOnDamage(player.getUniqueId(), System.currentTimeMillis());
        }
    }

    /**
     * Holds a warming traveler at the aperture with a one-tick self-rescheduling task on their own scheduler,
     * which is the only thread allowed to touch their velocity on a regionised server.
     */
    private final class BukkitPinner implements WarmupTracker.Pinner {
        private static final Vector STILL = new Vector(0.0D, 0.0D, 0.0D);

        @Override
        public void schedule(UUID playerId) {
            Wormholes host = plugin;
            Player player = Bukkit.getPlayer(playerId);
            if (host == null || player == null) {
                return;
            }
            Runnable[] step = new Runnable[1];
            step[0] = () -> {
                if (!player.isValid()) {
                    warmups.clear(playerId);
                    return;
                }
                long nowMillis = System.currentTimeMillis();
                if (warmups.cancelOnMove(playerId, player.getLocation(), RulesLimits.config().warmupCancelMoveBlocks, nowMillis)
                    || !warmups.tick(playerId, nowMillis)) {
                    return;
                }
                player.setVelocity(STILL.clone());
                FoliaScheduler.runEntity(host, player, step[0], 1L, () -> warmups.clear(playerId));
            };
            FoliaScheduler.runEntity(host, player, step[0], 1L, () -> warmups.clear(playerId));
        }

        @Override
        public void pin(UUID playerId, long secondsLeft) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) {
                return;
            }
            MessageArgs arguments = MessageArgs.builder()
                .untrusted("seconds", Long.valueOf(secondsLeft))
                .build();
            WormholesHud.hold(player, Wormholes.text().component(player, RulesMessages.WARMUP_COUNTDOWN, arguments));
        }

        @Override
        public void cancelled(UUID playerId) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) {
                return;
            }
            WormholesHud.notice(player, Wormholes.text().component(player, RulesMessages.WARMUP_CANCELLED, MessageArgs.empty()));
        }
    }
}
