package art.arcane.wormholes.modded.menu.host;

import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.nexus.DialOutcome;
import art.arcane.wormholes.modded.MinecraftNexus;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftPortalRegistry;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.MinecraftTestSettings;
import art.arcane.wormholes.modded.WormholesModConfiguration;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.menu.MinecraftMenuViewer;
import art.arcane.wormholes.nexus.DialState;
import art.arcane.wormholes.nexus.NetworkMember;
import art.arcane.wormholes.nexus.NetworkRegistry;
import art.arcane.wormholes.nexus.PortalNetwork;
import net.minecraft.server.level.ServerPlayer;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftDialMenuHostTest extends MinecraftTestBase {
    private static final UUID PORTAL = UUID.fromString("d1a10000-0000-4000-8000-0000000000aa");
    private static final UUID PEER = UUID.fromString("d1a10000-0000-4000-8000-0000000000ab");
    private static final UUID NETWORK = UUID.fromString("d1a10000-0000-4000-8000-0000000000bb");

    private MinecraftNexus nexus;
    private NetworkRegistry registry;
    private ServerPlayer player;
    private MinecraftPortal portal;
    private MinecraftPortal peer;
    private MenuViewer viewer;
    private MinecraftDialMenuHost host;

    @Before
    public void setUp() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        MinecraftPortalRegistry portals = mock(MinecraftPortalRegistry.class);
        WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
        nexus = mock(MinecraftNexus.class);
        registry = mock(NetworkRegistry.class);
        player = mock(ServerPlayer.class);
        portal = mock(MinecraftPortal.class);
        peer = mock(MinecraftPortal.class);
        when(runtime.portals()).thenReturn(portals);
        when(runtime.nexus()).thenReturn(nexus);
        when(runtime.configuration()).thenReturn(configuration);
        when(configuration.settings()).thenReturn(MinecraftTestSettings.defaults());
        when(nexus.networks()).thenReturn(registry);
        when(portals.get(PORTAL)).thenReturn(portal);
        when(portals.get(PEER)).thenReturn(peer);
        when(portal.getId()).thenReturn(PORTAL);
        when(peer.getWorldKey()).thenReturn("minecraft:the_nether");
        when(portal.setting("nexus.networkId")).thenReturn(NETWORK.toString());
        PortalNetwork network = PortalNetwork.create(NETWORK, "Lobby", UUID.randomUUID())
            .withMember(PORTAL, new NetworkMember(PORTAL, "AAAA", "Alpha", 1L, null))
            .withMember(PEER, new NetworkMember(PEER, "BBBB", "Beta", 2L, null));
        when(registry.byId(NETWORK)).thenReturn(network);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        viewer = new MinecraftMenuViewer(runtime, player);
        host = new MinecraftDialMenuHost(runtime);
    }

    @Test
    public void aKnownAddressDialsThroughTheNexus() {
        when(nexus.dial(player, portal, "BBBB")).thenReturn(true);

        assertEquals(DialOutcome.DIALED, host.dial(viewer, PORTAL, "BBBB"));
    }

    @Test
    public void unknownAndOwnAddressesAreRefusedBeforeDialing() {
        assertEquals(DialOutcome.UNKNOWN_ADDRESS, host.dial(viewer, PORTAL, "ZZZZ"));
        assertEquals(DialOutcome.UNKNOWN_ADDRESS, host.dial(viewer, PORTAL, "AAAA"));

        verify(nexus, never()).dial(any(), any(), anyString());
    }

    @Test
    public void managedPortalsAndPortalsOffTheNetworkCannotDial() {
        when(portal.isMirrorMode()).thenReturn(true);
        assertEquals(DialOutcome.MANAGED_PORTAL, host.dial(viewer, PORTAL, "BBBB"));

        when(portal.setting("nexus.networkId")).thenReturn(null);
        assertEquals(DialOutcome.NOT_ON_NETWORK, host.dial(viewer, PORTAL, "BBBB"));
        assertEquals(DialOutcome.NOT_ON_NETWORK, host.dial(viewer, UUID.randomUUID(), "BBBB"));
    }

    @Test
    public void aRecentDialIsDebounced() {
        Map<String, Object> dialed = new DialState("BBBB", System.currentTimeMillis(), PEER, false).toMap();
        when(portal.setting("nexus.dial")).thenReturn(dialed);

        assertEquals(DialOutcome.DEBOUNCED, host.dial(viewer, PORTAL, "BBBB"));
    }

    @Test
    public void worldNamesAreTheTargetWorldKey() {
        assertEquals("minecraft:the_nether", host.worldName(PEER));
        assertEquals("", host.worldName(UUID.randomUUID()));
    }

    @Test
    public void aPortalOffItsNetworkCannotBeDialledFromTheMenu() {
        when(registry.byId(NETWORK)).thenReturn(null);

        assertFalse(host.dialAdmission(viewer, PORTAL));
    }
}
