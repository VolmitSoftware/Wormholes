package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.wormholes.EffectManager;
import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.ProjectionManager;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.access.AccessPortalExtension;
import art.arcane.wormholes.access.PortalRole;
import art.arcane.wormholes.hook.WormholesHooks;
import art.arcane.wormholes.hook.WormholesRegistrar;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.menu.BukkitMenuViewer;
import art.arcane.wormholes.menu.MenuAction;
import art.arcane.wormholes.menu.MenuBuild;
import art.arcane.wormholes.menu.MenuCaptures;
import art.arcane.wormholes.menu.MenuChestAction;
import art.arcane.wormholes.menu.MenuHost;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuNotice;
import art.arcane.wormholes.menu.MenuRegistry;
import art.arcane.wormholes.menu.MenuRenderer;
import art.arcane.wormholes.menu.MenuService;
import art.arcane.wormholes.menu.MenuTransport;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.host.BukkitPortalMenuHost;
import art.arcane.wormholes.menu.portal.FrameOperation;
import art.arcane.wormholes.menu.portal.GatewayMenuHost;
import art.arcane.wormholes.menu.portal.NetworkNumberField;
import art.arcane.wormholes.menu.portal.PortalEdit;
import art.arcane.wormholes.menu.portal.PortalEditOutcome;
import art.arcane.wormholes.menu.portal.PortalMenuController;
import art.arcane.wormholes.menu.portal.PortalMenuView;
import art.arcane.wormholes.menu.portal.PortalRoutes;
import art.arcane.wormholes.menu.protocol.MenuClientMessage;
import art.arcane.wormholes.menu.protocol.MenuCodec;
import art.arcane.wormholes.menu.protocol.MenuProtocolException;
import art.arcane.wormholes.menu.protocol.MenuServerInfo;
import art.arcane.wormholes.menu.protocol.MenuServerMessage;
import art.arcane.wormholes.service.WormholesAudience;
import art.arcane.wormholes.util.Cuboid;

class LocalPortalMenuHostTest {
    private final UUID ownerId = UUID.randomUUID();
    private ProjectionManager previousProjections;
    private MenuService previousMenus;
    private EffectManager previousEffects;
    private boolean previousTwoWayEnd;
    private LocalPortal portal;
    private PortalManager manager;
    private BukkitPortalMenuHost host;

    @BeforeEach
    void setUp() {
        previousProjections = Wormholes.projectionManager;
        previousMenus = Wormholes.menus;
        previousEffects = Wormholes.effectManager;
        previousTwoWayEnd = Settings.TWO_WAY_END_PORTALS;
        Settings.TWO_WAY_END_PORTALS = true;
        ProjectionManager projections = mock(ProjectionManager.class, RETURNS_DEEP_STUBS);
        when(projections.clientView().nativeMesh(any())).thenReturn(false);
        Wormholes.projectionManager = projections;
        World world = LocalPortalTestSupport.world("overworld");
        portal = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
        portal.setOwner(ownerId);
        manager = mock(PortalManager.class);
        when(manager.getLocalPortal(portal.getId())).thenReturn(portal);
        when(manager.getAccessableCount(PortalType.PORTAL)).thenReturn(3);
        host = new BukkitPortalMenuHost(manager);
    }

    @AfterEach
    void tearDown() {
        Wormholes.projectionManager = previousProjections;
        Wormholes.menus = previousMenus;
        Wormholes.effectManager = previousEffects;
        Settings.TWO_WAY_END_PORTALS = previousTwoWayEnd;
    }

    @Test
    void onlyTheOwnerAndAdministratorsManageThePortal() {
        BukkitMenuViewer owner = viewer(ownerId, false);
        BukkitMenuViewer stranger = viewer(UUID.randomUUID(), false);
        BukkitMenuViewer operator = viewer(UUID.randomUUID(), true);
        BukkitMenuViewer administrator = viewer(UUID.randomUUID(), false);
        when(administrator.player().hasPermission(PortalTypeAccess.ADMIN)).thenReturn(true);

        assertTrue(host.canManage(owner, portal.getId()));
        assertFalse(host.canManage(stranger, portal.getId()));
        assertTrue(host.canManage(operator, portal.getId()));
        assertTrue(host.canManage(administrator, portal.getId()));
        assertTrue(host.ownerOrAdmin(owner, portal.getId()));
        assertFalse(host.ownerOrAdmin(stranger, portal.getId()));
        assertFalse(host.canManage(owner, UUID.randomUUID()));
    }

