package art.arcane.wormholes.menu.cost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.menu.CapturedItem;
import art.arcane.wormholes.menu.ChestLayout;
import art.arcane.wormholes.menu.ChestPane;
import art.arcane.wormholes.menu.ChestReopen;
import art.arcane.wormholes.menu.FakeMenuHost;
import art.arcane.wormholes.menu.FakePortals;
import art.arcane.wormholes.menu.ItemCaptureElement;
import art.arcane.wormholes.menu.ListEntryElement;
import art.arcane.wormholes.menu.MenuAction;
import art.arcane.wormholes.menu.MenuBuild;
import art.arcane.wormholes.menu.MenuChestAction;
import art.arcane.wormholes.menu.MenuElement;
import art.arcane.wormholes.menu.MenuIcon;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuRegistry;
import art.arcane.wormholes.menu.MenuRoute;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.MenuScreens;
import art.arcane.wormholes.menu.MenuService;
import art.arcane.wormholes.menu.MenuText;
import art.arcane.wormholes.menu.MenuValue;
import art.arcane.wormholes.menu.NoticeChannel;
import art.arcane.wormholes.menu.NumberElement;
import art.arcane.wormholes.menu.StubFamily;
import art.arcane.wormholes.menu.TextElement;
import art.arcane.wormholes.menu.portal.PortalMenuView;
import art.arcane.wormholes.menu.portal.PortalRoutes;
import art.arcane.wormholes.menu.protocol.MenuClientMessage;
import art.arcane.wormholes.menu.protocol.MenuCodec;
import art.arcane.wormholes.menu.protocol.MenuProtocolException;
import art.arcane.wormholes.menu.protocol.MenuServerMessage;

final class CostMenuControllerTest {
    private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID OTHER_OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    private static final CapturedItem DIAMOND = new TestItem("minecraft:diamond", "Diamond");
    private static final PortalMenuView.Cost VANILLA = new PortalMenuView.Cost(PortalMenuView.CostMode.VANILLA, "minecraft:diamond", "Diamond",
        70, null, "cost-template");
    private static final PortalMenuView.Cost VAULT = new PortalMenuView.Cost(PortalMenuView.CostMode.VAULT, null, null, 0, "12.5", null);

    private FakeMenuHost host;
    private FakePortals portals;
    private FakeCostMenuHost costs;
    private MenuService service;

    @BeforeEach
    void setUp() {
        host = new FakeMenuHost();
        host.viewer(VIEWER, "Owner");
        portals = new FakePortals(VIEWER);
        costs = new FakeCostMenuHost(portals);
        service = new MenuService(host, new MenuRegistry(List.of(new CostMenuController(portals, costs),
            new StubFamily(PortalRoutes.SETTINGS_CONTROLLER))), () -> 1_000L);
    }

    @Test
    void freeModeMatchesTheChestWindowAndNativeTables() {
        open();
        MenuBuild build = host.chest.last();

        assertEquals(new ChestLayout("§0§lAlpha", 4, ChestPane.BROWN, null), build.chest());
        assertEquals(PortalRoutes.COST_MAIN, build.screen().screenId());
        assertEquals("Travel Cost", build.screen().title());
        assertEquals("Alpha - Portal", build.screen().context());
        assertEquals(MenuScreens.mapOf(
            "travel-cost-placard", "Label 0,0 minecraft:chest cost {}",
            "travel-cost-free", "ListEntry -2,1 minecraft:feather* cost {}",
            "travel-cost-vanilla", "ItemCapture 0,1 minecraft:hopper cost {}",
            "travel-cost-vault", "Text 2,1 minecraft:emerald cost {}",
            "travel-cost-free-detail", "Label -1,2 minecraft:white_stained_glass_pane - {} hidden",
            "travel-cost-secondary-empty", "Label 1,2 minecraft:brown_stained_glass_pane - {} hidden",
            "back-to-settings", "Button 0,3 minecraft:arrow - {} hidden"), MenuScreens.layout(build.screen()));
        assertTrue(((ListEntryElement) element("travel-cost-free")).selected());
        assertEquals(List.of(), element("travel-cost-placard").interactions().stream().toList());
        MenuScreens.assertBound(build);
    }

