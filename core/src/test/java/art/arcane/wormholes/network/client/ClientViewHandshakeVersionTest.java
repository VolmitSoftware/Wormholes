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
    void wireVersionIsEight() {
        assertEquals(8, ViewStreamLimits.WIRE_VERSION);
    }

    @Test
    void channelCarriesTheNativeProtocolVersion() {
        assertEquals("wormholes", ClientViewChannel.NAMESPACE);
        assertEquals("v9", ClientViewChannel.PATH);
        assertEquals("wormholes:v9", ClientViewChannel.CHANNEL);
    }

    @Test
    void offerAdvertisesEightAndASevenHelloIsAWireMismatch() {
        ViewStreamHandshake.Policy policy = new ViewStreamHandshake.Policy(true, DATA_VERSION, ViewStreamCapability.ALL,
            ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES, 100, 20, 8, true);
        ViewStreamHandshake handshake = new ViewStreamHandshake(policy, ClientViewExtensions.CODEC, 0L, () -> 7, () -> 0x1234L);
        assertEquals(8, handshake.offer(0L).wire());
        ViewStreamMessage.Hello previous = new ViewStreamMessage.Hello(7, DATA_VERSION, ViewStreamCapability.ALL, 1, 1, 0L, "fabric");
        ViewStreamMessage.Decline decline = (ViewStreamMessage.Decline) handshake.onHello(previous, 1L, true).reply();
        assertEquals(ViewStreamMessage.DeclineReason.WIRE_MISMATCH, decline.reason());
    }
}
