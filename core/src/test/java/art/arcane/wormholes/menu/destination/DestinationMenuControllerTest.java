package art.arcane.wormholes.menu.destination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.optics.math.Face;
import art.arcane.wormholes.menu.ButtonElement;
import art.arcane.wormholes.menu.ChestLayout;
import art.arcane.wormholes.menu.ChestPane;
import art.arcane.wormholes.menu.CycleElement;
import art.arcane.wormholes.menu.FakeMenuHost;
import art.arcane.wormholes.menu.FakePortals;
import art.arcane.wormholes.menu.ListEntryElement;
import art.arcane.wormholes.menu.MenuAction;
import art.arcane.wormholes.menu.MenuBindings;
import art.arcane.wormholes.menu.MenuBuild;
import art.arcane.wormholes.menu.MenuChestAction;
import art.arcane.wormholes.menu.MenuContext;
import art.arcane.wormholes.menu.MenuController;
import art.arcane.wormholes.menu.MenuDecision;
import art.arcane.wormholes.menu.MenuElement;
import art.arcane.wormholes.menu.MenuElementCommon;
import art.arcane.wormholes.menu.MenuGuards;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuPaging;
import art.arcane.wormholes.menu.MenuRegistry;
import art.arcane.wormholes.menu.MenuResult;
import art.arcane.wormholes.menu.MenuRoute;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.MenuScreens;
import art.arcane.wormholes.menu.MenuService;
import art.arcane.wormholes.menu.MenuSlot;
import art.arcane.wormholes.menu.MenuText;
import art.arcane.wormholes.menu.MenuValue;
import art.arcane.wormholes.menu.NoticeChannel;
import art.arcane.wormholes.menu.portal.PortalRoutes;
import art.arcane.wormholes.menu.protocol.MenuClientMessage;
import art.arcane.wormholes.menu.protocol.MenuCodec;
import art.arcane.wormholes.menu.protocol.MenuProtocolException;
import art.arcane.wormholes.menu.protocol.MenuServerMessage;
import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.portal.PortalType;

final class DestinationMenuControllerTest {
    private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID OTHER_OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    private static final UUID BETA = UUID.fromString("00000000-0000-0000-0000-00000000be7a");
    private static final UUID GAMMA = UUID.fromString("00000000-0000-0000-0000-00000000ca3a");
    private static final UUID REMOTE = UUID.fromString("00000000-0000-0000-0000-00000000f00d");
    private static final DestinationMenuView BETA_VIEW = new DestinationMenuView(BETA, "Beta", "world", 1, 64, 2, Face.N, 100.0D, true, null, true);
    private static final DestinationMenuView GAMMA_VIEW = new DestinationMenuView(GAMMA, "Gamma", "world", 9, 65, -3, Face.E, 25.0D, false, null,
        true);
    private static final DestinationMenuView REMOTE_VIEW = new DestinationMenuView(REMOTE, "Remote", "lobby_world", 0, 70, 0, Face.S,
        Double.MAX_VALUE, false, "lobby", false);

    private FakeMenuHost host;
    private FakePortals portals;
    private FakeDestinationMenuHost destinations;
    private MenuService service;

    @BeforeEach
    void setUp() {
        host = new FakeMenuHost();
        host.viewer(VIEWER, "Owner");
        portals = new FakePortals(VIEWER);
        portals.put(FakePortals.base(BETA, "Beta").owner(VIEWER).build());
        portals.put(FakePortals.base(GAMMA, "Gamma").owner(OTHER_OWNER).build());
        destinations = new FakeDestinationMenuHost();
        service = new MenuService(host, new MenuRegistry(List.of(new DestinationMenuController(portals, destinations), new HomeStub())),
            () -> 1_000L);
    }