    @Test
    void destroyedPortalsAreNeitherShownNorManaged() {
        BukkitMenuViewer owner = viewer(ownerId, false);
        LocalPortal destroyed = mock(LocalPortal.class);
        UUID destroyedId = UUID.randomUUID();
        when(destroyed.isDestroyed()).thenReturn(true);
        when(manager.getLocalPortal(destroyedId)).thenReturn(destroyed);

        assertTrue(host.portal(destroyedId).isEmpty());
        assertFalse(host.canManage(owner, destroyedId));
    }

    @Test
    void typeChangesNeedTheTypePermissionUnlessAdministrator() {
        BukkitMenuViewer member = viewer(ownerId, false);
        BukkitMenuViewer operator = viewer(UUID.randomUUID(), true);

        assertFalse(host.typeAllowed(member, PortalType.GATEWAY));
        when(member.player().hasPermission(PortalType.GATEWAY.permission())).thenReturn(true);
        assertTrue(host.typeAllowed(member, PortalType.GATEWAY));
        assertTrue(host.typeAllowed(operator, PortalType.RTP));
    }

    @Test
    void surfaceSkinsNeedTheAdminNodeEvenForOperators() {
        BukkitMenuViewer operator = viewer(UUID.randomUUID(), true);

        assertFalse(host.canEditSurfaceSkin(operator, portal.getId()));
        when(operator.player().hasPermission(PortalTypeAccess.ADMIN)).thenReturn(true);
        assertTrue(host.canEditSurfaceSkin(operator, portal.getId()));
    }

    @Test
    void viewSnapshotsTheHomeScreenState() {
        portal.setName("Alpha");
        portal.setProjectionMode(ProjectionMode.OFF);

        PortalMenuView view = host.portal(portal.getId()).orElseThrow();

        assertEquals("Alpha", view.name());
        assertEquals(PortalType.PORTAL, view.type());
        assertEquals(ProjectionMode.OFF, view.projection());
        assertEquals(3, view.accessibleCount());
        assertEquals(portal.getRouter(true), view.routerTitle());
        assertEquals(PortalMenuView.Cost.FREE, view.cost());
        assertEquals(null, view.destination());
    }

    @Test
    void editsRefuseWhatTodaysMenusRefuse() {
        BukkitMenuViewer owner = viewer(ownerId, false);
        PortalEditOutcome rotation = host.edit(owner, portal.getId(), new PortalEdit.MirrorRotation(true));
        assertEquals(WormholesMessages.PORTAL_MIRROR_SELECT_FIRST, assertInstanceOf(PortalEditOutcome.Refused.class, rotation).reason());

        PortalTravelMode before = portal.getTravelMode();
        portal.setMirrorMode(true);
        PortalEditOutcome travel = host.edit(owner, portal.getId(), new PortalEdit.TravelMode(PortalTravelMode.OUTBOUND));
        assertEquals(WormholesMessages.PORTAL_TRAVEL_MIRROR_LOCKED, assertInstanceOf(PortalEditOutcome.Refused.class, travel).reason());
        assertEquals(before, portal.getTravelMode());

        portal.setMirrorMode(false);
        Settings.TWO_WAY_END_PORTALS = false;
        portal.setDimensionalPortalKind(DimensionalPortalKind.END_ARRIVAL);
        PortalEditOutcome projection = host.edit(owner, portal.getId(), new PortalEdit.Projection(ProjectionMode.ON));
        assertEquals(WormholesMessages.PORTAL_PROJECTION_RECEIVER_INACTIVE,
            assertInstanceOf(PortalEditOutcome.Refused.class, projection).reason());
        PortalTravelMode managed = portal.getTravelMode();
        PortalEditOutcome managedTravel = host.edit(owner, portal.getId(), new PortalEdit.TravelMode(PortalTravelMode.OUTBOUND));
        assertEquals(WormholesMessages.PORTAL_TRAVEL_MANAGED, assertInstanceOf(PortalEditOutcome.Refused.class, managedTravel).reason());
        assertEquals(managed, portal.getTravelMode());
    }

