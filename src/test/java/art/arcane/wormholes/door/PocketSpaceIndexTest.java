package art.arcane.wormholes.door;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PocketSpaceIndexTest {
    private final PocketStructureService structures = new PocketStructureService();

    @Test
    void aGrownRoomIsFoundByColumnJustLikeTheBaseRoom() {
        PocketSpace base = space();
        PocketRoom room = PocketRooms.allocate(base, BlockFace.NORTH, 9).orElseThrow();
        PocketSpace grown = base.withRooms(List.of(room));
        PocketLayout roomLayout = PocketRooms.layout(grown, room);
        PocketSpaceIndex index = new PocketSpaceIndex(structures);

        index.index(grown);

        assertSame(grown, index.spaceAt(grown.centerX(), grown.centerZ()));
        assertSame(grown, index.spaceAt(roomLayout.minX() + 2, roomLayout.minZ() + 2));
        assertNull(index.spaceAt(
            grown.centerX() + PocketAllocator.DEFAULT_STRIDE, grown.centerZ()), "another slot is not this pocket");
    }

    @Test
    void aGrownRoomShellIsProtectedTheSameWayTheBaseRoomShellIs() {
        PocketSpace base = space();
        PocketRoom room = PocketRooms.allocate(base, BlockFace.EAST, 9).orElseThrow();
        PocketSpace grown = base.withRooms(List.of(room));
        PocketLayout roomLayout = PocketRooms.layout(grown, room);

        assertTrue(PocketSpaceIndex.isProtectedBlock(grown, structures,
            roomLayout.minX(), roomLayout.minY() + 1, roomLayout.minZ() + 1), "a grown room wall");
        assertFalse(PocketSpaceIndex.isProtectedBlock(grown, structures,
            roomLayout.minX() + 2, roomLayout.minY() + 1, roomLayout.minZ() + 2), "a grown room interior");
        assertTrue(PocketSpaceIndex.isProtectedBlock(grown, structures,
            structures.layout(grown).minX(), structures.layout(grown).minY(), structures.layout(grown).minZ()),
            "the base room floor corner");
    }

    @Test
    void reindexingDropsTheOldRoomsAndPicksUpTheNewOnes() {
        PocketSpace base = space();
        PocketRoom room = PocketRooms.allocate(base, BlockFace.SOUTH, 9).orElseThrow();
        PocketSpace grown = base.withRooms(List.of(room));
        PocketLayout roomLayout = PocketRooms.layout(grown, room);
        PocketSpaceIndex index = new PocketSpaceIndex(structures);
        index.index(grown);

        index.reindex(grown, base);

        assertSame(base, index.spaceAt(base.centerX(), base.centerZ()));
        assertNull(index.spaceAt(roomLayout.minX() + 2, roomLayout.minZ() + 2),
            "the room that went away is no longer claimed");
    }

    private static PocketSpace space() {
        PocketBinding binding = PocketBinding.personal(new UUID(0, 1300));
        return new PocketSpace(
            PocketAllocator.spaceIdFor(binding), binding, 0L,
            PocketAllocator.CHUNK_CENTER_OFFSET, PocketAllocator.DEFAULT_CENTER_Y,
            PocketAllocator.CHUNK_CENTER_OFFSET, PocketShell.defaults());
    }
}
