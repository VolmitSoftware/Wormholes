package art.arcane.wormholes.network.client;

import art.arcane.optics.stream.ViewStreamCodec;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.stream.ViewStreamProtocolException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ArrivalExtensionTest {
    private final ViewStreamCodec codec = new ViewStreamCodec(List.of(ArrivalExtension.INSTANCE));
    private final UUID token = UUID.randomUUID();

    @Test
    public void preparationRetainsWorldIdentityAndRegistryFingerprint() throws Exception {
        ArrivalMessage.Prepare message = new ArrivalMessage.Prepare(token, 1, UUID.randomUUID(),
            new TravelMessage.TravelWorld("minecraft:overworld", "minecraft:overworld", 43, false, false, 63, -64, 384),
            new TravelMessage.TravelPose(1024.5, 70, -900.5, 90, 0), 2, "0123456789abcdef".repeat(4), 28000, 18000, 0.5F, 0.25F, 100);
        ViewStreamMessage.Extension frame = ArrivalExtension.INSTANCE.wrap(message);
        assertEquals(frame, codec.decodeS2C(codec.encodeS2C(frame, 1, 0), ClientViewExtensions.ARRIVAL_PREPARATION).message());
        assertEquals(25, message.chunkCount());
        assertEquals(1, message.renderRadius());
    }

    @Test
    public void chunkFragmentsPreserveBytesAndCannotExceedBound() throws Exception {
        byte[] payload = new byte[]{3, 7, 12};
        ArrivalMessage.Chunk message = new ArrivalMessage.Chunk(token, 1, 12, -24, 0, 1, payload.length, payload);
        payload[0] = 99;
        ViewStreamMessage.Extension decoded = (ViewStreamMessage.Extension) codec.decodeS2C(
            codec.encodeS2C(ArrivalExtension.INSTANCE.wrap(message), 2, 0), ClientViewExtensions.ARRIVAL_PREPARATION).message();
        assertArrayEquals(new byte[]{3, 7, 12}, ((ArrivalMessage.Chunk) decoded.payload()).payload());
        assertThrows(IllegalArgumentException.class, () -> new ArrivalMessage.Chunk(token, 1, 12, -24, 0, 1,
            ArrivalMessage.MAX_CHUNK_BYTES + 1, new byte[]{1}));
    }

    @Test
    public void readinessIsServerboundAndNeedsItsOwnCapability() throws Exception {
        ArrivalMessage.Ready message = new ArrivalMessage.Ready(token, 1, ArrivalMessage.Readiness.RENDER);
        ViewStreamMessage.Extension frame = ArrivalExtension.INSTANCE.wrap(message);
        assertEquals(frame, codec.decodeC2S(codec.encodeC2S(frame)));
        assertThrows(ViewStreamProtocolException.class, () -> codec.encodeS2C(frame, 0, 0));
        assertEquals(ClientViewExtensions.ARRIVAL_PREPARATION, ArrivalExtension.INSTANCE.capabilities());
        assertEquals(0, ArrivalExtension.INSTANCE.requires(ClientViewExtensions.ARRIVAL_PREPARATION));
    }

    @Test
    public void invalidPreparationCannotAllocateUnboundedWorlds() {
        assertThrows(IllegalArgumentException.class, () -> new ArrivalMessage.Prepare(token, 1, UUID.randomUUID(),
            new TravelMessage.TravelWorld("minecraft:overworld", "minecraft:overworld", 0, false, false, 63, -64, 384),
            new TravelMessage.TravelPose(0, 64, 0, 0, 0), 17, "0123456789abcdef".repeat(4), 0, 0, 0, 0, 100));
        assertThrows(IllegalArgumentException.class, () -> new ArrivalMessage.Ready(token, 0, ArrivalMessage.Readiness.DATA));
    }
}
