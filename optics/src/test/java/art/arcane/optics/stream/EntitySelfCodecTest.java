package art.arcane.optics.stream;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class EntitySelfCodecTest {
    private static final UUID PROJECTED_ID = new UUID(0x1122334455667788L, 0x1020304050607080L);

    @Test
    void exactProjectedIdentityUsesOnlyTheUuidBody() throws ViewStreamProtocolException {
        ViewStreamMessage.EntitySelf message = new ViewStreamMessage.EntitySelf(PROJECTED_ID);
        byte[] frame = ViewStreamFixtures.CODEC.encodeS2C(message, 24, ViewStreamLimits.FLAG_LAST, true);
        assertEquals(ViewStreamLimits.S2C_HEADER_BYTES + 16, frame.length);
        ByteBuffer body = ByteBuffer.wrap(frame, ViewStreamLimits.S2C_HEADER_BYTES, 16).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(PROJECTED_ID.getMostSignificantBits(), body.getLong());
        assertEquals(PROJECTED_ID.getLeastSignificantBits(), body.getLong());
        ViewStreamCodec.S2CFrame decoded = ViewStreamFixtures.CODEC.decodeS2C(frame, ViewStreamCapability.ALL);
        assertEquals(24, decoded.seq());
        assertEquals(ViewStreamLimits.FLAG_LAST, decoded.flags());
        assertEquals(message, decoded.message());
    }

    @Test
    void identityIsRequiredAndCannotBeSentByTheClient() {
        assertThrows(NullPointerException.class, () -> new ViewStreamMessage.EntitySelf(null));
        assertThrows(ViewStreamProtocolException.class,
            () -> ViewStreamFixtures.CODEC.encodeC2S(new ViewStreamMessage.EntitySelf(PROJECTED_ID)));
        byte[] clientboundType = new byte[ViewStreamLimits.C2S_HEADER_BYTES + 16];
        clientboundType[0] = (byte) ViewStreamMessageType.ENTITY_SELF.id();
        assertThrows(ViewStreamProtocolException.class, () -> ViewStreamFixtures.CODEC.decodeC2S(clientboundType));
    }

    @Test
    void malformedUuidBodiesAreRejected() throws ViewStreamProtocolException {
        byte[] frame = ViewStreamFixtures.CODEC.encodeS2C(new ViewStreamMessage.EntitySelf(PROJECTED_ID), 24, 0);
        for (int length : new int[]{ViewStreamLimits.S2C_HEADER_BYTES, frame.length - 1, frame.length + 1}) {
            byte[] invalid = Arrays.copyOf(frame, length);
            assertThrows(ViewStreamProtocolException.class,
                () -> ViewStreamFixtures.CODEC.decodeS2C(invalid, ViewStreamCapability.ALL));
        }
    }
}
