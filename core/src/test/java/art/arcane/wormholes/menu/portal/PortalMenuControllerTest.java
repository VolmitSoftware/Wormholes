package art.arcane.wormholes.menu.portal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.QuarterTurn;
import art.arcane.optics.math.Face;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.menu.ChestClick;
import art.arcane.wormholes.menu.ChestClicks;
import art.arcane.wormholes.menu.ChestLayout;
import art.arcane.wormholes.menu.ChestReopen;
import art.arcane.wormholes.menu.ChestPane;
import art.arcane.wormholes.menu.ChestTarget;
import art.arcane.wormholes.menu.FakeMenuHost;
import art.arcane.wormholes.menu.FakeMenuViewer;
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
import art.arcane.wormholes.menu.MenuSession;
import art.arcane.wormholes.menu.MenuSlot;
import art.arcane.wormholes.menu.MenuStyle;
import art.arcane.wormholes.menu.MenuText;
import art.arcane.wormholes.menu.MenuValue;
import art.arcane.wormholes.menu.NoticeChannel;
import art.arcane.wormholes.menu.TextElement;
import art.arcane.wormholes.menu.ToggleElement;
import art.arcane.wormholes.menu.protocol.MenuClientMessage;
import art.arcane.wormholes.menu.protocol.MenuCodec;
import art.arcane.wormholes.menu.protocol.MenuProtocolException;
import art.arcane.wormholes.menu.protocol.MenuServerMessage;
import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.wormholes.portal.rtp.RtpAllocationMode;
import art.arcane.wormholes.portal.rtp.RtpRotationMode;

final class PortalMenuControllerTest {
    private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-0000000000b0");

    private FakeMenuHost host;
    private FakeMenuViewer viewer;
    private FakePortalMenuHost portals;
    private FakeGatewayMenuHost gateways;
    private MenuService service;

    @BeforeEach
    void setUp() {
        host = new FakeMenuHost();
        viewer = host.viewer(VIEWER, "Owner");
        portals = new FakePortalMenuHost();
        portals.managers.add(VIEWER);
        gateways = new FakeGatewayMenuHost();
        service = new MenuService(host, new MenuRegistry(List.of(new PortalMenuController(portals, gateways),
            new StubController(PortalRoutes.SETTINGS_CONTROLLER), new StubController(PortalRoutes.DESTINATION_CONTROLLER),
            new StubController(PortalRoutes.RTP_CONTROLLER))), () -> 1_000L);
    }

    @Test
    void homeMatchesTheChestWindowAndNativeTables() {
        open();
        MenuBuild build = host.chest.last();

        assertEquals(new ChestLayout("§0§lAlpha", 4, ChestPane.GRAY, null), build.chest());
        assertEquals("Portal", build.screen().title());
        assertEquals("Alpha - Portal", build.screen().context());
        assertEquals(mapOf(
            "portal-placard", "Label 0,0 minecraft:book portal {}",
            "set-destination", "Button -2,1 minecraft:ender_eye link {}",
            "toggle-projections", "Toggle 0,1 minecraft:redstone_torch* projection {}",
            "portal-settings", "Button 2,1 minecraft:lever settings {}",
            "set-name", "Text -2,2 minecraft:name_tag rename {}",
            "portal-orientation", "Button 0,2 minecraft:compass rotate {}",
            "set-mode", "Button 2,2 minecraft:ender_eye* portal {}",
            "destroy", "Button 0,3 minecraft:gunpowder delete {SHIFT_LEFT=P!}"), layout(build.screen()));
        assertEquals(MenuStyle.DESTRUCTIVE, element(build.screen(), "destroy").style());
        assertEquals(Map.of(ChestClick.SHIFT_LEFT, ChestTarget.preconfirmed(MenuInteraction.PRIMARY)),
            ChestClicks.resolve(element(build.screen(), "destroy")));
        assertTrue(element(build.screen(), "portal-placard").interactions().isEmpty());
        assertBound(build);
    }

