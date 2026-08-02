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
        return switch (record.mode()) {
            case UNRESTRICTED -> true;
            case WHITELIST -> record.players().contains(playerId);
            case BLACKLIST -> !record.players().contains(playerId);
        };
    }

    static boolean canManage(DoorAccessRecord record, UUID playerId, boolean administrator) {
        if (administrator) {
            return true;
        }
        return record != null && record.ownerId().equals(playerId);
    }
}
