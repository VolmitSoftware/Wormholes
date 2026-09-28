package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.wormholes.localization.RulesMessages;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Objects;

public final class MinecraftRulesMenuEntry implements MinecraftPortalMenuEntry {
    private final WormholesModRuntime runtime;

    public MinecraftRulesMenuEntry(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
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
    public void onLeftClick(MinecraftPortal portal, ServerPlayer viewer, MinecraftWindow window) {
        window.close();
    }
}