    @Test
    void homeLoreMatchesTodaysChestText() {
        open();
        MenuScreen screen = host.chest.screen();

        assertEquals("§6§lAlpha§r", element(screen, "portal-placard").label());
        assertEquals(List.of("§7Type: §ePortal", "§7Facing: §9South", "§7Linked to: §cNone", "",
            "§8Operators bypass white/blacklist."), chestLore(element(screen, "portal-placard")));
        assertEquals(List.of("§7Change the name shown on links and menus.", "§7Current: §fAlpha", "",
            "§8Click to type a new name."), chestLore(element(screen, "set-name")));
        assertEquals(List.of("§7Change the name shown on links and menus.", "§7Current: §fAlpha"), element(screen, "set-name").description());
        assertEquals(List.of("§7Access: §6Blacklist", "§7Send §bOn§7  Receive §bOn"),
            chestLore(element(screen, "portal-settings")).subList(3, 5));
        TextElement name = (TextElement) element(screen, "set-name");
        assertEquals("Alpha", name.value());
        assertEquals(ChestReopen.ALWAYS, name.chestReopen());
        assertNull(name.chestCancelRoute());
        assertEquals(List.of("§bType the new portal name in chat (or 'cancel'):"), name.chestPrompt());
    }

    @Test
    void destinationTileFollowsThePortalKind() {
        portals.portal = FakePortalMenuHost.base().type(PortalType.GATEWAY).accessibleCount(150).build();
        open();
        MenuElement gateway = element(host.chest.screen(), "set-destination");
        assertEquals(MenuIcon.item("minecraft:end_crystal", 99, false), gateway.icon());
        assertEquals("§6§lPair & Destination§r", gateway.label());

        portals.portal = FakePortalMenuHost.base().type(PortalType.RTP)
            .rtp(new PortalMenuView.Rtp(RtpAllocationMode.PER_PLAYER, RtpRotationMode.TIMED, 300_000L)).build();
        open();
        MenuElement rtp = element(host.chest.screen(), "set-destination");
        assertEquals(MenuIcon.item("minecraft:compass"), rtp.icon());
        assertEquals(MenuIcon.sprite("rtp"), rtp.common().nativeIcon());
        assertTrue(rtp.description().contains("§7Rotation: §bPrivate every 5m"));
        assertTrue(chestLore(element(host.chest.screen(), "portal-placard")).contains("§7Allocation: §bPer-player"));
    }

    @Test
    void rtpSettingsBelongOnlyToRtpPortals() {
        PortalMenuView.Rtp rtp = new PortalMenuView.Rtp(RtpAllocationMode.SHARED, RtpRotationMode.STATIC, 0L);

        assertThrows(IllegalArgumentException.class, () -> FakePortalMenuHost.base().type(PortalType.GATEWAY).rtp(rtp).build());
        assertThrows(IllegalArgumentException.class, () -> FakePortalMenuHost.base().rtp(rtp).build());
        assertDoesNotThrow(() -> FakePortalMenuHost.base().type(PortalType.RTP).rtp(rtp).build());
        assertDoesNotThrow(() -> FakePortalMenuHost.base().type(PortalType.RTP).build());
    }

    @Test
    void destinationPushesTheFamilyForThePortalKind() {
        open();
        chest("set-destination");
        assertEquals(PortalRoutes.DESTINATION_PICKER, host.chest.screen().screenId());

        portals.portal = FakePortalMenuHost.base().type(PortalType.GATEWAY).build();
        open();
        chest("set-destination");
        assertEquals(PortalRoutes.GATEWAY, host.chest.screen().screenId());

        portals.portal = FakePortalMenuHost.base().type(PortalType.RTP)
            .rtp(new PortalMenuView.Rtp(RtpAllocationMode.SHARED, RtpRotationMode.STATIC, 0L)).build();
        open();
        chest("set-destination");
        assertEquals(PortalRoutes.RTP_OVERVIEW, host.chest.screen().screenId());

        open();
        chest("portal-settings");
        assertEquals(PortalRoutes.SETTINGS_MAIN, host.chest.screen().screenId());
    }

