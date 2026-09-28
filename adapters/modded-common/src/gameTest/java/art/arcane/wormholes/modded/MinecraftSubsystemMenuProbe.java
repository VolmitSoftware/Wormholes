package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;

final class MinecraftSubsystemMenuProbe {
    private MinecraftSubsystemMenuProbe() {
    }

    static int slot(int position, int row) {
        return row * 9 + position + 4;
    }

    static ChestMenu window(GameTestHelper helper, ServerPlayer player, String context) {
        helper.assertTrue(player.containerMenu instanceof MinecraftWindowMenu, context + ": no native window is open");
        return (ChestMenu) player.containerMenu;
    }

    static void assertWindow(GameTestHelper helper, ServerPlayer player, String title, int rows, Item pane, int paneSlot, String context) {
        ChestMenu menu = window(helper, player, context);
        helper.assertTrue(menu.getRowCount() == rows, context + ": expected " + rows + " rows but found " + menu.getRowCount());
        MinecraftWindow active = MinecraftWindow.active(player);
        helper.assertTrue(active != null && active.getTitle().equals(title),
            context + ": expected title " + title + " but found " + (active == null ? "none" : active.getTitle()));
        ItemStack background = menu.getSlot(paneSlot).getItem();
        helper.assertTrue(background.is(pane) && name(background).equals(" "),
            context + ": slot " + paneSlot + " is not the pane background but " + background);
    }

    static ItemStack assertElement(GameTestHelper helper, ServerPlayer player, int position, int row, Item item, String expectedName,
                                   boolean glint, String context) {
        ItemStack stack = window(helper, player, context).getSlot(slot(position, row)).getItem();
        helper.assertTrue(stack.is(item), context + ": slot (" + position + "," + row + ") holds " + stack + " instead of " + item);
        helper.assertTrue(name(stack).equals(expectedName),
            context + ": slot (" + position + "," + row + ") is named '" + name(stack) + "' instead of '" + expectedName + "'");
        helper.assertTrue(glint(stack) == glint, context + ": slot (" + position + "," + row + ") glint is " + glint(stack));
        return stack;
    }

    static String name(ServerPlayer player, LinesKey key, MessageArgs arguments) {
        return MinecraftLegacyText.component(MinecraftLegacyText.lines(player, key, arguments).getFirst()).getString();
    }

    static String legacy(ServerPlayer player, TextKey key, MessageArgs arguments) {
        return MinecraftLegacyText.component(MinecraftLegacyText.text(player, key, arguments)).getString();
    }

    static String text(ServerPlayer player, TextKey key, MessageArgs arguments) {
        return MinecraftMenuText.text(player, key, arguments).getString();
    }

    static String name(ItemStack stack) {
        Component name = stack.get(DataComponents.CUSTOM_NAME);
        return name == null ? "" : name.getString();
    }

    static boolean glint(ItemStack stack) {
        return Boolean.TRUE.equals(stack.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE));
    }

    static boolean messaged(List<Component> messages, String expected) {
        for (Component message : messages) {
            if (message.getString().equals(expected)) {
                return true;
            }
        }
        return false;
    }

    static void left(ServerPlayer player, int position, int row) {
        player.containerMenu.clicked(slot(position, row), 0, ContainerInput.PICKUP, player);
    }

    static void right(ServerPlayer player, int position, int row) {
        player.containerMenu.clicked(slot(position, row), 1, ContainerInput.PICKUP, player);
    }

    static void shiftLeft(ServerPlayer player, int position, int row) {
        player.containerMenu.clicked(slot(position, row), 0, ContainerInput.QUICK_MOVE, player);
    }

    static void shiftRight(ServerPlayer player, int position, int row) {
        player.containerMenu.clicked(slot(position, row), 1, ContainerInput.QUICK_MOVE, player);
    }

    static void assertClosed(GameTestHelper helper, ServerPlayer player, String context) {
        helper.assertTrue(player.containerMenu == player.inventoryMenu, context + ": the window stayed open");
    }
}
