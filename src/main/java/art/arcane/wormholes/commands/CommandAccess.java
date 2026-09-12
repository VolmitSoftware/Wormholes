package art.arcane.wormholes.commands;

import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.access.AccessPortalExtension;
import art.arcane.wormholes.access.PortalLimits;
import art.arcane.wormholes.localization.AccessMessages;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.service.WormholesAudience;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Director(name = "access", descriptionKey = "access.command.help.access", description = "Portal access lists, ownership, and limits")
public class CommandAccess {
    private static final String ADMIN_NODE = "wormholes.admin.access";
    private static final int ID_PREFIX_LENGTH = 4;

    @Director(name = "transfer", sync = true, descriptionKey = "access.command.help.transfer", description = "Give a portal to another player")
    public void transfer(@Param(name = "sender", contextual = true) CommandSender sender,
                         @Param(name = "portal", descriptionKey = "access.command.help.transfer.portal", description = "Portal name or id") String portal,
                         @Param(name = "player", descriptionKey = "access.command.help.transfer.player", description = "Name of the new owner") String player) {
        if (!allowed(sender)) {
            return;
        }
        LocalPortal target = requirePortal(sender, portal);
        if (target == null) {
            return;
        }
        UUID newOwner = resolvePlayerId(player);
        if (newOwner == null) {
            send(sender, AccessMessages.PLAYER_NOT_FOUND, args("name", player));
            return;
        }
        UUID previousOwner = target.getOwner();
        target.setOwner(newOwner);
        AccessPortalExtension access = target.extension(AccessPortalExtension.class);
        if (access != null) {
            access.recordTransfer(previousOwner, newOwner);
        }
        target.save();
        Wormholes.i("access: portal " + target.getId() + " transferred to " + newOwner);
        send(sender, AccessMessages.TRANSFER_DONE, args("portal", target.getName(), "name", displayName(newOwner, player)));
    }

    @Director(name = "key", sync = true, descriptionKey = "access.command.help.key", description = "Set the stable permission key of a portal")
    public void key(@Param(name = "sender", contextual = true) CommandSender sender,
                    @Param(name = "portal", descriptionKey = "access.command.help.key.portal", description = "Portal name or id") String portal,
                    @Param(name = "key", descriptionKey = "access.command.help.key.key", description = "New permission key") String key) {
        if (!allowed(sender)) {
            return;
        }
        LocalPortal target = requirePortal(sender, portal);
        if (target == null) {
            return;
        }
        AccessPortalExtension access = target.extension(AccessPortalExtension.class);
        if (access == null) {
            send(sender, AccessMessages.PORTAL_NOT_FOUND, args("portal", portal));
            return;
        }
        String requested = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        switch (access.setPermissionKey(requested)) {
            case INVALID -> send(sender, AccessMessages.KEY_INVALID, MessageArgs.empty());
            case TAKEN -> send(sender, AccessMessages.KEY_TAKEN, args("key", requested));
            case SET, UNCHANGED -> send(sender, AccessMessages.KEY_SET,
                args("portal", target.getName(), "key", access.permissionKey()));
        }
    }

    @Director(name = "limits", sync = true, descriptionKey = "access.command.help.limits", description = "Show how many portals a player owns and may own")
    public void limits(@Param(name = "sender", contextual = true) CommandSender sender,
                       @Param(name = "player", descriptionKey = "access.command.help.limits.player", description = "Player to inspect; defaults to you", defaultValue = "") String player) {
        if (!allowed(sender)) {
            return;
        }
        Player subject = player == null || player.isBlank()
            ? (sender instanceof Player self ? self : null)
            : Bukkit.getPlayerExact(player.trim());
        if (subject == null) {
            send(sender, AccessMessages.PLAYER_NOT_FOUND, args("name", player == null || player.isBlank() ? "" : player.trim()));
            return;
        }
        int configuredDefault = Wormholes.settings == null ? 0 : Wormholes.settings.getAccess().portalLimitDefault;
        int maximum = PortalLimits.maximum(subject, configuredDefault);
        int owned = PortalLimits.owned(subject.getUniqueId());
        if (maximum <= 0) {
            send(sender, AccessMessages.LIMITS_UNLIMITED, args("name", subject.getName(), "count", owned));
            return;
        }
        send(sender, AccessMessages.LIMITS_REPORT, args("name", subject.getName(), "count", owned, "maximum", maximum));
    }

