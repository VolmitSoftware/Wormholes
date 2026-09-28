package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.wormholes.localization.TransitMessages;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Objects;

public final class MinecraftTransitMenuEntry implements MinecraftPortalMenuEntry {
    private final WormholesModRuntime runtime;

    public MinecraftTransitMenuEntry(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public String id() {
        return "transit";
    }

    @Override
    public Item icon() {
        return Items.FEATHER;
    }

    @Override
    public LinesKey label() {
        return TransitMessages.MENU_ENTRY;
    }

    @Override
    public void onLeftClick(MinecraftPortal portal, ServerPlayer viewer, MinecraftWindow window) {
        window.close();
    }
}
