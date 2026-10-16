package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.optics.math.Face;
import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.ProjectionManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.menu.BukkitMenuViewer;
import art.arcane.wormholes.menu.MenuBuild;
import art.arcane.wormholes.menu.MenuCaptures;
import art.arcane.wormholes.menu.MenuHost;
import art.arcane.wormholes.menu.MenuNotice;
import art.arcane.wormholes.menu.MenuRegistry;
import art.arcane.wormholes.menu.MenuRenderer;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuService;
import art.arcane.wormholes.menu.MenuTransport;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.ToggleElement;
import art.arcane.wormholes.menu.host.BukkitPortalMenuHost;
import art.arcane.wormholes.menu.portal.GatewayMenuHost;
import art.arcane.wormholes.menu.portal.PortalMenuController;
import art.arcane.wormholes.menu.portal.PortalRoutes;
import art.arcane.wormholes.network.PortalSyncService;

class LocalPortalMenuInvalidationTest {
    private ProjectionManager previousProjections;
    private MenuService previousMenus;
    private LocalPortal portal;

    @BeforeEach
    void setUp() {
        previousProjections = Wormholes.projectionManager;
        previousMenus = Wormholes.menus;
        ProjectionManager projections = mock(ProjectionManager.class, RETURNS_DEEP_STUBS);
        when(projections.clientView().nativeMesh(any())).thenReturn(false);
        Wormholes.projectionManager = projections;
        World world = LocalPortalTestSupport.world("overworld");
        portal = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
    }

    @AfterEach
    void tearDown() {
        Wormholes.projectionManager = previousProjections;
        Wormholes.menus = previousMenus;
    }

    @Test
    void portalChangesInvalidateThePortalScope() {
        MenuService menus = mock(MenuService.class);
        Wormholes.menus = menus;

        portal.setProjectionMode(ProjectionMode.OFF);
        portal.setDirection(Face.N);

        verify(menus, times(2)).invalidate(new MenuScope.Portal(portal.getId()));
    }

    @Test
    void remoteSettingsApplicationDoesNotInvalidate() {
        MenuService menus = mock(MenuService.class);
        Wormholes.menus = menus;

        PortalSyncService.applyRemote(() -> portal.setProjectionMode(ProjectionMode.OFF));

        verify(menus, never()).invalidate(any(MenuScope.class));
    }

    @Test
    void wandingOpensPortalHomeAndDoesNothingWhileMenusAreUnloaded() {
        Player player = mock(Player.class);
        UUID viewerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(viewerId);
        Wormholes.menus = null;

        portal.onWanded(player);

        MenuService menus = mock(MenuService.class);
        Wormholes.menus = menus;
        portal.onWanded(player);
        verify(menus).open(viewerId, PortalRoutes.home(portal.getId()));
    }

    @Test
    void openHomeScreensRepaintWhenTheirPortalChanges() {
        Player player = mock(Player.class);
        UUID viewerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(viewerId);
        when(player.getName()).thenReturn("Viewer");
        when(player.isOp()).thenReturn(true);
        PortalManager manager = mock(PortalManager.class);
        when(manager.getLocalPortal(portal.getId())).thenReturn(portal);
        RecordingHost host = new RecordingHost(new BukkitMenuViewer(player));
        MenuService menus = new MenuService(host, new MenuRegistry(List.of(
            new PortalMenuController(new BukkitPortalMenuHost(manager), mock(GatewayMenuHost.class)))));
        Wormholes.menus = menus;
        portal.setProjectionMode(ProjectionMode.ON);

        menus.open(viewerId, PortalRoutes.home(portal.getId()));
        portal.setProjectionMode(ProjectionMode.OFF);

        assertEquals(2, host.shows.size());
        assertTrue(host.openings.getFirst());
        assertFalse(host.openings.get(1));
        assertTrue(projection(host.shows.getFirst()));
        assertFalse(projection(host.shows.get(1)));

        LocalPortal other = LocalPortalTestSupport.portal(LocalPortalTestSupport.world("overworld"), PortalType.PORTAL);
        other.setProjectionMode(ProjectionMode.OFF);

        assertEquals(2, host.shows.size());
    }

    @Test
    void rejectedRefreshesAreRetriedOnTheNextChange() {
        Player player = mock(Player.class);
        UUID viewerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(viewerId);
        when(player.isOp()).thenReturn(true);
        PortalManager manager = mock(PortalManager.class);
        when(manager.getLocalPortal(portal.getId())).thenReturn(portal);
        RecordingHost host = new RecordingHost(new BukkitMenuViewer(player));
        MenuService menus = new MenuService(host, new MenuRegistry(List.of(
            new PortalMenuController(new BukkitPortalMenuHost(manager), mock(GatewayMenuHost.class)))));
        Wormholes.menus = menus;
        menus.open(viewerId, PortalRoutes.home(portal.getId()));

        host.accepting = false;
        portal.setProjectionMode(ProjectionMode.OFF);
        host.accepting = true;
        portal.setDirection(Face.N);

        assertEquals(2, host.shows.size());
        assertFalse(projection(host.shows.get(1)));
        assertEquals(1, host.warnings.size());
    }

    private static boolean projection(MenuBuild build) {
        return ((ToggleElement) build.screen().element("toggle-projections").orElseThrow()).value();
    }

    private static final class RecordingHost implements MenuHost, MenuRenderer {
        private final MenuViewer viewer;
        private final List<MenuBuild> shows = new ArrayList<>();
        private final List<Boolean> openings = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private boolean accepting = true;

        private RecordingHost(MenuViewer viewer) {
            this.viewer = viewer;
        }

        @Override
        public Optional<MenuViewer> viewer(UUID viewerId) {
            return Optional.of(viewer);
        }

        @Override
        public boolean runForViewer(UUID viewerId, Runnable task) {
            if (!accepting) {
                return false;
            }
            task.run();
            return true;
        }

        @Override
        public void warn(String message) {
            warnings.add(message);
        }

        @Override
        public MenuRenderer chestRenderer() {
            return this;
        }

        @Override
        public MenuTransport transport() {
            return (viewerId, frame) -> false;
        }

        @Override
        public MenuCaptures captures() {
            return mock(MenuCaptures.class);
        }

        @Override
        public boolean nativeScreensEnabled() {
            return false;
        }

        @Override
        public void show(MenuViewer target, MenuBuild build, boolean opening) {
            shows.add(build);
            openings.add(opening);
        }

        @Override
        public void close(MenuViewer target) {
        }

        @Override
        public void notice(MenuViewer target, MenuNotice notice) {
        }
    }
}
