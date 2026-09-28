package art.arcane.wormholes.chunk.presend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientChunkWindowTest {
    @Test
    void theClientAcceptanceRadiusIsTheServerViewDistancePlusThreeWithAFloorOfFive() {
        assertEquals(5, ClientChunkWindow.chunkRadiusFor(0));
        assertEquals(5, ClientChunkWindow.chunkRadiusFor(2));
        assertEquals(6, ClientChunkWindow.chunkRadiusFor(3));
        assertEquals(13, ClientChunkWindow.chunkRadiusFor(10));
    }

    @Test
    void acceptanceIsAChebyshevSquareAroundTheAnnouncedCentreAndNotAEuclideanDisc() {
        ClientChunkWindow window = ClientChunkWindow.around(0, 0, 2);

        assertEquals(5, window.chunkRadius());
        assertEquals(11, window.viewRange());
        assertTrue(window.contains(5, 5));
        assertTrue(window.contains(-5, 5));
        assertFalse(window.contains(6, 0));
        assertFalse(window.contains(0, -6));
    }

    @Test
    void coordinatesOneViewRangeApartShareAStorageSlot() {
        ClientChunkWindow window = ClientChunkWindow.around(0, 0, 2);

        assertEquals(window.slotIndex(0, 0), window.slotIndex(11, 0));
        assertEquals(window.slotIndex(0, 0), window.slotIndex(-11, 0));
        assertEquals(window.slotIndex(0, 0), window.slotIndex(0, 22));
        assertNotEquals(window.slotIndex(0, 0), window.slotIndex(1, 0));
    }

    @Test
    void everySlotInAFullWindowIsOccupiedExactlyOnce() {
        ClientChunkWindow window = ClientChunkWindow.around(37, -19, 4);
        int range = window.viewRange();
        boolean[] seen = new boolean[range * range];

        for (int x = window.centerX() - window.chunkRadius(); x <= window.centerX() + window.chunkRadius(); x++) {
            for (int z = window.centerZ() - window.chunkRadius(); z <= window.centerZ() + window.chunkRadius(); z++) {
                int slot = window.slotIndex(x, z);
                assertFalse(seen[slot], "slot " + slot + " was claimed twice inside one window");
                seen[slot] = true;
            }
        }

        for (int slot = 0; slot < seen.length; slot++) {
            assertTrue(seen[slot], "slot " + slot + " was never claimed");
        }
    }

    @Test
    void theOccupantOfADistantCoordinateIsTheWindowChunkThatSharesItsSlot() {
        ClientChunkWindow window = ClientChunkWindow.around(100, 100, 2);

        ChunkCoordinate occupant = window.occupantOf(0, 0);

        assertEquals(new ChunkCoordinate(99, 99), occupant);
        assertEquals(window.slotIndex(0, 0), window.slotIndex(occupant.x(), occupant.z()));
        assertTrue(window.contains(occupant.x(), occupant.z()));
    }

    @Test
    void aCoordinateAlreadyInsideTheWindowIsItsOwnOccupant() {
        ClientChunkWindow window = ClientChunkWindow.around(100, 100, 2);

        assertEquals(new ChunkCoordinate(103, 97), window.occupantOf(103, 97));
        assertEquals(new ChunkCoordinate(95, 105), window.occupantOf(95, 105));
    }

    @Test
    void aNonPositiveRadiusIsRejectedBecauseTheStorageArrayWouldBeEmpty() {
        assertThrows(IllegalArgumentException.class, () -> new ClientChunkWindow(0, 0, 0));
    }
}
