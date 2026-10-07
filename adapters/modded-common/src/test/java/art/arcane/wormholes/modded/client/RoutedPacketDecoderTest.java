package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.network.client.TravelMessage;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class RoutedPacketDecoderTest {
    @Test
    public void handlesReleaseIndependentlyAndInSequenceOrder() {
        RoutedPacketDecoder decoder = new RoutedPacketDecoder();
        byte[] large = payload(TravelMessage.TRAVEL_FRAGMENT_BYTES + 10, 1);
        byte[] small = payload(12, 2);
        TravelMessage.RoutedPacket first = fragment(1, 3, 0, large);
        TravelMessage.RoutedPacket second = fragment(1, 3, 1, large);
        assertTrue(decoder.accept(first).isEmpty());
        assertTrue(decoder.accept(fragment(1, 4, 0, small)).isEmpty());
        List<byte[]> other = decoder.accept(fragment(2, 1, 0, small));
        assertEquals(1, other.size());
        List<byte[]> released = decoder.accept(second);
        assertEquals(2, released.size());
        assertArrayEquals(large, released.get(0));
        assertArrayEquals(small, released.get(1));
        assertEquals(4, decoder.lastSequence(1));
        assertEquals(1, decoder.lastSequence(2));
        assertTrue(decoder.accept(fragment(1, 4, 0, small)).isEmpty());
        assertEquals(0L, decoder.pendingBytes());
    }

    @Test
    public void conflictingFragmentLayoutsAndOversizedBacklogsAreRejected() {
        RoutedPacketDecoder decoder = new RoutedPacketDecoder();
        byte[] large = payload(TravelMessage.TRAVEL_FRAGMENT_BYTES + 10, 1);
        decoder.accept(fragment(1, 3, 0, large));
        byte[] other = payload(TravelMessage.TRAVEL_FRAGMENT_BYTES * 2 + 10, 2);
        assertThrows(IllegalArgumentException.class, () -> decoder.accept(fragment(1, 3, 0, other)));
        decoder.forget(1);
        assertEquals(0L, decoder.pendingBytes());
        byte[] huge = payload(TravelMessage.MAX_ROUTED_PACKET_BYTES, 3);
        int sequence = 0;
        while (decoder.pendingBytes() + huge.length <= RoutedPacketDecoder.MAX_PENDING_BYTES) {
            decoder.accept(fragment(5, sequence++, 0, huge));
        }
        int next = sequence;
        assertThrows(IllegalArgumentException.class, () -> decoder.accept(fragment(5, next, 0, huge)));
    }

    private static TravelMessage.RoutedPacket fragment(int handle, int sequence, int index, byte[] payload) {
        int count = (payload.length + TravelMessage.TRAVEL_FRAGMENT_BYTES - 1) / TravelMessage.TRAVEL_FRAGMENT_BYTES;
        int offset = index * TravelMessage.TRAVEL_FRAGMENT_BYTES;
        byte[] piece = new byte[Math.min(TravelMessage.TRAVEL_FRAGMENT_BYTES, payload.length - offset)];
        System.arraycopy(payload, offset, piece, 0, piece.length);
        return new TravelMessage.RoutedPacket(handle, sequence, index, count, payload.length, piece);
    }

    private static byte[] payload(int length, int seed) {
        byte[] bytes = new byte[length];
        for (int index = 0; index < length; index++) {
            bytes[index] = (byte) (index * 31 + seed);
        }
        return bytes;
    }
}
