package art.arcane.optics.stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

final class ViewStreamExtensionDispatchTest {
    @Test
    void extensionMessagesRoundTripInBothDirections() throws ViewStreamProtocolException {
        ViewStreamCodec codec = new ViewStreamCodec(List.of(Probe.range(60, 62)));
        ViewStreamMessage.Extension down = Probe.message(60, 7);
        byte[] frame = codec.encodeS2C(down, 3, ViewStreamLimits.FLAG_LAST);
        assertEquals(60, Byte.toUnsignedInt(frame[0]));
        ViewStreamCodec.S2CFrame decoded = codec.decodeS2C(frame, ViewStreamCapability.ALL);
        assertEquals(down, decoded.message());
        assertEquals(3, decoded.seq());
        assertTrue(decoded.last());
        ViewStreamMessage.Extension up = Probe.message(61, -9);
        assertEquals(up, codec.decodeC2S(codec.encodeC2S(up)));
    }

    @Test
    void unknownIdInsideARegisteredRangeIsAProtocolException() throws ViewStreamProtocolException {
        ViewStreamCodec codec = new ViewStreamCodec(List.of(Probe.range(60, 62)));
        byte[] down = codec.encodeS2C(Probe.message(60, 1), 0, 0);
        down[0] = 62;
        assertThrows(ViewStreamProtocolException.class, () -> codec.decodeS2C(down, ViewStreamCapability.ALL));
        byte[] up = codec.encodeC2S(Probe.message(61, 1));
        up[0] = 62;
        assertThrows(ViewStreamProtocolException.class, () -> codec.decodeC2S(up));
        assertThrows(ViewStreamProtocolException.class, () -> codec.encodeS2C(Probe.message(62, 1), 0, 0));
    }

    @Test
    void unregisteredIdsAndWrongDirectionsAreProtocolExceptions() throws ViewStreamProtocolException {
        ViewStreamCodec codec = new ViewStreamCodec(List.of(Probe.range(60, 62)));
        byte[] down = codec.encodeS2C(Probe.message(60, 1), 0, 0);
        byte[] stray = down.clone();
        stray[0] = 63;
        assertThrows(ViewStreamProtocolException.class, () -> codec.decodeS2C(stray, ViewStreamCapability.ALL));
        assertThrows(ViewStreamProtocolException.class, () -> new ViewStreamCodec(List.of()).decodeS2C(down, ViewStreamCapability.ALL));
        assertThrows(ViewStreamProtocolException.class, () -> codec.encodeC2S(Probe.message(60, 1)));
        assertThrows(ViewStreamProtocolException.class, () -> codec.encodeS2C(Probe.message(61, 1), 0, 0));
        byte[] upAsDown = codec.encodeC2S(Probe.message(61, 1));
        assertThrows(ViewStreamProtocolException.class, () -> codec.decodeC2S(down));
        assertThrows(ViewStreamProtocolException.class, () -> codec.decodeS2C(upAsDown, ViewStreamCapability.ALL));
        assertThrows(ViewStreamProtocolException.class, () -> codec.encodeS2C(new ViewStreamMessage.Extension(63, new Probe.Value(63, 1)), 0, 0));
    }

