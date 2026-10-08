package art.arcane.wormholes.network.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.stream.ViewStreamHandshake;
import art.arcane.optics.stream.ViewStreamMessage;

final class ClientViewHandshakeVersionTest {
    private static final int DATA_VERSION = 4325;

    @Test
    void wireVersionIsSeven() {
        assertEquals(7, ViewStreamLimits.WIRE_VERSION);
    }

    @Test
    void channelCarriesTheWireVersion() {
        assertEquals("wormholes", ClientViewChannel.NAMESPACE);
        assertEquals("v7", ClientViewChannel.PATH);
        assertEquals("wormholes:v7", ClientViewChannel.CHANNEL);
    }

    @Test
    void offerAdvertisesSevenAndASixHelloIsAWireMismatch() {
        ViewStreamHandshake.Policy policy = new ViewStreamHandshake.Policy(true, DATA_VERSION, ViewStreamCapability.ALL,
            ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES, 100, 20, 8, true);
        ViewStreamHandshake handshake = new ViewStreamHandshake(policy, ClientViewExtensions.CODEC, 0L, () -> 7, () -> 0x1234L);
        assertEquals(7, handshake.offer(0L).wire());
        ViewStreamMessage.Hello previous = new ViewStreamMessage.Hello(6, DATA_VERSION, ViewStreamCapability.ALL, 1, 1, 0L, "fabric");
        ViewStreamMessage.Decline decline = (ViewStreamMessage.Decline) handshake.onHello(previous, 1L, true).reply();
        assertEquals(ViewStreamMessage.DeclineReason.WIRE_MISMATCH, decline.reason());
    }
}
