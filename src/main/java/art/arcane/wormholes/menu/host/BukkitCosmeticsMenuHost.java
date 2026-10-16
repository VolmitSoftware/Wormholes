package art.arcane.wormholes.menu.host;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.DyeColor;

import art.arcane.wormholes.menu.cosmetics.CosmeticsMenuHost;
import art.arcane.wormholes.menu.cosmetics.DyeSwatch;

public final class BukkitCosmeticsMenuHost implements CosmeticsMenuHost {
    private final List<DyeSwatch> palette;

    public BukkitCosmeticsMenuHost() {
        DyeColor[] dyes = DyeColor.values();
        List<DyeSwatch> swatches = new ArrayList<>(dyes.length);
        for (DyeColor dye : dyes) {
            String name = dye.name().toLowerCase(Locale.ROOT);
            swatches.add(new DyeSwatch(name, "minecraft:" + name + "_wool", dye.getColor().asRGB()));
        }
        this.palette = List.copyOf(swatches);
    }

    @Override
    public List<DyeSwatch> dyePalette() {
        return palette;
    }
}