    @Test
    void freeModeLoreMatchesTodaysChestText() {
        open();

        assertEquals("§6§lTravel Cost§r", element("travel-cost-placard").label());
        assertEquals(List.of("§7Players must pay before this portal", "§7allows them to travel.", "", "§7Mode: §fFree", "§7Cost: §fFree"),
            MenuScreens.chestLore(element("travel-cost-placard")));
        assertEquals(List.of("§7No payment is required."), element("travel-cost-free").description());
        assertEquals(List.of("", "§8Click to make travel free."), element("travel-cost-free").common().chestHints());
        assertEquals(List.of("", "§8Click, hold the item, then press Drop."), element("travel-cost-vanilla").common().chestHints());
        TextElement vault = (TextElement) element("travel-cost-vault");
        assertEquals("", vault.value());
        assertEquals("Amount", vault.placeholder());
        assertEquals(List.of("§bEnter the currency amount per traversal (or 'cancel'):"), vault.chestPrompt());
        assertEquals(ChestReopen.ALWAYS, vault.chestReopen());
        assertNull(vault.chestCancelRoute());
        assertEquals(List.of("§7Travel through this portal is free."), element("travel-cost-free-detail").description());
        assertEquals("§8§lNo Additional Setting§r", element("travel-cost-secondary-empty").label());
        assertEquals("§e§lBack to Settings§r", element("back-to-settings").label());
    }

    @Test
    void vanillaModeShowsTheTemplateItemAndQuantity() {
        setCost(VANILLA);
        open();
        MenuScreen screen = host.chest.screen();

        assertEquals(MenuScreens.mapOf(
            "travel-cost-placard", "Label 0,0 minecraft:chest cost {}",
            "travel-cost-free", "ListEntry -2,1 minecraft:feather cost {}",
            "travel-cost-vanilla", "ItemCapture 0,1 minecraft:diamond* - {}",
            "travel-cost-vault", "Text 2,1 minecraft:emerald cost {}",
            "travel-cost-item", "ItemCapture -1,2 minecraft:diamond - {}",
            "travel-cost-quantity", "Number 1,2 minecraft:chest cost {}",
            "back-to-settings", "Button 0,3 minecraft:arrow - {} hidden"), MenuScreens.layout(screen));
        assertEquals(MenuIcon.template("minecraft:diamond", 1, true, "cost-template"), element("travel-cost-vanilla").icon());
        assertEquals(MenuIcon.template("minecraft:diamond", 1, false, "cost-template"), element("travel-cost-item").icon());
        assertFalse(((ListEntryElement) element("travel-cost-free")).selected());
        assertEquals(List.of(MenuInteraction.SECONDARY), element("travel-cost-item").common().sideActions().stream()
            .map(action -> action.interaction()).toList());
        assertEquals("Clear", element("travel-cost-item").common().sideActions().get(0).label());
        assertEquals("§b§lDiamond§r", element("travel-cost-item").label());
        assertEquals(List.of("", "§8Left: select another held item.", "§8Right: clear the cost."), element("travel-cost-item").common().chestHints());
        NumberElement quantity = (NumberElement) element("travel-cost-quantity");
        assertEquals(70.0D, quantity.value());
        assertEquals(1.0D, quantity.min());
        assertEquals(2304.0D, quantity.max());
        assertEquals(1.0D, quantity.step());
        assertEquals(8.0D, quantity.bigStep());
        assertEquals("70", quantity.display());
        assertEquals(MenuIcon.item("minecraft:chest", 64, false), quantity.icon());
        assertEquals("§b§lQuantity 70§r", quantity.label());
        assertTrue(MenuScreens.chestLore(quantity).contains("§8Maximum: 2304"));
        assertTrue(MenuScreens.chestLore(element("travel-cost-placard")).containsAll(List.of("§7Mode: §fVanilla Item", "§7Cost: §f70x Diamond")));
    }

