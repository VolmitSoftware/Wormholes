package art.arcane.wormholes.modded;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftWindowTest {
    private final List<Runnable> scheduled = new ArrayList<>();
    private final List<MenuProvider> providers = new ArrayList<>();
    private WormholesModRuntime runtime;
    private ServerPlayer player;
    private Inventory inventory;

    @BeforeClass
    public static void bootstrap() {
        MinecraftPortalToolsTest.bootstrap();
        for (Item item : List.of(Items.BOOK, Items.ENDER_EYE, Items.GUNPOWDER, Items.LEVER, Items.STAINED_GLASS_PANE.gray(),
            Items.PAPER, Items.DIRT, Items.COMPASS)) {
            item.builtInRegistryHolder().bindComponents(DataComponents.COMMON_ITEM_COMPONENTS);
        }
    }

    @Before
    public void setUp() {
        runtime = mock(WormholesModRuntime.class);
        when(runtime.schedule(any(), anyLong())).thenAnswer(invocation -> scheduled.add(invocation.getArgument(0)));
        player = mock(ServerPlayer.class);
        inventory = new Inventory(player, new EntityEquipment());
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        when(player.openMenu(any())).thenAnswer(invocation -> {
            MenuProvider provider = invocation.getArgument(0);
            providers.add(provider);
            player.containerMenu = provider.createMenu(providers.size(), inventory, player);
            return OptionalInt.of(providers.size());
        });
        doAnswer(invocation -> {
            player.containerMenu.removed(player);
            player.containerMenu = null;
            return null;
        }).when(player).closeContainer();
    }

    @Test
    public void elementsUseCenteredCoordinatesAndPaneBackground() {
        MinecraftWindow window = new MinecraftWindow(runtime, player);
        window.setTitle("\u00A70\u00A7lHome");
        window.setViewportHeight(4);
        window.setDecorator(Items.STAINED_GLASS_PANE.gray());
        window.setElement(0, 0, new MinecraftElement("placard").setMaterial(Items.BOOK));
        window.setElement(-2, 1, new MinecraftElement("destination").setMaterial(Items.ENDER_EYE).setCount(3));
        window.setElement(4, 3, new MinecraftElement("corner").setMaterial(Items.GUNPOWDER));
        window.setElement(9, 2, new MinecraftElement("clipped").setMaterial(Items.LEVER));
        window.open();

        MinecraftWindowMenu menu = (MinecraftWindowMenu) player.containerMenu;
        assertSame(MenuType.GENERIC_9x4, menu.getType());
        assertEquals(36, menu.size());
        assertTrue(menu.item(4).is(Items.BOOK));
        assertTrue(menu.item(11).is(Items.ENDER_EYE));
        assertEquals(3, menu.item(11).getCount());
        assertTrue(menu.item(35).is(Items.GUNPOWDER));
        assertTrue(menu.item(26).is(Items.LEVER));
        ItemStack pane = menu.item(0);
        assertTrue(pane.is(Items.STAINED_GLASS_PANE.gray()));
        assertEquals(" ", pane.get(DataComponents.CUSTOM_NAME).getString());
    }

    @Test
    public void legacyTextMatchesCraftBukkitConversion() {
        Component title = MinecraftLegacyText.component("\u00A70\u00A7lAlpha\u00A77 -> \u00A77\u00A7lBeta");
        assertEquals("Alpha -> Beta", title.getString());
        Style alpha = title.getSiblings().getFirst().getStyle();
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.BLACK), alpha.getColor());
        assertTrue(alpha.isBold());
        assertFalse(alpha.isItalic());
        Style arrow = title.getSiblings().get(1).getStyle();
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GRAY), arrow.getColor());
        assertFalse(arrow.isBold());
        Style plain = MinecraftLegacyText.component("plain").getSiblings().getFirst().getStyle();
        assertNull(plain.getColor());
        assertFalse(plain.isItalic());
        Style hex = MinecraftLegacyText.component("\u00A7x\u00A7f\u00A7f\u00A78\u00A70\u00A70\u00A70Hex").getSiblings().getFirst().getStyle();
        assertEquals(0xFF8000, hex.getColor().getValue());
    }

    @Test
    public void loreWrapsLikeInventoryGuiElements() {
        MinecraftElement element = new MinecraftElement("lore").setMaterial(Items.PAPER);
        element.setName("\u00A76Name");
        element.addLore("\u00A77one two three four five six seven eight nine ten eleven twelve");
        element.addLore("first\nsecond");
        assertEquals(List.of("\u00A77one two three four five six seven eight", "\u00A77nine ten eleven twelve", "first", "second"),
            element.getLore());
        element.setEnchanted(true);
        ItemStack stack = element.computeItemStack();
        assertEquals("Name", stack.get(DataComponents.CUSTOM_NAME).getString());
        assertEquals(4, stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines().size());
        assertTrue(stack.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE));
        assertTrue(new MinecraftElement("air").computeItemStack().isEmpty());
    }

    @Test
    public void clicksRouteLikeBukkitWindows() {
        List<String> clicks = new ArrayList<>();
        MinecraftWindow window = new MinecraftWindow(runtime, player);
        window.setViewportHeight(1);
        window.setElement(0, 0, new MinecraftElement("target").setMaterial(Items.STONE)
            .onLeftClick(element -> clicks.add("left"))
            .onRightClick(element -> clicks.add("right"))
            .onMiddleClick(element -> clicks.add("middle"))
            .onShiftLeftClick(element -> clicks.add("shift-left"))
            .onShiftRightClick(element -> clicks.add("shift-right")));
        window.open();
        MinecraftWindowMenu menu = (MinecraftWindowMenu) player.containerMenu;
        inventory.setItem(0, new ItemStack(Items.EMERALD, 7));

        menu.clicked(4, 0, ContainerInput.PICKUP, player);
        assertTrue(clicks.isEmpty());
        runScheduled();
        menu.clicked(4, 1, ContainerInput.PICKUP, player);
        menu.clicked(4, 2, ContainerInput.CLONE, player);
        menu.clicked(4, 0, ContainerInput.QUICK_MOVE, player);
        menu.clicked(4, 1, ContainerInput.QUICK_MOVE, player);
        for (ContainerInput input : ContainerInput.values()) {
            menu.clicked(9, 0, input, player);
            menu.clicked(-999, 0, input, player);
            menu.clicked(4, 3, input, player);
        }
        menu.clicked(4, 0, ContainerInput.PICKUP, mock(ServerPlayer.class));
        runScheduled();

        assertEquals(List.of("left", "right", "middle", "shift-left", "shift-right"), clicks);
        assertTrue(menu.item(4).is(Items.STONE));
        assertTrue(inventory.getItem(0).is(Items.EMERALD));
        assertEquals(7, inventory.getItem(0).getCount());
        assertTrue(menu.getCarried().isEmpty());
    }

    @Test
    public void rapidDoubleLeftClickScrollsInsteadOfActivating() {
        List<String> clicks = new ArrayList<>();
        MinecraftWindow window = new MinecraftWindow(runtime, player);
        window.setViewportHeight(1);
        window.setElement(0, 0, new MinecraftElement("top").setMaterial(Items.STONE).onLeftClick(element -> clicks.add("top")));
        window.setElement(0, 1, new MinecraftElement("below").setMaterial(Items.DIRT));
        window.open();
        MinecraftWindowMenu menu = (MinecraftWindowMenu) player.containerMenu;

        menu.clicked(4, 0, ContainerInput.PICKUP, player);
        menu.clicked(4, 0, ContainerInput.PICKUP, player);
        runScheduled();

        assertTrue(clicks.isEmpty());
        assertTrue(menu.item(4).is(Items.DIRT));
    }

    @Test
    public void sameTitleAndHeightReusesTheOpenContainer() {
        List<String> closed = new ArrayList<>();
        MinecraftWindow first = new MinecraftWindow(runtime, player).setTitle("Portal");
        first.setViewportHeight(3).onClosed(window -> closed.add("first"));
        first.setElement(0, 0, new MinecraftElement("one").setMaterial(Items.BOOK));
        first.open();
        MinecraftWindowMenu menu = (MinecraftWindowMenu) player.containerMenu;

        MinecraftWindow second = new MinecraftWindow(runtime, player).setTitle("Portal");
        second.setViewportHeight(3);
        second.setElement(0, 0, new MinecraftElement("two").setMaterial(Items.COMPASS));
        second.open();

        verify(player, times(1)).openMenu(any());
        assertSame(menu, player.containerMenu);
        assertTrue(menu.item(4).is(Items.COMPASS));
        assertFalse(first.isVisible());
        assertSame(second, MinecraftWindow.active(player));

        MinecraftWindow third = new MinecraftWindow(runtime, player).setTitle("Other");
        third.setViewportHeight(3);
        third.open();
        runScheduled();
        verify(player, times(2)).openMenu(any());
        assertTrue(closed.isEmpty());
    }

    @Test
    public void playerCloseSchedulesCallbackButProgrammaticCloseDoesNot() {
        List<String> closed = new ArrayList<>();
        MinecraftWindow window = new MinecraftWindow(runtime, player).onClosed(source -> closed.add("closed"));
        window.open();
        window.close();
        runScheduled();
        assertTrue(closed.isEmpty());

        window.open();
        player.containerMenu.removed(player);
        assertTrue(closed.isEmpty());
        runScheduled();
        assertEquals(List.of("closed"), closed);
        assertFalse(window.isVisible());
    }

    private void runScheduled() {
        List<Runnable> tasks = List.copyOf(scheduled);
        scheduled.clear();
        for (Runnable task : tasks) {
            task.run();
        }
    }
}