    @Test
    void pickerMatchesTheChestWindowAndNativeTables() {
        destinations.candidates.addAll(List.of(REMOTE_VIEW, GAMMA_VIEW, BETA_VIEW));
        open();
        MenuBuild build = host.chest.last();

        assertEquals(new ChestLayout("§0§lAlpha", 6, ChestPane.GRAY, PortalRoutes.home(FakePortals.PORTAL)), build.chest());
        assertEquals(PortalRoutes.DESTINATION_PICKER, build.screen().screenId());
        assertEquals("Destinations", build.screen().title());
        assertEquals("Alpha - Portal", build.screen().context());
        assertNull(build.screen().paging());
        assertEquals(MenuScreens.mapOf(
            "destination-sort", "Cycle -2,5 minecraft:comparator sort {}",
            "portal-0", "ListEntry -4,0 minecraft:ender_pearl* portal {}",
            "portal-1", "ListEntry -3,0 minecraft:ender_pearl portal {}",
            "remote-portal-2", "ListEntry -2,0 minecraft:end_crystal gateway {}",
            "destination-page", "Label 0,5 minecraft:paper - {} hidden"), MenuScreens.layout(build.screen()));
        assertTrue(((ListEntryElement) element("portal-0")).selected());
        assertFalse(((ListEntryElement) element("portal-1")).selected());
        assertTrue(element("portal-0").common().sideActions().isEmpty());
        assertEquals("§6Beta", element("portal-0").label());
        assertEquals(List.of("§7at 1, 64, 2 in world Facing North"), element("portal-0").description());
        assertEquals("§6Remote", element("remote-portal-2").label());
        assertEquals(List.of("§7on server §flobby", "§7at 0, 70, 0 in lobby_world Facing South", "§bClosed"),
            element("remote-portal-2").description());
        CycleElement sort = (CycleElement) element("destination-sort");
        assertEquals(List.of("Smart", "Name", "World", "Distance"), sort.options());
        assertEquals(0, sort.index());
        assertEquals("§e§lSort: §f§lSmart§r", sort.label());
        assertEquals(List.of("§eLeft click"), sort.common().chestHints());
        assertEquals("§b§lPage 1/1§r", element("destination-page").label());
        assertEquals(List.of("§73 destinations"), element("destination-page").description());
        assertEquals(MenuScreens.mapOf(
            "destination-sort", "{LEFT=P}",
            "portal-0", "{LEFT=P}",
            "portal-1", "{LEFT=P}",
            "remote-portal-2", "{LEFT=P}",
            "destination-page", "{}"), MenuScreens.clickMaps(build.screen()));
        MenuScreens.assertBound(build);
    }

    @Test
    void emptyPickersShowThePlaceholder() {
        open();

        assertEquals(MenuScreens.mapOf(
            "destination-sort", "Cycle -2,5 minecraft:comparator sort {}",
            "destination-empty", "Label 0,2 minecraft:barrier - {}",
            "destination-page", "Label 0,5 minecraft:paper - {} hidden"), MenuScreens.layout(host.chest.screen()));
        assertEquals("§7No destinations are available to link.", element("destination-empty").label());
    }

    @Test
    void pagesHoldFortyFiveDestinations() throws MenuProtocolException {
        destinations.candidates.addAll(numbered(100));
        open();
        MenuScreen first = host.chest.screen();
        assertEquals(new MenuPaging(0, 3), first.paging());
        assertTrue(first.element("portal-44").isPresent());
        assertFalse(first.element("portal-45").isPresent());
        assertFalse(first.element("destination-previous").isPresent());
        assertEquals("Button 4,5 minecraft:arrow - {} hidden", MenuScreens.layout(first).get("destination-next"));
        assertEquals("ListEntry 4,4 minecraft:ender_pearl portal {}", MenuScreens.layout(first).get("portal-44"));

        chest("destination-next", MenuInteraction.PRIMARY, null);
        MenuScreen second = host.chest.screen();
        assertEquals(new MenuPaging(1, 3), second.paging());
        assertEquals("ListEntry -4,0 minecraft:ender_pearl portal {}", MenuScreens.layout(second).get("portal-45"));
        assertEquals("Button -4,5 minecraft:arrow - {} hidden", MenuScreens.layout(second).get("destination-previous"));
        assertEquals("§b§lPage 2/3§r", MenuScreens.element(second, "destination-page").label());

        chest("destination-previous", MenuInteraction.PRIMARY, null);
        assertEquals(new MenuPaging(0, 3), host.chest.screen().paging());

        hello();
        open();
        receive(new MenuClientMessage.Page(revision(), 2));
        MenuScreen last = lastScreen();
        assertEquals(new MenuPaging(2, 3), last.paging());
        assertTrue(last.element("portal-99").isPresent());
        assertFalse(last.element("destination-next").isPresent());
    }