    @Test
    void projectionToggleFlipsTheModeAndNotifies() {
        open();

        chest("toggle-projections");

        assertEquals(List.of("Projection[mode=OFF]"), portals.edits);
        assertEquals("§aAlpha: §rProjections: Off", host.chest.lastNotice());
        assertEquals(NoticeChannel.SUCCESS_EFFECT, host.chest.notices.get(0).chestChannel());
        ToggleElement toggle = (ToggleElement) element(host.chest.screen(), "toggle-projections");
        assertFalse(toggle.value());
        assertEquals(MenuIcon.item("minecraft:torch"), toggle.icon());
    }

    @Test
    void receiverOnlyAndManagedPortalsRefuseWithTheirNotices() {
        portals.portal = FakePortalMenuHost.base().dimensionalKind(DimensionalPortalKind.END_ARRIVAL).twoWayEnd(false).build();
        open();

        chest("toggle-projections");
        chest("set-destination");
        chest("set-mode");

        assertTrue(portals.edits.isEmpty());
        assertEquals(List.of("§aAlpha: §rThe End arrival stays visually inactive.",
            "§aAlpha: §rThis dimensional portal keeps its generated link.",
            "§aAlpha: §rManaged dimensional portals stay in portal mode."), noticeTexts());
        assertEquals(PortalRoutes.HOME, host.chest.screen().screenId());
        assertTrue(menuOpen());
    }

    @Test
    void twoWayEndArrivalsSendTheirProjectionEditToTheHost() {
        portals.portal = FakePortalMenuHost.base().dimensionalKind(DimensionalPortalKind.END_ARRIVAL).twoWayEnd(true).build();
        open();

        chest("toggle-projections");

        assertEquals(List.of("Projection[mode=OFF]"), portals.edits);
        assertTrue(noticeTexts().stream().noneMatch(text -> text.contains("The End arrival stays visually inactive.")));
    }

    @Test
    void renameAppliesTextAndRejectsBlankNames() {
        open();

        service.chestAction(VIEWER, new MenuChestAction("set-name", MenuInteraction.SET_TEXT, MenuValue.text("   "), false, null));
        service.chestAction(VIEWER, new MenuChestAction("set-name", MenuInteraction.SET_TEXT, MenuValue.text("Garden Arch"), false, null));

        assertEquals(List.of("Name[name=Garden Arch]"), portals.edits);
        assertEquals("Garden Arch", ((TextElement) element(host.chest.screen(), "set-name")).value());
        assertEquals(PortalRoutes.HOME, host.chest.screen().screenId());
    }

    @Test
    void chestDeleteIsPreconfirmedByShiftClick() {
        open();

        service.chestAction(VIEWER, new MenuChestAction("destroy", MenuInteraction.PRIMARY, null, true, null));

        assertEquals(List.of("destroy"), portals.edits);
        assertEquals("close", host.chest.lastEvent());
        assertFalse(menuOpen());
    }

    @Test
    void nativeDeleteAsksFirst() throws MenuProtocolException {
        hello();
        open();

        nativeAction("destroy", MenuInteraction.PRIMARY, null);
        MenuServerMessage.Open confirm = assertInstanceOf(MenuServerMessage.Open.class, host.lastMessage());
        assertTrue(portals.edits.isEmpty());
        assertEquals(List.of("This removes the portal and unlinks portals that lead to it.", "This cannot be undone."),
            confirm.screen().confirmation().messageLines());
        assertEquals("Delete Alpha?", confirm.screen().element(MenuSession.CONFIRM_HEADER).orElseThrow().label());
        assertEquals("Delete", confirm.screen().element(MenuSession.CONFIRM_ACCEPT).orElseThrow().label());

        receive(new MenuClientMessage.Confirm(revision(), true));
        assertEquals(List.of("destroy"), portals.edits);
        assertInstanceOf(MenuServerMessage.Close.class, host.lastMessage());
    }

