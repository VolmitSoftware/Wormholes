package art.arcane.wormholes.access;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.api.traversal.WormholesPortalCreateEvent;
import art.arcane.wormholes.config.toml.AccessConfig;
import art.arcane.wormholes.localization.AccessMessages;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.service.WormholesHud;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The installed {@link AccessGuard}: per-player portal limits, the configured claim adapters, and
 * the cancellable {@link WormholesPortalCreateEvent}.
 */
public final class ClaimAccessGuard implements AccessGuard {
    private final ClaimAdapters adapters;
    private volatile AccessConfig config = new AccessConfig();

    public ClaimAccessGuard(ClaimAdapters adapters) {
        this.adapters = Objects.requireNonNull(adapters, "adapters");
    }

    public void applySettings(AccessConfig settings) {
        config = settings == null ? new AccessConfig() : settings;
    }

    @Override
    public boolean allowConstruct(UUID ownerId, Set<Block> cells, PortalType type) {
        if (cells.isEmpty()) {
            return true;
        }
        World world = cells.iterator().next().getWorld();
        Player owner = resolve(ownerId);
        AccessConfig settings = config;
        if (!withinPortalLimit(owner, settings)) {
            return false;
        }
        if (settings.claimCheckOnConstruct) {
            PlacementDecision decision = adapters.evaluate(
                new PlacementRequest(ownerId, world, positions(cells), PlacementKind.CREATE));
            if (!decision.allowed()) {
                notify(owner, AccessMessages.CONSTRUCT_DENIED, reasonArgument(decision));
                return false;
            }
        }
        WormholesPortalCreateEvent event = new WormholesPortalCreateEvent(ownerId, world, cells, type);
        if (!cancelled(event)) {
            return true;
        }
        String reason = event.getCancelReason();
        if (!reason.isBlank()) {
            notify(owner, AccessMessages.CONSTRUCT_DENIED,
                WormholesLocalization.args(MessageArgument.untrusted("reason", reason)));
        }
        return false;
    }

    @Override
    public boolean allowPlacement(UUID actorId, World world, List<int[]> cells, PlacementKind kind, String subject) {
        if (!checkEnabled(kind) || cells.isEmpty()) {
            return true;
        }
        PlacementDecision decision = adapters.evaluate(new PlacementRequest(actorId, world, cells, kind));
        if (decision.allowed()) {
            return true;
        }
        Player actor = resolve(actorId);
        if (kind == PlacementKind.CREATE) {
            notify(actor, AccessMessages.CONSTRUCT_DENIED, reasonArgument(decision));
        } else {
            notify(actor, AccessMessages.DENIED_CLAIM,
                WormholesLocalization.args(MessageArgument.untrusted("portal", subject)));
        }
        return false;
    }

    private boolean withinPortalLimit(Player owner, AccessConfig settings) {
        if (owner == null) {
            return true;
        }
        int maximum = PortalLimits.maximum(owner, settings.portalLimitDefault);
        int owned = PortalLimits.owned(owner.getUniqueId());
        if (maximum <= 0 || owned < maximum) {
            return true;
        }
        notify(owner, AccessMessages.DENIED_LIMIT, WormholesLocalization.args(
            MessageArgument.untrusted("count", owned),
            MessageArgument.untrusted("maximum", maximum)));
        return false;
    }

    private boolean checkEnabled(PlacementKind kind) {
        AccessConfig settings = config;
        return switch (kind) {
            case CREATE -> settings.claimCheckOnConstruct;
            case LINK -> settings.claimCheckOnLink;
            case USE, ARRIVE -> settings.claimCheckOnUse;
        };
    }

    private static boolean cancelled(WormholesPortalCreateEvent event) {
        if (event.getHandlers().getRegisteredListeners().length == 0) {
            return false;
        }
        try {
            Bukkit.getPluginManager().callEvent(event);
        } catch (RuntimeException | LinkageError dispatchFailure) {
            Wormholes.w("access: a listener threw on WormholesPortalCreateEvent: " + dispatchFailure);
            return false;
        }
        return event.isCancelled();
    }

    private static MessageArgs reasonArgument(PlacementDecision decision) {
        TextKey reasonKey = decision.status() == PlacementDecision.Status.FAILURE
            ? AccessMessages.CLAIM_FAILURE
            : AccessMessages.CLAIM_REASON;
        String reason = Wormholes.text().plain(reasonKey,
            WormholesLocalization.args(MessageArgument.untrusted("plugin", decision.plugin())));
        return WormholesLocalization.args(MessageArgument.untrusted("reason", reason));
    }

    private static List<int[]> positions(Set<Block> cells) {
        List<int[]> positions = new ArrayList<>(cells.size());
        for (Block cell : cells) {
            positions.add(new int[] {cell.getX(), cell.getY(), cell.getZ()});
        }
        return positions;
    }

    private static Player resolve(UUID playerId) {
        return playerId == null ? null : Bukkit.getPlayer(playerId);
    }

    private static void notify(Player player, TextKey message, MessageArgs arguments) {
        if (player == null) {
            return;
        }
        WormholesHud.notice(player, Wormholes.text().component(player, message, arguments));
    }
}
