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
import art.arcane.optics.stream.ViewStreamMessage;

final class ClientPreparedTravelCacheCodecTest {
    private static final UUID TOKEN = new UUID(12, 34);

    @Test
    void exactCoordinatesRevisionHashAndAvailabilityRoundTrip() throws ViewStreamProtocolException {
        byte[] hash = hash();
        TravelMessage.TravelReuse reuse = new TravelMessage.TravelReuse(TOKEN, 3, -32, -10, 9, hash);
        byte[] frame = ClientViewExtensions.CODEC.encodeS2C(TravelExtension.PREPARED.wrap(reuse), 5, ViewStreamLimits.FLAG_LAST, true);
        assertEquals(TravelExtension.PREPARED.wrap(reuse), ClientViewExtensions.CODEC.decodeS2C(frame, ViewStreamCapability.ALL).message());
        for (boolean available : new boolean[]{false, true}) {
            TravelMessage.TravelCached cached = new TravelMessage.TravelCached(TOKEN, 3, -32, -10, 9, hash, available);
            assertEquals(TravelExtension.PREPARED.wrap(cached),
                ClientViewExtensions.CODEC.decodeC2S(ClientViewExtensions.CODEC.encodeC2S(TravelExtension.PREPARED.wrap(cached))));
        }
    }

    @Test
    void hashBytesAreImmutableAndRequireExactSha256Length() {
        byte[] hash = hash();
        TravelMessage.TravelReuse reuse = new TravelMessage.TravelReuse(TOKEN, 3, 0, 0, 1, hash);
        TravelMessage.TravelCached cached = new TravelMessage.TravelCached(TOKEN, 3, 0, 0, 1, hash, true);
        hash[0]++;
        reuse.hash()[1]++;
        cached.hash()[2]++;
        assertArrayEquals(hash(), reuse.hash());
        assertArrayEquals(hash(), cached.hash());
        for (int size : new int[]{0, 31, 33}) {
            assertThrows(IllegalArgumentException.class, () -> new TravelMessage.TravelReuse(TOKEN, 3, 0, 0, 1, new byte[size]));
            assertThrows(IllegalArgumentException.class, () -> new TravelMessage.TravelCached(TOKEN, 3, 0, 0, 1, new byte[size], true));
        }
    }

    @Test
    void decoderRejectsInvalidGenerationRevisionAndAvailability() throws ViewStreamProtocolException {
        byte[] reuse = ClientViewExtensions.CODEC.encodeS2C(TravelExtension.PREPARED.wrap(new TravelMessage.TravelReuse(TOKEN, 3, 0, 0, 1, hash())),
            5, 0, true);
        byte[] cached = ClientViewExtensions.CODEC.encodeC2S(TravelExtension.PREPARED.wrap(new TravelMessage.TravelCached(TOKEN, 3, 0, 0, 1, hash(), true)));
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
        assertThrows(ViewStreamProtocolException.class, () -> ClientViewExtensions.CODEC.decodeC2S(cached));
    }

    private static ViewStreamMessage decode(byte[] frame, boolean clientbound) throws ViewStreamProtocolException {
        return clientbound ? ClientViewExtensions.CODEC.decodeS2C(frame, ViewStreamCapability.ALL).message() : ClientViewExtensions.CODEC.decodeC2S(frame);
    }

    private static byte[] hash() {
        byte[] hash = new byte[32];
        for (int index = 0; index < hash.length; index++) {
            hash[index] = (byte) index;
        }
        return hash;
    }
}
