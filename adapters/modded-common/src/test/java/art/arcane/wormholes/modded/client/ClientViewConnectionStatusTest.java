package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.network.client.ClientViewCapability;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.core.registries.BuiltInRegistries;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class ClientViewConnectionStatusTest extends MinecraftTestBase {
    @Test
    public void pendingAndUnansweredNegotiationAreDisconnectedUntilAccepted() {
        ClientViewSession session = session(new WormholesClientConfig());
        assertEquals(ClientViewSession.ConnectionStatus.DISCONNECTED, session.connectionStatus());
        session.offer(offer(ClientViewProtocol.WIRE_VERSION, 1));
        assertEquals(ClientViewSession.ConnectionStatus.DISCONNECTED, session.connectionStatus());
        session.unanswered();
        assertEquals(ClientViewSession.ConnectionStatus.DISCONNECTED, session.connectionStatus());
        session.accept(accept());
        assertEquals(ClientViewSession.ConnectionStatus.CONNECTED, session.connectionStatus());
    }

    @Test
    public void wireAndMinecraftVersionIncompatibilityShowMismatchFromTheOffer() {
        ClientViewSession session = session(new WormholesClientConfig());
        session.offer(offer(ClientViewProtocol.WIRE_VERSION + 1, 1));
        assertEquals(ClientViewSession.ConnectionStatus.MISMATCH, session.connectionStatus());
        session.offer(offer(ClientViewProtocol.WIRE_VERSION, 2));
        assertEquals(ClientViewSession.ConnectionStatus.MISMATCH, session.connectionStatus());
    }

    @Test
    public void explicitMismatchDeclinesClearWhenAnotherNegotiationSucceeds() {
        ClientViewSession session = session(new WormholesClientConfig());
        session.offer(offer(ClientViewProtocol.WIRE_VERSION, 1));
        session.decline(new ClientViewMessage.Decline(ClientViewMessage.DeclineReason.WIRE_MISMATCH));
        assertEquals(ClientViewSession.ConnectionStatus.MISMATCH, session.connectionStatus());
        session.offer(offer(ClientViewProtocol.WIRE_VERSION, 1));
        assertEquals(ClientViewSession.ConnectionStatus.DISCONNECTED, session.connectionStatus());
        session.decline(new ClientViewMessage.Decline(ClientViewMessage.DeclineReason.DATA_VERSION_MISMATCH));
        assertEquals(ClientViewSession.ConnectionStatus.MISMATCH, session.connectionStatus());
        session.accept(accept());
        assertEquals(ClientViewSession.ConnectionStatus.CONNECTED, session.connectionStatus());
        session.abandon(mock(ClientViewSession.Sink.class));
        assertEquals(ClientViewSession.ConnectionStatus.DISCONNECTED, session.connectionStatus());
    }

    @Test
    public void disabledCapacityAndStandardPacketRenderingAreDisconnected() {
        ClientViewSession session = session(new WormholesClientConfig());
        session.decline(new ClientViewMessage.Decline(ClientViewMessage.DeclineReason.DISABLED));
        assertEquals(ClientViewSession.ConnectionStatus.DISCONNECTED, session.connectionStatus());
        session.decline(new ClientViewMessage.Decline(ClientViewMessage.DeclineReason.CAPACITY));
        assertEquals(ClientViewSession.ConnectionStatus.DISCONNECTED, session.connectionStatus());
        WormholesClientConfig config = new WormholesClientConfig();
        config.renderer = "block-packets";
        ClientViewSession standard = session(config);
        standard.offer(offer(ClientViewProtocol.WIRE_VERSION + 1, 2));
        standard.accept(accept());
        assertEquals(ClientViewSession.ConnectionStatus.DISCONNECTED, standard.connectionStatus());
    }

    @Test
    public void debugEntryShowsTheExactStatusWithWormholesBrandAndStatusColors() {
        assertLine(ClientViewSession.ConnectionStatus.CONNECTED, "Connected", ChatFormatting.GREEN);
        assertLine(ClientViewSession.ConnectionStatus.MISMATCH, "Mismatch", ChatFormatting.YELLOW);
        assertLine(ClientViewSession.ConnectionStatus.DISCONNECTED, "Disconnected", ChatFormatting.RED);
        DebugScreenDisplayer displayer = mock(DebugScreenDisplayer.class);
        ClientViewDebugEntry entry = new ClientViewDebugEntry(ClientViewSession.ConnectionStatus.CONNECTED::debugLine);
        entry.display(displayer, null, null, null);
        verify(displayer).addLine(ClientViewSession.ConnectionStatus.CONNECTED.debugLine());
    }

    private static void assertLine(ClientViewSession.ConnectionStatus status, String label, ChatFormatting color) {
        assertEquals("Wormholes: " + label, ChatFormatting.stripFormatting(status.debugLine()));
        assertEquals(ChatFormatting.GOLD + "Wormholes: " + color + label + ChatFormatting.RESET, status.debugLine());
    }

    private static ClientViewSession session(WormholesClientConfig config) {
        return new ClientViewSession(config, new ClientPalette(BuiltInRegistries.BLOCK), 1, "test");
    }

    private static ClientViewMessage.Offer offer(int wire, int dataVersion) {
        return new ClientViewMessage.Offer(wire, dataVersion, ClientViewCapability.ALL, ClientViewProtocol.DEFAULT_MAX_FRAME_BYTES, 0L);
    }

    private static ClientViewMessage.Accept accept() {
        return new ClientViewMessage.Accept(1, ClientViewCapability.ALL, ClientViewProtocol.DEFAULT_TICK_RATE,
            ClientViewProtocol.DEFAULT_MAX_FRAME_BYTES, 7L, 8);
    }
}
