package art.arcane.wormholes.access;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.LocalPortal;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * The per-portal admission every path shares: DENIED refuses outright, one trusted role turns the portal
 * into a whitelist, an allowed permission group grants, and the portal's permission node is read in the
 * portal's own mode. The traversal gate runs this before its land-claim check, the frame gate runs it for
 * the arrivals no gate chain sees, and the dial gestures run it so an in-world gesture can never do what
 * travel cannot.
 */
public final class PortalAdmission {
    private PortalAdmission() {
    }

    /** Whether the name-derived node is still honoured as an alias for the stable key node. */
    public static boolean legacyNameNodeAlias() {
        return Wormholes.settings == null || Wormholes.settings.getAccess().legacyNameNodeEnabled;
    }

    /** Role, group and permission-node admission. The land-claim check belongs to the gate alone. */
    public static boolean allows(LocalPortal portal, Player player) {
        if (player.isOp()) {
            return true;
        }
        AccessPortalExtension access = portal.extension(AccessPortalExtension.class);
        if (access == null) {
            return true;
        }
        UUID playerId = player.getUniqueId();
        PortalRole role = access.role(playerId);
        if (role == PortalRole.DENIED) {
            return false;
        }
        if ((role != null && role.trusted()) || playerId.equals(portal.getOwner()) || grantedByGroup(player, access)) {
            return true;
        }
        if (access.whitelistOnly()) {
            return false;
        }
        return permissionAllows(portal, player);
    }

    /**
     * The portal's permission node read in the portal's own mode. The name-derived node is an alias for
     * the stable key, never a second node to hold: a whitelist grants on either, a blacklist refuses on
     * either. With {@code [access] legacy-name-node-enabled} off the stable key is the only node.
     */
    public static boolean permissionAllows(LocalPortal portal, Player player) {
        AccessPortalExtension access = portal.extension(AccessPortalExtension.class);
        boolean alias = legacyNameNodeAlias();
        String nameNode = PermissionKeys.node(PermissionKeys.sanitize(portal.getName()));
        if (access == null) {
            return !alias || portal.getPermissionMode().allows(player, nameNode);
        }
        String keyNode = access.permissionNode();
        if (!alias || keyNode.equals(nameNode)) {
            return portal.getPermissionMode().allows(player, keyNode);
        }
        return portal.getPermissionMode().allowsAny(player, keyNode, nameNode);
    }

    static boolean grantedByGroup(Player player, AccessPortalExtension access) {
        for (String node : access.groups()) {
            if (player.hasPermission(node)) {
                return true;
            }
        }
        return false;
    }
}