    @Test
    void twoWayEndArrivalsKeepTheirProjectionForcedOn() {
        BukkitMenuViewer owner = viewer(ownerId, false);
        portal.setDimensionalPortalKind(DimensionalPortalKind.END_ARRIVAL);

        assertFalse(host.portal(portal.getId()).orElseThrow().receiverOnly());
        assertEquals(PortalEditOutcome.applied(), host.edit(owner, portal.getId(), new PortalEdit.Projection(ProjectionMode.OFF)));
        assertEquals(ProjectionMode.ON, portal.getProjectionMode());

        Settings.TWO_WAY_END_PORTALS = false;
        assertTrue(host.portal(portal.getId()).orElseThrow().receiverOnly());
        PortalEditOutcome oneWay = host.edit(owner, portal.getId(), new PortalEdit.Projection(ProjectionMode.OFF));
        assertEquals(WormholesMessages.PORTAL_PROJECTION_RECEIVER_INACTIVE, assertInstanceOf(PortalEditOutcome.Refused.class, oneWay).reason());
    }

    @Test
    void renamesAndFrameChangesGoThroughThePortalSetters() {
        BukkitMenuViewer owner = viewer(ownerId, false);

        Frame before = portal.getFrame();

        host.edit(owner, portal.getId(), new PortalEdit.Name("Gamma"));
        host.edit(owner, portal.getId(), new PortalEdit.FrameChange(FrameOperation.FLIP_FACE));

        assertEquals("Gamma", portal.getName());
        assertEquals(before.flipNormal(), portal.getFrame());
    }

    @Test
    void coOwnersManageButOnlyTheOwnerAndAdministratorsPassOwnerOrAdmin() {
        WormholesHooks.install(new WormholesRegistrar().portalExtension(AccessPortalExtension.class, AccessPortalExtension::new));
        try {
            LocalPortal shared = LocalPortalTestSupport.portal(LocalPortalTestSupport.world("overworld"), PortalType.PORTAL);
            shared.setOwner(ownerId);
            when(manager.getLocalPortal(shared.getId())).thenReturn(shared);
            BukkitMenuViewer coOwner = viewer(UUID.randomUUID(), false);
            BukkitMenuViewer user = viewer(UUID.randomUUID(), false);
            AccessPortalExtension access = shared.extension(AccessPortalExtension.class);
            access.setRole(coOwner.id(), PortalRole.CO_OWNER);
            access.setRole(user.id(), PortalRole.USER);

            assertTrue(host.canManage(coOwner, shared.getId()));
            assertFalse(host.ownerOrAdmin(coOwner, shared.getId()));
            assertFalse(host.canManage(user, shared.getId()));
            assertFalse(host.ownerOrAdmin(user, shared.getId()));
            assertTrue(host.ownerOrAdmin(viewer(ownerId, false), shared.getId()));
            assertTrue(host.ownerOrAdmin(viewer(UUID.randomUUID(), true), shared.getId()));
        } finally {
            WormholesHooks.clear();
        }
    }

    @Test
    void typeAndMirrorEditsApplyUnlessThePortalIsManaged() {
        BukkitMenuViewer owner = viewer(ownerId, false);

        assertInstanceOf(PortalEditOutcome.Applied.class, host.edit(owner, portal.getId(), new PortalEdit.Type(PortalType.WORMHOLE)));
        assertInstanceOf(PortalEditOutcome.Applied.class, host.edit(owner, portal.getId(), new PortalEdit.Mirror(true)));
        assertEquals(PortalType.WORMHOLE, portal.getType());
        assertTrue(portal.isMirrorMode());

        LocalPortal managed = LocalPortalTestSupport.portal(LocalPortalTestSupport.world("overworld"), PortalType.PORTAL);
        managed.setOwner(ownerId);
        managed.setDimensionalPortalKind(DimensionalPortalKind.END_ARRIVAL);
        when(manager.getLocalPortal(managed.getId())).thenReturn(managed);

        host.edit(owner, managed.getId(), new PortalEdit.Type(PortalType.GATEWAY));
        host.edit(owner, managed.getId(), new PortalEdit.Mirror(true));

        assertEquals(PortalType.PORTAL, managed.getType());
        assertFalse(managed.isMirrorMode());
    }

