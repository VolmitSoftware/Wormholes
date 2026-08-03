package art.arcane.wormholes.door;

import art.arcane.volmlib.util.inventorygui.UIWindow;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
            DoorAccessMenu.resolveAddition(record(DoorAccessState.WHITELIST), null));
    }

    @Test
    void ownersAreRejectedBecauseTheyAlwaysHaveAccess() {
        for (DoorAccessState state : DoorAccessState.values()) {
            assertEquals(
                DoorAccessMenu.AddResolution.OWNER,
                DoorAccessMenu.resolveAddition(record(state), OWNER),
                state.name());
        }
    }

    @Test
    void alreadyListedPlayersAreNotAddedTwice() {
        for (DoorAccessState state : DoorAccessState.values()) {
            assertEquals(
                DoorAccessMenu.AddResolution.ALREADY_LISTED,
                DoorAccessMenu.resolveAddition(record(state), LISTED),
                state.name());
        }
    }

    @Test
    void unlistedPlayersAreAdded() {
        assertEquals(
            DoorAccessMenu.AddResolution.ADD,
            DoorAccessMenu.resolveAddition(record(DoorAccessState.WHITELIST), STRANGER));
        assertEquals(
            DoorAccessMenu.AddResolution.ADD,
            DoorAccessMenu.resolveAddition(DoorAccessRecord.unrestricted(ITEM, OWNER), STRANGER));
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
    void everyStateRendersItsOwnPaneColour() {
        assertEquals(Material.BLACK_STAINED_GLASS_PANE, DoorAccessMenu.stateIcon(DoorAccessState.NEUTRAL));
        assertEquals(Material.GREEN_STAINED_GLASS_PANE, DoorAccessMenu.stateIcon(DoorAccessState.WHITELIST));
        assertEquals(Material.RED_STAINED_GLASS_PANE, DoorAccessMenu.stateIcon(DoorAccessState.BLACKLIST));
        assertThrows(NullPointerException.class, () -> DoorAccessMenu.stateIcon(null));
    }

    @Test
    void firstEntryLandsUnderTheHeaderAtTheLeftEdge() {
        assertEquals(DoorAccessMenu.HEADER_ROW + 1, DoorAccessMenu.entryRow(0));
        assertEquals(-4, DoorAccessMenu.entryPosition(0));
    }

    @Test
    void entriesFillEachRowLeftToRightBeforeWrapping() {
        for (int index = 0; index < DoorAccessMenu.ROW_WIDTH; index++) {
            assertEquals(1, DoorAccessMenu.entryRow(index), "index " + index);
            assertEquals(index - 4, DoorAccessMenu.entryPosition(index), "index " + index);
        }
        assertEquals(2, DoorAccessMenu.entryRow(DoorAccessMenu.ROW_WIDTH));
        assertEquals(-4, DoorAccessMenu.entryPosition(DoorAccessMenu.ROW_WIDTH));
        assertEquals(3, DoorAccessMenu.entryRow(2 * DoorAccessMenu.ROW_WIDTH));
        assertEquals(4, DoorAccessMenu.entryPosition(2 * DoorAccessMenu.ROW_WIDTH - 1));
    }

    @Test
    void everyEntrySlotIsUniqueAndInsideTheWindow() {
        Set<Integer> occupied = new HashSet<>();
        for (int index = 0; index < 121; index++) {
            int row = DoorAccessMenu.entryRow(index);
            int position = DoorAccessMenu.entryPosition(index);
            assertTrue(row >= 1, "row " + row);
            assertTrue(position >= -4 && position <= 4, "position " + position);
            assertTrue(occupied.add((row * DoorAccessMenu.ROW_WIDTH) + position), "duplicate slot at " + index);
        }
        assertThrows(IllegalArgumentException.class, () -> DoorAccessMenu.entryRow(-1));
        assertThrows(IllegalArgumentException.class, () -> DoorAccessMenu.entryPosition(-1));
    }

    /** Lists deeper than the six-row viewport must stay reachable through the window's scroll clamp. */
    @Test
    void everyEntryRowIsReachableByScrollingEvenPastTheViewportCap() {
        for (int count = 1; count <= 121; count++) {
            int height = DoorAccessMenu.viewportHeight(count);
            int lastRow = DoorAccessMenu.entryRow(count - 1);
            int maxScroll = UIWindow.maxViewportPosition(lastRow, height);
            assertTrue(maxScroll + height - 1 >= lastRow, "count " + count + " strands row " + lastRow);
        }
    }

    @Test
    void viewportGrowsOneRowPerNineEntriesAndStopsAtTheChestHeight() {
        assertEquals(1, DoorAccessMenu.viewportHeight(0));
        assertEquals(1, DoorAccessMenu.viewportHeight(-3));
        assertEquals(2, DoorAccessMenu.viewportHeight(1));
        assertEquals(2, DoorAccessMenu.viewportHeight(9));
        assertEquals(3, DoorAccessMenu.viewportHeight(10));
        assertEquals(6, DoorAccessMenu.viewportHeight(45));
        assertEquals(6, DoorAccessMenu.viewportHeight(400));
    }

    @Test
    void everyRenderedEntryFitsTheViewportItSizes() {
        for (int count = 1; count <= 45; count++) {
            int height = DoorAccessMenu.viewportHeight(count);
            assertTrue(DoorAccessMenu.entryRow(count - 1) < height, "count " + count);
        }
    }

    private static DoorAccessRecord record(DoorAccessState state) {
        return DoorAccessRecord.unrestricted(ITEM, OWNER).withPlayerState(LISTED, state);
    }
}