    @Test
    void sortingCyclesAndReturnsToTheFirstPage() throws MenuProtocolException {
        destinations.candidates.addAll(numbered(50));
        destinations.candidates.add(BETA_VIEW);
        open();
        chest("destination-next", MenuInteraction.PRIMARY, null);

        chest("destination-sort", MenuInteraction.PRIMARY, null);

        MenuScreen screen = host.chest.screen();
        assertEquals(1, ((CycleElement) MenuScreens.element(screen, "destination-sort")).index());
        assertEquals(new MenuPaging(0, 2), screen.paging());
        assertEquals("§6Beta", MenuScreens.element(screen, "portal-0").label());

        hello();
        open();
        receive(new MenuClientMessage.Action(new MenuAction(revision(), "destination-sort", MenuInteraction.SELECT, MenuValue.index(3))));
        MenuScreen distance = lastScreen();
        assertEquals(3, ((CycleElement) distance.element("destination-sort").orElseThrow()).index());
        assertEquals("§6Stop 000", distance.element("portal-0").orElseThrow().label());
    }

    @Test
    void sortAndPageStartOverEachTimeThePickerOpens() {
        destinations.candidates.addAll(numbered(50));
        destinations.candidates.add(BETA_VIEW);
        service.open(VIEWER, PortalRoutes.home(FakePortals.PORTAL));
        chest("set-destination", MenuInteraction.PRIMARY, null);
        chest("destination-sort", MenuInteraction.PRIMARY, null);
        chest("destination-next", MenuInteraction.PRIMARY, null);
        assertEquals(1, sortIndex());
        assertEquals(new MenuPaging(1, 2), host.chest.screen().paging());

        service.chestEscape(VIEWER);
        assertEquals(PortalRoutes.HOME, host.chest.screen().screenId());
        chest("set-destination", MenuInteraction.PRIMARY, null);

        assertEquals(PortalRoutes.DESTINATION_PICKER, host.chest.screen().screenId());
        assertEquals(0, sortIndex());
        assertEquals(new MenuPaging(0, 2), host.chest.screen().paging());
    }

    @Test
    void pickersDoNotInheritTheSelectionSort() {
        destinations.portals.addAll(List.of(
            new DestinationMenuView(BETA, "Beta", "world", 1, 64, 2, Face.N, Double.MAX_VALUE, false, null, true),
            new DestinationMenuView(FakePortals.PORTAL, "Alpha", "world", 0, 64, 0, Face.S, Double.MAX_VALUE, false, null, true)));
        destinations.candidates.add(BETA_VIEW);
        service.open(VIEWER, DestinationRoutes.select());
        chest("destination-sort", MenuInteraction.PRIMARY, null);
        assertEquals(1, sortIndex());

        chest("portal-0", MenuInteraction.PRIMARY, null);
        assertEquals(PortalRoutes.HOME, host.chest.screen().screenId());
        chest("set-destination", MenuInteraction.PRIMARY, null);

        assertEquals(PortalRoutes.DESTINATION_PICKER, host.chest.screen().screenId());
        assertEquals(0, sortIndex());
    }

    @Test
    void choosingRereadsTheLinkStateWhenClicked() {
        DestinationMenuView betaUnlinked = new DestinationMenuView(BETA, "Beta", "world", 1, 64, 2, Face.N, 100.0D, false, null, true);
        DestinationMenuView gammaLinked = new DestinationMenuView(GAMMA, "Gamma", "world", 9, 65, -3, Face.E, 25.0D, true, null, true);
        destinations.candidates.addAll(List.of(BETA_VIEW, GAMMA_VIEW));
        open();
        destinations.candidates.clear();
        destinations.candidates.addAll(List.of(betaUnlinked, gammaLinked));

        chest("portal-1", MenuInteraction.PRIMARY, null);
        assertEquals(List.of("unlink"), destinations.calls);
        assertEquals(List.of("§eAlpha unlinked from Gamma."), portals.announcements);

        open();
        destinations.candidates.clear();
        destinations.candidates.addAll(List.of(BETA_VIEW, GAMMA_VIEW));
        chest("portal-0", MenuInteraction.PRIMARY, null);

        assertEquals(List.of("unlink", "link:Gamma"), destinations.calls);
        assertEquals("§aAlpha linked to Gamma.", portals.announcements.get(1));
    }

