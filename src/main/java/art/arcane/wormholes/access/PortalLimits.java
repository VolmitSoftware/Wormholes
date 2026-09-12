package art.arcane.wormholes.access;

import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * How many frame portals one player may own. {@code wormholes.limit.<n>} raises the cap and the
 * highest node a player holds wins; without one the configured default applies. A limit of 0 means
 * unlimited, and {@code wormholes.admin} is never capped.
 */
public final class PortalLimits {
    public static final String LIMIT_NODE_PREFIX = "wormholes.limit.";
    private static final String ADMIN_NODE = "wormholes.admin";

    private PortalLimits() {
    }

    /** The cap for this player, or 0 for unlimited. */
    public static int maximum(Player player, int configuredDefault) {
        if (player == null || player.isOp() || player.hasPermission(ADMIN_NODE)) {
            return 0;
        }
        int fromNodes = highestLimitNode(grantedNodes(player));
        return fromNodes >= 0 ? fromNodes : Math.max(0, configuredDefault);
    }

    /** How many more portals the player may build, or -1 when they are unlimited. */
    public static int remaining(Player player, int configuredDefault) {
        return remaining(maximum(player, configuredDefault), player == null ? null : player.getUniqueId(), loadedPortals());
    }

    static int remaining(int maximum, UUID ownerId, Iterable<? extends ILocalPortal> portals) {
        if (maximum <= 0) {
            return -1;
        }
        return Math.max(0, maximum - owned(ownerId, portals));
    }

    public static int owned(UUID ownerId) {
        return owned(ownerId, loadedPortals());
    }

    /** A portal owned by its own id belongs to the server, not to a player. */
    static int owned(UUID ownerId, Iterable<? extends ILocalPortal> portals) {
        if (ownerId == null) {
            return 0;
        }
        int owned = 0;
        for (ILocalPortal portal : portals) {
            if (!(portal instanceof LocalPortal local)) {
                continue;
            }
            UUID owner = local.getOwner();
            if (ownerId.equals(owner) && !ownerId.equals(local.getId())) {
                owned++;
            }
        }
        return owned;
    }

    /** -1 when the player holds no usable {@code wormholes.limit.<n>} node. */
    static int highestLimitNode(Collection<String> nodes) {
        int highest = -1;
        for (String node : nodes) {
            if (node == null || !node.startsWith(LIMIT_NODE_PREFIX)) {
                continue;
            }
            String tail = node.substring(LIMIT_NODE_PREFIX.length());
            if (tail.isEmpty() || tail.length() > 9) {
                continue;
            }
            int value = 0;
            boolean numeric = true;
            for (int index = 0; index < tail.length(); index++) {
                char digit = tail.charAt(index);
                if (digit < '0' || digit > '9') {
                    numeric = false;
                    break;
                }
                value = value * 10 + (digit - '0');
            }
            if (numeric && value > highest) {
                highest = value;
            }
        }
        return highest;
    }

    private static List<String> grantedNodes(Player player) {
        List<String> nodes = new ArrayList<>();
        for (PermissionAttachmentInfo granted : player.getEffectivePermissions()) {
            if (granted.getValue()) {
                nodes.add(granted.getPermission());
            }
        }
        return nodes;
    }

    private static List<ILocalPortal> loadedPortals() {
        PortalManager manager = Wormholes.portalManager;
        return manager == null ? List.of() : manager.getLocalPortals();
    }
}
