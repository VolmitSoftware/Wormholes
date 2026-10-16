package art.arcane.wormholes.modded.menu.host;

import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.volmlib.util.localization.LocalizationCandidate;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.PluralSelector;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.menu.MenuAction;
import art.arcane.wormholes.menu.MenuBuild;
import art.arcane.wormholes.menu.MenuCaptures;
import art.arcane.wormholes.menu.MenuChestAction;
import art.arcane.wormholes.menu.MenuHost;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuNotice;
import art.arcane.wormholes.menu.MenuRegistry;
import art.arcane.wormholes.menu.MenuRenderer;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuService;
import art.arcane.wormholes.menu.MenuTransport;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.portal.FrameOperation;
import art.arcane.wormholes.menu.portal.GatewayMenuHost;
import art.arcane.wormholes.menu.portal.PortalEdit;
import art.arcane.wormholes.menu.portal.PortalEditOutcome;
import art.arcane.wormholes.menu.portal.PortalMenuController;
import art.arcane.wormholes.menu.portal.PortalMenuView;
import art.arcane.wormholes.menu.portal.PortalRoutes;
import art.arcane.wormholes.menu.protocol.MenuClientMessage;
import art.arcane.wormholes.menu.protocol.MenuCodec;
import art.arcane.wormholes.menu.protocol.MenuProtocolException;
import art.arcane.wormholes.menu.protocol.MenuServerMessage;
import art.arcane.wormholes.modded.MinecraftAccessService;
import art.arcane.wormholes.modded.MinecraftLocalization;
import art.arcane.wormholes.modded.MinecraftMenuText;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftPortalRegistry;
import art.arcane.wormholes.modded.MinecraftPortalText;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.MinecraftTestSettings;
import art.arcane.wormholes.modded.WormholesModConfiguration;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.menu.MinecraftMenuViewer;
import art.arcane.wormholes.portal.ApertureShapePresets;
import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.portal.Portal;
import art.arcane.wormholes.portal.PortalTravelMode;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.wormholes.portal.rtp.MinecraftRtpRuntime;
import art.arcane.wormholes.portal.rtp.RtpSettings;
import art.arcane.wormholes.portal.rtp.RtpWorld;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftPortalMenuHostTest extends MinecraftTestBase {
    private static final UUID OWNER = UUID.fromString("e1b2c3d4-0000-4000-8000-000000000001");
    private static final UUID PORTAL = UUID.fromString("e1b2c3d4-0000-4000-8000-0000000000aa");

    private WormholesModRuntime runtime;
    private MinecraftPortalRegistry portals;
    private MenuService menus;
    private MinecraftAccessService access;
    private WormholesSettings settings;
    private ServerPlayer player;
    private MenuViewer viewer;
    private MinecraftPortal portal;
    private MinecraftPortalMenuHost host;

    @Before
    public void setUp() {
        runtime = mock(WormholesModRuntime.class);
        portals = mock(MinecraftPortalRegistry.class);
        menus = mock(MenuService.class);
        access = mock(MinecraftAccessService.class);
        settings = MinecraftTestSettings.defaults();
        WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
        when(configuration.settings()).thenReturn(settings);
        player = mock(ServerPlayer.class);
        when(runtime.portals()).thenReturn(portals);
        when(runtime.menus()).thenReturn(menus);
        when(runtime.access()).thenReturn(access);
        when(runtime.configuration()).thenReturn(configuration);
        when(player.getUUID()).thenReturn(OWNER);
        portal = portal(PortalType.PORTAL);
        when(portals.get(PORTAL)).thenReturn(portal);
        when(portals.snapshot()).thenReturn(List.of(portal));
        when(portals.update(eq(player), eq(PORTAL), any())).thenAnswer(invocation -> {
            Consumer<MinecraftPortal> mutation = invocation.getArgument(2);
            mutation.accept(portal);
            return true;
        });
        viewer = new MinecraftMenuViewer(runtime, player);
        host = new MinecraftPortalMenuHost(runtime);
    }

    @Test
    public void portalViewsMirrorThePortalWithoutRtpSettingsForOtherTypes() {
        PortalMenuView view = host.portal(PORTAL).orElseThrow();

        assertEquals(PORTAL, view.id());
        assertEquals("Alpha", view.name());
        assertEquals(PortalType.PORTAL, view.type());
        assertEquals(ProjectionMode.ON, view.projection());
        assertEquals(Face.N, view.frame().getNormal());
        assertEquals("§0§lAlpha", view.routerTitle());
        assertEquals(1, view.accessibleCount());
        assertEquals(PortalMenuView.Cost.FREE, view.cost());
        assertEquals("minecraft:glass", view.surfaceSkinItemId());
        assertNull(view.destination());
        assertNull(view.rtp());
        assertTrue(host.portal(UUID.randomUUID()).isEmpty());
    }

    @Test
    public void portalViewsShowTheNameNodeAndTheOpenCellsInsideTheShape() {
        ApertureCells geometry = new ApertureCells();
        List<Vec3d> cells = new ArrayList<>(6);
        for (int x = 0; x < 2; x++) {
            for (int y = 64; y < 67; y++) {
                cells.add(new Vec3d(x, y, 0));
            }
        }
        geometry.setBlocks(cells);
        MinecraftPortal shaped = new MinecraftPortal(new MinecraftPortal.Definition(new Portal.State(PORTAL, geometry.getApertureCenter(), "Alpha",
            Frame.canonical(Face.N), true), geometry, "minecraft:overworld", new HashMap<>(Map.of("owner", OWNER.toString(), "type", "PORTAL"))));
        when(portals.get(PORTAL)).thenReturn(shaped);
        shaped.setName("Harbor Gate");
        ShapeDescriptor circle = ApertureShapePresets.next(ShapeDescriptor.FULL);
        assertTrue(shaped.acceptsApertureShape(circle));
        shaped.setApertureShape(circle);

        PortalMenuView view = host.portal(PORTAL).orElseThrow();

        assertEquals("wormholes.portal.harbor_gate", view.permissionNode());
        assertEquals(shaped.getGeometry().getBlockPositions().size(), view.openCellCount());
        assertTrue(view.openCellCount() < shaped.getBuiltGeometry().getBlockPositions().size());
    }

    @Test
    public void rtpPortalsCarryTheirRotationSettings() {
        MinecraftPortal rtp = portal(PortalType.RTP);
        MinecraftRtpRuntime rtpRuntime = mock(MinecraftRtpRuntime.class);
        RtpSettings settings = RtpSettings.defaults(new RtpWorld(UUID.randomUUID(), "minecraft:overworld", -64, 320, 63));
        when(runtime.rtp()).thenReturn(rtpRuntime);
        when(rtpRuntime.settings(rtp)).thenReturn(settings);
        when(portals.get(PORTAL)).thenReturn(rtp);

        PortalMenuView view = host.portal(PORTAL).orElseThrow();

        assertNotNull(view.rtp());
        assertEquals(settings.getAllocationMode(), view.rtp().allocation());
        assertEquals(settings.getRotationMode(), view.rtp().rotation());
        assertEquals(settings.getCycleDurationMillis(), view.rtp().cycleMillis());
    }

    @Test
    public void manageChecksAskTheRegistryForThatPortal() {
        when(portals.canManage(player, portal)).thenReturn(true);
        assertTrue(host.canManage(viewer, PORTAL));

        when(portals.canManage(player, portal)).thenReturn(false);
        assertFalse(host.canManage(viewer, PORTAL));
        assertFalse(host.canManage(viewer, UUID.randomUUID()));
    }

    @Test
    public void ownerOrAdminIgnoresAccessRoles() {
        assertTrue(host.ownerOrAdmin(viewer, PORTAL));

        when(player.getUUID()).thenReturn(UUID.randomUUID());
        MenuViewer stranger = new MinecraftMenuViewer(runtime, player);
        assertFalse(host.ownerOrAdmin(stranger, PORTAL));

        when(access.administrator(player)).thenReturn(true);
        assertTrue(host.ownerOrAdmin(stranger, PORTAL));
    }

    @Test
    public void typeAccessFollowsAdministratorOrTheTypePermission() {
        assertFalse(host.typeAllowed(viewer, PortalType.WORMHOLE));

        when(access.permission(player, PortalType.WORMHOLE.permission())).thenReturn(true);
        assertTrue(host.typeAllowed(viewer, PortalType.WORMHOLE));

        when(access.administrator(player)).thenReturn(true);
        assertTrue(host.typeAllowed(viewer, PortalType.GATEWAY));
    }

    @Test
    public void surfaceSkinsNeedTheAdminNodeAndManageRights() {
        when(portals.canManage(player, portal)).thenReturn(true);
        assertFalse(host.canEditSurfaceSkin(viewer, PORTAL));

        when(access.permission(player, "wormholes.admin")).thenReturn(true);
        assertTrue(host.canEditSurfaceSkin(viewer, PORTAL));
    }

    @Test
    public void editsRunThroughTheRegistryAndInvalidateThePortal() {
        PortalEditOutcome renamed = host.edit(viewer, PORTAL, new PortalEdit.Name("Beta"));
        PortalEditOutcome flipped = host.edit(viewer, PORTAL, new PortalEdit.FrameChange(FrameOperation.FLIP_FACE));

        assertEquals(PortalEditOutcome.applied(), renamed);
        assertEquals(PortalEditOutcome.applied(), flipped);
        assertEquals("Beta", portal.getName());
        assertEquals(Face.S, portal.getDirection());
        verify(portals, times(2)).update(eq(player), eq(PORTAL), any());
        verify(menus, times(2)).invalidate(new MenuScope.Portal(PORTAL));
    }

    @Test
    public void editsTheRegistryRefusesAreDeniedWithoutInvalidating() {
        when(portals.update(eq(player), eq(PORTAL), any())).thenReturn(false);

        PortalEditOutcome outcome = host.edit(viewer, PORTAL, new PortalEdit.Name("Beta"));

        assertEquals(PortalEditOutcome.refused(WormholesMessages.PORTAL_EDIT_DENIED, MessageArgs.empty()), outcome);
        assertEquals("Alpha", portal.getName());
        verify(menus, never()).invalidate(any());
    }

    @Test
    public void editsOfMissingPortalsAreDenied() {
        assertTrue(host.edit(viewer, UUID.randomUUID(), new PortalEdit.Name("Beta")) instanceof PortalEditOutcome.Refused);
    }

    @Test
    public void endPortalsKeepTheirProjectionForcedOn() {
        portal.setDimensionalKind(DimensionalPortalKind.END_SOURCE);

        assertEquals(PortalEditOutcome.applied(), host.edit(viewer, PORTAL, new PortalEdit.Projection(ProjectionMode.OFF)));
        assertEquals(ProjectionMode.ON, portal.getProjectionMode());

        portal.setDimensionalKind(DimensionalPortalKind.END_EXIT);
        host.edit(viewer, PORTAL, new PortalEdit.Projection(ProjectionMode.OFF));
        assertEquals(ProjectionMode.ON, portal.getProjectionMode());

        portal.setDimensionalKind(DimensionalPortalKind.NONE);
        host.edit(viewer, PORTAL, new PortalEdit.Projection(ProjectionMode.OFF));
        assertEquals(ProjectionMode.OFF, portal.getProjectionMode());
    }

    @Test
    public void twoWayEndArrivalsKeepTheirProjectionForcedOn() {
        portal.setDimensionalKind(DimensionalPortalKind.END_ARRIVAL);
        portal.setProjectionMode(ProjectionMode.OFF);

        assertFalse(host.portal(PORTAL).orElseThrow().receiverOnly());
        assertEquals(PortalEditOutcome.applied(), host.edit(viewer, PORTAL, new PortalEdit.Projection(ProjectionMode.OFF)));
        assertEquals(ProjectionMode.ON, portal.getProjectionMode());

        settings.getMain().twoWayEndPortals = false;
        assertTrue(host.portal(PORTAL).orElseThrow().receiverOnly());
        assertEquals(PortalEditOutcome.refused(WormholesMessages.PORTAL_PROJECTION_RECEIVER_INACTIVE, MessageArgs.empty()),
            host.edit(viewer, PORTAL, new PortalEdit.Projection(ProjectionMode.OFF)));
        assertEquals(ProjectionMode.ON, portal.getProjectionMode());
    }

    @Test
    public void receiverOnlyProjectionAndUnmirroredRotationsAreRefusedBeforeTheRegistry() {
        settings.getMain().twoWayEndPortals = false;
        portal.setDimensionalKind(DimensionalPortalKind.END_ARRIVAL);

        PortalEditOutcome projection = host.edit(viewer, PORTAL, new PortalEdit.Projection(ProjectionMode.ON));
        PortalEditOutcome rotation = host.edit(viewer, PORTAL, new PortalEdit.MirrorRotation(true));

        assertEquals(PortalEditOutcome.refused(WormholesMessages.PORTAL_PROJECTION_RECEIVER_INACTIVE, MessageArgs.empty()), projection);
        assertEquals(PortalEditOutcome.refused(WormholesMessages.PORTAL_MIRROR_SELECT_FIRST, MessageArgs.empty()), rotation);
        verify(portals, never()).update(any(), any(), any());
    }

    @Test
    public void managedAndMirroredTravelAndTooSmallShapesAreRefusedBeforeTheRegistry() {
        portal.setDimensionalKind(DimensionalPortalKind.END_ARRIVAL);
        assertEquals(PortalEditOutcome.refused(WormholesMessages.PORTAL_TRAVEL_MANAGED, MessageArgs.empty()),
            host.edit(viewer, PORTAL, new PortalEdit.TravelMode(PortalTravelMode.OUTBOUND)));

        portal.setDimensionalKind(DimensionalPortalKind.NONE);
        portal.setMirrorMode(true);
        assertEquals(PortalEditOutcome.refused(WormholesMessages.PORTAL_TRAVEL_MIRROR_LOCKED, MessageArgs.empty()),
            host.edit(viewer, PORTAL, new PortalEdit.TravelMode(PortalTravelMode.OUTBOUND)));

        portal.setMirrorMode(false);
        ShapeDescriptor tiny = ShapeDescriptor.parse("circle(radius=0.05)");
        PortalEditOutcome shape = host.edit(viewer, PORTAL, new PortalEdit.ApertureShape(tiny));

        assertEquals(WormholesMessages.PORTAL_APERTURE_SHAPE_TOO_SMALL, ((PortalEditOutcome.Refused) shape).reason());
        assertEquals(tiny.format(), ((PortalEditOutcome.Refused) shape).arguments().require("shape").value());
        assertEquals(ShapeDescriptor.FULL, portal.getApertureShape());
        verify(portals, never()).update(any(), any(), any());
        verify(menus, never()).invalidate(any());
    }

    @Test
    public void directionPromptsWaitForThePunchThenApplyTheLookAndResume() {
        MinecraftLocalization localization = mock(MinecraftLocalization.class);
        when(runtime.localization()).thenReturn(localization);
        when(localization.snapshot(player)).thenReturn(mock(LocalizationSnapshot.class));
        when(player.getLookAngle()).thenReturn(new Vec3(1, 0, 0));
        try (MockedStatic<MinecraftMenuText> text = mockStatic(MinecraftMenuText.class);
             MockedStatic<MinecraftPortalText> portalText = mockStatic(MinecraftPortalText.class)) {
            text.when(() -> MinecraftMenuText.lines(any(), eq(WormholesMessages.PORTAL_PROMPT_DIRECTION), any())).thenReturn(List.of(Component.literal("Aim")));
            text.when(() -> MinecraftMenuText.text(eq(player), eq(WormholesMessages.PORTAL_DIRECTION_SET), any(MessageArgs.class)))
                .thenReturn(Component.literal("Set"));

            assertTrue(host.beginDirectionPrompt(viewer, PORTAL));
            assertTrue(host.choosingDirection(player));
            verify(menus, never()).resume(any());

            host.chooseDirection(player, false);

            assertFalse(host.choosingDirection(player));
            assertEquals(Face.E, portal.getDirection());
            verify(menus).invalidate(new MenuScope.Portal(PORTAL));
            verify(menus).resume(OWNER);
            portalText.verify(() -> MinecraftPortalText.notifyNearby(eq(runtime), eq(portal), eq(WormholesMessages.PORTAL_DIRECTION_CHANGED), any(),
                eq(true)));
            verify(player).sendSystemMessage(Component.literal("Aim"));
            verify(player).sendSystemMessage(Component.literal("Set"));
        }
    }

    @Test
    public void sneakingCancelsTheDirectionPromptAndStillResumes() {
        MinecraftLocalization localization = mock(MinecraftLocalization.class);
        when(runtime.localization()).thenReturn(localization);
        when(player.isShiftKeyDown()).thenReturn(true);
        try (MockedStatic<MinecraftMenuText> text = mockStatic(MinecraftMenuText.class)) {
            text.when(() -> MinecraftMenuText.lines(any(), any(), any())).thenReturn(List.of());
            host.beginDirectionPrompt(viewer, PORTAL);

            host.chooseDirection(player, false);
            host.chooseDirection(player, false);

            text.verify(() -> MinecraftMenuText.text(eq(player), eq(WormholesMessages.PORTAL_DIRECTION_CANCELLED), any(MessageArgs.class)));
        }
        assertEquals(Face.N, portal.getDirection());
        verify(portals, never()).update(any(), any(), any());
        verify(menus).resume(OWNER);
    }

    @Test
    public void finishingTheAimPromptEndsAChestSessionWithoutReopening() {
        SessionHost sessions = new SessionHost(viewer, false);
        MenuService service = aimingMenus(sessions);
        try (MockedStatic<MinecraftLocalization> localization = english();
             MockedStatic<MinecraftMenuText> text = mockStatic(MinecraftMenuText.class);
             MockedStatic<MinecraftPortalText> portalText = quietPortalText()) {
            text.when(() -> MinecraftMenuText.lines(any(), any(), any())).thenReturn(List.of());
            text.when(() -> MinecraftMenuText.text(eq(player), any(), any(MessageArgs.class))).thenReturn(Component.literal("East"));
            service.open(OWNER, PortalRoutes.home(PORTAL));
            service.chestAction(OWNER, new MenuChestAction("portal-orientation", MenuInteraction.PRIMARY, null, false, null));
            service.chestAction(OWNER, new MenuChestAction("set-direction", MenuInteraction.PRIMARY, null, false, null));
            assertTrue(sessions.closed);
            int shown = sessions.shows.size();

            host.chooseDirection(player, false);

            assertEquals(shown, sessions.shows.size());
            assertEquals(Face.E, portal.getDirection());
            assertFalse(service.chestPrecheck(OWNER, "flip-face", MenuInteraction.PRIMARY));
        }
    }

    @Test
    public void finishingTheAimPromptReopensTheOrientationScreenForNativeViewers() throws MenuProtocolException {
        SessionHost sessions = new SessionHost(viewer, true);
        MenuService service = aimingMenus(sessions);
        try (MockedStatic<MinecraftLocalization> localization = english();
             MockedStatic<MinecraftMenuText> text = mockStatic(MinecraftMenuText.class);
             MockedStatic<MinecraftPortalText> portalText = quietPortalText()) {
            text.when(() -> MinecraftMenuText.lines(any(), any(), any())).thenReturn(List.of());
            text.when(() -> MinecraftMenuText.text(eq(player), any(), any(MessageArgs.class))).thenReturn(Component.literal("East"));
            service.receive(OWNER, MenuCodec.encodeClient(new MenuClientMessage.Hello(1)));
            service.open(OWNER, PortalRoutes.home(PORTAL));
            service.receive(OWNER, MenuCodec.encodeClient(new MenuClientMessage.Action(
                new MenuAction(sessions.revision(), "portal-orientation", MenuInteraction.PRIMARY, null))));
            long suspended = sessions.revision();
            service.receive(OWNER, MenuCodec.encodeClient(new MenuClientMessage.Action(
                new MenuAction(suspended, "set-direction", MenuInteraction.PRIMARY, null))));
            assertTrue(sessions.lastMessage() instanceof MenuServerMessage.Close);

            host.chooseDirection(player, false);

            assertTrue(sessions.lastMessage() instanceof MenuServerMessage.Open);
            MenuServerMessage.Open reopened = (MenuServerMessage.Open) sessions.lastMessage();
            assertEquals(PortalRoutes.ORIENTATION, reopened.screen().screenId());
            assertTrue(reopened.screen().revision() > suspended);
            assertEquals(Face.E, portal.getDirection());
            assertTrue(sessions.shows.isEmpty());
        }
    }

    @Test
    public void unknownPortalsStartNoDirectionPrompt() {
        assertFalse(host.beginDirectionPrompt(viewer, UUID.randomUUID()));
        assertFalse(host.choosingDirection(player));
    }

    @Test
    public void disconnectingAndClosingForgetPrompts() {
        try (MockedStatic<MinecraftMenuText> text = mockStatic(MinecraftMenuText.class)) {
            text.when(() -> MinecraftMenuText.lines(any(), any(), any())).thenReturn(List.of());
            when(runtime.localization()).thenReturn(mock(MinecraftLocalization.class));
            host.beginDirectionPrompt(viewer, PORTAL);
            host.playerDisconnected(player);
            assertFalse(host.choosingDirection(player));

            host.beginDirectionPrompt(viewer, PORTAL);
            host.close();
            assertFalse(host.choosingDirection(player));
        }
        host.chooseDirection(player, false);
        verify(menus, never()).resume(any());
    }

    private MenuService aimingMenus(SessionHost sessions) {
        MenuService service = new MenuService(sessions, new MenuRegistry(List.of(new PortalMenuController(host, mock(GatewayMenuHost.class)))));
        when(runtime.menus()).thenReturn(service);
        when(portals.canManage(player, portal)).thenReturn(true);
        when(player.getLookAngle()).thenReturn(new Vec3(1, 0, 0));
        return service;
    }

    private static MockedStatic<MinecraftPortalText> quietPortalText() {
        MockedStatic<MinecraftPortalText> statics = mockStatic(MinecraftPortalText.class, CALLS_REAL_METHODS);
        statics.when(() -> MinecraftPortalText.notifyNearby(any(), any(), any(), any(), anyBoolean())).thenAnswer(invocation -> null);
        return statics;
    }

    private MockedStatic<MinecraftLocalization> english() {
        MinecraftLocalization localization = mock(MinecraftLocalization.class);
        when(localization.snapshot(player)).thenReturn(LocalizationSnapshot.create(
            LocalizationCandidate.english(WormholesMessages.catalog(), PluralSelector.oneOther())));
        when(runtime.localization()).thenReturn(localization);
        MockedStatic<MinecraftLocalization> statics = mockStatic(MinecraftLocalization.class);
        statics.when(() -> MinecraftLocalization.forPlayer(player)).thenReturn(localization);
        return statics;
    }

    private static MinecraftPortal portal(PortalType type) {
        ApertureCells geometry = new ApertureCells();
        geometry.setBlocks(List.of(new Vec3d(0, 64, 0), new Vec3d(0, 65, 0)));
        Map<String, Object> values = new HashMap<>();
        values.put("owner", OWNER.toString());
        values.put("type", type.name());
        return new MinecraftPortal(new MinecraftPortal.Definition(new Portal.State(PORTAL, geometry.getApertureCenter(), "Alpha",
            Frame.canonical(Face.N), true), geometry, "minecraft:overworld", values));
    }

    private static final class SessionHost implements MenuHost, MenuRenderer {
        private final MenuViewer viewer;
        private final boolean nativeScreens;
        private final List<MenuBuild> shows = new ArrayList<>();
        private final List<byte[]> frames = new ArrayList<>();
        private boolean closed;

        private SessionHost(MenuViewer viewer, boolean nativeScreens) {
            this.viewer = viewer;
            this.nativeScreens = nativeScreens;
        }

        @Override
        public Optional<MenuViewer> viewer(UUID viewerId) {
            return Optional.of(viewer);
        }

        @Override
        public boolean runForViewer(UUID viewerId, Runnable task) {
            task.run();
            return true;
        }

        @Override
        public MenuRenderer chestRenderer() {
            return this;
        }

        @Override
        public MenuTransport transport() {
            return (viewerId, frame) -> frames.add(frame);
        }

        @Override
        public MenuCaptures captures() {
            return mock(MenuCaptures.class);
        }

        @Override
        public boolean nativeScreensEnabled() {
            return nativeScreens;
        }

        @Override
        public void warn(String message) {
        }

        @Override
        public void show(MenuViewer target, MenuBuild build, boolean opening) {
            shows.add(build);
        }

        @Override
        public void close(MenuViewer target) {
            closed = true;
        }

        @Override
        public void notice(MenuViewer target, MenuNotice notice) {
        }

        private MenuServerMessage lastMessage() throws MenuProtocolException {
            return MenuCodec.decodeServer(frames.getLast());
        }

        private long revision() throws MenuProtocolException {
            for (int index = frames.size() - 1; index >= 0; index--) {
                MenuServerMessage message = MenuCodec.decodeServer(frames.get(index));
                if (message instanceof MenuServerMessage.Open open) {
                    return open.screen().revision();
                }
                if (message instanceof MenuServerMessage.Update update) {
                    return update.screen().revision();
                }
            }
            throw new IllegalStateException("no screen was sent");
        }
    }
}
