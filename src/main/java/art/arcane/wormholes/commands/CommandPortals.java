package art.arcane.wormholes.commands;

import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.OpsMessages;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.network.RemotePortalRegistry;
import art.arcane.wormholes.ops.PortalListModel;
import art.arcane.wormholes.ops.PortalLocator;
import art.arcane.wormholes.ops.PortalMaintenance;
import art.arcane.wormholes.ops.PortalSafeLanding;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.UniversalTunnel;
import art.arcane.wormholes.service.WormholesAudience;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Bulk portal administration: list, find, inspect, travel to, retarget, unlink, prune, and rewrite
 * cross-server links. Destructive subcommands stay dry runs until {@code confirm=true}.
 */
@Director(name = "portals", descriptionKey = OpsMessages.PORTALS_HELP,
        description = "List, inspect, and bulk-edit portals")
public class CommandPortals {
    private static final String PERMISSION = "wormholes.admin.portals";

    @Director(name = "list", sync = true, descriptionKey = OpsMessages.PORTALS_LIST_HELP,
            description = "List portals with optional filters")
    public void list(@Param(name = "sender", contextual = true) CommandSender sender,
                     @Param(name = "world", descriptionKey = OpsMessages.PORTALS_LIST_WORLD_HELP,
                             description = "Only portals in this world", defaultValue = "") String world,
                     @Param(name = "type", descriptionKey = OpsMessages.PORTALS_LIST_TYPE_HELP,
                             description = "Only portals of this type: portal, wormhole, gateway, or rtp",
                             defaultValue = "") String type,
                     @Param(name = "owner", descriptionKey = OpsMessages.PORTALS_LIST_OWNER_HELP,
                             description = "Only portals owned by this player name or uuid",
                             defaultValue = "") String owner,
                     @Param(name = "state", descriptionKey = OpsMessages.PORTALS_LIST_STATE_HELP,
                             description = "Only portals in this state: open, closed, linked, or unlinked",
                             defaultValue = "") String state,
                     @Param(name = "page", descriptionKey = OpsMessages.PORTALS_LIST_PAGE_HELP,
                             description = "Page number", defaultValue = "1") int page) {
        if (!allowed(sender)) {
            return;
        }
        PortalListModel.Page rendered = PortalListModel.page(rows(),
                new PortalListModel.Filters(world, type, owner, state), page);
        sendPage(sender, rendered);
    }

    @Director(name = "find", sync = true, descriptionKey = OpsMessages.PORTALS_FIND_HELP,
            description = "Find portals whose name contains the text")
    public void find(@Param(name = "sender", contextual = true) CommandSender sender,
                     @Param(name = "name", descriptionKey = OpsMessages.PORTALS_FIND_NAME_HELP,
                             description = "Text to search for") String name) {
        if (!allowed(sender)) {
            return;
        }
        String needle = name.toLowerCase();
        List<PortalListModel.PortalRow> matches = new ArrayList<>();
        for (PortalListModel.PortalRow row : rows()) {
            if (row.name() != null && row.name().toLowerCase().contains(needle)) {
                matches.add(row);
            }
        }
        sendPage(sender, PortalListModel.page(matches, PortalListModel.Filters.none(), 1));
    }

    @Director(name = "info", sync = true, descriptionKey = OpsMessages.PORTALS_INFO_HELP,
            description = "Show one portal's world, state, destination, and owner")
    public void info(@Param(name = "sender", contextual = true) CommandSender sender,
                     @Param(name = "portal", descriptionKey = OpsMessages.PORTALS_INFO_PORTAL_HELP,
                             description = "Portal name or id") String portal) {
        if (!allowed(sender)) {
            return;
        }
        ILocalPortal found = require(sender, portal);
        if (found == null) {
            return;
        }
        Location center = found.getCenter();
        sendLines(sender, OpsMessages.PORTALS_INFO, WormholesLocalization.args(
                MessageArgument.untrusted("portal", found.getName()),
                MessageArgument.untrusted("id", found.getId().toString()),
                MessageArgument.untrusted("world", center == null || center.getWorld() == null
                        ? "-" : center.getWorld().getName()),
                MessageArgument.untrusted("value", center == null ? "-" : coordinates(center)),
                MessageArgument.untrusted("state", found.isOpen()
                        ? PortalListModel.STATE_OPEN : PortalListModel.STATE_CLOSED),
                MessageArgument.untrusted("destination", destination(found)),
                MessageArgument.untrusted("owner", ownerName(found))));
    }

