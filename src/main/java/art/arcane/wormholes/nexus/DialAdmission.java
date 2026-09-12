package art.arcane.wormholes.nexus;

import art.arcane.wormholes.access.PortalAdmission;
import art.arcane.wormholes.portal.LocalPortal;
import org.bukkit.entity.Player;

/**
 * Whether a player may repoint or enumerate a portal's network from in-world. Dialing is portal state
 * every other player sees, so a gesture runs the same admission travel runs - a DENIED role, a whitelist
 * the player is not on, or the portal's permission node all refuse it - and a network that is not PUBLIC
 * is neither dialable nor enumerable by someone who is not on its roster.
 */
final class DialAdmission {
    private DialAdmission() {
    }

    static boolean allows(LocalPortal portal, PortalNetwork network, Player player) {
        if (network == null) {
            return false;
        }
        boolean administrator = player.isOp() || player.hasPermission("wormholes.admin");
        if (!administrator && !PortalAdmission.allows(portal, player)) {
            return false;
        }
        return network.visibleTo(player.getUniqueId(), administrator);
    }
}
