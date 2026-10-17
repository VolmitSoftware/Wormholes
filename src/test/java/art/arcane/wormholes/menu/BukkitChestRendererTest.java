package art.arcane.wormholes.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import art.arcane.volmlib.util.scheduling.Callback;
import io.papermc.paper.registry.RegistryAccess;

import art.arcane.volmlib.util.inventorygui.Element;
import art.arcane.volmlib.util.inventorygui.ElementEvent;
import art.arcane.volmlib.util.inventorygui.UIElement;
import art.arcane.volmlib.util.inventorygui.UIPaneDecorator;
import art.arcane.volmlib.util.inventorygui.UIWindow;
import art.arcane.volmlib.util.inventorygui.Window;
import art.arcane.wormholes.EffectManager;
import art.arcane.wormholes.TestRegistryAccess;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.menu.protocol.MenuServerInfo;
import art.arcane.wormholes.service.WormholesHud;

class BukkitChestRendererTest {
    private static final MenuRoute HOME = MenuRoute.of("portal", "portal.home");
    private static final MenuRoute PROFILE = MenuRoute.of("rules", "rules.profile");
    private static final MenuRoute RTP_OVERVIEW = MenuRoute.of("rtp", "rtp.overview");
    private static final MenuRoute RTP_DESTINATION = MenuRoute.of("rtp", "rtp.destination");
    private static final MenuRoute RULE_LIST = MenuRoute.of("rules", "rules.list");
    private static final MenuRoute RULE_PAGE = MenuRoute.of("rules", "rules.rule");

    private final UUID viewerId = UUID.randomUUID();
    private final Map<String, ItemStack> templates = new HashMap<>();
    private Player player;
    private BukkitMenuViewer viewer;
    private MenuServiceApi service;
    private MenuCaptures captures;
    private BukkitChestRenderer renderer;
    private MockedConstruction<UIWindow> windows;
    private MockedStatic<RegistryAccess> registries;
    private final List<WindowState> states = new ArrayList<>();

    @BeforeEach
    void setUp() {
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(viewerId);
        when(player.getName()).thenReturn("Viewer");
        when(player.isOnline()).thenReturn(true);
        viewer = new BukkitMenuViewer(player);
        service = mock(MenuServiceApi.class);
        captures = mock(MenuCaptures.class);
        BukkitMenuIcons icons = new BukkitMenuIcons(Logger.getAnonymousLogger(), ref -> Optional.ofNullable(templates.get(ref)), material -> true);
        renderer = new BukkitChestRenderer(mock(JavaPlugin.class), icons, captures);
        renderer.start(service);
        registries = mockStatic(RegistryAccess.class);
        registries.when(RegistryAccess::registryAccess).thenReturn(new TestRegistryAccess());
        windows = mockConstruction(UIWindow.class, withSettings().defaultAnswer(RETURNS_SELF), (window, context) -> {
            WindowState state = new WindowState();
            states.add(state);
            doAnswer(invocation -> {
                ((Runnable) invocation.getArgument(0)).run();
                return window;
            }).when(window).batch(any(Runnable.class));
            doAnswer(invocation -> {
                state.elements.put(invocation.getArgument(1, Integer.class) * 9 + 4 + invocation.getArgument(0, Integer.class),
                    invocation.getArgument(2, Element.class));
                return window;
            }).when(window).setElement(anyInt(), anyInt(), any(Element.class));
            doAnswer(invocation -> {
                state.elements.clear();
                return window;
            }).when(window).clearElements();
            doAnswer(invocation -> {
                state.title = invocation.getArgument(0);
                return window;
            }).when(window).setTitle(anyString());
            doAnswer(invocation -> state.title).when(window).getTitle();
            doAnswer(invocation -> {
                state.rows = invocation.getArgument(0);
                return window;
            }).when(window).setViewportHeight(anyInt());
            doAnswer(invocation -> state.rows).when(window).getViewportHeight();
            doAnswer(invocation -> {
                state.decorated = true;
                return window;
            }).when(window).setDecorator(any(UIPaneDecorator.class));
            doAnswer(invocation -> {
                state.visible = invocation.getArgument(0);
                return window;
            }).when(window).setVisible(any(Boolean.class));
            doAnswer(invocation -> state.visible).when(window).isVisible();
            doAnswer(invocation -> {
                state.visible = false;
                return window;
            }).when(window).close();
            doAnswer(invocation -> {
                state.closed = invocation.getArgument(0);
                return window;
            }).when(window).onClosed(any());
        });
    }