    /**
     * Exact id first, then an exact name, then an id prefix. Two portals sharing a name report as
     * ambiguous so the operator falls back to the id.
     */
    static PortalMatch findPortal(String token, Iterable<? extends ILocalPortal> portals) {
        String requested = token == null ? "" : token.trim();
        if (requested.isEmpty()) {
            return new PortalMatch(null, 0);
        }
        LocalPortal named = null;
        LocalPortal prefixed = null;
        int namedMatches = 0;
        int prefixedMatches = 0;
        for (ILocalPortal candidate : portals) {
            if (!(candidate instanceof LocalPortal local)) {
                continue;
            }
            String id = local.getId().toString();
            if (id.equalsIgnoreCase(requested)) {
                return new PortalMatch(local, 1);
            }
            if (requested.equalsIgnoreCase(local.getName())) {
                named = local;
                namedMatches++;
            } else if (requested.length() >= ID_PREFIX_LENGTH && id.regionMatches(true, 0, requested, 0, requested.length())) {
                prefixed = local;
                prefixedMatches++;
            }
        }
        if (namedMatches > 0) {
            return new PortalMatch(namedMatches == 1 ? named : null, namedMatches);
        }
        return new PortalMatch(prefixedMatches == 1 ? prefixed : null, prefixedMatches);
    }

    private LocalPortal requirePortal(CommandSender sender, String token) {
        PortalManager manager = Wormholes.portalManager;
        List<ILocalPortal> portals = manager == null ? List.of() : manager.getLocalPortals();
        PortalMatch match = findPortal(token, portals);
        if (match.portal() != null) {
            return match.portal();
        }
        if (match.matches() > 1) {
            send(sender, AccessMessages.PORTAL_AMBIGUOUS, args("count", match.matches(), "portal", token));
            return null;
        }
        send(sender, AccessMessages.PORTAL_NOT_FOUND, args("portal", token));
        return null;
    }

    private boolean allowed(CommandSender sender) {
        if (sender.hasPermission(ADMIN_NODE)) {
            return true;
        }
        send(sender, WormholesMessages.COMMAND_NO_PERMISSION, MessageArgs.empty());
        return false;
    }

    private static UUID resolvePlayerId(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Player online = Bukkit.getPlayerExact(name.trim());
        if (online != null) {
            return online.getUniqueId();
        }
        OfflinePlayer cached = WormholesPlatform.offlinePlayerIfCached(name.trim());
        return cached == null ? null : cached.getUniqueId();
    }

    private static String displayName(UUID playerId, String requested) {
        Player online = Bukkit.getPlayer(playerId);
        return online == null ? requested.trim() : online.getName();
    }

    private static void send(CommandSender sender, TextKey message, MessageArgs arguments) {
        WormholesAudience.sendMessage(sender, Wormholes.text().component(sender, message, arguments));
    }

    private static MessageArgs args(Object... nameValuePairs) {
        MessageArgument[] arguments = new MessageArgument[nameValuePairs.length / 2];
        for (int index = 0; index < nameValuePairs.length; index += 2) {
            arguments[index / 2] = MessageArgument.untrusted((String) nameValuePairs[index], nameValuePairs[index + 1]);
        }
        return WormholesLocalization.args(arguments);
    }

    /** A resolved portal, or null with the number of candidates that matched. */
    record PortalMatch(LocalPortal portal, int matches) {
    }
}