    @Test
    void networkNumberEditsClampThroughThePortalSetters() {
        BukkitMenuViewer owner = viewer(ownerId, false);

        host.edit(owner, portal.getId(), new PortalEdit.NetworkNumber(NetworkNumberField.DEPTH, 0));
        host.edit(owner, portal.getId(), new PortalEdit.NetworkNumber(NetworkNumberField.HEARTBEAT_TICKS, 10_000));
        host.edit(owner, portal.getId(), new PortalEdit.NetworkNumber(NetworkNumberField.ENTITY_INTERVAL_TICKS, 1));
        host.edit(owner, portal.getId(), new PortalEdit.NetworkNumber(NetworkNumberField.UNSUBSCRIBE_GRACE_SECONDS, 1));

        assertEquals(1, portal.getNetworkViewDepth());
        assertEquals(600, portal.getNetworkViewHeartbeatTicks());
        assertEquals(2, portal.getNetworkViewEntityIntervalTicks());
        assertEquals(5, portal.getNetworkViewUnsubscribeGraceSeconds());
    }

    @Test
    void cosmeticEditsGoThroughThePortalSettersAndShowInTheView() {
        BukkitMenuViewer owner = viewer(ownerId, false);

        host.edit(owner, portal.getId(), new PortalEdit.Blackout(true));
        host.edit(owner, portal.getId(), new PortalEdit.BlackoutColorChoice(BlackoutColor.RED));
        host.edit(owner, portal.getId(), new PortalEdit.AmbientStyle(AmbientParticleStyle.CORNERS));
        host.edit(owner, portal.getId(), new PortalEdit.AmbientColor(0x9D9D97));
        host.edit(owner, portal.getId(), new PortalEdit.RenderMode(ProjectionRenderMode.VENTICULAR));
        host.edit(owner, portal.getId(), new PortalEdit.ActivationRange(64));
        host.edit(owner, portal.getId(), new PortalEdit.PublicLookLabel(true));

        PortalMenuView view = host.portal(portal.getId()).orElseThrow();
        assertTrue(view.blackout());
        assertEquals(BlackoutColor.RED, view.blackoutColor());
        assertEquals(AmbientParticleStyle.CORNERS, view.ambientStyle());
        assertEquals(0x9D9D97, view.ambientColor());
        assertEquals(ProjectionRenderMode.VENTICULAR, view.renderMode());
        assertEquals(64, view.activationRange());
        assertTrue(view.publicLookLabel());
    }

    @Test
    void apertureShapesWithoutAnOpenCellAreRefusedAndKeepTheCurrentShape() {
        BukkitMenuViewer owner = viewer(ownerId, false);
        World world = LocalPortalTestSupport.world("overworld");
        PortalStructure structure = new PortalStructure();
        structure.setWorld(world);
        structure.setArea(new Cuboid(new Location(world, 0.0D, 64.0D, 0.0D), new Location(world, 6.0D, 70.0D, 0.0D)));
        LocalPortal wide = new LocalPortal(UUID.randomUUID(), PortalType.WORMHOLE, structure);
        wide.setAmbientAttended(false);
        wide.setOwner(ownerId);
        when(manager.getLocalPortal(wide.getId())).thenReturn(wide);
        ShapeDescriptor star = ShapeDescriptor.parse("star");
        ShapeDescriptor tiny = ShapeDescriptor.parse("circle(radius=0.05)");

        assertInstanceOf(PortalEditOutcome.Applied.class, host.edit(owner, wide.getId(), new PortalEdit.ApertureShape(star)));
        PortalEditOutcome.Refused refused = assertInstanceOf(PortalEditOutcome.Refused.class,
            host.edit(owner, wide.getId(), new PortalEdit.ApertureShape(tiny)));

        assertEquals(WormholesMessages.PORTAL_APERTURE_SHAPE_TOO_SMALL, refused.reason());
        assertEquals(star, wide.getApertureShape());
        assertEquals(WormholesMessages.PORTAL_EDIT_DENIED,
            assertInstanceOf(PortalEditOutcome.Refused.class, host.edit(owner, UUID.randomUUID(), new PortalEdit.ApertureShape(star))).reason());
    }

