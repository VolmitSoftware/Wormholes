package art.arcane.wormholes.network.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ClientViewHandshakeVersionTest {
    private static final int DATA_VERSION = 4325;

    @Test
    void wireVersionIsSix() {
        assertEquals(6, ClientViewProtocol.WIRE_VERSION);
    }

    @Test
    void channelCarriesTheWireVersion() {
        assertEquals("wormholes", ClientViewChannel.NAMESPACE);
        assertEquals("v6", ClientViewChannel.PATH);
        assertEquals("wormholes:v6", ClientViewChannel.CHANNEL);
    }

    @Test
    void offerAdvertisesSixAndAFiveHelloIsAWireMismatch() {
        ClientViewHandshake.Policy policy = new ClientViewHandshake.Policy(true, DATA_VERSION, ClientViewCapability.ALL,
            ClientViewProtocol.DEFAULT_MAX_FRAME_BYTES, 100, 20, 8, true);
        ClientViewHandshake handshake = new ClientViewHandshake(policy, 0L, () -> 7, () -> 0x1234L);
        assertEquals(6, handshake.offer(0L).wire());
        ClientViewMessage.Hello previous = new ClientViewMessage.Hello(5, DATA_VERSION, ClientViewCapability.ALL, 1, 1, 0L, "fabric");
        ClientViewMessage.Decline decline = (ClientViewMessage.Decline) handshake.onHello(previous, 1L, true).reply();
        assertEquals(ClientViewMessage.DeclineReason.WIRE_MISMATCH, decline.reason());
    }
}