    @Test
    void vaultModeShowsTheAmountAndTheEconomyState() {
        setCost(VAULT);
        open();
        MenuScreen screen = host.chest.screen();

        assertEquals(MenuScreens.mapOf(
            "travel-cost-placard", "Label 0,0 minecraft:chest cost {}",
            "travel-cost-free", "ListEntry -2,1 minecraft:feather cost {}",
            "travel-cost-vanilla", "ItemCapture 0,1 minecraft:hopper cost {}",
            "travel-cost-vault", "Text 2,1 minecraft:emerald* cost {}",
            "travel-cost-vault-amount", "Text -1,2 minecraft:gold_ingot cost {}",
            "travel-cost-secondary-empty", "Label 1,2 minecraft:brown_stained_glass_pane - {} hidden",
            "back-to-settings", "Button 0,3 minecraft:arrow - {} hidden"), MenuScreens.layout(screen));
        TextElement amount = (TextElement) element("travel-cost-vault-amount");
        assertEquals("12.5", amount.value());
        assertEquals(ChestReopen.ALWAYS, amount.chestReopen());
        assertEquals("§a§l12.5§r", amount.label());
        assertEquals(List.of(MenuInteraction.SECONDARY), amount.common().sideActions().stream().map(action -> action.interaction()).toList());
        assertEquals("12.5", ((TextElement) element("travel-cost-vault")).value());
        assertTrue(MenuScreens.chestLore(element("travel-cost-placard")).containsAll(List.of("§7Mode: §fEconomy", "§7Cost: §f12.5")));

        costs.currency = false;
        open();
        assertEquals("Text 2,1 minecraft:redstone* cost {}", MenuScreens.layout(host.chest.screen()).get("travel-cost-vault"));
        assertEquals("§c§lEconomy Unavailable§r", element("travel-cost-vault").label());
    }

    @Test
    void chestClicksFollowTodaysHandlers() {
        open();
        assertEquals(MenuScreens.mapOf(
            "travel-cost-placard", "{}",
            "travel-cost-free", "{LEFT=P}",
            "travel-cost-vanilla", "{LEFT=P}",
            "travel-cost-vault", "{LEFT=X}",
            "travel-cost-free-detail", "{}",
            "travel-cost-secondary-empty", "{}",
            "back-to-settings", "{LEFT=P}"), MenuScreens.clickMaps(host.chest.screen()));

        setCost(VANILLA);
        open();
        assertEquals("{LEFT=P, RIGHT=S}", MenuScreens.clickMaps(host.chest.screen()).get("travel-cost-item"));
        assertEquals("{LEFT=+, RIGHT=-, SHIFT_LEFT=++, SHIFT_RIGHT=--}", MenuScreens.clickMaps(host.chest.screen()).get("travel-cost-quantity"));

        setCost(VAULT);
        open();
        assertEquals("{LEFT=X, RIGHT=S}", MenuScreens.clickMaps(host.chest.screen()).get("travel-cost-vault-amount"));
    }

    @Test
    void freeClearsOnlyWhenACostIsSet() {
        open();
        chest("travel-cost-free", MenuInteraction.PRIMARY, null, null);
        assertTrue(costs.edits.isEmpty());
        assertTrue(host.chest.notices.isEmpty());

        setCost(VANILLA);
        open();
        chest("travel-cost-free", MenuInteraction.PRIMARY, null, null);

        assertEquals(List.of("Clear[]"), costs.edits);
        assertEquals("§aAlpha: §rTravel cost cleared.", host.chest.lastNotice());
        assertEquals(NoticeChannel.SUCCESS_EFFECT, lastChannel());
        assertTrue(((ListEntryElement) element("travel-cost-free")).selected());
        assertEquals(PortalRoutes.COST_MAIN, host.chest.screen().screenId());
    }

    @Test
    void chestDropCaptureSetsTheTemplateItem() {
        open();

        chest("travel-cost-vanilla", MenuInteraction.PRIMARY, null, DIAMOND);

        assertEquals(List.of("Item[item=" + DIAMOND + "]"), costs.edits);
        assertEquals("Travel now requires Diamond.", host.chest.lastNotice());
        assertEquals(NoticeChannel.HUD, lastChannel());
        assertEquals(PortalRoutes.COST_MAIN, host.chest.screen().screenId());
        assertEquals("ItemCapture -1,2 minecraft:diamond - {}", MenuScreens.layout(host.chest.screen()).get("travel-cost-item"));

        chest("travel-cost-item", MenuInteraction.PRIMARY, null, new TestItem("minecraft:air", "Air"));
        assertEquals("That item could not be saved as a travel cost.", host.chest.lastNotice());
        assertEquals(NoticeChannel.HUD, lastChannel());
    }

