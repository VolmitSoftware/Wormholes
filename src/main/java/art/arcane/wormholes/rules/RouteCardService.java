package art.arcane.wormholes.rules;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.access.AccessPortalExtension;
import art.arcane.wormholes.config.toml.RulesConfig;
import art.arcane.wormholes.localization.RulesMessages;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.RemotePortal;
import art.arcane.wormholes.service.WormholesHud;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shows the route card on the action bar to a player standing near a portal that has rules or a price. It runs
 * off the traveler's own move events, which is both the thread that may read their position on a regionised
 * server and the only time the card can change; a per-player throttle keeps it at the configured cadence.
 */
final class RouteCardService implements Listener {
    private static final String UNLISTED_SETTING = "access.listed";

    private final RulesEnvironment environment;
    private final Map<UUID, Long> lastPublished = new ConcurrentHashMap<>();

    RouteCardService(RulesEnvironment environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        RulesConfig config = RulesLimits.config();
        if (!config.routeCardEnabled) {
            return;
        }
        Player player = event.getPlayer();
        long nowMillis = System.currentTimeMillis();
        Long previous = lastPublished.get(player.getUniqueId());
        if (previous != null && nowMillis - previous.longValue() < config.routeCardIntervalTicks * 50L) {
            return;
        }
        lastPublished.put(player.getUniqueId(), Long.valueOf(nowMillis));
        publish(player, config, nowMillis);
    }

    void forget(UUID playerId) {
        lastPublished.remove(playerId);
        RouteCardCache.forget(playerId);
    }

    void clear() {
        lastPublished.clear();
        RouteCardCache.clear();
    }

    private void publish(Player player, RulesConfig config, long nowMillis) {
        LocalPortal portal = nearestCandidate(player, config.routeCardRange);
        if (portal == null) {
            return;
        }
        RulesPortalExtension extension = portal.extension(RulesPortalExtension.class);
        CompiledRules.Match dryRun = extension == null
            ? new CompiledRules.Match(null, RuleOutcome.allow(), List.of(), List.of())
            : extension.compiled().evaluate(new RuleContext(portal, player, player, nowMillis, true, environment));
        long cooldownRemaining = extension == null ? 0L : PortalCooldowns.remainingMillis(player.getUniqueId(),
            portal.getId(), extension.document().profile().cooldownGroup(), nowMillis);
        ChargePool charges = extension == null ? new ChargePool() : extension.charges();
        RouteCardModel.RouteCard card = RouteCardModel.build(portal, player, dryRun, cooldownRemaining, charges, listed(portal), nowMillis);
        RouteCardCache.publish(player.getUniqueId(), new RouteCardCache.Entry(portal.getId(), card.price(),
            card.state() == RouteCardModel.RouteCard.State.REFUSED ? refusalText(player, card) : "", nowMillis));
        WormholesHud.notice(player, Wormholes.text().component(player, RulesMessages.ROUTE_CARD, MessageArgs.builder()
            .untrusted("portal", card.portal())
            .untrusted("destination", card.destinationKnown() ? card.destination() : label(player, RulesMessages.ROUTE_UNLISTED))
            .untrusted("amount", amount(player, card))
            .untrusted("state", state(player, card))
            .build()));
    }

    /** The closest portal within range that has something to say: a rules document or a travel cost. */
    private static LocalPortal nearestCandidate(Player player, double range) {
        if (Wormholes.portalManager == null || player.getWorld() == null) {
            return null;
        }
        double bestDistanceSquared = range * range;
        LocalPortal best = null;
        for (ILocalPortal candidate : Wormholes.portalManager.getLocalPortals()) {
            if (!(candidate instanceof LocalPortal portal) || !portal.isOpen()) {
                continue;
            }
            Location center = portal.getCenter();
            if (center == null || center.getWorld() == null || !center.getWorld().equals(player.getWorld())) {
                continue;
            }
            if (!interesting(portal)) {
                continue;
            }
            double distanceSquared = center.distanceSquared(player.getLocation());
            if (distanceSquared < bestDistanceSquared) {
                bestDistanceSquared = distanceSquared;
                best = portal;
            }
        }
        return best;
    }

    private static boolean interesting(LocalPortal portal) {
        if (portal.getTravelCost() != null) {
            return true;
        }
        RulesPortalExtension extension = portal.extension(RulesPortalExtension.class);
        return extension != null && !extension.document().isInert();
    }

    /** A destination the access lane has marked unlisted stays hidden on the card, local or remote. */
    static boolean listed(LocalPortal portal) {
        ITunnel tunnel = portal.getTunnel();
        if (tunnel == null) {
            return true;
        }
        IPortal destination = tunnel.getDestination();
        if (destination instanceof LocalPortal local) {
            AccessPortalExtension access = local.extension(AccessPortalExtension.class);
            return access == null || access.listed();
        }
        if (!(destination instanceof RemotePortal remote)) {
            return true;
        }
        return !"false".equalsIgnoreCase(remote.mirroredExtensionSetting(UNLISTED_SETTING));
    }

    private static String amount(Player player, RouteCardModel.RouteCard card) {
        if (card.free()) {
            return label(player, RulesMessages.ROUTE_FREE);
        }
        return (card.affordable() ? "&a" : "&c") + card.price();
    }

    private static String state(Player player, RouteCardModel.RouteCard card) {
        return switch (card.state()) {
            case READY -> label(player, RulesMessages.ROUTE_READY);
            case COOLDOWN -> PlainTextComponentSerializer.plainText().serialize(Wormholes.text().component(player,
                RulesMessages.ROUTE_COOLDOWN, MessageArgs.builder().untrusted("seconds", Long.valueOf(card.cooldownSeconds())).build()));
            case REFUSED -> refusalText(player, card);
        };
    }

    /** The refusal the gate would give this viewer, rendered plainly for the action bar and the PAPI key. */
    private static String refusalText(Player player, RouteCardModel.RouteCard card) {
        return PlainTextComponentSerializer.plainText().serialize(Wormholes.text().component(player,
            RulesMessages.denial(card.refusalKey()),
            RuleMessageArgs.of().with("portal", card.portal()).forKey(RulesMessages.denial(card.refusalKey()))));
    }

    private static String label(Player player, TextKey key) {
        return PlainTextComponentSerializer.plainText().serialize(Wormholes.text().component(player, key, MessageArgs.empty()));
    }
}
