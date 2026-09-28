package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.wormholes.localization.RulesMessages;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Objects;

public final class MinecraftRulesMenuEntry implements MinecraftPortalMenuEntry {
    private final WormholesModRuntime runtime;
    private final MinecraftRulesMenus menus;

    public MinecraftRulesMenuEntry(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        menus = new MinecraftRulesMenus(runtime);
    }

    @Override
    public String id() {
        return "rules";
    }

    @Override
    public Item icon() {
        return Items.BOOK;
    }

    @Override
    public LinesKey label() {
        return RulesMessages.MENU_ENTRY;
    }

    @Override
    public boolean enchanted(MinecraftPortal portal, ServerPlayer viewer) {
        return !runtime.rules().document(portal).isInert();
    }

    @Override
    public void onLeftClick(MinecraftPortal portal, ServerPlayer viewer, MinecraftWindow window) {
        window.close();
        viewer.closeContainer();
        runtime.schedule(() -> menus.open(viewer, portal.getId()), 1L);
    }
}