    @Test
    void nativeCaptureReadsTheHeldItem() throws MenuProtocolException {
        hello();
        open();

        nativeAction("travel-cost-vanilla", MenuInteraction.PRIMARY, null);
        assertTrue(costs.edits.isEmpty());
        assertEquals("That item could not be saved as a travel cost.", lastFeedback());

        host.heldItem = DIAMOND;
        nativeAction("travel-cost-vanilla", MenuInteraction.PRIMARY, null);
        assertEquals(List.of("Item[item=" + DIAMOND + "]"), costs.edits);
        assertEquals("Travel now requires Diamond.", lastFeedback());
        assertTrue(lastScreen().element("travel-cost-quantity").isPresent());
    }

    @Test
    void captureElementsCarryTheDropPrompt() {
        List<String> prompt = List.of("§bHold the exact cost item in your main hand, then press Drop. The item will not leave your inventory.");
        open();
        assertEquals(prompt, ((ItemCaptureElement) element("travel-cost-vanilla")).chestPrompt());

        setCost(VANILLA);
        open();
        assertEquals(prompt, ((ItemCaptureElement) element("travel-cost-vanilla")).chestPrompt());
        assertEquals(prompt, ((ItemCaptureElement) element("travel-cost-item")).chestPrompt());
    }

    @Test
    void coOwnersReachTheCapturePrompt() {
        portals.put(portals.view().toBuilder().owner(OTHER_OWNER).cost(VANILLA).build());
        portals.coOwners.add(VIEWER);
        open();
        host.chest.events.clear();

        assertTrue(service.chestPrecheck(VIEWER, "travel-cost-vanilla", MenuInteraction.PRIMARY));
        assertTrue(service.chestPrecheck(VIEWER, "travel-cost-item", MenuInteraction.PRIMARY));

        assertTrue(host.chest.events.isEmpty());
        assertTrue(costs.edits.isEmpty());
        assertFalse(((ItemCaptureElement) element("travel-cost-item")).chestPrompt().isEmpty());
    }

    @Test
    void captureCompletionNeedsTheOwnerOrAnAdministrator() throws MenuProtocolException {
        portals.put(portals.view().toBuilder().owner(OTHER_OWNER).cost(VANILLA).build());
        portals.coOwners.add(VIEWER);
        open();

        chest("travel-cost-item", MenuInteraction.SECONDARY, null, null);
        assertEquals(List.of("Clear[]"), costs.edits);

        chest("travel-cost-vanilla", MenuInteraction.PRIMARY, null, DIAMOND);
        assertEquals(List.of("Clear[]"), costs.edits);
        assertEquals("§cOnly the portal owner or an administrator can edit this portal.", host.chest.lastNotice());
        assertEquals(NoticeChannel.HUD, lastChannel());
        assertTrue(host.chest.events.contains("close"));
        assertFalse(service.chestPrecheck(VIEWER, "travel-cost-free", MenuInteraction.PRIMARY));

        hello();
        open();
        host.heldItem = DIAMOND;
        nativeAction("travel-cost-vanilla", MenuInteraction.PRIMARY, null);
        assertEquals(List.of("Clear[]"), costs.edits);
        assertInstanceOf(MenuServerMessage.Close.class, host.lastMessage());

        portals.put(portals.view().toBuilder().owner(VIEWER).build());
        open();
        nativeAction("travel-cost-vanilla", MenuInteraction.PRIMARY, null);
        assertEquals(List.of("Clear[]", "Item[item=" + DIAMOND + "]"), costs.edits);
        assertEquals("Travel now requires Diamond.", lastFeedback());
    }

