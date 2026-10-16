package art.arcane.wormholes.menu.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import org.bukkit.DyeColor;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.menu.cosmetics.DyeSwatch;

class BukkitCosmeticsMenuHostTest {
    @Test
    void paletteListsEveryDyeInDeclarationOrderWithItsWoolAndColour() {
        List<DyeSwatch> palette = new BukkitCosmeticsMenuHost().dyePalette();

        assertEquals(DyeColor.values().length, palette.size());
        assertEquals(new DyeSwatch("white", "minecraft:white_wool", 0xF9FFFE), palette.getFirst());
        assertEquals(new DyeSwatch("light_blue", "minecraft:light_blue_wool", 0x3AB3DA), palette.get(DyeColor.LIGHT_BLUE.ordinal()));
        assertEquals(new DyeSwatch("light_gray", "minecraft:light_gray_wool", 0x9D9D97), palette.get(DyeColor.LIGHT_GRAY.ordinal()));
        assertEquals(new DyeSwatch("black", "minecraft:black_wool", 0x1D1D21), palette.getLast());
    }

    @Test
    void paletteIsBuiltOnce() {
        BukkitCosmeticsMenuHost host = new BukkitCosmeticsMenuHost();

        assertSame(host.dyePalette(), host.dyePalette());
    }
}
