package art.arcane.wormholes.door;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoorAccessPolicyTest {
    private static final UUID ITEM = new UUID(0, 1);
    private static final UUID OWNER = new UUID(0, 2);
    private static final UUID LISTED = new UUID(0, 3);
    private static final UUID STRANGER = new UUID(0, 4);

    @Test
    void doorsWithoutARecordAreAlwaysUsable() {
        assertTrue(DoorAccessPolicy.canUse(null, STRANGER, false));
        assertTrue(DoorAccessPolicy.canUse(null, null, false));
    }

    @Test
    void bypassAndOwnershipDefeatEveryMode() {
        for (DoorAccessMode mode : DoorAccessMode.values()) {
            DoorAccessRecord record = record(mode, List.of(LISTED));
            assertTrue(DoorAccessPolicy.canUse(record, STRANGER, true), mode.name());
            assertTrue(DoorAccessPolicy.canUse(record, OWNER, false), mode.name());
        }
    }

    @Test
    void unrestrictedDoorsAdmitEveryoneRegardlessOfTheList() {
        DoorAccessRecord record = record(DoorAccessMode.UNRESTRICTED, List.of(LISTED));

        assertTrue(DoorAccessPolicy.canUse(record, LISTED, false));
        assertTrue(DoorAccessPolicy.canUse(record, STRANGER, false));
    }

    @Test
    void whitelistAdmitsOnlyListedPlayers() {
        DoorAccessRecord record = record(DoorAccessMode.WHITELIST, List.of(LISTED));

        assertTrue(DoorAccessPolicy.canUse(record, LISTED, false));
        assertFalse(DoorAccessPolicy.canUse(record, STRANGER, false));
        assertFalse(DoorAccessPolicy.canUse(record(DoorAccessMode.WHITELIST, List.of()), LISTED, false));
    }

    @Test
    void blacklistRefusesOnlyListedPlayers() {
        DoorAccessRecord record = record(DoorAccessMode.BLACKLIST, List.of(LISTED));

        assertFalse(DoorAccessPolicy.canUse(record, LISTED, false));
        assertTrue(DoorAccessPolicy.canUse(record, STRANGER, false));
        assertTrue(DoorAccessPolicy.canUse(record(DoorAccessMode.BLACKLIST, List.of()), LISTED, false));
    }

    @Test
    void onlyOwnersAndAdministratorsCanManage() {
        DoorAccessRecord record = record(DoorAccessMode.WHITELIST, List.of(LISTED));

        assertTrue(DoorAccessPolicy.canManage(record, OWNER, false));
        assertFalse(DoorAccessPolicy.canManage(record, LISTED, false));
        assertFalse(DoorAccessPolicy.canManage(record, STRANGER, false));
        assertTrue(DoorAccessPolicy.canManage(record, STRANGER, true));
    }

    @Test
    void recordlessDoorsCanOnlyBeManagedByAdministrators() {
        assertFalse(DoorAccessPolicy.canManage(null, OWNER, false));
        assertTrue(DoorAccessPolicy.canManage(null, OWNER, true));
    }

    private static DoorAccessRecord record(DoorAccessMode mode, List<UUID> players) {
        return new DoorAccessRecord(ITEM, mode, OWNER, players);
    }
}