    @Test
    void templatesResolveOnlyForVanillaItemCosts() {
        ItemStack template = mock(ItemStack.class);
        VanillaTravelCost vanilla = mock(VanillaTravelCost.class);
        when(vanilla.getTemplate()).thenReturn(template);
        LocalPortal costed = mock(LocalPortal.class);
        UUID costedId = UUID.randomUUID();
        when(costed.getTravelCost()).thenReturn(vanilla);
        when(manager.getLocalPortal(costedId)).thenReturn(costed);

        assertEquals(Optional.of(template), host.template(costedId.toString()));
        assertTrue(host.template(portal.getId().toString()).isEmpty());
        assertTrue(host.template(UUID.randomUUID().toString()).isEmpty());
        assertTrue(host.template("not-a-portal").isEmpty());
    }

    @Test
    void finishingTheAimPromptEndsAChestSessionWithoutReopening() {
        BukkitMenuViewer owner = aimingViewer();
        SessionHost sessions = new SessionHost(owner, false);
        MenuService menus = menus(sessions);
        menus.open(ownerId, PortalRoutes.home(portal.getId()));
        menus.chestAction(ownerId, new MenuChestAction("portal-orientation", MenuInteraction.PRIMARY, null, false, null));

        try (MockedStatic<WormholesAudience> ignored = mockStatic(WormholesAudience.class)) {
            menus.chestAction(ownerId, new MenuChestAction("set-direction", MenuInteraction.PRIMARY, null, false, null));
            assertTrue(sessions.closed);
            int shown = sessions.shows.size();

            portal.on(leftClick(owner.player()));

            assertEquals(shown, sessions.shows.size());
        }
        assertFalse(menus.chestPrecheck(ownerId, "flip-face", MenuInteraction.PRIMARY));
    }

    @Test
    void finishingTheAimPromptReopensTheOrientationScreenForNativeViewers() throws MenuProtocolException {
        BukkitMenuViewer owner = aimingViewer();
        SessionHost sessions = new SessionHost(owner, true);
        MenuService menus = menus(sessions);
        menus.receive(ownerId, MenuCodec.encodeClient(new MenuClientMessage.Hello(1)));
        menus.open(ownerId, PortalRoutes.home(portal.getId()));
        menus.receive(ownerId, MenuCodec.encodeClient(new MenuClientMessage.Action(
            new MenuAction(sessions.revision(), "portal-orientation", MenuInteraction.PRIMARY, null))));
        long suspended = sessions.revision();

        try (MockedStatic<WormholesAudience> ignored = mockStatic(WormholesAudience.class)) {
            menus.receive(ownerId, MenuCodec.encodeClient(new MenuClientMessage.Action(
                new MenuAction(suspended, "set-direction", MenuInteraction.PRIMARY, null))));
            assertInstanceOf(MenuServerMessage.Close.class, sessions.lastMessage());

            portal.on(leftClick(owner.player()));
        }

        MenuServerMessage.Open reopened = assertInstanceOf(MenuServerMessage.Open.class, sessions.lastMessage());
        assertEquals(PortalRoutes.ORIENTATION, reopened.screen().screenId());
        assertTrue(reopened.screen().revision() > suspended);
    }

    private BukkitMenuViewer aimingViewer() {
        BukkitMenuViewer owner = viewer(ownerId, false);
        when(owner.player().getLocation()).thenReturn(new Location(portal.getStructure().getWorld(), 0.0D, 64.0D, 0.0D, 90.0F, 0.0F));
        Wormholes.effectManager = mock(EffectManager.class);
        return owner;
    }

    private MenuService menus(SessionHost sessions) {
        MenuService menus = new MenuService(sessions, new MenuRegistry(List.of(new PortalMenuController(host, mock(GatewayMenuHost.class)))));
        Wormholes.menus = menus;
        return menus;
    }

    private static PlayerInteractEvent leftClick(Player player) {
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getAction()).thenReturn(Action.LEFT_CLICK_AIR);
        return event;
    }

    private static BukkitMenuViewer viewer(UUID id, boolean operator) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn("viewer");
        when(player.isOp()).thenReturn(operator);
        when(player.hasPermission(anyString())).thenReturn(false);
        return new BukkitMenuViewer(player);
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
        public MenuServerInfo serverInfo() {
            return new MenuServerInfo("test-version", "Test 1.0", nativeScreensEnabled());
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
