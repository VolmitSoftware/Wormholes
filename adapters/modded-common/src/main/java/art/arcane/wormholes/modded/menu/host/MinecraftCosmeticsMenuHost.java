package art.arcane.wormholes.modded.menu.host;

import art.arcane.wormholes.menu.cosmetics.CosmeticsMenuHost;
import art.arcane.wormholes.menu.cosmetics.DyeSwatch;
import art.arcane.wormholes.modded.MinecraftDyedItems;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.DyeColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MinecraftCosmeticsMenuHost implements CosmeticsMenuHost {
    private final List<DyeSwatch> palette;

    public MinecraftCosmeticsMenuHost() {
        DyeColor[] dyes = DyeColor.values();
        List<DyeSwatch> swatches = new ArrayList<>(dyes.length);
        for (DyeColor dye : dyes) {
            swatches.add(new DyeSwatch(dye.name().toLowerCase(Locale.ROOT), BuiltInRegistries.ITEM.getKey(MinecraftDyedItems.wool(dye)).toString(),
                dye.getTextureDiffuseColor() & 0xFFFFFF));
        }
        this.palette = List.copyOf(swatches);
    }

    @Override
    public List<DyeSwatch> dyePalette() {
        return palette;
    }
}
