package art.arcane.wormholes.door;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoorAccessMenuTest {
    private static final UUID ITEM = new UUID(0, 1);
    private static final UUID OWNER = new UUID(0, 2);
    private static final UUID LISTED = new UUID(0, 3);
    private static final UUID STRANGER = new UUID(0, 4);

    @Test
    void unresolvedNamesNeverReachTheAccessList() {
        assertEquals(
            DoorAccessMenu.AddResolution.NOT_FOUND,
            DoorAccessMenu.resolveAddition(record(DoorAccessMode.WHITELIST, List.of(LISTED)), null));
    }

    @Test
    void ownersAreRejectedBecauseTheyAlwaysHaveAccess() {
        for (DoorAccessMode mode : DoorAccessMode.values()) {
            assertEquals(
                DoorAccessMenu.AddResolution.OWNER,
                DoorAccessMenu.resolveAddition(record(mode, List.of(LISTED)), OWNER),
                mode.name());
        }
    }

    @Test
    void alreadyListedPlayersAreNotAddedTwice() {
        assertEquals(
            DoorAccessMenu.AddResolution.ALREADY_LISTED,
            DoorAccessMenu.resolveAddition(record(DoorAccessMode.BLACKLIST, List.of(LISTED)), LISTED));
    }

    @Test
    void unlistedPlayersAreAdded() {
        assertEquals(
            DoorAccessMenu.AddResolution.ADD,
            DoorAccessMenu.resolveAddition(record(DoorAccessMode.WHITELIST, List.of(LISTED)), STRANGER));
        assertEquals(
            DoorAccessMenu.AddResolution.ADD,
            DoorAccessMenu.resolveAddition(record(DoorAccessMode.WHITELIST, List.of()), STRANGER));
    }

    @Test
    void removalOnlyAppliesToListedPlayers() {
        DoorAccessRecord record = record(DoorAccessMode.WHITELIST, List.of(LISTED));

        assertEquals(DoorAccessMenu.RemoveResolution.REMOVE, DoorAccessMenu.resolveRemoval(record, LISTED));
        assertEquals(DoorAccessMenu.RemoveResolution.NOT_LISTED, DoorAccessMenu.resolveRemoval(record, STRANGER));
        assertEquals(DoorAccessMenu.RemoveResolution.NOT_LISTED, DoorAccessMenu.resolveRemoval(record, OWNER));
        assertEquals(DoorAccessMenu.RemoveResolution.NOT_LISTED, DoorAccessMenu.resolveRemoval(record, null));
    }

    @Test
    void unknownPlayerNamesFallBackToAShortenedIdentifier() {
        assertEquals("Astra", DoorAccessMenu.resolveDisplayName("Astra", "fallback"));
        assertEquals("fallback", DoorAccessMenu.resolveDisplayName(null, "fallback"));
        assertEquals("fallback", DoorAccessMenu.resolveDisplayName("   ", "fallback"));
        assertEquals("00000000", DoorAccessMenu.shortId(LISTED));
        assertEquals(8, DoorAccessMenu.shortId(UUID.randomUUID()).length());
    }

    @Test
    void everyModeRendersADistinctIconAndOnlyWhitelistGlints() {
        assertEquals(Material.LEATHER_HELMET, DoorAccessMenu.modeIcon(DoorAccessMode.UNRESTRICTED));
        assertEquals(Material.GOLDEN_HELMET, DoorAccessMenu.modeIcon(DoorAccessMode.WHITELIST));
        assertEquals(Material.IRON_HELMET, DoorAccessMenu.modeIcon(DoorAccessMode.BLACKLIST));
        assertTrue(DoorAccessMenu.modeGlint(DoorAccessMode.WHITELIST));
        assertFalse(DoorAccessMenu.modeGlint(DoorAccessMode.UNRESTRICTED));
        assertFalse(DoorAccessMenu.modeGlint(DoorAccessMode.BLACKLIST));
    }

    private static DoorAccessRecord record(DoorAccessMode mode, List<UUID> players) {
        return new DoorAccessRecord(ITEM, mode, OWNER, players);
    }
}
