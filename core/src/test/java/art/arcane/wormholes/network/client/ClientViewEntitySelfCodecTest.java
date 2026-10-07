package art.arcane.wormholes.network.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import art.arcane.optics.stream.ViewStreamProtocolException;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.stream.ViewStreamMessageType;

final class ClientViewEntitySelfCodecTest {
    private static final UUID PROJECTED_ID = new UUID(0x1122334455667788L, 0x1020304050607080L);

    @Test
    void exactProjectedIdentityUsesOnlyTheUuidBody() throws ViewStreamProtocolException {
        ClientViewMessage.EntitySelf message = new ClientViewMessage.EntitySelf(PROJECTED_ID);
        byte[] frame = ClientViewCodec.encodeS2C(message, 24, ViewStreamLimits.FLAG_LAST, true);
        assertEquals(ViewStreamLimits.S2C_HEADER_BYTES + 16, frame.length);
        ByteBuffer body = ByteBuffer.wrap(frame, ViewStreamLimits.S2C_HEADER_BYTES, 16).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(PROJECTED_ID.getMostSignificantBits(), body.getLong());
        assertEquals(PROJECTED_ID.getLeastSignificantBits(), body.getLong());
        ClientViewCodec.S2CFrame decoded = ClientViewCodec.decodeS2C(frame, ViewStreamCapability.ALL);
        assertEquals(24, decoded.seq());
        assertEquals(ViewStreamLimits.FLAG_LAST, decoded.flags());
        assertEquals(message, decoded.message());
    }

    @Test
    void identityIsRequiredAndCannotBeSentByTheClient() {
        assertThrows(NullPointerException.class, () -> new ClientViewMessage.EntitySelf(null));
        assertThrows(ViewStreamProtocolException.class,
            () -> ClientViewCodec.encodeC2S(new ClientViewMessage.EntitySelf(PROJECTED_ID)));
        byte[] clientboundType = new byte[ViewStreamLimits.C2S_HEADER_BYTES + 16];
        clientboundType[0] = (byte) ViewStreamMessageType.ENTITY_SELF.id();
        assertThrows(ViewStreamProtocolException.class, () -> ClientViewCodec.decodeC2S(clientboundType));
    }

    @Test
    void malformedUuidBodiesAreRejected() throws ViewStreamProtocolException {
        byte[] frame = ClientViewCodec.encodeS2C(new ClientViewMessage.EntitySelf(PROJECTED_ID), 24, 0);
        for (int length : new int[]{ViewStreamLimits.S2C_HEADER_BYTES, frame.length - 1, frame.length + 1}) {
            byte[] invalid = Arrays.copyOf(frame, length);
            assertThrows(ViewStreamProtocolException.class,
                () -> ClientViewCodec.decodeS2C(invalid, ViewStreamCapability.ALL));
        }
    }
}
