package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.wormholes.localization.AccessMessages;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Objects;

public final class MinecraftAccessMenuEntry implements MinecraftPortalMenuEntry {
    private final WormholesModRuntime runtime;

    public MinecraftAccessMenuEntry(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public String id() {
        return "access";
    }

    @Override
    public Item icon() {
        return Items.NAME_TAG;
    }

    @Override
    public LinesKey label() {
        return AccessMessages.MENU_ENTRY;
    }

    @Override
    public void onLeftClick(MinecraftPortal portal, ServerPlayer viewer, MinecraftWindow window) {
        window.close();
    }
}