    @AfterEach
    void tearDown() {
        windows.close();
        registries.close();
    }

    @Test
    void elementsMapToTheirItemNameLoreGlintAndCount() {
        ButtonElement button = new ButtonElement(MenuElementCommon.builder("set-destination")
            .label("Destination")
            .description(List.of("Linked to Beta", ""))
            .chestHints(List.of("Click to choose."))
            .icon(MenuIcon.item("minecraft:ender_eye", 3, true))
            .interactions(Set.of(MenuInteraction.PRIMARY))
            .chestSlot(new MenuSlot(-2, 1))
            .build());

        UIElement element = renderer.element(player, button);

        assertEquals("set-destination", element.getId());
        assertEquals(Material.ENDER_EYE, element.getMaterial().getMaterial());
        assertEquals("Destination", element.getName());
        assertEquals(List.of("Linked to Beta", "", "Click to choose."), List.copyOf(element.getLore()));
        assertTrue(element.isEnchanted());
        assertEquals(3, element.getCount());
    }

    @Test
    void longLoreLinesWrapLikeTodaysMenus() {
        LabelElement label = new LabelElement(MenuElementCommon.builder("placard")
            .label("Portal")
            .description(List.of("§7This description is long enough that it wraps onto a second lore line"))
            .icon(MenuIcon.item("minecraft:book"))
            .chestSlot(new MenuSlot(0, 0))
            .build());

        UIElement element = renderer.element(player, label);

        assertEquals(2, element.getLore().size());
        assertTrue(element.getLore().get(1).startsWith("§7"));
    }

    @Test
    void spriteOnlyIconsRenderAsBarrierAndTemplatesBecomeTheBaseStack() {
        ItemStack template = mock(ItemStack.class);
        when(template.clone()).thenReturn(template);
        templates.put("cost-template", template);
        ButtonElement sprite = new ButtonElement(MenuElementCommon.builder("sprite")
            .icon(MenuIcon.sprite("portal"))
            .chestSlot(new MenuSlot(0, 1))
            .build());
        ButtonElement cost = new ButtonElement(MenuElementCommon.builder("travel-cost")
            .icon(MenuIcon.template("minecraft:diamond", 1, false, "cost-template"))
            .chestSlot(new MenuSlot(1, 1))
            .build());

        assertEquals(Material.BARRIER, renderer.element(player, sprite).getMaterial().getMaterial());
        UIElement costElement = renderer.element(player, cost);
        assertEquals(Material.DIAMOND, costElement.getMaterial().getMaterial());
        assertSame(template, costElement.getBaseItemStack());
    }

    @Test
    void showOpensAWindowWithTheChestLayoutAndPlacesEverySlottedElement() {
        renderer.show(viewer, build(HOME, 4, ChestPane.GRAY, null, homeElements()), true);

        assertEquals(1, windows.constructed().size());
        WindowState state = states.getFirst();
        assertEquals("§0§lAlpha", state.title);
        assertEquals(4, state.rows);
        assertTrue(state.decorated);
        assertTrue(state.visible);
        assertEquals(Set.of(4, 13, 31), state.elements.keySet());
    }

    @Test
    void sameTitleAndRowsRepaintInPlaceWhileChangesOpenANewWindow() {
        MenuBuild home = build(HOME, 4, ChestPane.GRAY, null, homeElements());
        renderer.show(viewer, home, true);
        renderer.show(viewer, home, false);

        assertEquals(1, windows.constructed().size());

        renderer.show(viewer, build(HOME, 3, ChestPane.GRAY, null, homeElements()), false);

        assertEquals(2, windows.constructed().size());
        assertEquals(3, states.get(1).rows);
    }

    @Test
    void openingAnotherScreenClosesThePreviousWindowBeforeShowingTheNewOne() {
        renderer.show(viewer, build(HOME, 4, ChestPane.GRAY, null, homeElements()), true);
        renderer.show(viewer, build(PROFILE, 4, ChestPane.BLACK, null, homeElements()), true);

        assertEquals(2, windows.constructed().size());
        InOrder order = inOrder(windows.constructed().getFirst(), windows.constructed().get(1));
        order.verify(windows.constructed().getFirst()).close();
        order.verify(windows.constructed().get(1)).setVisible(true);
        assertFalse(states.getFirst().visible);
        assertTrue(states.get(1).visible);
    }

