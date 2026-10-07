package art.arcane.optics.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

final class ViewStreamHandshakeCapabilityTest {
    private static final long PARENT = ViewStreamCapability.extension(3);
    private static final long CHILD = ViewStreamCapability.extension(4);
    private static final long GRANDCHILD = ViewStreamCapability.extension(5);
    private static final long BASE = ViewStreamCapability.PLATES.mask();
    private static final ViewStreamCodec CODEC = new ViewStreamCodec(List.of(new Declared(60, PARENT | CHILD | GRANDCHILD,
        Map.of(CHILD, PARENT | ViewStreamCapability.MESH_RENDER.mask(), GRANDCHILD, CHILD))));

    @Test
    void extensionCapabilitiesLiveInTheReservedUpperHalf() {
        assertEquals(1L << 32, ViewStreamCapability.extension(0));
        assertEquals(1L << 63, ViewStreamCapability.extension(ViewStreamCapability.EXTENSION_BITS - 1));
        assertThrows(IllegalArgumentException.class, () -> ViewStreamCapability.extension(-1));
        assertThrows(IllegalArgumentException.class, () -> ViewStreamCapability.extension(ViewStreamCapability.EXTENSION_BITS));
        for (ViewStreamCapability capability : ViewStreamCapability.values()) {
            assertTrue(capability.bit() < ViewStreamCapability.FIRST_EXTENSION_BIT, capability.name());
        }
        assertEquals(ViewStreamCapability.EXTENSIONS, ViewStreamCapability.ALL & ViewStreamCapability.EXTENSIONS);
        assertEquals(PARENT | CHILD | GRANDCHILD, CODEC.capabilities());
    }

    @Test
    void declaredPrerequisitesDropAnExtensionCapabilityWhoseParentsAreMissing() {
        long[] parents = {PARENT, ViewStreamCapability.MESH_RENDER.mask()};
        for (int serverMask = 0; serverMask < 4; serverMask++) {
            for (int clientMask = 0; clientMask < 4; clientMask++) {
                long server = BASE | CHILD | parents(parents, serverMask);
                long client = BASE | CHILD | parents(parents, clientMask);
                long accepted = accept(server, client);
                boolean expected = (serverMask & clientMask) == 3;
                assertEquals(expected, (accepted & CHILD) != 0L, "server " + serverMask + " client " + clientMask);
                assertEquals((serverMask & clientMask & 1) != 0, (accepted & PARENT) != 0L, "a parent never depends on its child");
            }
        }
    }

    @Test
    void prerequisitesSettleThroughChains() {
        long everything = BASE | PARENT | CHILD | GRANDCHILD | ViewStreamCapability.MESH_RENDER.mask();
        assertEquals(everything, accept(everything, everything));
        long noParent = everything & ~PARENT;
        long accepted = accept(everything, noParent);
        assertEquals(0L, accepted & (PARENT | CHILD | GRANDCHILD));
        assertEquals(everything & ~(PARENT | CHILD | GRANDCHILD), accepted);
    }

    @Test
    void extensionCapabilitiesAreNeverGrantedUnlessBothPeersOfferThem() {
        long parents = BASE | PARENT | ViewStreamCapability.MESH_RENDER.mask();
        assertTrue((accept(parents | CHILD, parents | CHILD) & CHILD) != 0L);
        assertFalse((accept(parents, parents | CHILD) & CHILD) != 0L);
        assertFalse((accept(parents | CHILD, parents) & CHILD) != 0L);
    }

    @Test
    void entitySelfKeepsItsParent() {
        long self = ViewStreamCapability.ENTITY_SELF.mask();
        assertFalse(ViewStreamCapability.ENTITY_SELF.in(accept(BASE | self, BASE | self)));
        long frames = ViewStreamCapability.ENTITY_FRAMES.mask();
        assertTrue(ViewStreamCapability.ENTITY_SELF.in(accept(BASE | self | frames, BASE | self | frames)));
    }

    @Test
    void extensionsMayOnlyClaimUnownedBitsInTheExtensionRange() {
        assertThrows(IllegalArgumentException.class,
            () -> new ViewStreamCodec(List.of(new Declared(60, ViewStreamCapability.MESH_RENDER.mask(), Map.of()))));
        assertThrows(IllegalArgumentException.class,
            () -> new ViewStreamCodec(List.of(new Declared(60, PARENT, Map.of()), new Declared(61, PARENT | CHILD, Map.of()))));
        ViewStreamCodec shared = new ViewStreamCodec(List.of(new Declared(60, PARENT, Map.of()), new Declared(61, CHILD, Map.of(CHILD, PARENT))));
        assertEquals(PARENT | CHILD, shared.capabilities());
        assertEquals(BASE, shared.settle(BASE | CHILD));
    }

    private static long parents(long[] parents, int mask) {
        long caps = ViewStreamCapability.NONE;
        for (int i = 0; i < parents.length; i++) {
            if ((mask & (1 << i)) != 0) {
                caps |= parents[i];
            }
        }
        return caps;
    }

    private static long accept(long serverCaps, long clientCaps) {
        ViewStreamHandshake.Policy policy = new ViewStreamHandshake.Policy(true, 4325, serverCaps, ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES,
            100, ViewStreamLimits.DEFAULT_TICK_RATE, ViewStreamLimits.DEFAULT_ACK_WINDOW_FRAMES, false);
        ViewStreamHandshake handshake = new ViewStreamHandshake(policy, CODEC, 0L, () -> 1, () -> 1L);
        ViewStreamMessage.Offer offer = handshake.offer(0L);
        ViewStreamMessage.Hello hello = ViewStreamHandshake.clientHello(offer, 4325, clientCaps, ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES,
            256, 0L, "fabric");
        ViewStreamHandshake.Result result = handshake.onHello(hello, 1L, true);
        assertTrue(result.accepted());
        return ((ViewStreamMessage.Accept) result.reply()).caps();
    }

    private record Declared(int id, long capabilities, Map<Long, Long> prerequisites) implements ViewStreamExtension<Object> {
        @Override
        public int firstId() {
            return id;
        }

        @Override
        public int lastId() {
            return id;
        }

        @Override
        public boolean serverbound(int message) {
            return false;
        }

        @Override
        public boolean clientbound(int message) {
            return false;
        }

        @Override
        public Class<Object> type() {
            return Object.class;
        }

        @Override
        public String name(int message) {
            return "DECLARED";
        }

        @Override
        public int id(Object message) {
            return id;
        }

        @Override
        public void encode(Object message, ViewStreamWriter out) throws ViewStreamProtocolException {
            throw new ViewStreamProtocolException("No declared messages");
        }

        @Override
        public Object decode(int message, ViewStreamReader in) throws ViewStreamProtocolException {
            throw new ViewStreamProtocolException("No declared messages");
        }

        @Override
        public long requires(long capability) {
            return prerequisites.getOrDefault(capability, ViewStreamCapability.NONE);
        }
    }
}
