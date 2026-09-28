package art.arcane.wormholes.modded;

import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftInventoryMenuTest {
    @BeforeClass
    public static void bootstrap() {
        MinecraftPortalToolsTest.bootstrap();
    }

    @Test
    public void everyClickModePreservesDisplayPlayerInventoryAndCursor() {
        ServerPlayer player = mock(ServerPlayer.class);
        Inventory inventory = new Inventory(player, new EntityEquipment());
        List<MinecraftInventoryMenu.Click> actions = new ArrayList<>();
        MinecraftInventoryMenu.open(player, Component.literal("Test"), new MinecraftInventoryMenu.Actions(
            () -> true, menu -> menu.set(0, new ItemStack(Items.BLAZE_ROD, 12)), actions::add));
        ArgumentCaptor<MenuProvider> provider = ArgumentCaptor.forClass(MenuProvider.class);
        verify(player).openMenu(provider.capture());
        MinecraftInventoryMenu menu = (MinecraftInventoryMenu) provider.getValue().createMenu(1, inventory, player);
        player.containerMenu = menu;
        inventory.setItem(0, new ItemStack(Items.EMERALD, 7));
        menu.setCarried(new ItemStack(Items.DIAMOND, 3));
        for (ContainerInput input : ContainerInput.values()) {
            for (int button = 0; button < 9; button++) {
                menu.clicked(0, button, input, player);
                menu.clicked(54, button, input, player);
                menu.clicked(-999, button, input, player);
            }
        }
        assertEquals(4, actions.size());
        when(player.isCreative()).thenReturn(true);
        menu.clicked(0, 2, ContainerInput.CLONE, player);
        assertEquals(5, actions.size());
        assertTrue(actions.getLast().middle());
        assertEquals(2, actions.stream().filter(MinecraftInventoryMenu.Click::shift).count());
        assertTrue(menu.getContainer().getItem(0).is(Items.BLAZE_ROD));
        assertEquals(12, menu.getContainer().getItem(0).getCount());
        assertTrue(inventory.getItem(0).is(Items.EMERALD));
        assertEquals(7, inventory.getItem(0).getCount());
        assertTrue(menu.getCarried().is(Items.DIAMOND));
        assertEquals(3, menu.getCarried().getCount());
        assertTrue(menu.quickMoveStack(player, 0).isEmpty());
    }

    @Test
    public void staleSessionAndForeignPlayerCannotInvokeActions() {
        ServerPlayer player = mock(ServerPlayer.class);
        Inventory inventory = new Inventory(player, new EntityEquipment());
        AtomicBoolean valid = new AtomicBoolean(true);
        List<MinecraftInventoryMenu.Click> actions = new ArrayList<>();
        MinecraftInventoryMenu.open(player, Component.literal("Test"), new MinecraftInventoryMenu.Actions(
            valid::get, menu -> menu.set(0, new ItemStack(Items.STONE)), actions::add));
        ArgumentCaptor<MenuProvider> provider = ArgumentCaptor.forClass(MenuProvider.class);
        verify(player).openMenu(provider.capture());
        MinecraftInventoryMenu menu = (MinecraftInventoryMenu) provider.getValue().createMenu(1, inventory, player);
        menu.clicked(0, 0, ContainerInput.PICKUP, mock(ServerPlayer.class));
        valid.set(false);
        assertFalse(menu.stillValid(player));
        menu.clicked(0, 0, ContainerInput.PICKUP, player);
        assertTrue(actions.isEmpty());
    }
}