    @Test
    void screensSharingAWindowKeyRepaintTheOpenWindow() {
        renderer.show(viewer, keyed(RTP_OVERVIEW, 6, homeElements()), true);
        renderer.show(viewer, keyed(RTP_DESTINATION, 6, List.of(homeElements().getFirst())), true);

        assertEquals(1, windows.constructed().size());
        verify(windows.constructed().getFirst(), never()).close();
        verify(windows.constructed().getFirst(), times(1)).setVisible(true);
        assertTrue(states.getFirst().visible);
        assertEquals(Set.of(4), states.getFirst().elements.keySet());

        renderer.show(viewer, keyed(RTP_DESTINATION, 5, homeElements()), true);
        renderer.show(viewer, build(HOME, 5, ChestPane.BLACK, null, homeElements()), true);

        assertEquals(3, windows.constructed().size());
        assertFalse(states.get(0).visible);
        assertFalse(states.get(1).visible);
        assertTrue(states.get(2).visible);
    }

    @Test
    void aSharedWindowTakesTheNextScreensPane() {
        renderer.show(viewer, shared(RULE_LIST, ChestPane.GRAY), true);
        UIWindow window = windows.constructed().getFirst();

        renderer.show(viewer, shared(RULE_PAGE, ChestPane.CYAN), true);
        renderer.show(viewer, shared(RULE_PAGE, ChestPane.CYAN), true);

        assertEquals(1, windows.constructed().size());
        verify(window, times(2)).setDecorator(any(UIPaneDecorator.class));
        verify(window, times(1)).setVisible(true);
    }

    @Test
    void paneNoneLeavesTheWindowUndecorated() {
        renderer.show(viewer, build(HOME, 3, ChestPane.NONE, null, homeElements()), true);

        assertFalse(states.getFirst().decorated);
    }

    @Test
    void defaultClickMapsSendOnlyDeclaredInteractions() {
        renderer.show(viewer, build(HOME, 4, ChestPane.GRAY, null, homeElements()), true);
        Element toggle = states.getFirst().elements.get(13);

        toggle.call(ElementEvent.LEFT, toggle);
        toggle.call(ElementEvent.RIGHT, toggle);
        toggle.call(ElementEvent.SHIFT_LEFT, toggle);

        ArgumentCaptor<MenuChestAction> actions = ArgumentCaptor.forClass(MenuChestAction.class);
        verify(service).chestAction(eq(viewerId), actions.capture());
        assertEquals(new MenuChestAction("toggle-projections", MenuInteraction.PRIMARY, null, false, null), actions.getValue());
    }

    @Test
    void everyElementKindUsesItsDefaultChestClickMap() {
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
            new ItemCaptureElement(common("item", 2, 1, Set.of(MenuInteraction.PRIMARY, MenuInteraction.SECONDARY)), List.of("Drop.")),
            new LookCaptureElement(common("look", 3, 1, Set.of(MenuInteraction.PRIMARY))),
            new LabelElement(MenuElementCommon.builder("label").icon(MenuIcon.item("minecraft:book")).chestSlot(new MenuSlot(4, 1)).build()));
        renderer.show(viewer, build(HOME, 3, ChestPane.GRAY, null, elements), true);
        Map<String, List<MenuInteraction>> received = new HashMap<>();
        doAnswer(invocation -> {
            MenuChestAction action = invocation.getArgument(1);
            received.computeIfAbsent(action.elementId(), ignored -> new ArrayList<>()).add(action.interaction());
            return null;
        }).when(service).chestAction(eq(viewerId), any(MenuChestAction.class));

