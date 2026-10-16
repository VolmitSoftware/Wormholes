package art.arcane.wormholes.modded.menu.host;

import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuService;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.destination.DestinationMenuView;
import art.arcane.wormholes.menu.destination.PairOutcome;
import art.arcane.wormholes.modded.MinecraftNetworkService;
import art.arcane.wormholes.modded.MinecraftNexus;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftPortalRegistry;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.menu.MinecraftMenuViewer;
import art.arcane.wormholes.network.RemotePortalRegistry;
import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.RemotePortal;
import art.arcane.wormholes.util.RemoteWorld;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftDestinationMenuHostTest extends MinecraftTestBase {
    private final List<MinecraftPortal> registered = new ArrayList<>();
    private final List<RemotePortal> remotes = new ArrayList<>();
    private WormholesModRuntime runtime;
    private MinecraftPortalRegistry portals;
    private MinecraftNexus nexus;
    private MenuService menus;
    private ServerPlayer player;
    private MenuViewer viewer;
    private MinecraftPortal source;
    private MinecraftDestinationMenuHost host;

    @Before
    public void setUp() {
        runtime = mock(WormholesModRuntime.class);
        portals = mock(MinecraftPortalRegistry.class);
        nexus = mock(MinecraftNexus.class);
        menus = mock(MenuService.class);
        MinecraftNetworkService network = mock(MinecraftNetworkService.class);
        RemotePortalRegistry remoteRegistry = mock(RemotePortalRegistry.class);
        player = mock(ServerPlayer.class);
        when(runtime.portals()).thenReturn(portals);
        when(runtime.nexus()).thenReturn(nexus);
        when(runtime.menus()).thenReturn(menus);
        when(runtime.network()).thenReturn(network);
        when(network.remotePortals()).thenReturn(remoteRegistry);
        when(remoteRegistry.all()).thenReturn(remotes);
        when(portals.snapshot()).thenReturn(registered);
        viewer = new MinecraftMenuViewer(runtime, player);
        source = portal("Source", PortalType.PORTAL, "minecraft:overworld", 0.5D, 64.0D, 0.5D);
        host = new MinecraftDestinationMenuHost(runtime);
    }

    @Test
    public void candidatesAreTheLinkableLoadedPortalsWithTheirLinkStateAndDistance() {
        MinecraftPortal near = portal("Near", PortalType.PORTAL, "minecraft:overworld", 3.5D, 64.0D, 4.5D);
        portal("Far", PortalType.PORTAL, "minecraft:the_nether", 10.5D, 70.0D, 10.5D);
        portal("Random", PortalType.RTP, "minecraft:overworld", 1.5D, 64.0D, 1.5D);
        portal("Gate", PortalType.GATEWAY, "minecraft:overworld", 2.5D, 64.0D, 2.5D);
        MinecraftPortal managed = portal("Managed", PortalType.PORTAL, "minecraft:overworld", 5.5D, 64.0D, 5.5D);
        when(managed.getDimensionalKind()).thenReturn(DimensionalPortalKind.END_SOURCE);
        MinecraftPortal unloaded = portal("Unloaded", PortalType.PORTAL, "minecraft:overworld", 6.5D, 64.0D, 6.5D);
        when(portals.resolveLevel(unloaded)).thenReturn(null);
        UUID nearId = near.getId();
        when(source.getDestinationId()).thenReturn(nearId);

        List<DestinationMenuView> candidates = host.candidates(source.getId());

        assertEquals(List.of("Near", "Far"), candidates.stream().map(DestinationMenuView::name).toList());
        DestinationMenuView view = candidates.getFirst();
        assertEquals(nearId, view.id());
        assertEquals("minecraft:overworld", view.world());
        assertEquals(3, view.x());
        assertEquals(64, view.y());
        assertEquals(4, view.z());
        assertEquals(Face.N, view.direction());
        assertEquals(25.0D, view.distanceSquared(), 0.0D);
        assertTrue(view.linked());
        assertTrue(view.open());
        assertFalse(view.remote());
        assertEquals(Double.MAX_VALUE, candidates.getLast().distanceSquared(), 0.0D);
        assertFalse(candidates.getLast().linked());
    }

    @Test
    public void gatewaysAlsoListRemoteGatewaysWithTheirLinkState() {
        MinecraftPortal gateway = portal("Gate", PortalType.GATEWAY, "minecraft:overworld", 0.5D, 64.0D, 0.5D);
        RemotePortal remoteGateway = remote("Lobby Gate", "lobby", PortalType.GATEWAY, false);
        remote("Lobby Portal", "lobby", PortalType.PORTAL, true);
        UUID remoteId = remoteGateway.getId();
        when(gateway.getTunnelType()).thenReturn("UNIVERSAL");
        when(gateway.getDestinationServer()).thenReturn("lobby");
        when(gateway.getDestinationId()).thenReturn(remoteId);

        List<DestinationMenuView> candidates = host.candidates(gateway.getId());

        assertEquals(1, candidates.size());
        DestinationMenuView view = candidates.getFirst();
        assertTrue(view.remote());
        assertEquals("lobby", view.server());
        assertEquals("lobby_world", view.world());
        assertEquals(12, view.x());
        assertTrue(view.linked());
        assertFalse(view.open());
        assertEquals(Double.MAX_VALUE, view.distanceSquared(), 0.0D);
    }

    @Test
    public void localLinksAndUnlinksGoThroughTheRegistryAndRefreshTheSource() {
        MinecraftPortal target = portal("Target", PortalType.PORTAL, "minecraft:overworld", 3.5D, 64.0D, 4.5D);
        UUID sourceId = source.getId();
        UUID targetId = target.getId();
        when(portals.link(player, sourceId, targetId)).thenReturn(true);
        when(portals.link(player, sourceId, null)).thenReturn(true);

        host.link(viewer, sourceId, view(targetId, null));
        host.unlink(viewer, sourceId);

        verify(portals).link(player, sourceId, targetId);
        verify(portals).link(player, sourceId, null);
        verify(menus, times(2)).invalidate(new MenuScope.Portal(sourceId));
    }

    @Test
    public void remoteLinksStoreTheServerAndPortal() {
        UUID sourceId = source.getId();
        UUID remoteId = UUID.randomUUID();
        when(portals.update(eq(player), eq(sourceId), any())).thenAnswer(invocation -> {
            Consumer<MinecraftPortal> mutation = invocation.getArgument(2);
            mutation.accept(source);
            return true;
        });

        host.link(viewer, sourceId, view(remoteId, "lobby"));

        verify(source).linkRemote("lobby", remoteId);
        verify(menus).invalidate(new MenuScope.Portal(sourceId));
    }

    @Test
    public void refusedLinksRefreshNothing() {
        UUID sourceId = source.getId();

        host.link(viewer, sourceId, view(UUID.randomUUID(), null));
        host.unlink(viewer, sourceId);

        verify(menus, never()).invalidate(any());
    }

    @Test
    public void theLocalDestinationIsOnlyALinkedLocalPortal() {
        MinecraftPortal target = portal("Target", PortalType.PORTAL, "minecraft:overworld", 3.5D, 64.0D, 4.5D);
        UUID targetId = target.getId();

        assertTrue(host.localDestination(source.getId()).isEmpty());

        when(source.getDestinationId()).thenReturn(targetId);
        DestinationMenuView destination = host.localDestination(source.getId()).orElseThrow();
        assertEquals(targetId, destination.id());
        assertTrue(destination.linked());

        when(source.getTunnelType()).thenReturn("UNIVERSAL");
        assertTrue(host.localDestination(source.getId()).isEmpty());
    }

    @Test
    public void pairingReportsTheNexusResult() {
        MinecraftPortal target = portal("Target", PortalType.PORTAL, "minecraft:overworld", 3.5D, 64.0D, 4.5D);
        UUID sourceId = source.getId();
        UUID targetId = target.getId();

        assertEquals(PairOutcome.PAIRED, host.pair(viewer, sourceId, targetId));
        verify(nexus).pair(player, source, target, true);
        verify(menus).invalidate(new MenuScope.Portal(sourceId));
        verify(menus).invalidate(new MenuScope.Portal(targetId));

        doThrow(new IllegalArgumentException("denied")).when(nexus).pair(player, source, target, true);
        assertEquals(PairOutcome.DENIED, host.pair(viewer, sourceId, targetId));
        assertEquals(PairOutcome.UNAVAILABLE, host.pair(viewer, sourceId, UUID.randomUUID()));
    }

    @Test
    public void theSelectionListsEveryPortalUnlinked() {
        portal("Gate", PortalType.GATEWAY, "minecraft:overworld", 2.5D, 64.0D, 2.5D);
        portal("Random", PortalType.RTP, "minecraft:overworld", 1.5D, 64.0D, 1.5D);

        List<DestinationMenuView> listed = host.portals();

        assertEquals(List.of("Source", "Gate", "Random"), listed.stream().map(DestinationMenuView::name).toList());
        assertTrue(listed.stream().noneMatch(DestinationMenuView::linked));
        assertTrue(listed.stream().allMatch(view -> view.distanceSquared() == Double.MAX_VALUE));
    }

    private MinecraftPortal portal(String name, PortalType type, String world, double x, double y, double z) {
        MinecraftPortal portal = mock(MinecraftPortal.class);
        UUID id = UUID.randomUUID();
        ApertureCells geometry = mock(ApertureCells.class);
        when(geometry.getApertureCenter()).thenReturn(new Vec3d(x, y, z));
        when(portal.getId()).thenReturn(id);
        when(portal.getName()).thenReturn(name);
        when(portal.getType()).thenReturn(type);
        when(portal.getWorldKey()).thenReturn(world);
        when(portal.getGeometry()).thenReturn(geometry);
        when(portal.getDirection()).thenReturn(Face.N);
        when(portal.getDimensionalKind()).thenReturn(DimensionalPortalKind.NONE);
        when(portals.get(id)).thenReturn(portal);
        when(portals.resolveLevel(portal)).thenReturn(mock(ServerLevel.class));
        registered.add(portal);
        return portal;
    }

    private RemotePortal remote(String name, String server, PortalType type, boolean open) {
        RemotePortal portal = mock(RemotePortal.class);
        UUID id = UUID.randomUUID();
        when(portal.getId()).thenReturn(id);
        when(portal.getName()).thenReturn(name);
        when(portal.getServer()).thenReturn(new RemoteWorld(server, server + "_world"));
        when(portal.getType()).thenReturn(type);
        when(portal.isOpen()).thenReturn(open);
        when(portal.getOrigin()).thenReturn(new Vec3d(12.5D, 70.0D, -3.5D));
        when(portal.getDirection()).thenReturn(Face.E);
        remotes.add(portal);
        return portal;
    }

    private static DestinationMenuView view(UUID id, String server) {
        return new DestinationMenuView(id, "Target", "minecraft:overworld", 0, 64, 0, Face.N, 0.0D, false, server, true);
    }
}
