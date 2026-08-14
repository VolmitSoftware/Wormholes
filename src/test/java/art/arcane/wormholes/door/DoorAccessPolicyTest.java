package art.arcane.wormholes.door;

import org.junit.jupiter.api.Test;
import org.bukkit.entity.Player;

import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoorAccessPolicyTest {
    private static final UUID ITEM = new UUID(0, 1);
    private static final UUID OWNER = new UUID(0, 2);
    private static final UUID LISTED = new UUID(0, 3);
    private static final UUID OTHER = new UUID(0, 5);
    private static final UUID STRANGER = new UUID(0, 4);

    @Test
    void doorsWithoutARecordAreAlwaysUsable() {
        assertTrue(DoorAccessPolicy.canUse(null, STRANGER, false));
        assertTrue(DoorAccessPolicy.canUse(null, null, false));
    }

    @Test
    void bypassAndOwnershipDefeatEveryState() {
        for (DoorAccessState state : DoorAccessState.values()) {
            DoorAccessRecord record = record(LISTED, state);
            assertTrue(DoorAccessPolicy.canUse(record, STRANGER, true), state.name());
            assertTrue(DoorAccessPolicy.canUse(record, OWNER, false), state.name());
        }
    }

    @Test
    void emptyListsAdmitEveryone() {
        DoorAccessRecord record = DoorAccessRecord.unrestricted(ITEM, OWNER);

        assertTrue(DoorAccessPolicy.canUse(record, LISTED, false));
        assertTrue(DoorAccessPolicy.canUse(record, STRANGER, false));
    }

    @Test
    void neutralEntriesChangeNothingOnTheirOwn() {
        DoorAccessRecord record = record(LISTED, DoorAccessState.NEUTRAL);

        assertTrue(DoorAccessPolicy.canUse(record, LISTED, false));
        assertTrue(DoorAccessPolicy.canUse(record, STRANGER, false));
    }

    @Test
    void blacklistedPlayersAreAlwaysDenied() {
        DoorAccessRecord record = record(LISTED, DoorAccessState.BLACKLIST);

        assertFalse(DoorAccessPolicy.canUse(record, LISTED, false));
        assertTrue(DoorAccessPolicy.canUse(record, STRANGER, false));
        assertFalse(DoorAccessPolicy.canUse(
            record.withPlayerState(OTHER, DoorAccessState.WHITELIST), LISTED, false));
    }

    @Test
    void anyWhitelistEntryLocksOutEveryUnlistedPlayer() {
        DoorAccessRecord record = record(LISTED, DoorAccessState.WHITELIST);

        assertTrue(DoorAccessPolicy.canUse(record, LISTED, false));
        assertFalse(DoorAccessPolicy.canUse(record, STRANGER, false));
        assertFalse(DoorAccessPolicy.canUse(
            record.withPlayerState(OTHER, DoorAccessState.NEUTRAL), OTHER, false));
    }

    @Test
    void ownersPassEvenWhenTheirOwnPaneIsBlacklisted() {
        DoorAccessRecord record = DoorAccessRecord.unrestricted(ITEM, OWNER)
            .withPlayerState(OWNER, DoorAccessState.BLACKLIST);

        assertTrue(DoorAccessPolicy.canUse(record, OWNER, false));
    }

    @Test
    void ownerAsTheSoleWhitelistEntryLocksOutStrangersButNeverTheOwner() {
        DoorAccessRecord record = DoorAccessRecord.unrestricted(ITEM, OWNER)
            .withPlayerState(OWNER, DoorAccessState.WHITELIST);

        assertTrue(DoorAccessPolicy.canUse(record, OWNER, false));
        assertFalse(DoorAccessPolicy.canUse(record, STRANGER, false));
    }

    @Test
    void onlyOwnersAndAdministratorsCanManage() {
        DoorAccessRecord record = record(LISTED, DoorAccessState.WHITELIST);

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

    @Test
    void onlyOperatorsOrPlayersWithTheCraftNodeCanCraftDoors() {
        assertTrue(DoorAccessPolicy.canCraft(player(true, Set.of())));
        assertTrue(DoorAccessPolicy.canCraft(player(false, Set.of("wormholes.admin"))));
        assertTrue(DoorAccessPolicy.canCraft(player(false, Set.of(DoorAccessPolicy.CRAFT_NODE))));
        assertFalse(DoorAccessPolicy.canCraft(player(false, Set.of())));
        assertFalse(DoorAccessPolicy.canCraft(null));
    }

    @Test
    void onlyOperatorsAdministratorsOrPlayersWithThePlaceNodeCanPlaceDoors() {
        assertTrue(DoorAccessPolicy.canPlace(player(true, Set.of())));
        assertTrue(DoorAccessPolicy.canPlace(player(false, Set.of("wormholes.admin"))));
        assertTrue(DoorAccessPolicy.canPlace(player(false, Set.of(DoorAccessPolicy.PLACE_NODE))));
        assertFalse(DoorAccessPolicy.canPlace(player(false, Set.of(DoorAccessPolicy.CRAFT_NODE))));
        assertFalse(DoorAccessPolicy.canPlace(player(false, Set.of())));
        assertFalse(DoorAccessPolicy.canPlace(null));
    }

    private static DoorAccessRecord record(UUID playerId, DoorAccessState state) {
        return DoorAccessRecord.unrestricted(ITEM, OWNER).withPlayerState(playerId, state);
    }

    private static Player player(boolean operator, Set<String> permissions) {
        return (Player) Proxy.newProxyInstance(
            DoorAccessPolicyTest.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "isOp" -> operator;
                case "hasPermission" -> permissions.contains(String.valueOf(arguments[0]));
                case "equals" -> proxy == arguments[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "DoorAccessPolicyTestPlayer";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
