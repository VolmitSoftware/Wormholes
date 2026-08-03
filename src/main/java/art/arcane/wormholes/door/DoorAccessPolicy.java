package art.arcane.wormholes.door;

import java.util.UUID;

final class DoorAccessPolicy {
    static final String BYPASS_NODE = "wormholes.doors.bypass";

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

    static boolean canManage(DoorAccessRecord record, UUID playerId, boolean administrator) {
        if (administrator) {
            return true;
        }
        return record != null && record.ownerId().equals(playerId);
    }
}
