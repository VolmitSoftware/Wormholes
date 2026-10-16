package art.arcane.wormholes.menu.extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.wormholes.localization.AccessMessages;
import art.arcane.wormholes.localization.FidelityMessages;
import art.arcane.wormholes.localization.NexusMessages;
import art.arcane.wormholes.localization.RulesMessages;
import art.arcane.wormholes.localization.TransitMessages;
import art.arcane.wormholes.menu.ChestLayout;
import art.arcane.wormholes.menu.ChestPane;
import art.arcane.wormholes.menu.FakeMenuHost;
import art.arcane.wormholes.menu.FakePortals;
import art.arcane.wormholes.menu.MenuAction;
import art.arcane.wormholes.menu.MenuBuild;
import art.arcane.wormholes.menu.MenuChestAction;
import art.arcane.wormholes.menu.MenuController;
import art.arcane.wormholes.menu.MenuElement;
import art.arcane.wormholes.menu.MenuIcon;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuRegistry;
import art.arcane.wormholes.menu.MenuRoute;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.MenuScreens;
import art.arcane.wormholes.menu.MenuService;
import art.arcane.wormholes.menu.MenuSideAction;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.StubFamily;
import art.arcane.wormholes.menu.portal.MenuExtensionEntry;
import art.arcane.wormholes.menu.portal.PortalRoutes;
import art.arcane.wormholes.menu.protocol.MenuClientMessage;
import art.arcane.wormholes.menu.protocol.MenuCodec;
import art.arcane.wormholes.menu.protocol.MenuProtocolException;
import art.arcane.wormholes.menu.protocol.MenuServerMessage;

final class ExtensionsMenuControllerTest {
    private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID OTHER_OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000e2");

    private final Set<UUID> rolesSet = new HashSet<>();
    private final Set<UUID> transitPortals = new HashSet<>();
    private FakeMenuHost host;
    private FakePortals portals;
    private FakeExtensionsMenuHost extensions;
    private MenuService service;

    @BeforeEach
    void setUp() {
        host = new FakeMenuHost();
        host.viewer(VIEWER, "Owner");
        portals = new FakePortals(VIEWER);
        extensions = new FakeExtensionsMenuHost();
        extensions.entries.addAll(List.of(
            routed("access", "minecraft:name_tag", "access", AccessMessages.MENU_ENTRY, 0, (viewer, portalId) -> rolesSet.contains(portalId),
                portalId -> route("access", "access.roles", portalId)),
            routed("rules", "minecraft:book", "settings", RulesMessages.MENU_ENTRY, 0, (viewer, portalId) -> false,
                portalId -> route("rules", "rules.profile", portalId)),
            new MenuExtensionEntry("nexus-network", "minecraft:compass", "network", NexusMessages.MENU_ENTRY, 1,
                portalId -> MessageArgs.builder().untrusted("name", "Ring").untrusted("address", "A1").build(), (viewer, portalId) -> true,
                (viewer, portalId) -> true, portalId -> route("nexus.network", "nexus.joined", portalId)),
            new MenuExtensionEntry("transit", "minecraft:feather", "transit", TransitMessages.MENU_ENTRY, 1, portalId -> MessageArgs.empty(),
                (viewer, portalId) -> transitPortals.contains(portalId), (viewer, portalId) -> false,
                portalId -> route("transit", "transit.main", portalId)),
            routed("fidelity", "minecraft:spyglass", "projection", FidelityMessages.MENU_ENTRY, 1, (viewer, portalId) -> false,
                portalId -> route("fidelity", "fidelity.main", portalId)),
            new MenuExtensionEntry("hook", "minecraft:clock", "settings", FidelityMessages.MENU_PLACARD, 0, portalId -> MessageArgs.empty(),
                (viewer, portalId) -> true, (viewer, portalId) -> false, null)));
        transitPortals.add(FakePortals.PORTAL);
        List<MenuController> controllers = List.of(new ExtensionsMenuController(portals, extensions),
            new StubFamily(PortalRoutes.SETTINGS_CONTROLLER), new StubFamily("access"), new StubFamily("rules"), new StubFamily("nexus.network"),
            new StubFamily("transit"), new StubFamily("fidelity"));
        service = new MenuService(host, new MenuRegistry(controllers), () -> 1_000L);
    }