    @Test
    void payloadsMustMatchTheOwningExtensionAndTheirId() {
        ViewStreamCodec codec = new ViewStreamCodec(List.of(Probe.range(60, 62)));
        assertThrows(ViewStreamProtocolException.class, () -> codec.encodeS2C(new ViewStreamMessage.Extension(60, "text"), 0, 0));
        assertThrows(ViewStreamProtocolException.class, () -> codec.encodeS2C(new ViewStreamMessage.Extension(60, new Probe.Value(61, 1)), 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ViewStreamMessage.Extension(256, new Probe.Value(60, 1)));
        assertThrows(IllegalArgumentException.class, () -> new ViewStreamMessage.Extension(-1, new Probe.Value(60, 1)));
        assertThrows(NullPointerException.class, () -> new ViewStreamMessage.Extension(60, null));
    }

    @Test
    void overlappingRangesAreRejectedAtRegistration() {
        assertThrows(IllegalArgumentException.class, () -> new ViewStreamCodec(List.of(Probe.range(60, 62), Probe.range(62, 63))));
        assertThrows(IllegalArgumentException.class, () -> new ViewStreamCodec(List.of(Probe.range(60, 62), Probe.range(61, 61))));
        assertThrows(IllegalArgumentException.class, () -> new ViewStreamCodec(List.of(Probe.range(60, 60), Probe.range(60, 60))));
        ViewStreamCodec adjacent = new ViewStreamCodec(List.of(Probe.range(60, 61), Probe.range(62, 63)));
        assertTrue(adjacent.clientbound(Probe.message(60, 1)));
        assertTrue(adjacent.clientbound(Probe.message(62, 1)));
    }

    @Test
    void rangesOverlappingProjectionMessagesOrOutsideTheIdSpaceAreRejected() {
        for (ViewStreamMessageType type : ViewStreamMessageType.values()) {
            assertThrows(IllegalArgumentException.class, () -> new ViewStreamCodec(List.of(Probe.range(type.id(), type.id()))),
                type.name());
        }
        assertThrows(IllegalArgumentException.class, () -> new ViewStreamCodec(List.of(Probe.range(62, 60))));
        assertThrows(IllegalArgumentException.class, () -> new ViewStreamCodec(List.of(Probe.range(-1, 0))));
        assertThrows(IllegalArgumentException.class, () -> new ViewStreamCodec(List.of(Probe.range(250, 256))));
    }

    @Test
    void codecOffersTheUnionOfExtensionCapabilities() {
        ViewStreamCodec codec = new ViewStreamCodec(List.of(Probe.range(60, 61), TestEffects.INSTANCE));
        assertEquals(Probe.range(60, 61).capabilities() | TestEffects.CAPABILITY, codec.capabilities());
        assertEquals(ViewStreamCapability.NONE, new ViewStreamCodec(List.of()).capabilities());
    }

    @Test
    void namesCoverProjectionExtensionAndUnknownIds() {
        ViewStreamCodec codec = new ViewStreamCodec(List.of(Probe.range(60, 62)));
        assertEquals("PALETTE", codec.name(ViewStreamMessageType.PALETTE.id()));
        assertEquals("PROBE(61)", codec.name(61));
        assertEquals("UNKNOWN(63)", codec.name(63));
        assertEquals("PROBE(60)", codec.name(Probe.message(60, 1)));
    }

    @Test
    void coalesceMergesRunsPerExtensionInOrder() throws ViewStreamProtocolException {
        ViewStreamCodec codec = new ViewStreamCodec(List.of(TestEffects.INSTANCE, Probe.range(60, 62)));
        List<ViewStreamMessage.Extension> coalesced = codec.coalesce(List.of(TestEffects.burst("a"), TestEffects.burst("b"),
            Probe.message(60, 1), TestEffects.burst("c")));
        assertEquals(3, coalesced.size());
        assertEquals(List.of("a", "b"), TestEffects.payload(coalesced.get(0)).names());
        assertEquals(Probe.message(60, 1), coalesced.get(1));
        assertEquals(List.of("c"), TestEffects.payload(coalesced.get(2)).names());
        assertThrows(ViewStreamProtocolException.class, () -> codec.coalesce(List.of(new ViewStreamMessage.Extension(63, "x"))));
    }

    @Test
    void projectionMessagesKeepTheirTypeAndIdentity() throws ViewStreamProtocolException {
        ViewStreamCodec codec = new ViewStreamCodec(List.of(Probe.range(60, 62)));
        ViewStreamMessage.PlateEnd end = new ViewStreamMessage.PlateEnd(7, 3);
        byte[] frame = codec.encodeS2C(end, 1, ViewStreamLimits.FLAG_LAST);
        assertArrayEquals(ViewStreamFixtures.CODEC.encodeS2C(end, 1, ViewStreamLimits.FLAG_LAST), frame);
        ViewStreamMessage decoded = codec.decodeS2C(frame, ViewStreamCapability.ALL).message();
        assertInstanceOf(ViewStreamMessage.Projection.class, decoded);
        assertEquals(ViewStreamMessageType.PLATE_END.id(), decoded.id());
        assertFalse(codec.serverbound(end));
        assertTrue(codec.clientbound(end));
    }

    private static final class Probe implements ViewStreamExtension<Probe.Value> {
        private final int first;
        private final int last;

        private Probe(int first, int last) {
            this.first = first;
            this.last = last;
        }

        static Probe range(int first, int last) {
            return new Probe(first, last);
        }

        static ViewStreamMessage.Extension message(int id, int value) {
            return new ViewStreamMessage.Extension(id, new Value(id, value));
        }

        @Override
        public int firstId() {
            return first;
        }

        @Override
        public int lastId() {
            return last;
        }

        @Override
        public boolean serverbound(int id) {
            return id == first + 1;
        }

        @Override
        public boolean clientbound(int id) {
            return id == first;
        }

        @Override
        public Class<Value> type() {
            return Value.class;
        }

        @Override
        public String name(int id) {
            return "PROBE(" + id + ")";
        }

        @Override
        public int id(Value message) {
            return message.id();
        }

        @Override
        public void encode(Value message, ViewStreamWriter out) throws ViewStreamProtocolException {
            if (message.id() < first || message.id() > first + 1) {
                throw new ViewStreamProtocolException("probe cannot encode " + message.id());
            }
            out.i32(message.value());
        }

        @Override
        public Value decode(int id, ViewStreamReader in) throws ViewStreamProtocolException {
            if (id < first || id > first + 1) {
                throw new ViewStreamProtocolException("probe cannot decode " + id);
            }
            return new Value(id, in.i32());
        }

        @Override
        public long capabilities() {
            return ViewStreamCapability.extension(first % ViewStreamCapability.EXTENSION_BITS);
        }

        record Value(int id, int value) {
        }
    }
}
