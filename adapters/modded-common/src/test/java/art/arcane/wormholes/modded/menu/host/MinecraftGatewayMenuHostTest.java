package art.arcane.wormholes.modded.menu.host;

import art.arcane.wormholes.menu.portal.GatewayLinkState;
import art.arcane.wormholes.menu.portal.GatewayView;
import art.arcane.wormholes.menu.portal.ImportOutcome;
import art.arcane.wormholes.modded.MinecraftNetworkService;
import art.arcane.wormholes.modded.MinecraftNetworkTools;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftPortalRegistry;
import art.arcane.wormholes.modded.MinecraftPortalText;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.menu.MinecraftMenuViewer;
import art.arcane.wormholes.network.NetworkManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftGatewayMenuHostTest extends MinecraftTestBase {
    private static final UUID PORTAL = UUID.fromString("f1b2c3d4-0000-4000-8000-0000000000aa");

    private WormholesModRuntime runtime;
    private MinecraftPortalRegistry portals;
    private NetworkManager network;
    private MinecraftPortal portal;
    private MinecraftGatewayMenuHost host;

    @Before
    public void setUp() {
        runtime = mock(WormholesModRuntime.class);
        portals = mock(MinecraftPortalRegistry.class);
        MinecraftNetworkService service = mock(MinecraftNetworkService.class);
        network = mock(NetworkManager.class);
        portal = mock(MinecraftPortal.class);
        when(runtime.portals()).thenReturn(portals);
        when(runtime.network()).thenReturn(service);
        when(service.manager()).thenReturn(network);
        when(portals.get(PORTAL)).thenReturn(portal);
        host = new MinecraftGatewayMenuHost(runtime);
    }

    @Test
    public void portalsWithoutAResolvedDestinationAreUnpaired() {
        try (MockedStatic<MinecraftPortalText> text = mockStatic(MinecraftPortalText.class)) {
            assertEquals(GatewayView.UNPAIRED, host.gateway(PORTAL));
            assertEquals(GatewayView.UNPAIRED, host.gateway(UUID.randomUUID()));
        }
    }

    @Test
    public void localPairsShowOnlyTheDestination() {
        try (MockedStatic<MinecraftPortalText> text = mockStatic(MinecraftPortalText.class)) {
            text.when(() -> MinecraftPortalText.linkedDestinationName(runtime, portal)).thenReturn("Beta");
            when(portal.getTunnelType()).thenReturn("LOCAL");

            assertEquals(new GatewayView("Beta", null, null), host.gateway(PORTAL));
        }
    }

    @Test
    public void remotePairsReportThePeerLinkState() {
        try (MockedStatic<MinecraftPortalText> text = mockStatic(MinecraftPortalText.class)) {
            text.when(() -> MinecraftPortalText.linkedDestinationName(runtime, portal)).thenReturn("Beta");
            when(portal.getTunnelType()).thenReturn("UNIVERSAL");
            when(portal.getDestinationServer()).thenReturn("lobby");

            assertEquals(new GatewayView("Beta", "lobby", GatewayLinkState.RECONNECTING), host.gateway(PORTAL));

            when(network.isPeerReady("lobby")).thenReturn(true);
            assertEquals(new GatewayView("Beta", "lobby", GatewayLinkState.DIRECT), host.gateway(PORTAL));

            when(network.isSidebandOnlyPeer("lobby")).thenReturn(true);
            assertEquals(new GatewayView("Beta", "lobby", GatewayLinkState.SIDEBAND), host.gateway(PORTAL));
        }
    }

    @Test
    public void exportAndImportUseTheNetworkToolsAsThePlayer() {
        MinecraftNetworkTools tools = mock(MinecraftNetworkTools.class);
        ServerPlayer player = mock(ServerPlayer.class);
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(runtime.networkTools()).thenReturn(tools);
        when(player.createCommandSourceStack()).thenReturn(source);
        MinecraftMenuViewer viewer = new MinecraftMenuViewer(runtime, player);

        host.exportToChat(viewer, PORTAL);
        ImportOutcome outcome = host.importCode(viewer, PORTAL, "WH1-CODE");

        verify(tools).exportPortal(source, PORTAL);
        verify(tools).importCode(source, PORTAL, "WH1-CODE");
        assertEquals(ImportOutcome.imported(), outcome);
    }
}
