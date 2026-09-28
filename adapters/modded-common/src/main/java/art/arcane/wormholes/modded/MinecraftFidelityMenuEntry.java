package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.wormholes.localization.FidelityMessages;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Objects;

public final class MinecraftFidelityMenuEntry implements MinecraftPortalMenuEntry {
    private final WormholesModRuntime runtime;

    public MinecraftFidelityMenuEntry(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public String id() {
        return "fidelity";
    }

    @Override
    public Item icon() {
        return Items.SPYGLASS;
    }

    @Override
    public LinesKey label() {
        return FidelityMessages.MENU_ENTRY;
    }

    @Override
    public void onLeftClick(MinecraftPortal portal, ServerPlayer viewer, MinecraftWindow window) {
        window.close();
        runtime.schedule(() -> new MinecraftFidelityMenu(runtime, portal).open(viewer), 1L);
    }
}
