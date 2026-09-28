package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

public interface MinecraftPortalMenuEntry {
    String id();

    Item icon();

    LinesKey label();

    default MessageArgs arguments(MinecraftPortal portal, ServerPlayer viewer) {
        return MessageArgs.empty();
    }

    default boolean visible(MinecraftPortal portal, ServerPlayer viewer) {
        return true;
    }

    default boolean enchanted(MinecraftPortal portal, ServerPlayer viewer) {
        return false;
    }

    void onLeftClick(MinecraftPortal portal, ServerPlayer viewer, MinecraftWindow window);

    default void onRightClick(MinecraftPortal portal, ServerPlayer viewer, MinecraftWindow window) {
    }

    default void onShiftLeftClick(MinecraftPortal portal, ServerPlayer viewer, MinecraftWindow window) {
    }
}