    @Test
    void choosingADestinationLinksOrUnlinksAndAlwaysAnnounces() {
        destinations.candidates.addAll(List.of(BETA_VIEW, GAMMA_VIEW, REMOTE_VIEW));

        open();
        chest("portal-1", MenuInteraction.PRIMARY, null);
        assertEquals(List.of("link:Gamma"), destinations.calls);
        assertEquals(List.of("§aAlpha linked to Gamma."), portals.announcements);
        assertTrue(host.chest.events.contains("close"));

        open();
        chest("portal-0", MenuInteraction.PRIMARY, null);
        open();
        chest("remote-portal-2", MenuInteraction.PRIMARY, null);

        assertEquals(List.of("link:Gamma", "unlink", "link:Remote@lobby"), destinations.calls);
        assertEquals(List.of("§aAlpha linked to Gamma.", "§eAlpha unlinked from Beta.", "§aAlpha linked to Remote on lobby."),
            portals.announcements);
        assertFalse(service.chestPrecheck(VIEWER, "destination-sort", MenuInteraction.PRIMARY));
    }

    @Test
    void pickersRefuseRandomTeleportMirrorAndManagedPortals() {
        portals.put(portals.view().toBuilder().type(PortalType.RTP).build());
        open();
        portals.put(portals.view().toBuilder().type(PortalType.PORTAL).mirror(true).build());
        open();
        portals.put(portals.view().toBuilder().mirror(false).dimensionalKind(DimensionalPortalKind.END_ARRIVAL).build());
        open();

        assertTrue(host.chest.builds.isEmpty());
        assertEquals(List.of("§aAlpha: §rRandom teleport portals do not link to destinations.", "§aAlpha: §rMirror mode never allows travel.",
            "§aAlpha: §rThis dimensional portal keeps its generated link."), noticeTexts());
        assertEquals(NoticeChannel.SUCCESS_EFFECT, host.chest.notices.get(0).chestChannel());
    }

    @Test
    void linkAndReturnNeedsBothPortals() {
        destinations.candidates.add(BETA_VIEW);
        destinations.localDestination = BETA_VIEW;
        open();
        assertEquals("Button 2,5 minecraft:ender_eye link {}", MenuScreens.layout(host.chest.screen()).get("destination-link-return"));
        assertEquals("§d§lLink and return§r", element("destination-link-return").label());
        assertEquals(List.of("§7Also point Beta back at Alpha."), element("destination-link-return").description());

        chest("destination-link-return", MenuInteraction.PRIMARY, null);
        assertEquals(List.of("pair:" + BETA), destinations.calls);
        assertEquals("§aAlpha: §r[Wormholes] Alpha and Beta now link both ways.", host.chest.lastNotice());
        assertEquals(NoticeChannel.SUCCESS_EFFECT, host.chest.notices.get(host.chest.notices.size() - 1).chestChannel());
        assertTrue(host.chest.events.contains("close"));

        destinations.pairOutcome = PairOutcome.DENIED;
        open();
        chest("destination-link-return", MenuInteraction.PRIMARY, null);
        assertEquals("§aAlpha: §r[Wormholes] You cannot manage both Alpha and Beta.", host.chest.lastNotice());

        destinations.pairOutcome = PairOutcome.UNAVAILABLE;
        open();
        int notices = host.chest.notices.size();
        chest("destination-link-return", MenuInteraction.PRIMARY, null);
        assertEquals(notices, host.chest.notices.size());
        assertEquals("close", host.chest.lastEvent());

        destinations.localDestination = new DestinationMenuView(GAMMA, "Gamma", "world", 0, 0, 0, Face.N, 1.0D, true, null, true);
        open();
        assertFalse(host.chest.screen().element("destination-link-return").isPresent());
        destinations.localDestination = new DestinationMenuView(FakePortals.PORTAL, "Alpha", "world", 0, 0, 0, Face.N, 1.0D, true, null, true);
        open();
        assertFalse(host.chest.screen().element("destination-link-return").isPresent());

        destinations.localDestination = BETA_VIEW;
        open();
        portals.put(FakePortals.base(BETA, "Beta").owner(OTHER_OWNER).build());
        chest("destination-link-return", MenuInteraction.PRIMARY, null);
        assertEquals(3, destinations.calls.size());
        assertEquals("§cOnly the portal owner or an administrator can edit this portal.", host.chest.lastNotice());
    }

