package art.arcane.wormholes.modded;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import java.util.List;

final class MinecraftWindowMenu extends ChestMenu {
    private static final List<MenuType<ChestMenu>> TYPES = List.of(MenuType.GENERIC_9x1, MenuType.GENERIC_9x2,
        MenuType.GENERIC_9x3, MenuType.GENERIC_9x4, MenuType.GENERIC_9x5, MenuType.GENERIC_9x6);

    private final ServerPlayer viewer;
    private final int rows;
    private final String title;
    private MinecraftWindow window;

    MinecraftWindowMenu(int id, Inventory inventory, ServerPlayer viewer, MinecraftWindow window, int rows, String title) {
        super(TYPES.get(rows - 1), id, inventory, new SimpleContainer(rows * 9), rows);
        this.viewer = viewer;
        this.window = window;
        this.rows = rows;
        this.title = title;
    }

    boolean reusableFor(int rows, String title) {
        return this.rows == rows && this.title.equals(title);
    }

    void bind(MinecraftWindow window) {
        this.window = window;
    }

    MinecraftWindow window() {
        return window;
    }

    int size() {
        return rows * 9;
    }

    ItemStack item(int slot) {
        return getContainer().getItem(slot);
    }

    void setItem(int slot, ItemStack item) {
        getContainer().setItem(slot, item);
    }

    @Override
    public void clicked(int slot, int button, ContainerInput input, Player player) {
        if (player != viewer || viewer.containerMenu != this) {
            return;
        }
        window.handleClick(this, slot, button, input);
    }

    @Override
    public boolean stillValid(Player player) {
        return player == viewer && !viewer.hasDisconnected();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (player == viewer) {
            window.handleRemoved(this);
        }
    }
}
