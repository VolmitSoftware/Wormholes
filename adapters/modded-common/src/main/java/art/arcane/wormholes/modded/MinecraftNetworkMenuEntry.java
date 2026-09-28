package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.wormholes.localization.NexusMessages;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Objects;

public final class MinecraftNetworkMenuEntry implements MinecraftPortalMenuEntry {
    private final WormholesModRuntime runtime;

    public MinecraftNetworkMenuEntry(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public String id() {
        return "nexus-network";
    }

    @Override
    public Item icon() {
        return Items.COMPASS;
    }

    @Override
    public LinesKey label() {
        return NexusMessages.MENU_ENTRY;
    }

    @Override
    public void onLeftClick(MinecraftPortal portal, ServerPlayer viewer, MinecraftWindow window) {
        window.close();
    }
}
