package art.arcane.wormholes.door;

import java.util.UUID;

import org.bukkit.entity.Player;

final class DoorAccessPolicy {
    private static final String ADMINISTRATOR_NODE = "wormholes.admin";
    static final String BYPASS_NODE = "wormholes.doors.bypass";
    static final String CRAFT_NODE = "wormholes.doors.craft";
    static final String PLACE_NODE = "wormholes.doors.place";

    private DoorAccessPolicy() {
    }

    static boolean canUse(DoorAccessRecord record, UUID playerId, boolean bypass) {
        if (record == null) {
            return true;
        }
        if (bypass) {
            return true;
        }
        if (record.ownerId().equals(playerId)) {
            return true;
        }
        DoorAccessState state = record.stateOf(playerId);
        if (state == DoorAccessState.BLACKLIST) {
            return false;
        }
        if (!record.hasWhitelist()) {
            return true;
        }
        return state == DoorAccessState.WHITELIST;
    }

    static boolean canCraft(Player player) {
        return hasCapability(player, CRAFT_NODE);
    }

    static boolean canPlace(Player player) {
        return hasCapability(player, PLACE_NODE);
    }

    static boolean canManage(DoorAccessRecord record, UUID playerId, boolean administrator) {
        if (administrator) {
            return true;
        }
        return record != null && record.ownerId().equals(playerId);
    }

    private static boolean hasCapability(Player player, String permission) {
        return player != null
            && (player.isOp() || player.hasPermission(ADMINISTRATOR_NODE) || player.hasPermission(permission));
    }
}
