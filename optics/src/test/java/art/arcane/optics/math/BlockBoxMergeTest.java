package art.arcane.optics.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class BlockBoxMergeTest {
    @Test
    void plateShapeKeepsMinimumAndSize() {
        BlockBox box = new BlockBox(-4, 60, 9, 3, 2, 5);
        assertEquals(30L, box.cells());
        assertEquals(-2, box.maxX());
        assertEquals(61, box.maxY());
        assertEquals(13, box.maxZ());
    }

    @Test
    void negativeSizesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new BlockBox(0, 0, 0, -1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new BlockBox(0, 0, 0, 1, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new BlockBox(0, 0, 0, 1, 1, -1));
    }

    @Test
    void spanningIsInclusiveAndCollapsesInvertedRangesToEmpty() {
        assertEquals(new BlockBox(-10, 5, -10, 21, 21, 21), BlockBox.spanning(-10, 5, -10, 10, 25, 10));
        assertSame(BlockBox.EMPTY, BlockBox.spanning(0, 10, 0, 0, 9, 0));
        assertEquals(new BlockBox(3, 3, 3, 1, 1, 1), BlockBox.spanning(3, 3, 3, 3, 3, 3));
    }

    @Test
    void indexWalksZThenYThenXInsideTheBoxOnly() {
        BlockBox box = new BlockBox(10, 20, 30, 2, 3, 4);
        assertEquals(0, box.index(10, 20, 30));
        assertEquals(1, box.index(10, 20, 31));
        assertEquals(4, box.index(10, 21, 30));
        assertEquals(12, box.index(11, 20, 30));
        assertEquals((int) box.cells() - 1, box.index(11, 22, 33));
        assertEquals(-1, box.index(9, 20, 30));
        assertEquals(-1, box.index(12, 20, 30));
        assertEquals(-1, box.index(10, 23, 30));
        assertEquals(-1, box.index(10, 20, 34));
    }

    @Test
    void containsMatchesTheInclusiveViewBoxBounds() {
        BlockBox box = BlockBox.spanning(-16, -64, -16, 31, 319, 31);
        assertTrue(box.contains(-16, -64, -16));
        assertTrue(box.contains(31, 319, 31));
        assertFalse(box.contains(32, 0, 0));
        assertFalse(box.contains(0, -65, 0));
        assertFalse(box.contains(0, 0, -17));
        assertFalse(BlockBox.EMPTY.contains(0, 0, 0));
    }

    @Test
    void centresMatchTheViewBoxCentres() {
        BlockBox box = BlockBox.spanning(-54, 51, 7, 74, 79, 37);
        assertEquals((-54 + 74 + 1) * 0.5D, box.centerX());
        assertEquals((51 + 79 + 1) * 0.5D, box.centerY());
        assertEquals((7 + 37 + 1) * 0.5D, box.centerZ());
    }

    @Test
    void unionCoversBothBoxes() {
        BlockBox first = BlockBox.spanning(0, 0, 0, 15, 15, 15);
        BlockBox second = BlockBox.spanning(32, -8, -16, 47, 7, -1);
        assertEquals(BlockBox.spanning(0, -8, -16, 47, 15, 15), first.union(second));
        assertEquals(first, first.union(BlockBox.EMPTY));
        assertEquals(second, BlockBox.EMPTY.union(second));
    }
}
