package art.arcane.wormholes.modded.menu.host;

import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuService;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.nexus.NetworkEdit;
import art.arcane.wormholes.menu.nexus.NexusPortalView;
import art.arcane.wormholes.modded.MinecraftNexus;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftPortalRegistry;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.menu.MinecraftMenuViewer;
import art.arcane.wormholes.nexus.DestinationPolicy;
import art.arcane.wormholes.nexus.FrameIo;
import art.arcane.wormholes.nexus.NetworkRegistry;
import art.arcane.wormholes.nexus.PortalNetwork;
import art.arcane.wormholes.portal.PortalType;
import net.minecraft.server.level.ServerPlayer;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftNetworkMenuHostTest extends MinecraftTestBase {
    private static final UUID PORTAL = UUID.fromString("e0e70000-0000-4000-8000-0000000000aa");
    private static final UUID NETWORK = UUID.fromString("e0e70000-0000-4000-8000-0000000000bb");

    private WormholesModRuntime runtime;
    private MinecraftPortalRegistry portals;
    private NetworkRegistry registry;
    private MenuService menus;
    private ServerPlayer player;
    private MinecraftPortal portal;
    private MenuViewer viewer;
    private MinecraftNetworkMenuHost host;

    @Before
    public void setUp() {
        runtime = mock(WormholesModRuntime.class);
        portals = mock(MinecraftPortalRegistry.class);
        MinecraftNexus nexus = mock(MinecraftNexus.class);
        registry = mock(NetworkRegistry.class);
        menus = mock(MenuService.class);
        player = mock(ServerPlayer.class);
        portal = mock(MinecraftPortal.class);
        when(runtime.portals()).thenReturn(portals);
        when(runtime.nexus()).thenReturn(nexus);
        when(runtime.menus()).thenReturn(menus);
        when(nexus.networks()).thenReturn(registry);
        when(portals.get(PORTAL)).thenReturn(portal);
        when(portal.getId()).thenReturn(PORTAL);
        when(portal.getName()).thenReturn("Alpha");
        when(portal.getType()).thenReturn(PortalType.PORTAL);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        when(portals.update(eq(player), eq(PORTAL), any())).thenAnswer(invocation -> {
            Consumer<MinecraftPortal> mutation = invocation.getArgument(2);
            mutation.accept(portal);
            return true;
        });
        viewer = new MinecraftMenuViewer(runtime, player);
        host = new MinecraftNetworkMenuHost(runtime);
    }

    @Test
    public void thePortalViewReadsTheStoredNexusSettings() {
        when(portal.setting("nexus.networkId")).thenReturn(NETWORK.toString());
        when(portal.setting("nexus.address")).thenReturn("ALPHA");
        when(portal.setting("nexus.reciprocal")).thenReturn(true);

        NexusPortalView view = host.portal(PORTAL).orElseThrow();

        assertEquals("Alpha", view.name());
        assertEquals(NETWORK, view.networkId());
        assertEquals("ALPHA", view.address());
        assertTrue(view.reciprocal());
        assertEquals(DestinationPolicy.empty().mode(), view.policy().mode());
        assertFalse(host.portal(UUID.randomUUID()).isPresent());
    }

    @Test
    public void joiningAndLeavingWriteThePortalThroughTheRegistryAndRefreshIt() {
        host.edit(viewer, PORTAL, new NetworkEdit.Join(NETWORK, "ALPHA", "Alpha"));
        host.edit(viewer, PORTAL, new NetworkEdit.Leave());

        verify(portal).setNexusValue("networkId", NETWORK.toString());
        verify(portal).setNexusValue("address", "ALPHA");
        verify(portal).setNexusValue("label", "Alpha");
        verify(portal).setNexusValue("networkId", null);
        verify(portal).setNexusValue("address", null);
        verify(portal).setNexusValue("dial", null);
        verify(menus, times(2)).invalidate(new MenuScope.Portal(PORTAL));
    }

    @Test
    public void policyAndRedstoneAreStoredAsDocuments() {
        FrameIo frameIo = FrameIo.none().withAction(FrameIo.none().action().next());

        host.edit(viewer, PORTAL, new NetworkEdit.Policy(DestinationPolicy.empty()));
        host.edit(viewer, PORTAL, new NetworkEdit.Redstone(frameIo));

        verify(portal).setNexusValue("policy", DestinationPolicy.empty().toMap());
        verify(portal).setNexusValue("frameIo", frameIo.toMap());
    }

    @Test
    public void refusedUpdatesRefreshNothing() {
        when(portals.update(eq(player), eq(PORTAL), any())).thenReturn(false);

        host.edit(viewer, PORTAL, new NetworkEdit.Reciprocal(true));

        verify(menus, never()).invalidate(any());
    }

    @Test
    public void savedNetworksRefreshEveryNetworkWindowAndFailedSavesReportFalse() throws IOException {
        PortalNetwork network = PortalNetwork.create(NETWORK, "Lobby", player.getUUID());

        assertTrue(host.saveNetwork(network));
        verify(menus).invalidate(new MenuScope.Network(NETWORK));

        doThrow(new IOException("disk full")).when(registry).save(network);

        assertFalse(host.saveNetwork(network));
    }

    @Test
    public void networkLookupsReadTheRegistry() {
        PortalNetwork network = PortalNetwork.create(NETWORK, "Lobby", player.getUUID());
        when(registry.byName("lobby")).thenReturn(network);
        when(registry.byId(NETWORK)).thenReturn(network);
        when(registry.ownedBy(player.getUUID())).thenReturn(List.of(network));

        assertSame(network, host.networkByName("lobby").orElseThrow());
        assertSame(network, host.network(NETWORK).orElseThrow());
        assertEquals(1, host.networksOwnedBy(player.getUUID()));
        assertFalse(host.networkByName("missing").isPresent());
    }
}
