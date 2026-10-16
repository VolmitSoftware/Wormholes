package art.arcane.wormholes.modded.menu;

import art.arcane.wormholes.menu.ButtonElement;
import art.arcane.wormholes.menu.CapturedItem;
import art.arcane.wormholes.menu.ChestClick;
import art.arcane.wormholes.menu.ChestLayout;
import art.arcane.wormholes.menu.ChestPane;
import art.arcane.wormholes.menu.ChestReopen;
import art.arcane.wormholes.menu.ChestTarget;
import art.arcane.wormholes.menu.CycleElement;
import art.arcane.wormholes.menu.ItemCaptureElement;
import art.arcane.wormholes.menu.LabelElement;
import art.arcane.wormholes.menu.ListEntryElement;
import art.arcane.wormholes.menu.LookCaptureElement;
import art.arcane.wormholes.menu.MenuBindings;
import art.arcane.wormholes.menu.MenuBuild;
import art.arcane.wormholes.menu.MenuChestAction;
import art.arcane.wormholes.menu.MenuElement;
import art.arcane.wormholes.menu.MenuElementCommon;
import art.arcane.wormholes.menu.MenuFeedbackStyle;
import art.arcane.wormholes.menu.MenuIcon;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuNotices;
import art.arcane.wormholes.menu.MenuRoute;
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.MenuService;
import art.arcane.wormholes.menu.MenuSlot;
import art.arcane.wormholes.menu.MenuValue;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.NoticeChannel;
import art.arcane.wormholes.menu.NumberElement;
import art.arcane.wormholes.menu.TextElement;
import art.arcane.wormholes.menu.ToggleElement;
import art.arcane.wormholes.modded.MinecraftDyedItems;
import art.arcane.wormholes.modded.MinecraftElement;
import art.arcane.wormholes.modded.MinecraftMenuText;
import art.arcane.wormholes.modded.MinecraftPortalToolsTest;
import art.arcane.wormholes.modded.MinecraftWindow;
import art.arcane.wormholes.modded.WormholesModRuntime;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftChestRendererTest {
    private static final MenuRoute HOME = MenuRoute.of("portal", "portal.home");
    private static final MenuRoute PROFILE = MenuRoute.of("rules", "rules.profile");
    private static final MenuRoute RTP_OVERVIEW = MenuRoute.of("rtp", "rtp.overview");
    private static final MenuRoute RTP_DESTINATION = MenuRoute.of("rtp", "rtp.destination");
    private static final MenuRoute RULE_LIST = MenuRoute.of("rules", "rules.list");
    private static final MenuRoute RULE_PAGE = MenuRoute.of("rules", "rules.rule");
    private static final String TITLE = "§0§lAlpha";

    private final List<Runnable> scheduled = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final Map<String, ItemStack> templates = new HashMap<>();
    private UUID viewerId;
    private WormholesModRuntime runtime;
    private MenuService service;
    private MinecraftMenuCaptures captures;
    private ServerPlayer player;
    private int opened;
    private MenuViewer viewer;
    private MinecraftChestRenderer renderer;

    @BeforeClass
    public static void bootstrap() {
        MinecraftPortalToolsTest.bootstrap();
        for (Item item : List.of(Items.BOOK, Items.ENDER_EYE, Items.GUNPOWDER, Items.REDSTONE_TORCH, Items.PAPER, Items.NAME_TAG,
            Items.BARRIER, Items.COPPER_TORCH, Items.COMPASS, MinecraftDyedItems.pane(DyeColor.GRAY), MinecraftDyedItems.pane(DyeColor.BLUE),
            MinecraftDyedItems.pane(DyeColor.BROWN))) {
            item.builtInRegistryHolder().bindComponents(DataComponents.COMMON_ITEM_COMPONENTS);
        }
    }

    @Before
    public void setUp() {
        viewerId = UUID.randomUUID();
        runtime = mock(WormholesModRuntime.class);
        service = mock(MenuService.class);
        captures = mock(MinecraftMenuCaptures.class);
        when(runtime.menus()).thenReturn(service);
        when(runtime.schedule(any(), anyLong())).thenAnswer(invocation -> scheduled.add(invocation.getArgument(0)));
        player = mock(ServerPlayer.class);
        Inventory inventory = new Inventory(player, new EntityEquipment());
        when(player.getUUID()).thenReturn(viewerId);
        when(player.openMenu(any())).thenAnswer(invocation -> {
            MenuProvider provider = invocation.getArgument(0);
            opened++;
            player.containerMenu = provider.createMenu(opened, inventory, player);
            return OptionalInt.of(opened);
        });
        doAnswer(invocation -> {
            AbstractContainerMenu open = player.containerMenu;
            player.containerMenu = null;
            if (open != null) {
                open.removed(player);
            }
            return null;
        }).when(player).closeContainer();
        viewer = new MinecraftMenuViewer(runtime, player);
        renderer = new MinecraftChestRenderer(runtime, new MinecraftMenuIcons(warnings::add, reference -> Optional.ofNullable(templates.get(reference))),
            captures);
    }

    @Test
    public void elementsMapToTheirItemNameLoreGlintAndCount() {
        ButtonElement button = new ButtonElement(MenuElementCommon.builder("set-destination")
            .label("§bDestination")
            .description(List.of("§7Linked to Beta", ""))
            .chestHints(List.of("§eClick to choose."))
            .icon(MenuIcon.item("minecraft:ender_eye", 3, true))
            .interactions(Set.of(MenuInteraction.PRIMARY))
            .chestSlot(new MenuSlot(-2, 1))
            .build());

        MinecraftElement element = renderer.element(player, button);

        assertEquals("set-destination", element.getId());
        assertSame(Items.ENDER_EYE, element.getMaterial());
        assertEquals("§bDestination", element.getName());
        assertEquals(List.of("§7Linked to Beta", "", "§eClick to choose."), element.getLore());
        assertTrue(element.isEnchanted());
        assertEquals(3, element.getCount());
        assertNull(element.getBaseItemStack());
    }

    @Test
    public void longLoreLinesWrapLikeTodaysMenus() {
        LabelElement label = new LabelElement(MenuElementCommon.builder("placard")
            .label("Portal")
            .description(List.of("§7This description is long enough that it wraps onto a second lore line"))
            .icon(MenuIcon.item("minecraft:book"))
            .chestSlot(new MenuSlot(0, 0))
            .build());

        MinecraftElement element = renderer.element(player, label);

        assertEquals(2, element.getLore().size());
        assertTrue(element.getLore().get(1).startsWith("§7"));
    }

    @Test
    public void spriteIconsRenderAsBarriersAndTemplatesBecomeTheBaseStack() {
        ItemStack template = new ItemStack(Items.DIAMOND);
        template.set(DataComponents.CUSTOM_NAME, Component.literal("Exact toll"));
        templates.put("cost-template", template);
        ButtonElement sprite = new ButtonElement(MenuElementCommon.builder("sprite")
            .icon(MenuIcon.sprite("portal"))
            .chestSlot(new MenuSlot(0, 1))
            .build());
        ButtonElement cost = new ButtonElement(MenuElementCommon.builder("travel-cost")
            .icon(MenuIcon.template("minecraft:diamond", 2, false, "cost-template"))
            .chestSlot(new MenuSlot(1, 1))
            .build());

        assertSame(Items.BARRIER, renderer.element(player, sprite).getMaterial());
        MinecraftElement costElement = renderer.element(player, cost);
        assertSame(Items.DIAMOND, costElement.getMaterial());
        assertSame(template, costElement.getBaseItemStack());
        assertEquals(2, costElement.computeItemStack().getCount());
    }

    @Test
    public void unknownItemsRenderAsBarriersWithOneWarningPerId() {
        ButtonElement unknown = new ButtonElement(MenuElementCommon.builder("unknown")
            .icon(MenuIcon.item("wormholes:not_an_item"))
            .chestSlot(new MenuSlot(0, 1))
            .build());

        assertSame(Items.BARRIER, renderer.element(player, unknown).getMaterial());
        assertSame(Items.BARRIER, renderer.element(player, unknown).getMaterial());

        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("wormholes:not_an_item"));
    }

    @Test
    public void showOpensAWindowWithTheChestLayoutAndPlacesEverySlottedElement() {
        renderer.show(viewer, build(4, ChestPane.GRAY, null, homeElements()), true);

        AbstractContainerMenu menu = player.containerMenu;
        assertEquals(1, opened);
        assertEquals(36 + 36, menu.slots.size());
        assertEquals(TITLE, MinecraftWindow.active(player).getTitle());
        assertTrue(item(4).is(Items.BOOK));
        assertTrue(item(13).is(Items.REDSTONE_TORCH));
        assertTrue(item(13).hasFoil());
        assertTrue(item(31).is(Items.GUNPOWDER));
        assertTrue(item(0).is(MinecraftDyedItems.pane(DyeColor.GRAY)));
        assertEquals(" ", item(0).getHoverName().getString());
    }

    @Test
    public void paneNoneLeavesTheBackgroundEmpty() {
        renderer.show(viewer, build(3, ChestPane.NONE, null, homeElements()), true);

        assertTrue(item(0).isEmpty());
        assertTrue(item(4).is(Items.BOOK));
    }

    @Test
    public void sameTitleAndRowsRepaintInPlaceWhileChangesOpenANewWindow() {
        renderer.show(viewer, build(4, ChestPane.GRAY, null, homeElements()), true);
        AbstractContainerMenu first = player.containerMenu;

        renderer.show(viewer, build(4, ChestPane.GRAY, null, List.of(placard("minecraft:compass"))), false);

        assertEquals(1, opened);
        assertSame(first, player.containerMenu);
        assertTrue(item(4).is(Items.COMPASS));
        assertTrue(item(13).is(MinecraftDyedItems.pane(DyeColor.GRAY)));

        renderer.show(viewer, build(3, ChestPane.BLUE, null, homeElements()), false);

        assertEquals(2, opened);
        assertEquals(27 + 36, player.containerMenu.slots.size());
        assertTrue(item(0).is(MinecraftDyedItems.pane(DyeColor.BLUE)));
    }

    @Test
    public void openingAnotherScreenWithTheSameTitleAndRowsOpensAFreshWindow() {
        renderer.show(viewer, build(4, ChestPane.GRAY, null, homeElements()), true);
        AbstractContainerMenu first = player.containerMenu;

        renderer.show(viewer, screen(PROFILE, null, 4, ChestPane.BLUE, homeElements()), true);

        assertEquals(2, opened);
        assertNotSame(first, player.containerMenu);
        assertTrue(item(0).is(MinecraftDyedItems.pane(DyeColor.BLUE)));
        runScheduled();
        verify(service, never()).chestEscape(viewerId);
    }

    @Test
    public void screensSharingAWindowKeyRepaintTheOpenWindow() {
        renderer.show(viewer, screen(RTP_OVERVIEW, "rtp", 6, ChestPane.BLACK, homeElements()), true);
        AbstractContainerMenu first = player.containerMenu;

        renderer.show(viewer, screen(RTP_DESTINATION, "rtp", 6, ChestPane.BLACK, List.of(placard("minecraft:compass"))), true);

        assertEquals(1, opened);
        assertSame(first, player.containerMenu);
        assertTrue(item(4).is(Items.COMPASS));
        assertFalse(item(13).is(Items.REDSTONE_TORCH));

        renderer.show(viewer, screen(RTP_DESTINATION, "rtp", 5, ChestPane.BLACK, homeElements()), true);
        assertEquals(2, opened);
        renderer.show(viewer, screen(HOME, null, 5, ChestPane.BLACK, homeElements()), true);

        assertEquals(3, opened);
        runScheduled();
        verify(service, never()).chestEscape(viewerId);
    }

    @Test
    public void aSharedWindowTakesTheNextScreensPane() {
        renderer.show(viewer, screen(RULE_LIST, "rules.list", 6, ChestPane.GRAY, homeElements()), true);
        AbstractContainerMenu first = player.containerMenu;

        renderer.show(viewer, screen(RULE_PAGE, "rules.list", 6, ChestPane.BLUE, homeElements()), true);

        assertEquals(1, opened);
        assertSame(first, player.containerMenu);
        assertTrue(item(0).is(MinecraftDyedItems.pane(DyeColor.BLUE)));
    }

    @Test
    public void everyElementKindUsesItsDefaultChestClickMap() {
        Set<MenuInteraction> press = Set.of(MenuInteraction.PRIMARY, MenuInteraction.SECONDARY, MenuInteraction.TERTIARY);
        Set<MenuInteraction> steps = Set.of(MenuInteraction.INCREMENT, MenuInteraction.DECREMENT, MenuInteraction.BIG_INCREMENT,
            MenuInteraction.BIG_DECREMENT);
        List<MenuElement> elements = List.of(
            new ButtonElement(common("button", -4, 1, press)),
            new ToggleElement(common("toggle", -3, 1, press), true),
            new CycleElement(common("cycle", -2, 1, press), List.of("A", "B"), 0),
            new ListEntryElement(common("entry", -1, 1, press), false),
            new NumberElement(common("number", 0, 1, steps), 8, 0, 64, 1, 8, "8"),
            new TextElement(common("text", 1, 1, Set.of(MenuInteraction.SET_TEXT, MenuInteraction.SECONDARY, MenuInteraction.TERTIARY)),
                "", 256, "", List.of("Type."), ChestReopen.ALWAYS, null),
            new ItemCaptureElement(common("item", 2, 1, Set.of(MenuInteraction.PRIMARY, MenuInteraction.SECONDARY)), List.of("Drop the item.")),
            new LookCaptureElement(common("look", 3, 1, Set.of(MenuInteraction.PRIMARY))),
            new LabelElement(MenuElementCommon.builder("label").icon(MenuIcon.item("minecraft:book")).chestSlot(new MenuSlot(4, 1)).build()));
        renderer.show(viewer, build(3, ChestPane.GRAY, null, elements), true);
        Map<String, List<MenuInteraction>> received = new HashMap<>();
        doAnswer(invocation -> {
            MenuChestAction action = invocation.getArgument(1);
            received.computeIfAbsent(action.elementId(), ignored -> new ArrayList<>()).add(action.interaction());
            return null;
        }).when(service).chestAction(eq(viewerId), any(MenuChestAction.class));

        for (int slot = 9; slot <= 17; slot++) {
            for (ChestClick click : ChestClick.values()) {
                click(slot, click);
            }
        }

        List<MenuInteraction> pressOrder = List.of(MenuInteraction.PRIMARY, MenuInteraction.SECONDARY, MenuInteraction.TERTIARY);
        assertEquals(pressOrder, received.get("button"));
        assertEquals(pressOrder, received.get("toggle"));
        assertEquals(pressOrder, received.get("cycle"));
        assertEquals(pressOrder, received.get("entry"));
        assertEquals(List.of(MenuInteraction.INCREMENT, MenuInteraction.DECREMENT, MenuInteraction.BIG_INCREMENT, MenuInteraction.BIG_DECREMENT),
            received.get("number"));
        assertEquals(List.of(MenuInteraction.SECONDARY, MenuInteraction.TERTIARY), received.get("text"));
        assertEquals(List.of(MenuInteraction.SECONDARY), received.get("item"));
        assertEquals(List.of(MenuInteraction.PRIMARY), received.get("look"));
        assertFalse(received.containsKey("label"));
        verify(service).chestPrecheck(viewerId, "text", MenuInteraction.SET_TEXT);
        verify(service).chestPrecheck(viewerId, "item", MenuInteraction.PRIMARY);
    }

    @Test
    public void leftClicksWaitOneTickLikeTodaysWindows() {
        renderer.show(viewer, build(4, ChestPane.GRAY, null, homeElements()), true);

        player.containerMenu.clicked(13, 0, ContainerInput.PICKUP, player);
        verify(service, never()).chestAction(any(), any());
        runScheduled();

        verify(service).chestAction(viewerId, new MenuChestAction("toggle-projections", MenuInteraction.PRIMARY, null, false, null));
    }

    @Test
    public void overriddenClickMapsDelegateAndCarryThePreconfirmedFlag() {
        ButtonElement mirror = new ButtonElement(MenuElementCommon.builder("mode-mirror")
            .icon(MenuIcon.item("minecraft:copper_torch"))
            .interactions(Set.of(MenuInteraction.PRIMARY, MenuInteraction.SECONDARY, MenuInteraction.TERTIARY))
            .chestSlot(new MenuSlot(4, 1)
                .withClick(ChestClick.RIGHT, ChestTarget.self(MenuInteraction.SECONDARY))
                .withClick(ChestClick.SHIFT_RIGHT, ChestTarget.self(MenuInteraction.TERTIARY))
                .withClick(ChestClick.MIDDLE, ChestTarget.delegate("destroy", MenuInteraction.PRIMARY)))
            .build());
        ButtonElement destroy = new ButtonElement(MenuElementCommon.builder("destroy")
            .icon(MenuIcon.item("minecraft:gunpowder"))
            .interactions(Set.of(MenuInteraction.PRIMARY))
            .chestSlot(new MenuSlot(0, 3).withClick(ChestClick.SHIFT_LEFT, ChestTarget.preconfirmed(MenuInteraction.PRIMARY)))
            .build());
        renderer.show(viewer, build(4, ChestPane.GRAY, null, List.of(mirror, destroy)), true);

        click(17, ChestClick.LEFT);
        click(17, ChestClick.RIGHT);
        click(17, ChestClick.SHIFT_RIGHT);
        click(17, ChestClick.MIDDLE);
        click(31, ChestClick.LEFT);
        click(31, ChestClick.SHIFT_LEFT);

        ArgumentCaptor<MenuChestAction> actions = ArgumentCaptor.forClass(MenuChestAction.class);
        verify(service, times(4)).chestAction(eq(viewerId), actions.capture());
        assertEquals(List.of(
            new MenuChestAction("mode-mirror", MenuInteraction.SECONDARY, null, false, null),
            new MenuChestAction("mode-mirror", MenuInteraction.TERTIARY, null, false, null),
            new MenuChestAction("destroy", MenuInteraction.PRIMARY, null, false, null),
            new MenuChestAction("destroy", MenuInteraction.PRIMARY, null, true, null)), actions.getAllValues());
    }

    @Test
    public void playerClosingTheWindowEscapesOneTickLaterAndOnlyOnce() {
        renderer.show(viewer, build(3, ChestPane.GRAY, HOME, homeElements()), true);

        player.containerMenu.removed(player);
        verify(service, never()).chestEscape(viewerId);
        runScheduled();
        runScheduled();

        verify(service, times(1)).chestEscape(viewerId);
    }

    @Test
    public void replacedWindowsNeverEscape() {
        renderer.show(viewer, build(3, ChestPane.GRAY, HOME, homeElements()), true);
        AbstractContainerMenu first = player.containerMenu;
        renderer.show(viewer, build(4, ChestPane.GRAY, null, homeElements()), true);

        first.removed(player);
        runScheduled();

        verify(service, never()).chestEscape(viewerId);
    }

    @Test
    public void refreshesAfterThePlayerClosedTheWindowDoNotReopenIt() {
        renderer.show(viewer, build(4, ChestPane.GRAY, null, homeElements()), true);
        player.containerMenu.removed(player);
        player.containerMenu = null;

        renderer.show(viewer, build(4, ChestPane.GRAY, null, homeElements()), false);
        runScheduled();

        assertEquals(1, opened);
        verify(service).chestEscape(viewerId);
    }

    @Test
    public void closeShutsTheWindowWithoutEscaping() {
        renderer.show(viewer, build(4, ChestPane.GRAY, null, homeElements()), true);

        renderer.close(viewer);
        runScheduled();

        assertNull(player.containerMenu);
        assertNull(MinecraftWindow.active(player));
        verify(service, never()).chestEscape(viewerId);
    }

    @Test
    public void textPromptsCloseTheWindowAndReopenAfterSubmitWhenAlways() {
        Prompt prompt = startPrompt(ChestReopen.ALWAYS, null);

        assertNull(player.containerMenu);
        prompt.submitted.get().accept("Gamma");

        verify(service).chestAction(viewerId, new MenuChestAction("set-name", MenuInteraction.SET_TEXT, MenuValue.text("Gamma"), false, null));
        assertEquals(2, opened);
        assertTrue(item(20).is(Items.NAME_TAG));
    }

    @Test
    public void refreshesWhilePromptingStayHidden() {
        Prompt prompt = startPrompt(ChestReopen.ALWAYS, null);

        renderer.show(viewer, build(4, ChestPane.GRAY, null, List.of(nameElement(ChestReopen.ALWAYS, null))), false);

        assertEquals(1, opened);
        prompt.cancelled.get().run();
        assertEquals(2, opened);
        verify(service, never()).chestAction(any(), any());
    }

    @Test
    public void reopenOnCancelSkipsTheReopenAfterSubmitAndEndsTheChestSession() {
        Prompt prompt = startPrompt(ChestReopen.ON_CANCEL, null);
        doAnswer(invocation -> {
            renderer.show(viewer, build(3, ChestPane.GRAY, null, homeElements()), false);
            return null;
        }).when(service).chestAction(eq(viewerId), any(MenuChestAction.class));

        prompt.submitted.get().accept("WH1-CODE");

        assertEquals(1, opened);
        verify(service).chestEnd(viewerId);
        verify(service, never()).chestEscape(viewerId);
    }

    @Test
    public void reopenOnCancelReopensTheStartingScreenAfterCancel() {
        Prompt prompt = startPrompt(ChestReopen.ON_CANCEL, null);

        prompt.cancelled.get().run();

        assertEquals(2, opened);
        verify(service, never()).chestEscape(viewerId);
    }

    @Test
    public void neverReopensAfterSubmit() {
        Prompt prompt = startPrompt(ChestReopen.NEVER, null);

        prompt.submitted.get().accept("Lobby");

        assertEquals(1, opened);
        verify(service).chestAction(viewerId, new MenuChestAction("set-name", MenuInteraction.SET_TEXT, MenuValue.text("Lobby"), false, null));
        verify(service).chestEnd(viewerId);
        verify(service, never()).chestEscape(viewerId);
    }

    @Test
    public void navigationDuringASubmitShowsTheNewScreenAndKeepsTheSession() {
        Prompt prompt = startPrompt(ChestReopen.NEVER, null);
        doAnswer(invocation -> {
            renderer.show(viewer, build(3, ChestPane.BLUE, null, homeElements()), true);
            return null;
        }).when(service).chestAction(eq(viewerId), any(MenuChestAction.class));

        prompt.submitted.get().accept("Lobby");

        assertEquals(2, opened);
        assertTrue(item(0).is(MinecraftDyedItems.pane(DyeColor.BLUE)));
        verify(service, never()).chestEnd(viewerId);
        verify(service, never()).chestEscape(viewerId);
    }

    @Test
    public void neverReopensAfterCancel() {
        Prompt prompt = startPrompt(ChestReopen.NEVER, null);

        prompt.cancelled.get().run();

        assertEquals(1, opened);
        verify(service, never()).chestAction(any(), any());
        verify(service).chestEnd(viewerId);
        verify(service, never()).chestEscape(viewerId);
    }

    @Test
    public void cancelRouteOpensThatRouteInsteadOfTheStartingScreen() {
        Prompt prompt = startPrompt(ChestReopen.ALWAYS, PROFILE);

        prompt.cancelled.get().run();

        verify(service).open(viewerId, PROFILE);
        assertEquals(1, opened);
    }

    @Test
    public void openingANewScreenCancelsThePendingCapture() {
        startPrompt(ChestReopen.ALWAYS, null);
        verify(captures, never()).cancel(player);

        renderer.show(viewer, build(4, ChestPane.GRAY, null, homeElements()), true);

        verify(captures).cancel(player);
    }

    @Test
    public void closingWhilePromptingCancelsThePendingCapture() {
        startPrompt(ChestReopen.ALWAYS, null);

        renderer.close(viewer);

        verify(captures).cancel(player);
    }

    @Test
    public void staleOutcomesAfterANewScreenAreIgnored() {
        Prompt prompt = startPrompt(ChestReopen.ALWAYS, null);
        renderer.show(viewer, build(4, ChestPane.GRAY, null, homeElements()), true);

        prompt.submitted.get().accept("Late");

        verify(service, never()).chestAction(any(), any());
        assertEquals(2, opened);
    }

    @Test
    public void deniedPrechecksStartNoPrompt() {
        renderer.show(viewer, build(4, ChestPane.GRAY, null, List.of(nameElement(ChestReopen.ALWAYS, null))), true);

        click(20, ChestClick.LEFT);

        verify(captures, never()).prompt(any(), anyList(), any(), any());
        assertTrue(item(20).is(Items.NAME_TAG));
    }

    @Test
    public void dropCapturesSendTheCapturedItemAndReopen() {
        when(service.chestPrecheck(viewerId, "item", MenuInteraction.PRIMARY)).thenReturn(true);
        AtomicReference<Consumer<CapturedItem>> captured = new AtomicReference<>();
        doAnswer(invocation -> {
            captured.set(invocation.getArgument(2));
            return null;
        }).when(captures).dropItem(any(MenuViewer.class), anyList(), any(), any());
        renderer.show(viewer, build(4, ChestPane.BROWN, null,
            List.of(new ItemCaptureElement(common("item", 0, 1, Set.of(MenuInteraction.PRIMARY)), List.of("Drop the item.")))), true);

        click(13, ChestClick.LEFT);
        assertNull(player.containerMenu);
        verify(captures).dropItem(any(MenuViewer.class), eq(List.of("Drop the item.")), any(), any());
        CapturedItem diamond = mock(CapturedItem.class);
        captured.get().accept(diamond);

        verify(service).chestAction(viewerId, new MenuChestAction("item", MenuInteraction.PRIMARY, null, false, diamond));
        assertEquals(2, opened);
    }

    @Test
    public void emptyHandDropCapturesSubmitWithoutAnItemAndReopen() {
        when(service.chestPrecheck(viewerId, "item", MenuInteraction.PRIMARY)).thenReturn(true);
        AtomicReference<Runnable> cancelled = new AtomicReference<>();
        doAnswer(invocation -> {
            cancelled.set(invocation.getArgument(3));
            return null;
        }).when(captures).dropItem(any(MenuViewer.class), anyList(), any(), any());
        renderer.show(viewer, build(4, ChestPane.BROWN, null,
            List.of(new ItemCaptureElement(common("item", 0, 1, Set.of(MenuInteraction.PRIMARY)), List.of("Drop the item.")))), true);

        click(13, ChestClick.LEFT);
        cancelled.get().run();

        verify(service).chestAction(viewerId, new MenuChestAction("item", MenuInteraction.PRIMARY, null, false, null));
        assertEquals(2, opened);
    }

    @Test
    public void disconnectingClosesTheWindowAndDropsTheView() {
        renderer.show(viewer, build(4, ChestPane.GRAY, HOME, homeElements()), true);

        renderer.disconnected(viewerId);
        runScheduled();

        assertNull(player.containerMenu);
        assertNull(MinecraftWindow.active(player));
        verify(service, never()).chestEscape(viewerId);

        Prompt prompt = startPrompt(ChestReopen.ALWAYS, null);
        renderer.disconnected(viewerId);
        prompt.submitted.get().accept("Late");

        verify(service, never()).chestAction(any(), any());
        assertEquals(2, opened);
    }

    @Test
    public void windowClosesFromDisconnectedPlayersNeverEscape() {
        renderer.show(viewer, build(4, ChestPane.GRAY, HOME, homeElements()), true);
        when(player.hasDisconnected()).thenReturn(true);

        player.containerMenu.removed(player);
        runScheduled();

        verify(service, never()).chestEscape(viewerId);
    }

    @Test
    public void showAndCloseRequireTheServerThread() {
        doThrow(new IllegalStateException("off thread")).when(runtime).requireServerThread();
        MenuBuild home = build(4, ChestPane.GRAY, null, homeElements());

        assertThrows(IllegalStateException.class, () -> renderer.show(viewer, home, true));
        assertThrows(IllegalStateException.class, () -> renderer.close(viewer));
        assertEquals(0, opened);
    }

    @Test
    public void noticesFollowTheirChestChannel() {
        try (MockedStatic<MinecraftMenuText> text = mockStatic(MinecraftMenuText.class)) {
            renderer.notice(viewer, MenuNotices.text("§aSaved", MenuFeedbackStyle.SUCCESS, NoticeChannel.SUCCESS_EFFECT));
            renderer.notice(viewer, MenuNotices.text("§cNo", MenuFeedbackStyle.ERROR, NoticeChannel.FAILURE_EFFECT));
            renderer.notice(viewer, MenuNotices.text("Denied", MenuFeedbackStyle.ERROR, NoticeChannel.HUD));
            renderer.notice(viewer, MenuNotices.text("Exported", MenuFeedbackStyle.INFO, NoticeChannel.CHAT));
            renderer.notice(viewer, MenuNotices.text("Native only", MenuFeedbackStyle.INFO, NoticeChannel.NONE));

            text.verify(() -> MinecraftMenuText.notifySuccess(player, "§aSaved"));
            text.verify(() -> MinecraftMenuText.notifyFailure(player, "§cNo"));
            ArgumentCaptor<Component> hud = ArgumentCaptor.forClass(Component.class);
            text.verify(() -> MinecraftMenuText.notice(eq(player), hud.capture()));
            assertEquals("Denied", hud.getValue().getString());
        }
        ArgumentCaptor<Component> chat = ArgumentCaptor.forClass(Component.class);
        verify(player, times(1)).sendSystemMessage(chat.capture());
        assertEquals("Exported", chat.getValue().getString());
    }

    private Prompt startPrompt(ChestReopen reopen, MenuRoute cancelRoute) {
        when(service.chestPrecheck(viewerId, "set-name", MenuInteraction.SET_TEXT)).thenReturn(true);
        Prompt prompt = new Prompt();
        doAnswer(invocation -> {
            prompt.lines.set(invocation.getArgument(1));
            prompt.submitted.set(invocation.getArgument(2));
            prompt.cancelled.set(invocation.getArgument(3));
            return null;
        }).when(captures).prompt(any(MenuViewer.class), anyList(), any(), any());
        renderer.show(viewer, build(4, ChestPane.GRAY, null, List.of(nameElement(reopen, cancelRoute))), true);
        click(20, ChestClick.LEFT);
        assertEquals(List.of("Type a new name."), prompt.lines.get());
        return prompt;
    }

    private void click(int slot, ChestClick click) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null) {
            return;
        }
        switch (click) {
            case LEFT -> menu.clicked(slot, 0, ContainerInput.PICKUP, player);
            case RIGHT -> menu.clicked(slot, 1, ContainerInput.PICKUP, player);
            case SHIFT_LEFT -> menu.clicked(slot, 0, ContainerInput.QUICK_MOVE, player);
            case SHIFT_RIGHT -> menu.clicked(slot, 1, ContainerInput.QUICK_MOVE, player);
            case MIDDLE -> menu.clicked(slot, 2, ContainerInput.CLONE, player);
        }
        runScheduled();
    }

    private ItemStack item(int slot) {
        return player.containerMenu.getSlot(slot).getItem();
    }

    private void runScheduled() {
        List<Runnable> tasks = List.copyOf(scheduled);
        scheduled.clear();
        for (Runnable task : tasks) {
            task.run();
        }
    }

    private static MenuElementCommon common(String id, int position, int row, Set<MenuInteraction> interactions) {
        return MenuElementCommon.builder(id)
            .label(id)
            .icon(MenuIcon.item("minecraft:paper"))
            .interactions(interactions)
            .chestSlot(new MenuSlot(position, row))
            .build();
    }

    private static TextElement nameElement(ChestReopen reopen, MenuRoute cancelRoute) {
        return new TextElement(MenuElementCommon.builder("set-name")
            .label("Rename")
            .icon(MenuIcon.item("minecraft:name_tag"))
            .interactions(Set.of(MenuInteraction.SET_TEXT))
            .chestSlot(new MenuSlot(-2, 2))
            .build(), "Alpha", 256, "Name", List.of("Type a new name."), reopen, cancelRoute);
    }

    private static LabelElement placard(String itemId) {
        return new LabelElement(MenuElementCommon.builder("portal-placard").label("Alpha").icon(MenuIcon.item(itemId))
            .chestSlot(new MenuSlot(0, 0)).build());
    }

    private static List<MenuElement> homeElements() {
        return List.of(
            placard("minecraft:book"),
            new ToggleElement(MenuElementCommon.builder("toggle-projections").label("Projections")
                .icon(MenuIcon.item("minecraft:redstone_torch", 1, true)).interactions(Set.of(MenuInteraction.PRIMARY))
                .chestSlot(new MenuSlot(0, 1)).build(), true),
            new ButtonElement(MenuElementCommon.builder("destroy").label("Delete").icon(MenuIcon.item("minecraft:gunpowder"))
                .interactions(Set.of(MenuInteraction.PRIMARY))
                .chestSlot(new MenuSlot(0, 3).withClick(ChestClick.SHIFT_LEFT, ChestTarget.preconfirmed(MenuInteraction.PRIMARY))).build()),
            new ButtonElement(MenuElementCommon.builder("native-only").label("Native").icon(MenuIcon.sprite("portal")).build()));
    }

    private static MenuBuild build(int rows, ChestPane pane, MenuRoute escape, List<MenuElement> elements) {
        return new MenuBuild(MenuScreen.builder(HOME.screen()).title("Portal").elements(elements).build(),
            new ChestLayout(TITLE, rows, pane, escape), MenuBindings.builder().build());
    }

    private static MenuBuild screen(MenuRoute route, String windowKey, int rows, ChestPane pane, List<MenuElement> elements) {
        return new MenuBuild(MenuScreen.builder(route.screen()).title("Portal").elements(elements).build(),
            new ChestLayout(TITLE, rows, pane, null, windowKey), MenuBindings.builder().build());
    }

    private static final class Prompt {
        private final AtomicReference<List<String>> lines = new AtomicReference<>();
        private final AtomicReference<Consumer<String>> submitted = new AtomicReference<>();
        private final AtomicReference<Runnable> cancelled = new AtomicReference<>();
    }
}