    @Test
    void selectionListsManageablePortalsAndOpensTheirHome() {
        destinations.portals.addAll(List.of(
            new DestinationMenuView(GAMMA, "Gamma", "world", 5, 70, 5, Face.W, Double.MAX_VALUE, false, null, true),
            new DestinationMenuView(BETA, "Beta", "world", 1, 64, 2, Face.N, Double.MAX_VALUE, false, null, true),
            new DestinationMenuView(FakePortals.PORTAL, "Alpha", "world", 0, 64, 0, Face.S, Double.MAX_VALUE, false, null, true)));
        service.open(VIEWER, DestinationRoutes.select());
        MenuBuild build = host.chest.last();

        assertEquals(new ChestLayout("", 6, ChestPane.GRAY, null), build.chest());
        assertEquals(DestinationRoutes.SELECT, build.screen().screenId());
        assertEquals("Choose a Portal", build.screen().title());
        assertEquals("", build.screen().context());
        assertEquals(MenuScreens.mapOf(
            "destination-sort", "Cycle -2,5 minecraft:comparator sort {}",
            "portal-0", "ListEntry -4,0 minecraft:ender_pearl portal {}",
            "portal-1", "ListEntry -3,0 minecraft:ender_pearl portal {}",
            "destination-page", "Label 0,5 minecraft:paper - {} hidden"), MenuScreens.layout(build.screen()));
        assertEquals("§6Alpha", element("portal-0").label());
        assertEquals("§6Beta", element("portal-1").label());
        MenuScreens.assertBound(build);

        chest("portal-1", MenuInteraction.PRIMARY, null);
        assertEquals(PortalRoutes.HOME, host.chest.screen().screenId());
        assertFalse(host.chest.screen().canGoBack());
        assertTrue(destinations.calls.isEmpty());

        service.open(VIEWER, DestinationRoutes.select());
        portals.put(FakePortals.base(BETA, "Beta").owner(OTHER_OWNER).build());
        host.chest.events.clear();
        chest("portal-1", MenuInteraction.PRIMARY, null);
        assertEquals(DestinationRoutes.SELECT, host.chest.screen().screenId());
        assertEquals(List.of("close", "notice:HUD"), host.chest.events);
        assertEquals("§cOnly the portal owner or an administrator can edit this portal.", host.chest.lastNotice());
    }

    @Test
    void escapingThePickerReturnsToPortalHome() {
        open();

        service.chestEscape(VIEWER);

        assertEquals(PortalRoutes.HOME, host.chest.screen().screenId());
    }

    @Test
    void viewersWhoCannotManageAreRejected() {
        destinations.candidates.addAll(List.of(BETA_VIEW, GAMMA_VIEW, REMOTE_VIEW));
        destinations.localDestination = BETA_VIEW;
        open();
        List<String> bound = MenuScreens.boundIds(host.chest.screen());
        assertEquals(List.of("destination-sort", "portal-0", "portal-1", "remote-portal-2", "destination-link-return"), bound);

        for (String elementId : bound) {
            portals.put(portals.view().toBuilder().owner(VIEWER).build());
            open();
            portals.put(portals.view().toBuilder().owner(OTHER_OWNER).build());
            host.chest.events.clear();

            chest(elementId, MenuInteraction.PRIMARY, null);

            assertTrue(destinations.calls.isEmpty(), elementId);
            assertTrue(portals.announcements.isEmpty(), elementId);
            assertEquals(List.of("close", "notice:HUD"), host.chest.events, elementId);
        }
    }