    @Director(name = "tp", sync = true, descriptionKey = OpsMessages.PORTALS_TP_HELP,
            description = "Teleport to a portal, landing on safe ground")
    public void tp(@Param(name = "sender", contextual = true) CommandSender sender,
                   @Param(name = "portal", descriptionKey = OpsMessages.PORTALS_TP_PORTAL_HELP,
                           description = "Portal name or id") String portal) {
        if (!allowed(sender)) {
            return;
        }
        if (!(sender instanceof Player player)) {
            send(sender, WormholesMessages.COMMAND_ONLY_PLAYERS);
            return;
        }
        ILocalPortal found = require(sender, portal);
        if (found == null) {
            return;
        }
        Location center = found.getCenter();
        if (center == null || center.getWorld() == null) {
            send(sender, OpsMessages.PORTALS_NOT_FOUND, WormholesLocalization.args(
                    MessageArgument.untrusted("name", portal)));
            return;
        }
        PortalSafeLanding.choose(found).whenComplete((landing, failure) -> {
            Location target = landing == null || failure != null ? center : landing.location();
            boolean safe = landing != null && failure == null && landing.safe();
            FoliaScheduler.runEntity(Wormholes.instance, player, () -> {
                player.teleport(target);
                if (!safe) {
                    send(sender, OpsMessages.PORTALS_TELEPORT_UNSAFE, WormholesLocalization.args(
                            MessageArgument.untrusted("portal", found.getName())));
                }
                send(sender, OpsMessages.PORTALS_TELEPORTED, WormholesLocalization.args(
                        MessageArgument.untrusted("portal", found.getName())));
            });
        });
    }

    @Director(name = "retarget", sync = true, descriptionKey = OpsMessages.PORTALS_RETARGET_HELP,
            description = "Point a portal at another local portal")
    public void retarget(@Param(name = "sender", contextual = true) CommandSender sender,
                         @Param(name = "portal", descriptionKey = OpsMessages.PORTALS_RETARGET_PORTAL_HELP,
                                 description = "Portal name or id to change") String portal,
                         @Param(name = "destination", descriptionKey = OpsMessages.PORTALS_RETARGET_DESTINATION_HELP,
                                 description = "Portal name or id to point at") String destination) {
        if (!allowed(sender)) {
            return;
        }
        ILocalPortal source = require(sender, portal);
        if (source == null) {
            return;
        }
        ILocalPortal target = require(sender, destination);
        if (target == null) {
            return;
        }
        source.setDestination(target);
        send(sender, OpsMessages.PORTALS_RETARGETED, WormholesLocalization.args(
                MessageArgument.untrusted("portal", source.getName()),
                MessageArgument.untrusted("destination", target.getName())));
    }

    @Director(name = "unlink", sync = true, descriptionKey = OpsMessages.PORTALS_UNLINK_HELP,
            description = "Remove a portal's destination")
    public void unlink(@Param(name = "sender", contextual = true) CommandSender sender,
                       @Param(name = "portal", descriptionKey = OpsMessages.PORTALS_UNLINK_PORTAL_HELP,
                               description = "Portal name or id") String portal) {
        if (!allowed(sender)) {
            return;
        }
        ILocalPortal found = require(sender, portal);
        if (found == null) {
            return;
        }
        found.unlink();
        send(sender, OpsMessages.PORTALS_UNLINKED, WormholesLocalization.args(
                MessageArgument.untrusted("portal", found.getName())));
    }