    @Test
    void entriesFollowRegistrationOrderAndTodaysSlots() {
        rolesSet.add(FakePortals.PORTAL);
        open();
        MenuBuild build = host.chest.last();

        assertEquals(new ChestLayout("§0§lAlpha", 3, ChestPane.CYAN, null), build.chest());
        assertEquals(PortalRoutes.EXTENSIONS_MAIN, build.screen().screenId());
        assertEquals("More Settings", build.screen().title());
        assertEquals("Alpha - Portal", build.screen().context());
        assertEquals(MenuScreens.mapOf(
            "extensions-placard", "Label 0,0 minecraft:comparator - {}",
            "access", "Button -4,1 minecraft:name_tag* access {}",
            "rules", "Button -3,1 minecraft:book settings {}",
            "nexus-network", "Button -2,1 minecraft:compass* network {}",
            "transit", "Button -1,1 minecraft:feather transit {}",
            "fidelity", "Button 0,1 minecraft:spyglass projection {}",
            "hook", "Button 1,1 minecraft:clock settings {}",
            "back-to-settings", "Button 0,2 minecraft:arrow - {} hidden"), MenuScreens.layout(build.screen()));
        assertEquals("{LEFT=P}", MenuScreens.clickMaps(build.screen()).get("access"));
        assertEquals("{LEFT=P, RIGHT=S, SHIFT_LEFT=T}", MenuScreens.clickMaps(build.screen()).get("hook"));
        assertEquals("{}", MenuScreens.clickMaps(build.screen()).get("extensions-placard"));
        assertEquals(Set.of(MenuInteraction.PRIMARY), element("access").interactions());
        assertEquals(Set.of(MenuInteraction.PRIMARY, MenuInteraction.SECONDARY, MenuInteraction.TERTIARY), element("hook").interactions());
        assertTrue(element("access").common().sideActions().isEmpty());
        assertEquals(List.of(new MenuSideAction(MenuInteraction.SECONDARY, "Projection fidelity", MenuIcon.sprite("small/settings")),
            new MenuSideAction(MenuInteraction.TERTIARY, "Projection fidelity", MenuIcon.sprite("small/chevron_right"))),
            element("hook").common().sideActions());
        assertEquals("§b§lMore settings§r", element("extensions-placard").label());
        assertEquals(List.of("§eLeft-click"), element("extensions-placard").common().chestHints());
        MenuScreens.assertBound(build);
    }

    @Test
    void entryTextUsesTheEntryArgumentsAndHintCount() {
        open();

        MenuElement network = element("nexus-network");
        assertEquals("§b§lNetwork§r", network.label());
        assertEquals(List.of("§7Networks, addresses, dialing, destination policy, redstone.", "§7Network: §fRing", "§7Address: §fA1"),
            network.description());
        assertEquals(List.of("§eLeft-click"), network.common().chestHints());
        assertEquals(List.of("§7Roles, groups, and the permission key."), element("access").description());
        assertTrue(element("access").common().chestHints().isEmpty());
        assertEquals(MenuIcon.sprite("network"), network.common().nativeIcon());
    }

    @Test
    void hiddenEntriesCloseTheirGapAndRowsGrowWithTheEntries() {
        transitPortals.clear();
        open();
        assertEquals("Button -1,1 minecraft:spyglass projection {}", MenuScreens.layout(host.chest.screen()).get("fidelity"));
        assertFalse(host.chest.screen().element("transit").isPresent());

        extensions.entries.clear();
        open();
        assertEquals(3, host.chest.last().chest().rows());
        assertEquals("Button 0,2 minecraft:arrow - {} hidden", MenuScreens.layout(host.chest.screen()).get("back-to-settings"));

        extensions.entries.addAll(numbered(20));
        open();
        assertEquals(5, host.chest.last().chest().rows());
        assertEquals("Button -3,3 minecraft:paper settings {}", MenuScreens.layout(host.chest.screen()).get("entry-19"));
        assertEquals("Button 0,4 minecraft:arrow - {} hidden", MenuScreens.layout(host.chest.screen()).get("back-to-settings"));

        extensions.entries.clear();
        extensions.entries.addAll(numbered(40));
        open();
        assertEquals(6, host.chest.last().chest().rows());
        assertTrue(host.chest.screen().element("entry-35").isPresent());
        assertFalse(host.chest.screen().element("entry-36").isPresent());
        assertEquals("Button 4,4 minecraft:paper settings {}", MenuScreens.layout(host.chest.screen()).get("entry-35"));
        assertEquals("Button 0,5 minecraft:arrow - {} hidden", MenuScreens.layout(host.chest.screen()).get("back-to-settings"));
    }

    @Test
    void routedEntriesOpenTheirFamily() {
        open();

        chest("nexus-network", MenuInteraction.PRIMARY);

        assertEquals("nexus.joined", host.chest.screen().screenId());
        assertTrue(host.chest.screen().canGoBack());
        assertTrue(extensions.activations.isEmpty());
        assertFalse(service.chestPrecheck(VIEWER, "nexus-network", MenuInteraction.SECONDARY));
    }

    @Test
    void hookEntriesRunAfterTheMenuCloses() {
        List<String> seen = new ArrayList<>();
        extensions.onActivate = () -> seen.add(host.chest.lastEvent());
        open();

        chest("hook", MenuInteraction.PRIMARY);
        assertEquals(List.of("hook:PRIMARY"), extensions.activations);
        assertEquals(List.of("close"), seen);
        assertEquals("close", host.chest.lastEvent());

        extensions.takesOver = false;
        open();
        chest("hook", MenuInteraction.SECONDARY);
        open();
        chest("hook", MenuInteraction.TERTIARY);

        assertEquals(List.of("hook:PRIMARY", "hook:SECONDARY", "hook:TERTIARY"), extensions.activations);
        assertEquals(List.of("close", "close", "close"), seen);
        assertEquals("notice:HUD", host.chest.lastEvent());
        assertEquals("That option is not available right now.", host.chest.lastNotice());
    }

