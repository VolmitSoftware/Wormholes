package art.arcane.wormholes.menu.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.menu.BukkitMenuViewer;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.destination.DestinationMenuView;
import art.arcane.wormholes.menu.destination.PairOutcome;
import art.arcane.wormholes.network.RemotePortalRegistry;
import art.arcane.wormholes.nexus.ReciprocalLinks;
import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.RemotePortal;
import art.arcane.wormholes.portal.UniversalTunnel;
import art.arcane.wormholes.util.RemoteWorld;

class BukkitDestinationMenuHostTest {
    private final List<ILocalPortal> registered = new ArrayList<>();
    private RemotePortalRegistry previousRemotes;
    private PortalManager manager;
    private ReciprocalLinks links;
    private World overworld;
    private World nether;
    private LocalPortal source;
    private MenuViewer viewer;
    private BukkitDestinationMenuHost host;

    @BeforeEach
    void setUp() {
        previousRemotes = Wormholes.remotePortalRegistry;
        Wormholes.remotePortalRegistry = null;
        manager = mock(PortalManager.class);
        when(manager.getLocalPortals()).thenReturn(registered);
        links = mock(ReciprocalLinks.class);
        overworld = world("world");
        nether = world("world_nether");
        source = portal("Source", PortalType.PORTAL, overworld, 0, 64, 0);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        viewer = new BukkitMenuViewer(player);
        host = new BukkitDestinationMenuHost(manager, () -> links);
    }

    @AfterEach
    void tearDown() {
        Wormholes.remotePortalRegistry = previousRemotes;
    }

    @Test
    void candidatesAreTheLinkableLocalPortalsWithTheirLinkStateAndDistance() {
        LocalPortal near = portal("Near", PortalType.PORTAL, overworld, 3, 64, 4);
        LocalPortal far = portal("Far", PortalType.PORTAL, nether, 10, 70, 10);
        portal("Random", PortalType.RTP, overworld, 1, 64, 1);
        portal("Gate", PortalType.GATEWAY, overworld, 2, 64, 2);
        LocalPortal managed = portal("Managed", PortalType.PORTAL, overworld, 5, 64, 5);
        when(managed.getDimensionalPortalKind()).thenReturn(DimensionalPortalKind.END_SOURCE);
        LocalPortal unplaced = portal("Unplaced", PortalType.PORTAL, overworld, 6, 64, 6);
        when(unplaced.getStructure()).thenReturn(null);
        link(source, near);

        List<DestinationMenuView> candidates = host.candidates(source.getId());

        assertEquals(List.of("Near", "Far"), candidates.stream().map(DestinationMenuView::name).toList());
        DestinationMenuView nearView = candidates.getFirst();
        assertEquals(near.getId(), nearView.id());
        assertEquals("world", nearView.world());
        assertEquals(3, nearView.x());
        assertEquals(64, nearView.y());
        assertEquals(4, nearView.z());
        assertEquals(Face.N, nearView.direction());
        assertEquals(25.0D, nearView.distanceSquared());
        assertTrue(nearView.linked());
        assertFalse(nearView.remote());
        assertTrue(nearView.open());
        assertEquals(Double.MAX_VALUE, candidates.getLast().distanceSquared());
        assertFalse(candidates.getLast().linked());
        assertEquals(far.getId(), candidates.getLast().id());
    }

    @Test
    void gatewaysAlsoListRemoteGatewaysFromTheRegistry() {
        LocalPortal gateway = portal("Gate", PortalType.GATEWAY, overworld, 0, 64, 0);
        when(gateway.isGateway()).thenReturn(true);
        RemotePortalRegistry remotes = mock(RemotePortalRegistry.class);
        RemotePortal remoteGateway = remote("Lobby Gate", "lobby", PortalType.GATEWAY, true);
        RemotePortal remotePortal = remote("Lobby Portal", "lobby", PortalType.PORTAL, true);
        when(remotes.all()).thenReturn(List.of(remoteGateway, remotePortal));
        Wormholes.remotePortalRegistry = remotes;
        UniversalTunnel tunnel = mock(UniversalTunnel.class);
        when(tunnel.getServerName()).thenReturn("lobby");
        UUID remoteId = remoteGateway.getId();
        when(tunnel.getDestinationId()).thenReturn(remoteId);
        when(gateway.getTunnel()).thenReturn(tunnel);

        List<DestinationMenuView> candidates = host.candidates(gateway.getId());

        assertEquals(1, candidates.size());
        DestinationMenuView view = candidates.getFirst();
        assertTrue(view.remote());
        assertEquals("lobby", view.server());
        assertEquals("lobby_world", view.world());
        assertEquals(12, view.x());
        assertEquals(Double.MAX_VALUE, view.distanceSquared());
        assertTrue(view.linked());
        assertTrue(view.open());
    }

    @Test
    void linkingChoosesTheLocalPortalOrTheRegisteredRemotePortal() {
        LocalPortal target = portal("Target", PortalType.PORTAL, overworld, 3, 64, 4);
        RemotePortalRegistry remotes = mock(RemotePortalRegistry.class);
        RemotePortal remoteGateway = remote("Lobby Gate", "lobby", PortalType.GATEWAY, true);
        UUID remoteId = remoteGateway.getId();
        when(remotes.get("lobby", remoteId)).thenReturn(remoteGateway);
        Wormholes.remotePortalRegistry = remotes;

        host.link(viewer, source.getId(), view(target.getId(), null));
        host.link(viewer, source.getId(), view(remoteGateway.getId(), "lobby"));
        host.unlink(viewer, source.getId());

        verify(source).setDestination(target);
        verify(source).setDestination(remoteGateway);
        verify(source).unlink();
    }