    @Test
    void staleActionsFromAnEarlierScreenNeverReachTheNextPortal() throws MenuProtocolException {
        portals.others.put(FakePortalMenuHost.SECOND, FakePortalMenuHost.base(FakePortalMenuHost.SECOND, "Beta").build());
        hello();
        open();
        long stale = revision();

        service.open(VIEWER, PortalRoutes.home(FakePortalMenuHost.SECOND));
        receive(new MenuClientMessage.Action(new MenuAction(stale, "toggle-projections", MenuInteraction.PRIMARY, null)));

        assertTrue(portals.edits.isEmpty());
        MenuServerMessage.Update resent = assertInstanceOf(MenuServerMessage.Update.class, host.lastMessage());
        assertEquals("Beta - Portal", resent.screen().context());
        assertTrue(resent.screen().revision() > stale);
        assertEquals("The menu changed and was refreshed.", resent.screen().feedback().text());
    }

    @Test
    void modeChooserMatchesTheChestWindowAndNativeTables() {
        open();
        chest("set-mode");
        MenuBuild build = host.chest.last();

        assertEquals(new ChestLayout("§0§lAlpha", 3, ChestPane.GRAY, PortalRoutes.home(FakePortalMenuHost.PORTAL)), build.chest());
        assertEquals("Portal Mode", build.screen().title());
        assertEquals(mapOf(
            "mode-placard", "Label 0,0 minecraft:beacon - {}",
            "mode-portal", "ListEntry -4,1 minecraft:ender_eye* portal {}",
            "mode-wormhole", "ListEntry -2,1 minecraft:ender_pearl wormhole {}",
            "mode-gateway", "ListEntry 0,1 minecraft:end_crystal gateway {}",
            "mode-rtp", "ListEntry 2,1 minecraft:compass rtp {}",
            "mode-mirror", "ListEntry 4,1 minecraft:copper_torch mirror {LEFT=P, RIGHT=S, SHIFT_RIGHT=T}",
            "back-to-portal", "Button 0,2 minecraft:arrow - {} hidden"), layout(build.screen()));
        assertTrue(((ListEntryElement) element(build.screen(), "mode-portal")).selected());
        assertFalse(((ListEntryElement) element(build.screen(), "mode-mirror")).selected());
        assertEquals(List.of(MenuInteraction.SECONDARY, MenuInteraction.TERTIARY),
            ((ListEntryElement) element(build.screen(), "mode-mirror")).secondaryActions().stream().map(action -> action.interaction()).toList());
        assertEquals(List.of("§7Basic linkable portal.", "", "§aCurrently Selected"), chestLore(element(build.screen(), "mode-portal")));
        assertBound(build);
    }

    @Test
    void escapingTheModeChooserReturnsHome() {
        open();
        chest("set-mode");

        service.chestEscape(VIEWER);

        assertEquals(PortalRoutes.HOME, host.chest.screen().screenId());
    }

    @Test
    void typeChangesNeedTheTypePermission() {
        open();
        chest("set-mode");

        chest("mode-wormhole");
        assertTrue(portals.edits.isEmpty());
        assertEquals("§8[§6Wormholes§8] §cYou do not have permission.", host.chest.lastNotice());
        assertEquals(NoticeChannel.FAILURE_EFFECT, host.chest.notices.get(0).chestChannel());
        assertFalse(menuOpen());

        viewer.permissions.add(PortalType.WORMHOLE.permission());
        open();
        chest("set-mode");
        chest("mode-wormhole");
        assertEquals(List.of("Type[type=WORMHOLE]"), portals.edits);
        assertEquals(List.of("portal.mode.changed"), portals.announcements);
        assertEquals("§aAlpha mode set to Wormhole.", host.chest.lastNotice());
        assertEquals(NoticeChannel.NONE, host.chest.notices.get(host.chest.notices.size() - 1).chestChannel());
        assertFalse(menuOpen());
    }