    @Test
    void backReturnsToTheSettingsScreen() {
        service.open(VIEWER, PortalRoutes.settings(FakePortals.PORTAL));
        open();

        chest("back-to-settings", MenuInteraction.PRIMARY);

        assertEquals(PortalRoutes.SETTINGS_MAIN, host.chest.screen().screenId());
        assertFalse(host.chest.screen().canGoBack());
    }

    @Test
    void viewersWhoCannotManageAreRejectedEverywhere() {
        open();
        for (String elementId : MenuScreens.boundIds(host.chest.screen())) {
            for (MenuInteraction interaction : element(elementId).interactions()) {
                portals.put(portals.view().toBuilder().owner(VIEWER).build());
                open();
                portals.put(portals.view().toBuilder().owner(OTHER_OWNER).build());
                host.chest.events.clear();

                chest(elementId, interaction);

                assertTrue(extensions.activations.isEmpty(), elementId + "/" + interaction);
                assertEquals(List.of("close", "notice:HUD"), host.chest.events, elementId + "/" + interaction);
            }
        }
        assertEquals(List.of("extensions-placard", "access", "rules", "nexus-network", "transit", "fidelity", "hook", "back-to-settings"),
            host.chest.screen().elements().stream().map(MenuElement::id).toList());
    }

    @Test
    void staleNativeRevisionsNeverReachTheHost() throws MenuProtocolException {
        List<String> seen = new ArrayList<>();
        extensions.onActivate = () -> seen.add(lastMessageName());
        receive(new MenuClientMessage.Hello(1));
        open();
        long stale = lastScreen().revision();
        receive(new MenuClientMessage.Action(new MenuAction(stale, "nexus-network", MenuInteraction.PRIMARY, null)));

        receive(new MenuClientMessage.Action(new MenuAction(stale, "hook", MenuInteraction.PRIMARY, null)));

        assertTrue(extensions.activations.isEmpty());
        MenuServerMessage.Update resent = assertInstanceOf(MenuServerMessage.Update.class, host.lastMessage());
        assertEquals("The menu changed and was refreshed.", resent.screen().feedback().text());

        receive(new MenuClientMessage.Back(lastScreen().revision()));
        receive(new MenuClientMessage.Action(new MenuAction(lastScreen().revision(), "hook", MenuInteraction.PRIMARY, null)));
        assertEquals(List.of("hook:PRIMARY"), extensions.activations);
        assertEquals(List.of("Close"), seen);
        assertInstanceOf(MenuServerMessage.Close.class, host.lastMessage());
    }

    @Test
    void refreshFollowsThePortal() {
        ExtensionsMenuController controller = new ExtensionsMenuController(portals, extensions);
        MenuRoute route = PortalRoutes.extensions(FakePortals.PORTAL);

        assertEquals(PortalRoutes.EXTENSIONS_CONTROLLER, controller.id());
        assertEquals(FakePortals.PORTAL, PortalRoutes.portalId(route));
        assertTrue(controller.touches(route, new MenuScope.Portal(FakePortals.PORTAL)));
        assertFalse(controller.touches(route, new MenuScope.Portal(OTHER_OWNER)));
        assertFalse(controller.touches(route, new MenuScope.Viewer(VIEWER)));
    }

    private static MenuExtensionEntry routed(String id, String item, String sprite, LinesKey label, int hints,
                                             BiPredicate<MenuViewer, UUID> enchanted, Function<UUID, MenuRoute> route) {
        return new MenuExtensionEntry(id, item, sprite, label, hints, portalId -> MessageArgs.empty(), (viewer, portalId) -> true, enchanted, route);
    }

    private void open() {
        service.open(VIEWER, PortalRoutes.extensions(FakePortals.PORTAL));
    }

    private void chest(String elementId, MenuInteraction interaction) {
        service.chestAction(VIEWER, new MenuChestAction(elementId, interaction, null, false, null));
    }

    private void receive(MenuClientMessage message) {
        try {
            service.receive(VIEWER, MenuCodec.encodeClient(message));
        } catch (MenuProtocolException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String lastMessageName() {
        try {
            return host.lastMessage().getClass().getSimpleName();
        } catch (MenuProtocolException exception) {
            throw new IllegalStateException(exception);
        }
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

    private MenuElement element(String id) {
        return MenuScreens.element(host.chest.screen(), id);
    }

    private static MenuRoute route(String controller, String screen, UUID portalId) {
        return MenuRoute.of(controller, screen).with(PortalRoutes.PORTAL, portalId.toString());
    }

    private static List<MenuExtensionEntry> numbered(int count) {
        List<MenuExtensionEntry> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            entries.add(new MenuExtensionEntry("entry-" + index, "minecraft:paper", "settings", FidelityMessages.MENU_PLACARD, 0,
                portalId -> MessageArgs.empty(), (viewer, portalId) -> true, (viewer, portalId) -> false,
                portalId -> route("rules", "rules.profile", portalId)));
        }
        return entries;
    }
}