    @Test
    void linkingAVanishedTargetChangesNothing() {
        host.link(viewer, source.getId(), view(UUID.randomUUID(), null));
        host.link(viewer, source.getId(), view(UUID.randomUUID(), "lobby"));
        host.link(viewer, UUID.randomUUID(), view(source.getId(), null));

        verify(source, never()).setDestination(any());
    }

    @Test
    void theLocalDestinationIsOnlyALinkedLocalPortal() {
        LocalPortal target = portal("Target", PortalType.PORTAL, overworld, 3, 64, 4);

        assertTrue(host.localDestination(source.getId()).isEmpty());

        link(source, target);
        DestinationMenuView destination = host.localDestination(source.getId()).orElseThrow();

        assertEquals(target.getId(), destination.id());
        assertEquals("Target", destination.name());
        assertTrue(destination.linked());

        ITunnel remoteTunnel = mock(UniversalTunnel.class);
        RemotePortal remoteGateway = remote("Lobby Gate", "lobby", PortalType.GATEWAY, true);
        when(remoteTunnel.getDestination()).thenReturn(remoteGateway);
        when(source.getTunnel()).thenReturn(remoteTunnel);
        assertTrue(host.localDestination(source.getId()).isEmpty());
    }

    @Test
    void pairingReportsTheReciprocalResultOrUnavailable() {
        LocalPortal target = portal("Target", PortalType.PORTAL, overworld, 3, 64, 4);
        when(links.pair(source, target)).thenReturn(ReciprocalLinks.PairResult.PAIRED);

        assertEquals(PairOutcome.PAIRED, host.pair(viewer, source.getId(), target.getId()));

        when(links.pair(source, target)).thenReturn(ReciprocalLinks.PairResult.REFUSED);
        assertEquals(PairOutcome.DENIED, host.pair(viewer, source.getId(), target.getId()));
        assertEquals(PairOutcome.UNAVAILABLE, host.pair(viewer, source.getId(), UUID.randomUUID()));
        assertEquals(PairOutcome.UNAVAILABLE, new BukkitDestinationMenuHost(manager, () -> null).pair(viewer, source.getId(), target.getId()));
    }

    @Test
    void theSelectionListsEveryPlacedLocalPortalUnlinked() {
        portal("Gate", PortalType.GATEWAY, overworld, 2, 64, 2);
        LocalPortal unplaced = portal("Unplaced", PortalType.PORTAL, overworld, 6, 64, 6);
        when(unplaced.getStructure()).thenReturn(null);

        List<DestinationMenuView> portals = host.portals();

        assertEquals(List.of("Source", "Gate"), portals.stream().map(DestinationMenuView::name).toList());
        assertTrue(portals.stream().noneMatch(DestinationMenuView::linked));
        assertTrue(portals.stream().allMatch(view -> view.distanceSquared() == Double.MAX_VALUE));
    }

    private LocalPortal portal(String name, PortalType type, World world, int x, int y, int z) {
        LocalPortal portal = mock(LocalPortal.class);
        UUID id = UUID.randomUUID();
        when(portal.getId()).thenReturn(id);
        when(portal.getName()).thenReturn(name);
        when(portal.getType()).thenReturn(type);
        when(portal.isGateway()).thenReturn(type == PortalType.GATEWAY);
        when(portal.getDimensionalPortalKind()).thenReturn(DimensionalPortalKind.NONE);
        when(portal.getDirection()).thenReturn(Face.N);
        PortalStructure structure = mock(PortalStructure.class);
        when(structure.getWorld()).thenReturn(world);
        when(structure.getCenter()).thenReturn(new Location(world, x, y, z));
        when(portal.getStructure()).thenReturn(structure);
        when(manager.getLocalPortal(id)).thenReturn(portal);
        registered.add(portal);
        return portal;
    }

    private static void link(LocalPortal portal, LocalPortal target) {
        ITunnel tunnel = mock(ITunnel.class);
        when(tunnel.getDestination()).thenReturn(target);
        when(portal.getTunnel()).thenReturn(tunnel);
    }

    private static RemotePortal remote(String name, String server, PortalType type, boolean open) {
        RemotePortal portal = mock(RemotePortal.class);
        UUID id = UUID.randomUUID();
        RemoteWorld world = mock(RemoteWorld.class);
        when(world.getName()).thenReturn(server);
        when(world.getWorld()).thenReturn(server + "_world");
        when(portal.getId()).thenReturn(id);
        when(portal.getName()).thenReturn(name);
        when(portal.getServer()).thenReturn(world);
        when(portal.getType()).thenReturn(type);
        when(portal.isOpen()).thenReturn(open);
        when(portal.getOrigin()).thenReturn(new Vec3d(12.5D, 70.0D, -3.5D));
        when(portal.getDirection()).thenReturn(Face.E);
        return portal;
    }

    private static DestinationMenuView view(UUID id, String server) {
        return new DestinationMenuView(id, "Target", "world", 0, 64, 0, Face.N, 0.0D, false, server, true);
    }

    private static World world(String name) {
        World world = mock(World.class);
        when(world.getName()).thenReturn(name);
        return world;
    }
}
