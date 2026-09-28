package art.arcane.wormholes.modded;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class MinecraftInventoryMenu extends ChestMenu {
    private final ServerPlayer viewer;
    private final Actions actions;

    private MinecraftInventoryMenu(int id, Inventory inventory, ServerPlayer viewer, Actions actions) {
        super(MenuType.GENERIC_9x6, id, inventory, new SimpleContainer(54), 6);
        this.viewer = viewer;
        this.actions = actions;
    }

    public static void open(ServerPlayer viewer, Component title, Actions actions) {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(actions, "actions");
        viewer.openMenu(new SimpleMenuProvider((id, inventory, player) -> {
            MinecraftInventoryMenu menu = new MinecraftInventoryMenu(id, inventory, viewer, actions);
            actions.render().accept(menu);
            return menu;
        }, title));
    }

    public void set(int slot, ItemStack item) {
        if (slot < 0 || slot >= 54) {
            throw new IllegalArgumentException("Menu slot outside the six-row inventory");
        }
        getContainer().setItem(slot, item);
    }

    public void refresh() {
        getContainer().clearContent();
        actions.render().accept(this);
        broadcastFullState();
    }

    @Override
    public void clicked(int slot, int button, ContainerInput input, Player player) {
        if (player != viewer || viewer.containerMenu != this || !stillValid(player)) {
            return;
        }
        boolean middle = button == 2 && input == ContainerInput.CLONE && viewer.isCreative();
        boolean primary = (button == 0 || button == 1)
            && (input == ContainerInput.PICKUP || input == ContainerInput.QUICK_MOVE);
        if (slot >= 0 && slot < 54 && (primary || middle)) {
            actions.click().accept(new Click(this, slot, button == 1, input == ContainerInput.QUICK_MOVE, middle));
        }
        if (viewer.containerMenu == this) {
            broadcastFullState();
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return player == viewer && !viewer.hasDisconnected() && actions.valid().getAsBoolean();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    public record Actions(BooleanSupplier valid, Consumer<MinecraftInventoryMenu> render, Consumer<Click> click) {
        public Actions {
            Objects.requireNonNull(valid, "valid");
            Objects.requireNonNull(render, "render");
            Objects.requireNonNull(click, "click");
        }
    }

    public record Click(MinecraftInventoryMenu menu, int slot, boolean right, boolean shift, boolean middle) {
    }
}
