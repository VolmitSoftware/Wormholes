package art.arcane.wormholes.door;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PocketRoomsTest {
    private static final int MAX_ROOMS = 9;

    @Test
    void theBaseRoomIsIndexZeroAtTheSlotCentre() {
        PocketRoom base = PocketRooms.baseRoom();

        assertEquals(0, base.index());
        assertEquals(0, base.offsetX());
        assertEquals(0, base.offsetZ());
    }

    @Test
    void aRoomIsAllocatedThroughTheWallTheDoorWasPlacedOn() {
        PocketSpace space = space(PocketShell.defaults());

        PocketRoom north = PocketRooms.allocate(space, BlockFace.NORTH, MAX_ROOMS).orElseThrow();
        assertEquals(0, north.offsetX());
        assertEquals(-PocketRooms.ROOM_STRIDE, north.offsetZ());

        PocketRoom east = PocketRooms.allocate(space.withRooms(List.of(north)), BlockFace.EAST, MAX_ROOMS)
            .orElseThrow();
        assertEquals(PocketRooms.ROOM_STRIDE, east.offsetX());
        assertEquals(0, east.offsetZ());
    }

    @Test
    void aTakenNeighbourFallsBackToAnotherFreeCellInsteadOfOverlapping() {
        PocketSpace space = space(PocketShell.defaults());
        PocketRoom first = PocketRooms.allocate(space, BlockFace.SOUTH, MAX_ROOMS).orElseThrow();

        PocketRoom second = PocketRooms.allocate(space.withRooms(List.of(first)), BlockFace.SOUTH, MAX_ROOMS)
            .orElseThrow();

        assertFalse(first.offsetX() == second.offsetX() && first.offsetZ() == second.offsetZ());
        assertEquals(2, second.index());
    }

    @Test
    void everyRoomOfOnePocketGetsItsOwnCellAndIndex() {
        PocketSpace space = space(PocketShell.defaults());
        List<PocketRoom> rooms = new ArrayList<>();
        Set<String> offsets = new HashSet<>();
        Set<Integer> indexes = new HashSet<>();
        offsets.add(offsetKey(PocketRooms.baseRoom()));
        indexes.add(0);

        for (int allocated = 1; allocated < MAX_ROOMS; allocated++) {
            PocketRoom room = PocketRooms.allocate(space.withRooms(rooms), BlockFace.NORTH, MAX_ROOMS).orElseThrow();
            assertTrue(offsets.add(offsetKey(room)), "duplicate cell at room " + allocated);
            assertTrue(indexes.add(room.index()), "duplicate index at room " + allocated);
            rooms = new ArrayList<>(rooms);
            rooms.add(room);
        }

        assertEquals(MAX_ROOMS, offsets.size());
        assertEquals(Optional.empty(), PocketRooms.allocate(space.withRooms(rooms), BlockFace.NORTH, MAX_ROOMS));
    }

    @Test
    void theConfiguredRoomCapIsHonouredEvenBelowTheGridSize() {
        PocketSpace space = space(PocketShell.defaults());
        PocketRoom first = PocketRooms.allocate(space, BlockFace.WEST, 2).orElseThrow();

        assertEquals(Optional.empty(),
            PocketRooms.allocate(space.withRooms(List.of(first)), BlockFace.WEST, 2));
        assertEquals(Optional.empty(), PocketRooms.allocate(space, BlockFace.WEST, 1),
            "a cap of one leaves room for the base room only");
    }

    @Test
    void noRoomOfTheLargestPocketEscapesItsOwnSlot() {
        PocketSpace space = space(PocketShell.defaults().withSize(PocketShell.MAX_SIZE));
        int halfSlot = PocketAllocator.DEFAULT_STRIDE / 2;
        List<PocketRoom> rooms = new ArrayList<>();

        for (int allocated = 1; allocated < MAX_ROOMS; allocated++) {
            PocketRoom room = PocketRooms.allocate(space.withRooms(rooms), BlockFace.NORTH, MAX_ROOMS).orElseThrow();
            PocketLayout layout = PocketRooms.layout(space, room);
            assertTrue(Math.abs(layout.minX() - space.centerX()) < halfSlot, "room " + room.index() + " min x");
            assertTrue(Math.abs(layout.maxX() - space.centerX()) < halfSlot, "room " + room.index() + " max x");
            assertTrue(Math.abs(layout.minZ() - space.centerZ()) < halfSlot, "room " + room.index() + " min z");
            assertTrue(Math.abs(layout.maxZ() - space.centerZ()) < halfSlot, "room " + room.index() + " max z");
            rooms = new ArrayList<>(rooms);
            rooms.add(room);
        }
    }

    @Test
    void roomsOfTheLargestPocketNeverOverlapEachOther() {
        PocketSpace space = space(PocketShell.defaults().withSize(PocketShell.MAX_SIZE));
        List<PocketRoom> rooms = new ArrayList<>();
        rooms.add(PocketRooms.baseRoom());
        for (int allocated = 1; allocated < MAX_ROOMS; allocated++) {
            rooms.add(PocketRooms.allocate(space.withRooms(rooms.subList(1, rooms.size())),
                BlockFace.NORTH, MAX_ROOMS).orElseThrow());
        }

        for (int a = 0; a < rooms.size(); a++) {
            for (int b = a + 1; b < rooms.size(); b++) {
                PocketLayout first = PocketRooms.layout(space, rooms.get(a));
                PocketLayout second = PocketRooms.layout(space, rooms.get(b));
                boolean separated = first.maxX() < second.minX() || second.maxX() < first.minX()
                    || first.maxZ() < second.minZ() || second.maxZ() < first.minZ();
                assertTrue(separated, "rooms " + rooms.get(a).index() + " and " + rooms.get(b).index() + " overlap");
            }
        }
    }

    @Test
    void theRoomOccupyingAColumnIsTheOneWhoseCubeContainsIt() {
        PocketSpace space = space(PocketShell.defaults());
        PocketRoom north = PocketRooms.allocate(space, BlockFace.NORTH, MAX_ROOMS).orElseThrow();
        PocketSpace grown = space.withRooms(List.of(north));
        PocketLayout northLayout = PocketRooms.layout(grown, north);

        assertEquals(Optional.of(north), PocketRooms.roomAt(grown, northLayout.minX() + 2, northLayout.minZ() + 2));
        assertEquals(Optional.of(PocketRooms.baseRoom()),
            PocketRooms.roomAt(grown, grown.centerX(), grown.centerZ()));
        assertEquals(Optional.empty(),
            PocketRooms.roomAt(grown, grown.centerX() + PocketAllocator.DEFAULT_STRIDE, grown.centerZ()));
    }

    @Test
    void aPocketDoorInsideAPocketOnlyLeadsBackIntoTheSamePocket() {
        PocketSpace here = space(PocketShell.defaults());
        PocketSpace elsewhere = new PocketSpace(
            PocketAllocator.spaceIdFor(PocketBinding.personal(new UUID(0, 1001))),
            PocketBinding.personal(new UUID(0, 1001)), 1L,
            PocketAllocator.CHUNK_CENTER_OFFSET, PocketAllocator.DEFAULT_CENTER_Y,
            PocketAllocator.CHUNK_CENTER_OFFSET, PocketShell.defaults());

        assertTrue(PocketRooms.allowsPocketEntry(false, null, elsewhere), "outside, every pocket door works");
        assertTrue(PocketRooms.allowsPocketEntry(true, here, here));
        assertFalse(PocketRooms.allowsPocketEntry(true, here, elsewhere), "no pocket inside another pocket");
        assertFalse(PocketRooms.allowsPocketEntry(true, null, here), "the void world outside any pocket");
        assertFalse(PocketRooms.allowsPocketEntry(true, here, null));
    }

    @Test
    void onlyCardinalWallsGrowAPocket() {
        PocketSpace space = space(PocketShell.defaults());

        assertThrows(IllegalArgumentException.class, () -> PocketRooms.allocate(space, BlockFace.UP, MAX_ROOMS));
        assertThrows(NullPointerException.class, () -> PocketRooms.allocate(space, null, MAX_ROOMS));
    }

    private static String offsetKey(PocketRoom room) {
        return room.offsetX() + ":" + room.offsetZ();
    }

    private static PocketSpace space(PocketShell shell) {
        PocketBinding binding = PocketBinding.personal(new UUID(0, 1000));
        return new PocketSpace(
            PocketAllocator.spaceIdFor(binding), binding, 0L,
            PocketAllocator.CHUNK_CENTER_OFFSET, PocketAllocator.DEFAULT_CENTER_Y,
            PocketAllocator.CHUNK_CENTER_OFFSET, shell);
    }
}
