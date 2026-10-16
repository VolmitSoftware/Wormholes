package art.arcane.wormholes.modded.menu.host;

import art.arcane.wormholes.menu.cosmetics.DyeSwatch;
import art.arcane.wormholes.modded.MinecraftTestBase;
import net.minecraft.world.item.DyeColor;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class MinecraftCosmeticsMenuHostTest extends MinecraftTestBase {
    @Test
    public void paletteListsEveryDyeInDeclarationOrderWithItsWoolAndColour() {
        List<DyeSwatch> palette = new MinecraftCosmeticsMenuHost().dyePalette();

        assertEquals(DyeColor.values().length, palette.size());
        assertEquals(new DyeSwatch("white", "minecraft:white_wool", 0xF9FFFE), palette.getFirst());
        assertEquals(new DyeSwatch("light_blue", "minecraft:light_blue_wool", 0x3AB3DA), palette.get(DyeColor.LIGHT_BLUE.ordinal()));
        assertEquals(new DyeSwatch("light_gray", "minecraft:light_gray_wool", 0x9D9D97), palette.get(DyeColor.LIGHT_GRAY.ordinal()));
        assertEquals(new DyeSwatch("black", "minecraft:black_wool", 0x1D1D21), palette.getLast());
    }

    @Test
    public void paletteIsBuiltOnce() {
        MinecraftCosmeticsMenuHost host = new MinecraftCosmeticsMenuHost();

        assertSame(host.dyePalette(), host.dyePalette());
    }
}