    @Test
    void quantityStepsThroughTheHostAndAnnouncesChanges() throws MenuProtocolException {
        setCost(new PortalMenuView.Cost(PortalMenuView.CostMode.VANILLA, "minecraft:diamond", "Diamond", 1, null, "cost-template"));
        open();

        chest("travel-cost-quantity", MenuInteraction.INCREMENT, null, null);
        assertEquals("§aAlpha: §rTravel cost quantity: 2", host.chest.lastNotice());
        chest("travel-cost-quantity", MenuInteraction.BIG_INCREMENT, null, null);
        chest("travel-cost-quantity", MenuInteraction.DECREMENT, null, null);
        int notices = host.chest.notices.size();
        chest("travel-cost-quantity", MenuInteraction.BIG_DECREMENT, null, null);
        chest("travel-cost-quantity", MenuInteraction.BIG_DECREMENT, null, null);

        assertEquals(List.of("Quantity[quantity=2]", "Quantity[quantity=10]", "Quantity[quantity=9]", "Quantity[quantity=1]",
            "Quantity[quantity=1]"), costs.edits);
        assertEquals(notices + 1, host.chest.notices.size());
        assertEquals("§aAlpha: §rTravel cost quantity: 1", host.chest.lastNotice());
        assertEquals(1.0D, ((NumberElement) element("travel-cost-quantity")).value());

        hello();
        open();
        nativeAction("travel-cost-quantity", MenuInteraction.SET_VALUE, MenuValue.number(2304.0D));
        assertEquals("Quantity[quantity=2304]", costs.edits.get(costs.edits.size() - 1));
        assertEquals("Alpha: Travel cost quantity: 2304", MenuText.stripColors(lastFeedback()));
        assertEquals(MenuIcon.sprite("cost"), lastScreen().element("travel-cost-quantity").orElseThrow().icon());
    }

    @Test
    void vaultAmountsApplyRejectAndClear() {
        open();

        chest("travel-cost-vault", MenuInteraction.SET_TEXT, MenuValue.text(" 25 "), null);
        assertEquals(List.of("Currency[amount=25]"), costs.edits);
        assertEquals("§aAlpha: §rCurrency travel cost: 25", host.chest.lastNotice());
        assertEquals(NoticeChannel.SUCCESS_EFFECT, lastChannel());

        chest("travel-cost-vault-amount", MenuInteraction.SET_TEXT, MenuValue.text("abc"), null);
        assertEquals("§aAlpha: §rEnter a positive currency amount no greater than 1000000000000.", host.chest.lastNotice());
        assertEquals("25", ((TextElement) element("travel-cost-vault-amount")).value());

        chest("travel-cost-vault-amount", MenuInteraction.SECONDARY, null, null);
        assertEquals(List.of("Currency[amount=25]", "Currency[amount=abc]", "Clear[]"), costs.edits);
        assertEquals("§aAlpha: §rTravel cost cleared.", host.chest.lastNotice());
        assertTrue(host.chest.screen().element("travel-cost-free-detail").isPresent());
    }

    @Test
    void vaultSelectionNeedsAnEconomyProvider() {
        costs.currency = false;
        open();

        assertFalse(service.chestPrecheck(VIEWER, "travel-cost-vault", MenuInteraction.SET_TEXT));
        chest("travel-cost-vault", MenuInteraction.SET_TEXT, MenuValue.text("25"), null);

        assertTrue(costs.edits.isEmpty());
        assertEquals("§aAlpha: §rAn economy provider must be available before selecting this cost.", host.chest.lastNotice());
        assertEquals(PortalRoutes.COST_MAIN, host.chest.screen().screenId());
        assertFalse(host.chest.events.contains("close"));
    }

    @Test
    void backReturnsToTheSettingsScreen() {
        service.open(VIEWER, PortalRoutes.settings(FakePortals.PORTAL));
        open();

        chest("back-to-settings", MenuInteraction.PRIMARY, null, null);

        assertEquals(PortalRoutes.SETTINGS_MAIN, host.chest.screen().screenId());
        assertFalse(host.chest.screen().canGoBack());
    }

    @Test
    void viewersWhoCannotManageAreRejectedEverywhere() {
        for (PortalMenuView.Cost cost : List.of(PortalMenuView.Cost.FREE, VANILLA, VAULT)) {
            setCost(cost);
            open();
            for (String elementId : MenuScreens.boundIds(host.chest.screen())) {
                for (MenuInteraction interaction : element(elementId).interactions()) {
                    portals.put(portals.view().toBuilder().owner(VIEWER).build());
                    open();
                    portals.put(portals.view().toBuilder().owner(OTHER_OWNER).build());
                    host.chest.events.clear();
                    MenuValue value = switch (interaction) {
                        case SET_TEXT -> MenuValue.text("5");
                        case SET_VALUE -> MenuValue.number(5.0D);
                        default -> null;
                    };

                    chest(elementId, interaction, value, DIAMOND);

                    String where = cost.mode() + "/" + elementId + "/" + interaction;
                    assertTrue(costs.edits.isEmpty(), where);
                    assertEquals(List.of("close", "notice:HUD"), host.chest.events, where);
                }
            }
            portals.put(portals.view().toBuilder().owner(VIEWER).build());
        }
    }

