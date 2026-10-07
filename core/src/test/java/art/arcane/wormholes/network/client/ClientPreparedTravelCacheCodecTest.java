package art.arcane.wormholes.network.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import art.arcane.optics.stream.ViewStreamProtocolException;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamLimits;

final class ClientPreparedTravelCacheCodecTest {
    private static final UUID TOKEN = new UUID(12, 34);

    @Test
    void exactCoordinatesRevisionHashAndAvailabilityRoundTrip() throws ViewStreamProtocolException {
        byte[] hash = hash();
        ClientViewMessage.TravelReuse reuse = new ClientViewMessage.TravelReuse(TOKEN, 3, -32, -10, 9, hash);
        byte[] frame = ClientViewCodec.encodeS2C(reuse, 5, ViewStreamLimits.FLAG_LAST, true);
        assertEquals(reuse, ClientViewCodec.decodeS2C(frame, ViewStreamCapability.ALL).message());
        for (boolean available : new boolean[]{false, true}) {
            ClientViewMessage.TravelCached cached = new ClientViewMessage.TravelCached(TOKEN, 3, -32, -10, 9, hash, available);
            assertEquals(cached, ClientViewCodec.decodeC2S(ClientViewCodec.encodeC2S(cached)));
        }
    }

    @Test
    void hashBytesAreImmutableAndRequireExactSha256Length() {
        byte[] hash = hash();
        ClientViewMessage.TravelReuse reuse = new ClientViewMessage.TravelReuse(TOKEN, 3, 0, 0, 1, hash);
        ClientViewMessage.TravelCached cached = new ClientViewMessage.TravelCached(TOKEN, 3, 0, 0, 1, hash, true);
        hash[0]++;
        reuse.hash()[1]++;
        cached.hash()[2]++;
        assertArrayEquals(hash(), reuse.hash());
        assertArrayEquals(hash(), cached.hash());
        for (int size : new int[]{0, 31, 33}) {
            assertThrows(IllegalArgumentException.class, () -> new ClientViewMessage.TravelReuse(TOKEN, 3, 0, 0, 1, new byte[size]));
            assertThrows(IllegalArgumentException.class, () -> new ClientViewMessage.TravelCached(TOKEN, 3, 0, 0, 1, new byte[size], true));
        }
    }

    @Test
    void decoderRejectsInvalidGenerationRevisionAndAvailability() throws ViewStreamProtocolException {
        byte[] reuse = ClientViewCodec.encodeS2C(new ClientViewMessage.TravelReuse(TOKEN, 3, 0, 0, 1, hash()), 5, 0, true);
        byte[] cached = ClientViewCodec.encodeC2S(new ClientViewMessage.TravelCached(TOKEN, 3, 0, 0, 1, hash(), true));
        for (boolean clientbound : new boolean[]{false, true}) {
            byte[] frame = clientbound ? reuse : cached;
            int header = clientbound ? ViewStreamLimits.S2C_HEADER_BYTES : ViewStreamLimits.C2S_HEADER_BYTES;
            for (long generation : new long[]{0, -1}) {
                byte[] invalid = frame.clone();
                ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putLong(header + 16, generation);
                assertThrows(ViewStreamProtocolException.class, () -> decode(invalid, clientbound));
            }
            for (int revision : new int[]{0, -1}) {
                byte[] invalid = frame.clone();
                ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putInt(header + 32, revision);
                assertThrows(ViewStreamProtocolException.class, () -> decode(invalid, clientbound));
            }
            assertThrows(ViewStreamProtocolException.class, () -> decode(Arrays.copyOf(frame, frame.length - 1), clientbound));
            assertThrows(ViewStreamProtocolException.class, () -> decode(Arrays.copyOf(frame, frame.length + 1), clientbound));
        }
        cached[cached.length - 1] = 2;
        assertThrows(ViewStreamProtocolException.class, () -> ClientViewCodec.decodeC2S(cached));
    }

    private static ClientViewMessage decode(byte[] frame, boolean clientbound) throws ViewStreamProtocolException {
        return clientbound ? ClientViewCodec.decodeS2C(frame, ViewStreamCapability.ALL).message() : ClientViewCodec.decodeC2S(frame);
    }

    private static byte[] hash() {
        byte[] hash = new byte[32];
        for (int index = 0; index < hash.length; index++) {
            hash[index] = (byte) index;
        }
        return hash;
    }
}