    @Test
    void staleNativeRevisionsNeverLink() throws MenuProtocolException {
        destinations.candidates.addAll(List.of(BETA_VIEW, GAMMA_VIEW));
        hello();
        open();
        long stale = revision();
        receive(new MenuClientMessage.Action(new MenuAction(stale, "destination-sort", MenuInteraction.PRIMARY, null)));

        receive(new MenuClientMessage.Action(new MenuAction(stale, "portal-1", MenuInteraction.PRIMARY, null)));

        assertTrue(destinations.calls.isEmpty());
        MenuServerMessage.Update resent = assertInstanceOf(MenuServerMessage.Update.class, host.lastMessage());
        assertEquals("The menu changed and was refreshed.", resent.screen().feedback().text());
    }

    @Test
    void refreshFollowsThePickedPortal() {
        DestinationMenuController controller = new DestinationMenuController(portals, destinations);
        MenuRoute picker = PortalRoutes.destinationPicker(FakePortals.PORTAL);

        assertEquals(PortalRoutes.DESTINATION_CONTROLLER, controller.id());
        assertEquals(0, controller.refreshIntervalTicks(picker));
        assertTrue(controller.touches(picker, new MenuScope.Portal(FakePortals.PORTAL)));
        assertFalse(controller.touches(picker, new MenuScope.Portal(BETA)));
        assertFalse(controller.touches(DestinationRoutes.select(), new MenuScope.Portal(FakePortals.PORTAL)));
        assertFalse(controller.touches(DestinationRoutes.select(), new MenuScope.Viewer(VIEWER)));
    }

    @Test
    void nativeLabelsNameTheSettingWithoutItsValue() {
        open();
        MenuScreen screen = host.chest.screen();

        MenuScreens.assertSettingLabels(screen);
        assertEquals("Sort", MenuText.stripColors(MenuScreens.element(screen, "destination-sort").common().wireLabel()));
    }

    private void open() {
        service.open(VIEWER, PortalRoutes.destinationPicker(FakePortals.PORTAL));
    }

    private void hello() {
        receive(new MenuClientMessage.Hello(1));
    }

    private void chest(String elementId, MenuInteraction interaction, MenuValue value) {
        service.chestAction(VIEWER, new MenuChestAction(elementId, interaction, value, false, null));
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

    private List<String> noticeTexts() {
        List<String> texts = new ArrayList<>(host.chest.notices.size());
        host.chest.notices.forEach(notice -> texts.add(notice.feedback().text()));
        return texts;
    }

    private MenuElement element(String id) {
        return MenuScreens.element(host.chest.screen(), id);
    }

    private int sortIndex() {
        return ((CycleElement) element("destination-sort")).index();
    }

    private static List<DestinationMenuView> numbered(int count) {
        List<DestinationMenuView> views = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            views.add(new DestinationMenuView(new UUID(7L, index), String.format(Locale.ROOT, "Stop %03d", index), "world", index, 64, 0, Face.N,
                index, false, null, true));
        }
        return views;
    }

    private record HomeStub() implements MenuController {
        @Override
        public String id() {
            return PortalRoutes.CONTROLLER;
        }

        @Override
        public MenuDecision routeGuard(MenuContext context) {
            return MenuDecision.allow();
        }

        @Override
        public MenuBuild build(MenuContext context) {
            MenuRoute picker = PortalRoutes.destinationPicker(PortalRoutes.portalId(context.route()));
            MenuScreen screen = MenuScreen.builder(context.route().screen())
                .elements(List.of(new ButtonElement(MenuElementCommon.builder("set-destination")
                    .interactions(Set.of(MenuInteraction.PRIMARY))
                    .chestSlot(new MenuSlot(0, 0))
                    .build())))
                .build();
            MenuBindings bindings = MenuBindings.builder()
                .guard("set-destination", MenuGuards.none())
                .on("set-destination", MenuInteraction.PRIMARY, (ctx, action) -> MenuResult.push(picker))
                .build();
            return new MenuBuild(screen, new ChestLayout("Home", 1, ChestPane.GRAY, null), bindings);
        }
    }
}
