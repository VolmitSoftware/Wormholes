package art.arcane.optics.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

final class SessionPaletteTest {
    @Test
    void reservedIdsAreFixedAndDenseIdsFollowFirstUse() {
        SessionPalette palette = new SessionPalette();
        assertEquals(ViewStreamLimits.PALETTE_AIR, palette.id("minecraft:air"));
        assertEquals(ViewStreamLimits.PALETTE_OCCLUDED, palette.id(SessionPalette.OCCLUDED));
        assertEquals(ViewStreamLimits.PALETTE_BACKING, palette.id(SessionPalette.BACKING));
        assertEquals(3, palette.id("minecraft:stone"));
        assertEquals(4, palette.id("minecraft:dirt"));
        assertEquals(3, palette.id("stone"));
        assertEquals("minecraft:stone", palette.state(3));
        assertEquals(5, palette.size());
        assertEquals(-1, palette.lookup("minecraft:granite"));
    }

    @Test
    void canonicalFormSortsPropertiesAndDefaultsTheNamespace() {
        assertEquals("minecraft:oak_stairs[facing=north,half=top,shape=straight,waterlogged=false]",
            SessionPalette.canonical("oak_stairs[waterlogged=false, shape=straight,half=top,facing=north]"));
        assertEquals("minecraft:stone", SessionPalette.canonical(" minecraft:stone[] "));
        assertEquals("mod:thing[a=1]", SessionPalette.canonical("mod:thing[a=1]"));
        assertThrows(IllegalArgumentException.class, () -> SessionPalette.canonical("minecraft:stone[facing"));
        assertThrows(IllegalArgumentException.class, () -> SessionPalette.canonical(""));
        SessionPalette palette = new SessionPalette();
        int first = palette.id("minecraft:oak_stairs[half=top,facing=north]");
        assertEquals(first, palette.id("minecraft:oak_stairs[facing=north,half=top]"));
    }

    @Test
    void cursorHandsOutOnlyReferencedEntriesNotYetSentToThatSession() {
        SessionPalette palette = new SessionPalette();
        int stone = palette.id("minecraft:stone");
        int dirt = palette.id("minecraft:dirt");
        int hidden = palette.id("minecraft:diamond_ore");
        SessionPalette.Cursor cursor = palette.cursor();
        List<ViewStreamMessage.PaletteEntry> first = cursor.pending(new int[] {ViewStreamLimits.PALETTE_OCCLUDED, stone});
        assertEquals(1, first.size());
        assertEquals(stone, first.get(0).id());
        assertTrue(cursor.pending(new int[] {stone}).isEmpty());
        List<ViewStreamMessage.PaletteEntry> second = cursor.pending(new int[] {stone, dirt});
        assertEquals(1, second.size());
        assertEquals("minecraft:dirt", second.get(0).state());
        assertTrue(cursor.isSent(dirt));
        assertFalse(cursor.isSent(hidden));
        assertEquals(ViewStreamLimits.RESERVED_PALETTE_IDS + 2, cursor.sentCount());
        cursor.reset();
        assertEquals(2, cursor.pending(new int[] {stone, dirt}).size());
        SessionPalette.Cursor other = palette.cursor();
        assertEquals(2, other.pending(new int[] {dirt, stone}).size());
        assertThrows(IllegalArgumentException.class, () -> other.pending(new int[] {999}));
    }
}