        for (int slot = 9; slot <= 17; slot++) {
            Element element = states.getFirst().elements.get(slot);
            for (ElementEvent event : List.of(ElementEvent.LEFT, ElementEvent.RIGHT, ElementEvent.SHIFT_LEFT, ElementEvent.SHIFT_RIGHT,
                ElementEvent.MIDDLE)) {
                element.call(event, element);
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
    void dropCapturesSendTheCapturedItemAndReopen() {
        when(service.chestPrecheck(viewerId, "item", MenuInteraction.PRIMARY)).thenReturn(true);
        AtomicReference<List<String>> lines = new AtomicReference<>();
        AtomicReference<Consumer<CapturedItem>> captured = new AtomicReference<>();
        doAnswer(invocation -> {
            lines.set(invocation.getArgument(1));
            captured.set(invocation.getArgument(2));
            return null;
        }).when(captures).dropItem(any(MenuViewer.class), anyList(), any(), any());
        renderer.show(viewer, build(HOME, 4, ChestPane.BROWN, null,
            List.of(new ItemCaptureElement(common("item", 0, 1, Set.of(MenuInteraction.PRIMARY)), List.of("Drop the item to charge.")))), true);
        Element item = states.getFirst().elements.get(13);

        item.call(ElementEvent.LEFT, item);
        assertFalse(states.getFirst().visible);
        assertEquals(List.of("Drop the item to charge."), lines.get());
        CapturedItem diamond = mock(CapturedItem.class);
        captured.get().accept(diamond);

        verify(service).chestAction(viewerId, new MenuChestAction("item", MenuInteraction.PRIMARY, null, false, diamond));
        assertEquals(2, windows.constructed().size());
        assertTrue(states.get(1).visible);
    }

    @Test
    void overriddenClickMapsDelegateAndCarryThePreconfirmedFlag() {
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
        renderer.show(viewer, build(HOME, 4, ChestPane.GRAY, null, List.of(mirror, destroy)), true);
        Element mirrorSlot = states.getFirst().elements.get(17);
        Element destroySlot = states.getFirst().elements.get(31);

        mirrorSlot.call(ElementEvent.LEFT, mirrorSlot);
        mirrorSlot.call(ElementEvent.RIGHT, mirrorSlot);
        mirrorSlot.call(ElementEvent.SHIFT_RIGHT, mirrorSlot);
        mirrorSlot.call(ElementEvent.MIDDLE, mirrorSlot);
        destroySlot.call(ElementEvent.LEFT, destroySlot);
        destroySlot.call(ElementEvent.SHIFT_LEFT, destroySlot);

        ArgumentCaptor<MenuChestAction> actions = ArgumentCaptor.forClass(MenuChestAction.class);
        verify(service, times(4)).chestAction(eq(viewerId), actions.capture());
        assertEquals(List.of(
            new MenuChestAction("mode-mirror", MenuInteraction.SECONDARY, null, false, null),
            new MenuChestAction("mode-mirror", MenuInteraction.TERTIARY, null, false, null),
            new MenuChestAction("destroy", MenuInteraction.PRIMARY, null, false, null),
            new MenuChestAction("destroy", MenuInteraction.PRIMARY, null, true, null)), actions.getAllValues());
    }

    @Test
    void playerClosingTheWindowEscapesOnceAndStaleWindowsAreIgnored() {
        renderer.show(viewer, build(HOME, 3, ChestPane.GRAY, HOME, homeElements()), true);
        Callback<Window> firstClosed = states.getFirst().closed;
        renderer.show(viewer, build(HOME, 4, ChestPane.GRAY, null, homeElements()), true);

        firstClosed.run(windows.constructed().getFirst());
        verify(service, never()).chestEscape(viewerId);

        states.get(1).closed.run(windows.constructed().get(1));
        states.get(1).closed.run(windows.constructed().get(1));

        verify(service).chestEscape(viewerId);
    }

    @Test
    void closeShutsTheWindowWithoutEscaping() {
        renderer.show(viewer, build(HOME, 4, ChestPane.GRAY, null, homeElements()), true);

        renderer.close(viewer);

        assertFalse(states.getFirst().visible);
        states.getFirst().closed.run(windows.constructed().getFirst());
        verify(service, never()).chestEscape(viewerId);
    }

    @Test
    void closeAllShutsEveryWindowWithoutEndingSessions() {
        MenuService menus = openPromptScreen();

        renderer.closeAll();
        states.getFirst().closed.run(windows.constructed().getFirst());

        assertFalse(states.getFirst().visible);
        assertEquals(1, windows.constructed().size());
        assertTrue(menus.chestPrecheck(viewerId, "set-name", MenuInteraction.SET_TEXT));
    }

    @Test
    void textPromptsCloseTheWindowAndReopenAfterSubmitWhenAlways() {
        Prompt prompt = startPrompt(ChestReopen.ALWAYS, null);

        assertFalse(states.getFirst().visible);
        prompt.submitted.get().accept("Gamma");

        verify(service).chestAction(viewerId, new MenuChestAction("set-name", MenuInteraction.SET_TEXT, MenuValue.text("Gamma"), false, null));
        assertEquals(2, windows.constructed().size());
        assertTrue(states.get(1).visible);
    }

    @Test
    void refreshesWhilePromptingStayHidden() {
        Prompt prompt = startPrompt(ChestReopen.ALWAYS, null);

        renderer.show(viewer, build(HOME, 4, ChestPane.GRAY, null, homeElements()), false);

        assertEquals(1, windows.constructed().size());
        prompt.cancelled.get().run();
        assertEquals(2, windows.constructed().size());
    }

    @Test
    void reopenOnCancelSkipsTheReopenAfterSubmitAndEndsTheChestSession() {
        Prompt prompt = new Prompt();
        MenuService menus = promptSession(ChestReopen.ON_CANCEL, prompt);

        prompt.submitted.get().accept("WH1-CODE");

        assertEquals(1, windows.constructed().size());
        assertFalse(menus.chestPrecheck(viewerId, "set-name", MenuInteraction.SET_TEXT));
    }

    @Test
    void reopenOnCancelReopensTheStartingScreenAfterCancel() {
        Prompt prompt = startPrompt(ChestReopen.ON_CANCEL, null);

        prompt.cancelled.get().run();

        assertEquals(2, windows.constructed().size());
        assertTrue(states.get(1).visible);
    }

    @Test
    void neverEndsTheChestSessionAfterSubmit() {
        Prompt prompt = new Prompt();
        MenuService menus = promptSession(ChestReopen.NEVER, prompt);

        prompt.submitted.get().accept("Lobby");

        assertEquals(1, windows.constructed().size());
        assertFalse(menus.chestPrecheck(viewerId, "set-name", MenuInteraction.SET_TEXT));
    }

    @Test
    void neverEndsTheChestSessionAfterCancel() {
        Prompt prompt = new Prompt();
        MenuService menus = promptSession(ChestReopen.NEVER, prompt);

        prompt.cancelled.get().run();

        assertEquals(1, windows.constructed().size());
        assertFalse(menus.chestPrecheck(viewerId, "set-name", MenuInteraction.SET_TEXT));
    }

    @Test
    void navigationDuringASubmitOpensTheNewScreen() {
        Prompt prompt = new Prompt();
        MenuService menus = promptSession(ChestReopen.NEVER, prompt);

        prompt.submitted.get().accept(PromptController.NAVIGATE);

        assertEquals(2, windows.constructed().size());
        assertEquals(PromptController.OTHER_TITLE, states.get(1).title);
        assertTrue(states.get(1).visible);
        assertTrue(menus.chestPrecheck(viewerId, "other-button", MenuInteraction.PRIMARY));
    }

    @Test
    void disconnectedViewersLoseTheirWindowAndView() {
        MenuService menus = openPromptScreen();

        renderer.disconnected(viewerId);
        states.getFirst().closed.run(windows.constructed().getFirst());

        assertFalse(states.getFirst().visible);
        assertEquals(1, windows.constructed().size());
        assertTrue(menus.chestPrecheck(viewerId, "set-name", MenuInteraction.SET_TEXT));
    }

    @Test
    void windowsClosedByADisconnectDoNotEscape() {
        MenuService menus = openPromptScreen();
        when(player.isOnline()).thenReturn(false);

        states.getFirst().closed.run(windows.constructed().getFirst());

        assertEquals(1, windows.constructed().size());
        assertTrue(menus.chestPrecheck(viewerId, "set-name", MenuInteraction.SET_TEXT));
    }

    @Test
    void promptAnswersAfterADisconnectAreIgnored() {
        Prompt prompt = new Prompt();
        MenuService menus = promptSession(ChestReopen.ALWAYS, prompt);

        renderer.disconnected(viewerId);
        prompt.submitted.get().accept(PromptController.NAVIGATE);

        assertEquals(1, windows.constructed().size());
        assertTrue(menus.chestPrecheck(viewerId, "set-name", MenuInteraction.SET_TEXT));
    }

    @Test
    void cancelRouteOpensThatRouteInsteadOfTheStartingScreen() {
        Prompt prompt = startPrompt(ChestReopen.ALWAYS, PROFILE);

        prompt.cancelled.get().run();

        verify(service).open(viewerId, PROFILE);
        assertEquals(1, windows.constructed().size());
    }

    @Test
    void deniedPrechecksStartNoPrompt() {
        renderer.show(viewer, build(HOME, 4, ChestPane.GRAY, null, List.of(nameElement(ChestReopen.ALWAYS, null))), true);
        Element name = states.getFirst().elements.get(20);

        name.call(ElementEvent.LEFT, name);

        verify(captures, never()).prompt(any(), anyList(), any(), any());
        assertTrue(states.getFirst().visible);
    }

    @Test
    void noticesFollowTheirChestChannel() {
        EffectManager effects = mock(EffectManager.class);
        EffectManager previous = Wormholes.effectManager;
        Wormholes.effectManager = effects;
        try (MockedStatic<WormholesHud> hud = mockStatic(WormholesHud.class)) {
            renderer.notice(viewer, MenuNotices.text("§aSaved", MenuFeedbackStyle.SUCCESS, NoticeChannel.SUCCESS_EFFECT));
            renderer.notice(viewer, MenuNotices.text("§cNo", MenuFeedbackStyle.ERROR, NoticeChannel.FAILURE_EFFECT));
            renderer.notice(viewer, MenuNotices.text("Denied", MenuFeedbackStyle.ERROR, NoticeChannel.HUD));
            renderer.notice(viewer, MenuNotices.text("Native only", MenuFeedbackStyle.INFO, NoticeChannel.NONE));

            verify(effects).playNotificationSuccess("§aSaved", player);
            verify(effects).playNotificationFail("§cNo", player);
            hud.verify(() -> WormholesHud.notice(eq(player), any()));
        } finally {
            Wormholes.effectManager = previous;
        }
    }

    private MenuService openPromptScreen() {
        MenuService menus = new MenuService(new PromptHost(), new MenuRegistry(List.of(new PromptController(ChestReopen.ALWAYS))));
        renderer.start(menus);
        menus.open(viewerId, PromptController.ASK);
        return menus;
    }

    private MenuService promptSession(ChestReopen reopen, Prompt prompt) {
        MenuService menus = new MenuService(new PromptHost(), new MenuRegistry(List.of(new PromptController(reopen))));
        renderer.start(menus);
        doAnswer(invocation -> {
            prompt.lines.set(invocation.getArgument(1));
            prompt.submitted.set(invocation.getArgument(2));
            prompt.cancelled.set(invocation.getArgument(3));
            return null;
        }).when(captures).prompt(any(MenuViewer.class), anyList(), any(), any());
        menus.open(viewerId, PromptController.ASK);
        Element name = states.getFirst().elements.get(20);
        name.call(ElementEvent.LEFT, name);
        assertEquals(List.of("Type a new name."), prompt.lines.get());
        return menus;
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
        renderer.show(viewer, build(HOME, 4, ChestPane.GRAY, null, List.of(nameElement(reopen, cancelRoute))), true);
        Element name = states.getFirst().elements.get(20);
        name.call(ElementEvent.LEFT, name);
        assertEquals(List.of("Type a new name."), prompt.lines.get());
        return prompt;
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

    private static List<MenuElement> homeElements() {
        return List.of(
            new LabelElement(MenuElementCommon.builder("portal-placard").label("Alpha").icon(MenuIcon.item("minecraft:book"))
                .chestSlot(new MenuSlot(0, 0)).build()),
            new ToggleElement(MenuElementCommon.builder("toggle-projections").label("Projections")
                .icon(MenuIcon.item("minecraft:redstone_torch", 1, true)).interactions(Set.of(MenuInteraction.PRIMARY))
                .chestSlot(new MenuSlot(0, 1)).build(), true),
            new ButtonElement(MenuElementCommon.builder("destroy").label("Delete").icon(MenuIcon.item("minecraft:gunpowder"))
                .interactions(Set.of(MenuInteraction.PRIMARY))
                .chestSlot(new MenuSlot(0, 3).withClick(ChestClick.SHIFT_LEFT, ChestTarget.preconfirmed(MenuInteraction.PRIMARY))).build()),
            new ButtonElement(MenuElementCommon.builder("native-only").label("Native").icon(MenuIcon.sprite("portal")).build()));
    }

    private static MenuBuild build(MenuRoute route, int rows, ChestPane pane, MenuRoute escape, List<MenuElement> elements) {
        return new MenuBuild(MenuScreen.builder(route.screen()).title("Portal").elements(elements).build(),
            new ChestLayout("§0§lAlpha", rows, pane, escape), MenuBindings.builder().build());
    }

    private static MenuBuild shared(MenuRoute route, ChestPane pane) {
        return new MenuBuild(MenuScreen.builder(route.screen()).title("Rules").elements(homeElements()).build(),
            new ChestLayout("§0§lAlpha", 6, pane, null, "rules.list"), MenuBindings.builder().build());
    }

    private static MenuBuild keyed(MenuRoute route, int rows, List<MenuElement> elements) {
        return new MenuBuild(MenuScreen.builder(route.screen()).title("Random Teleport").elements(elements).build(),
            new ChestLayout("RTP: Alpha", rows, ChestPane.BLACK, null, "rtp"), MenuBindings.builder().build());
    }

    private final class PromptHost implements MenuHost {
        @Override
        public Optional<MenuViewer> viewer(UUID id) {
            return Optional.of(viewer);
        }

        @Override
        public boolean runForViewer(UUID id, Runnable task) {
            task.run();
            return true;
        }

        @Override
        public MenuRenderer chestRenderer() {
            return renderer;
        }

        @Override
        public MenuTransport transport() {
            return (id, frame) -> false;
        }

        @Override
        public MenuCaptures captures() {
            return captures;
        }

        @Override
        public boolean nativeScreensEnabled() {
            return false;
        }

        @Override
        public MenuServerInfo serverInfo() {
            return new MenuServerInfo("test-version", "Test 1.0", nativeScreensEnabled());
        }

        @Override
        public void warn(String message) {
        }
    }

    private static final class PromptController implements MenuController {
        private static final MenuRoute ASK = MenuRoute.of("prompt", "prompt.ask");
        private static final MenuRoute OTHER = MenuRoute.of("prompt", "prompt.other");
        private static final String NAVIGATE = "other";
        private static final String OTHER_TITLE = "Other";
        private static final MenuGuard ALLOW = (context, action) -> MenuDecision.allow();

        private final ChestReopen reopen;

        private PromptController(ChestReopen reopen) {
            this.reopen = reopen;
        }

        @Override
        public String id() {
            return "prompt";
        }

        @Override
        public MenuDecision routeGuard(MenuContext context) {
            return MenuDecision.allow();
        }

        @Override
        public MenuBuild build(MenuContext context) {
            if (context.route().screen().equals(OTHER.screen())) {
                ButtonElement button = new ButtonElement(common("other-button", 0, 1, Set.of(MenuInteraction.PRIMARY)));
                return new MenuBuild(MenuScreen.builder(OTHER.screen()).title(OTHER_TITLE).elements(List.of(button)).build(),
                    new ChestLayout(OTHER_TITLE, 3, ChestPane.BLUE, null),
                    MenuBindings.builder().guard("other-button", ALLOW).on("other-button", MenuInteraction.PRIMARY, (ctx, action) -> MenuResult.stay())
                        .build());
            }
            MenuBindings bindings = MenuBindings.builder()
                .guard("set-name", ALLOW)
                .on("set-name", MenuInteraction.SET_TEXT, (ctx, action) -> action.value() instanceof MenuValue.Text text && text.text().equals(NAVIGATE)
                    ? MenuResult.push(OTHER)
                    : MenuResult.stay())
                .build();
            return new MenuBuild(MenuScreen.builder(ASK.screen()).title("Portal").elements(List.of(nameElement(reopen, null))).build(),
                new ChestLayout("§0§lAlpha", 4, ChestPane.GRAY, OTHER), bindings);
        }
    }

    private static final class WindowState {
        private final Map<Integer, Element> elements = new HashMap<>();
        private String title;
        private int rows;
        private boolean decorated;
        private boolean visible;
        private Callback<Window> closed;
    }

    private static final class Prompt {
        private final AtomicReference<List<String>> lines = new AtomicReference<>();
        private final AtomicReference<Consumer<String>> submitted = new AtomicReference<>();
        private final AtomicReference<Runnable> cancelled = new AtomicReference<>();
    }
}
