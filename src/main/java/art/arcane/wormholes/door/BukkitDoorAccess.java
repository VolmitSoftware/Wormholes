package art.arcane.wormholes.door;

import org.bukkit.entity.Player;

final class BukkitDoorAccess {
    private BukkitDoorAccess() {
    }

    static boolean canCraft(Player player) {
        return hasCapability(player, DoorAccessPolicy.CRAFT_NODE);
    }

    static boolean canPlace(Player player) {
        return hasCapability(player, DoorAccessPolicy.PLACE_NODE);
    }

    private static boolean hasCapability(Player player, String permission) {
        return player != null && (player.isOp() || player.hasPermission("wormholes.admin") || player.hasPermission(permission));
    }
}
