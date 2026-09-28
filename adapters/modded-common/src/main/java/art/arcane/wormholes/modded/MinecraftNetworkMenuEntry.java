package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.wormholes.localization.NexusMessages;
import art.arcane.wormholes.nexus.PortalNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Objects;
import java.util.UUID;

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
    public MessageArgs arguments(MinecraftPortal portal, ServerPlayer viewer) {
        UUID networkId = MinecraftNexusMenus.networkId(portal);
        PortalNetwork network = networkId == null ? null : runtime.nexus().networks().byId(networkId);
        return MinecraftPortalText.arguments(
            "name", network == null ? "" : network.name(),
            "address", MinecraftNexusMenus.address(portal));
    }

    @Override
    public boolean enchanted(MinecraftPortal portal, ServerPlayer viewer) {
        return MinecraftNexusMenus.networkId(portal) != null && !MinecraftNexusMenus.address(portal).isEmpty();
    }

    @Override
    public void onLeftClick(MinecraftPortal portal, ServerPlayer viewer, MinecraftWindow window) {
        window.close();
        runtime.nexus().menus().openManagement(viewer, portal);
    }
}