    @Test
    void choosingTheCurrentTypeLeavesMirrorWithoutAPermissionCheck() {
        portals.portal = FakePortalMenuHost.base().mirror(true).build();
        open();
        chest("set-mode");

        chest("mode-portal");

        assertEquals(List.of("Mirror[enabled=false]"), portals.edits);
        assertFalse(menuOpen());
    }

    @Test
    void mirrorRotationNeedsMirrorAndFollowsTheFrame() {
        open();
        chest("set-mode");
        chest("mode-mirror", MenuInteraction.SECONDARY);
        assertTrue(portals.edits.isEmpty());
        assertEquals("§aAlpha: §rChoose Mirror before rotating the image.", host.chest.lastNotice());

        portals.portal = portals.portal.toBuilder().mirror(true).build();
        chest("mode-mirror", MenuInteraction.SECONDARY);
        assertEquals(QuarterTurn.DEGREES_180, portals.portal.mirrorRotation());
        assertEquals("§aAlpha: §rMirror Rotation 180 degrees", host.chest.lastNotice());
        chest("mode-mirror", MenuInteraction.TERTIARY);
        assertEquals(QuarterTurn.DEGREES_0, portals.portal.mirrorRotation());
        assertEquals(PortalRoutes.MODE, host.chest.screen().screenId());
        List<String> lore = chestLore(element(host.chest.screen(), "mode-mirror"));
        assertEquals("§7Image rotation: §b0 degrees", lore.get(5));
        assertEquals("§8Right click: flip the reflected image.", lore.get(lore.size() - 1));
    }

    @Test
    void floorMirrorsRotateInQuarterTurns() {
        portals.portal = FakePortalMenuHost.base().mirror(true).frame(Frame.canonical(Face.U)).build();
        open();
        chest("set-mode");

        chest("mode-mirror", MenuInteraction.SECONDARY);

        assertEquals(QuarterTurn.DEGREES_90, portals.portal.mirrorRotation());
        List<String> lore = chestLore(element(host.chest.screen(), "mode-mirror"));
        assertEquals(List.of("§8Right click: rotate clockwise.", "§8Shift + right click: rotate counterclockwise."), lore.subList(lore.size() - 2, lore.size()));
    }

    @Test
    void selectingMirrorEnablesItAnnouncesAndCloses() {
        open();
        chest("set-mode");

        chest("mode-mirror");

        assertEquals(List.of("Mirror[enabled=true]"), portals.edits);
        assertEquals(List.of("portal.mode.changed"), portals.announcements);
        assertFalse(menuOpen());
    }

    @Test
    void orientationMatchesTheChestWindowAndReturnsHomeAfterChanges() {
        open();
        chest("portal-orientation");
        MenuBuild build = host.chest.last();
        assertEquals(new ChestLayout("§0§lAlpha", 3, ChestPane.BLUE, null), build.chest());
        assertEquals(mapOf(
            "orientation-placard", "Label 0,0 minecraft:compass - {}",
            "set-direction", "LookCapture -3,1 minecraft:compass rotate {}",
            "flip-face", "Button -1,1 minecraft:target rotate {}",
            "rotate-counter-clockwise", "Button 1,1 minecraft:repeater rotate {}",
            "rotate-clockwise", "Button 3,1 minecraft:lever rotate {}",
            "back-to-portal", "Button 0,2 minecraft:arrow - {} hidden"), layout(build.screen()));
        assertBound(build);

        chest("flip-face");
        assertEquals(List.of("FrameChange[operation=FLIP_FACE]"), portals.edits);
        assertEquals(List.of("portal.orientation.face_flipped"), portals.announcements);
        assertEquals("§aAlpha face flipped to North.", host.chest.lastNotice());
        assertEquals(PortalRoutes.HOME, host.chest.screen().screenId());

        chest("portal-orientation");
        chest("rotate-clockwise");
        assertEquals("FrameChange[operation=ROTATE_CLOCKWISE]", portals.edits.get(1));
        assertEquals(PortalRoutes.HOME, host.chest.screen().screenId());
    }

