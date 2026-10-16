package art.arcane.wormholes.menu.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.menu.BukkitMenuViewer;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuService;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.nexus.NetworkEdit;
import art.arcane.wormholes.menu.nexus.NexusPortalView;
import art.arcane.wormholes.nexus.DestinationPolicy;
import art.arcane.wormholes.nexus.DialState;
import art.arcane.wormholes.nexus.FrameIo;
import art.arcane.wormholes.nexus.NetworkRegistry;
import art.arcane.wormholes.nexus.NexusPortalExtension;
import art.arcane.wormholes.nexus.NexusSubsystem;
import art.arcane.wormholes.nexus.PortalNetwork;
import art.arcane.wormholes.nexus.RedstoneIo;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalType;

class BukkitNetworkMenuHostTest {
    private final UUID portalId = UUID.randomUUID();
    private final UUID networkId = UUID.randomUUID();
    private final List<LogRecord> logged = new ArrayList<>();
    private MenuService previousMenus;
    private MenuService menus;
    private LocalPortal portal;
    private NexusPortalExtension state;
    private NetworkRegistry registry;
    private RedstoneIo redstone;
    private NexusSubsystem nexus;
    private MenuViewer viewer;
    private BukkitNetworkMenuHost host;

    @BeforeEach
    void setUp() {
        previousMenus = Wormholes.menus;
        menus = mock(MenuService.class);
        Wormholes.menus = menus;
        portal = mock(LocalPortal.class);
        state = mock(NexusPortalExtension.class);
        when(portal.getId()).thenReturn(portalId);
        when(portal.getName()).thenReturn("Alpha");
        when(portal.getType()).thenReturn(PortalType.PORTAL);
        when(portal.getRouter(true)).thenReturn("Alpha Router");
        when(portal.extension(NexusPortalExtension.class)).thenReturn(state);
        PortalManager portals = mock(PortalManager.class);
        when(portals.getLocalPortal(portalId)).thenReturn(portal);
        registry = mock(NetworkRegistry.class);
        redstone = mock(RedstoneIo.class);
        nexus = mock(NexusSubsystem.class);
        when(nexus.registry()).thenReturn(registry);
        when(nexus.redstoneIo()).thenReturn(redstone);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        viewer = new BukkitMenuViewer(player);
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        host = new BukkitNetworkMenuHost(logger, portals, () -> nexus);
    }

    @AfterEach
    void tearDown() {
        Wormholes.menus = previousMenus;
    }

    @Test
    void thePortalViewCarriesTheNexusStateOfThePortal() {
        DestinationPolicy policy = DestinationPolicy.empty();
        when(state.networkId()).thenReturn(networkId);
        when(state.address()).thenReturn("ALPHA");
        when(state.reciprocal()).thenReturn(true);
        when(state.policy()).thenReturn(policy);
        when(state.frameIo()).thenReturn(FrameIo.none());
        when(state.dial()).thenReturn(DialState.idle());

        NexusPortalView view = host.portal(portalId).orElseThrow();

        assertEquals("Alpha", view.name());
        assertEquals("Alpha Router", view.routerTitle());
        assertEquals(networkId, view.networkId());
        assertEquals("ALPHA", view.address());
        assertTrue(view.reciprocal());
        assertSame(policy, view.policy());
        assertTrue(host.portal(UUID.randomUUID()).isEmpty());
    }

    @Test
    void managementFollowsThePortalRoles() {
        when(portal.canManage(viewer.id(), false)).thenReturn(true);

        assertTrue(host.canManage(viewer, portalId));
        assertFalse(host.canManage(viewer, UUID.randomUUID()));
    }

    @Test
    void networkLookupsReadTheRegistry() {
        PortalNetwork network = PortalNetwork.create(networkId, "Lobby", viewer.id());
        when(registry.byId(networkId)).thenReturn(network);
        when(registry.byName("lobby")).thenReturn(network);
        when(registry.ownedBy(viewer.id())).thenReturn(List.of(network, network));

        assertSame(network, host.network(networkId).orElseThrow());
        assertSame(network, host.networkByName("lobby").orElseThrow());
        assertEquals(2, host.networksOwnedBy(viewer.id()));
        assertTrue(host.networkByName("missing").isEmpty());
    }

    @Test
    void aSavedNetworkRefreshesItsOpenWindows() throws IOException {
        PortalNetwork network = PortalNetwork.create(networkId, "Lobby", viewer.id());

        assertTrue(host.saveNetwork(network));

        verify(registry).save(network);
        verify(menus).invalidate(new MenuScope.Network(networkId));
    }

    @Test
    void aFailedSaveIsLoggedAsAWarningAndReported() throws IOException {
        PortalNetwork network = PortalNetwork.create(networkId, "Lobby", viewer.id());
        IOException failure = new IOException("disk full");
        doThrow(failure).when(registry).save(network);

        assertFalse(host.saveNetwork(network));

        assertEquals(1, logged.size());
        assertEquals(Level.WARNING, logged.getFirst().getLevel());
        assertSame(failure, logged.getFirst().getThrown());
        verify(menus, never()).invalidate(any());
    }

    @Test
    void joiningAndLeavingWriteTheNexusStateAndSaveThePortal() {
        host.edit(viewer, portalId, new NetworkEdit.Join(networkId, "ALPHA", "Alpha"));

        verify(state).setNetworkId(networkId);
        verify(state).setAddress("ALPHA");
        verify(state).setLabel("Alpha");

        host.edit(viewer, portalId, new NetworkEdit.Leave());

        verify(state).setNetworkId(null);
        verify(state).setAddress("");
        verify(state).setDial(DialState.idle());
        verify(portal, times(2)).save();
        verify(portal, times(2)).invalidateMenus();
        verify(redstone, never()).sync(any());
    }

    @Test
    void redstoneChangesResyncTheFrameWiring() {
        FrameIo frameIo = FrameIo.none().withAction(FrameIo.none().action().next());

        host.edit(viewer, portalId, new NetworkEdit.Redstone(frameIo));

        verify(state).setFrameIo(frameIo);
        verify(portal).save();
        verify(redstone).sync(portal);
    }

    @Test
    void withoutTheNexusSubsystemNothingIsSavedOrFound() {
        nexus = null;

        assertTrue(host.network(networkId).isEmpty());
        assertTrue(host.networkByName("Lobby").isEmpty());
        assertEquals(0, host.networksOwnedBy(viewer.id()));
        assertFalse(host.saveNetwork(PortalNetwork.create(networkId, "Lobby", viewer.id())));
    }
}