    @Director(name = "prune", sync = true, descriptionKey = OpsMessages.PORTALS_PRUNE_HELP,
            description = "Remove links whose destination no longer exists")
    public void prune(@Param(name = "sender", contextual = true) CommandSender sender,
                      @Param(name = "dry", descriptionKey = OpsMessages.PORTALS_PRUNE_DRY_HELP,
                              description = "Report what would be pruned without changing anything",
                              defaultValue = "true") boolean dry,
                      @Param(name = "confirm", descriptionKey = OpsMessages.PORTALS_PRUNE_CONFIRM_HELP,
                              description = "Required to prune when dry=false", defaultValue = "false") boolean confirm) {
        if (!allowed(sender)) {
            return;
        }
        List<PortalMaintenance.TunnelView> orphans = PortalMaintenance.orphans(tunnels());
        if (dry) {
            send(sender, OpsMessages.PORTALS_PRUNE_DRY_RUN, countArgs(orphans.size()));
            return;
        }
        if (!confirm) {
            send(sender, OpsMessages.BACKUP_CONFIRM_REQUIRED);
            return;
        }
        PortalManager manager = Wormholes.portalManager;
        int pruned = 0;
        for (PortalMaintenance.TunnelView orphan : orphans) {
            ILocalPortal portal = manager.getLocalPortal(orphan.portalId());
            if (portal != null) {
                portal.unlink();
                pruned++;
            }
        }
        send(sender, OpsMessages.PORTALS_PRUNED, countArgs(pruned));
    }

    @Director(name = "rename-server", sync = true, descriptionKey = OpsMessages.PORTALS_RENAME_SERVER_HELP,
            description = "Rewrite cross-server links from one server name to another")
    public void renameServer(@Param(name = "sender", contextual = true) CommandSender sender,
                             @Param(name = "old", descriptionKey = OpsMessages.PORTALS_RENAME_SERVER_OLD_HELP,
                                     description = "Server name stored in the links today") String old,
                             @Param(name = "new", descriptionKey = OpsMessages.PORTALS_RENAME_SERVER_NEW_HELP,
                                     description = "Server name to store instead") String replacement,
                             @Param(name = "confirm", descriptionKey = OpsMessages.PORTALS_RENAME_SERVER_CONFIRM_HELP,
                                     description = "Required to rewrite the links",
                                     defaultValue = "false") boolean confirm) {
        if (!allowed(sender)) {
            return;
        }
        List<PortalMaintenance.TunnelView> affected = PortalMaintenance.onServer(tunnels(), old);
        if (!confirm) {
            send(sender, OpsMessages.BACKUP_DRY_RUN, countArgs(affected.size()));
            send(sender, OpsMessages.BACKUP_CONFIRM_REQUIRED);
            return;
        }
        PortalManager manager = Wormholes.portalManager;
        int rewritten = 0;
        for (PortalMaintenance.TunnelView tunnel : affected) {
            ILocalPortal portal = manager.getLocalPortal(tunnel.portalId());
            if (portal == null || !(portal.getTunnel() instanceof UniversalTunnel universal)) {
                continue;
            }
            UUID destinationId = universal.getDestinationPortalId();
            if (destinationId != null && portal.linkRemote(replacement.trim(), destinationId)) {
                rewritten++;
            }
        }
        send(sender, OpsMessages.PORTALS_RENAMED_SERVER, WormholesLocalization.args(
                MessageArgument.untrusted("count", Integer.valueOf(rewritten)),
                MessageArgument.untrusted("name", old),
                MessageArgument.untrusted("server", replacement)));
    }

    private void sendPage(CommandSender sender, PortalListModel.Page page) {
        if (page.total() == 0) {
            send(sender, OpsMessages.PORTALS_EMPTY);
            return;
        }
        for (PortalListModel.PortalRow row : page.rows()) {
            send(sender, OpsMessages.PORTALS_ROW, WormholesLocalization.args(
                    MessageArgument.untrusted("portal", row.name()),
                    MessageArgument.untrusted("world", row.world()),
                    MessageArgument.untrusted("state", row.open()
                            ? PortalListModel.STATE_OPEN : PortalListModel.STATE_CLOSED),
                    MessageArgument.untrusted("destination", row.destination().isEmpty() ? "-" : row.destination())));
        }
        send(sender, OpsMessages.PORTALS_PAGE, WormholesLocalization.args(
                MessageArgument.untrusted("page", Integer.valueOf(page.page())),
                MessageArgument.untrusted("pages", Integer.valueOf(page.pages()))));
    }

    private static List<PortalListModel.PortalRow> rows() {
        PortalManager manager = Wormholes.portalManager;
        if (manager == null) {
            return List.of();
        }
        List<PortalListModel.PortalRow> rows = new ArrayList<>();
        for (ILocalPortal portal : manager.getLocalPortals()) {
            Location center = portal.getCenter();
            rows.add(new PortalListModel.PortalRow(portal.getId(), portal.getName(),
                    center == null || center.getWorld() == null ? "-" : center.getWorld().getName(),
                    portal.getType().name(), portal.isOpen(), portal.hasTunnel(), destination(portal),
                    ownerId(portal), ownerName(portal)));
        }
        return rows;
    }