    @Test
    void orientationAndBackElementsPopToHomeEvenWhenOpenedDirectly() {
        service.open(VIEWER, PortalRoutes.orientation(FakePortalMenuHost.PORTAL));
        chest("rotate-counter-clockwise");
        assertEquals(PortalRoutes.HOME, host.chest.screen().screenId());
        assertFalse(host.chest.screen().canGoBack());

        for (MenuRoute route : List.of(PortalRoutes.mode(FakePortalMenuHost.PORTAL), PortalRoutes.orientation(FakePortalMenuHost.PORTAL))) {
            service.open(VIEWER, route);
            chest("back-to-portal");
            assertEquals(PortalRoutes.HOME, host.chest.screen().screenId(), route.screen());
            assertTrue(menuOpen(), route.screen());
        }
    }

    @Test
    void directionPromptSuspendsTheChestMenuAndResumeEndsIt() {
        open();
        chest("portal-orientation");

        chest("set-direction");

        assertEquals(List.of("direction-prompt"), portals.edits);
        assertEquals("close", host.chest.lastEvent());
        assertTrue(service.chestPrecheck(VIEWER, "flip-face", MenuInteraction.PRIMARY));

        int events = host.chest.events.size();
        service.resume(VIEWER);
        assertEquals(events, host.chest.events.size());
        assertFalse(service.chestPrecheck(VIEWER, "flip-face", MenuInteraction.PRIMARY));
    }

    @Test
    void directionPromptSuspendsTheNativeMenuAndResumeReopensOrientation() throws MenuProtocolException {
        hello();
        open();
        nativeAction("portal-orientation", MenuInteraction.PRIMARY, null);
        long suspendedRevision = revision();

        nativeAction("set-direction", MenuInteraction.PRIMARY, null);
        assertEquals(List.of("direction-prompt"), portals.edits);
        assertInstanceOf(MenuServerMessage.Close.class, host.lastMessage());

        service.resume(VIEWER);
        MenuServerMessage.Open reopened = assertInstanceOf(MenuServerMessage.Open.class, host.lastMessage());
        assertEquals(PortalRoutes.ORIENTATION, reopened.screen().screenId());
        assertTrue(reopened.screen().revision() > suspendedRevision);
    }

    @Test
    void gatewayPairingMatchesTheChestWindow() {
        portals.portal = FakePortalMenuHost.base().type(PortalType.GATEWAY).build();
        gateways.view = new GatewayView("Remote", "lobby", GatewayLinkState.SIDEBAND);
        open();
        chest("set-destination");
        MenuBuild build = host.chest.last();

        assertEquals(new ChestLayout("§0§lAlpha", 3, ChestPane.BLACK, null), build.chest());
        assertEquals(mapOf(
            "gateway-pair-placard", "Label 0,0 minecraft:respawn_anchor gateway {}",
            "export-portal", "Button -2,1 minecraft:paper network {}",
            "choose-gateway-destination", "Button 0,1 minecraft:end_crystal link {}",
            "import-portal", "Text 2,1 minecraft:writable_book network {}",
            "back-to-portal", "Button 0,2 minecraft:arrow - {} hidden"), layout(build.screen()));
        assertEquals(List.of("§7Destination: §6Remote", "§7Server: §flobby", "§7Link: §bSideband"),
            element(build.screen(), "gateway-pair-placard").description());
        TextElement importer = (TextElement) element(build.screen(), "import-portal");
        assertEquals(ChestReopen.ON_CANCEL, importer.chestReopen());
        assertNull(importer.chestCancelRoute());
        assertBound(build);

        service.chestAction(VIEWER, new MenuChestAction("import-portal", MenuInteraction.SET_TEXT, MenuValue.text("WHG1-abc"), false, null));
        assertEquals(List.of("import:WHG1-abc"), gateways.calls);
        assertEquals(PortalRoutes.GATEWAY, host.chest.screen().screenId());

        chest("export-portal");
        assertEquals(List.of("import:WHG1-abc", "export"), gateways.calls);
        assertEquals("close", host.chest.lastEvent());
    }

