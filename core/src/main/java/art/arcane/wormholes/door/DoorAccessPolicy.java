package art.arcane.wormholes.door;

import java.util.UUID;


public final class DoorAccessPolicy {
    public static final String BYPASS_NODE = "wormholes.doors.bypass";
    public static final String CRAFT_NODE = "wormholes.doors.craft";
    public static final String PLACE_NODE = "wormholes.doors.place";

    private DoorAccessPolicy() {
    }

    public static boolean canUse(DoorAccessRecord record, UUID playerId, boolean bypass) {
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

    public static boolean canManage(DoorAccessRecord record, UUID playerId, boolean administrator) {
        if (administrator) {
            return true;
        }
        return record != null && record.ownerId().equals(playerId);
    }

}