    private static List<PortalMaintenance.TunnelView> tunnels() {
        PortalManager manager = Wormholes.portalManager;
        if (manager == null) {
            return List.of();
        }
        RemotePortalRegistry registry = Wormholes.remotePortalRegistry;
        List<PortalMaintenance.TunnelView> tunnels = new ArrayList<>();
        for (ILocalPortal portal : manager.getLocalPortals()) {
            ITunnel tunnel = portal.getTunnel();
            String server = tunnel instanceof UniversalTunnel universal ? universal.getServerName() : null;
            boolean peerKnown = server != null && registry != null && registry.hasPeer(server);
            tunnels.add(new PortalMaintenance.TunnelView(portal.getId(), portal.getName(), tunnel != null,
                    tunnel != null && tunnel.getDestination() != null, server, peerKnown));
        }
        return tunnels;
    }

    private ILocalPortal require(CommandSender sender, String query) {
        PortalManager manager = Wormholes.portalManager;
        if (manager == null) {
            send(sender, OpsMessages.PORTALS_NOT_FOUND, WormholesLocalization.args(
                    MessageArgument.untrusted("name", query)));
            return null;
        }
        List<PortalLocator.Candidate> candidates = new ArrayList<>();
        for (ILocalPortal portal : manager.getLocalPortals()) {
            candidates.add(new PortalLocator.Candidate(portal.getId(), portal.getName()));
        }
        PortalLocator.Resolution resolution = PortalLocator.resolve(candidates, query);
        if (resolution.found()) {
            return manager.getLocalPortal(resolution.id());
        }
        if (resolution.ambiguous()) {
            send(sender, OpsMessages.PORTALS_AMBIGUOUS, WormholesLocalization.args(
                    MessageArgument.untrusted("count", Integer.valueOf(resolution.matches())),
                    MessageArgument.untrusted("name", query)));
            return null;
        }
        send(sender, OpsMessages.PORTALS_NOT_FOUND, WormholesLocalization.args(
                MessageArgument.untrusted("name", query)));
        return null;
    }

    private static String destination(ILocalPortal portal) {
        ITunnel tunnel = portal.getTunnel();
        if (tunnel == null) {
            return "";
        }
        if (tunnel instanceof UniversalTunnel universal) {
            IPortal remote = universal.getDestination();
            String name = remote == null ? String.valueOf(universal.getDestinationPortalId()) : remote.getName();
            return universal.getServerName() == null ? name : universal.getServerName() + ":" + name;
        }
        IPortal destination = tunnel.getDestination();
        return destination == null ? "" : destination.getName();
    }

    private static UUID ownerId(ILocalPortal portal) {
        return portal instanceof LocalPortal local ? local.getOwner() : null;
    }

    private static String ownerName(ILocalPortal portal) {
        UUID owner = ownerId(portal);
        if (owner == null) {
            return "-";
        }
        OfflinePlayer player = Bukkit.getOfflinePlayer(owner);
        return player.getName() == null ? owner.toString() : player.getName();
    }

    private static String coordinates(Location location) {
        return location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ();
    }

    private static MessageArgs countArgs(int count) {
        return WormholesLocalization.args(MessageArgument.untrusted("count", Integer.valueOf(count)));
    }

    private static boolean allowed(CommandSender sender) {
        if (sender.hasPermission(PERMISSION)) {
            return true;
        }
        send(sender, WormholesMessages.COMMAND_NO_PERMISSION);
        return false;
    }

    private static void send(CommandSender sender, TextKey key) {
        WormholesAudience.sendMessage(sender, Wormholes.text().component(sender, key));
    }

    private static void send(CommandSender sender, TextKey key, MessageArgs arguments) {
        WormholesAudience.sendMessage(sender, Wormholes.text().component(sender, key, arguments));
    }

    private static void sendLines(CommandSender sender, LinesKey key, MessageArgs arguments) {
        for (Component line : Wormholes.text().components(sender, key, arguments)) {
            WormholesAudience.sendMessage(sender, line);
        }
    }
}