    @Test
    void rejectedInvitesAnswerWithTheirReason() {
        portals.portal = FakePortalMenuHost.base().type(PortalType.GATEWAY).build();
        gateways.outcome = ImportOutcome.rejected(WormholesMessages.NETWORK_CODE_INVALID,
            MessageArgs.builder().untrusted("prefix", "WHG1").build());
        open();
        chest("set-destination");

        service.chestAction(VIEWER, new MenuChestAction("import-portal", MenuInteraction.SET_TEXT, MenuValue.text("bad"), false, null));

        assertTrue(host.chest.lastNotice().contains("WHG1"));
    }

    @Test
    void refreshAndInvalidationScopes() {
        PortalMenuController controller = new PortalMenuController(portals, gateways);
        MenuRoute home = PortalRoutes.home(FakePortalMenuHost.PORTAL);

        assertEquals(0, controller.refreshIntervalTicks(home));
        assertEquals(PortalMenuController.GATEWAY_REFRESH_TICKS, controller.refreshIntervalTicks(PortalRoutes.gateway(FakePortalMenuHost.PORTAL)));
        assertTrue(controller.touches(home, new MenuScope.Portal(FakePortalMenuHost.PORTAL)));
        assertFalse(controller.touches(home, new MenuScope.Portal(UUID.randomUUID())));
        assertFalse(controller.touches(home, new MenuScope.Viewer(VIEWER)));
    }

    @Test
    void invalidationRepaintsHome() {
        open();
        portals.portal = portals.portal.toBuilder().projection(ProjectionMode.OFF).build();

        service.invalidate(new MenuScope.Portal(FakePortalMenuHost.PORTAL));

        assertEquals("update:portal.home@2", host.chest.lastEvent());
    }

    @Test
    void viewersWhoCannotManageAreRejectedEverywhere() {
        portals.managers.clear();
        service.open(VIEWER, PortalRoutes.home(FakePortalMenuHost.PORTAL));
        assertEquals("§cOnly the portal owner or an administrator can edit this portal.", host.chest.lastNotice());
        assertTrue(host.chest.events.stream().noneMatch(event -> event.startsWith("open:")));

        portals.portal = FakePortalMenuHost.base().type(PortalType.GATEWAY).build();
        for (MenuRoute route : List.of(PortalRoutes.home(FakePortalMenuHost.PORTAL), PortalRoutes.mode(FakePortalMenuHost.PORTAL),
            PortalRoutes.orientation(FakePortalMenuHost.PORTAL), PortalRoutes.gateway(FakePortalMenuHost.PORTAL))) {
            for (String elementId : boundElements(route)) {
                portals.managers.add(VIEWER);
                service.open(VIEWER, route);
                portals.managers.clear();
                MenuElement element = element(host.chest.screen(), elementId);
                MenuInteraction interaction = element.interactions().iterator().next();
                MenuValue value = interaction == MenuInteraction.SET_TEXT ? MenuValue.text("x") : null;
                host.chest.events.clear();
                service.chestAction(VIEWER, new MenuChestAction(elementId, interaction, value, true, null));
                assertTrue(portals.edits.isEmpty(), route.screen() + "/" + elementId);
                assertTrue(gateways.calls.isEmpty(), route.screen() + "/" + elementId);
                assertTrue(host.chest.events.contains("close"), route.screen() + "/" + elementId);
            }
        }
    }