    @Test
    void staleNativeRevisionsAreRefreshedWithoutReachingTheHost() throws MenuProtocolException {
        setCost(VANILLA);
        hello();
        open();
        long stale = revision();
        nativeAction("travel-cost-quantity", MenuInteraction.INCREMENT, null);
        assertEquals(List.of("Quantity[quantity=71]"), costs.edits);

        receive(new MenuClientMessage.Action(new MenuAction(stale, "travel-cost-free", MenuInteraction.PRIMARY, null)));

        assertEquals(List.of("Quantity[quantity=71]"), costs.edits);
        MenuServerMessage.Update resent = assertInstanceOf(MenuServerMessage.Update.class, host.lastMessage());
        assertEquals("The menu changed and was refreshed.", resent.screen().feedback().text());
        assertTrue(resent.screen().revision() > stale);
    }

    @Test
    void refreshFollowsThePortal() {
        CostMenuController controller = new CostMenuController(portals, costs);
        MenuRoute route = PortalRoutes.cost(FakePortals.PORTAL);

        assertEquals(PortalRoutes.COST_CONTROLLER, controller.id());
        assertEquals(0, controller.refreshIntervalTicks(route));
        assertTrue(controller.touches(route, new MenuScope.Portal(FakePortals.PORTAL)));
        assertFalse(controller.touches(route, new MenuScope.Portal(UUID.randomUUID())));
        assertFalse(controller.touches(route, new MenuScope.Viewer(VIEWER)));
        assertEquals(FakePortals.PORTAL, PortalRoutes.portalId(route));
    }

    @Test
    void vanishedPortalsCloseTheMenu() {
        open();
        portals.views.clear();
        host.chest.events.clear();

        chest("travel-cost-vault", MenuInteraction.SET_TEXT, MenuValue.text("5"), null);

        assertTrue(costs.edits.isEmpty());
        assertEquals(List.of("close", "notice:HUD"), host.chest.events);
    }

    private void open() {
        service.open(VIEWER, PortalRoutes.cost(FakePortals.PORTAL));
    }

    private void setCost(PortalMenuView.Cost cost) {
        portals.put(portals.view().toBuilder().cost(cost).build());
    }

    private void hello() {
        receive(new MenuClientMessage.Hello(1));
    }

    private void chest(String elementId, MenuInteraction interaction, MenuValue value, CapturedItem item) {
        service.chestAction(VIEWER, new MenuChestAction(elementId, interaction, value, false, item));
    }

    private void nativeAction(String elementId, MenuInteraction interaction, MenuValue value) throws MenuProtocolException {
        receive(new MenuClientMessage.Action(new MenuAction(revision(), elementId, interaction, value)));
    }

    private void receive(MenuClientMessage message) {
        try {
            service.receive(VIEWER, MenuCodec.encodeClient(message));
        } catch (MenuProtocolException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private long revision() throws MenuProtocolException {
        return lastScreen().revision();
    }

    private MenuScreen lastScreen() throws MenuProtocolException {
        List<MenuServerMessage> messages = host.serverMessages();
        for (int index = messages.size() - 1; index >= 0; index--) {
            switch (messages.get(index)) {
                case MenuServerMessage.Open opened -> {
                    return opened.screen();
                }
                case MenuServerMessage.Update updated -> {
                    return updated.screen();
                }
                default -> {
                }
            }
        }
        throw new AssertionError("no screen was sent");
    }

    private String lastFeedback() throws MenuProtocolException {
        return assertInstanceOf(MenuServerMessage.Feedback.class, host.lastMessage()).feedback().text();
    }

    private NoticeChannel lastChannel() {
        return host.chest.notices.get(host.chest.notices.size() - 1).chestChannel();
    }

    private MenuElement element(String id) {
        return MenuScreens.element(host.chest.screen(), id);
    }

    private record TestItem(String itemId, String label) implements CapturedItem {
    }
}