    @Test
    void vanishedPortalsCloseTheMenu() {
        open();
        portals.present = false;

        chest("toggle-projections");

        assertTrue(portals.edits.isEmpty());
        assertTrue(host.chest.events.contains("close"));
    }

    private List<String> boundElements(MenuRoute route) {
        portals.managers.add(VIEWER);
        service.open(VIEWER, route);
        portals.managers.clear();
        List<String> ids = new ArrayList<>();
        for (MenuElement element : host.chest.screen().elements()) {
            if (!element.interactions().isEmpty()) {
                ids.add(element.id());
            }
        }
        service.quit(VIEWER);
        return ids;
    }

    private void assertBound(MenuBuild build) {
        for (MenuElement element : build.screen().elements()) {
            for (MenuInteraction interaction : element.interactions()) {
                assertTrue(build.bindings().find(element.id()).flatMap(binding -> binding.handler(interaction)).isPresent(),
                    element.id() + " " + interaction);
            }
        }
    }

    @Test
    void nativeLabelsNameTheSettingWithoutItsValue() {
        open();
        MenuScreen screen = host.chest.screen();

        MenuScreens.assertSettingLabels(screen);
        assertEquals("Projection", MenuText.stripColors(MenuScreens.element(screen, "toggle-projections").common().wireLabel()));
    }

    private void open() {
        service.open(VIEWER, PortalRoutes.home(FakePortalMenuHost.PORTAL));
    }

    private void hello() {
        receive(new MenuClientMessage.Hello(1));
    }

    private void chest(String elementId) {
        chest(elementId, MenuInteraction.PRIMARY);
    }

    private void chest(String elementId, MenuInteraction interaction) {
        service.chestAction(VIEWER, new MenuChestAction(elementId, interaction, null, false, null));
    }

    private boolean menuOpen() {
        return service.chestPrecheck(VIEWER, "set-name", MenuInteraction.SET_TEXT);
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
        return host.screenRevision();
    }

    private List<String> noticeTexts() {
        List<String> texts = new ArrayList<>();
        host.chest.notices.forEach(notice -> texts.add(notice.feedback().text()));
        return texts;
    }

    private static MenuElement element(MenuScreen screen, String id) {
        return screen.element(id).orElseThrow();
    }

    private static List<String> chestLore(MenuElement element) {
        List<String> lore = new ArrayList<>(element.description());
        lore.addAll(element.common().chestHints());
        return lore;
    }

    private static Map<String, String> mapOf(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            map.put(pairs[index], pairs[index + 1]);
        }
        return map;
    }

    private static Map<String, String> layout(MenuScreen screen) {
        Map<String, String> layout = new LinkedHashMap<>();
        for (MenuElement element : screen.elements()) {
            MenuSlot slot = element.chestSlot();
            MenuIcon.Item item = (MenuIcon.Item) element.icon();
            MenuIcon nativeIcon = element.common().nativeIcon();
            String sprite = nativeIcon instanceof MenuIcon.Sprite named ? named.name() : "-";
            StringBuilder clicks = new StringBuilder("{");
            for (Map.Entry<ChestClick, ChestTarget> click : slot.clicks().entrySet()) {
                if (clicks.length() > 1) {
                    clicks.append(", ");
                }
                ChestTarget target = click.getValue();
                clicks.append(click.getKey()).append('=').append(target.interaction().name().charAt(0)).append(target.preconfirmed() ? "!" : "");
            }
            clicks.append('}');
            String kind = element.getClass().getSimpleName().replace("Element", "");
            layout.put(element.id(), kind + " " + slot.position() + "," + slot.row() + " " + item.itemId() + (item.glint() ? "*" : "") + " "
                + sprite + " " + clicks + (element.common().nativeHidden() ? " hidden" : ""));
        }
        return layout;
    }
}
